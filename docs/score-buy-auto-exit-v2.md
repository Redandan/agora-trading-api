# Score Buy Auto Exit V2

## Decision

`TV_BTC_DAILY_SCORE_BUY_AUTO_EXIT_V2` is a PAPER/research candidate that keeps
the frozen TradingView score-buy entry contract and adds automatic, per-lot
profit harvesting.

V2 does not change the frozen entry logic in
`TV_BTC_DAILY_ACCUMULATION_V1`. In the current local source, V1 is retained as
`ARCHIVED` and V2 is registered as `PAPER`. V2 reuses the same daily evaluator
and Binance stream, has no exchange adapter, and cannot place a real order.
Deployment does not activate the evaluator: the deployment default remains
`TRADINGVIEW_LOCAL_ENABLED=false`.

## Strategy contract

- Signal source: Binance `BTCUSDT`, daily UTC, closed bars only.
- Entry rules and intent weights: exactly the frozen V1 score-buy rules.
- Buy timing: next available daily open.
- Position model: every signal bar creates one independently tracked lot;
  same-bar score-buy intents are aggregated into that lot.
- Exit trigger: estimated net liquidation return reaches `+5%`, including
  entry fee, estimated exit fee, and adverse slippage.
- Exit timing: next available daily open.
- Profit floor: defer the queued exit when that open would realize less than
  `+1%` net profit after costs.
- No automatic loss exit, maximum holding-time exit, OCO, trailing stop,
  averaging across lots, AI gate, or platform risk opinion.

The profit floor is mechanical execution correctness for the V2 rule. It
prevents an overnight gap from converting a profit-only exit into an
unintended loss exit.

## Read-only historical research

Research was run on 2026-07-25 with the current Java score-buy implementation
and official Binance daily klines from 2017-08-17 through 2026-07-10.
Signals fill at the next daily open. Normal costs are `0.10%` fee and `0.05%`
adverse slippage per side.

| Window | V1 no-exit return | V2 total return | V2 profitable exits | V2 open lots |
| --- | ---: | ---: | ---: | ---: |
| 365 days | `-20.74%` | `-8.57%` | `16 / 16` | 12 |
| 1,095 days | `-9.90%` | `-2.22%` | `42 / 42` | 12 |
| Full available history | `+382.84%` | `+4.33%` | `139 / 139` | 12 |

The full-history return is not a claim that V2 beats holding BTC. V2 realizes
small profits repeatedly while V1 retains long BTC exposure, so their terminal
inventory profiles are deliberately different. The useful V2 result is the
reduction in unresolved inventory and the number of profitable exits that no
longer need manual action.

Under doubled cost assumptions (`0.20%` fee and `0.10%` adverse slippage per
side), V2 produced:

| Window | V2 total return | Profitable exits | Open lots |
| --- | ---: | ---: | ---: |
| 365 days | `-8.63%` | `16 / 16` | 12 |
| 1,095 days | `-1.95%` | `42 / 42` | 12 |
| Full available history | `+4.37%` | `139 / 139` | 12 |

Both normal and doubled-cost runs produced `3/5` positive isolated temporal
folds. V2 improves the recent terminal result and automates most historical
profit-taking, but it does not yet satisfy a `4/5` positive-fold promotion
gate.

## Evidence limitation

The preserved TradingView report contains 203 full-history intents, while the
current Java replay over current Binance data produces 208. The canonical
365-day fixture remains the exact parity boundary with 42 intents. Therefore
the 365-day comparison is the strongest direct V1/V2 evidence; longer-window
results are sensitivity evidence until the full-history source difference is
resolved.

## Runtime boundary

The local implementation now:

1. uses a V2-specific policy mode and evidence schema, leaving V1 evidence
   untouched;
2. preserves lot IDs, buy fills, sell fills, fees, realized PnL, open
   inventory, and deferred exits in hashed durable PAPER snapshots;
3. rebuilds V2 state from the full causal daily history when no valid V2
   snapshot exists;
4. keeps exactly one Binance daily subscription for owner 508;
5. has no dependency on exchange trading, OCO, Telegram, Grid, or live
   positions.

Every deployment must keep `TRADINGVIEW_LOCAL_ENABLED=false` unless PAPER
activation was separately approved. The next strategy gate is separately
approved PAPER enablement and forward observation. Review whether `3/5`
positive folds is acceptable for the operator's goal of reducing manual exits,
or whether further strategy work must first reach `4/5`.

LIVE requires a separate sell adapter, inventory ownership design, explicit
capital limits, deployment approval, and operator authorization. This document
does not authorize any of them.
