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

`bitcoin.node.disk-consensus` is the mainnet-scale embedded host. It validates
headers and blocks, selects the most-work chain, and atomically commits the
resulting UTXO delta, active-chain undo journals, and checksummed fork-choice
metadata to one SQLite database. Header-only progress, side-chain blocks,
multi-block reorganizations, and process restarts retain one consistent
security boundary. Active block bodies are pruned from metadata after commit;
a detached branch must be fetched again before a later reactivation.
Normalized headers are exposed through an immutable lazy map with a bounded
LRU and write overlay, so normal restart reads only compact host metadata plus
the active and best tips. A bounded block locator is persisted with that
metadata and advanced incrementally instead of walking every ancestor before
each peer request. Databases using normalized host format v1 perform one
transactional full-index migration; subsequent v2 opens remain bounded.

`bitcoin.node.disk-utxo` remains available as a lower-level linear-chain host.
It parses raw blocks, derives consensus Script flags, atomically commits
touched outpoints plus undo, supports durable tip disconnect and integrity
checks, and streams authenticated AssumeUTXO snapshots without materializing
all coins. Its caller owns header validation and fork selection.

`bitcoin.node.peer` adds a bounded, read-only JVM P2P transport for
mainnet, testnet3, testnet4, signet, and regtest. It performs version/verack,
answers ping, verifies network magic and checksums before decoding bounded
payloads, enforces an overall monotonic deadline even during control traffic
or partial frames, and retrieves headers in protocol-sized batches. The disk
host builds a sparse Bitcoin-style block locator, validates every returned
header, and commits each batch atomically, so synchronization resumes after
restart and can find a common ancestor after a reorganization. A bounded peer
set fails over from the latest durable locator, records typed failure evidence,
and can compare independently reported tips; only local validation and exact
most-work fork choice select state. It deliberately has no transaction relay,
mempool, wallet, signing, or mining commands. Retained blocks can be requested
individually with witness data; the response header must match the requested
hash before the raw block is returned to the full consensus validator.

`bitcoin.node.peer-pool` bootstraps bounded public IPv4 candidates from the
Bitcoin Core DNS seed set, then rotates peers using local success, failure,
latency, and exponential-cooldown history. DNS is discovery only: every peer
must still complete the authenticated network handshake and all downloaded
data crosses the same local consensus boundary. Pool snapshots are bounded,
checksummed, and atomically replaced so health history survives process
restarts without becoming consensus input. Only globally routed IPv4 unicast
answers are accepted; local, CGNAT, documentation, benchmark, multicast, and
reserved ranges are excluded.

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

Atomic disk-backed validation:

```clojure
(require '[bitcoin.node.disk-consensus :as disk-consensus])

(def durable
  (disk-consensus/open
   {:network :mainnet
    :genesis-bytes raw-genesis-block
    :path "data/mainnet-consensus.sqlite"}))

(disk-consensus/accept-header! durable raw-80-byte-header unix-time)
(disk-consensus/accept-block! durable raw-block unix-time)
(disk-consensus/consensus-status durable)
(disk-consensus/integrity-check! durable)
```

Direct header synchronization:

```clojure
(require '[bitcoin.node.peer :as peer])

(with-open [connection
            (peer/connect! {:host "127.0.0.1" :network :mainnet
                            :timeout-ms 30000})]
  (disk-consensus/sync-headers!
    durable connection unix-time {:max-batches 500})
  (disk-consensus/sync-blocks!
   durable connection unix-time {:max-blocks 128}))
```

Managed discovery, failover, and durable peer health:

```clojure
(require '[bitcoin.node.peer-pool :as peer-pool])

(def peers
  (atom
   (peer-pool/create
    (peer-pool/discover-dns!
     :mainnet {:timeout-ms 5000 :maximum-results 64}))))

(disk-consensus/sync-headers-managed!
 durable peers unix-time
 {:maximum-peers 8
  :max-batches 500
  :pool-path "data/mainnet-peers.edn"})

(peer-pool/status @peers (System/currentTimeMillis))
```

Applications should retain explicit operator peers as additional candidates
and periodically rediscover when the eligible pool is depleted. A DNS answer
is never treated as identity, finality, chain state, or a reason to bypass
proof-of-work, difficulty, timestamp, or block validation.

Block synchronization walks only unvalidated members of the most-work header
chain, oldest first. Each block is fully validated and committed separately;
after interruption or restart the next call derives its cursor from durable
consensus state.

After the initial database is seeded, `genesis-bytes` may be omitted on
restart. A populated legacy UTXO database without the matching atomic
chainstate blob is rejected; it is never assigned inferred fork-choice state.

For snapshot-start, pass an authenticated Core v2 snapshot as
`:snapshot-source` and a separately PoW/difficulty-validated, headers-only
chainstate as `:header-state`. The snapshot base must be on its most-work
header chain. UTXOs, `:assumed` trust status, active tip, and headers are
committed together; `ready?` remains false across restart until independent
background validation reproduces the pinned UTXO commitment. The disk host
creates `<path>.background` from genesis and advances it with
`disk-consensus/accept-background-block!`. At the exact snapshot base it checks
SQLite integrity, streams Core HASH_SERIALIZED without loading the UTXO set,
and atomically changes the foreground trust state to `:validated`.

If promotion verification is interrupted after the background block commit,
`disk-consensus/verify-background!` safely retries without advancing either
chain. Status reports `:background-height`, `:background-tip`, and the pinned
snapshot base. A custom foreground DataSource must also provide an independent
`:background-datasource` or `:background-path`.

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

Snapshot-start historical differential against a synchronized/pruned Core:

```bash
CONSENSUS_CORE_DATADIR=/path/to/bitcoin \
CONSENSUS_SNAPSHOT_PATH=/path/to/utxo.dat \
CONSENSUS_SNAPSHOT_HEIGHT=840000 \
CONSENSUS_HEADER_CHAINSTATE=/path/to/headers.edn \
CONSENSUS_DISK_CHAINSTATE=/path/to/consensus.sqlite \
CONSENSUS_HISTORY_END=850000 \
./scripts/core_snapshot_history_differential.sh
```

The harness independently validates every header through the requested end,
authenticates the snapshot against the pinned Core v31.1 AssumeUTXO anchor,
compares each retained raw block's hash/size/weight with Core, applies it to
the disk consensus host, and reopens the database at a configurable interval.
If a pruned node no longer has the requested post-snapshot range it fails
before importing state. `CONSENSUS_GENESIS_HEX` is needed only when Core has
also pruned the genesis block and no header checkpoint exists.

On an archival Core, set `CONSENSUS_BACKGROUND_VALIDATE=true` to replay blocks
1 through the snapshot base into the independent disk chainstate, reopen it at
the configured interval, recompute the base commitment, and require the final
foreground state to report `snapshot=validated` and `ready=true`.
