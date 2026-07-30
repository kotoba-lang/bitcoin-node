(ns bitcoin.node.peer-test
  (:require [bitcoin.consensus.block :as block]
            [bitcoin.node.consensus-test :as fixture]
            [bitcoin.node.peer :as peer]
            [clojure.test :refer [deftest is]]
            [kotobase.bitcoin.protocol :as protocol])
  (:import [java.io DataInputStream DataOutputStream]
           [java.net ServerSocket Socket]))

(defn- read-bytes [^DataInputStream input length]
  (let [value (byte-array length)]
    (.readFully input value)
    (mapv #(bit-and 0xff %) value)))

(defn- read-message [^DataInputStream input]
  (let [header
        (protocol/decode-message-header
         (read-bytes input protocol/header-size))
        payload (read-bytes input (:length header))]
    (is (protocol/checksum-valid? header payload))
    {:command (:command header) :payload payload}))

(defn- send-message!
  [^DataOutputStream output magic command payload]
  (.write output
          (byte-array
           (map unchecked-byte
                (protocol/encode-message magic command payload))))
  (.flush output))

(defn- send-raw! [^DataOutputStream output bytes]
  (.write output (byte-array (map unchecked-byte bytes)))
  (.flush output))

(defn- handshake-error-type [frame]
  (with-open [server (ServerSocket. 0)]
    (let [served
          (future
            (with-open [^Socket socket (.accept server)
                        input (DataInputStream. (.getInputStream socket))
                        output (DataOutputStream. (.getOutputStream socket))]
              (is (= "version" (:command (read-message input))))
              (send-raw! output frame)))]
      (let [type
            (:type
             (ex-data
              (try
                (peer/connect!
                 {:host "127.0.0.1" :port (.getLocalPort server)
                  :network :regtest :timeout-ms 5000})
                (catch clojure.lang.ExceptionInfo error error))))]
        @served
        type))))

(deftest jvm-peer-handshake-ping-and-getheaders-round-trip
  (with-open [server (ServerSocket. 0)]
    (let [magic (get-in peer/network-configuration [:regtest :magic])
          genesis
          (block/parse (fixture/hex->bytes fixture/regtest-genesis))
          block-1 (fixture/mine-regtest-block genesis 1)
          block-2 (fixture/mine-regtest-block block-1 2)
          expected [(:header block-1) (:header block-2)]
          server-result
          (future
            (with-open [^Socket socket (.accept server)
                        input (DataInputStream. (.getInputStream socket))
                        output (DataOutputStream. (.getOutputStream socket))]
              (let [version (read-message input)]
                (is (= "version" (:command version)))
                (send-message!
                 output magic "version"
                (protocol/encode-version-payload
                  {:timestamp 1 :nonce 18446744073709551615N
                   :start-height 2}))
                (send-message! output magic "verack" [])
                (is (= "verack" (:command (read-message input))))
                (let [request (read-message input)
                      decoded
                      (protocol/decode-getheaders-payload
                       (:payload request))]
                  (is (= "getheaders" (:command request)))
                  ;; Exercise control traffic while a response is pending.
                  (send-message!
                   output magic "ping"
                   (protocol/encode-ping-payload 42))
                  (is (= "pong" (:command (read-message input))))
                  (send-message!
                   output magic "headers"
                   (protocol/encode-headers-payload expected))
                  decoded))))
          connection
          (peer/connect!
           {:host "127.0.0.1" :port (.getLocalPort server)
            :network :regtest :timeout-ms 5000})]
      (try
        (is (= 2 (get-in connection [:peer-version :start-height])))
        (let [accepted (atom nil)
              result
              (peer/sync-headers!
               connection [(get-in genesis [:header :hash])]
               #(reset! accepted %)
               {:max-batches 2})]
          (is (= (mapv :hash-hex expected)
                 (mapv :hash-hex @accepted)))
          (is (= {:status :synced :batches 1 :accepted 2
                  :locator (get-in block-2 [:header :hash])}
                 result)))
        (is (= [(get-in genesis [:header :hash])]
               (:locator-hashes @server-result)))
      (finally
          (peer/close! connection))))))

(deftest obsolete-peer-version-fails-closed
  (with-open [server (ServerSocket. 0)]
    (let [magic (get-in peer/network-configuration [:regtest :magic])
          served
          (future
            (with-open [^Socket socket (.accept server)
                        input (DataInputStream. (.getInputStream socket))
                        output (DataOutputStream. (.getOutputStream socket))]
              (is (= "version" (:command (read-message input))))
              (send-message!
               output magic "version"
               (protocol/encode-version-payload
                {:version (dec peer/minimum-peer-version)
                 :timestamp 1 :nonce 2}))
              (send-message! output magic "verack" [])
              (read-message input)))]
      (is (= :bitcoin.node/peer-version
             (:type
              (ex-data
               (try
                 (peer/connect!
                  {:host "127.0.0.1" :port (.getLocalPort server)
                   :network :regtest :timeout-ms 5000})
                 (catch clojure.lang.ExceptionInfo error error))))))
      @served)))

(deftest malformed-peer-framing-fails-closed
  (let [regtest-magic (get-in peer/network-configuration [:regtest :magic])
        mainnet-magic (get-in peer/network-configuration [:mainnet :magic])
        version-payload
        (protocol/encode-version-payload
         {:timestamp 1 :nonce 2 :start-height 2})
        valid (protocol/encode-message
               regtest-magic "version" version-payload)
        bad-checksum (update (vec valid) (dec (count valid)) bit-xor 1)
        oversized
        (reduce
         (fn [message [index byte]] (assoc message index byte))
         (vec (protocol/encode-message regtest-magic "version" []))
         (map-indexed
          (fn [index byte] [(+ 16 index) byte])
          (protocol/uint-le->bytes
           (inc protocol/max-protocol-payload-bytes) 4)))]
    (is (= :bitcoin.node/peer-network-mismatch
           (handshake-error-type
            (protocol/encode-message
             mainnet-magic "version" version-payload))))
    (is (= :bitcoin.node/peer-checksum
           (handshake-error-type bad-checksum)))
    (is (= :bitcoin.node/peer-oversized-message
           (handshake-error-type oversized)))))

(deftest peer-configuration-fails-before-network-io
  (is (= :bitcoin.node/peer-network
         (:type
          (ex-data
           (try
             (peer/connect! {:network :unknown})
             (catch clojure.lang.ExceptionInfo error error))))))
  (is (= :bitcoin.node/peer-locator
         (:type
          (ex-data
           (try
             (peer/get-headers! {} [])
             (catch clojure.lang.ExceptionInfo error error)))))))
