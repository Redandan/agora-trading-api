# OKX Microstructure Positive Route Hypothesis Design V2

Status: `FROZEN_PREOUTCOME_ROUTE_SELECTION_DESIGN_ONLY`

This versioned bridge preserves every V1 byte. It accepts only canonical sealed
V1 interpretation-result bytes and creates a deterministic research-only design
result. It does not instantiate a source, choose dates, create a manifest or
adapter, register a hypothesis or candidate, open OOS, write state, add a timer,
or authorize Trading.

## Frozen route priority

For `READY_FOR_ONE_HYPOTHESIS_DESIGN`, the sole primary route is
`OKX_MICROSTRUCTURE_INTRADAY_ECONOMIC_ROUTE_V1`, bound to SHA-256
`33fdef52654845911eda5f9f0dc9a3d1281ae6a6e0d4c0aab1bc93b51f34304e`.
The bridge mechanically copies the interpretation-selected first passing tier.
The caller cannot select a tier, route, mechanism, threshold, date or hash.

Exactly one eight-field Coach proposal is required. The builder transports the
proposal without authoring or enriching its thesis, economic rationale,
performance thesis, drawdown thesis or opportunity-cost judgment. The resulting
mechanism is one long-only standalone 60-minute design with capability
`MICROSTRUCTURE_STANDALONE_INTRADAY_ECONOMIC_ADAPTER_V1_NOT_IMPLEMENTED`.
Maximum routes, designs and eventual candidate variants are each one.

The selection is `SOLE_PRIMARY`. Caller override, multiple routes, DRA fallback
and switching after any Design, Validation or OOS outcome are false. A failed
standalone route closes; it does not make DRA a post-outcome fallback.

## Closed branches

`NO_CANDIDATE_SHORT_HORIZON_BUY_PRESSURE`,
`AMBIGUOUS_NO_HYPOTHESIS` and `INSUFFICIENT_FORWARD_EVIDENCE` reject every
proposal and return `CLOSED_NO_HYPOTHESIS_DESIGN` with null route selection,
hypothesis design and evidence plan.

## Future evidence boundary

The positive result remains `DESIGN_ONLY_NOT_REGISTERED`. Later work must occur
in this fixed order without collapsing stages:

1. freeze one byte-separated, single-active V4 source instance and an exact
   economic manifest before any future economic byte;
2. implement one offline standalone adapter and prove ledger parity;
3. evaluate 14 consecutive Design days;
4. evaluate 14 consecutive Validation days;
5. expose a canonical candidate path only if both stages pass; and
6. only then permit the separately authorized one-time opening of 14
   server-sealed OOS days.

The original 14-day predictive window is discovery only and cannot become
economic evidence or OOS. The future total is 42 new consecutive complete UTC
days. Exact dates remain `MISSING_PROOF`, and OOS bytes remain undisclosed until
Design and Validation pass while the route is still frozen.

## Performance boundary

Policy V3 remains bound to fee-adjusted equal-capital total PnL with realized,
unrealized and total PnL, maximum drawdown, capital utilization, blocked entries
and holding age visible. Future evidence must also retain terminal inventory,
fees, adverse slippage, round-trip friction, matched-control coverage and
breadth, year/regime concentration, event cadence and capacity.

This bridge has zero immediate PnL and drawdown effect. It reduces route-decision
latency and prevents an unintentional default detour to the audited sparse DRA
clock of approximately 10.59 to 22.50 months; that range is not a forecast.
Economic value after the frozen 30-bps round-trip planning friction, event
cadence, matched-control coverage, source reliability, adapter parity, PnL,
drawdown, capacity, candidate readiness, OOS value and activation all remain
`MISSING_PROOF`.

## Determinism and safety

The pure builder rehashes the V2 contract and schema, Policy V3, the frozen V1
interpretation contract and schema, and the standalone route contract. It then
validates the canonical interpretation bytes, builds compact sorted-key UTF-8
JSON, seals the payload excluding `seal`, and validates its own result.

The Draft 2020-12 result schema rejects extra fields at every object boundary.
All source, manifest, adapter, registration, OOS, activation, timer/writer,
Trading/database/order/fund and paid-API authorization fields remain false.
