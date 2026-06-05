# Trading Split Audit

## Status

- Snapshot date: 2026-06-05
- Repo: `agora-trading-api`
- Goal: standalone trading service repo for trading APIs, trading MCP, strategy, market, and notification workflows.
- Out of scope: marketplace frontend assets, commerce APIs, cart/order delivery flows, marketplace login/account features, wallet commerce flows, and AgoraMarketAPI deployment.

This document is a split-boundary audit, not a production completion claim. Production currentness still requires deployment plus `scripts/verify_server.sh`.

## Intentional Cross-Repo Dependency

The remaining AgoraMarket dependency is intentionally limited to exchange-rate reads through the internal SDK:

- `pom.xml`: `agora-market-internal-client`
- `src/main/java/com/agora/service/ExchangeRateService.java`
- `src/main/java/com/agora/service/impl/AgoraMarketExchangeRateServiceImpl.java`
- `src/main/java/com/agora/config/AgoraMarketExchangeRateProperties.java`
- `src/main/resources/application.yml`: `agora-market.*`
- `scripts/bootstrap_server.sh` and `scripts/verify_server.sh`: AgoraMarket exchange-rate dependency health checks

Expected behavior:

- With `AGORA_MARKET_INTERNAL_API_KEY`, trading calls the AgoraMarket internal exchange-rate API.
- Without the key, or if the internal API times out, returns 401, or is unavailable, trading falls back to static rates.
- Local tests cover the SDK-backed service and fallback behavior.

## Local Gates

Use these checks before committing split-cleanup batches:

```powershell
.\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180
.\scripts\verify_local.ps1
```

For a faster split-boundary-only pass without Maven tests or app startup:

```powershell
.\scripts\verify_split_boundaries.ps1
```

Expected:

- `smoke_local_health.ps1` starts the service with profile `local-smoke`, checks `http://127.0.0.1:18084/api/trading/actuator/health`, then stops the temporary Maven/Java process tree.
- `local-smoke` excludes scheduled task registration, and smoke logs include `Scheduling disabled for local-smoke profile`.
- `verify_local.ps1` runs `mvn test`.
- `verify_local.ps1` scans for forbidden marketplace, frontend, login, auth, commerce, wallet, realtime, stale utility residue, deployment guard regressions, schema-bootstrap drift, and internal API contract drift.
- `verify_split_boundaries.ps1` runs the schema inventory, POM dependency boundary, package boundary, and server env template contract checks.
- Server schema comparison rejects implicit entity table names before comparing database metadata.

## Removed Residue Covered By Verification

The local verification gate currently covers these previously removed or forbidden categories:

- Flutter/AppVersion deployment residue
- Marketplace search logging
- Support ticket, image audit, product classification, product report, product validator, and product routing residue
- Address, postal, delivery, logistics, cart summary, and marketplace order-event residue
- OAuth2, WalletConnect/Web3 login, AuthService, AuthCode, 2FA, JWT login, and account-login DTO residue
- WebPush, notification enum, chat, WebRTC, SSE, and realtime marketplace residue
- Betting, marketplace status/type enum, PWA log, traffic analytics, slot analytics, slot cache, staking, and transaction DTO residue
- Object storage, login/Tron, security audit, group AI, common utility, OCI maintenance, and AI group config residue

## Split Guardrails Covered By Verification

The local verification gate also checks that split/deploy assumptions stay aligned:

- Deploy scripts do not use unsafe broad `sed` rewrites for nginx trading path swaps.
- Deploy refuses to overwrite staged or unstaged server worktree changes before syncing from `origin/main`.
- Failed blue-green deploys clean the new process, new-port pid file, and temporary nginx file.
- Deploy/server verification rejects unknown `app.port` or `TRADING_PORT` state outside the `8084/8085` set.
- Server preflight/verify require non-empty env keys without printing secret values.
- Deploy/preflight fail fast when required server tools for blue-green and nginx swaps are missing.
- The nginx path installer fails fast when its own `awk`, `mktemp`, or `sudo` dependencies are missing.
- The tracked `.env.trading.secrets.example` documents required trading-only server env keys; `scripts/validate_env_template.ps1` checks it against server script `require_env_key` usage, while the real `/home/ubuntu/.env.trading.secrets` remains untracked.
- Deploy fails fast if the AgoraMarket `internal-client` SDK is missing, then installs that SDK before building trading.
- Flyway remains disabled until a trading baseline exists, and `ddl-auto=update` is documented as temporary bootstrap-only schema mode.
- `scripts/verify_local.ps1` runs the read-only schema source inventory and rejects implicit JPA table names before any Flyway baseline is generated.
- `scripts/schema_baseline_compare_server.sh` repeats the implicit JPA table-name check on the server before comparing database metadata.
- Migration drift checks use `flyway_schema_history` and no stale `db_migration_history` or `db/migrations` comments remain.
- Internal API docs use externally callable `/api/internal/...` paths for exchange-rate contracts and do not predefine identity/user contracts.
- `scripts/validate_pom_boundary.ps1` allows only the thin `com.agora:agora-market-internal-client` SDK as an Agora dependency and rejects marketplace application jar/path references.
- `scripts/validate_package_boundary.ps1` keeps top-level and nested `com.agora.*` packages inside the trading-owned allowlist and rejects marketplace-style package segments such as product, order, cart, user, wallet, OAuth, and webpush. The retained `com.agora.mcp.auth` package is service-level MCP API-key auth, not marketplace login.

## Retained Trading Domains

These domains are retained unless a later audit proves they are unused:

- Trading REST APIs and admin APIs
- Trading MCP endpoints and tools
- Backtest, strategy, market-data, signal, and exchange-order workflows
- Telegram trading notifications
- AI strategy integration directly used by trading workflows

`Order`, `orderId`, OCO, and exchange-order search hits are trading exchange-order semantics, not marketplace order-domain residue.

## Remaining Risks

- Production deploy currentness is not proven by this audit.
- Server verification checks that the deployed worktree matches `origin/main` by default; explicit rollback verification must opt out with `VERIFY_GIT_CURRENT=0`.
- Server verification requires nginx `/api/trading/` path presence by default; non-nginx verification must opt out with `REQUIRE_NGINX_TRADING_PATH=0`.
- Server shell script syntax is checked locally when Git Bash or `bash` is available, and on Linux/server with `bash scripts/preflight_server.sh`.
- `scripts/verify_server.sh` still requires server verification for production exchange-rate mode and nginx path split.
- This audit does not prove live OKX/Binance, Telegram, scheduler, or production MCP behavior. It only documents local compile, health-smoke, and split-boundary expectations.
