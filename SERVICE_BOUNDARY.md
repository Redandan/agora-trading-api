# Service Boundary

This repo is the trading service extracted from `AgoraMarketAPI`.

## Owns

- Versioned strategy catalog, owner 509 LIVE execution, archived owner 508/V1
  evidence, PAPER accounting, archived strategy inventory, Donchian SHADOW
  evidence, the default-OFF DRA 30 USDT LIVE-capable canary, spot OCO
  reconciliation, read-only OKX-native Grid monitoring, and the minimal
  trading MCP surface.
- Market data ingestion used by trading decisions. The versioned runtime
  catalog owns exact provider/symbol/interval subscriptions; database strategy
  `enabled` flags are research inventory and cannot start a stream.
- Trading execution-safety notifications and operator diagnostics.
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
- AgoraMarketAPI may call Trading's read-only internal report endpoints as the Telegram command gateway; it must not import Trading classes.

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
Non-public HTTP routes default to deny-all; trading access is through explicit
public probes/docs and MCP API-key guarded tools, not role-based web login.
Trading MCP does not own Telegram callbacks or cross-service approval state.
Its callable tools require their declared Bearer API-key level; unannotated or
unknown tools fail closed.

## Telegram Report Gateway

AgoraMarketAPI owns the Telegram webhook and command dispatch for `/report`,
`/manager`, `/analysis`, and `/weekly`. This service owns the report content
and exposes only read-only internal endpoints under `/api/trading/internal/reports/**`,
protected by `X-Internal-Api-Key`.

For the current split, do not add identity internal APIs. If a future product
requirement explicitly needs shared identity, treat that as a separate design
change with a dedicated DTO-only SDK. Do not predefine user internal API
contracts in trading.

## Cleanup Boundary

Marketplace leftovers should be removed or replaced locally, not turned into
internal APIs. The automated local regression script was removed during the
strategy-first simplification, so changes touching these areas require direct
source review. Forbidden categories include:

- Flutter/AppVersion deployment, search logging, customer issue, address, image audit, and product-classification residue.
- OAuth2, WalletConnect/Web3 login, AuthService/AuthCode/2FA, JWT/member login, and marketplace account DTO residue.
- WebPush, chat/realtime, commerce order/cart/delivery/logistics, marketplace wallet enum, PWA/slot analytics, staking, and transaction DTO residue.

If a later trading feature really needs one of these capabilities, add a trading-owned model/service or an explicit SDK/HTTP DTO contract. Do not re-import marketplace entities, repositories, controllers, or service implementations.

The protected runtime, staged removal batches, source-only database policy, and
per-batch acceptance rules are defined in
`docs/minimal-runtime-cleanup-roadmap.md`. Cleanup must preserve owner 509 LIVE
semantics, archived owner 508/V1 evidence, Donchian SHADOW evidence, exact
catalog market streams, the isolated default-OFF DRA single-lot canary,
mechanical OCO execution safety, read-only provider Grid monitoring, outbound
critical notifications, and deployment verification.
