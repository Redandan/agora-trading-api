# Profit Execution Plan

Last updated: 2026-07-14

## Objective

Prove a repeatable net-positive BTCUSDT edge after fees and slippage before
increasing exposure. This plan does not guarantee profit. No historical or
shadow result authorizes a live order.

## Current Candidate

The only candidate with a designed order-execution lane is versioned policy
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
analyzer uses deterministic 1m OHLC execution and exit ordering; it does not
claim exact exchange fills or intraminute ordering. The production 1m
snapshot available during implementation starts on 2026-05-14, so the first
365-day exact run is expected to return `INSUFFICIENT_EXACT_1M_SAMPLE` unless
more authoritative data is available. It must fail closed rather than replace
missing 1m bars with 4h precision.

## Price-Only Research Candidate

`BTC_DONCHIAN_20D_10D_V1` is a separate local SHADOW-review candidate. It buys
after a close above the prior 20-day high, exits after a close below the prior
10-day low, uses a 14-day ATR two-times initial stop, and risks 1% of equity
with no leverage. It is not strategy 508 and cannot prove TradingView/OI/funding
parity.

On the immutable 2019-01-01 through 2026-07-13 OKX 1H dataset it produced
`+171.89%` normal and `+163.25%` stress return, PF `4.443/4.170`, 41 completed
round trips, and about `15.10%` maximum drawdown. Four of five isolated folds
were positive; the latest fold was `-4.45%/-4.83%`. This is enough only for a
separately approved SHADOW experiment. The evidence-only runtime is deployed at
`cb2c31c`, exactly matches the frozen signal/order/trade ledgers, and production
is currently `SHADOW`. The 2026-07-14 read-only snapshot had two runtime rows,
one non-bootstrap forward bar, zero entries, and zero completed trades, so it
does not yet provide return evidence. There is no live implementation, order,
OCO, or Telegram path. This document update does not authorize any environment
or runtime mutation.

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
- one open position maximum, one admitted order per UTC day, and no same-minute
  exit/re-entry;
- at least 99% of mature admitted outcomes finalized, with every finalized
  promotion event having 100% canonical UTC minute-lattice coverage and valid
  OHLC invariants; off-grid or duplicate timestamps cannot replace a missing minute;
- finalized events span at least 270 calendar days;
- 180d and 365d average net return `>0.20%`, median `>0`, PF `>=1.30`;
- at least three of 90/120/180/270/365d windows positive;
- worst fixed window average at least `-0.10%` and PF at least `0.90`;
- all five fixed non-overlapping calendar folds contain observations and at
  least three are positive; these are temporal validation folds, not a
  train/refit walk-forward claim;
- double fees plus 0.05% adverse slippage per side remains non-negative;
- max strategy equity drawdown no greater than 15%;
- improvement over the 72H benchmark at least 0.5 percentage points;
- the 24H and 72H paths build independent admission/holding cohorts, then use
  only event keys finalized in both, with at least 30 pairs;
- same-minute TP/SL rows and rows below 99% 1m coverage excluded from finalized
  evidence.
- every raw outcome reconciles, and unresolved mature outcomes receive a
  conservative worst-case loss bound that must remain non-negative in total.

Failure verdict is fixed:
`REJECTED_NO_LIVE_NO_MORE_PARAMETER_TUNING`.

## Forward Shadow Gate

Production starts with:

```text
TRADING_508_TIME_EXIT_MODE=SHADOW
TRADING_508_TIME_EXIT_LIVE_ORDER_ENABLED=false
```

Shadow must run at least 30 days and finalize at least five independent signals.
The forward cohort uses the same virtual one-position and one-order-per-UTC-day
admission semantics as historical/live policy. Rolling 30-day net PnL must be
positive, with no duplicate event key, malformed context, non-SHADOW row,
canonical entry/exit timing gap, hard-gate event, entry/exit parity gap, OCO
execution gap, or unknown fee claim. EV and TQS are recorded as observer-only
`wouldBlock` values; they never block this lane. Ensemble is explicitly
`NOT_EVALUATED` when the isolated raw-signal context is incomplete.

Every row must carry the same effective hash of the fixed policy plus current
strategy config used by the historical analyzer. Config drift resets the
forward cohort. Timing-gap validation covers the rolling window plus a 24-hour
position-state seed lookback; unrelated older rows do not poison the gate.

Passing shadow proves only the forward sample. The current producer has no
immutable all-fill signed-fee ledger, so readiness reports
`LIVE_EXACT_FILL_PROVENANCE_NOT_IMPLEMENTED` and cannot emit a live-ready
verdict. Implementing that ledger is a separate execution change requiring
explicit authorization; it still would not itself authorize an order.

## Live Promotion

Live promotion requires a new explicit authorization after both historical and
forward gates pass and immutable fill/fee provenance is implemented.

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
first-probe gate fail closed. Fee numbers are not exact evidence unless all
fills are aggregated, fee sign/rebate semantics are preserved, and gross minus
fees matches reported net PnL. The first probe must also have positive exact net
PnL. Missing or stale mark prices keep open PnL unknown rather than converting
the missing mark to zero.

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
