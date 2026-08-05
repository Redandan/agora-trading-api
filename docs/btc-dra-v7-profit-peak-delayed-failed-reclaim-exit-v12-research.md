# BTC DRA V7 Profit-Peak Delayed Failed-Reclaim Exit V12 Research

Date: 2026-08-03

Research identity:
`BTC_DRA_V7_PROFIT_PEAK_DELAYED_FAILED_RECLAIM_EXIT_V12_RESEARCH`

Status before execution: `PREREGISTERED_RESEARCH_ONLY`.

## Objective

Test a deliberately later form of trend confirmation. V12 does not try to
predict the market top. It may manage only a frozen V7 full runner that has
already reached its causal `1R` arm, formed an observable profit peak, broken
the prior 24 complete hourly lows, and then failed to reclaim that broken
level for a fixed 12 or 24 complete hours.

The purpose is to reject ordinary one-hour pullbacks that made V9 exit too
early while retaining a profit-only exit before the V7 parent gives back an
unreasonable amount of an established runner. This is an execution-value
study, not a claim that the factor predicts the full BTC market regime.

## Operational boundary

This is read-only historical research. It does not authorize `SHADOW`,
`PAPER`, `LIVE`, or any Production change. It must not modify runtime,
configuration, DRA V1, V7, position `263`, owner `509`, Grid/OCO, funds,
schedules, databases, Telegram, orders, commits, pushes, or deployment.

The older portable adaptive-discovery workflow is excluded because it scans
AI-generated candidates and conflicts with this repository's strategy-first
runtime and fail-closed preregistration boundary.

## Frozen parent, data, and accounting

- Parent strategy: frozen V7
  `V3C_NONSTRONG_PRE_PARTIAL_1R_PROMOTE_FULL_V2A_ELSE_NET_POSITIVE_EMA5_PARTIAL_24_6`.
- Source: server-local read-only OKX `BTCUSDT` complete causal `1h` bars.
- Preselection cutoff: `2025-01-01T00:00:00Z`.
- Expected preselection rows/hash: `52,608` /
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.
- Design: 2019-2022; Validation: 2023-2024.
- Fair-reset folds: calendar years 2020-2024.
- DRA V1 entry, arm, 30-day expiry, seven-day cooldown, entry routing, and
  V7 promotion are unchanged.
- Independent `30 USDT` lots under a `250 USDT` reference cap.
- Fee `0.10%` and adverse slippage `0.05%` per side.
- Complete-bar decisions and next-hour-open fills.
- Profit-only; no stop loss, forced loss, time exit, or final liquidation.
- No fixed profit percentage and no new entry or promotion threshold.

V7 remains monotonic. A first `1R` promotion cannot be vetoed, delayed,
resized, demoted, or reversed. The unchanged V2A `1.50 ATR` ratchet always has
same-hour precedence.

## Runner-manager arm and observable peak

V12 observes only lots currently on a full runner path:

```text
runnerPath = initial FULL_V2A or V7 promoted

runnerManagerArm = first complete hourly bar where
                   peakEstimatedFullNetPnl
                   >= entryATR14 * originalFilledQuantity
```

The manager cannot act on the arm bar. From the arm onward it records the
causal maximum estimated full-lot net PnL and its associated close. `1R` is
only the already frozen V7 arm and profit-peak qualification; it is not a sell
target.

## Frozen structure break

For an armed full runner with no parent exit queued, a fresh break begins an
observation attempt on complete hour `b` when:

```text
breakLevel = min(low[b-24 ... b-1])
freshBreak = close[b] < breakLevel
```

The 24 lows are complete bars strictly before `b`. One lot may have at most
one active observation attempt. A reclaimed or cancelled attempt may be
followed by a later genuinely fresh prior-24-low break; no second attempt can
start on the same bar that classified the first.

## Two preregistered delayed-confirmation candidates

No other wait, lookback, ATR multiple, EMA, streak, profit floor, or partial
ratio may be tested.

### `POST_1R_PRIOR24_LOW_BREAK_12H_FAILED_RECLAIM`

At exactly `b+12` complete hours, confirm only when:

```text
every close[b+1 ... b+12] <= breakLevel
and close[b+12] <= close[b]
```

### `POST_1R_PRIOR24_LOW_BREAK_24H_FAILED_RECLAIM`

At exactly `b+24` complete hours, confirm only when:

```text
every close[b+1 ... b+24] <= breakLevel
and close[b+24] <= close[b]
```

Any close above `breakLevel` before decision classifies the attempt
`RECLAIMED` and cancels it. A decision close above `close[b]` without reclaim
is `CANCELLED_NO_DOWN_CONFIRM`. A valid confirmation queues the complete
remaining runner only when estimated net PnL is strictly positive. The next
open must also remain strictly net-positive; otherwise the fill is deferred
under the unchanged parent rule.

## Required evidence and audits

Before candidate performance is accepted, reproduce the frozen V1, V2A, and
V7 checkpoints exactly. For Design, Validation, and all annual folds report:

- realized, unrealized, total, maximum drawdown;
- cost-weighted median/P90 holding, utilization, capital-hours, turnover,
  blocked entries, buys/sells/open lots;
- manager arms, fresh-break attempts, reclaimed attempts, no-down-confirm
  cancellations, confirmed-positive and confirmed-nonpositive decisions,
  queues, fills, deferred fills, and arm-to-queue delay;
- per-lot realized delta versus V7 for every manager-filled lot;
- annual total and median-hold wins versus V7.

Every path must audit:

- exact prior-24-low inputs excluding the current bar;
- one active attempt per lot and no same-bar restart after classification;
- all attempts strictly after the first causal `1R` manager arm;
- exact 12/24-hour decision time and complete intervening closes;
- no reclaim and non-rising decision close for every confirmed attempt;
- queue only on a confirmed, estimated-net-positive complete bar;
- parent V2A same-hour precedence;
- unchanged buys, blocked entries, entry routes, promotion records, partial
  decisions, cooldown, quantities, and costs versus V7;
- strictly net-positive actual fills and full accounting reconciliation.

Any mismatch is a fail-closed research rejection.

## Frozen candidate screen

A candidate passes only if every condition holds:

- Design total PnL is strictly greater than V7 Design
  (`94.90277533 USDT`);
- Validation realized and total PnL are strictly greater than V7
  (`96.02789691 / 95.38625667 USDT`);
- Validation ending unrealized PnL is no worse than V7
  (`-0.64164024 USDT`);
- Validation drawdown is no higher than V7 (`6.832349%`);
- Validation median holding is no higher than DRA V1 (`182.5h`) and strictly
  below V7 (`192h`);
- Validation P90 holding is no higher than V7 (`836h`);
- Validation harvest efficiency is strictly greater than V7;
- at least three manager-completed exits occur in both Design and Validation;
- manager fills are strictly fewer than V9's one-hour giveback manager
  (`27` Design and `23` Validation), proving the delay is actually selective;
- total PnL beats V7 in at least `3/5` annual folds;
- median holding beats V7 in at least `3/5` annual folds;
- every invariance, causal, delayed-confirmation, profit-only, and accounting
  audit passes.

If both candidates pass, rank by highest Validation total, then highest
harvest efficiency, then lower median hold, then the shorter wait. No gate may
be relaxed or reinterpreted after results are visible.

## OOS and one-slot seal

No 2025+ row may be read during selection. If and only if one candidate passes
the complete pre-2025 screen, bind it to the exact data, specification,
formula-manifest, V7 dependency, and runner hashes, open 2025+ once, and run
the independent `30 USDT` one-slot overlay. Otherwise return:

```text
NO_CANDIDATE_KEEP_V7_AND_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
```

An OOS pass remains research and cannot authorize SHADOW or LIVE.

## Reproduction commands

```powershell
python research/btc_dra_v7_profit_peak_delayed_failed_reclaim_exit_v12.py preselect `
  --output <new-preselection.json>
```

The OOS command is seal-guarded and may run only from a hash-valid frozen
preselection artifact.
