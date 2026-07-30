(ns bitcoin.node.peer-pool-test
  (:require [bitcoin.node.peer :as peer]
            [bitcoin.node.peer-pool :as pool]
            [clojure.test :refer [deftest is testing]])
  (:import [java.net InetAddress]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- ipv4 [a b c d]
  (InetAddress/getByAddress
   (byte-array (map unchecked-byte [a b c d]))))

(deftest dns-discovery-is-bounded-deduplicated-and-public-only
  (let [resolved
        {"seed-a"
         [(ipv4 8 8 8 8)
          (ipv4 10 0 0 1)
          (ipv4 100 64 0 1)
          (ipv4 127 0 0 1)
          (ipv4 192 0 2 1)
          (ipv4 198 18 0 1)
          (ipv4 198 51 100 1)
          (ipv4 203 0 113 1)
          (ipv4 224 0 0 1)]
         "seed-b"
         [(ipv4 1 1 1 1) (ipv4 8 8 8 8)]}
        peers
        (pool/discover-dns!
         :mainnet
         {:seeds ["seed-a" "seed-b"]
          :resolver #(get resolved %)
          :timeout-ms 1000
          :maximum-results 8})]
    (is (= ["1.1.1.1" "8.8.8.8"] (mapv :host peers)))
    (is (every? #(= 8333 (:port %)) peers))
    (is (every? #(= :mainnet (:network %)) peers)))
  (is (= [] (pool/discover-dns! :regtest)))
  (is (= :bitcoin.node/peer-discovery-configuration
         (:type
          (ex-data
           (try
             (pool/discover-dns!
              :mainnet {:seeds (repeat 17 "seed")})
             (catch clojure.lang.ExceptionInfo error error)))))))

(deftest pool-rotates-healthy-peers-and-cools-down-failures
  (let [initial
        (pool/create
         [{:host "a" :network :regtest}
          {:host "b" :network :regtest}
          {:host "c" :network :regtest}])
        first-two (pool/candidates initial 100 2)
        selected (pool/mark-selected initial first-two 100)
        next-peer (first (pool/candidates selected 101 1))
        failed
        (pool/record-failure
         selected next-peer 101 :bitcoin.node/peer-timeout 500)
        eligible (pool/candidates failed 102 3)]
    (is (= ["a" "b"] (mapv :host first-two)))
    (is (= "c" (:host next-peer)))
    (is (= ["a" "b"] (mapv :host eligible)))
    (is (= {:peers 3 :eligible 2 :cooling-down 1
            :successful 0 :next-retry-at 30101}
           (pool/status failed 102)))
    (let [healthy
          (pool/record-success failed (first eligible) 103 40)]
      (is (= 1 (get-in healthy
                       [:peers (pool/peer-id (first eligible))
                        :successes])))
      (is (= 40.0 (get-in healthy
                          [:peers (pool/peer-id (first eligible))
                           :latency-ema-ms]))))))

(deftest repeated-failures-use-bounded-exponential-backoff
  (let [configuration {:host "a" :network :regtest}
        once
        (pool/record-failure
         (pool/create [configuration]) configuration
         1000 :bitcoin.node/peer-timeout 100)
        twice
        (pool/record-failure
         once configuration 2000 :bitcoin.node/peer-timeout 100)
        severe
        (pool/record-failure
         twice configuration 3000
         :bitcoin.node/peer-network-mismatch 10)]
    (is (= 31000
           (get-in once [:peers (pool/peer-id configuration)
                         :cooldown-until])))
    (is (= 62000
           (get-in twice [:peers (pool/peer-id configuration)
                          :cooldown-until])))
    (is (= (+ 3000 pool/maximum-cooldown-ms)
           (get-in severe [:peers (pool/peer-id configuration)
                           :cooldown-until])))))

(deftest peer-pool-snapshot-is-checksummed-bounded-and-atomic
  (let [directory
        (Files/createTempDirectory
         "bitcoin-peer-pool-" (make-array FileAttribute 0))
        path (.resolve ^Path directory "peers.edn")
        configuration {:host "8.8.8.8" :network :mainnet}
        expected
        (-> (pool/create [configuration])
            (pool/record-success configuration 1000 25))]
    (try
      (is (= path (pool/save! path expected)))
      (is (= expected (pool/load! path)))
      (let [damaged (Files/readAllBytes path)]
        (aset-byte damaged (dec (alength damaged))
                   (unchecked-byte
                    (bit-xor 1
                             (bit-and
                              0xff
                              (aget damaged (dec (alength damaged)))))))
        (is (= :bitcoin.consensus/chainstate-checksum-mismatch
               (:type
                (ex-data
                 (try
                   (pool/decode damaged)
                   (catch clojure.lang.ExceptionInfo error error)))))))
      (is (= :bitcoin.node/peer-pool-corrupt
             (:type
              (ex-data
               (try
                 (pool/validate {:peers {[:mainnet "bad" 8333] {}}})
                 (catch clojure.lang.ExceptionInfo error error))))))
      (finally
        (Files/deleteIfExists path)
        (Files/deleteIfExists directory)))))

(deftest managed-sync-updates-health-from-typed-peer-evidence
  (let [pool-atom
        (atom
         (pool/create
          [{:host "bad" :network :regtest}
           {:host "good" :network :regtest}]))]
    (with-redefs
     [peer/sync-headers-from-peers!
      (fn [configurations _ _ options]
        (is (= ["bad" "good"] (mapv :host configurations)))
        (is (= {:max-batches 2} options))
        {:status :synced
         :successful-peers 1
         :observations
         [{:peer {:host "good" :port 18444 :network :regtest}
           :elapsed-ms 25.0}]
         :failures
         [{:peer {:host "bad" :port 18444 :network :regtest}
           :type :bitcoin.node/peer-timeout :elapsed-ms 500.0}]})]
     (let [result
           (pool/sync-headers!
            pool-atom (constantly []) (constantly nil)
            {:now-ms 1000 :maximum-peers 2 :max-batches 2})]
       (is (= :synced (:status result)))
       (is (= 1 (get-in result [:pool :successful])))
       (is (= ["good"]
              (mapv :host (pool/candidates @pool-atom 1001 2))))))))

(deftest managed-sync-retains-failures-when-all-peers-fail
  (let [configuration {:host "bad" :network :regtest}
        pool-atom (atom (pool/create [configuration]))]
    (with-redefs
     [peer/sync-headers-from-peers!
      (fn [& _]
        (throw
         (ex-info
          "failed"
          {:type :bitcoin.node/peer-set-exhausted
           :failures
           [{:peer {:host "bad" :port 18444 :network :regtest}
             :type :bitcoin.node/peer-eof :elapsed-ms 5.0}]})))]
     (testing "typed evidence survives the orchestration exception"
       (is (= :bitcoin.node/peer-set-exhausted
              (:type
               (ex-data
                (try
                  (pool/sync-headers!
                   pool-atom (constantly []) (constantly nil)
                   {:now-ms 100})
                  (catch clojure.lang.ExceptionInfo error error))))))
       (is (= 1 (get-in @pool-atom
                        [:peers (pool/peer-id configuration)
                         :failures])))))))

(deftest managed-sync-persists-selection-on-unexpected-failure
  (let [directory
        (Files/createTempDirectory
         "bitcoin-peer-pool-failure-" (make-array FileAttribute 0))
        path (.resolve ^Path directory "peers.edn")
        configuration {:host "bad" :network :regtest}
        pool-atom (atom (pool/create [configuration]))]
    (try
      (with-redefs
       [peer/sync-headers-from-peers!
        (fn [& _] (throw (IllegalStateException. "callback failed")))]
       (is (thrown? IllegalStateException
                    (pool/sync-headers!
                     pool-atom (constantly []) (constantly nil)
                     {:now-ms 42 :pool-path path}))))
      (is (= 42
             (get-in (pool/load! path)
                     [:peers (pool/peer-id configuration)
                      :last-selected-at])))
      (finally
        (Files/deleteIfExists path)
        (Files/deleteIfExists directory)))))
