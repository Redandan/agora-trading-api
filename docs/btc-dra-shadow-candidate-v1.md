# BTC DRA SHADOW Candidate V1

## Status

`BTC_DAILY_REVERSAL_ACCUMULATION_V1@v1`, abbreviated `DRA`, means
**Daily Reversal Accumulation**.

Runtime boundary:

- catalog mode: `SHADOW`;
- configuration switch: `TRADING_BTC_DRA_SHADOW_MODE`;
- safe default: `OFF`;
- authorized Production observation value: `SHADOW`;
- no exchange, OCO, Grid, fund, Telegram, or live-signal dependency;
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

## Virtual accounting contract

- one virtual entry lot: `30 USDT`;
- maximum open virtual cost: `250 USDT`;
- buy/sell fee: `0.10%` per side;
- adverse buy/sell slippage: `0.05%` per side;
- independent lots;
- exit queues at `+5%` estimated net liquidation return;
- next-open sell is deferred unless at least `+1%` net profit remains;
- no stop loss, time exit, forced risk exit, or end-of-period liquidation.

The 250 USDT cap is mechanical accounting safety, not an alpha veto.

## Bootstrap and restart contract

The first DRA event warms indicators from exactly 90 days of contiguous OKX
hourly bars. Historical warm-up bars cannot create virtual lots or arms; only
the genuine current closed bar may start DRA state.

Evidence stores the exact canonical state as a JSON string and its SHA-256.
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
- `orderSent=false`;
- `liveImplementationPresent=false`;
- `ocoModified=false`;
- `gridModified=false`.

## Production acceptance

Deployment acceptance requires:

1. owner 509 remains `LIVE` and `executionArmed=true` with `10/80/250 USDT`;
2. positions `260/261/262` remain outside DRA and owner-509 takeover;
3. OKX Native Grid `3767345250394603520` remains provider-managed and running;
4. OCO execution safety has no new issue;
5. catalog shows DRA as `SHADOW` with `exchangeOrderAllowed=false`;
6. `draConfiguredEnabled=true`;
7. the first genuine DRA row has `bootstrap=true`, `catchUp=false`,
   `orderSent=false`, no blocker, and a valid canonical-state hash;
8. the next genuine row restores that state with `bootstrap=false`,
   `catchUp=false`, `invalidStateRowsScanned=0`, and contiguous bar time.

The first row proves deployment and bootstrap only. Forward profitability
requires later completed virtual exits and cannot be inferred from historical
backtest results.
