# bitcoin-node

Backend-neutral, watch-only Bitcoin node integration for Clojure.

`bitcoin-node` does not reimplement Bitcoin consensus. It defines a small
`NodeBackend` protocol and ships a hardened Bitcoin Core JSON-RPC adapter for:

- chain and initial-sync status;
- canonical public output descriptor inspection;
- Taproot and SegWit multisig policy validation;
- public receive-address derivation;
- explicit UTXO-set scans.
- network/genesis identity binding and Core capability discovery;
- serialized scan execution, progress reporting, and cancellation;
- optional compact-block-filter history candidate scans.

The adapter is loopback-only by default, prefers Bitcoin Core's short-lived
cookie authentication, rejects URL userinfo/query/fragment components, limits
response size, correlates JSON-RPC IDs, and allows only:

- `getblockchaininfo`
- `getblockhash`
- `getindexinfo`
- `getnetworkinfo`
- `getdescriptorinfo`
- `deriveaddresses`
- `scantxoutset`
- `scanblocks`

Private descriptors and unsolvable or unsupported policies fail closed.
Cloud applications should keep user/organization persistence and Passkey
approval outside this library.

`node/ready?` is intentionally stricter than connectivity: initial block
download must be false, block and header heights must both be non-negative
integers, and the heights must match. Address derivation and scan inputs are
bounded before an RPC call is made.

## Non-goals

This project is not a Bitcoin full node, consensus implementation, signer, key
store, wallet database, or broadcast API. Bitcoin Core remains the validating
security boundary. Other backends can implement `NodeBackend`, but must provide
equivalent validation semantics and contract tests.

## Usage

```clojure
(require '[bitcoin.node.core :as core]
         '[bitcoin.node.protocol :as node])

(def backend
  (core/backend
   {:url "http://127.0.0.1:8332"
    :expected-chain "main"
    :expected-genesis-hash
    "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"
    :cookie-file "~/Library/Application Support/Bitcoin/.cookie"}))

(node/node-identity backend)
(node/capabilities backend)
(node/node-status backend)
(node/descriptor-info backend "tr(xpub.../0/*)#checksum")
(node/derive-addresses backend "tr(xpub.../0/*)#checksum" [0 4])
(node/scan-status backend)
(node/abort-scan! backend)
```

`core/scan-blocks` requires a discovered, fully synchronized basic block
filter index and otherwise fails with `:bitcoin.node/capability-unavailable`.
It returns candidate block hashes, not transaction history; consumers must
fetch and validate the relevant blocks separately.

## Verify

```bash
clojure -M:test
clojure -M:lint
clojure -M:coverage
```
