(ns bitcoin.node.consensus
  "Thread-safe host adapter for the pure bitcoin-consensus chainstate."
  (:require [bitcoin.consensus.block :as block]
            [bitcoin.consensus.chainstate :as chainstate]
            [bitcoin.consensus.storage :as storage])
  (:import (java.nio.file Files LinkOption Path)))

(defrecord ConsensusNode [state verify-script snapshot-path])

(defn- existing-snapshot? [path]
  (and path
       (Files/exists (Path/of (str path) (make-array String 0))
                     (make-array LinkOption 0))))

(defn open
  "Open an embedded validating chainstate. `verify-script` is mandatory.
  Existing snapshots are network-bound; otherwise raw genesis bytes are
  parsed and used to initialize state."
  [{:keys [network genesis-bytes verify-script snapshot-path]}]
  (when-not (ifn? verify-script)
    (throw (ex-info "Embedded consensus requires a Script verifier."
                    {:type :bitcoin.node/missing-script-verifier})))
  (let [initial
        (if (existing-snapshot? snapshot-path)
          (storage/load! snapshot-path network)
          (chainstate/initialize network (block/parse genesis-bytes)
                                 verify-script))]
    (->ConsensusNode (atom initial) verify-script snapshot-path)))

(defn consensus-status [node]
  (let [state @(:state node)
        tip (:active-tip state)
        current (get-in state [:nodes tip])]
    {:status :connected
     :backend :embedded-consensus
     :network (:network state)
     :height (:height current)
     :best-block tip
     :chainwork (:chainwork current)
     :utxo-count (count (get-in state [:utxo :coins]))
     :fully-validated? (true? (:block-valid? current))
     :persistent? (boolean (:snapshot-path node))}))

(defn ready? [node]
  (let [status (consensus-status node)]
    (and (:fully-validated? status)
         (nat-int? (:height status))
         (string? (:best-block status)))))

(defn accept-block!
  "Parse, validate, and atomically publish one raw block. Persistence happens
  only after the compare-and-set succeeds; an interrupted save leaves the
  previous valid snapshot intact."
  [node raw-block now]
  (let [parsed (block/parse raw-block)]
    (loop []
      (let [before @(:state node)
            after (chainstate/accept-block
                   before parsed now (:verify-script node))]
        (if (compare-and-set! (:state node) before after)
          (do
            (when-let [path (:snapshot-path node)]
              (storage/save! path after))
            (consensus-status node))
          (recur))))))
