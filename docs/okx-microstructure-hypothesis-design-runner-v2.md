# OKX Microstructure Hypothesis Design Runner V2

Status: frozen offline Local Research tooling. This version changes only the
versioned Local transport identity needed to consume the manifest-bound V3
interpretation runner V2. It preserves the V1 proposal schema, pure builder,
hypothesis contract, result schema, dispositions, gates, and safety boundary.

## Fixed zero-argument paths

- Source interpretation root:
  `C:/Users/Redan/.codex/local-research-node/outbox/local-node-microstructure-v3-interpretation-runner-v2`
- Source leaf: `interpretation-result.json`
- Coach proposal root:
  `C:/Users/Redan/.codex/local-research-node/inbox/local-node-microstructure-v3-hypothesis-design-runner-v2`
- Proposal leaf: `coach-proposal.json`
- Design output root:
  `C:/Users/Redan/.codex/local-research-node/outbox/local-node-microstructure-v3-hypothesis-design-runner-v2`
- Output leaf: `hypothesis-design-result.json`

The roots are separate, pre-provisioned regular non-link directories. The CLI
accepts zero arguments, reads no environment setting or clock, and offers no
alternate path, task, contract, tier, mechanism, disposition, or callable.

## Validation and branches

Before reading a source, the runner validates its exact V2 self-task bytes,
authority and prohibitions, every frozen repository hash, and the exact
four-file implementation inventory. It accepts exactly one canonical sealed
`interpretation-result.json` through `validate_interpretation_result_bytes`.

For `READY_FOR_ONE_HYPOTHESIS_DESIGN`, the proposal directory contains exactly
one canonical sealed envelope bound to the exact source document and payload
hashes and source-selected tier. The eight proposal fields remain entirely
Coach-authored. The runner never generates, ranks, enriches, timestamps,
rewrites, or selects a thesis, tier, mechanism, parent, metric, gate, date, or
opportunity-cost judgment.

For `NO_CANDIDATE_SHORT_HORIZON_BUY_PRESSURE`,
`AMBIGUOUS_NO_HYPOTHESIS`, or `INSUFFICIENT_FORWARD_EVIDENCE`, the proposal
directory is empty. Any proposal or extra entry fails closed. The runner calls
the unchanged `build_hypothesis_design_result_bytes` in process and produces
only its deterministic closed result.

Outputs remain `DESIGN_ONLY_NOT_REGISTERED` or
`CLOSED_NO_HYPOTHESIS_DESIGN`. The runner revalidates task, repository,
implementation, source, and proposal bytes before output.

## Create-once output

The only write is an exclusive create in the fixed V2 outbox. An exact
canonical existing result returns `IDEMPOTENT_IDENTICAL`. A partial, linked,
noncanonical, unsealed, extra, source-mismatched, or conflicting output fails
without overwrite, deletion, repair, cleanup, permission change, copy, move,
or fallback.

## Evidence boundary

Tests use deterministic synthetic `TemporaryDirectory` fixtures only. The
production fixed roots are not executed by this tooling slice. Historical V1
task and documentation bytes remain immutable evidence.

Real source/proposal execution, predictive value, adapter readiness, economic
ledgers, fees, slippage, fills, capacity, PnL, drawdown, OOS value, candidate
readiness, registration, activation, deployment, and liveness remain
`MISSING_PROOF`. Immediate PnL and drawdown effect are zero.

This runner does not write canonical or `.research-state` state, call Research
MCP, add a timer or writer, use network or paid APIs, register a hypothesis or
candidate, open OOS, implement an adapter or manifest, or touch Trading,
databases, orders, funds, SHADOW, PAPER, or LIVE.
