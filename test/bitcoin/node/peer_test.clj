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
              (send-raw! output frame)))
          type
          (:type
           (ex-data
            (try
              (peer/connect!
               {:host "127.0.0.1" :port (.getLocalPort server)
                :network :regtest :timeout-ms 5000})
              (catch clojure.lang.ExceptionInfo error error))))]
      @served
      type)))

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
                  (let [block-request (read-message input)]
                    (is (= "getdata" (:command block-request)))
                    (send-message!
                     output magic "block" (block/serialize block-1))
                    (assoc decoded
                           :block-request (:payload block-request)))))))
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
        (is (= (block/serialize block-1)
               (peer/get-block!
                connection (get-in block-1 [:header :hash]))))
        (is (= [(get-in genesis [:header :hash])]
               (:locator-hashes @server-result)))
        (is (= (vec
                (concat
                 [1]
                 (protocol/uint-le->bytes
                  peer/witness-block-inventory-type 4)
                 (get-in block-1 [:header :hash])))
               (:block-request @server-result)))
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

(deftest peer-must-advertise-required-service-bits
  (letfn [(connect-to [advertised-services required-services]
            (with-open [server (ServerSocket. 0)]
              (let [magic
                    (get-in peer/network-configuration [:regtest :magic])
                    served
                    (future
                      (with-open
                       [^Socket socket (.accept server)
                        input (DataInputStream. (.getInputStream socket))
                        output (DataOutputStream. (.getOutputStream socket))]
                        (is (= "version" (:command (read-message input))))
                        (send-message!
                         output magic "version"
                         (protocol/encode-version-payload
                          {:services advertised-services
                           :timestamp 1 :nonce 2}))
                        (send-message! output magic "verack" [])
                        (is (= "verack" (:command (read-message input))))))
                    result
                    (try
                      (peer/connect!
                       {:host "127.0.0.1"
                        :port (.getLocalPort server)
                        :network :regtest
                        :timeout-ms 5000
                        :required-services required-services})
                      (catch clojure.lang.ExceptionInfo error error))]
                @served
                result)))]
    (let [failure (connect-to 0 peer/node-network-service)]
      (is (= :bitcoin.node/peer-required-services
             (:type (ex-data failure))))
      (is (= peer/node-network-service
             (:required (ex-data failure))))
      (is (= 0 (:actual (ex-data failure)))))
    (let [connection
          (connect-to peer/node-network-service
                      peer/node-network-service)]
      (try
        (is (= peer/node-network-service
               (get-in connection [:peer-version :services])))
        (finally
          (peer/close! connection))))))

(deftest control-traffic-cannot-extend-an-overall-request-deadline
  (with-open [server (ServerSocket. 0)]
    (let [magic (get-in peer/network-configuration [:regtest :magic])
          served
          (future
            (try
              (with-open [^Socket socket (.accept server)
                          input (DataInputStream. (.getInputStream socket))
                          output (DataOutputStream. (.getOutputStream socket))]
                (is (= "version" (:command (read-message input))))
                (send-message!
                 output magic "version"
                 (protocol/encode-version-payload
                  {:timestamp 1 :nonce 2 :start-height 1}))
                (send-message! output magic "verack" [])
                (is (= "verack" (:command (read-message input))))
                (is (= "getheaders" (:command (read-message input))))
                (dotimes [nonce 20]
                  (send-message!
                   output magic "ping"
                   (protocol/encode-ping-payload nonce))
                  (Thread/sleep 50)))
              (catch Throwable _ :client-closed)))
          connection
          (peer/connect!
           {:host "127.0.0.1" :port (.getLocalPort server)
            :network :regtest :timeout-ms 500})
          started (System/nanoTime)]
      (try
        (let [failure
              (try
                (peer/get-headers!
                 connection [(vec (repeat 32 0))])
                (catch clojure.lang.ExceptionInfo error error))
              elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
          (is (= :bitcoin.node/peer-timeout
                 (:type (ex-data failure))))
          (is (= :headers (:operation (ex-data failure))))
          (is (< elapsed-ms 1500.0)))
        (finally
          (peer/close! connection)))
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
             (catch clojure.lang.ExceptionInfo error error))))))
  (is (= :bitcoin.node/peer-block-hash
         (:type
          (ex-data
           (try
             (peer/get-block! {} [])
             (catch clojure.lang.ExceptionInfo error error)))))))

(deftest bounded-peer-set-fails-over-and-resumes-from-current-locator
  (let [locators (atom [])
        closed (atom [])
        tip (vec (repeat 32 7))]
    (with-redefs
     [peer/connect!
      (fn [{:keys [host]}]
        (if (= host "unreachable")
          (throw (ex-info "offline" {:type :test/offline}))
          {:id host :peer-version {:start-height 42}}))
      peer/close! #(swap! closed conj (:id %))
      peer/sync-headers!
      (fn [_ locator _ options]
        (swap! locators conj locator)
        (is (= {:max-batches 3} options))
        {:status :synced :batches 1 :accepted 2 :locator tip})]
     (let [result
           (peer/sync-headers-from-peers!
            [{:host "unreachable" :network :regtest}
             {:host "healthy" :network :regtest}]
            #(vec (repeat 1 (vec (repeat 32 1))))
            (constantly nil)
            {:max-batches 3})]
       (is (= :synced (:status result)))
       (is (= 1 (:successful-peers result)))
       (is (= 2 (:attempted result)))
       (is (= :test/offline (get-in result [:failures 0 :type])))
       (is (= [42] (mapv :start-height (:observations result))))
       (is (= ["healthy"] @closed))
       (is (= 1 (count @locators)))))))

(deftest multiple-successful-peers-surface-chain-disagreement
  (let [next-tip (atom 0)]
    (with-redefs
     [peer/connect!
      (fn [{:keys [host]}]
        {:id host :peer-version {:start-height 2}})
      peer/close! (constantly nil)
      peer/sync-headers!
      (fn [_ _ _ _]
        (let [byte (swap! next-tip inc)]
          {:status :synced :batches 1 :accepted 1
           :locator (vec (repeat 32 byte))}))]
     (let [result
           (peer/sync-headers-from-peers!
            [{:host "peer-a"} {:host "peer-b"}]
            #(vector (vec (repeat 32 0)))
            (constantly nil)
            {:required-successes 2})]
       (is (= 2 (:successful-peers result)))
       (is (true? (:disagreement? result)))
       (is (= 2 (count (:observations result))))))))

(deftest exhausted-peer-set-preserves-typed-failure-evidence
  (with-redefs
   [peer/connect!
    (fn [{:keys [host]}]
      (throw (ex-info host {:type :test/rejected})))]
   (let [failure
         (try
           (peer/sync-headers-from-peers!
            [{:host "a"} {:host "b"}]
            (constantly [(vec (repeat 32 0))])
            (constantly nil) {})
           (catch clojure.lang.ExceptionInfo error error))]
     (is (= :bitcoin.node/peer-set-exhausted
            (:type (ex-data failure))))
     (is (= 2 (count (:failures (ex-data failure))))))))
