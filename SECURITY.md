# Security policy

Do not open public issues containing wallet descriptors, RPC cookies,
credentials, transaction drafts, or incident details.

Report vulnerabilities through GitHub's private vulnerability reporting for
this repository. Include the affected commit, a minimal reproduction, and the
expected security boundary.

This library is watch-only. Applications must never pass private descriptors,
seed phrases, WIF keys, or signing shares to it. Keep Bitcoin Core RPC on
loopback unless a separately authenticated and encrypted transport has been
reviewed. A connected node is not considered ready until initial block download
has finished and validated block height equals header height.

Production configuration should bind both `:expected-chain` and
`:expected-genesis-hash`. POSIX cookie files are rejected when group or other
permissions are present. A UTXO scan result is an observation at its returned
height and best-block hash; use `bitcoin.node.protocol/same-observation?` before
treating a cached result as current after the tip changes.
