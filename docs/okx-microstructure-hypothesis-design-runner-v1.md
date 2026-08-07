# OKX Microstructure Hypothesis Design Runner V1

Status: frozen offline Local Research tooling. This runner transports and
validates a Coach-authored proposal; it never authors, ranks, enriches, or
selects a thesis.

## Fixed zero-argument paths

- Source interpretation root:
  `C:/Users/Redan/.codex/local-research-node/outbox/local-node-microstructure-v3-interpretation-runner-v1`
- Source leaf: `interpretation-result.json`
- Coach proposal root:
  `C:/Users/Redan/.codex/local-research-node/inbox/local-node-microstructure-v3-hypothesis-design-runner-v1`
- Proposal leaf: `coach-proposal.json`
- Design output root:
  `C:/Users/Redan/.codex/local-research-node/outbox/local-node-microstructure-v3-hypothesis-design-runner-v1`
- Output leaf: `hypothesis-design-result.json`

The roots must be separate, pre-provisioned regular non-link directories. The
CLI accepts zero arguments, reads no environment setting or clock, and offers
no production path, contract, task, tier, mechanism, or callable override.

## Validation and branches

Before reading a source, the runner validates the exact task bytes, task
authority and prohibitions, every frozen repository hash, and the exact
four-file implementation inventory. It accepts only canonical sealed source
bytes that pass `validate_interpretation_result_bytes`.

For `READY_FOR_ONE_HYPOTHESIS_DESIGN`, the proposal directory must contain
exactly one canonical sealed envelope. The envelope binds the exact source
document and payload hashes, disposition, and source-selected tier to one
caller-authored eight-field Coach proposal. All registration, OOS, activation,
timer/writer, Trading/database/order/fund, and paid-API flags are false. A bare
proposal dictionary is never a production input.

For `NO_CANDIDATE_SHORT_HORIZON_BUY_PRESSURE`,
`AMBIGUOUS_NO_HYPOTHESIS`, or `INSUFFICIENT_FORWARD_EVIDENCE`, the proposal
directory must be empty. The runner passes `None` to the existing pure builder
and creates only the deterministic closed result.

The runner calls `build_hypothesis_design_result_bytes` in process as the sole
design implementation and revalidates with
`validate_hypothesis_design_result_bytes`. It rechecks task, repository,
implementation, source, and proposal bytes before output.

## Create-once output

The only production write is an exclusive create in the fixed design outbox.
An exact canonical existing result returns `IDEMPOTENT_IDENTICAL`. A partial,
linked, noncanonical, unsealed, extra, source-mismatched, or conflicting output
fails closed without overwrite, deletion, repair, cleanup, permission change,
copy, move, or fallback.

The returned surface contains only create/idempotent status, fixed result name,
SHA-256, source disposition, design status, and design id or null.

## Evidence boundary

Temporary-directory tests use synthetic deterministic fixtures only. Real
source/proposal execution, 14-day predictive evidence, DRA clock and feature
compatibility, adapter readiness, economic ledgers, fees, slippage, fills,
capacity, PnL, drawdown, OOS value, candidate readiness, registration, and
activation remain `MISSING_PROOF`.

This runner does not write canonical or `.research-state` state, call Research
MCP, add a timer, use network or paid APIs, register a hypothesis or candidate,
open OOS, implement an adapter or manifest, or touch Trading, databases,
orders, funds, SHADOW, PAPER, or LIVE.
