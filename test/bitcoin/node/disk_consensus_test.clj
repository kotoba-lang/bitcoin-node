(ns bitcoin.node.disk-consensus-test
  (:require [bitcoin.consensus.assumeutxo :as assumeutxo]
            [bitcoin.consensus.block :as block]
            [bitcoin.consensus.chainstate :as chainstate]
            [bitcoin.consensus.transaction :as transaction]
            [bitcoin.consensus.utxo :as utxo]
            [bitcoin.node.consensus :as consensus]
            [bitcoin.node.consensus-test :as fixture]
            [bitcoin.node.disk-consensus :as disk]
            [bitcoin.node.disk-utxo :as disk-utxo]
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
        (doseq [suffix ["-shm" "-wal" ""]]
          (Files/deleteIfExists
           (Path/of (str path suffix) (make-array String 0))))
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
        (is (= 1
               (:best-header-height
                (disk/accept-header!
                 node (get-in block-1 [:header :bytes]) 2000000000))))
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
          (is (= 4 (:utxo-count status)))
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
        (is (false? (disk/ready? node)))
        (is (= 3
               (:height
                (disk/accept-block!
                 node (block/serialize block-3) 2000000000))))
        (let [reopened (disk/open {:path path :network :regtest})]
          (is (= :assumed
                 (:snapshot-status (disk/consensus-status reopened))))
          (is (false? (disk/ready? reopened))))))))
