# Changelog

## 0.8.2 — 2026-07-30

- Add a resumable Core-backed snapshot historical harness covering full header
  validation, retained post-snapshot blocks, and interval process restarts.

## 0.8.1 — 2026-07-30

- Add atomic AssumeUTXO initialization to the disk consensus host using an
  independently validated headers-only state.
- Keep snapshot-start nodes explicitly unready while background validation is
  pending, including after validating later blocks and across process restart.
- Upgrade to `bitcoin-consensus` v0.7.1 so snapshot UTXO, trust status, and
  fork-choice metadata share one SQLite transaction.

## 0.8.0 — 2026-07-30

- Upgrade to `bitcoin-consensus` v0.7.0 for testnet4, default signet,
  BIP94/BIP325 validation, and atomic host-state persistence.
- Add `bitcoin.node.disk-consensus`, integrating validated headers,
  cumulative-work fork choice, disk UTXO transitions, durable undo, and
  checksummed restart state in one SQLite commit boundary.
- Support header-only persistence and atomic multi-block reorganization.
- Prune active block bodies and undo values from the metadata blob while
  retaining side-chain block data until activation.
- Fail closed when a populated lower-level UTXO database lacks its matching
  consensus host state.

## 0.7.0 — 2026-07-30

- Upgrade to `bitcoin-consensus` v0.6.1 with complete Core Script-vector
  parity and historical soft-fork flag selection.
- Add a node-facing SQLite UTXO host for raw block parsing, network deployment
  flags, atomic connect/undo, durable disconnect, integrity checks, and
  constant-memory authenticated AssumeUTXO import.
- Require independently validated MTP context before connecting CSV-active
  history and reject blocks that do not extend the durable tip.

## 0.6.0 — 2026-07-30

- Upgrade the embedded kernel to `bitcoin-consensus` v0.5.1.
- Expose atomic headers-first indexing without premature block activation.
- Authenticate and activate Core v2 AssumeUTXO snapshots while retaining a
  separate background chainstate.
- Promote snapshots only after full historical validation reproduces the
  pinned base commitment; fail closed when background state is unavailable.
- Report best-header, snapshot, and background-validation progress explicitly.

## 0.5.2 — 2026-07-30

- Upgrade the embedded consensus kernel to v0.4.2.

## 0.5.1 — 2026-07-30

- Upgrade the embedded consensus kernel to v0.4.1.

## 0.5.0 — 2026-07-30

- Use the built-in consensus Script verifier by default.

## 0.4.1 — 2026-07-30

- Upgrade the embedded kernel to `bitcoin-consensus` v0.3.0 for overflow-safe
  unsigned wire decoding, block serialization, adversarial fuzz coverage, and
  Bitcoin Core v31 differential verification.

## 0.4.0 — 2026-07-30

- Integrate the separately versioned `bitcoin-consensus` v0.2.0 kernel.
- Add a thread-safe embedded consensus host that validates raw blocks before
  compare-and-set publication.
- Require a Script verifier and optionally persist atomic checksummed
  chainstate after every accepted block.
- Report embedded validation height, best block, chainwork, UTXO count, and
  persistence readiness without weakening the Bitcoin Core backend.

## 0.3.0 — 2026-07-30

- Implement the shared read-only `chain-observer` snapshot contract.
- Identify Bitcoin networks with the CAIP-2 `bip122` genesis-hash prefix.
- Preserve Bitcoin's probabilistic best-chain semantics instead of claiming a
  deterministic finalized head.
- Report local Bitcoin Core validation, sync, peers, warnings, and discovered
  history capability through one validated observation.

## 0.2.0 — 2026-07-30

- Bind a backend to an expected chain and genesis block.
- Discover Bitcoin Core version, network state, peers, warnings, and block
  filter index capability at runtime.
- Add best-block and chainwork evidence to sync status.
- Serialize local UTXO scans and expose progress and cancellation.
- Add fail-closed compact-block-filter history candidate scans.
- Reject POSIX RPC cookies readable or writable by group/other users.
- Validate response content type and normalize transport failures without
  credential-bearing error data.
- Add form/line coverage reporting with a 70% CI floor.

This release remains watch-only. It neither signs nor broadcasts transactions.
Compact-filter scans return candidate blocks, not verified transaction history.
