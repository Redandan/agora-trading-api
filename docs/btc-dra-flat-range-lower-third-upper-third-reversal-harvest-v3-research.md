# BTC DRA Flat-Range Lower-Third / Upper-Third Reversal Harvest V3 Research

Research identity:
`BTC_DRA_FLAT_RANGE_LOWER_THIRD_UPPER_THIRD_REVERSAL_HARVEST_V3_RESEARCH`

Status before execution: `PREREGISTERED_POST_HOC_HISTORICAL_RESEARCH_ONLY`.

## Boundary

This is a read-only research contract. It does not authorize `SHADOW`, `PAPER`,
or `LIVE`, and it must not change Production, runtime/configuration, DRA V1,
position 263, owner 509, Grid/OCO, funds, schedules, databases, Telegram, or
orders.

The candidate is a standalone flat-range sleeve. It is not mixed with the DRA
V1 router, does not use the failed stale-inventory admission veto, and does not
modify any existing strategy. The hypothesis tests whether economic entry and
exit location can solve the holding-tail problem more directly than limiting
new entries.

## Contamination ledger

- DRA V1, flat sleeve V1, the stale-inventory V2 experiment, and their
  2019-2024 results were inspected before this contract.
- The lower-range entry / upper-range reversal hypothesis was generated after
  reviewing those failures and the July 2026 trade prices.
- DRA V7 2025+ OOS and the July 2026 path were already opened.

Therefore all historical results are post-hoc robustness evidence. A pass can
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
- Complete-bar signals and next-hour-open fills.
- No stop loss, time exit, forced loss, fixed-profit exit, `1R` arm, or final
  liquidation.
- Every sell fill must remain strictly net-positive after costs.
- The existing 30-day arm and seven-day cooldown after an accepted entry signal
  remain unchanged.

## Frozen flat regime

The V1 flat regime remains unchanged. Daily EMA20 and Wilder ATR14 use complete
UTC daily bars only:

```text
flat = abs(EMA20_now - EMA20_5d_ago) <= 0.25 * ATR14
```

No positive EMA slope, rising trend, breakout, momentum, or DRA V1 signal is
required.

## Frozen 20-day range

At each complete hourly signal bar, use the 20 complete UTC daily bars strictly
before the current UTC day. The current partial day is never included; at
23:00 UTC, the newly completed current day is also excluded.

```text
rangeLow20  = minimum(dailyLow of prior 20 complete UTC days)
rangeHigh20 = maximum(dailyHigh of prior 20 complete UTC days)
rangeWidth  = rangeHigh20 - rangeLow20
lowerThird  = rangeLow20 + rangeWidth / 3
midpoint    = rangeLow20 + rangeWidth / 2
upperThird  = rangeHigh20 - rangeWidth / 3
```

`rangeWidth` must be strictly positive.

## Single preregistered candidate

Candidate:
`FLAT_DONCHIAN20_LOWER_THIRD_RECLAIM_UPPER_THIRD_TOUCH_EMA5_REVERSAL`

### Entry signal

On a complete hourly bar:

```text
entrySignal = flat
              and previousHourlyClose <= lowerThird
              and currentHourlyClose > lowerThird
              and currentHourlyClose <= midpoint
```

The signal queues one `30 USDT` buy for the next hourly open, subject only to
the unchanged `250 USDT` cap. At fill time:

```text
effectiveAdverseBuyPrice <= signalMidpoint
```

Otherwise the buy is cancelled and counted as a midpoint gap cancellation. A
cancelled fill still consumes the already accepted signal's seven-day cooldown;
it does not re-arm immediately.

Each filled lot freezes the signal bar's `rangeLow20`, `rangeHigh20`,
`lowerThird`, `midpoint`, and `upperThird`. Later boundary movement cannot arm
the lot.

### Upper-range qualification and exit

A filled lot becomes upper-qualified only after a later complete hourly close
reaches its frozen upper-third level:

```text
upperQualified = hourlyClose >= frozenEntryUpperThird
```

The touch bar only records qualification. It cannot also queue an exit. On a
strictly later complete hourly bar:

```text
exitSignal = upperQualified
             and currentHourlyClose < causalHourlyEMA5
             and currentEstimatedNetPnl > 0
```

The full lot queues for next-hour-open sale. The actual modeled fill is
cancelled if fees and adverse slippage make net PnL non-positive. Qualification
remains attached to the lot after a deferred fill.

This exit has no fixed percentage or ATR-profit target. Net-positive is a
safety floor, while causal upper-range reach followed by short-trend reversal
is the economic sell condition.

## Comparators

1. Frozen DRA V1, unchanged.
2. Frozen flat sleeve V1, unchanged.
3. The standalone V3 range-location candidate at the `250 USDT` cap.
4. Independent `30 USDT` one-slot V3 accept/block overlay.

No combined or mutually-exclusive DRA router is evaluated in this phase. A
standalone failure stops the study; route mixing cannot rescue it.

## Required audit

The runner must report:

- every signal's causal range bounds and position;
- accepted fills and midpoint gap cancellations;
- per-lot frozen upper-third level;
- upper-touch time and proof that exit queue time is later;
- EMA5 reversal and estimated net-positive checks;
- actual next-open fill positivity and deferred exits;
- entries, exits, open lots, blocked entries, open cost, realized, unrealized,
  total, drawdown, holding, open age, utilization, and turnover.

## Frozen gates

Baseline parity must reproduce the DRA V1 and flat sleeve V1 Validation
checkpoints exactly.

The V3 candidate passes only if all are true:

- Validation total PnL is at least flat sleeve V1 total PnL
  (`34.25578664 USDT`).
- Validation realized PnL is positive.
- Validation unrealized PnL is no worse than DRA V1
  (`-3.20820121 USDT`).
- Validation drawdown is no higher than DRA V1 (`7.121498%`).
- Validation median holding is at most `182.5` hours.
- Validation P90 holding is at most `1418.3` hours.
- Validation has at least 10 completed sells.
- At least four of five fair-reset folds have positive total PnL.
- Every entry, range freeze, upper touch, reversal, and next-open fill audit
  passes causally.

The independent one-slot overlay must report the same ledger and blocked
entries. It cannot override a failed reference candidate.

If all gates pass, status is `HISTORICAL_GATE_PASS_FORWARD_PENDING`. Otherwise
status is `NO_CANDIDATE`. No threshold may be changed after results.

## Outputs

The runner must reject output overwrite and emit deterministic JSON containing
source, specification, and runner hashes; exact checkpoint evidence; complete
metrics; gate booleans; range/entry/exit audits; annual folds; the one-slot
overlay; and a separately labelled July 2026 post-hoc price diagnostic.
