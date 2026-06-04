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

Current deploy blocker:

- Trading has a deploy skeleton in `deploy.sh`, but it has not been installed on the server yet.
- Production must define `AGORA_MARKET_INTERNAL_API_KEY` in `/home/ubuntu/.env.trading.secrets` before trading can use AgoraMarket exchange rates.

## Cleanup Priority

1. Decide whether trading keeps independent auth or only MCP/API-key auth.
2. Remove OAuth/passkey/wallet-connect code if trading does not need user-facing login.
3. Evaluate UserAddress/postal-area leftovers separately; do not hard-delete until auth/user boundaries are settled.

## Do Not Do Yet

- Do not share marketplace JPA entities with trading.
- Do not let trading read the marketplace database directly.
- Do not add identity internal API until shared login is required.
- Do not convert every leftover marketplace service into an internal API.
