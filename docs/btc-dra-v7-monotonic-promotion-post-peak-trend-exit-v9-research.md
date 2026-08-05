# BTC DRA V7 Monotonic-Promotion Post-Peak Trend Exit V9 Research

Research identity:
`BTC_DRA_V7_MONOTONIC_PROMOTION_POST_PEAK_TREND_EXIT_V9_RESEARCH`

Status before execution: `PREREGISTERED_RESEARCH_ONLY`.

## Objective

Test whether a causal lot-level post-peak trend manager can strictly improve
the frozen V7 hybrid without allowing a slower market-trend label to veto a
runner that has already been confirmed by its own price path.

V7 remains the parent strategy. A first `1R` crossing is monotonic: once a lot
earns runner status, no V9 factor may cancel, delay, resize, or reverse that
promotion. V9 may only queue an earlier full-lot profit-only exit after runner
status already exists.

## Operational boundary

This is read-only research. It does not authorize `SHADOW`, `PAPER`, `LIVE`,
or any Production change. It must not modify runtime/configuration, DRA V1,
position `263`, owner `509`, Grid/OCO, funds, schedules, databases, Telegram,
orders, commits, or deployment.

## Frozen data and accounting

- Server-local OKX `BTCUSDT` 1h complete causal bars only.
- Selection cutoff: `2025-01-01T00:00:00Z`.
- Design: 2019-2022; Validation: 2023-2024.
- Fair-reset folds: calendar years 2020-2024.
- DRA V1 entry, arm, 30-day expiry, and seven-day cooldown unchanged.
- Independent `30 USDT` lots under a `250 USDT` reference cap.
- `0.10%` fee and `0.05%` adverse slippage per side.
- Complete-bar signal and next-hour-open fill.
- Profit-only; no stop loss, forced loss, time exit, or final liquidation.
- No fixed profit percentage and no new entry or promotion threshold.

The V7 entry routing and exit paths remain frozen:

- 18 Validation lots selected by the frozen V4 entry-quality conjunction
  begin on full V2A `1.50 ATR` ratchet;
- the other 33 begin on V3C's immediate net-positive plus hourly EMA5
  `24/6` partial path;
- a partial-eligible lot that first reaches
  `entry ATR14 * original quantity` before partial fill promotes
  unconditionally to the full V2A path;
- non-promoted lots never wait for `1R`.

## Runner-manager arm

V9 observes only lots currently on a full runner path:

```text
runnerPath = initial FULL_V2A or V7 promoted
runnerManagerArm = first complete hourly bar where
                   peakEstimatedFullNetPnl >= entryAtr14 * originalQuantity
```

The manager does not act on a partial remainder and may not queue an exit on
the arm bar. Every V9 exit decision must occur on a strictly later complete
hourly bar. The unchanged V2A ratchet has same-bar precedence; V9 is recorded
only when it queues an exit earlier than the parent path.

For every armed runner, V9 causally tracks:

```text
currentNetPnl = estimated net proceeds at complete hourly close - current lot cost
peakNetPnl = maximum currentNetPnl observed since manager arm
giveback = peakNetPnl - currentNetPnl
oneR = entryAtr14 * original filled quantity
ema5Downturn = close < causal hourly EMA5
               and causal hourly EMA5 < prior bar causal hourly EMA5
```

`oneR` is the already frozen V7 natural risk unit. It is not a sell target.

## Three preregistered ablations

1. `POST_1R_PEAK_GIVEBACK_1R`

```text
queue = armed on a prior bar
        and giveback >= oneR
        and currentNetPnl > 0
```

2. `POST_1R_EMA5_DOWNTURN`

```text
queue = armed on a prior bar
        and ema5Downturn
        and currentNetPnl > 0
```

3. `POST_1R_PEAK_GIVEBACK_1R_AND_EMA5_DOWNTURN`

```text
queue = armed on a prior bar
        and giveback >= oneR
        and ema5Downturn
        and currentNetPnl > 0
```

All queues fill at the next open only if actual modeled net PnL remains
strictly positive. A non-positive next-open fill is deferred under the parent
profit-only mechanism. No neighboring giveback multiple, EMA length, streak,
profit floor, or partial ratio may be tested.

## Required invariance and causal audits

For the `250 USDT` Design, Validation, and every annual fold:

- V1, V2A, and V7 checkpoints reproduce exactly;
- candidate buy times/counts, entry routes, V7 promotion times/counts, and
  partial-path decisions equal the V7 parent before V9 exit divergence;
- all 1R promotions remain unconditional and no trend factor rejects one;
- V9 never blocks/resizes an entry, consumes a quota, or changes cooldown;
- every manager arm is a first causal 1R crossing on a runner path;
- every V9 queue is strictly after arm and exactly matches factor truth;
- the parent V2A ratchet has same-hour precedence;
- quantity/cost allocation reconciles and every exit fill is net-positive.

If a candidate passes and a natural independent `30 USDT` one-slot overlay is
opened, earlier V9 exits may legitimately free the single slot sooner. The
overlay must therefore preserve the raw DRA signal/cooldown reservation clock
and report capacity-blocked entries separately; it is not required to preserve
the parent's filled-buy subset after exit paths diverge.

Any mismatch returns a fail-closed research rejection.

## Required evidence

For V1, V2A, V7, and every V9 ablation, report:

- realized, unrealized, total, maximum drawdown;
- cost-weighted median/P90 holding;
- utilization, capital-hours, turnover, blocked entries, buys/sells/open lots;
- manager arms, queues, successful fills, deferred fills, and trigger delays;
- Design, Validation, and annual fair-reset ledgers;
- exact per-lot delta versus V7 for every manager-affected lot;
- independent `30 USDT` one-slot evidence only after one candidate passes the
  pre-2025 frozen screen.

## Frozen candidate screen

A V9 mode must satisfy every condition:

- Design total PnL strictly greater than V7 Design
  (`94.90277533 USDT`);
- Validation realized and total PnL strictly greater than V7
  (`96.02789691 / 95.38625667 USDT`);
- Validation unrealized no worse than V7 (`-0.64164024 USDT`);
- Validation drawdown no higher than V7 (`6.832349%`);
- Validation median hold no higher than DRA V1 (`182.5h`) and strictly lower
  than V7 (`192h`);
- Validation P90 hold no higher than V7 (`836h`);
- Validation harvest efficiency strictly greater than V7;
- at least three manager-completed exits in both Design and Validation;
- total PnL beats V7 in at least three of five annual folds;
- median hold beats V7 in at least three of five annual folds;
- all invariance, causal, promotion, profit-only, and accounting audits pass.

If exactly one mode passes, freeze it. If multiple pass, rank by highest
Validation total, then highest harvest efficiency, then lowest median hold. If
none passes, return `NO_CANDIDATE` without changing a threshold.

## OOS seal

No 2025+ row may be read during selection. OOS may be opened once only if one
pre-2025 candidate is formally frozen with exact data, specification,
dependency, runner, and freeze hashes. Otherwise OOS and the independent
one-slot overlay remain unopened.

## Outputs

Emit deterministic, overwrite-protected JSON containing hashes, exact parent
checkpoints, complete candidate ledgers, per-lot attribution, audits, gates,
and decision; then produce a reproducible result report.
