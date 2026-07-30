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
            [bitcoin.node.peer :as peer]
            [kotobase.bitcoin.protocol :as header]))

(defrecord DiskConsensusNode
  [state backend verify-script network background])

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

(defn- open-single
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
    (->DiskConsensusNode
     (atom initial) backend verify-script network nil)))

(defn- header-genesis-bytes [header-state]
  (when header-state
    (some-> (get-in header-state
                    [:nodes (:active-tip header-state) :block])
            block/serialize)))

(defn open
  "Open an atomic disk consensus node.

  Snapshot nodes automatically maintain an independent genesis-started
  background database at `<path>.background`. Custom DataSources must provide
  `:background-datasource` or `:background-path`."
  [{:keys [path network genesis-bytes verify-script
           busy-timeout-ms header-state
           background-path background-datasource
           background-genesis-bytes]
    :as options}]
  (let [node (open-single options)
        assumed? (= :assumed (get-in @(:state node) [:snapshot :status]))]
    (if-not assumed?
      node
      (let [derived-path (or background-path
                             (when path (str path ".background")))
            _ (when-not (or derived-path background-datasource)
                (fail!
                 :bitcoin.node/missing-background-storage
                 "Snapshot consensus requires independent background storage."
                 {:network network}))
            background
            (open-single
             {:path derived-path
              :datasource background-datasource
              :network network
              :genesis-bytes
              (or background-genesis-bytes genesis-bytes
                  (header-genesis-bytes header-state))
              :verify-script verify-script
              :busy-timeout-ms (or busy-timeout-ms 5000)})]
        (assoc node :background background)))))

(defn consensus-status [node]
  (let [state @(:state node)
        durable (sqlite/status (:backend node))
        tip (:active-tip state)
        best (:best-header state)
        assumed? (= :assumed (get-in state [:snapshot :status]))
        background-status
        (when (and assumed? (:background node))
          (sqlite/status (get-in node [:background :backend])))]
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
     :snapshot-base-height (get-in state [:snapshot :base-height])
     :snapshot-base-block (get-in state [:snapshot :base-blockhash])
     :background-height (:height background-status)
     :background-tip (:tip background-status)
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

(declare accept-headers!)

(defn accept-header!
  "Validate and durably index one raw 80-byte header."
  [node raw-header now]
  (accept-headers! node [raw-header] now))

(defn accept-headers!
  "Validate a chronological header batch and persist it once, atomically.
  No header in a failing batch becomes visible."
  [node raw-headers now]
  (locking node
    (let [before @(:state node)
          after
          (chainstate/accept-headers
           before
           (mapv #(header/decode-block-header (vec %)) raw-headers)
           now)
          durable (sqlite/status (:backend node))
          stripped (strip-active-data after (:coin-count durable))]
      (validate-durable-tip! before durable)
      (sqlite/save-host-state!
       (:backend node) (:tip durable) (:height durable)
       (storage/encode stripped))
      (reset! (:state node) stripped)
      (consensus-status node))))

(defn block-locator
  "Build a Bitcoin Core-style locator from the best validated header.

  The ten newest entries are consecutive, then the walk doubles its step
  until genesis. This lets a peer find a common ancestor after deep reorgs
  and makes restart synchronization independent of the peer that supplied
  the previous tip."
  [state]
  (loop [hash (:best-header state)
         step 1
         entries 0
         locator []]
    (when-not hash
      (fail! :bitcoin.node/missing-best-header
             "Cannot build a locator without a validated best header." {}))
    (let [node (get-in state [:nodes hash])]
      (when-not node
        (fail! :bitcoin.node/missing-header-node
               "Best-header ancestry is incomplete."
               {:hash hash}))
      (let [locator' (conj locator (get-in node [:header :hash]))
            height (:height node)]
        (if (zero? height)
          locator'
          (let [step' (if (>= entries 9) (* 2 step) step)
                ancestor
                (loop [current hash remaining (min step' height)]
                  (if (zero? remaining)
                    current
                    (recur (get-in state [:nodes current :parent])
                           (dec remaining))))]
            (recur ancestor step' (inc entries) locator')))))))

(defn sync-headers!
  "Synchronize validated P2P header batches into this disk consensus node."
  ([node connection now]
   (sync-headers! node connection now {}))
  ([node connection now options]
   (let [state @(:state node)
         locator (block-locator state)]
     (peer/sync-headers!
      connection locator
      #(accept-headers! node (mapv :bytes %) now)
      options))))

(declare accept-block!)

(defn pending-best-chain-blocks
  "Return natural-order hashes for unvalidated blocks on the best header chain.

  Results are chronological and bounded, so a caller can resume after every
  atomic block commit without maintaining a separate download cursor."
  ([node]
   (pending-best-chain-blocks node 128))
  ([node limit]
   (when-not (and (integer? limit) (<= 1 limit 1024))
     (fail! :bitcoin.node/block-sync-limit
            "Block synchronization limit must be between 1 and 1,024."
            {:limit limit}))
   (let [state @(:state node)
         chain
         (loop [hash (:best-header state) result []]
           (if hash
             (recur (get-in state [:nodes hash :parent])
                    (conj result hash))
             (reverse result)))]
     (->> chain
          (remove #(true? (get-in state [:nodes % :block-valid?])))
          (take limit)
          (mapv #(get-in state [:nodes % :header :hash]))))))

(defn sync-blocks!
  "Fetch and fully validate a bounded segment of the best header chain.

  Every block is committed independently through `accept-block!`; interruption
  therefore resumes from the first unvalidated header without replaying an
  external cursor."
  ([node connection now]
   (sync-blocks! node connection now {}))
  ([node connection now {:keys [max-blocks] :or {max-blocks 128}}]
   (let [hashes (pending-best-chain-blocks node max-blocks)]
     (doseq [hash hashes]
       (accept-block! node (peer/get-block! connection hash) now))
     (let [more? (boolean (seq (pending-best-chain-blocks node 1)))]
       {:status (if more? :batch-limit :synced)
        :downloaded (count hashes)
        :more? more?
        :consensus (consensus-status node)}))))

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

(defn- promote-background!
  [node]
  (let [state @(:state node)
        snapshot (:snapshot state)
        background (:background node)
        background-status (sqlite/status (:backend background))
        base-height (:base-height snapshot)]
    (if (not= base-height (:height background-status))
      state
      (let [_ (sqlite/integrity-check! (:backend background))
            commitment (sqlite/hash-serialized (:backend background))
            validated
            (assumeutxo/validate-background-commitment
             state (:height background-status) (:tip background-status)
             commitment)
            base (:base-blockhash snapshot)
            foreground-status (sqlite/status (:backend node))
            promoted
            (-> validated
                (assoc-in [:nodes base :block-valid?] true)
                (assoc-in [:nodes base :scripts-checked?] true)
                (strip-active-data (:coin-count foreground-status)))]
        (sqlite/save-host-state!
         (:backend node) (:tip foreground-status)
         (:height foreground-status) (storage/encode promoted))
        (reset! (:state node) promoted)
        promoted))))

(defn accept-background-block!
  "Fully validate one pre-snapshot block in the independent disk chainstate.
  At the exact snapshot base, stream its UTXO commitment and atomically promote
  the foreground trust state when height, tip, and commitment all match."
  [node raw-block now]
  (locking node
    (when-not (= :assumed (get-in @(:state node) [:snapshot :status]))
      (fail! :bitcoin.node/no-background-validation
             "No assumed snapshot is awaiting background validation." {}))
    (when-not (:background node)
      (fail! :bitcoin.node/missing-background-storage
             "Assumed snapshot has no independent background chainstate." {}))
    (let [base-height (get-in @(:state node) [:snapshot :base-height])
          before-height
          (:height (sqlite/status (get-in node [:background :backend])))]
      (when (>= before-height base-height)
        (fail! :bitcoin.node/background-complete
               "Background chainstate already reached the snapshot base."
               {:height before-height :base-height base-height}))
      (accept-block! (:background node) raw-block now)
      (promote-background! node)
      (consensus-status node))))

(defn verify-background!
  "Retry integrity and commitment verification after the background database
  has reached the snapshot base. This never advances either chainstate."
  [node]
  (locking node
    (when-not (= :assumed (get-in @(:state node) [:snapshot :status]))
      (fail! :bitcoin.node/no-background-validation
             "No assumed snapshot is awaiting background validation." {}))
    (when-not (:background node)
      (fail! :bitcoin.node/missing-background-storage
             "Assumed snapshot has no independent background chainstate." {}))
    (let [base-height (get-in @(:state node) [:snapshot :base-height])
          actual-height
          (:height (sqlite/status (get-in node [:background :backend])))]
      (when-not (= base-height actual-height)
        (fail! :bitcoin.node/background-incomplete
               "Background chainstate has not reached the snapshot base."
               {:height actual-height :base-height base-height}))
      (promote-background! node)
      (consensus-status node))))

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
