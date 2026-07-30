(ns bitcoin.node.peer
  "Bounded JVM Bitcoin P2P client for read-only consensus synchronization.

  It performs version/verack, answers ping, and requests headers in protocol
  batches. Transaction relay, wallet, mempool, and mining commands are absent."
  (:require [bitcoin.consensus.codec :as codec]
            [clojure.string :as str]
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
   network magic peer-version timeout-ms]
  java.io.Closeable
  (close [_] (.close socket)))

(def minimum-peer-version 31800)
(def node-network-service 1)
(def node-network-limited-service 1024)
(def maximum-service-mask 18446744073709551615N)
(def witness-block-inventory-type 0x40000002)

(defn- fail! [type message data]
  (codec/fail! type message data))

(defn- deadline-nanos [timeout-ms]
  (+ (System/nanoTime) (* (long timeout-ms) 1000000)))

(defn- set-read-timeout!
  [connection deadline operation]
  (let [remaining (- deadline (System/nanoTime))]
    (when-not (pos? remaining)
      (fail! :bitcoin.node/peer-timeout
             "Bitcoin peer exceeded the overall request deadline."
             {:operation operation}))
    (.setSoTimeout
     ^Socket (:socket connection)
     (int (max 1 (quot (+ remaining 999999) 1000000))))))

(defn- read-exactly-until
  [connection deadline operation length]
  (let [bytes (byte-array length)]
    (try
      (loop [offset 0]
        (if (= offset length)
          (mapv #(bit-and 0xff %) bytes)
          (do
            (set-read-timeout! connection deadline operation)
            (let [read
                  (.read ^DataInputStream (:input connection)
                         bytes offset (- length offset))]
              (if (neg? read)
                (fail! :bitcoin.node/peer-eof
                       "Bitcoin peer closed a partial message."
                       {:expected length :received offset})
                (recur (+ offset read)))))))
      (catch EOFException _
        (fail! :bitcoin.node/peer-eof
               "Bitcoin peer closed a partial message."
               {:expected length})))))

(defn- write-message!
  [^DataOutputStream output magic command payload]
  (let [message (protocol/encode-message magic command payload)]
    (.write output (byte-array (map unchecked-byte message)))
    (.flush output)))

(defn- read-message-until!
  [connection deadline operation expected-magic]
  (let [header
        (protocol/decode-message-header
         (read-exactly-until
          connection deadline operation protocol/header-size))
        length (:length header)]
    (when-not (= expected-magic (:magic header))
      (fail! :bitcoin.node/peer-network-mismatch
             "Bitcoin peer sent another network's magic."
             {:expected expected-magic :actual (:magic header)}))
    (when (> length protocol/max-protocol-payload-bytes)
      (fail! :bitcoin.node/peer-oversized-message
             "Bitcoin peer declared an oversized payload."
             {:length length :limit protocol/max-protocol-payload-bytes}))
    (let [payload
          (read-exactly-until connection deadline operation length)]
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
  `:user-agent`, and an optional `:required-services` bit mask. The returned
  connection must be closed with `close!`."
  [{:keys [host port network timeout-ms start-height user-agent
           required-services]
    :or {host "127.0.0.1" network :mainnet timeout-ms 10000
         start-height 0 user-agent "/kotoba-lang:bitcoin-node:0.9.0/"
         required-services 0}}]
  (let [base-config (get network-configuration network)]
    (when-not base-config
      (fail! :bitcoin.node/peer-network
             "Unsupported Bitcoin peer network." {:network network}))
    (let [{:keys [magic port]}
          (assoc base-config :port (or port (:port base-config)))]
    (when-not (and (integer? timeout-ms) (pos? timeout-ms)
                   (<= timeout-ms Integer/MAX_VALUE)
                   (integer? required-services)
                   (<= 0 required-services maximum-service-mask))
      (fail! :bitcoin.node/peer-configuration
             "Peer timeout or required service mask is invalid."
             {:timeout-ms timeout-ms
              :required-services required-services}))
    (let [socket (Socket.)]
      (try
        (.connect socket (InetSocketAddress. ^String host (int port))
                  (int timeout-ms))
        (.setSoTimeout socket (int timeout-ms))
        (.setTcpNoDelay socket true)
        (let [input (DataInputStream. (.getInputStream socket))
              output (DataOutputStream. (.getOutputStream socket))
              base (->PeerConnection
                    socket input output network magic nil timeout-ms)
              deadline (deadline-nanos timeout-ms)]
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
                (when-not
                 (= (biginteger required-services)
                    (.and (biginteger required-services)
                          (biginteger (:services peer-version))))
                  (fail!
                   :bitcoin.node/peer-required-services
                   "Bitcoin peer lacks required advertised services."
                   {:required required-services
                    :actual (:services peer-version)}))
                (assoc base :peer-version peer-version))
              (let [message
                    (read-message-until!
                     base deadline :handshake magic)
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
  (let [deadline (deadline-nanos (:timeout-ms connection))]
    (try
      (loop []
        (let [message
              (read-message-until!
               connection deadline :headers (:magic connection))
              _ (handle-control! connection message)]
          (if (= "headers" (:command message))
            (protocol/decode-headers-payload (:payload message))
            ;; Unknown announcements are deliberately ignored; this client
            ;; never changes behavior based on inv/addr/feefilter traffic.
            (recur))))
      (catch SocketTimeoutException error
        (throw
         (ex-info "Bitcoin peer headers request timed out."
                  {:type :bitcoin.node/peer-timeout
                   :operation :headers}
                  error))))))

(defn get-block!
  "Fetch one witness-capable raw block by its natural-order 32-byte hash.

  The response remains bounded by the transport payload limit and its header
  hash must match the request before raw bytes are returned for full consensus
  validation by `bitcoin.node.disk-consensus/accept-block!`."
  [connection block-hash]
  (when-not (= 32 (count block-hash))
    (fail! :bitcoin.node/peer-block-hash
           "Block request requires one natural-order 32-byte hash."
           {:length (count block-hash)}))
  (write-message!
   (:output connection) (:magic connection) "getdata"
   (vec
    (concat
     (protocol/encode-varint 1)
     (protocol/uint-le->bytes witness-block-inventory-type 4)
     block-hash)))
  (let [deadline (deadline-nanos (:timeout-ms connection))]
    (try
      (loop []
        (let [message
              (read-message-until!
               connection deadline :block (:magic connection))
              _ (handle-control! connection message)]
          (case (:command message)
            "block"
            (let [payload (:payload message)]
              (when (< (count payload) protocol/block-header-size)
                (fail! :bitcoin.node/peer-malformed-block
                       "Bitcoin peer returned a truncated block."
                       {:length (count payload)}))
              (let [actual
                    (:hash
                     (protocol/decode-block-header
                      (subvec payload 0 protocol/block-header-size)))]
                (when-not (= block-hash actual)
                  (fail! :bitcoin.node/peer-unrequested-block
                         "Bitcoin peer returned a different block."
                         {:requested block-hash :actual actual}))
                payload))

            "notfound"
            (fail! :bitcoin.node/peer-block-not-found
                   "Bitcoin peer does not have the requested block." {})

            (recur))))
      (catch SocketTimeoutException error
        (throw
         (ex-info "Bitcoin peer block request timed out."
                  {:type :bitcoin.node/peer-timeout
                   :operation :block}
                  error))))))

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

(defn- peer-summary [configuration]
  (let [network (or (:network configuration) :mainnet)]
    {:host (:host configuration)
     :port (or (:port configuration)
               (get-in network-configuration [network :port]))
     :network network}))

(defn- failure-summary [configuration error elapsed-ms]
  {:peer (peer-summary configuration)
   :type (or (:type (ex-data error)) :bitcoin.node/peer-error)
   :message (.getMessage ^Throwable error)
   :elapsed-ms elapsed-ms})

(defn sync-headers-from-peers!
  "Synchronize through a bounded, ordered peer set with durable failover.

  `locator-fn` is called after each successful handshake, so a replacement
  peer resumes from batches already validated and committed by an earlier
  peer. `:required-successes` can be greater than one to compare independently
  reported tips; differing reports are surfaced as `:disagreement?` while the
  validating callback remains the sole fork-choice authority."
  [peer-configurations locator-fn accept-batch!
   {:keys [attempts-per-peer required-successes max-batches]
    :or {attempts-per-peer 1 required-successes 1 max-batches 10000}}]
  (let [peers (vec peer-configurations)]
    (when-not (and (<= 1 (count peers) 32)
                   (every? #(and (map? %)
                                 (string? (:host %))
                                 (not (str/blank? (:host %))))
                           peers)
                   (= (count peers)
                      (count (into #{} (map peer-summary) peers))))
      (fail! :bitcoin.node/peer-set
             "Peer failover requires 1..32 unique configurations with explicit hosts."
             {:count (count peers)}))
    (when-not (and (ifn? locator-fn) (ifn? accept-batch!))
      (fail! :bitcoin.node/peer-callback
             "Peer failover requires locator and validation callbacks." {}))
    (when-not (and (integer? attempts-per-peer)
                   (<= 1 attempts-per-peer 8)
                   (integer? required-successes)
                   (<= 1 required-successes (count peers)))
      (fail! :bitcoin.node/peer-configuration
             "Peer attempts and required successes are outside their bounds."
             {:attempts-per-peer attempts-per-peer
              :required-successes required-successes
              :peer-count (count peers)}))
    (loop [remaining (vec (mapcat identity
                                  (repeat attempts-per-peer peers)))
           observations []
           failures []]
      (if-let [configuration (first remaining)]
        (if (some #(= (peer-summary configuration) (:peer %))
                  observations)
          (recur (subvec remaining 1) observations failures)
          (let [started (System/nanoTime)
                elapsed-ms
                #(/ (- (System/nanoTime) started) 1e6)
                attempt
                (try
                  (let [connection (connect! configuration)]
                    (try
                      (let [result
                            (sync-headers!
                             connection (locator-fn) accept-batch!
                             {:max-batches max-batches})
                            tip (:locator result)]
                        {:observation
                         {:peer (peer-summary configuration)
                          :start-height
                          (get-in connection [:peer-version :start-height])
                          :status (:status result)
                          :batches (:batches result)
                          :accepted (:accepted result)
                          :elapsed-ms (elapsed-ms)
                          :services
                          (get-in connection [:peer-version :services])
                          :reported-tip
                          (when tip (protocol/natural-hash->hex tip))}})
                      (finally
                        (close! connection))))
                  (catch Exception error
                    {:failure
                     (failure-summary
                      configuration error (elapsed-ms))}))]
            (if-let [observation (:observation attempt)]
              (let [next-observations (conj observations observation)]
                (if (= required-successes (count next-observations))
                  (let [tips (into #{} (keep :reported-tip)
                                   next-observations)]
                    {:status (:status observation)
                     :successful-peers (count next-observations)
                     :attempted (+ (count next-observations)
                                   (count failures))
                     :disagreement? (> (count tips) 1)
                     :observations next-observations
                     :failures failures})
                  (recur (subvec remaining 1)
                         next-observations failures)))
              (recur (subvec remaining 1) observations
                     (conj failures (:failure attempt))))))
        (fail! :bitcoin.node/peer-set-exhausted
               "Every bounded peer synchronization attempt failed."
               {:attempted (+ (count observations) (count failures))
                :successful-peers (count observations)
                :required-successes required-successes
                :observations observations
                :failures failures})))))
