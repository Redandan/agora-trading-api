# OKX Microstructure Positive-Route Hypothesis-Design Runner V2

Status: `LOCAL_RESEARCH_TOOLING_ONLY`

This runner transports one frozen V3 interpretation into the already-frozen
standalone intraday economic-route design builder. It adds no research
judgment. A positive interpretation requires one canonical Coach proposal;
the three non-positive dispositions require an empty proposal directory and
produce only the builder's closed result.

## Fixed interface

The production entry point accepts zero arguments and reads no environment or
clock. Its fixed roots are:

- source:
  `C:/Users/Redan/.codex/local-research-node/outbox/local-node-microstructure-v3-interpretation-runner-v1/interpretation-result.json`;
- proposal:
  `C:/Users/Redan/.codex/local-research-node/inbox/local-node-microstructure-positive-route-design-runner-v2/coach-proposal.json`;
- output:
  `C:/Users/Redan/.codex/local-research-node/outbox/local-node-microstructure-positive-route-design-runner-v2/positive-route-hypothesis-design-result.json`.

The three roots must be pre-provisioned, separate, non-overlapping regular
directories. The CLI must not be run until the Manager has separately supplied
and authorized a real proposal. Tests use only injected `RuntimePaths` backed
by temporary directories.

## Fail-closed sequence

Before reading the source, the runner validates task SHA-256
`7f5461a5354596a5bdaec57074eafadb0993f2cc8e3c6e997c9275b219345a5a`,
its exact authority and prohibitions, every listed repository hash, and the
exact four-file implementation inventory. It then:

1. accepts exactly one canonical sealed interpretation file;
2. for `READY_FOR_ONE_HYPOTHESIS_DESIGN`, accepts exactly one canonical sealed
   V2 proposal envelope bound to the interpretation document and payload
   hashes, selected tier, and sole-primary route
   `OKX_MICROSTRUCTURE_INTRADAY_ECONOMIC_ROUTE_V1` at contract SHA-256
   `33fdef52654845911eda5f9f0dc9a3d1281ae6a6e0d4c0aab1bc93b51f34304e`;
3. for every non-positive disposition, requires the proposal root to be empty;
4. calls `build_positive_route_hypothesis_design_result_bytes` in process and
   validates its exact result against the source bytes;
5. revalidates the task, repository, implementation inventory, source, and
   proposal before writing; and
6. exclusively creates the fixed result once, or returns
   `IDEMPOTENT_IDENTICAL` for byte-identical validated output.

Missing, extra, linked, reparse, wrong-type, noncanonical, unsealed, drifted,
partial, or conflicting input/output fails without overwrite, repair, cleanup,
fallback, or alternate path selection.

## Proposal boundary

The envelope contains the exact caller-authored eight-field proposal. The pure
helper may validate and bind those fields but cannot generate, enrich, rank,
rewrite, timestamp, or select them. Route priority is `SOLE_PRIMARY`; caller
override, multiple routes, DRA fallback, and route switching after Design,
Validation, or OOS are all false. A bare proposal object is not a production
input.

## Research boundary

The output is `DESIGN_ONLY_NOT_REGISTERED`. This slice does not instantiate a
V4 source, create a manifest or adapter, register a hypothesis or candidate,
open OOS, write canonical or `.research-state` state, add a timer or writer,
use a paid API, or touch Trading, databases, orders, funds, SHADOW, PAPER, or
LIVE.

Real source/proposal execution, predictive significance, event cadence,
source reliability, matched-control coverage, fees, slippage, PnL, drawdown,
capital utilization, capacity, candidate readiness, registration, OOS value,
and activation all remain `MISSING_PROOF`.

The next bounded action is Manager review and a clean commit of this four-file
slice. A real Coach proposal and fixed-root run require a separate task after a
positive sealed interpretation; V4 source/manifest/adapter work remains a later
independent gate.
