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
- provider submissions and cumulative reconciliation use the dedicated
  `bt_spot_execution_attempt` mechanical ledger;
- the DRA position prefix remains inside the intentional BTC-base namespace so
  OCO reconciliation cannot adopt it;
- one forward-only execution-attempt schema migration and no additional MCP
  tool;
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

Entry confirmation therefore occurs once per UTC day. After a live lot exists,
exit eligibility is evaluated on each fresh closed OKX `1h` bar. V1 does not
continuously watch intrabar prices; adding an intrabar exit would require a new
strategy version.

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
- the runtime queries OKX by deterministic `clOrdId` before submission and
  requires one atomic database submitter claim;
- an unresolved or ambiguous submission is never retried automatically;
- while any DRA reservation or open lot exists, another DRA buy is blocked;
- the live lot uses actual fill quantity, price, and fee-aware effective cost;
- a sell is considered only when fee- and adverse-slippage-adjusted estimated
  net return reaches `+5%`;
- no stop-loss, time exit, forced loss sale, OCO, or trailing exit exists;
- a sell uses only the quantity recorded in the DRA-owned live lot;
- cumulative fills and fees are applied as monotonic deltas; overfill and
  backwards provider receipts fail closed;
- only a reconciled partial sell may allocate the next durable sell sequence;
- provider order id, client order id, observed fill price, gross quantity,
  observed fee, sellable strategy quantity, and realized PnL are linked to the
  canonical DRA evidence row;
- when the provider buy fee is not yet available, the current adapter records a
  conservative sellable quantity using a `0.2%` buffer. The provider receipt,
  not that provisional quantity, remains the exact source of truth;
- no deployment or acceptance step sends a test order.

## Performance reporting contract

DRA must be compared with owner 509 under equal starting capital, market
window, fees, adverse slippage, and final valuation time. Report realized PnL,
unrealized PnL, total PnL, maximum drawdown, capital utilization, blocked
entries, and holding age separately.

The 250 USDT reference ledger permits multiple lots. The Production canary
permits one 30 USDT lot. The reference result cannot be used as the expected
return of the one-lot LIVE deployment. Realized PnL alone is not sufficient
because V1 intentionally leaves losing inventory open.

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

## Known V1 limitations

These limitations do not authorize changing the frozen V1 strategy:

1. Position `263` predates the execution-attempt ledger. It remains
   intentionally unbackfilled, so its provider/DB quantity difference must be
   reported as a separate BTC safety remainder during the first sell
   reconciliation.
2. State restore scans recent append-only evidence rows. This fails closed for
   the current canary, but current state should be separated from evidence
   history before more LIVE strategies are added.
3. Duplicate bar evaluation is guarded in process; strategy-plus-bar
   uniqueness is not yet a database contract for multi-instance evaluation.
4. The broad historical test tree remains removed. The retained 14-test
   bootstrap/execution-attempt contract suite must be extended whenever DRA
   order, fill, ownership, or state behavior changes.

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
    reconciled before economic acceptance is called complete.

The first rows prove deployment and restart continuity only. Forward
profitability requires later completed actual exits and cannot be inferred from
historical backtest results.

Current Production checkpoint: commit
`ae47ef0609b6f86c7cfe2338c6d80a3135dc7e25`, active port `8084`, V4
execution-attempt table present with zero initial rows, and first new-JVM
natural bar accepted at evidence `28830` for
`2026-07-30T05:00:00Z`. Position `263` remains the only DRA lot and has not
sold.
