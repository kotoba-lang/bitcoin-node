(ns bitcoin.node.peer
  "Bounded JVM Bitcoin P2P client for read-only consensus synchronization.

  It performs version/verack, answers ping, and requests headers in protocol
  batches. Transaction relay, wallet, mempool, and mining commands are absent."
  (:require [bitcoin.consensus.codec :as codec]
            [kotobase.bitcoin.protocol :as protocol])
  (:import [java.io DataInputStream DataOutputStream EOFException]
           [java.net InetSocketAddress Socket SocketTimeoutException]
           [java.security SecureRandom]))

(def network-configuration
  {:mainnet {:magic [0xf9 0xbe 0xb4 0xd9] :port 8333}
   :testnet {:magic [0x0b 0x11 0x09 0x07] :port 18333}
   :testnet4 {:magic [0x1c 0x16 0x3f 0x28] :port 48333}
   :signet {:magic [0x0a 0x03 0xcf 0x40] :port 38333}
   :regtest {:magic [0xfa 0xbf 0xb5 0xda] :port 18444}})

(defrecord PeerConnection
  [^Socket socket ^DataInputStream input ^DataOutputStream output
   network magic peer-version]
  java.io.Closeable
  (close [_] (.close socket)))

(def minimum-peer-version 31800)

(defn- fail! [type message data]
  (codec/fail! type message data))

(defn- read-exactly [^DataInputStream input length]
  (let [bytes (byte-array length)]
    (try
      (.readFully input bytes)
      (mapv #(bit-and 0xff %) bytes)
      (catch EOFException _
        (fail! :bitcoin.node/peer-eof
               "Bitcoin peer closed a partial message."
               {:expected length})))))

(defn- write-message!
  [^DataOutputStream output magic command payload]
  (let [message (protocol/encode-message magic command payload)]
    (.write output (byte-array (map unchecked-byte message)))
    (.flush output)))

(defn- read-message!
  [^DataInputStream input expected-magic]
  (let [header
        (protocol/decode-message-header
         (read-exactly input protocol/header-size))
        length (:length header)]
    (when-not (= expected-magic (:magic header))
      (fail! :bitcoin.node/peer-network-mismatch
             "Bitcoin peer sent another network's magic."
             {:expected expected-magic :actual (:magic header)}))
    (when (> length protocol/max-protocol-payload-bytes)
      (fail! :bitcoin.node/peer-oversized-message
             "Bitcoin peer declared an oversized payload."
             {:length length :limit protocol/max-protocol-payload-bytes}))
    (let [payload (read-exactly input length)]
      (when-not (protocol/checksum-valid? header payload)
        (fail! :bitcoin.node/peer-checksum
               "Bitcoin peer message checksum is invalid."
               {:command (:command header)}))
      {:command (:command header) :payload payload})))

(defn- positive-nonce []
  (bit-and (.nextLong (SecureRandom.)) Long/MAX_VALUE))

(defn- handle-control!
  [connection {:keys [command payload]}]
  (case command
    "ping"
    (do
      (write-message! (:output connection) (:magic connection)
                      "pong" payload)
      :control)

    "version"
    (do
      (write-message! (:output connection) (:magic connection)
                      "verack" [])
      [:version (protocol/decode-version-payload payload)])

    "verack" :verack
    nil))

(defn connect!
  "Connect and complete mutual version/verack.

  Options: `:host`, `:port`, `:network`, `:timeout-ms`, `:start-height`,
  and `:user-agent`. The returned connection must be closed with `close!`."
  [{:keys [host port network timeout-ms start-height user-agent]
    :or {host "127.0.0.1" network :mainnet timeout-ms 10000
         start-height 0 user-agent "/kotoba-lang:bitcoin-node:0.9.0/"}}]
  (let [base-config (get network-configuration network)]
    (when-not base-config
      (fail! :bitcoin.node/peer-network
             "Unsupported Bitcoin peer network." {:network network}))
    (let [{:keys [magic port]}
          (assoc base-config :port (or port (:port base-config)))]
    (when-not (and (integer? timeout-ms) (pos? timeout-ms))
      (fail! :bitcoin.node/peer-configuration
             "Peer timeout must be a positive integer."
             {:timeout-ms timeout-ms}))
    (let [socket (Socket.)]
      (try
        (.connect socket (InetSocketAddress. ^String host (int port))
                  (int timeout-ms))
        (.setSoTimeout socket (int timeout-ms))
        (.setTcpNoDelay socket true)
        (let [input (DataInputStream. (.getInputStream socket))
              output (DataOutputStream. (.getOutputStream socket))
              base (->PeerConnection
                    socket input output network magic nil)]
          (write-message!
           output magic "version"
           (protocol/encode-version-payload
            {:timestamp (quot (System/currentTimeMillis) 1000)
             :nonce (positive-nonce)
             :recv-addr {:ip host :port port}
             :user-agent user-agent
             :start-height start-height :relay? false}))
          (loop [peer-version nil verack? false]
            (if (and peer-version verack?)
              (do
                (when (< (:version peer-version) minimum-peer-version)
                  (fail! :bitcoin.node/peer-version
                         "Bitcoin peer does not support getheaders."
                         {:minimum minimum-peer-version
                          :actual (:version peer-version)}))
                (assoc base :peer-version peer-version))
              (let [message (read-message! input magic)
                    result (handle-control! base message)]
                (cond
                  (and (vector? result) (= :version (first result)))
                  (recur (second result) verack?)

                  (= :verack result)
                  (recur peer-version true)

                  :else
                  (recur peer-version verack?))))))
        (catch SocketTimeoutException error
          (.close socket)
          (throw
           (ex-info "Bitcoin peer handshake timed out."
                    {:type :bitcoin.node/peer-timeout
                     :host host :port port}
                    error)))
        (catch Throwable error
          (.close socket)
          (throw error)))))))

(defn close! [connection]
  (.close ^java.io.Closeable connection)
  nil)

(defn get-headers!
  "Request at most 2,000 headers after the supplied natural-order locators."
  [connection locator-hashes]
  (when-not (and (sequential? locator-hashes)
                 (<= 1 (count locator-hashes) 101)
                 (every? #(= 32 (count %)) locator-hashes))
    (fail! :bitcoin.node/peer-locator
           "Header locator must contain 1..101 natural-order hashes."
           {:count (count locator-hashes)}))
  (write-message!
   (:output connection) (:magic connection) "getheaders"
   (protocol/encode-getheaders-payload
    {:locator-hashes locator-hashes}))
  (try
    (loop []
      (let [message (read-message! (:input connection)
                                   (:magic connection))
            _ (handle-control! connection message)]
        (if (= "headers" (:command message))
          (protocol/decode-headers-payload (:payload message))
          ;; Unknown announcements are deliberately ignored; this client never
          ;; changes behavior based on inv/addr/feefilter traffic.
          (recur))))
    (catch SocketTimeoutException error
      (throw
       (ex-info "Bitcoin peer headers request timed out."
                {:type :bitcoin.node/peer-timeout}
                error)))))

(defn sync-headers!
  "Drive sequential getheaders batches through a validating batch callback.

  `accept-batch!` receives decoded headers and must reject invalid linkage,
  PoW, difficulty, or time context before returning. Sync stops on a short
  batch, an empty batch, or `max-batches`."
  [connection locator-hashes accept-batch!
   {:keys [max-batches] :or {max-batches 10000}}]
  (when-not (ifn? accept-batch!)
    (fail! :bitcoin.node/peer-callback
           "Header synchronization requires a validating callback." {}))
  (when-not (and (integer? max-batches) (pos? max-batches))
    (fail! :bitcoin.node/peer-configuration
           "Header batch limit must be a positive integer."
           {:max-batches max-batches}))
  (loop [locator locator-hashes batches 0 accepted 0]
    (if (= batches max-batches)
      {:status :batch-limit :batches batches :accepted accepted
       :locator (first locator)}
      (let [headers (get-headers! connection locator)
            count (count headers)]
        (if (zero? count)
          {:status :synced :batches (inc batches) :accepted accepted
           :locator (first locator)}
          (do
            (accept-batch! headers)
            (let [tip (:hash (last headers))
                  result
                  {:batches (inc batches) :accepted (+ accepted count)
                   :locator tip}]
              (if (< count protocol/max-headers-per-message)
                (assoc result :status :synced)
                (recur [tip] (inc batches) (+ accepted count))))))))))
