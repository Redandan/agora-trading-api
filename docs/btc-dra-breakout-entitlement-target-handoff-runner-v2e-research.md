# BTC DRA Breakout-Entitlement Target-Handoff Runner V2E Research

Status: `RESEARCH_ONLY / OOS_SEALED / FINAL_V2_ITERATION / NOT_IN_RUNTIME_CATALOG / NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE`

Research identity: `BTC_DRA_BREAKOUT_ENTITLEMENT_TARGET_HANDOFF_RUNNER_V2E_RESEARCH`

## Objective and terminal research boundary

Test one final causal explanation for the V2D failure: V2D assigned runners on
the breakout close while their estimated net profits were still thin. V2E lets
a fresh breakout create a one-use entitlement, but delays runner assignment
until a lot has independently earned the default `1.0 entry ATR` target.

This document freezes one candidate before its performance is evaluated. No
factor ablation, ATR multiplier, lookback, event window, target, tie-break, or
gate is scanned. If this candidate fails, the V2 dynamic-exit research line
ends as `DRA_DYNAMIC_EXIT_RESEARCH_STOP / KEEP_V1`; no V2F is inferred.

## Frozen DRA and accounting contract

- Source: server-local, read-only `md_kline` rows for OKX `BTCUSDT`, contiguous
  complete `1h` bars.
- Entry, 30-day arm expiry, seven-day cooldown, and next-open fill: exactly DRA
  V1. Entry requires a complete UTC daily close above causal EMA20, EMA20 above
  its value five complete daily closes earlier, and positive 24-hour momentum.
- Lot size: `30 USDT`; reference capacity: `250 USDT`.
- Fee: `0.10%` per side; adverse slippage: `0.05%` per side.
- Lots remain independent; no average-cost exit is permitted.
- No fixed profit percentage, stop loss, time exit, forced loss, or final
  liquidation is permitted.
- Every queued sale fills at the next `1h` open only when the adverse estimate
  remains strictly net positive after fees and slippage. A failed safety check
  clears the queue and records a deferred exit.

## Causal daily state

Daily values use only complete UTC days aggregated from the hourly source. The
hour whose open time is `23:00` completes the day and updates daily state before
that closed bar's exit decision.

- EMA20 uses `alpha = 2 / 21`, starts at the first causal warm-up close, and is
  rounded to `0.00000001` with `ROUND_HALF_UP` after each update.
- ATR14 uses V2A's Wilder definition. The seed is the arithmetic mean of the
  first 14 complete daily true ranges; later values use
  `(13 * prior ATR14 + current TR) / 14`.
- True range is
  `max(high - low, abs(high - prior close), abs(low - prior close))`.

## Default target

Each lot stores the causal daily ATR14 available at its entry-signal close. A
non-runner lot becomes target-ready on the first complete hourly close where:

```text
estimated net liquidation profit
    >= entry ATR14 * filled lot quantity
```

If no pending entitlement consumes that crossing, the lot queues its normal
next-open target sale. The fill needs only to remain strictly net positive; it
does not need to repeat the ATR threshold.

## Fresh Donchian-20 event

For complete UTC day `t`:

```text
prior20High_t     = max(high[t-20], ..., high[t-1])
prior20High_t-1   = max(high[t-21], ..., high[t-2])

freshBreakout_t =
    close[t] > prior20High_t
    and close[t-1] <= prior20High_t-1
```

This is the same exact V2D event. Both thresholds exclude the current day and
use only complete candles. Remaining above an earlier threshold is not a new
event.

## Trend epoch and entitlement lifecycle

The fair-reset window begins in epoch `0`. A new epoch starts only on a causal
complete-day EMA20 down-cross:

```text
close[t] < EMA20[t] and close[t-1] >= EMA20[t-1]
```

Each epoch has exactly one entitlement state: `NONE`, `PENDING`, or
`CONSUMED`.

1. The first fresh Donchian-20 event while state is `NONE` creates one
   `PENDING` entitlement stamped with the complete daily close time.
2. Later fresh events in the same epoch cannot refresh, replace, or create a
   second entitlement, whether the state is `PENDING` or `CONSUMED`.
3. An EMA20 down-cross expires an unused `PENDING` entitlement before that
   bar's hourly target decision, increments the epoch, and resets the new
   epoch's state to `NONE`.
4. A `CONSUMED` entitlement stays consumed until the next epoch. The assigned
   runner itself keeps its runner exit identity until it exits; an epoch reset
   never forces or queues its sale.
5. An entitlement may be created even when no lot is open. It remains pending
   for later DRA lots until consumed or expired. There is no hour/day timeout
   other than the causal epoch reset.

## First-target handoff

After an entitlement is `PENDING`, each complete hourly close evaluates all
non-runner open lots with causal entry ATR14 and no queued exit.

- The first hourly close with at least one target-ready lot consumes the
  entitlement and assigns exactly one runner.
- If several lots first appear target-ready on that same close, the lot with
  the latest fill time is selected; signal time is the deterministic secondary
  tie-break.
- Other target-ready lots on that close queue their ordinary target sales and
  are reported as same-bar handoff rejections.
- Lots that are not target-ready remain on the default target path.
- A target crossing before the entitlement was created cannot be recovered or
  replayed. A crossing on the same `23:00` close that creates the entitlement
  is eligible because the complete daily event is known before the exit
  decision on that bar.

This is an event entitlement, not a `still above Donchian` state and not a
lookback window. The candidate contains no additional trend-quality factor.

## Runner exit

The handed-off lot uses V2A's unchanged hourly ratchet:

```text
candidate stop = highest closed-hour close since fill - current ATR14 * 1.50
ratchet stop   = max(previous ratchet stop, candidate stop)
```

The stop never moves down. A runner queues an exit only when the complete
hourly close is at or below the ratchet and estimated net PnL is strictly
positive. The next-open fill uses the common net-positive safety floor.

## Single preregistered candidate

```text
FRESH_DONCHIAN20_ENTITLEMENT_FIRST_TARGET_HANDOFF_RUNNER
```

No alternate entitlement expiry, handoff order, lot ranking, breakout filter,
ATR target, or ratchet multiplier is permitted in this study.

## Required entitlement and sparsity audit

Every window must report:

- fresh-breakout count and exact event times;
- entitlement creations, duplicate-event rejections while pending/consumed,
  pending expirations, consumed handoffs, and pending entitlement at end;
- entitlement creation-to-handoff latency;
- target-ready handoff bars, selected signal/fill times, target threshold,
  estimated net PnL, and same-bar rejected lots;
- runner assignments, runner share of buys, runner exits, target exits, and
  ending open runners;
- trend-epoch resets, epoch identifiers, and per-epoch entitlement and runner
  counts; and
- direct uniqueness assertions proving at most one entitlement and one runner
  per epoch.

Epoch entitlement and runner uniqueness are hard gates. Validation runner
handoffs must also remain at or below `ceil(10% * buy_count)`. This is an
acceptance audit, not a future-aware assignment cap.

## Sealed data and checkpoint gate

Preselection may query only rows with close time at or before
`2025-01-01T00:00:00`. The frozen input is:

- first open: `2019-01-01T00:00:00`;
- rows: `52,608`;
- data-quality defects: all zero;
- SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.

Before V2E performance is accepted, the runner must reproduce exactly:

1. V1 Design and Validation;
2. V2A `1.50 ATR` Design and Validation;
3. all three V2B Validation checkpoints and annual wins;
4. all five V2C Validation checkpoints and annual wins; and
5. all three V2D Validation checkpoints, runner audits, and annual wins.

Hash drift or data defects return `DATA_REJECT` or
`PREREGISTRATION_REJECT`. Any checkpoint mismatch returns
`BASELINE_PARITY_REJECT`. None permits V2E selection.

## Frozen selection gates

Use Design `2019-2022`, Validation `2023-2024`, and fair-reset folds `2020`,
`2021`, `2022`, `2023`, and `2024`. Indicators receive only causal warm-up;
arm, cooldown, lots, ledger, epoch, entitlement, and runner state reset at each
window boundary.

The sole candidate passes only when every condition holds:

- Validation total PnL is at least V1 and at least `90%` of V2A;
- Validation realized PnL is at least V1;
- Validation ending unrealized PnL is no worse than V1;
- Validation maximum drawdown is at most `9.121498%`;
- Validation median holding is at most `182.5h`;
- Validation P90 holding is at most `1,418.3h`;
- it beats V1 total PnL in at least three of five fair-reset folds;
- it beats V1 median holding in at least three of five folds;
- entitlement and runner epoch uniqueness both pass; and
- Validation runner handoffs are no more than `ceil(10% * buy_count)`.

Report realized, unrealized, total, drawdown, buys, sells, open lots, blocked
entries, deferred exits, median/P90 holding, average/peak utilization,
turnover, target/runner attribution, and the complete entitlement audit.

If any gate fails, freeze:

```text
NO_CANDIDATE
DRA_DYNAMIC_EXIT_RESEARCH_STOP
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
```

No failed gate may be removed or relaxed.

## OOS and one-slot one-open rule

Only a `CANDIDATE_FROZEN` manifest bound to the exact preselection data,
specification, V2C/V2D dependency, and V2E runner hashes may query
`2025-01-01` onward once to an explicit complete-hour cutoff.

That one OOS run compares V1, V2A, and frozen V2E and calculates the independent
`30 USDT` one-slot overlay for Design, Validation, annual folds, and OOS. The
overlay uses `30 USDT` as both capacity and equity reference and reports
blocked entries.

An OOS miss returns `OUT_OF_SAMPLE_FAIL` and cannot trigger a second open or
reselection. A failed preselection must reject OOS before any data access and
must not overwrite an existing OOS output.

## Reproduction commands

```powershell
python research/btc_dra_breakout_entitlement_target_handoff_runner_v2e.py preselect `
  --output <preselection.json>
```

```powershell
python research/btc_dra_breakout_entitlement_target_handoff_runner_v2e.py oos `
  --preselect <preselection.json> `
  --cutoff <YYYY-MM-DDTHH:00:00> `
  --output <new-oos-output.json>
```

The runner uses the existing user-level `AGORA_SSH_KEY` and `AGORA_SSH_HOST`.
Database credentials remain inside the server process and are never printed or
copied locally.

## Authorization boundary

V2E is historical research only. It cannot add a runtime catalog entry,
authorize SHADOW or LIVE, deploy, write the database, change DRA V1 or position
`263`, change owner `509`, Grid/OCO, funds, schedules, Telegram, or send an
order. A historical pass would still require a separate versioned runtime
proposal and explicit authorization.
