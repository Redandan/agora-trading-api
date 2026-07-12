# Profit Execution Plan

Last updated: 2026-07-12

## Objective

Prove a repeatable net-positive BTCUSDT edge after fees and slippage before
increasing exposure. This plan does not guarantee profit. No historical or
shadow result authorizes a live order.

## Current Candidate

The only active profit candidate is versioned policy
`STRATEGY_508_4H_24H_V1`:

- source signal: strategy 508 raw BUY on closed `BTCUSDT/4h/okx` bars;
- entry: first following 1m open with 0.05% adverse slippage;
- size: one 10 USDT position;
- exits: `+6%` TP, `-12%` disaster SL, otherwise market exit at 24 hours;
- fees: actual live fee evidence; historical baseline uses 0.10% each side;
- concurrency: one experiment position and one live order per UTC day;
- fuse: stop new experiment entries at cumulative net loss `-3 USDT`.

The earlier coarse hold counterfactual reported:

| Window | Trades | Average | Profit factor |
| --- | ---: | ---: | ---: |
| 90d | 21 | +0.38% | 1.75 |
| 120d | 30 | -0.04% | 0.95 |
| 180/365d | 35 | +0.60% | 1.84 |

The same 4H entries held for 72 hours averaged `-0.52%`. These figures identify
holding time as the hypothesis; they are not final promotion evidence. The new
analyzer requires exact 1m execution and exit ordering. The production 1m
snapshot available during implementation starts on 2026-05-14, so the first
365-day exact run is expected to return `INSUFFICIENT_EXACT_1M_SAMPLE` unless
more authoritative data is available. It must fail closed rather than replace
missing 1m bars with 4h precision.

## Rejected Directions

- Trailing replay is rejected. The latest production comparison worsened all
  `19/19` replayable positions, with aggregate delta approximately
  `-1180.39 USDT`. It must not be presented as a profitable lane or promoted to
  live OCO mutation. The prior dry-run observation evidence workflow remains a
  historical read-only audit path, not an active profit candidate.
- Strategy 485 remains `BTC_BASE_DRY_RUN`; recent and long-window results do not
  prove positive expectancy.
- Grid, additional bottom buys, staged adds, and general strategy 508 evaluator
  activation are outside this experiment.
- Existing strategy 508 positions `#260/#261/#262` and their OCO orders are not
  modified or included in the time-exit lane.

## Historical Gate

All conditions must pass in one fixed run:

- at least 30 unique finalized 4H entries;
- 180d and 365d average net return `>0.20%`, median `>0`, PF `>=1.30`;
- at least three of 90/120/180/270/365d windows positive;
- worst fixed window average at least `-0.10%` and PF at least `0.90`;
- at least three of five chronological folds positive;
- double fees plus 0.05% adverse slippage per side remains non-negative;
- max strategy equity drawdown no greater than 15%;
- improvement over the 72H benchmark at least 0.5 percentage points;
- the 24H and 72H comparison uses the same paired finalized entries, with at
  least 30 pairs; unmatched recent rows cannot improve the comparison;
- same-minute TP/SL rows and rows below 99% 1m coverage excluded from finalized
  evidence.

Failure verdict is fixed:
`REJECTED_NO_LIVE_NO_MORE_PARAMETER_TUNING`.

## Forward Shadow Gate

Production starts with:

```text
TRADING_508_TIME_EXIT_MODE=SHADOW
TRADING_508_TIME_EXIT_LIVE_ORDER_ENABLED=false
```

Shadow must run at least 30 days and finalize at least five independent signals.
Rolling 30-day net PnL must be positive, with no hard-gate event, entry/exit
parity gap, OCO execution gap, or unknown fee claim. EV and TQS are recorded as
observer-only `wouldBlock` values; they never block this lane. Ensemble is
explicitly `NOT_EVALUATED` when the isolated raw-signal context is incomplete.

Passing shadow may emit
`READY_FOR_SINGLE_10_USDT_PROBE_NOT_AUTHORIZED`. It still cannot send an order.

## Live Promotion

Live promotion requires a new explicit authorization after both historical and
forward gates pass.

1. One 10 USDT probe only.
2. Verify buy fill, fee attribution, OCO attachment, 24H idempotent close, and
   no oversell or partial-fill gap.
3. Until that first probe is closed with complete fee evidence and no entry or
   exit parity gap, every later entry is hard-blocked. If correct, allow at most
   five 10 USDT order attempts over at most 60 days.
4. Return to SHADOW for net pilot PnL `<=0`, any OCO/partial-fill incident, or
   cumulative net loss `<=-3 USDT`.
5. Consider 20 USDT only after at least 20 live trades, positive 90-day net PnL,
   PF `>=1.30`, and four of five walk-forward folds positive.

Existing OCO polling and 24H exits continue for an open experiment position
after the entry fuse trips.

The close path bypasses cached balances after OCO cancellation. If released
quantity cannot be confirmed, or a market sell fails, it attempts to reattach
the fixed OCO and reports whether the position is reprotected or unprotected.
An accepted order whose fill cannot be confirmed blocks every later probe and
emits a critical alert. Unknown entry or partial-exit fees keep net PnL and the
first-probe gate fail closed.

## Verification

Local:

```powershell
.\scripts\verify_local.ps1
.\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180
```

Production read-only acceptance:

```powershell
.\scripts\verify_server_ssh.ps1
.\scripts\smoke_mcp_parity_ssh.ps1
.\scripts\smoke_signal_correctness_ssh.ps1
.\scripts\smoke_strategy508_hold_counterfactual_ssh.ps1
.\scripts\smoke_strategy508_time_exit_ssh.ps1
```

The rollout is successful when SHADOW is active, live-order is false, existing
OCO health is unchanged, the three new MCP reports are available, and the
historical result honestly reports either a gate pass or insufficient exact 1m
data. It is not successful merely because a recent coarse window is positive.
