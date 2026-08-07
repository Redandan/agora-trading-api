# OKX Microstructure DRA Entry Admission V1

Status: `PREOUTCOME_DESIGN_ONLY_NOT_ADAPTER_NOT_CANDIDATE`

This document explains the frozen research-only contract in
`research_pipeline/okx-microstructure-dra-entry-admission-contract.v1.json`.
It does not implement or authorize an adapter, economic run, hypothesis,
candidate, OOS access, runtime integration, state write, or Trading action.

## Purpose

The contract defines the smallest causal way a future offline comparison may
use the interpretation-selected OKX microstructure tier as a veto on an
otherwise eligible DRA V1 entry. Microstructure cannot create an entry and
cannot change DRA arming, cooldown, sizing, capital, exits, fees, slippage,
valuation, inventory, or fill timing.

The performance thesis is deliberately conditional: a point-in-time veto may
avoid adverse DRA entries and underwater inventory. PnL and drawdown effects
remain `MISSING_PROOF`. False vetoes may remove profitable entries, reduce
capital utilization, increase year/regime concentration, and leave the long
holding risk of admitted DRA lots unchanged. This contract alone has zero PnL
and zero drawdown effect.

## Tier binding

The caller cannot choose a tier. A future adapter may proceed only from a
validated interpretation whose disposition is
`READY_FOR_ONE_HYPOTHESIS_DESIGN`. It must copy the first passing tier from the
frozen simplest-first order:

1. `MIDLINE_RATIO_1_5_ONLY`
2. `MIDLINE_RATIO_1_5_PLUS_NET_TAKER_BUY`
3. `MIDLINE_RATIO_1_5_PLUS_NET_TAKER_BUY_PLUS_BOOK_SUPPORT`

The pass rule remains positive 15-minute confirmation and positive 60-minute
primary response. Those response fields select the tier during discovery but
are never adapter inputs. A non-positive, ambiguous, or insufficient
interpretation closes without an admission design. No fallback, magnitude
ranking, threshold change, or escalation to a more complex tier is allowed.

## Causal clock

DRA evaluates the closed UTC hourly bar covering `23:00:00` through
`24:00:00`, decides after that close, and preserves its virtual fill at the
next one-hour open at `00:00:00`.

A microstructure minute represents `[minute, minute+60s)` and becomes available
only when that interval completes. The eligible minute starts are exactly
`23:00:00` through `23:58:00`, inclusive: 59 contiguous records. Each eligible
bucket ends strictly before the DRA fill boundary. The `23:59:00` bucket is
excluded because it completes at `00:00:00`, contemporaneously with the
next-hour fill rather than strictly earlier.

```text
23:00                         23:58  23:59               00:00
|------ 59 eligible starts -----|    | excluded bucket ----|
                                              DRA close/decision and next-open fill
```

## Input projection and aggregation

The full day document must first validate against the exact frozen V3 schema,
source provenance, canonical payload seal, and `CLEAN` integrity. Only then may
the future adapter project the fields needed by the selected tier:

- every tier: `minute`, `above_mid_buy_quote_notional`, and
  `below_mid_sell_quote_notional`;
- net-taker tier: additionally `net_taker_quote_notional`;
- book-support tier: additionally `net_taker_quote_notional`,
  `average_book_imbalance`, and `bid_replenishment_quote_proxy`.

The fixed ratio is above-mid buy quote notional divided by below-mid sell quote
notional, with threshold `1.50`. A zero denominator is `NO_EVENT`.

For one eligible parent entry, the aggregation is intentionally simple:

| Parent entry | Valid 59-minute window | Selected-tier events | Result |
| --- | --- | --- | --- |
| No | Yes | Any | `NO_PARENT_ENTRY` |
| Yes | Yes | At least one | `ADMIT` |
| Yes | Yes | Zero | `VETO` |
| Any | No | Any | `INVALID_COMPARISON_DATA_REJECT` |

The diagnostic 60-minute cooldown, next-complete-minute price, matched control,
future returns, MFE/MAE, positive-return share, response deltas, and observed
magnitude ranking are not admission inputs.

## Fail-closed integrity

All 59 eligible records are mandatory and must be unique, contiguous, complete,
UTC-aligned, strictly before the fill boundary, and drawn from the exact sealed
OKX `BTC-USDT` `trades` plus `books5` forward-only source. Missing, duplicate,
late, anomalous, unsealed, incomplete, provenance-mismatched, or hash-mismatched
data invalidates that date's paired comparison. It is not silently interpreted
as either an admission or a veto, and the date is excluded from both parent and
candidate with the exclusion reported.

## Matched-parent accounting

The comparator is `BTC_DRA_V1_BASELINE_250_USDT_RESEARCH`. Parent and candidate
must use identical parent decisions, eligible dates, `250.00 USDT` reference
capital, `30.00 USDT` lots, entry and exit logic, next-one-hour-open fills,
`0.10%` fee per side, `0.05%` adverse slippage per side, final valuation,
terminal inventory, and fold boundaries. The sole candidate difference is the
selected-tier entry veto.

A future offline run must retain ordered event, fill, hourly economic-state,
and terminal-lot ledgers. It must report realized, unrealized, and total PnL;
maximum drawdown and path; utilization; blocked entries; holding age;
year/regime concentration; terminal inventory; and data exclusions separately.

## Evidence boundary and next slice

The existing 14-day microstructure window may select one tier only. It is
discovery and cannot become candidate Validation or OOS. Before any adapter or
economic execution, a separate task must freeze a prospective joint DRA plus
microstructure corpus and a matched economic manifest, then implement the
smallest offline adapter and parity tests. The manifest must freeze the same
parent/candidate folds before outcomes are accessed.

Until those prerequisites exist, the selected tier, future corpus, adapter
parity, event/veto frequency, false-veto rate, PnL, drawdown, utilization,
holding risk, concentration, fills, capacity, candidate readiness, OOS value,
and activation remain `MISSING_PROOF`.
