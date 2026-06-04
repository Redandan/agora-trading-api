# Service Boundary

This repo is the trading service extracted from `AgoraMarketAPI`.

## Owns

- Trading strategy configuration, backtests, signals, OCO/grid state, execution audits, and trading MCP tools.
- Market data ingestion used by trading decisions.
- Trading-specific Telegram notifications and operator diagnostics.
- Trading database schema. Marketplace entities must not be read through shared JPA repositories.

## Does Not Own

- Commerce products, stores, carts, orders, delivery, disputes, reviews, promo codes, recharge, withdraw, and marketplace wallet tables.
- Marketplace frontend deployment/version management.
- Marketplace user profile screens, search logging, customer issues, product classification, and image audit workflows.

## Cross-Service Rule

`agora-trading-api` must not depend on `AgoraMarketAPI` entities, repositories, controllers, or service implementations.

Allowed dependency shapes:

- A small internal-client SDK jar published by `AgoraMarketAPI`.
- HTTP calls through SDK DTOs.
- Local fallback implementation for development or degraded mode.

Direct database reads against the marketplace database are not allowed after the split.

## First Internal API

The first required internal API is exchange rates:

- `GET /internal/exchange-rates/usdt`
- `GET /internal/exchange-rates/usdt/{currency}`

The response DTO should match the current `ExchangeRateInfo` shape:

- `fromCurrency`
- `toCurrency`
- `rate`
- `symbol`
- `currencyName`
- `lastUpdated`

## Deferred Internal API

Identity should remain local to trading for the first split unless there is a clear product requirement to share marketplace login.

If shared identity is later required, add a separate `IdentityInternalClient` instead of importing marketplace user tables.

Candidate endpoints:

- `GET /internal/users/{id}`
- `GET /internal/users/by-telegram/{telegramId}`
- `POST /internal/users/resolve-or-create-telegram`

## Cleanup Queue

These are marketplace leftovers and should be removed or replaced locally, not turned into internal APIs:

- AppVersion and Flutter deployment services.
- UserSearchLog and search logging aspect.
- CustomerIssue and user address models.
- Product classification suggestions and image audit DTOs/services.
- PWA/client-log workflows unless trading explicitly needs its own client log.
