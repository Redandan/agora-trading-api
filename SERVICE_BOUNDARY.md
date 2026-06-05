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

- `GET /api/internal/exchange-rates/usdt`
- `GET /api/internal/exchange-rates/usdt/{currency}`

The response DTO should match the current `ExchangeRateInfo` shape:

- `fromCurrency`
- `toCurrency`
- `rate`
- `symbol`
- `currencyName`
- `lastUpdated`

## Identity Boundary

Trading does not share marketplace login, user profiles, or marketplace user tables.

For the current split, do not add identity internal APIs. If a future product
requirement explicitly needs shared identity, treat that as a separate design
change with a dedicated DTO-only SDK. Do not predefine user internal API
contracts in trading.

## Cleanup Regression Guard

Marketplace leftovers should be removed or replaced locally, not turned into internal APIs. `scripts/verify_local.ps1` guards the current forbidden categories, including:

- Flutter/AppVersion deployment, search logging, customer issue, address, image audit, and product-classification residue.
- OAuth2, WalletConnect/Web3 login, AuthService/AuthCode/2FA, JWT/member login, and marketplace account DTO residue.
- WebPush, chat/realtime, commerce order/cart/delivery/logistics, marketplace wallet enum, PWA/slot analytics, staking, and transaction DTO residue.

If a later trading feature really needs one of these capabilities, add a trading-owned model/service or an explicit SDK/HTTP DTO contract. Do not re-import marketplace entities, repositories, controllers, or service implementations.
