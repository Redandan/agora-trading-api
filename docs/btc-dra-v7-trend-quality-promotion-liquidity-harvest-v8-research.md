# BTC DRA V7 Trend-Quality Promotion Liquidity-Harvest V8 Research

Date frozen: 2026-08-02

Research identity:
`BTC_DRA_V7_TREND_QUALITY_PROMOTION_LIQUIDITY_HARVEST_V8_RESEARCH`

Candidate:
`V3C_PARTIAL_ELIGIBLE_FIRST_1R_7D_ACCEL_DAILY_EMA20_UP_PROMOTE_FULL_V2A_ELSE_NET_POSITIVE_EMA5_PARTIAL_24_6`

Status at freeze: `PREREGISTERED_PRE_PERFORMANCE`

This is a read-only historical study. It does not modify DRA V1, Production,
runtime configuration, database state, orders, or funds, and it does not
authorize SHADOW or LIVE. The single candidate, formulas, accounting, and
selection gates below are frozen before its performance is queried.

## Objective

This study treats liquidity harvesting as converting causal BTC price swings
into realized profit while releasing reference capacity. It does not claim to
measure exchange order-book depth or maker-fill quality. Those would require
causal spread, depth, and order-event data that are outside this OHLCV study.

V3C is the fast-harvest reference: it sells `24 USDT` on net-positive hourly
EMA5 deterioration without waiting for `1R`, leaving `6 USDT` on V2A. V7
recovers some profit by promoting every pre-partial first `1R` crossing, but
its eight Validation promotions include mediocre continuations. V8 changes
only promotion precision. It does not scan a profit target, allocation, ATR
multiplier, EMA period, or runner quota.

## Unchanged shared contract

- Source: server-local, read-only `md_kline`, OKX `BTCUSDT`, complete `1h`
  candles only.
- DRA V1 entry signal, arm, 30-day expiry, seven-day cooldown, and next-open
  entry fill remain exact. Exit state cannot block or resize a buy.
- Original lot cost: `30 USDT`; reference capacity: `250 USDT`.
- Fee: `0.10%` per side; adverse slippage: `0.05%` per side.
- Lots are independent. There is no runner quota, epoch, entitlement, or
  average-cost exit.
- No fixed profit percentage, stop loss, time exit, forced loss, or final
  liquidation.
- Every exit fills at the next `1h` open and only when that exact tranche is
  strictly net positive after both sides' fee and adverse slippage.
- All decisions use the current or past complete candles only.

## Frozen entry routing and 24/6 harvest path

V8 preserves V3C/V7 entry routing. At the DRA entry-signal daily close:

- entries where both causal seven-day momentum acceleration and daily range
  expansion pass use full V2A immediately;
- all other entries are `PARTIAL_ELIGIBLE`.

For a `PARTIAL_ELIGIBLE` lot:

```text
originalCost = 30 USDT
partialCost  = 24 USDT
runnerCost   = 6 USDT
```

The `24 USDT` tranche does not wait for `1R`. On any complete hourly close it
queues when:

```text
lot is not promoted
and partial fill has not occurred
and hourlyClose < causalHourlyEMA5
and estimated partial-tranche net PnL > 0
```

At a successful next-open fill the `6 USDT` residual is rebased to that open
and follows the unchanged V2A `1.50 ATR` monotone ratchet. At most one partial
fill may occur per original lot.

## Exact first-1R trend-quality promotion

Before the first partial fill, V8 tracks estimated full-lot peak net PnL. The
unique first crossing is:

```text
previousPeakFullNetPnl < entryATR14 * originalFilledQuantity
and peakFullNetPnl >= entryATR14 * originalFilledQuantity
```

At that exact complete hourly close, use the latest complete UTC daily candle
available at or before the hourly close. Let:

```text
recent7d = dailyClose[t] / dailyClose[t-7] - 1
prior7d  = dailyClose[t-7] / dailyClose[t-14] - 1
```

The lot promotes to full V2A only when all four conditions pass:

```text
recent7d > 0
recent7d > prior7d
dailyClose[t] > causalDailyEMA20[t]
causalDailyEMA20[t] > causalDailyEMA20[t-1]
```

Promotion is decided once. A rejected crossing can never promote later and
immediately remains on the ordinary no-`1R` V3C harvest path. If a qualified
promotion and partial condition occur on the same hour, promotion has
precedence. If promotion is rejected and the partial condition passes on that
hour, the partial may queue. Missing Design-inception inputs reject promotion;
Validation and every fair-reset fold must have complete inputs.

Every qualifying lot promotes independently. There is no runner count limit,
tie-break, or cross-lot state.

## Harvest-efficiency accounting

All existing realized, unrealized, total, drawdown, cost-weighted holding,
turnover, utilization, blocked-entry, deferred-fill, route, and reconciliation
metrics remain required.

For every window, derive:

```text
capitalHoursUsdt = referenceCapUsdt
                   * windowHours
                   * avgUtilizationPct / 100

harvestEfficiency = realizedPnlUsdt * 1000 / capitalHoursUsdt
```

The same reported utilization precision and formula apply to V1, V2A, V3C,
V7, and V8. The unit is realized USDT per `1,000 USDT-hours` of occupied
reference capital. This is the primary objective metric; total PnL remains a
hard protection against manufacturing realized profit while hiding inventory
losses.

## Frozen data and checkpoint gate

Preselection may query only complete rows with close time at or before
`2025-01-01T00:00:00`:

- first open: `2019-01-01T00:00:00`;
- rows: `52,608`;
- all data-quality defect counts: zero;
- SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.

Before V8 results are accepted, the runner must reproduce the complete V1
through V7 checkpoint chain, including V7 Validation metrics, route/promotion
audit, and annual fold wins. Hash or checkpoint drift is a hard
`PREREGISTRATION_REJECT`, `DATA_REJECT`, or `BASELINE_PARITY_REJECT`.

## Frozen selection gates

Use Design `2019-2022`, Validation `2023-2024`, and fair-reset folds `2020`,
`2021`, `2022`, `2023`, and `2024`. All indicator, entry, lot, promotion,
partial, capacity, realized-PnL, and equity state reset at every boundary.

The single V8 candidate passes only when all conditions hold:

- Validation total PnL is at least V1;
- Validation realized PnL is at least V1;
- Validation ending unrealized PnL is no worse than V1;
- Validation maximum drawdown is no higher than V1 `7.121498%`;
- Validation cost-weighted median holding is at most `200h`;
- Validation cost-weighted P90 holding is at most `1,000h`;
- Validation turnover is at least V1;
- Validation harvest efficiency is strictly greater than V1;
- harvest efficiency beats V1 in at least three of five fair-reset folds;
- total PnL beats V1 in at least two of five fair-reset folds;
- every promotion decision is the unique first causal `1R` crossing and uses
  complete causal daily inputs;
- every promoted lot passes all four trend-quality conditions;
- rejected promotion decisions never promote later and remain immediately
  eligible for the no-`1R` partial path;
- promotion/partial same-hour ordering follows the frozen rule;
- no entry is blocked or resized by promotion state;
- quantity and allocated cost reconcile exactly;
- every exit fill is strictly net positive after costs; and
- no lot fills more than one partial tranche.

There is one candidate and no ranking. Freeze it exactly when every gate
passes; otherwise emit `NO_CANDIDATE` without changing thresholds.

## OOS and authorization boundary

This task does not open `2025+` OOS. A passing preselection may create a
hash-bound `CANDIDATE_FROZEN` manifest and the pre-2025 independent `30 USDT`
one-slot overlay. Opening OOS remains a separate explicit action and may occur
only once against the exact frozen manifest. A failure emits:

```text
NO_CANDIDATE
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
```

This research cannot modify or deploy Production/runtime/config/database/DRA
V1/position `263`/owner `509`/Grid/OCO/funds/schedules/Telegram/orders. A
historical pass still requires a separate SHADOW proposal and authorization.

## Reproduction command

```powershell
python research/btc_dra_v7_trend_quality_promotion_liquidity_harvest_v8.py preselect `
  --output <preselection.json>
```

The runner uses the existing user-level `AGORA_SSH_KEY` and `AGORA_SSH_HOST`.
Database credentials remain server-local and are never printed or copied.
