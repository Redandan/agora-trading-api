# BTC DRA Flat-Range Qualification-Timing Diagnostic V3D Research

Research identity:
`BTC_DRA_FLAT_RANGE_QUALIFICATION_TIMING_DIAGNOSTIC_V3D_RESEARCH`

Status before execution: `PREREGISTERED_POST_HOC_DIAGNOSTIC_ONLY`.

## Boundary

This is read-only hypothesis-generation research. It does not authorize
`SHADOW`, `PAPER`, `LIVE`, OOS opening, strategy replacement, or any Production
change. It must not modify runtime/configuration, DRA V1, position 263, owner
509, Grid/OCO, funds, schedules, databases, Telegram, or orders.

The study freezes the V3 lower-range entry and changes only the per-lot
qualification milestone that must occur before the same EMA5 reversal exit.
It does not scan numeric thresholds and cannot promote a mode directly. Its
only possible positive result is one next hypothesis for a separately
preregistered study.

## Contamination ledger

- V3 Design, Validation, annual, July, and lot-level holding decomposition were
  inspected before this contract.
- V3 showed that entry-to-upper-third wait, not touch-to-EMA5 reversal, caused
  the holding tail.
- All 2025+ data and July 2026 are already contaminated.

Therefore status can be only `NEXT_HYPOTHESIS_IDENTIFIED_POST_HOC` or
`NO_NEXT_HYPOTHESIS`. Neither status is candidate acceptance.

## Fixed data and accounting

- Server-local OKX `BTCUSDT` 1h complete causal bars.
- Selection cutoff: `2025-01-01T00:00:00Z`.
- Design: 2019-2022; Validation: 2023-2024.
- Fair-reset folds: calendar years 2020-2024.
- `30 USDT` independent lots, `250 USDT` reference cap.
- `0.10%` fee and `0.05%` adverse slippage per side.
- Complete-bar signal, next-hour-open fill, no final liquidation.
- No stop loss, time exit, forced loss, fixed percentage, or `1R` target.

## Frozen V3 entry

The flat formula and 20-day range are unchanged:

```text
flat = abs(EMA20_now - EMA20_5d_ago) <= 0.25 * ATR14

rangeLow20  = minimum low of prior 20 complete UTC days excluding current day
rangeHigh20 = maximum high of prior 20 complete UTC days excluding current day
lowerThird  = rangeLow20 + (rangeHigh20 - rangeLow20) / 3
midpoint    = rangeLow20 + (rangeHigh20 - rangeLow20) / 2
upperThird  = rangeHigh20 - (rangeHigh20 - rangeLow20) / 3

entry = flat
        and previousHourlyClose <= lowerThird
        and currentHourlyClose > lowerThird
        and currentHourlyClose <= midpoint
```

The next-open adverse buy must remain at or below the signal midpoint. Each
filled lot freezes its signal midpoint and upper-third levels. The unchanged
30-day arm, seven-day accepted-signal cooldown, cap, and midpoint-gap
cancellation remain in force.

## Three fixed qualification modes

All qualification observations must occur on a complete hourly bar strictly
after the lot fill. The qualification bar cannot also queue an exit.

1. `FIRST_STRICT_NET_POSITIVE`

```text
qualification = currentEstimatedNetPnl > 0
```

2. `FROZEN_MIDPOINT_TOUCH`

```text
qualification = hourlyClose >= frozenEntryMidpoint
```

3. `FROZEN_UPPER_THIRD_TOUCH`

```text
qualification = hourlyClose >= frozenEntryUpperThird
```

The third mode must reproduce V3 exactly.

## Common exit

On a strictly later complete hourly bar:

```text
exit = qualified
       and currentHourlyClose < causalHourlyEMA5
       and currentEstimatedNetPnl > 0
```

The lot queues for next-hour-open sale. Actual modeled next-open net PnL must
remain strictly positive; otherwise the fill is deferred and qualification is
retained.

## Required evidence

For every mode and window, report the full economic ledger plus:

- entry count, midpoint gap cancellations, qualification count, queue count,
  positive fills, and deferred fills;
- median/P90 entry-to-qualification and qualification-to-queue hours;
- all causal ordering and positivity audits;
- entry-signal overlap between modes to expose cap/exit feedback;
- independent `30 USDT` one-slot metrics and blocked entries.

## Frozen hypothesis-eligibility screen

A mode is eligible only as the next post-hoc hypothesis if all are true:

- Design total PnL is positive.
- Validation total PnL is at least V3 upper-third total
  (`31.92221317 USDT`).
- Validation drawdown is no higher than DRA V1 (`7.121498%`).
- Validation median hold is at most `182.5` hours.
- Validation P90 hold is at most `1418.3` hours.
- Validation has at least 10 completed sells.
- At least four of five fair-reset folds have positive total PnL.
- Every causal and accounting audit passes.

If exactly one mode is eligible, it becomes the sole next hypothesis. If more
than one is eligible, choose lexicographically by highest Validation total,
then lowest drawdown, then lowest median hold. This ranking does not promote a
strategy; it only names the one future preregistered formula.

If none is eligible, status is `NO_NEXT_HYPOTHESIS`. No screen may be relaxed
after results.

## Outputs

The runner must reject output overwrite and emit deterministic JSON with data,
specification, and runner hashes; checkpoint parity; all mode/fold/one-slot
metrics; gates; overlap; and a separate July 2026 post-hoc diagnostic.
