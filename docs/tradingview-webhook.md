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
  "action": "BUY",
  "symbol": "BINANCE:BTCUSDT",
  "timeframe": "1D",
  "barTime": "2026-07-02T00:00:00Z",
  "price": "60400",
  "notionalUsdt": "10",
  "alertId": "unique-alert-id"
}
```

Current behavior:

- Invalid or missing secret is rejected before audit writes.
- Accepted alerts write `bt_decision_audit` evidence with source
  `TRADINGVIEW`.
- Duplicate alerts are suppressed by idempotency key for the configured TTL.
- K-line close events no longer invoke the legacy `LiveSignalEvaluator` while
  `TRADING_SIGNAL_SOURCE_PRIMARY=TRADINGVIEW`.
- Orders are not sent in this release. If dry-run is turned off, the endpoint
  still blocks live execution until the TradingView path creates tracked
  `bt_live_signal` rows and OCO/exit accounting.

This lets TradingView become the source-of-truth signal stream without creating
untracked exchange positions.

Legacy restore switch:

```bash
TRADING_SIGNAL_SOURCE_PRIMARY=LEGACY
TRADING_LEGACY_LIVE_EVALUATOR_ENABLED=true
```

Use that only for a deliberate rollback or shadow investigation. It restores
K-line close events into `LiveSignalEvaluator`.
