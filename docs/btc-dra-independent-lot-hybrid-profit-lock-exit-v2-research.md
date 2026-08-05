# BTC DRA Independent-Lot Hybrid Profit-Lock Exit V2 Research

Date frozen: 2026-08-02

Research identity:
`BTC_DRA_INDEPENDENT_LOT_HYBRID_PROFIT_LOCK_EXIT_V2_RESEARCH`

Candidate:
`ENTRY_ATR_1R_ARM_MAX_HALF_PEAK_OR_PEAK_MINUS_1R_LOCK_EXIT`

Status at freeze: `PREREGISTERED_PRE_PERFORMANCE`

This is a read-only historical study. It does not modify DRA V1, create a
runtime strategy, or authorize SHADOW or LIVE. Candidate rules and gates are
frozen before performance is queried.

## Prior-result diagnosis and research question

The preceding independent-lot `50%` peak-profit lock produced Validation total
`96.72713306`, above V1, but its advantage was entirely ending unrealized PnL.
It allowed two large winning lots to give back more than `6 USDT` each while
remaining above their half-peak floors. Realized PnL, drawdown, median holding,
annual holding wins, and `90%` V2A retention failed.

A partial split between the prior full-lot profit lock and the lower-return
`1R` core path cannot exceed the higher endpoint when Validation has no
blocked entries. It is therefore not tested here.

This study asks whether a risk-normalized maximum giveback can convert large
peaks into realized profit without forcing small winners to exit on the first
positive tick.

## Unchanged contract

- Source: server-local, read-only `md_kline`, OKX `BTCUSDT`, complete `1h`
  candles only.
- DRA V1 entry signal, arm, 30-day expiry, seven-day cooldown, and next-open
  entry fill remain exact.
- Lot cost: `30 USDT`; reference capacity: `250 USDT`.
- Fee: `0.10%` per side; adverse slippage: `0.05%` per side.
- Lots are independent and use normal open-cost capacity. There is no runner,
  entitlement, breakout, epoch, or runner quota.
- No average-cost exit, stop loss, time exit, forced loss, or final liquidation
  is allowed.
- Every queued sale fills at the next `1h` open only when the adverse fill
  remains strictly net positive after both sides' fees and slippage. A failed
  safety check clears the queue and records a deferred exit.

## Causal per-lot state

Each entry stores causal daily ATR14 at its entry-signal close. ATR14 uses the
existing V2A Wilder formula and complete UTC days only.

At complete hourly close `t`:

```text
currentNetPnl_t =
    estimatedNetSell(quantity, close[t]) - lotCost

entryRiskR = entryATR14 * filledQuantity

peakNetPnl_t = max(peakNetPnl_t-1, currentNetPnl_t)
```

The lot becomes permanently armed once:

```text
peakNetPnl_t >= entryRiskR
```

Positive profit before `1R` cannot queue a sale.

## Single hybrid profit floor

For every armed lot, calculate:

```text
halfPeakFloor_t = round8(0.50 * peakNetPnl_t)

oneRGivebackFloor_t = round8(peakNetPnl_t - entryRiskR)

effectiveProfitFloor_t =
    max(halfPeakFloor_t, oneRGivebackFloor_t)
```

The floor is monotone because peak PnL never decreases and entry risk is
fixed. From `1R` through `2R`, the half-peak branch binds. Above `2R`, the
one-entry-ATR branch prevents giving back more than `1R` of accrued net profit.

This is not a fixed percentage return or fixed price-profit target. It is one
predeclared hybrid formula; neither branch contains a scanned parameter.

## Exit queue

An armed lot with no queued exit queues at a complete hourly close only when:

```text
currentNetPnl_t > 0
currentNetPnl_t <= effectiveProfitFloor_t
```

The next-open adverse fill must remain strictly net positive. A failed fill
clears the queue while preserving the lot's armed state, peak, and monotone
floor.

No EMA, Donchian, momentum, current ATR, future high/low, or additional
confirmation is permitted.

## Required audit

Every window reports the normal realized, unrealized, total, maximum drawdown,
median/P90 holding, utilization, turnover, blocked-entry, deferred-exit,
buy/sell, and ending-open-lot metrics plus:

- entry-ATR completeness, arming records, `1R`, peak PnL, and time to arm;
- both floor branches, effective floor, binding branch, current PnL, giveback,
  and trigger time for every queue;
- successful and deferred next-open fills and realized net PnL;
- binding counts for `HALF_PEAK`, `PEAK_MINUS_1R`, and exact ties;
- ending armed/open states with both floor values;
- assertions that every queue was armed, peak was at least `1R`, current PnL
  was positive and no greater than the effective floor; and
- assertions that floors never decreased and every realized exit was strictly
  net positive.

Missing entry ATR, a condition violation, a decreasing floor, or a
non-positive realized exit is a hard candidate failure.

## Frozen data and checkpoint gate

Preselection may query only complete rows with close time at or before
`2025-01-01T00:00:00`:

- first open: `2019-01-01T00:00:00`;
- rows: `52,608`;
- data-quality defects: all zero;
- SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.

Before candidate performance is accepted, the runner must exactly reproduce:

1. V1, V2A, every V2B profile, every V2C factor, every V2D candidate/audit,
   and V2E;
2. the prior global-one-runner structural study; and
3. the preceding independent-lot `1R` arm / `50%` peak-profit lock Design,
   Validation, audit, and annual wins.

Hash drift or data defects return `DATA_REJECT` or
`PREREGISTRATION_REJECT`. Any numeric or operational mismatch returns
`BASELINE_PARITY_REJECT`.

## Frozen selection gates

Use Design `2019-2022`, Validation `2023-2024`, and fair-reset folds `2020`,
`2021`, `2022`, `2023`, and `2024`. Causal indicator warm-up is allowed;
entry, lot, peak, arming, queue, floor, and accounting state reset at each
window boundary.

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
- every queue satisfies the frozen arm, profit, and hybrid-floor conditions;
- every effective floor is monotone; and
- every realized exit is strictly net positive after costs.

No failed gate may be removed, rounded into a pass, or relaxed.

## OOS, one-slot, and authorization boundary

If every gate passes, emit `CANDIDATE_FROZEN` bound to the exact selection
data, specification, all dependency hashes, and runner hash. Only then may the
runner query 2025+ once to one explicit complete-hour cutoff and calculate the
independent `30 USDT` one-slot overlay for Design, Validation, annual folds,
and OOS.

If any gate fails, emit:

```text
NO_CANDIDATE
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
```

The OOS command must reject before data access when no candidate is frozen and
must never overwrite an existing output. An OOS miss cannot trigger
reselection.

This research cannot add a runtime catalog entry, authorize SHADOW or LIVE,
deploy, write the database, change DRA V1 or position `263`, change owner `509`,
Grid/OCO, funds, schedules, Telegram, or send an order. A historical pass still
requires a separate versioned runtime proposal and explicit authorization.

## Reproduction commands

```powershell
python research/btc_dra_independent_lot_hybrid_profit_lock_exit_v2.py preselect `
  --output <preselection.json>
```

```powershell
python research/btc_dra_independent_lot_hybrid_profit_lock_exit_v2.py oos `
  --preselect <preselection.json> `
  --cutoff <YYYY-MM-DDTHH:00:00> `
  --output <new-oos-output.json>
```

The runner uses the existing user-level `AGORA_SSH_KEY` and `AGORA_SSH_HOST`.
Database credentials remain on the server and are never printed or copied
locally.
