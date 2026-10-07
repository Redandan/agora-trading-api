# BTC order advice V1

## Product contract

Provide a current BTC-USDT spot limit-order plan and replace it when market,
inventory, pending orders, fee/rule inputs or validity materially change. The
user decides whether to place, amend or cancel an order. This is a new advisory
product, not a promoted research candidate or an extension of DRA/509/Grid.

The plan serves both objectives: keep BTC exposure and retain some USDT from a
completed sell/rebuy cycle. Show net BTC change, retained USDT and the conditions
needed for both. A sale is not a completed cycle; sale proceeds are not profit.

## Initial policy and evidence

- Venue: OKX BTC-USDT spot. Use fresh bid/ask and 168 consecutive closed hourly
  candles. No future candles, mixed venues, historical imports or stale fallback.
- Explain price levels from the preceding 24-hour high/low, hourly ATR14 and
  EMA24/EMA72. These are transparent V1 heuristics, not proven profitable alpha.
- Default illustration uses at most 25% of eligible legacy BTC, retaining 75%.
  Split the estimated fee-adjusted sell/rebuy surplus equally between cash and
  additional BTC. Show these assumptions in every plan; they authorize no trade.
- Exclude DRA, owner 509, Grid and unattributed balances from the allocation.
  Bound quantity by provider availability after protecting other recorded lots.
- Account fees and instrument tick/lot/minimum rules are mandatory. Round sells
  upward in price, buys downward, quantities downward. Use the current taker fee
  conservatively, including fees charged in BTC on the buy.
- Existing ordinary or conditional BTC orders require review before adding any
  new order. Display revised reference levels, but do not duplicate those orders
  or infer their ownership from price/quantity matching.
- The buy leg is conditional on actual sale proceeds and a new market/account
  check after the sale. It is not a simultaneously funded order. A partial sale
  requires recalculation. Never reserve anticipated proceeds as current cash.
- Data gaps, stale quotes, unknown inventory, adverse trend, excessive spread or
  an uneconomic round trip produce an explicit wait/review result.

## Updates and delivery

Authenticated internal reports provide text and structured output. A separate,
default-off advisory monitor refreshes every five minutes; it has no order or
strategy authority. It publishes only material revisions/invalidation, not every
tick. New advice explicitly supersedes the earlier revision but does not cancel
exchange orders. GET requests never send Telegram messages.

Monitor state and acknowledged delivery are stored atomically in an isolated
local file, not Trading tables or research canonical state. Restarts recheck
inputs before publishing. Failed delivery must remain pending; acknowledgement
means Telegram accepted the message, not that the user acted on it.

Small changes are compared both to the latest calculated plan and to the last
Telegram-acknowledged plan, so quiet expiry renewals cannot erase accumulated
market drift. The delivered baseline is durable across restart. A material
notification compares against the plan the recipient actually received.
Revise on status/policy/account/order changes, price reaching a proposed order,
25% ATR changes, or order-level movement exceeding max(0.25 ATR, 0.25% price).
Five-percent quantity changes also trigger revision. Expiry revalidates the plan
for on-demand use but does not send an unchanged hourly push. An undelivered
material change remains pending across that renewal.

A cross-process file lock covers refresh and delivery to prevent blue/green
instances from concurrently publishing. A crash after Telegram accepts a message
but before the acknowledgement is saved can still cause a duplicate: delivery
is at-least-once, not exactly-once. GET requests can update only this advisory
file; they never modify exchange or database state.

## Configuration and use

- `GET /api/trading/internal/reports/analysis` returns the Chinese advice text
  through the existing internal-report/Telegram gateway contract.
- `GET /api/trading/internal/reports/btc-order-advice` returns structured advice,
  the current quote metrics, revision/change reason, previous plan and delivery
  status. Both require the existing `X-Internal-Api-Key`; neither sends a push.
- `TRADING_BTC_ORDER_ADVICE_MONITOR_ENABLED=false` by default. Once explicitly
  enabled, the independent advice monitor starts after 60 seconds and refreshes
  with a five-minute fixed delay. It does not add a strategy market subscription.
- `TRADING_BTC_ORDER_ADVICE_NOTIFICATIONS_ENABLED=false` by default. Enabling
  it uses the existing configured Telegram channel, with acknowledgement and
  revision deduplication. Vacation mute is respected. No destination is invented.
- Optional settings: `TRADING_BTC_ORDER_ADVICE_TRADE_FRACTION=0.25`,
  `TRADING_BTC_ORDER_ADVICE_CASH_SHARE=0.5`,
  `TRADING_BTC_ORDER_ADVICE_VALID_MINUTES=60`,
  `TRADING_BTC_ORDER_ADVICE_STATE_FILE=<absolute local writable path>`.

V1 is scoped to the configured exchange account and recorded legacy BTC lots,
not a multi-user portfolio product. It excludes arbitrary manual/Earn balances.
Manual fills/transfers that make provider BTC smaller than recorded inventory
invalidate further actionable advice. V1 does not silently rewrite ownership or
claim that a matching exchange fill came from its suggestion. The conditional
buy price remains a scenario until the actual sale and ownership are reconciled;
automatic manual-order attribution/rebuy execution is outside this release.

Production deployment, monitor enablement and its Telegram destination remain
release decisions. No DB migration, historical repair, extra MCP tool, live
strategy configuration, automatic order or fund movement is part of V1.

## Acceptance

Verify fee/rounding arithmetic, position isolation, complete data, pending-order
review, no double use of sale proceeds, material revision/invalidation, restart
and delivery failure behavior, authenticated endpoints and no write-capable
exchange calls. Package Java with `mvn -DskipTests package` and run the narrow
new contract tests. Example outputs must identify synthetic versus live inputs.

Performance is not accepted by a passing software test. Future assessment must
compare total BTC/USDT value against the same starting holdings kept unchanged,
including open inventory, fees and missed upside; filled-cycle income alone is
insufficient.
