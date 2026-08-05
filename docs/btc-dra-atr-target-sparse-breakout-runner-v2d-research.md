# BTC DRA ATR-Target Sparse Breakout Runner V2D Research

Status: `RESEARCH_ONLY / OOS_SEALED / NOT_IN_RUNTIME_CATALOG / NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE`

Research identity: `BTC_DRA_ATR_TARGET_SPARSE_BREAKOUT_RUNNER_V2D_RESEARCH`

## Objective and frozen hypothesis

Test whether DRA can preserve a small part of V2A's large-trend return while
restoring V1-level capital recycling. Most lots take a causal `1.0 entry ATR`
profit target. Only one causally selected lot per trend epoch may become a
sparse breakout runner and use V2A's `1.50 ATR` ratchet.

This is a new preregistered study. It does not reopen the failed V2A, V2B, or
V2C candidate families and does not inspect another ATR multiplier. Candidate
formulas and gates below are frozen before candidate performance is evaluated.

## Frozen common contract

- Source: server-local, read-only `md_kline` rows for OKX `BTCUSDT`, contiguous
  complete `1h` bars.
- Entry, arm, expiry, and cooldown: exactly DRA V1. Entry requires the complete
  UTC daily close above causal EMA20, EMA20 above its value five complete daily
  closes earlier, and positive 24-hour momentum. Arm expiry is 30 days and
  entry-signal cooldown is seven days.
- Fill timing: a decision on a complete closed bar fills at the next `1h` open.
- Lot size: `30 USDT`; reference capacity: `250 USDT`.
- Fee: `0.10%` per side; adverse slippage: `0.05%` per side.
- Lots remain independent. No average-cost exit is permitted.
- No fixed profit percentage, stop loss, time exit, forced loss exit, or final
  liquidation is permitted.
- A queued V2D sell fills only when the adverse next-open estimate remains
  strictly net positive after fees and slippage. A failed safety check clears
  the queue and records a deferred exit.

## Causal daily state

All daily values use complete UTC days aggregated from the hourly source. The
hour whose open time is `23:00` completes the day and may update that day's
state before the exit decision on that closed bar.

- EMA20 uses `alpha = 2 / 21`, starts at the first warm-up daily close, and is
  rounded to `0.00000001` with `ROUND_HALF_UP` after each update.
- ATR14 uses the V2A Wilder definition. The first ATR14 is the arithmetic mean
  of the first 14 complete daily true ranges; later values use
  `(13 * prior ATR14 + current TR) / 14`.
- Daily true range is
  `max(high - low, abs(high - prior close), abs(low - prior close))`.

## Default `1.0 entry ATR` target

Each lot stores the causal daily ATR14 available at its entry-signal close. A
non-runner lot queues a sell on the first complete hourly close where:

```text
estimated net liquidation profit
    >= entry ATR14 * filled lot quantity
```

The signal threshold is denominated in USDT and is not a fixed return
percentage. The next-open fill needs only to remain strictly net positive; it
does not need to repeat the ATR target threshold.

## Fresh Donchian-20 event

For complete UTC day `t`, define:

```text
prior20High_t     = max(high[t-20], ..., high[t-1])
prior20High_t-1   = max(high[t-21], ..., high[t-2])

freshBreakout_t =
    close[t] > prior20High_t
    and close[t-1] <= prior20High_t-1
```

Both comparisons use only complete days. A close that merely remains above a
prior high is not a fresh event. Intraday highs and the current day's high are
not included in either Donchian threshold.

## Trend epochs and deterministic runner assignment

The initial warm state is epoch `0`. A new epoch starts only on a complete-day
down-cross:

```text
close[t] < EMA20[t] and close[t-1] >= EMA20[t-1]
```

The down-cross increments the epoch and clears that epoch's runner-assignment
slot. A runner selected in an earlier epoch keeps its runner exit identity
until it exits; the reset never forces a sale. No epoch may assign more than
one runner.

On a fresh breakout that passes the candidate-specific confirmation:

1. eligible lots are open, have causal entry ATR14, have no queued exit, have
   not already been assigned as a runner, and have strictly positive estimated
   net liquidation PnL on that complete daily close;
2. if the epoch slot is free, the eligible lot with the latest fill time is
   assigned as the runner; this fixed newest-lot rule aligns the retained lot
   with the newest breakout inventory and leaves older inventory on the target;
3. any additional eligible lots are rejected by the same-event one-runner cap;
4. if the epoch slot is already consumed, all otherwise eligible lots are
   rejected for that event; and
5. a fresh event with no eligible lot consumes no slot, so a later genuinely
   fresh event in the same epoch may qualify.

Every non-runner continues to use the default target. Assignment is based on
the fresh event itself, not on a later `still above Donchian` state.

## Runner exit

For an assigned runner, every complete hourly close updates:

```text
candidate stop = highest closed-hour close since fill - current ATR14 * 1.50
ratchet stop   = max(previous ratchet stop, candidate stop)
```

The ratchet never moves down. A runner queues an exit when the closed price is
at or below the ratchet and estimated net PnL is strictly positive. The fill is
the next open subject to the same net-positive safety floor.

## Three preregistered candidates

No formula or threshold may change after performance is inspected.

1. `FRESH_DONCHIAN20_RUNNER`
   - confirmation is exactly `freshBreakout_t`.
2. `FRESH_DONCHIAN20_PLUS_7D_MOMENTUM_ACCELERATION`
   - define `M7_recent = close[t] / close[t-7] - 1`;
   - define `M7_previous = close[t-7] / close[t-14] - 1`;
   - pass only when `M7_recent - M7_previous > 0` on the fresh-breakout close.
3. `FRESH_DONCHIAN20_PLUS_DAILY_RANGE_EXPANSION`
   - calculate the just-completed `TR[t]` using the formula above;
   - compare it with `ATR14[t-1]`, which was available before day `t` began;
   - pass only when `TR[t] > ATR14[t-1]` on the fresh-breakout close.

The zero acceleration threshold and `1.0` prior-ATR range threshold are fixed.
There is no magnitude sweep, lookback sweep, or alternative normalization.

## Sparse-runner audit

For every candidate and window, report:

- fresh-breakout events and candidate-confirmed events;
- epoch resets and distinct epochs observed;
- runner assignments, runner share of buys, and runner exits;
- exact assigned runner fill times and epoch identifiers;
- fresh events with no eligible lot;
- candidate-filter-rejected fresh events;
- eligible lots rejected because the epoch slot was already used;
- eligible lots rejected by the same-event one-runner tie-break; and
- a direct uniqueness audit proving no epoch assigned more than one runner.

`epoch uniqueness` is a hard gate. To keep the hypothesis genuinely sparse,
Validation assignments must also be no more than `ceil(10% * buy_count)`.
This is an acceptance audit only; the evaluator does not stop assigning at a
global count and therefore cannot manufacture sparsity from future knowledge.

## Sealed data and checkpoint gate

Preselection may query only rows with close time at or before
`2025-01-01T00:00:00`. The accepted input is:

- first open: `2019-01-01T00:00:00`;
- rows: `52,608`;
- data-quality defect counts: all zero;
- SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.

Before V2D candidate results are accepted, the runner must exactly reproduce:

1. V1 Design and Validation checkpoints;
2. V2A `1.50 ATR` Design and Validation checkpoints;
3. all three V2B Validation checkpoints and annual win counts; and
4. all five V2C Validation checkpoints and annual win counts.

Any mismatch returns `BASELINE_PARITY_REJECT`. Input drift or data-quality
failure returns `DATA_REJECT`. Neither result permits candidate selection.

## Frozen selection protocol

Use Design `2019-2022`, Validation `2023-2024`, and fair-reset calendar folds
`2020`, `2021`, `2022`, `2023`, and `2024`. Indicators receive causal warm-up,
but arm, expiry, cooldown, lots, realized PnL, equity, epochs, and runner slots
reset at each window boundary.

A candidate passes only when every condition holds:

- Validation total PnL is at least V1 and at least `90%` of V2A;
- Validation realized PnL is at least V1;
- Validation ending unrealized PnL is no worse than V1;
- Validation maximum drawdown is at most `9.121498%`;
- Validation median holding is at most `182.5h`;
- Validation P90 holding is at most `1,418.3h`;
- it beats V1 total PnL in at least three of five fair-reset folds;
- it beats V1 median holding in at least three of five folds;
- no epoch has more than one assigned runner; and
- Validation runner assignments are no more than `ceil(10% * buy_count)`.

Report buys, sells, open lots, blocked entries, deferred exits, realized,
unrealized, total, drawdown, average and peak utilization, median/P90 holding,
turnover, target/runner exit attribution, and the complete sparse-runner audit.

Rank passing candidates by Validation total PnL, then lower drawdown, shorter
P90, shorter median, and fewer runners. Freeze exactly one candidate or
`NO_CANDIDATE`. A failed gate may not be relaxed after results are known.

## OOS and one-slot one-open rule

`2025-01-01` onward remains physically unqueried until a single candidate is
frozen in a manifest tied to the exact selection-data, specification,
dependency-runner, and V2D-runner hashes.

Only that manifest may open OOS once to an explicit final complete-hour cutoff.
The same run compares V1, V2A, and frozen V2D and calculates the independent
`30 USDT` one-slot V2D overlay for Design, Validation, annual folds, and OOS.
The overlay uses the same exit and runner rules, `30 USDT` as both capacity and
equity reference, and reports blocked entries explicitly.

`NO_CANDIDATE` returns `OOS_NOT_OPENED / ONE_SLOT_OVERLAY_NOT_RUN`. An OOS miss
returns `OUT_OF_SAMPLE_FAIL` and cannot trigger reselection or a second open.

## Reproduction commands

Preselection, physically capped before 2025:

```powershell
python research/btc_dra_atr_target_sparse_breakout_runner_v2d.py preselect `
  --output <preselection.json>
```

OOS, permitted only for an exact frozen manifest:

```powershell
python research/btc_dra_atr_target_sparse_breakout_runner_v2d.py oos `
  --preselect <preselection.json> `
  --cutoff <YYYY-MM-DDTHH:00:00> `
  --output <new-oos-output.json>
```

The runner uses the existing user-level `AGORA_SSH_KEY` and `AGORA_SSH_HOST`.
Database credentials remain inside the server process and are neither copied
nor printed locally.

## Authorization boundary

This research cannot add a runtime catalog entry or authorize SHADOW, LIVE,
capital, deployment, database changes, scheduler changes, orders, or any
Production mutation. DRA V1, position `263`, owner `509`, Grid/OCO, funds, and
all current schedules remain unchanged. Any later implementation requires a
separate versioned proposal and explicit authorization.
