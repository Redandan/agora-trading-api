# BTC DRA Runtime V1

## Status

`BTC_DAILY_REVERSAL_ACCUMULATION_V1@v1`, abbreviated `DRA`, means
**Daily Reversal Accumulation**.

Runtime boundary:

- catalog capability: `LIVE`;
- configuration switch: `TRADING_BTC_DRA_MODE`;
- safe default: `OFF`;
- authorized Production value: `LIVE`;
- exact live notional and total exposure: `30 USDT`;
- at most one live lot;
- no OCO, Grid, fund, leverage, or Telegram dependency;
- actual fills use the existing `bt_live_signal` durable ledger under the
  catalog-only strategy id `-10001`;
- the DRA position prefix remains inside the intentional BTC-base namespace so
  OCO reconciliation cannot adopt it;
- no schema migration and no additional MCP tool;
- reuses the catalog-owned OKX `BTCUSDT@1h` stream.

The previous MEI directional runtime is retired. Its evidence rows remain
immutable history and are not valid DRA state.

## Frozen entry contract

Market and timing:

- source: OKX;
- symbol: `BTCUSDT`;
- input interval: `1h`;
- decision interval: UTC daily close, represented by the `23:00` hourly bar;
- closed bars only;
- exact contiguous hourly sequence required;
- virtual fill at the next hourly open.

An entry may be confirmed only while the lane is armed and all three conditions
are true:

```text
daily close > daily EMA20
AND daily EMA20 > daily EMA20 five daily closes earlier
AND 24-hour close momentum > 0
```

The strategy intentionally has:

- no MEI threshold;
- no drawdown threshold;
- one entry per arm;
- seven-day entry cooldown;
- 30-day arm expiry followed by a new arm when cooldown permits.

Because confirmation is evaluated before re-arming on the same daily bar, a
new arm cannot confirm until a later daily close.

## Reference accounting contract

- one virtual entry lot: `30 USDT`;
- maximum open virtual cost: `250 USDT`;
- buy/sell fee: `0.10%` per side;
- adverse buy/sell slippage: `0.05%` per side;
- independent lots;
- exit queues at `+5%` estimated net liquidation return;
- next-open sell is deferred unless at least `+1%` net profit remains;
- no stop loss, time exit, forced risk exit, or end-of-period liquidation.

The 250 USDT cap belongs to the historical/reference ledger. LIVE execution is
independently capped at exactly one 30 USDT lot.

## LIVE execution contract

- only a genuine fresh current bar may reach the adapter;
- bootstrap, catch-up, stale, incomplete, duplicate, or hash-invalid state can
  never place an order;
- a new daily entry signal is durably reserved before submitting one OKX market
  buy with deterministic `clOrdId`;
- an unresolved or ambiguous submission is never retried automatically;
- while any DRA reservation or open lot exists, another DRA buy is blocked;
- the live lot uses actual fill quantity, price, and fee-aware effective cost;
- a sell is considered only when fee- and adverse-slippage-adjusted estimated
  net return reaches `+5%`;
- no stop-loss, time exit, forced loss sale, OCO, or trailing exit exists;
- a sell uses only the quantity recorded in the DRA-owned live lot;
- actual fills, fees, realized PnL, provider order id, and client order id are
  persisted and linked back to the canonical DRA evidence row;
- no deployment or acceptance step sends a test order.

## Bootstrap and restart contract

The first DRA event warms indicators from exactly 90 days of contiguous OKX
hourly bars. Historical warm-up bars cannot create virtual lots or arms; only
the genuine current closed bar may start DRA state.

Evidence schema is `BTC_DRA_RUNTIME_EVIDENCE_V1`; state schema is
`BTC_DRA_RUNTIME_STATE_V1`. Evidence stores the exact canonical state as a JSON
string and its SHA-256.
After any current-schema DRA evidence exists:

- valid state may catch up at most 30 days;
- missing, corrupt, or hash-mismatched state fails closed;
- the runtime must never silently bootstrap a second independent DRA state;
- MEI policy keys, event types, schemas, and evidence are ignored.

Every DRA evidence row records:

- daily signal fields and arm state;
- virtual events and exposure;
- realized and unrealized PnL;
- state canonical JSON and SHA-256;
- actual `orderSent`;
- `liveImplementationPresent=true` while configured LIVE;
- `ocoModified=false`;
- `gridModified=false`.

## Production acceptance

Deployment acceptance requires:

1. owner 509 remains `LIVE` and `executionArmed=true` with `10/80/250 USDT`;
2. positions `260/261/262` remain outside DRA and owner-509 takeover;
3. OKX Native Grid `3767345250394603520` remains provider-managed and running;
4. OCO execution safety has no new issue;
5. catalog shows DRA as `LIVE`, configured mode `LIVE`, and
   `draExecutionArmed=true`;
6. live notional and maximum live exposure both equal exactly `30.00 USDT`;
7. the first genuine DRA row has `bootstrap=true`, `catchUp=false`,
   `orderSent=false`, no blocker, and a valid canonical-state hash;
8. the next genuine row restores that state with `bootstrap=false`,
   `catchUp=false`, `invalidStateRowsScanned=0`, and contiguous bar time.
9. deployment, bootstrap, catch-up, and acceptance create no DRA live-signal
   reservation and send no order;
10. the first later qualifying signal may send exactly one 30 USDT buy; its
    reservation, `clOrdId`, provider order id, fill, quantity, and fees must be
    reconciled before acceptance is called complete.

The first rows prove deployment and restart continuity only. Forward
profitability requires later completed actual exits and cannot be inferred from
historical backtest results.
