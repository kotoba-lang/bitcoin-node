(ns bitcoin.node.disk-consensus-test
  (:require [bitcoin.consensus.assumeutxo :as assumeutxo]
            [bitcoin.consensus.block :as block]
            [bitcoin.consensus.chainstate :as chainstate]
            [bitcoin.consensus.sqlite-utxo :as sqlite]
            [bitcoin.consensus.transaction :as transaction]
            [bitcoin.consensus.utxo :as utxo]
            [bitcoin.node.consensus :as consensus]
            [bitcoin.node.consensus-test :as fixture]
            [bitcoin.node.disk-consensus :as disk]
            [bitcoin.node.disk-utxo :as disk-utxo]
            [bitcoin.node.peer :as peer]
            [clojure.test :refer [deftest is]]
            [kotobase.bitcoin.protocol :as header])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- with-store [run!]
  (let [directory
        (Files/createTempDirectory
         "bitcoin-node-consensus-" (make-array FileAttribute 0))
        path (.resolve directory "chainstate.sqlite")]
    (try
      (run! path)
      (finally
        (doseq [target [path (Path/of (str path ".background")
                                     (make-array String 0))]
                suffix ["-shm" "-wal" ""]]
          (Files/deleteIfExists
           (Path/of (str target suffix) (make-array String 0))))
        (Files/deleteIfExists directory)))))

(defn- coinbase [height marker]
  (transaction/parse
   (transaction/serialize
    {:version 1
     :inputs [{:txid-natural (vec (repeat 32 0))
               :vout 0xffffffff
               :script-sig
               (conj
                (get-in (fixture/regtest-coinbase height)
                        [:inputs 0 :script-sig])
                marker)
               :sequence 0xffffffff}]
     :outputs [{:value (utxo/block-subsidy height 150)
                :script-pubkey [81]}]
     :witnesses nil :locktime 0 :segwit? false})))

(defn- mine-branch-block [parent height marker]
  ;; The standard fixture is sufficient for independent branches because its
  ;; timestamp and parent differ once the first branch block differs.
  (if (zero? marker)
    (fixture/mine-regtest-block parent height)
    (let [candidate (fixture/mine-regtest-block parent height)
          tx (coinbase height marker)]
      (loop [nonce 0]
        (let [template (-> (:header candidate)
                           (assoc :merkle-root (:txid-natural tx)
                                  :nonce nonce))
              bytes
              (header/encode-block-header template)
              decoded
              (header/decode-block-header bytes)]
          (if (header/hash-meets-target? (:hash decoded) (:bits decoded))
            (block/parse (vec (concat bytes [1] (:raw tx))))
            (recur (inc nonce))))))))

(deftest disk-consensus-persists-headers-blocks-and-restarts
  (with-store
    (fn [path]
      (let [genesis (block/parse (fixture/hex->bytes fixture/regtest-genesis))
            block-1 (fixture/mine-regtest-block genesis 1)
            block-2 (fixture/mine-regtest-block block-1 2)
            node
            (disk/open {:path path :network :regtest
                        :genesis-bytes
                        (fixture/hex->bytes fixture/regtest-genesis)})]
        (is (disk/ready? node))
        (is (= 2
               (:best-header-height
                (disk/accept-headers!
                 node [(get-in block-1 [:header :bytes])
                       (get-in block-2 [:header :bytes])]
                 2000000000))))
        (is (= 1
               (:height
                (disk/accept-block!
                 node (block/serialize block-1) 2000000000))))
        (is (= 2
               (:height
                (disk/accept-block!
                 node (block/serialize block-2) 2000000000))))
        (is (= :ok (:integrity (disk/integrity-check! node))))
        (is (nil? (get-in @(:state node)
                          [:nodes (:best-block
                                   (disk/consensus-status node)) :block])))
        (let [reopened (disk/open {:path path :network :regtest})]
          (is (= (disk/consensus-status node)
                 (disk/consensus-status reopened)))
          (is (disk/ready? reopened)))))))

(deftest block-locator-is-dense-then-exponentially-backtracks-to-genesis
  (let [genesis (block/parse (fixture/hex->bytes fixture/regtest-genesis))
        blocks (rest (reductions fixture/mine-regtest-block genesis
                                 (range 1 26)))
        state
        (reduce
         #(chainstate/accept-header %1 (:header %2) 2000000000)
         (chainstate/initialize :regtest genesis)
         blocks)
        locator (disk/block-locator state)
        heights
        (mapv #(get-in state [:nodes (header/natural-hash->hex %) :height])
              locator)]
    (is (= [25 24 23 22 21 20 19 18 17 16 14 10 2 0] heights))
    (is (= (get-in genesis [:header :hash]) (last locator)))))

(deftest peer-header-sync-validates-persists-and-resumes-from-durable-tip
  (with-store
    (fn [path]
      (let [genesis (block/parse (fixture/hex->bytes fixture/regtest-genesis))
            block-1 (fixture/mine-regtest-block genesis 1)
            block-2 (fixture/mine-regtest-block block-1 2)
            node
            (disk/open {:path path :network :regtest
                        :genesis-bytes
                        (fixture/hex->bytes fixture/regtest-genesis)})
            result
            (with-redefs
             [peer/sync-headers!
              (fn [_ locator accept-batch! options]
                (is (= [(get-in genesis [:header :hash])] locator))
                (is (= {:max-batches 3} options))
                (accept-batch! [(:header block-1) (:header block-2)])
                {:status :synced :batches 1 :accepted 2})]
              (disk/sync-headers!
               node ::connection 2000000000 {:max-batches 3}))]
        (is (= {:status :synced :batches 1 :accepted 2} result))
        (let [reopened (disk/open {:path path :network :regtest})
              status (disk/consensus-status reopened)]
          (is (= 2 (:best-header-height status)))
          (is (= (get-in block-2 [:header :hash-hex])
                 (:best-header status)))
          (is (= [(get-in block-2 [:header :hash])
                  (get-in block-1 [:header :hash])
                  (get-in genesis [:header :hash])]
                 (disk/block-locator @(:state reopened)))))))))

(deftest peer-block-sync-is-bounded-fully-validating-and-resumable
  (with-store
    (fn [path]
      (let [genesis (block/parse (fixture/hex->bytes fixture/regtest-genesis))
            block-1 (fixture/mine-regtest-block genesis 1)
            block-2 (fixture/mine-regtest-block block-1 2)
            node
            (disk/open {:path path :network :regtest
                        :genesis-bytes
                        (fixture/hex->bytes fixture/regtest-genesis)})
            _ (disk/accept-headers!
               node [(get-in block-1 [:header :bytes])
                     (get-in block-2 [:header :bytes])]
               2000000000)
            blocks-by-hash
            {(get-in block-1 [:header :hash]) (block/serialize block-1)
             (get-in block-2 [:header :hash]) (block/serialize block-2)}
            requested (atom [])]
        (with-redefs
         [peer/get-block!
          (fn [_ hash]
            (swap! requested conj hash)
            (get blocks-by-hash hash))]
          (let [first-batch
                (disk/sync-blocks!
                 node ::connection 2000000000 {:max-blocks 1})]
            (is (= :batch-limit (:status first-batch)))
            (is (= 1 (:downloaded first-batch)))
            (is (true? (:more? first-batch)))))
        (let [reopened (disk/open {:path path :network :regtest})]
          (with-redefs [peer/get-block! (fn [_ hash]
                                          (swap! requested conj hash)
                                          (get blocks-by-hash hash))]
            (let [completed
                  (disk/sync-blocks!
                   reopened ::connection 2000000000 {:max-blocks 2})]
              (is (= :synced (:status completed)))
              (is (= 1 (:downloaded completed)))
              (is (false? (:more? completed)))
              (is (= 2 (get-in completed [:consensus :height])))
              (is (disk/ready? reopened)))))
        (is (= [(get-in block-1 [:header :hash])
                (get-in block-2 [:header :hash])]
               @requested))))))

(deftest disk-consensus-reorganizes-with-durable-undo
  (with-store
    (fn [path]
      (let [genesis (block/parse (fixture/hex->bytes fixture/regtest-genesis))
            main-1 (mine-branch-block genesis 1 0)
            main-2 (mine-branch-block main-1 2 0)
            side-1 (mine-branch-block genesis 1 1)
            side-2 (mine-branch-block side-1 2 2)
            side-3 (mine-branch-block side-2 3 3)
            node
            (disk/open {:path path :network :regtest
                        :genesis-bytes
                        (fixture/hex->bytes fixture/regtest-genesis)})]
        (doseq [value [main-1 main-2 side-1 side-2 side-3]]
          (disk/accept-block! node (block/serialize value) 2000000000))
        (let [expected (get-in side-3 [:header :hash-hex])
              status (disk/consensus-status node)]
          (is (= 3 (:height status)))
          (is (= expected (:best-block status)))
          (is (= 3 (:utxo-count status)))
          (is (nil? (get-in @(:state node) [:nodes expected :undo])))
          (is (= expected
                 (:best-block
                  (disk/consensus-status
                   (disk/open {:path path :network :regtest}))))))))))

(deftest populated-utxo-without-host-state-fails-closed
  (with-store
    (fn [path]
      (let [legacy
            (disk-utxo/open {:path path :network :regtest})]
        (disk-utxo/connect-block!
         legacy (fixture/hex->bytes fixture/regtest-genesis))
        (is (= :bitcoin.node/missing-disk-consensus-state
               (:type
                (ex-data
                 (try
                   (disk/open {:path path :network :regtest})
                   (catch clojure.lang.ExceptionInfo error error))))))))))

(deftest assumeutxo-start-is-atomic-unready-and-restart-safe
  (with-store
    (fn [path]
      (let [genesis (block/parse (fixture/hex->bytes fixture/regtest-genesis))
            block-1 (fixture/mine-regtest-block genesis 1)
            block-2 (fixture/mine-regtest-block block-1 2)
            block-3 (fixture/mine-regtest-block block-2 3)
            full
            (consensus/open
             {:network :regtest
              :genesis-bytes
              (fixture/hex->bytes fixture/regtest-genesis)})
            _ (consensus/accept-block!
               full (block/serialize block-1) 2000000000)
            _ (consensus/accept-block!
               full (block/serialize block-2) 2000000000)
            coins (get-in @(:state full) [:utxo :coins])
            base-hash (get-in block-2 [:header :hash-hex])
            snapshot (fixture/core-snapshot base-hash coins)
            commitment
            (assumeutxo/hash-serialized coins)
            headers
            (-> (chainstate/initialize :regtest genesis)
                (chainstate/accept-header
                 (:header block-1) 2000000000)
                (chainstate/accept-header
                 (:header block-2) 2000000000)
                (chainstate/accept-header
                 (:header block-3) 2000000000))
            options
            {:checkpoints
             {2 {:blockhash base-hash
                 :hash-serialized commitment
                 :chain-tx-count 3}}}
            node
            (disk/open
             {:path path :network :regtest
              :header-state headers
              :snapshot-source snapshot
              :snapshot-options options})]
        (is (= :assumed (:snapshot-status
                         (disk/consensus-status node))))
        (is (= 0 (:background-height
                  (disk/consensus-status node))))
        (is (false? (disk/ready? node)))
        (is (= :bitcoin.node/background-incomplete
               (:type
                (ex-data
                 (try
                   (disk/verify-background! node)
                   (catch clojure.lang.ExceptionInfo error error))))))
        (is (= 3
               (:height
                (disk/accept-block!
                 node (block/serialize block-3) 2000000000))))
        (let [reopened (disk/open {:path path :network :regtest})]
          (is (= :assumed
                 (:snapshot-status (disk/consensus-status reopened))))
          (is (false? (disk/ready? reopened)))
          (is (= 1
                 (:background-height
                  (disk/accept-background-block!
                   reopened (block/serialize block-1) 2000000000))))
          (let [resumed (disk/open {:path path :network :regtest})
                validated
                (disk/accept-background-block!
                 resumed (block/serialize block-2) 2000000000)]
            (is (= :validated (:snapshot-status validated)))
            (is (= 3 (:height validated)))
            (is (nil? (:background-height validated)))
            (is (:fully-validated? validated))
            (is (disk/ready? resumed))
            (is (= :bitcoin.node/no-background-validation
                   (:type
                    (ex-data
                     (try
                       (disk/accept-background-block!
                        resumed (block/serialize block-2) 2000000000)
                       (catch clojure.lang.ExceptionInfo error error))))))
            (is (= :validated
                   (:snapshot-status
                    (disk/consensus-status
                     (disk/open {:path path :network :regtest})))))))))))

(deftest background-commitment-mismatch-never-promotes-and-can-retry
  (with-store
    (fn [path]
      (let [genesis (block/parse (fixture/hex->bytes fixture/regtest-genesis))
            block-1 (fixture/mine-regtest-block genesis 1)
            full
            (consensus/open
             {:network :regtest
              :genesis-bytes
              (fixture/hex->bytes fixture/regtest-genesis)})
            _ (consensus/accept-block!
               full (block/serialize block-1) 2000000000)
            coins (get-in @(:state full) [:utxo :coins])
            base-hash (get-in block-1 [:header :hash-hex])
            snapshot (fixture/core-snapshot base-hash coins)
            headers
            (chainstate/accept-header
             (chainstate/initialize :regtest genesis)
             (:header block-1) 2000000000)
            node
            (disk/open
             {:path path :network :regtest
              :header-state headers :snapshot-source snapshot
              :snapshot-options
              {:checkpoints
               {1 {:blockhash base-hash
                   :hash-serialized (assumeutxo/hash-serialized coins)
                   :chain-tx-count 2}}}})]
        (is (= :bitcoin.consensus/snapshot-background-mismatch
               (:type
                (ex-data
                 (with-redefs [sqlite/hash-serialized
                               (constantly (apply str (repeat 64 "f")))]
                   (try
                     (disk/accept-background-block!
                      node (block/serialize block-1) 2000000000)
                     (catch clojure.lang.ExceptionInfo error error)))))))
        (is (= :assumed
               (:snapshot-status (disk/consensus-status node))))
        (is (= 1 (:background-height
                  (disk/consensus-status node))))
        (is (= :validated
               (:snapshot-status (disk/verify-background! node))))
        (is (disk/ready? node))))))
