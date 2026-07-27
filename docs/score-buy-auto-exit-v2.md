# Score Buy Auto Exit V2

## Decision

`TV_BTC_DAILY_SCORE_BUY_AUTO_EXIT_V2`, owner alias `509`, keeps
the frozen TradingView score-buy entry contract and adds automatic, per-lot
profit harvesting.

V2 does not change the frozen entry logic in
`TV_BTC_DAILY_ACCUMULATION_V1`. V1 remains `ARCHIVED` under its historical
owner alias `508`; V2 is registered as `LIVE` under owner alias `509`. V2 uses
the same Binance daily evaluator and sends only its current complete bar to a
minimal OKX spot adapter.

## Strategy contract

- Signal source: Binance `BTCUSDT`, daily UTC, closed bars only.
- Entry rules and intent weights: exactly the frozen V1 score-buy rules.
- Buy timing: first available OKX market execution after the Binance daily bar
  closes; historical/catch-up bars are audit-only.
- Position model: every signal bar creates one independently tracked lot;
  same-bar score-buy intents are aggregated into that lot.
- Exit trigger: estimated net liquidation return reaches `+5%`, including
  entry fee, estimated exit fee, and adverse slippage.
- Exit timing: first available OKX market execution after a daily close confirms
  the provider-price trigger.
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

These results make owner 509 a parity and execution baseline, not a
proven-profitable benchmark. Current comparisons must use equal capital and
report realized, unrealized, total PnL, drawdown, utilization, blocked entries,
and holding age. Realized-only ranking is invalid because V2 has no forced loss
exit.

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
2. preserves provider order IDs, client order IDs, observed fill prices,
   quantities, fees, realized PnL, and independently owned 509 lots; delayed
   provider fees remain provisional until reconciled from the provider receipt;
3. commits a durable reservation before provider submission; ambiguous results
   are alerted and never blindly retried;
4. keeps exactly one Binance daily subscription for owner 509;
5. never attaches OCO and never sells Grid, manual, archived 508, or other
   strategy BTC;
6. applies only mechanical limits: `10 USDT` per weight unit, `80 USDT`
   maximum same-bar aggregate, `250 USDT` total open cost, exact market scope,
   current-bar freshness, provider credentials/balance, and exchange minimums.

The owner explicitly authorized LIVE promotion with weights `1/2/5` mapped to
`10/20/50 USDT`. Historical simulated performance is retained as context but
is not an execution gate. A real provider order occurs only when the next
genuine current-bar 509 buy or profit-exit condition appears; acceptance must
not manufacture a trade.
