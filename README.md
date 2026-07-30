# bitcoin-node

Backend-neutral, watch-only Bitcoin node integration for Clojure.

`bitcoin-node` defines a small `NodeBackend` protocol and ships a hardened
Bitcoin Core JSON-RPC adapter for:

- chain and initial-sync status;
- canonical public output descriptor inspection;
- Taproot and SegWit multisig policy validation;
- public receive-address derivation;
- explicit UTXO-set scans.
- network/genesis identity binding and Core capability discovery;
- serialized scan execution, progress reporting, and cancellation;
- optional compact-block-filter history candidate scans.
- a CAIP-2 `bip122` snapshot implementing the shared
  [`chain-observer`](https://github.com/kotoba-lang/chain-observer) contract.

It also exposes an opt-in embedded host around
[`bitcoin-consensus`](https://github.com/kotoba-lang/bitcoin-consensus).
`bitcoin.node.consensus/open` uses its in-process Script VM, validates raw
blocks before atomically publishing state, and can persist checksummed
chainstate. It supports headers-first indexing and authenticated Bitcoin Core
v2 AssumeUTXO activation while retaining a separate fully validated background
chainstate. A verifier override remains available for differential tests; the
security boundary documented by `bitcoin-consensus` still applies.

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

This project is not yet a production Bitcoin Core replacement, signer, key
store, wallet database, or broadcast API. Bitcoin Core remains the default
production validating security boundary. Other backends must provide
equivalent validation semantics and contract tests.

## Usage

```clojure
(require '[bitcoin.node.core :as core]
         '[bitcoin.node.protocol :as node]
         '[chain.observer.protocol :as observer])

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
(observer/snapshot backend)
```

Embedded validation:

```clojure
(require '[bitcoin.node.consensus :as consensus])

(def embedded
  (consensus/open
   {:network :mainnet
    :genesis-bytes raw-genesis-block
    :verify-script verify-bitcoin-script
    :snapshot-path "data/mainnet-chainstate.edn"}))

(consensus/accept-block! embedded raw-block unix-time)
(consensus/accept-header! embedded raw-80-byte-header unix-time)
(consensus/load-assumeutxo! embedded core-snapshot-input-stream)
(consensus/accept-background-block! embedded historical-raw-block unix-time)
(consensus/consensus-status embedded)
```

An AssumeUTXO state reports `:snapshot-status :assumed` until background
validation reaches the exact base and recomputes the pinned UTXO commitment.
The assumed and background chainstates are persisted separately; startup fails
closed if an assumed state has lost its background state.

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
