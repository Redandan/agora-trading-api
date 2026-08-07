# OKX Microstructure Discovery Economic Veto V1

Status: `PREOUTCOME_DISCOVERY_ONLY_TEMPLATE_NOT_INSTANTIATED`

This is a research-only contract freeze. It adds no runner, source, adapter,
ledger engine, manifest, state transition, hypothesis, candidate, OOS action,
or Trading behavior. The contract contains no actual handoff, tier, date,
event, metric, PnL, drawdown, or outcome.

## Purpose and ordering

The veto sits after one validated V1 interpretation with disposition
`READY_FOR_ONE_HYPOTHESIS_DESIGN` and before any later V4 source or economic
manifest. It uses the interpretation's first passing tier without caller
override, fallback, magnitude ranking, multiple routes, or tuning.

The fixed order is:

1. validate the create-only V3 handoff and positive V1 interpretation;
2. evaluate this discovery-only economic veto over the exact exported bytes;
3. stop at `VETO_BEFORE_V4` if any frozen gate fails; or
4. on `PERMIT_LATER_V4`, allow only a separately frozen later V4 source and
   economic-manifest slice. A later versioned bridge, not this contract, may
   then supersede the existing V2 bridge.

Neither disposition proves alpha, registers a candidate, opens Design,
Validation or OOS, or authorizes activation.

## Exact discovery bytes and tier

A future result must bind one validated create-only V3 handoff, the exact
manifest and all 14 exported raw day-bundle and envelope hashes, and one
validated V1 interpretation. The interpretation supplies the selected first
passing tier in the frozen simplest-first order. The caller cannot choose or
replace it.

All 14 UTC days must be contiguous and `CLEAN`, contain exactly 1,440 valid
minutes, have a valid hash chain and zero anomaly, and expose valid
`trade_open_price` plus the frozen feature bytes. No new data, backfill,
substitution, repair, or cross-window byte is allowed.

## Reused clock, ledgers and costs

The signal bucket `[m,m+1)` is available at `m+1`; a decision occurs only after
the bucket closes. Entry is the `m+2` `trade_open_price`, exit is the `m+62`
`trade_open_price`, and `m+2` through `m+61` are the 60 complete held minutes.
Exit precedes entry at the same timestamp. Signals lacking a full exit are
excluded and reported.

Candidate and matched-control lanes are separate one-position, long-only OKX
`BTC-USDT` spot ledgers with `30.00 USDT` gross entry and 60-minute cooldown.
Overlap, leverage, pyramiding, resizing, stop, target, and scaling are
forbidden; both lanes finish at zero inventory.

The accounting order is unchanged: entry raw price times `1.0005`; buy fee
`0.0010` of base; exit raw price times `0.9995`; exit fee `0.0010` of quote;
net PnL is net exit quote minus `30.00`. Controls use identical accounting.
The derived raw break-even hurdle is exactly `30.0550826113908` bps and is
context, not a tunable gate.

## Comparator and fail-closed gates

Cash remains the absolute benchmark. The matched control is the closest unused
strictly earlier day at the same UTC minute and in the same fold. Controls are
unique, cannot cross folds, use the same clock/notional/friction, and must give
equal paired counts with at least 80% match coverage.

Every frozen V1 gate applies unchanged: 14 clean complete days, 1,440 minutes
per day, at least 30 selected-tier trades, at least 10 in each seven-day half,
80% controls, zero duplicate/cross-fold/anomaly counts, full exits and zero
terminal inventory; plus positive candidate net total, positive candidate
minus control, positive median, positive share strictly above 50%, drawdown no
worse than control, positive delta in each half, and top-one positive
incremental contribution no greater than 40%.

Any failure yields `VETO_BEFORE_V4` and closes this route without tuning. Only
all gates passing yields `PERMIT_LATER_V4`.

## Evidence boundary and opportunity cost

This contract has zero immediate PnL and drawdown effect. Its bounded benefit
is avoiding 42 future days and implementation cost when the discovery ledger
already fails. One 14-day regime may veto a route that would generalize later;
that false-negative rate and the value of saved work are `MISSING_PROOF`.
Generalization, future V4 readiness, economic value, PnL, drawdown,
utilization, capacity, candidate readiness, OOS value, and activation all
remain `MISSING_PROOF`.
