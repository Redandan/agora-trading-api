# BTC DRA Volatility Exit V2B Research

Status: `RESEARCH_ONLY / OOS_SEALED / NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE`

Research identity: `BTC_DRA_VOLATILITY_PERCENTILE_EXIT_V2B_RESEARCH`

## Objective

Test whether a causal volatility-percentile ATR ratchet can retain the realized
and total-PnL improvement seen in V2A while reducing median and tail holding
time. V2B must not change DRA V1 entry signals, Production position `263`, the
runtime catalog, orders, Grid, OCO, funds, schedules, or database state.

## Frozen common contract

- OKX `BTCUSDT`, contiguous complete `1h` bars.
- Exact DRA V1 entry, arm, expiry, cooldown, next-open fill, and independent
  lot rules.
- `30 USDT` lot, `250 USDT` reference reserve, plus an independent one-slot
  overlay reported only after candidate freeze.
- Fee `0.10%` per side and adverse slippage `0.05%` per side.
- No stop-loss, time exit, forced loss exit, or final liquidation.
- A queued sell executes at the next `1h` open only when estimated proceeds
  remain net positive after fee and adverse slippage.

## Causal volatility state

V2B uses the same complete-UTC-day Wilder ATR14 as V2A. At each complete UTC
day close it keeps at most the latest `252` causal ATR14 values. At least `60`
values are required before a V2B exit may be evaluated.

For the current complete-day ATR:

1. compute its percentile rank inside the trailing window;
2. compute trailing ATR percentiles P25 and P75; and
3. winsorize the distance input:

```text
effective ATR = min(max(current ATR14, trailing P25), trailing P75)
```

This prevents a temporary ATR spike from indefinitely widening the exit and
prevents an unusually compressed ATR from producing a noise-level exit.

## Preregistered ratchet profiles

Each lot tracks its highest closed-hour price. The stop candidate is:

```text
candidate stop = highest close - effective ATR * percentile multiplier
ratchet stop   = max(previous ratchet stop, candidate stop)
```

The ratchet never moves down. Percentile buckets use `LOW <= 25`,
`NORMAL 25-75`, and `HIGH >= 75`.

| Profile | LOW multiplier | NORMAL multiplier | HIGH multiplier | Intent |
| --- | ---: | ---: | ---: | --- |
| `TURNOVER` | `0.75` | `1.25` | `0.50` | Favor earlier recycling |
| `BALANCED` | `1.00` | `1.50` | `0.75` | Preserve trend while tightening extremes |
| `TREND` | `1.25` | `1.75` | `1.00` | Give normal trends more room |

No profile contains a percentage profit target. A sell may be queued only
when the closed-bar estimated liquidation is net positive and price closes at
or below the ratcheted stop.

## Frozen selection protocol

1. Revalidate the canonical input and reproduce DRA V1 plus V2A checkpoints.
2. Candidate selection may use only 2019-2024 data. The period from
   `2025-01-01` onward remains sealed.
3. Report Design `2019-2022`, Validation `2023-2024`, and fair-reset calendar
   folds `2020`, `2021`, `2022`, `2023`, and `2024` with causal warm-up only.
4. A candidate passes only when all conditions hold:
   - Validation realized and total PnL are not below V1;
   - Validation ending unrealized PnL is not worse than V1;
   - Validation maximum drawdown is no more than two percentage points worse;
   - Validation median and P90 holding time are both no worse than V1;
   - it beats V1 total PnL in at least three of the five calendar folds; and
   - it improves median holding time in at least three folds.
5. Rank passing candidates by Validation total PnL, then lower drawdown,
   shorter P90 hold, and shorter median hold.
6. Freeze exactly one candidate or `NO_CANDIDATE` before opening OOS.
7. Only a frozen candidate may be evaluated once on 2025 through the latest
   complete frozen boundary. An OOS failure cannot trigger reselection.
8. Historical success is not SHADOW/LIVE authorization and does not replace
   the current DRA V1 exit.
