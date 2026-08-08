# OKX Microstructure Positive-Route Hypothesis-Design Runner V3

Status: `LOCAL_RESEARCH_TOOLING_ONLY`

This version changes only the fixed Local transport and self-attestation needed
to consume the manifest-bound interpretation-runner V2 outbox. It preserves
the positive-route V2 proposal schema, pure builder, route and hypothesis
contracts, result schema, all four dispositions, route selection, gates, and
safety boundary byte-identically.

## Fixed interface

The production entry point accepts zero arguments and reads no environment or
clock. Its fixed roots are:

- source:
  `C:/Users/Redan/.codex/local-research-node/outbox/local-node-microstructure-v3-interpretation-runner-v2/interpretation-result.json`;
- proposal:
  `C:/Users/Redan/.codex/local-research-node/inbox/local-node-microstructure-positive-route-design-runner-v3/coach-proposal.json`;
- output:
  `C:/Users/Redan/.codex/local-research-node/outbox/local-node-microstructure-positive-route-design-runner-v3/positive-route-hypothesis-design-result.json`.

The roots are pre-provisioned, separate, non-overlapping regular non-link
directories. Production accepts no alternate root, task, contract, tier,
route, proposal, builder, callable, disposition, environment, clock, or
fallback. Tests use only injected `RuntimePaths` under `TemporaryDirectory`.

## Fail-closed sequence

Before reading a source, the runner validates its exact V3 self-task, authority
and prohibitions, every frozen repository hash, final generic V2 dependencies,
and the exact four-file implementation inventory. It then:

1. accepts exactly one canonical sealed interpretation file;
2. for `READY_FOR_ONE_HYPOTHESIS_DESIGN`, accepts exactly one canonical sealed
   V2 proposal envelope bound to the source document and payload hashes,
   selected tier, and sole-primary route
   `OKX_MICROSTRUCTURE_INTRADAY_ECONOMIC_ROUTE_V1` at contract SHA-256
   `33fdef52654845911eda5f9f0dc9a3d1281ae6a6e0d4c0aab1bc93b51f34304e`;
3. for `NO_CANDIDATE_SHORT_HORIZON_BUY_PRESSURE`,
   `AMBIGUOUS_NO_HYPOTHESIS`, and `INSUFFICIENT_FORWARD_EVIDENCE`, requires
   an empty proposal root;
4. calls the unchanged
   `build_positive_route_hypothesis_design_result_bytes` in process and
   validates the result against the exact source bytes;
5. revalidates task, repository, implementation inventory, source, and
   proposal before output; and
6. exclusively creates the fixed result once, or returns
   `IDEMPOTENT_IDENTICAL` for identical validated bytes.

Missing, extra, linked, reparse, wrong-type, noncanonical, unsealed, drifted,
partial, source-mismatched, or conflicting input/output fails without
overwrite, repair, cleanup, fallback, or alternate path selection.

## Proposal and route boundary

The envelope contains exactly the caller-authored eight-field Coach proposal.
The helper validates and binds those fields but cannot generate, enrich, rank,
rewrite, timestamp, or select them. Route priority remains `SOLE_PRIMARY`;
caller override, multiple routes, DRA fallback, and route switching after
Design, Validation, or OOS remain false. A bare proposal is not a production
input.

Outputs remain `DESIGN_ONLY_NOT_REGISTERED` or
`CLOSED_NO_HYPOTHESIS_DESIGN`. No hypothesis or candidate is registered and no
OOS byte is opened.

## Evidence boundary

Historical positive-route V2 and generic V1 task/documentation bytes remain
immutable evidence. This slice does not execute a production fixed root,
instantiate V4, create a manifest or adapter, write canonical or
`.research-state` state, add a timer or writer, use network or paid APIs, or
touch Trading, databases, orders, funds, SHADOW, PAPER, or LIVE.

Real source/proposal execution, predictive significance, event cadence,
source reliability, matched-control coverage, fees, slippage, PnL, drawdown,
capital utilization, capacity, candidate readiness, registration, OOS value,
activation, deployment, and liveness remain `MISSING_PROOF`. Immediate PnL and
drawdown effect are zero.
