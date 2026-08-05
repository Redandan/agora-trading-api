# BTC DRA Independent-Lot Profit-Lock Exit V1 Research

Date frozen: 2026-08-02

Research identity:
`BTC_DRA_INDEPENDENT_LOT_PROFIT_LOCK_EXIT_V1_RESEARCH`

Candidate:
`ENTRY_ATR_1R_ARM_PEAK_PROFIT_50PCT_LOCK_EXIT`

Status at freeze: `PREREGISTERED_PRE_PERFORMANCE`

This is a read-only historical study. It does not modify DRA V1, create a
runtime strategy, or authorize SHADOW or LIVE. The specification is frozen
before candidate performance is queried.

## Research question

Can every DRA lot independently retain a meaningful trend gain and then exit
on a causal giveback of its own accrued profit, without runner entitlements,
runner quotas, a fixed price-profit target, or a current-volatility trail?

The study tests exactly one formula. It does not scan the `1R` arming level,
the `50%` peak-profit floor, ATR multipliers, indicators, lookbacks, or
confirmation counts.

## Unchanged contract

- Source: server-local, read-only `md_kline`, OKX `BTCUSDT`, complete `1h`
  candles only.
- DRA V1 entry signal, arm, 30-day expiry, seven-day cooldown, and next-open
  entry fill remain exact.
- Lot cost: `30 USDT`; reference capacity: `250 USDT`.
- Fee: `0.10%` per side; adverse slippage: `0.05%` per side.
- Lots remain independent and consume their normal `30 USDT` open-cost
  capacity. Removing runner slots does not remove the `250 USDT` cap.
- No average-cost exit, stop loss, time exit, forced loss, or final liquidation
  is allowed.
- Every queued sale fills at the next `1h` open only when the adverse fill
  remains strictly net positive after both sides' fees and slippage. A failed
  safety check clears the queue and records a deferred exit.

## Causal inputs

Each entry stores the causal daily ATR14 available at its entry-signal close.
ATR14 uses the existing V2A Wilder definition and only complete UTC days.

At every complete hourly close `t`, each open lot calculates the same adverse
estimated liquidation value used by the prior research:

```text
currentNetPnl_t =
    estimatedNetSell(quantity, close[t]) - lotCost

entryRiskR = entryATR14 * filledQuantity
```

The comparison uses deterministic Decimal arithmetic. Monetary reporting and
the profit floor use eight decimals with `ROUND_HALF_UP`.

## Per-lot profit-lock state machine

Each lot owns its state. There is no portfolio runner, breakout entitlement,
trend epoch, or runner count.

### 1. Peak tracking

Beginning with the complete hourly close of the fill bar:

```text
peakNetPnl_t = max(peakNetPnl_t-1, currentNetPnl_t)
```

Peak PnL never decreases.

### 2. Profit maturity / arming

The lot becomes permanently `ARMED` on the first complete hourly close where:

```text
peakNetPnl_t >= entryRiskR
```

Before arming, positive profit alone can never queue a sale. This prevents a
first-tick or microscopic-profit exit.

### 3. Monotone profit floor

For an armed lot:

```text
profitFloor_t = round8(0.50 * peakNetPnl_t)
```

Because peak PnL never decreases, the floor never decreases. The `50%` is a
giveback fraction of the lot's own observed peak net profit, not a fixed
percentage return or price target.

### 4. Exit queue

An armed lot with no queued exit queues a sale on a complete hourly close only
when both conditions hold:

```text
currentNetPnl_t > 0
currentNetPnl_t <= profitFloor_t
```

The giveback is the reversal evidence. No EMA, Donchian, momentum, present ATR,
future high/low, or runner qualification is added.

The next-open adverse fill must still have realized net PnL strictly greater
than zero. If it does not, the queue is cleared; the lot stays armed with its
unchanged historical peak and may queue again on a later qualifying close.

## Required audit

Every window must report the normal realized, unrealized, total, maximum
drawdown, median/P90 holding, utilization, turnover, blocked-entry,
deferred-exit, buy/sell, and ending-open-lot metrics plus:

- lots with and without causal entry ATR14;
- arming count, arming time, entry risk, peak at arm, and hours to arm;
- trigger count, current PnL, peak PnL, profit floor, giveback amount and
  giveback fraction at every queue;
- next-open successful and deferred fills with realized PnL and holding time;
- ending armed/open counts and ending peak/floor state;
- assertions that every queue was armed, peak was at least `1R`, current PnL
  was positive and no greater than the monotone `50%` floor; and
- assertion that every realized exit was strictly net positive.

Missing entry ATR, a negative fill, a floor decrease, or a trigger-condition
violation is a hard candidate failure.

## Frozen data and checkpoint gate

Preselection may query only rows with close time at or before
`2025-01-01T00:00:00`:

- first open: `2019-01-01T00:00:00`;
- rows: `52,608`;
- data-quality defects: all zero;
- SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.

Before the candidate is evaluated, the runner must reproduce exactly:

1. V1 Design, Validation, and fair-reset references;
2. V2A Design and Validation;
3. every V2B Validation checkpoint and annual wins;
4. every V2C Validation checkpoint and annual wins;
5. every V2D Validation checkpoint, audit, and annual wins;
6. V2E Design, Validation, audit, and annual wins; and
7. the prior structural-solution Design, Validation, audit, and annual wins.

Hash drift or data defects return `DATA_REJECT` or
`PREREGISTRATION_REJECT`. Any numeric or operational mismatch returns
`BASELINE_PARITY_REJECT`.

## Frozen selection gates

Use Design `2019-2022`, Validation `2023-2024`, and fair-reset folds `2020`,
`2021`, `2022`, `2023`, and `2024`. Indicators receive only causal warm-up;
entry, lot, peak, arming, queue, profit-floor, and accounting state reset at
every window boundary.

The sole candidate passes only when every condition holds:

- Validation total PnL is at least V1 and at least `90%` of V2A;
- Validation realized PnL is at least V1;
- Validation ending unrealized PnL is no worse than V1;
- Validation maximum drawdown is at most `9.121498%`;
- Validation median holding is at most `182.5h`;
- Validation P90 holding is at most `1,418.3h`;
- total PnL beats V1 in at least three of five fair-reset folds;
- median holding beats V1 in at least three of five folds;
- every bought lot has causal entry ATR14;
- every queue satisfies the frozen arm, profit, and floor conditions;
- every profit floor is monotone; and
- every realized exit is strictly net positive after costs.

No gate may be removed, rounded into a pass, or relaxed after results are
known.

## Decision, OOS, and one-slot boundary

If every gate passes, emit `CANDIDATE_FROZEN` bound to the exact selection
data, specification, V2C/V2D/V2E/prior-structural dependencies, and runner
hashes. Only then may the runner query 2025+ once to an explicit complete-hour
cutoff and calculate the independent `30 USDT` one-slot overlay for Design,
Validation, annual folds, and OOS.

If any gate fails, emit:

```text
NO_CANDIDATE
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
```

The OOS command must reject before data access when no candidate is frozen and
must never overwrite an existing output. An OOS miss cannot trigger
reselection or a second open.

## Reproduction commands

```powershell
python research/btc_dra_independent_lot_profit_lock_exit_v1.py preselect `
  --output <preselection.json>
```

```powershell
python research/btc_dra_independent_lot_profit_lock_exit_v1.py oos `
  --preselect <preselection.json> `
  --cutoff <YYYY-MM-DDTHH:00:00> `
  --output <new-oos-output.json>
```

The runner uses the existing user-level `AGORA_SSH_KEY` and `AGORA_SSH_HOST`.
Database credentials remain on the server and are never printed or copied
locally.

## Authorization boundary

This research cannot add a runtime catalog entry, authorize SHADOW or LIVE,
deploy, write the database, change DRA V1 or position `263`, change owner `509`,
Grid/OCO, funds, schedules, Telegram, or send an order. A historical pass would
still require a separate versioned runtime proposal and explicit authorization.
