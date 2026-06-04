# Split Progress

## Current Baseline

- `agora-trading-api` is extracted and compiles as a standalone Spring Boot app.
- Current test baseline: `mvn test` should load the full Spring context with `com.agora` component scanning.
- The repo still contains some system/auth/frontend remnants needed for the current context or queued for cleanup.

## Completed

- Trading app entry point uses full `com.agora` component scan.
- JPA repository scan is limited to trading/system repositories.
- Obvious marketplace product/order/cart/delivery/game/webpush/notification code was removed from trading.
- `AgoraMarketExchangeRateServiceImpl` uses the `agora-market-internal-client` SDK when configured.
- `StaticExchangeRateServiceImpl` exists as the local/downstream-failure fallback.
- Flutter/AppVersion deployment leftovers were removed from trading.
- UserSearchLog/SearchLogAspect leftovers were removed from trading.
- CustomerIssue/support-ticket leftovers were removed from trading.
- Product classification and image audit leftovers were removed from trading.
- UserAddress, postal-area, and delivery-country leftovers were removed from trading.
- Unused OAuth2 service interfaces and OAuth2 DTO leftovers were removed from trading.
- WalletConnect/Web3 login leftovers and the unused OAuth2 client dependency were removed from trading.
- Unused AuthService/AuthCode/2FA and marketplace account-login DTO leftovers were removed from trading.
- WebPush, product-report, product-validator, cart-summary, and marketplace order-event leftovers were removed from trading.
- Unused marketplace delivery/order/wallet enums, notification enums, logistics utilities, and delivery/digital-order properties were removed from trading.
- Empty legacy marketplace MCP tool placeholders were removed from trading.
- Unreferenced betting and marketplace status/type enums were removed from trading.
- Unused PWA log, traffic analytics, slot analytics DTOs, slot symbol enum, slot cache, and stale product/PWA/slot security rules were removed from trading.
- Unreferenced chat, staking, transaction DTOs and unused marketplace/betting enums were removed from trading.
- `AgoraMarketAPI` now has the first internal exchange-rate endpoint:
  - `GET /api/internal/exchange-rates/usdt`
  - `GET /api/internal/exchange-rates/usdt/{currency}`
  - Header: `X-Internal-Api-Key`
  - Provider property: `internal.api-key=${INTERNAL_API_KEY:}`
- `AgoraMarketAPI/internal-client` now contains an independently buildable thin SDK:
  - `ExchangeRateInternalClient`
  - `HttpExchangeRateInternalClient`
  - `ExchangeRateInfo`
  - `AgoraMarketInternalClientProperties`

## Exchange Rate Runtime

Keep static fallback behavior for:

- Local dev without `AGORA_MARKET_INTERNAL_API_KEY`.
- AgoraMarketAPI downtime.
- Timeout or `401` during transition.

Fresh-machine build prerequisite:

```powershell
mvn -f C:\Users\Redan\IdeaProjects\AgoraMarketAPI\internal-client\pom.xml install
```

## Acceptance And Deploy

AgoraMarketAPI deployment/acceptance runbook:

- `C:\Users\Redan\IdeaProjects\AgoraMarketAPI\docs\split-service-acceptance-deploy.md`

Trading deployment prep:

- Trading has a deploy skeleton in `deploy.sh`, but it has not been installed on the server yet.
- `scripts/bootstrap_server.sh` can clone/fetch the repo on the server and write a non-secret env template.
- 2026-06-05 server preflight confirmed AgoraMarketAPI is healthy on local port `8082`.
- 2026-06-05 server preflight confirmed `/home/ubuntu/agora-trading-api` and `/home/ubuntu/.env.trading.secrets` are still missing.
- 2026-06-05 server preflight did not find nginx `/api/trading/` routing yet.
- Production must define `AGORA_MARKET_INTERNAL_API_KEY` in `/home/ubuntu/.env.trading.secrets` before trading can use AgoraMarket exchange rates.
- Server-side deploy acceptance can be checked with `scripts/verify_server.sh` after the service is installed.

## Cleanup Priority

1. Decide whether Telegram login should remain in trading or be replaced by MCP/API-key-only access.
2. Review remaining user/OAuth binding code in smaller slices; Telegram trading notifications still need separate treatment from user login.
3. Reduce generic security/account dependencies after Telegram login boundary is settled.

## Do Not Do Yet

- Do not share marketplace JPA entities with trading.
- Do not let trading read the marketplace database directly.
- Do not add identity internal API until shared login is required.
- Do not convert every leftover marketplace service into an internal API.
