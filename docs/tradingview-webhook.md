# TradingView Webhook Ingress

The TradingView webhook is the primary live signal ingress. Legacy K-line close
strategy evaluation is disabled by default and only runs when explicitly
restored with the legacy signal-source flags. The webhook is still disabled and
dry-run by default until a secret is configured.

Endpoint:

```text
POST /api/tradingview/webhook
```

Required production env:

```bash
TRADINGVIEW_WEBHOOK_ENABLED=true
TRADINGVIEW_WEBHOOK_DRY_RUN=true
TRADINGVIEW_WEBHOOK_SECRET=<random-secret-in-alert-body>
TRADINGVIEW_WEBHOOK_ALLOWED_SYMBOLS=BTCUSDT
TRADINGVIEW_WEBHOOK_DEFAULT_NOTIONAL_USDT=10.0
TRADINGVIEW_WEBHOOK_MAX_NOTIONAL_USDT=10.0
TRADINGVIEW_WEBHOOK_IDEMPOTENCY_TTL_HOURS=24
TRADING_SIGNAL_SOURCE_PRIMARY=TRADINGVIEW
TRADING_LEGACY_LIVE_EVALUATOR_ENABLED=false
```

Expected alert JSON:

```json
{
  "secret": "<same value as TRADINGVIEW_WEBHOOK_SECRET>",
  "strategy": "AI",
  "strategyId": "485",
  "action": "BUY",
  "symbol": "BINANCE:BTCUSDT",
  "timeframe": "1D",
  "orderReason": "TRADINGVIEW_RELATIVE_LOW",
  "orderLabel": "相对低点买入",
  "qty": "1000",
  "barTime": "2026-07-02T00:00:00Z",
  "price": "60400",
  "notionalUsdt": "10",
  "alertId": "unique-alert-id"
}
```

TradingView parity fields:

- `strategyId`: optional but recommended. When present, audit rows are attached
  to the matching `bt_strategy.id`.
- `orderReason`: one of `TRADINGVIEW_RELATIVE_LOW`,
  `TRADINGVIEW_POTENTIAL_LOW`, or `TRADINGVIEW_AI_BUY_SIGNAL`.
- `orderLabel`: the Pine order label, for example `相对低点买入`,
  `潜在低点买入`, or `AI买点买入`.
- `qty`: the Pine `strategy.order(..., qty=...)` quantity. This is captured for
  parity audit only; live notional is still capped by the webhook notional
  settings.
- `timeframe`: TradingView values such as `1D` are normalized to service
  interval codes such as `1d`; the raw chart symbol is retained in audit context.

If the Pine script does not provide `orderReason`, the webhook derives it from
`orderLabel` where possible. Do not reuse the same explicit `alertId` for
different order reasons on the same bar; an explicit alert id is treated as the
idempotency key.

Pine alert payloads should be emitted for all three TradingView buy branches, not
only the final AI `buySignal` branch. The webhook distinguishes same-bar
relative-low and potential-low alerts by including the order reason and label in
the generated idempotency key.

Current behavior:

- Invalid or missing secret is rejected before audit writes.
- Accepted alerts write `bt_decision_audit` evidence with source
  `TRADINGVIEW`.
- Duplicate alerts are suppressed by idempotency key for the configured TTL.
- Same-bar TradingView buy alerts with different order reasons are not
  de-duplicated against each other unless TradingView sends the same explicit
  `alertId`.
- K-line close events no longer invoke the legacy `LiveSignalEvaluator` while
  `TRADING_SIGNAL_SOURCE_PRIMARY=TRADINGVIEW`.
- Orders are not sent in this release. If dry-run is turned off, the endpoint
  still blocks live execution until the TradingView path creates tracked
  `bt_live_signal` rows and OCO/exit accounting.
- Dry-run alerts intentionally do not create `bt_live_signal` rows yet. The
  current `bt_live_signal` uniqueness contract is one row per
  `(strategy_id, symbol, interval_code, bar_open_time)`, while the TradingView
  Pine script can emit multiple order intents on the same bar.

This lets TradingView become the source-of-truth signal stream without creating
untracked exchange positions.

Legacy restore switch:

```bash
TRADING_SIGNAL_SOURCE_PRIMARY=LEGACY
TRADING_LEGACY_LIVE_EVALUATOR_ENABLED=true
```

Use that only for a deliberate rollback or shadow investigation. It restores
K-line close events into `LiveSignalEvaluator`.
