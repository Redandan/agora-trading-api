# BTC DRA Flat-Regime Stale-Inventory Admission Veto V2 Research

Research identity:
`BTC_DRA_FLAT_REGIME_STALE_INVENTORY_ADMISSION_VETO_V2_RESEARCH`

Status before execution: `PREREGISTERED_POST_HOC_HISTORICAL_RESEARCH_ONLY`.

## Boundary

This is a read-only research contract. It does not authorize `SHADOW`, `PAPER`,
or `LIVE`, and it must not change Production, runtime/configuration, DRA V1,
position 263, owner 509, Grid/OCO, funds, schedules, databases, Telegram, or
orders.

The experiment changes only admission of a new flat-sleeve lot. It does not
change the frozen flat-regime formula, reclaim entry, per-lot exit, DRA V1
non-flat route, order accounting, or any existing lot.

## Contamination ledger

- All 2019-2024 V1 flat-sleeve headline results were inspected before this
  contract.
- The 2022 stale-inventory failure and the idea of age/exposure admission
  control were identified from those results.
- DRA V7 2025+ OOS and the July 2026 price path were already opened.

Therefore all results are historical, post-hoc diagnostic evidence. A pass can
produce only `HISTORICAL_GATE_PASS_FORWARD_PENDING`, never a clean OOS claim or
activation authorization.

## Fixed data and accounting

- Server-local `md_kline`, `source=okx`, `symbol=BTCUSDT`, `interval_code=1h`.
- Complete, gap-free, causal hourly bars only.
- Selection cutoff: `2025-01-01T00:00:00Z`.
- Design: 2019-01-01 through 2022-12-31 UTC.
- Validation: 2023-01-01 through 2024-12-31 UTC.
- Fair-reset folds: calendar years 2020 through 2024.
- `30 USDT` per independent lot and `250 USDT` reference open-cost cap.
- `0.10%` fee per side and `0.05%` adverse slippage per side.
- Complete-bar signal with next-hour-open fill.
- No stop loss, time exit, forced loss, or final liquidation.
- Every exit fill must remain strictly net-positive after costs.

## Frozen V1 flat sleeve

The V1 flat formula remains:

```text
flat = abs(EMA20_now - EMA20_5d_ago) <= 0.25 * ATR14

entry = flat
        and previousHourlyClose <= causalDailyEMA20
        and currentHourlyClose > causalDailyEMA20

R = entryCausalATR14 * filledBTCQuantity
exit = peakNetPnl >= 1.0R
       and currentHourlyClose < causalHourlyEMA5
       and currentEstimatedNetPnl >= 0.5R
```

The 30-day arm and seven-day cooldown after an accepted entry signal remain
unchanged. No positive slope, rising trend, breakout, or DRA V1 signal is
required for a flat entry.

## Single preregistered V2 hypothesis

Candidate:
`FLAT_STALE_7D_AND_60USDT_ADMISSION_VETO`

At the close of a complete hourly signal bar, after that bar's scheduled fills
and exits have been processed, inspect only currently open flat-route lots:

```text
flatOpenCost = sum(originalCost of currently open FLAT_SLEEVE lots)
oldestFlatAgeHours = max(signalBarTime - lotFillTime)

staleExposureVeto = oldestFlatAgeHours >= 168
                    and flatOpenCost >= 60 USDT
```

If a frozen flat reclaim signal occurs while `staleExposureVeto` is true, that
signal is rejected and counted. It does not create a lot, does not update the
last accepted-entry time, and does not alter or liquidate existing lots. The
current arm opportunity is consumed; later causal reclaim signals may be
evaluated normally.

The constants are structural rather than performance-selected:

- `168 hours` equals the already-frozen seven-day entry cooldown;
- `60 USDT` equals two standard `30 USDT` lots and 24% of the fixed reference
  cap.

The veto is conjunctive. Fresh inventory alone and exposure alone do not block
entry. There is no sweep of age, cost, EMA, ATR, arm, exit, or lot parameters.

## Research comparators

1. Frozen DRA V1.
2. Frozen flat-sleeve V1 at the 250-USDT cap.
3. V2 inventory-controlled flat sleeve at the same cap.
4. Frozen V1 mutually-exclusive router.
5. V2 mutually-exclusive router:
   - flat regime: only the flat reclaim path is eligible and the admission veto
     applies;
   - non-flat regime: only unchanged DRA V1 entry is eligible and the admission
     veto never applies;
   - existing lots retain their route-specific exits;
   - arm, cooldown, and cap remain shared.
6. Independent 30-USDT one-slot V2 overlay. Because its exposure cannot reach
   60 USDT, it is expected to reproduce the V1 one-slot overlay and serves as
   an implementation invariant, not a scaling claim.

Each V2 result must report total rejected signals, admission-veto rejections,
cap rejections, accepted signals, veto-time open cost, oldest age, route, and
the standard realized/unrealized/total/DD/holding/utilization/turnover/open-cost
ledger.

## Frozen gates

Baseline parity must reproduce the DRA V1 Validation checkpoint exactly. The
frozen flat-sleeve V1 and V1 router must also reproduce their prior Validation
checkpoints.

The V2 standalone flat sleeve passes only if all are true:

- Validation realized and total PnL are positive.
- Validation unrealized PnL is no worse than DRA V1.
- Validation drawdown is no higher than DRA V1.
- Validation median and P90 holding hours are no higher than DRA V1.
- At least four of five fair-reset folds have positive total PnL.
- Every admission rejection satisfies both frozen veto predicates.

The V2 router passes only if all are true:

- Validation total and realized PnL are at least DRA V1.
- Validation unrealized PnL is no worse than DRA V1.
- Validation drawdown is at most DRA V1 plus two percentage points.
- Validation median and P90 holding hours are no higher than DRA V1.
- It beats DRA V1 total PnL and median holding in at least three of five folds.
- Its Validation drawdown is strictly lower than the frozen V1 router's
  `12.399848%`, proving the veto affected its intended failure mode.
- Every admission rejection is a flat-route signal satisfying both veto
  predicates; no non-flat DRA V1 entry is rejected by this gate.

The one-slot overlay must have zero admission-veto rejections and exactly match
the prior one-slot checkpoint. It cannot override another failed gate.

All standalone and router gates must pass for status
`HISTORICAL_GATE_PASS_FORWARD_PENDING`; otherwise status is `NO_CANDIDATE`.
No threshold may be relaxed after results.

## Outputs

The runner must seal output paths against overwrite and emit deterministic JSON
with source, specification, and runner hashes; all comparator metrics; gate
booleans; admission audits; annual folds; and a separately labelled July 2026
post-hoc diagnostic.
