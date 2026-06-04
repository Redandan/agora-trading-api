# Split Progress

## Current Baseline

- `agora-trading-api` is extracted and compiles as a standalone Spring Boot app.
- Current test baseline: `mvn test` should load the full Spring context with `com.agora` component scanning.
- The repo still contains some system/auth/frontend remnants needed for the current context or queued for cleanup.

## Completed

- Trading app entry point uses full `com.agora` component scan.
- JPA repository scan is limited to trading/system repositories.
- Obvious marketplace product/order/cart/delivery/game/webpush/notification code was removed from trading.
- `StaticExchangeRateServiceImpl` exists as a local fallback.
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

## Next Implementation Step

Replace `StaticExchangeRateServiceImpl` with an SDK-backed implementation after publishing or locally installing `agora-market-internal-client`.

Suggested class shape in trading:

- `AgoraMarketExchangeRateClient`
- `AgoraMarketExchangeRateProperties`
- `AgoraMarketExchangeRateServiceImpl implements ExchangeRateService`

Keep static fallback behavior for:

- Local dev without `AGORA_MARKET_INTERNAL_API_KEY`.
- AgoraMarketAPI downtime.
- Timeout or `401` during transition.

## Acceptance And Deploy

AgoraMarketAPI deployment/acceptance runbook:

- `C:\Users\Redan\IdeaProjects\AgoraMarketAPI\docs\split-service-acceptance-deploy.md`

Current deploy blocker:

- AgoraMarketAPI production `deploy.sh` fetches/resets from git, so local uncommitted changes must be committed and pushed before deployment.
- Production must define `INTERNAL_API_KEY` in `/home/ubuntu/.env.secrets` before the internal endpoint can be used by trading.

## Cleanup Priority

1. Remove or isolate Flutter/AppVersion deployment leftovers.
2. Remove UserSearchLog and search logging aspect.
3. Remove CustomerIssue, UserAddress, product classification suggestions, and image audit leftovers.
4. Decide whether trading keeps independent auth or only MCP/API-key auth.
5. Remove OAuth/passkey/wallet-connect code if trading does not need user-facing login.

## Do Not Do Yet

- Do not share marketplace JPA entities with trading.
- Do not let trading read the marketplace database directly.
- Do not add identity internal API until shared login is required.
- Do not convert every leftover marketplace service into an internal API.
