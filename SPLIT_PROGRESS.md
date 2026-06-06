# Split Progress

## Current Baseline

- `agora-trading-api` is extracted and compiles as a standalone Spring Boot app.
- Current test baseline: `mvn test` should load the full Spring context with `com.agora` component scanning.
- The repo keeps trading/system runtime code needed for the Spring context. Marketplace auth/frontend remnants are treated as forbidden cleanup regressions by `scripts/verify_local.ps1`.

## Completed

- Trading app entry point uses full `com.agora` component scan.
- JPA repository scan is limited to trading/system repositories.
- Obvious marketplace product/order/cart/delivery/game/webpush/notification code was removed from trading.
- `AgoraMarketExchangeRateServiceImpl` uses the `agora-market-internal-client` SDK when configured.
- `StaticExchangeRateServiceImpl` exists as the local/downstream-failure fallback.
- Unused public exchange-rate provider chain leftovers were removed; exchange rates now use AgoraMarket internal SDK or static fallback only.
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
- Unused referrer DTO and marketplace logistics enum translation leftovers were removed from trading.
- Unused Telegram login/OAuth binding service chain was removed from trading while keeping Telegram notifications and MCP auth intact.
- Unused member CRUD service and member admin DTO leftovers were removed from trading.
- Unused marketplace user fields, user repository member queries, and post service/DTO leftovers were removed from trading.
- Unused web JWT filter, CurrentUser resolver, UserDetailsService, and `/auth/**` route leftover were removed while keeping MCP API-key auth intact.
- Trading withdrawal risk state no longer reads the marketplace `users` table; unused User entity/repository, AutoReply service, and WebRTC signaling service leftovers were removed.
- Trading no longer accepts login/member JWTs; MCP protected tools use service-level API-key authorization only.
- Unused KB daily-export and post-deploy audit config residue was removed, including stale default-on file/git export and listener properties.
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
- Local split verification now guards the remaining handoff assumptions:
  - `scripts/smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180` starts the service under `local-smoke`, proves `/api/trading/actuator/health`, calls `/api/trading/mcp` with `getMcpRegistryVersion`, and checks logs for disabled external side effects.
  - `scripts/verify_local.ps1` runs compile/tests, split boundary scanners, env-template checks, shell syntax checks, schema source inventory, and documentation drift guards.
  - `scripts/schema_baseline_inventory.ps1` writes `target/schema-baseline/entity-tables.txt`, `implicit-entities.txt`, and `forbidden-marketplace-tables.txt`; the latest local guard run found no implicit entity tables and no obvious marketplace-owned table mappings.
  - Runtime side effects that could surprise a split deployment now default off in code and/or the tracked env template, including scheduled market-data writes, startup backfills, attribution startup work, Telegram digests/alerts, market-flip detector/analyzer escalation, Polymarket/WAI, market WebSockets, OCO/grid/Earn/trailing-stop automation, ScoreBuy execution/notification paths, short-squeeze/taker-buy alerting, ML shadow inference logging, ML materialized startup refresh, ML protection/autoretrain/digest automation, Gemini advisor, Tiny Live auto-execution, guardian live actions, runtime evidence, and discovery AI suggestions.
  - `scripts/smoke_local_health.ps1` explicitly clears high-risk host env values and passes matching boot args so local smoke cannot inherit accidental trading/deletion/AI automation from the developer or CI environment.
  - Remaining `enabled:true` fallbacks are enforced by `scripts/verify_local.ps1` as a four-item protective/internal allowlist: MCP master-approval probe wait, Telegram noise reduction, enabled-strategy kline data validation, and deterministic regime filtering.
- Deploy/server scripts now reject stale AgoraMarket dependency routing unless `AGORA_MARKET_BASE_URL` points at `http://127.0.0.1:8082`.
- `deploy.sh` now checks AgoraMarket exchange-rate dependency health before starting the blue-green switch.
- Server preflight now requires AgoraMarket exchange-rate dependency health by default, with `REQUIRE_AGORA_MARKET_HEALTH=0` reserved for diagnostic-only checks.
- Server verification now requires deploy metadata (`app.commit`, `app.pid`, `app.port`) by default, with `REQUIRE_DEPLOY_METADATA=0` reserved for non-deploy diagnostics.
- Server verification now requires nginx service active by default, with `REQUIRE_NGINX_SERVICE=0` reserved for non-nginx diagnostics.
- Deploy keeps the previous blue-green instance and nginx backup when `RUN_POST_DEPLOY_VERIFY=0`, so skipped verification does not drain the last proven instance.
- Short-squeeze alerting and Binance taker-buy collection now default off in code as well as in the tracked env template and local smoke.
- Market-flip detector now defaults off in code and the tracked env template, so flip event writes and related notifications are production opt-in.
- ML shadow inference logging now defaults off in code and the tracked env template, so live-signal HeatWave prediction lookups and `ml_inference_log` writes are production opt-in.
- Deploy/nginx scripts fail fast when `systemctl` is unavailable before attempting nginx reloads.
- Local and server verification now prove the trading MCP context path through `/api/trading/mcp` instead of the pre-split `/api/mcp` path.

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

- Trading has a deploy skeleton in `deploy.sh`.
- `scripts/bootstrap_server.sh` can clone/fetch the repo on the server and write a non-secret env template.
- 2026-06-05 server preflight confirmed AgoraMarketAPI is healthy on local port `8082`.
- 2026-06-05 server bootstrap installed `/home/ubuntu/agora-trading-api` and verified fast-forward from `origin/main`.
- 2026-06-05 server bootstrap created `/home/ubuntu/agora-trading-api/.env.trading.secrets.example`.
- 2026-06-05 server configuration created `/home/ubuntu/.env.trading.secrets` without printing secret values.
- 2026-06-05 server configuration created independent MySQL database `agora_trading` for trading runtime.
- 2026-06-05 server configuration installed nginx `/api/trading/` routing.
- 2026-06-05 observed deployment snapshot used `origin/main` commit `11612b9`; this is historical evidence, not a current-deployment claim.
- 2026-06-05 trading service started on active port `8084`.
- 2026-06-05 `scripts/verify_server.sh` passed with public health check:
  - `https://agoramarketapi.purrtechllc.com/api/trading/actuator/health`
- Production defines `AGORA_MARKET_INTERNAL_API_KEY` in `/home/ubuntu/.env.trading.secrets`, so trading can call AgoraMarket exchange rates and still fall back on timeout or failure.
- Current `origin/main` has advanced beyond the observed deployed commit. Treat production currentness as unproven until `deploy.sh` and `scripts/verify_server.sh` are re-run on the server; `scripts/verify_server.sh` now checks that the server worktree matches `origin/main` by default.
- Local split cleanup has continued past the observed deployment snapshot. Recent pushed batches tightened default-off runtime safety and verifier guardrails, but no production deploy has been run for those commits in this thread.

## Cleanup Priority

1. Compare the local schema inventory with the real `agora_trading` database via `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh` only after server/DB verification is explicitly requested.
2. After the read-only server compare matches, add an explicit Flyway baseline under `src/main/resources/db/migration` and replace temporary `ddl-auto=update` bootstrap mode with schema validation plus Flyway.
3. Re-run server deploy/verify when production deployment is explicitly requested.

## Do Not Do Yet

- Do not share marketplace JPA entities with trading.
- Do not let trading read the marketplace database directly.
- Do not add or predefine identity internal API until shared login is required.
- Do not convert every leftover marketplace service into an internal API.
