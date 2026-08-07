# OKX Microstructure Hypothesis Design V1

Status: frozen, offline, pre-thesis design contract. This contract does not
register a hypothesis, create a manifest, run an adapter, open OOS, or authorize
SHADOW, PAPER, LIVE, Trading, database, order, or fund activity.

## Input and validation

The sole input is the canonical UTF-8 interpretation-result document defined by
`microstructure-interpretation-result.v1.schema.json`. The builder validates the
exact raw bytes and their frozen contract, handoff, diagnostic, payload, and seal
bindings before considering a design branch. A parsed dictionary is not an
acceptable substitute for the original bytes.

The source-selected interpretation tier is copied into the design binding. A
caller cannot select a tier, mechanism, threshold, magnitude, or more complex
model.

## Closed branches

`NO_CANDIDATE_SHORT_HORIZON_BUY_PRESSURE`, `AMBIGUOUS_NO_HYPOTHESIS`, and
`INSUFFICIENT_FORWARD_EVIDENCE` close as
`CLOSED_NO_HYPOTHESIS_DESIGN`. Their `hypothesis_design` is null and any supplied
proposal is rejected.

## Positive branch

`READY_FOR_ONE_HYPOTHESIS_DESIGN` accepts exactly one caller-supplied Coach
proposal with these exact fields:

- `design_id`
- `created_at`
- `title`
- `thesis`
- `economic_rationale`
- `performance_thesis`
- `drawdown_thesis`
- `opportunity_cost`

The result remains `DESIGN_ONLY_NOT_REGISTERED`. It fixes parent
`BTC_DRA_V1_BASELINE_250_USDT_RESEARCH`, status
`PROPOSED_PENDING_CLOCK_AND_FEATURE_COMPATIBILITY`, capability
`DRA_MICROSTRUCTURE_ENTRY_ADMISSION_ADAPTER_V1_NOT_IMPLEMENTED`, one mechanism,
and at most one variant. The existing daily volume/range adapter is not treated
as compatible with the books5 microstructure admission capability.

## Evaluation boundary

The design binds the exact Policy V3 metrics and constraints. Any future
discovery window is research evidence, not OOS. A separately frozen future OOS
window may be defined only after the hypothesis and manifest are frozen. Window
dates, feature compatibility, adapter implementation, predictive value, fees,
slippage, drawdown improvement, and PnL remain `MISSING_PROOF` until separately
established.

The result is deterministic compact sorted-key JSON with a SHA-256 seal computed
over the document excluding `seal`. All authorization and mutation safety flags
remain false. No result from this contract is canonical research state.
