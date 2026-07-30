# Changelog

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
