# BTC DRA Volatility Exit V2 Research

Status: `RESEARCH_ONLY / NOT_IN_RUNTIME_CATALOG / NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE`

Research identity: `BTC_DRA_VOLATILITY_EXIT_V2_RESEARCH`

## Objective

Replace DRA V1's fixed `+5%` estimated-net exit with a causal volatility-based
exit, while preserving the frozen DRA entry signal and the profit-only spot
inventory boundary. The research must determine whether a volatility exit can
improve fee-adjusted total PnL, ending unrealized PnL, holding time, and capital
reuse without increasing drawdown materially.

This document freezes the research rules before candidate performance is
inspected. It does not change DRA V1, position `263`, runtime configuration,
the strategy catalog, Production, orders, Grid, OCO, funds, or database state.

## Invariant entry and accounting contract

- Source: OKX `BTCUSDT`, contiguous closed `1h` bars.
- Entry rule: exactly DRA V1 daily close above EMA20, rising EMA20 over five
  daily closes, positive 24-hour momentum, seven-day cooldown, and 30-day arm.
- Fill timing: signal on a closed bar, fill at the next `1h` open.
- Lot size: `30 USDT`.
- Reference capacity: `250 USDT`; a separate one-live-slot overlay must also be
  reported.
- Fee: `0.10%` per side.
- Adverse slippage: `0.05%` per side.
- Independent lots; no averaging of exit cost.
- No stop-loss, time exit, forced loss exit, or end-of-window liquidation.
- A queued sell executes only when the next-open estimate remains net positive
  after fee and adverse slippage. This is a zero-profit safety boundary, not a
  fixed percentage profit target.

## Causal volatility input

Volatility is Wilder daily ATR14 built only from complete UTC days aggregated
from the source-pinned hourly bars. True range is the maximum of:

1. daily high minus daily low;
2. absolute daily high minus the previous complete daily close;
3. absolute daily low minus the previous complete daily close.

The first ATR14 is the mean of the first 14 complete true ranges. Later values
use Wilder smoothing. A `23:00` hourly close completes that UTC day and may
update ATR for decisions made at that close. Other hourly closes use the most
recent complete daily ATR. Missing or non-causal ATR produces no exit signal.

## Preregistered exit families

No candidate uses a fixed return percentage.

### A. Entry-frozen ATR target

Each lot stores the causal daily ATR14 available at its signal close. A sell is
queued when estimated net liquidation profit is at least:

```text
lot quantity * entry ATR14 * ATR multiplier
```

Preregistered multipliers: `0.50`, `0.75`, `1.00`, `1.25`, `1.50`, `2.00`.

### B. Ratcheting ATR trailing exit

Each lot tracks its highest closed-hour price after entry. On every closed bar:

```text
candidate stop = highest close - current complete-day ATR14 * ATR multiplier
ratchet stop   = max(previous ratchet stop, candidate stop)
```

The stop never moves down. It becomes executable only after the stop itself is
above fee/slippage-adjusted break-even. A sell is queued when a later closed
hour is at or below the ratchet stop, and the next-open net-positive safety
condition still applies.

Preregistered multipliers: `0.50`, `0.75`, `1.00`, `1.25`, `1.50`, `2.00`.

## Frozen evaluation protocol

1. Validate duplicates, hourly gaps, time grid, one-hour duration, OHLC, price,
   volume, bounds, and a deterministic input hash before performance analysis.
2. Reproduce the DRA V1 reference ledger before accepting the V2 evaluator.
3. Windows are fixed as Design `2019-2022`, Validation `2023-2024`, and OOS
   `2025` through the latest complete frozen data boundary.
4. Candidate selection may inspect Design and Validation only. OOS remains
   sealed until one candidate or `NO_CANDIDATE` is frozen.
5. A candidate passes only when Validation total PnL is not below V1, ending
   unrealized PnL is not worse than V1, maximum drawdown is no more than two
   percentage points worse, and median plus 90th-percentile holding time both
   improve. The result must also be stable at an adjacent multiplier.
6. Among passing candidates, select the highest Validation total PnL; break
   ties by lower drawdown, lower ending unrealized loss, then shorter median
   holding time.
7. Report both the `250 USDT` reference ledger and independent one-slot
   `30 USDT` capacity overlay.
8. Report buys, sells, open lots, blocked entries, realized, unrealized, total,
   maximum drawdown, average utilization, median/P90/max holding hours, and
   turnover. Realized-only ranking is invalid.
9. OOS may be opened once after candidate freeze. Failure returns
   `OUT_OF_SAMPLE_FAIL`; no second parameter selection is allowed from OOS.

## Promotion boundary

Passing historical research does not authorize a runtime implementation,
SHADOW, LIVE, capital increase, deployment, or modification of the existing
DRA V1 position. Any later runtime proposal requires a new versioned strategy
contract and separate authorization.
