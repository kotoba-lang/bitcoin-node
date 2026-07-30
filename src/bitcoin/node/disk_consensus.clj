(ns bitcoin.node.disk-consensus
  "A durable full-consensus host backed by the transactional SQLite UTXO set.

  Header/fork-choice metadata, the active UTXO delta, and active-chain undo
  journals share one commit boundary. Active block bodies and undo values are
  pruned from the metadata blob after commit; side-chain bodies are retained
  until they are either activated or explicitly resubmitted."
  (:require [bitcoin.consensus.assumeutxo :as assumeutxo]
            [bitcoin.consensus.block :as block]
            [bitcoin.consensus.chainstate :as chainstate]
            [bitcoin.consensus.codec :as codec]
            [bitcoin.consensus.sqlite-utxo :as sqlite]
            [bitcoin.consensus.storage :as storage]
            [bitcoin.consensus.utxo :as utxo]
            [kotobase.bitcoin.protocol :as header]))

(defrecord DiskConsensusNode [state backend verify-script network])

(def ^:private disk-coins-key ::disk-backed)

(defn- disk-coins [coin-count]
  {disk-coins-key true :coin-count coin-count})

(defn- strip-active-data
  [state coin-count]
  (let [active
        (loop [hash (:active-tip state) result #{}]
          (if hash
            (recur (get-in state [:nodes hash :parent])
                   (conj result hash))
            result))]
    (-> state
        (assoc-in [:utxo :coins] (disk-coins coin-count))
        (update
         :nodes
         (fn [nodes]
           (reduce
            (fn [result hash]
              (-> result
                  (assoc-in [hash :block] nil)
                  (assoc-in [hash :undo] nil)))
            nodes active))))))

(defn- fail! [type message data]
  (codec/fail! type message data))

(defn- validate-durable-tip!
  [state durable]
  (let [tip (:active-tip state)
        height (chainstate/active-height state)]
    (when-not (and (= tip (:tip durable))
                   (= height (:height durable)))
      (fail! :bitcoin.node/disk-consensus-tip-mismatch
             "Checksummed chainstate and SQLite UTXO tip disagree."
             {:state-tip tip :sqlite-tip (:tip durable)
              :state-height height :sqlite-height (:height durable)}))
    state))

(defn- seed-genesis!
  [backend initialized]
  (let [view (sqlite/begin backend)
        genesis-hash (:active-tip initialized)
        genesis-node (get-in initialized [:nodes genesis-hash])]
    (try
      (let [seeded
            (reduce
             (fn [coins [key coin]]
               (utxo/coin-assoc coins key coin))
             view
             (utxo/coin-entries (get-in initialized [:utxo :coins])))
            coin-count (utxo/coin-count seeded)
            durable (strip-active-data initialized coin-count)]
        (sqlite/commit-transition!
         seeded
         {:expected-tip nil
          :expected-height -1
          :new-tip genesis-hash
          :new-height 0
          :detach []
          :attach
          [{:block-hash genesis-hash
            :parent-hash nil
            :height 0
            :previous-height -1
            :undo (:undo genesis-node)}]
          :host-state-bytes (storage/encode durable)})
        durable)
      (catch Throwable error
        (sqlite/rollback! view)
        (throw error)))))

(defn- best-header-hash-at-height [state height]
  (loop [hash (:best-header state)]
    (let [node (get-in state [:nodes hash])]
      (cond
        (nil? node) nil
        (= height (:height node)) hash
        (< (:height node) height) nil
        :else (recur (:parent node))))))

(defn- seed-assumeutxo!
  [backend network header-state snapshot-source snapshot-options]
  (when-not (= network (:network header-state))
    (fail! :bitcoin.node/snapshot-header-network
           "AssumeUTXO header state belongs to another network."
           {:expected network :actual (:network header-state)}))
  (when-not (zero? (chainstate/active-height header-state))
    (fail! :bitcoin.node/snapshot-header-active-tip
           "Snapshot initialization requires a headers-only state at genesis."
           {:height (chainstate/active-height header-state)}))
  (let [result (volatile! nil)]
    (sqlite/import-snapshot!
     backend snapshot-source
     #(best-header-hash-at-height header-state %)
     (assoc
      snapshot-options
      :host-state-fn
      (fn [loaded]
        (let [activated (assumeutxo/activate header-state loaded)
              coin-count (get-in loaded [:snapshot :coins-count])
              durable (strip-active-data activated coin-count)]
          (vreset! result durable)
          (storage/encode durable)))))
    @result))

(defn open
  "Open or initialize an atomic disk-backed consensus node.

  `genesis-bytes` is required only for a new database. A non-empty legacy UTXO
  database without matching host metadata is rejected instead of guessing its
  header or fork-choice state."
  [{:keys [path datasource network genesis-bytes verify-script
           busy-timeout-ms snapshot-source header-state snapshot-options]
    :or {busy-timeout-ms 5000}}]
  (when-not (contains? chainstate/consensus-parameters network)
    (fail! :bitcoin.node/unsupported-consensus-network
           "The disk consensus host has no parameters for this network."
           {:network network}))
  (let [backend
        (sqlite/open {:path path :datasource datasource :network network
                      :busy-timeout-ms busy-timeout-ms})
        durable (sqlite/status backend)
        bytes (sqlite/host-state backend)
        initial
        (cond
          bytes
          (validate-durable-tip! (storage/decode bytes network) durable)

          (and (= -1 (:height durable)) snapshot-source)
          (do
            (when-not header-state
              (fail! :bitcoin.node/missing-snapshot-headers
                     "Snapshot initialization requires validated headers."
                     {:network network}))
            (seed-assumeutxo!
             backend network header-state snapshot-source
             (or snapshot-options {})))

          (= -1 (:height durable))
          (do
            (when-not genesis-bytes
              (fail! :bitcoin.node/missing-genesis
                     "A new disk consensus database requires genesis bytes."
                     {:network network}))
            (seed-genesis!
             backend
             (chainstate/initialize
              network (block/parse genesis-bytes) verify-script)))

          :else
          (fail! :bitcoin.node/missing-disk-consensus-state
                 "A populated UTXO database lacks atomic consensus metadata."
                 {:network network :height (:height durable)
                  :tip (:tip durable)}))]
    (->DiskConsensusNode (atom initial) backend verify-script network)))

(defn consensus-status [node]
  (let [state @(:state node)
        durable (sqlite/status (:backend node))
        tip (:active-tip state)
        best (:best-header state)]
    {:status :connected
     :backend :sqlite-consensus
     :network (:network state)
     :height (:height durable)
     :best-block tip
     :best-header best
     :best-header-height (get-in state [:nodes best :height])
     :chainwork (get-in state [:nodes tip :chainwork])
     :utxo-count (:coin-count durable)
     :fully-validated?
     (and (not= :assumed (get-in state [:snapshot :status]))
          (true? (get-in state [:nodes tip :block-valid?])))
     :snapshot-status (get-in state [:snapshot :status])
     :persistent? true}))

(defn ready? [node]
  (let [status (consensus-status node)]
    (and (:fully-validated? status)
         (nat-int? (:height status))
         (string? (:best-block status))
         (= (:height status)
            (chainstate/active-height @(:state node))))))

(defn lookup [node outpoint]
  (sqlite/lookup (:backend node) outpoint))

(defn integrity-check! [node]
  (sqlite/integrity-check! (:backend node)))

(defn accept-header!
  "Validate and durably index one raw 80-byte header."
  [node raw-header now]
  (locking node
    (let [before @(:state node)
          after (chainstate/accept-header
                 before (header/decode-block-header (vec raw-header)) now)
          durable (sqlite/status (:backend node))
          stripped (strip-active-data after (:coin-count durable))]
      (validate-durable-tip! before durable)
      (sqlite/save-host-state!
       (:backend node) (:tip durable) (:height durable)
       (storage/encode stripped))
      (reset! (:state node) stripped)
      (consensus-status node))))

(defn- path-to-root [state tip]
  (loop [hash tip result []]
    (if hash
      (recur (get-in state [:nodes hash :parent]) (conj result hash))
      result)))

(defn- transition-paths [before after]
  (let [old-path (path-to-root before (:active-tip before))
        new-path (path-to-root after (:active-tip after))
        new-hashes (set new-path)
        fork (first (filter new-hashes old-path))]
    {:detach (vec (take-while #(not= fork %) old-path))
     :attach (vec (reverse (take-while #(not= fork %) new-path)))}))

(defn- attachment [state hash]
  (let [node (get-in state [:nodes hash])]
    {:block-hash hash
     :parent-hash (:parent node)
     :height (:height node)
     :previous-height (dec (:height node))
     :undo (:undo node)}))

(defn accept-block!
  "Validate one raw block and atomically publish its fork-choice, UTXO delta,
  active undo journals, and checksummed restart state."
  [node raw-block now]
  (locking node
    (let [backend (:backend node)
          durable-before (sqlite/status backend)
          before @(:state node)
          _ (validate-durable-tip! before durable-before)
          view (sqlite/begin backend)]
      (try
        (let [hydrated (assoc-in before [:utxo :coins] view)
              parsed (block/parse raw-block)
              after
              (chainstate/accept-block
               hydrated parsed now (:verify-script node)
               {:undo-fn #(sqlite/undo backend %)})
              active-changed?
              (not= (:active-tip before) (:active-tip after))
              coin-count (utxo/coin-count (get-in after [:utxo :coins]))
              stripped (strip-active-data after coin-count)]
          (if active-changed?
            (let [{:keys [detach attach]}
                  (transition-paths before after)]
              (sqlite/commit-transition!
               (get-in after [:utxo :coins])
               {:expected-tip (:tip durable-before)
                :expected-height (:height durable-before)
                :new-tip (:active-tip after)
                :new-height (chainstate/active-height after)
                :detach detach
                :attach (mapv #(attachment after %) attach)
                :host-state-bytes (storage/encode stripped)}))
            (do
              (sqlite/rollback! view)
              (sqlite/save-host-state!
               backend (:tip durable-before) (:height durable-before)
               (storage/encode stripped))))
          (reset! (:state node) stripped)
          (consensus-status node))
        (catch Throwable error
          (sqlite/rollback! view)
          (throw error))))))
