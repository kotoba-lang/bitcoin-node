(ns bitcoin.node.protocol
  "Backend-neutral, watch-only Bitcoin node contract.")

(defprotocol NodeBackend
  (configured? [backend]
    "True only when endpoint and authentication material are available.")
  (node-status [backend]
    "Validated chain/sync status without secret-bearing fields.")
  (descriptor-info [backend descriptor-value]
    "Canonical public output descriptor metadata.")
  (derive-addresses [backend descriptor-value range-value]
    "Derive public addresses, optionally over [start end].")
  (scan-descriptors [backend descriptors]
    "Scan the active UTXO set for public descriptors."))

(defn ready?
  "A backend is usable for current-chain vault accounting only after initial
  block download finishes and headers equal blocks."
  [status]
  (and (= :connected (:status status))
       (false? (:initial-block-download? status))
       (nat-int? (:blocks status))
       (nat-int? (:headers status))
       (= (:blocks status) (:headers status))))
