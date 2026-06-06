# Trading Deploy Runbook

## Scope

This repo deploys the standalone trading service. It should not deploy marketplace frontend assets or AgoraMarket commerce APIs.

## Required Environment

Server secrets file:

```bash
/home/ubuntu/.env.trading.secrets
```

Required before enabling AgoraMarket-backed exchange rates:

```bash
AGORA_MARKET_BASE_URL=http://127.0.0.1:8082
AGORA_MARKET_INTERNAL_API_KEY=<same internal key configured in AgoraMarketAPI>
AGORA_MARKET_INTERNAL_TIMEOUT_MS=3000
```

Trading service runtime:

```bash
TRADING_ADMIN_KEY=<set for admin endpoints>
TRADING_MCP_KEY=<set for MCP endpoints>
SPRING_DATASOURCE_URL=jdbc:mysql://10.0.0.119:3306/agora_trading?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=<set for trading DB>
SPRING_DATASOURCE_PASSWORD=<set for trading DB>
META_CONTROL_ML_SQL_SCHEMA=agora_trading
META_CONTROL_ML_SQL_SIGNAL_SCORER_TRAINING_TABLE=bt_signal_training_v8_mat
META_CONTROL_ML_SQL_WEEKLY_RETRAIN_TRAINING_VIEW=vw_signal_training_v2
# temporary bootstrap-only schema mode; replace after Flyway baseline is added.
SPRING_JPA_HIBERNATE_DDL_AUTO=update
SPRING_FLYWAY_ENABLED=false
PORT=8084
```

ML training and evaluation table names are bound through `meta-control.ml.sql.*`.
Keep `META_CONTROL_ML_SQL_SCHEMA=agora_trading` for the standalone trading
schema, or set it explicitly to a legacy schema during a controlled transition.

`SPRING_JPA_HIBERNATE_DDL_AUTO=update` is a temporary bootstrap-only schema mode.
It is not production hardening. Keep `SPRING_FLYWAY_ENABLED=false` until a
Flyway baseline exists under `src/main/resources/db/migration`; then replace
Hibernate schema update with schema validation and enable Flyway.
Use `scripts/schema_baseline_inventory.ps1` and `docs/schema-baseline.md` as the
read-only inventory step before generating the baseline.
Use `scripts/schema_baseline_compare_server.sh` on the server for the read-only
database table comparison; it must not deploy or mutate schema.
`scripts/verify_server.sh` can run that comparison with
`RUN_SCHEMA_BASELINE_COMPARE=1`; the default remains skipped for normal deploy
health checks.

## Local Acceptance

Split-boundary expectations are documented in `docs/split-audit.md`.

```powershell
.\scripts\verify_local.ps1
```

For split-boundary-only checks during cleanup:

```powershell
.\scripts\verify_split_boundaries.ps1
```

Expected:

- `mvn test` passes.
- SDK-backed exchange-rate unit tests pass.
- Shell syntax checks pass for `deploy.sh` and `scripts/*.sh` when Git Bash or `bash` is available.
- Spring context test starts with profile `local-smoke` and exchange-rate fallback if `AGORA_MARKET_INTERNAL_API_KEY` is not configured.
- Split deploy guardrails stay documented: blue-green cleanup, strict server env checks, `8084/8085` port validation, internal-client SDK install, temporary schema bootstrap mode, Flyway baseline prerequisite, and `/api/internal/...` contract paths.
- POM dependency boundary stays explicit: trading may depend on `com.agora:agora-market-internal-client`, not the marketplace application jar.
- Java package boundary stays explicit: trading-owned `com.agora.*` packages are allowlisted, nested marketplace-style package segments are rejected by local verification, and `com.agora.mcp.auth` remains service-level MCP API-key auth.
- Schema baseline prep stays read-only until the real trading database schema has been compared with `target/schema-baseline/entity-tables.txt`.
- No Flutter/AppVersion deployment residue remains.
- No marketplace search logging residue remains.
- No marketplace support-ticket, image-audit, or product-classification residue remains.
- No marketplace address/postal delivery residue remains.
- No unused OAuth2 service/DTO residue remains.
- No WalletConnect/Web3 login residue or OAuth2 client dependency remains.
- No unused AuthService/AuthCode/2FA or marketplace account-login DTO residue remains.
- No WebPush, product-report, product-validator, cart-summary, or marketplace order-event residue remains.
- No unused marketplace delivery/order/wallet enum, notification enum, logistics utility, or delivery/digital-order property residue remains.
- No empty legacy marketplace MCP tool placeholder residue remains.
- No legacy public HTTP allowlist residue remains for public assets, test routes, Telegram webhook, backtest routes, admin market/OCO routes, or market-data frontend paths.
- No unreferenced betting or marketplace status/type enum residue remains.
- No unused PWA log, traffic analytics, slot analytics, slot cache, or stale product/PWA/slot security-route residue remains.
- No unreferenced chat, staking, transaction DTO or unused marketplace/betting enum residue remains.
- No unused object storage, login/Tron, security audit, group AI, common utility, OCI maintenance, or AI group config residue remains.

HTTP startup smoke with an in-memory local database:

```powershell
.\scripts\smoke_local_health.ps1
```

Expected:

- Spring Boot starts with profile `local-smoke`.
- `local-smoke` does not register scheduled tasks; smoke logs include `Scheduling disabled for local-smoke profile`.
- Health passes at `http://127.0.0.1:18084/api/trading/actuator/health`.
- Smoke command-line overrides clear local external keys for AgoraMarket, OKX, Binance, Telegram, AI providers, and market-data providers even if host environment variables are set.
- Smoke logs prove H2 local DB, exchange-rate fallback, cleared OKX API key, disabled OKX auto-trade, skipped private WS, and disabled startup refresh.
- Smoke logs prove `AiTaskRouter` initialized with 0 providers.
- Smoke logs prove Jina embeddings are disabled with `Jina embedding client initialised: enabled=false`.
- Smoke logs prove public market WebSocket auto-subscribe is disabled with `[MarketWS] auto-subscribe config: enabled=false`.
- Smoke logs prove OKX liquidation WebSocket is disabled with `[OkxLiqWS] disabled by market.liquidation-ws.enabled=false`.
- Smoke logs prove OKX Earn trading-buffer top-up is disabled with `[EarnTopUp] config: enabled=false`.
- Smoke logs prove Polymarket external market monitoring is disabled with `[PolymarketMonitor] config: enabled=false`.
- Smoke logs prove position-exit manager is disabled with `[ExitMgr] init: enabled=false`.
- Smoke logs prove trailing-stop OCO updates are disabled with `[TrailingStop] config: enabled=false`.
- Smoke logs prove short-squeeze alerting and Binance taker-buy collection are disabled with `[ShortSqueezeAlert] config: enabled=false takerBuyCollectorEnabled=false`; code defaults also keep both off if the env keys are omitted.
- Smoke logs must not show startup market-data backfills (`DexFlowBackfill`, `HLFundingBackfill`, `CoinalyzeBackfill`, or `CMIBackfill`).
- Smoke logs must not show order placement, Telegram sends, private trading WS connection, or auto-execution enabled.
- The script stops the temporary Maven/Java process tree after the check.

## Server Deploy Template

`deploy.sh` is a blue-green skeleton for one host:

First-time server bootstrap/preflight:

```bash
cd /home/ubuntu/agora-trading-api
bash scripts/bootstrap_server.sh
```

If the repo has not been cloned yet:

```bash
bash -c "$(curl -fsSL https://raw.githubusercontent.com/Redandan/agora-trading-api/main/scripts/bootstrap_server.sh)"
```

The bootstrap script checks server tools, clones or fetches this repo, confirms
`.env.trading.secrets.example` is present, checks the AgoraMarket exchange-rate dependency
health endpoint, and reports whether nginx already contains `/api/trading/`. It
does not create or print the real secret file.

`deploy.sh` refuses to overwrite staged or unstaged server worktree changes before
syncing from `origin/main`.

Pre-deploy check that does not deploy, start, stop, or switch traffic:

```bash
cd /home/ubuntu/agora-trading-api
bash scripts/preflight_server.sh
```

Expected:

- shell syntax passes for `deploy.sh` and `scripts/*.sh`.
- required server tools exist.
- AgoraMarket exchange-rate dependency health passes by default; `REQUIRE_AGORA_MARKET_HEALTH=0` is diagnostic-only and is not deploy acceptance.
- `.env.trading.secrets.example` covers every server script `require_env_key` without committing real secret values.
- `.env.trading.secrets.example` lists optional runtime safety toggles for startup backfills, market WebSockets, trading execution, Telegram, AI providers, and external market-data providers.
- Hourly orchestrator, market indicator collection, BTC price-move indicator writes, ETF pressure refresh, and meta-control attribution default off in code and the tracked template; enable `META_CONTROL_HOURLY_ORCHESTRATOR_ENABLED=true`, `META_CONTROL_INDICATOR_HISTORY_ENABLED=true`, `META_CONTROL_BTC_PRICE_MOVE_INDICATOR_ENABLED=true`, `META_CONTROL_ETF_PRESSURE_REFRESH_ENABLED=true`, or `META_CONTROL_ATTRIBUTION_ENABLED=true` only after this service should own external indicator API collection, `market_indicator_history` writes, ETF refresh calls, K-line gap backfills, attribution writes, and wide-TP Telegram scans.
- Decision-audit cleanup, 1m kline pruning, and ephemeral strategy cleanup default off in code and the tracked template; enable `META_CONTROL_AUDIT_ENABLED=true`, `KLINE_PRUNING_ENABLED=true`, or `TRADING_EPHEMERAL_CLEANUP_ENABLED=true` only after this service should own those deletion jobs.
- Composite indicator scheduled evaluation defaults off in code and the tracked template; set `META_CONTROL_COMPOSITE_INDICATOR_SCHEDULER_ENABLED=true` only after the trading service should persist CMI scores and emit CMI alerts.
- Market-indicator attention evaluation defaults off in code and the tracked template; enable `META_CONTROL_MARKET_INDICATOR_ATTENTION_ENABLED=true` only after this service should evaluate MIH attention rules and emit resulting notifications.
- ML protection scans, shadow inference logging, edge staleness alerts, and auto-retrain default off in code and the tracked template; enable `META_CONTROL_ML_PROTECTION_ENABLED=true`, `META_CONTROL_ML_SHADOW_ENABLED=true`, `META_CONTROL_ML_EDGE_WATCHER_ENABLED=true`, or `META_CONTROL_ML_AUTORETRAIN_ENABLED=true` only after the deployed trading service should own those ML automation writes, live-signal HeatWave prediction lookups, `ml_inference_log` writes, and Telegram alerts. Keep `META_CONTROL_ML_PROTECTION_AUTO_KILL_SECONDARY_LOAD=false` unless this service is explicitly allowed to kill stuck HeatWave connections.
- ML materialized training-table startup refresh defaults off in code and the tracked template; enable `META_CONTROL_ML_MATERIALIZED_REFRESH_STARTUP_CHECK_ENABLED=true` only after the deployed trading service should auto-populate `bt_signal_training_v8_mat` on startup. MCP-triggered manual refresh remains an explicit operator action.
- Daily ML digest notifications default off in code and the tracked template; set `META_CONTROL_DAILY_ML_DIGEST_ENABLED=true` only after Telegram and ML pipeline digest behavior are intended for the trading service.
- Market flip detection, analysis, and auto-escalation default off in code and the tracked template; set `META_CONTROL_MARKET_FLIP_DETECTOR_ENABLED=true`, `META_CONTROL_MARKET_FLIP_ANALYSIS_ENABLED=true`, or `META_CONTROL_MARKET_FLIP_AUTO_ESCALATE_ENABLED=true` only after this service should own flip event writes, immediate flip notifications, pending flip AI analysis, event status updates, audit writes, and Telegram escalation.
- Wick-capture shadow observer defaults off in code and the tracked template; set `WICK_CAPTURE_SHADOW_ENABLED=true` only after the service should persist `bt_wick_capture_shadow` rows, write attention audit, and send Telegram context. Keep `WICK_CAPTURE_SHADOW_BOOTSTRAP_ENABLED=false` unless historical 15m bootstrap writes are intentionally scheduled.
- Shadow signal cleanup defaults off in code and the tracked template; keep `SHADOW_CLEANUP_ENABLED=false` until this service should own automatic `bt_live_signal` timeout writes with `exit_reason='SHADOW_TIMEOUT'`.
- Daily TG report orchestration defaults off in code and the tracked template; keep `TRADING_DAILY_TG_REPORT_ENABLED=false` until the deployed trading service owns daily Telegram reporting.
- Scheduled trading notification digests default off in code and the tracked template, including attention weekly digest (`META_CONTROL_ATTENTION_WEEKLY_DIGEST_ENABLED=false`), scorecard digest (`META_CONTROL_SCORECARD_DIGEST_ENABLED=false`), autonomous digest (`TRADING_AUTONOMOUS_DIGEST_ENABLED=false`, `TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED=false`), and ScoreBuy forming-day notification (`TRADING_SCORE_BUY_FORMING_DAY_NOTIFICATION_ENABLED=false`, `TRADING_SCORE_BUY_FORMING_DAY_NOTIFICATION_TELEGRAM_ENABLED=false`).
- Event-calendar freshness notifications default off in code and the tracked template; enable `TRADING_EVENT_CALENDAR_FRESHNESS_NOTIFICATION_ENABLED=true` only when this service should send weekly calendar-maintenance Telegram reminders.
- Live-signal retry notification defaults off in code and the tracked template; enable `TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED=true` only when this service should resend pending `bt_live_signal` Telegram notifications and mark them notified.
- Event-scan scheduled outbound notifications default off and dry-run in the tracked template; keep `EVENT_SCAN_NOTIFICATION_ENABLED=false` and `EVENT_SCAN_NOTIFICATION_DRY_RUN=true` until that outbound operator notification path belongs to trading production.
- Execution-event scheduled scanning defaults off and its notification path defaults dry-run in the tracked template; keep `EXECUTION_EVENT_ENABLED=false` and `EXECUTION_EVENT_NOTIFICATION_DRY_RUN=true` until normalized execution-event writes and Telegram cards belong to trading production.
- BTC price-move Telegram alerts default off in code and the tracked template; keep `TRADING_BTC_PRICE_MOVE_ALERT_ENABLED=false` until that alert stream belongs to trading production.
- DB slow-query monitoring is read-only in the split service; local verification rejects `KILL QUERY`/safe-kill code so the diagnostic report cannot terminate database queries.
- Gemini advisor and its hint flip/staleness detectors default off in code and the tracked template; enable `TRADING_GEMINI_ADVISOR_ENABLED=true` plus detector flags only after AI hint generation and Telegram alerts belong to trading production.
- ShortAiFilter defaults off in code and the tracked template; enable `TRADING_SHORT_AI_FILTER_ENABLED=true` only after short-signal external AI/MCP shadow checks belong to trading production.
- Autonomous exploration monitoring defaults off in code and the tracked template; enable `TRADING_EXPLORATION_MONITOR_ENABLED=true` and `TRADING_EXPLORATION_MONITOR_TELEGRAM_ENABLED=true` only after that monitor belongs to the deployed trading service.
- AI strategy discovery scheduling defaults off in code and the tracked template; enable `AI_STRATEGY_DISCOVERY_ENABLED=true` only when this service should own scheduled AI strategy generation and `bt_strategy` writes.
- Autonomous exploration loop, auto-rollout promotion, runtime-evidence writes, funding-arb scheduler, discovery AI suggestions, and MCP guardian live actions are explicit opt-in keys in the tracked template and default off; keep `TRADING_EXPLORATION_LOOP_ENABLED=false`, `TRADING_EXPLORATION_LOOP_PRODUCTION_ENABLED=false`, `TRADING_EXPLORATION_ROLLOUT_AUTO_ENABLED=false`, `TRADING_EXPLORATION_ROLLOUT_ALLOW_PRODUCTION_PROMOTION=false`, and `TRADING_EXPLORATION_ROLLOUT_ALLOW_CAP_INCREASE=false` unless the deployed trading service should advance exploration state automatically.
- Signal outcome verification and alpha promotion tracking default off in code and the tracked template; enable `SIGNAL_VERIFICATION_SCHEDULER_ENABLED=true` or `AGORA_ALPHA_TRACKER_ENABLED=true` only when this service should own outcome verification writes, accuracy reports, snapshot-file writes, and related Telegram alerts.
- Market-signal risk-card scheduling defaults off and dry-run in the tracked template; keep `MARKET_SIGNAL_RISK_CARD_ENABLED=false` and `MARKET_SIGNAL_RISK_CARD_DRY_RUN=true` until scheduled market-signal cards should be emitted by trading production.
- Polymarket monitoring and WAI scheduled calculation default off in code and the tracked template; enable `POLYMARKET_MONITOR_ENABLED=true` only when this service should call Polymarket APIs, persist odds/alert rows, and send related Telegram digests, and keep `TRADING_WAI_ENABLED=false` until it should persist WAI `market_indicator_history` rows.
- Market WebSocket side effects default off in code and the tracked template; set `MARKET_LIQUIDATION_WS_ENABLED=true` only after the trading runtime is ready to connect to OKX public liquidation streams.
- K-line divergence alerting defaults off in code and the tracked template; set `TRADING_KLINE_DIVERGENCE_ENABLED=true` only after manual/snapshot divergence scans should be allowed to send Telegram alerts.
- Grid runtime, auto-rebalance scheduling, and grid orphan recovery default off in code and the tracked template; enable `TRADING_GRID_ENABLED=true`, `TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=true`, and `GRID_RECOVERY_ENABLED=true` only after the deployed trading service should own grid order placement and recovery.
- OCO poller and OKX private WS OCO handling default off in code and the tracked template; enable `TRADING_OCO_POLLER_ENABLED=true` only when the deployed trading service should own OCO close detection, auto retry, reconciliation writes, and related Telegram alerts.
- OKX Earn trading-buffer top-up and trailing-stop scheduling default off in code and the tracked template; enable `OKX_EARN_TOPUP_ENABLED=true` only when this service should redeem/transfer Earn funds automatically, and enable `TRAILING_STOP_ENABLED=true` only when it should write trailing state or manage OCO updates.
- ScoreBuy pre-position, confirmed-deploy, post-scout add execution, and near-trigger notifications default off and dry-run in code and the tracked template; enable `TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=true`, `TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=true`, `TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=true`, `TRADING_SCORE_BUY_POST_SCOUT_ADD_NOTIFICATION_ENABLED=true`, and `TRADING_SCORE_BUY_POST_SCOUT_ADD_NOTIFICATION_TELEGRAM_ENABLED=true` only after those dry-run/execution and Telegram alert paths belong to trading production.
- schema baseline compare tooling is syntax-checked but is not run automatically by preflight.
- required secret keys are present and non-empty without printing values.
- `AgoraMarketAPI/internal-client` exists for local SDK install during deploy.
- AgoraMarket exchange-rate dependency health is checked.
- nginx `/api/trading/` path split is reported.

Last observed server state from 2026-06-05 Asia/Taipei:

- AgoraMarketAPI exists at `/home/ubuntu/AgoraMarketAPI`.
- AgoraMarketAPI active port file reports `8082`.
- Local AgoraMarketAPI health is `UP`.
- `git`, `mvn`, `java`, and `curl` are installed.
- `/home/ubuntu/agora-trading-api` has been bootstrapped and can fast-forward from `origin/main`.
- `/home/ubuntu/agora-trading-api/.env.trading.secrets.example` has been created.
- `/home/ubuntu/.env.trading.secrets` has been created without printing secret values.
- independent trading database `agora_trading` has been created.
- nginx `/api/trading/` location has been installed and reloaded.
- trading snapshot was observed at `origin/main` commit `11612b9`, active port `8084`; this is historical evidence, not a current-deployment claim.
- This is an observed deployment snapshot, not proof that the current `origin/main`
  commit is deployed. Re-run deploy and `scripts/verify_server.sh` before treating
  production as current. `scripts/verify_server.sh` now checks that the server
  worktree commit matches `origin/main` by default.
- `scripts/verify_server.sh` passed with:
  - local trading health: `http://127.0.0.1:8084/api/trading/actuator/health`
  - local AgoraMarket exchange-rate dependency health: `http://127.0.0.1:8082/api/actuator/health`
  - public trading health: `https://agoramarketapi.purrtechllc.com/api/trading/actuator/health`

Deploy after secrets and nginx path are ready:

```bash
cd /home/ubuntu/agora-trading-api
bash scripts/install_nginx_path.sh
bash deploy.sh
```

Defaults:

- ports: `8084` and `8085`
- health: `http://127.0.0.1:<port>/api/trading/actuator/health`
- jar: `target/agora-trading-api-1.0-SNAPSHOT.jar`
- env file: `/home/ubuntu/.env.trading.secrets`
- deployment metadata: `app.port`, `app.pid`, and `app.commit`

`deploy.sh`, `scripts/install_nginx_path.sh`, and `scripts/verify_server.sh`
all treat `8084/8085` as the blue-green port set. Unknown active port state is
an error, not a fallback target.

After writing `app.port`, `app.pid`, and `app.commit`, `deploy.sh` runs
`scripts/verify_server.sh` by default with `RUN_PREFLIGHT=0`. This proves the
new active metadata, local health, AgoraMarket exchange-rate dependency health,
and nginx `/api/trading/` path before the deploy reports complete. When
`UPDATE_NGINX=1`, deploy also verifies public trading health through
`DEFAULT_PUBLIC_TRADING_HEALTH_URL`, defaulting to
`https://agoramarketapi.purrtechllc.com/api/trading/actuator/health`. Set
`RUN_POST_DEPLOY_VERIFY=0` only for deliberate emergency bypasses. When it is
used, deploy keeps the previous blue-green instance and nginx backup because the
new instance has not been proven by server verification.

Blue-green deploy keeps the previous instance alive until post-deploy
verification passes, then logs `draining old instance after verification` before
stopping the old port. If verification fails, the old process is still available
and deploy logs `post-deploy verification failed; rolling back active metadata`.
The rollback restores `app.port`, `app.pid`, and `app.commit`; when nginx was
updated, it also logs `restoring nginx trading upstream after failed verification`
and restores the nginx backup before cleaning up the new instance. A missing
post-deploy verifier is treated as the same rollback-worthy failure.

## Nginx Path Split

Suggested routing:

```nginx
location /api/trading/ {
    proxy_pass http://127.0.0.1:8084;
}
```

If blue-green is used, deploy should update nginx to the active `app.port`, matching the existing AgoraMarketAPI style.

## Post-Deploy Smoke

```bash
PORT=$(cat /home/ubuntu/agora-trading-api/app.port)
curl -fsS "http://127.0.0.1:${PORT}/api/trading/actuator/health"
curl -fsS "http://127.0.0.1:8082/api/actuator/health"
```

Or run the server verifier:

```bash
cd /home/ubuntu/agora-trading-api
bash scripts/verify_server.sh
```

Optional public path check:

```bash
PUBLIC_TRADING_HEALTH_URL="https://agoramarketapi.purrtechllc.com/api/trading/actuator/health" \
  bash scripts/verify_server.sh
```

Optional schema baseline table comparison before generating Flyway baseline:

```bash
RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh
```

Exchange-rate behavior:

- with `AGORA_MARKET_INTERNAL_API_KEY`: trading calls AgoraMarket internal API.
- without key, timeout, or 401: trading falls back to static rates.

AgoraMarket checks in these scripts are dependency checks for the trading
exchange-rate client. They do not deploy, configure, or mutate AgoraMarketAPI.

`scripts/verify_server.sh` checks:

- required local tools: `bash`, `awk`, `curl`, `git`, `java`, `lsof`, `mktemp`, `mvn`, `ps`, `sudo`, and `systemctl` when nginx reload is enabled; the nginx path installer also fails fast on its own required tools.
- shell syntax passes for `deploy.sh` and `scripts/*.sh` via `scripts/preflight_server.sh`.
- server worktree commit matches `origin/main` by default; set `VERIFY_GIT_CURRENT=0` only for explicit rollback verification.
- deployed `app.commit`, `app.pid`, and `app.port` metadata must exist by default; set `REQUIRE_DEPLOY_METADATA=0` only for explicit non-deploy diagnostics.
- deployed `app.commit` metadata matches the current worktree HEAD.
- deployed `app.pid` metadata points to a running process that is listening on the active `app.port`.
- public HTTP allowlist stays minimal: OpenAPI docs, MCP streamable HTTP, actuator probes/metrics, rate-limit JSON redirect, and favicon.
- `AGORA_MARKET_BASE_URL` must point at local AgoraMarketAPI dependency `http://127.0.0.1:8082`; deploy, preflight, and server verification fail on stale values.
- `deploy.sh` checks AgoraMarket exchange-rate dependency health before starting the blue-green switch, so dependency failure stops the deploy before a new instance or nginx change is attempted.
- preflight and server verification require AgoraMarket exchange-rate dependency health by default; `REQUIRE_AGORA_MARKET_HEALTH=0` is only for diagnostic preflight and does not make deploy acceptance pass.
- local MCP `getMcpRegistryVersion` passes through `/api/trading/mcp` using `TRADING_MCP_KEY`, proving the trading context path and MCP auth mapping.
- deploy runs this server verification after switching active metadata by default; set `RUN_POST_DEPLOY_VERIFY=0` only for deliberate emergency bypasses.
- deploy restores active metadata and nginx backup when post-deploy verification fails or the post-deploy verifier is missing.
- deploy drains the previous blue-green instance only after verification passes; logs include `draining old instance after verification`. If post-deploy verification is skipped, deploy keeps the previous instance and nginx backup.
- nginx deploy verifies public trading health through `DEFAULT_PUBLIC_TRADING_HEALTH_URL` by default.
- required server env keys exist and are non-empty in `/home/ubuntu/.env.trading.secrets` without printing secret values.
- deploy refuses to overwrite staged or unstaged server worktree changes before syncing from `origin/main`.
- deploy and server verification fail fast if `AgoraMarketAPI/internal-client` is missing; deploy installs it into the server Maven local repo before building trading.
- Remaining `enabled:true` fallbacks are deliberately limited to protective or internal behavior and enforced by `scripts/verify_local.ps1`: MCP master-approval probe wait, Telegram noise reduction, enabled-strategy kline data validation, and deterministic regime filtering. Any new default-on fallback must be classified or changed to explicit opt-in before deploy prep is considered clean.
- Coinalyze credentials use `TRADING_MARKET_DATA_COINALYZE_API_KEY`, which maps to `trading.market-data.coinalyze.api-key`; legacy external-style Coinalyze env names are not used by the trading code.
- trading uses an independent MySQL database, currently `agora_trading`.
- `SPRING_JPA_HIBERNATE_DDL_AUTO=update` remains temporary bootstrap-only schema mode and `SPRING_FLYWAY_ENABLED=false` remains required until a Flyway baseline exists.
- schema baseline database comparison is available through `scripts/schema_baseline_compare_server.sh`; run it through `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh` before generating `V1__baseline.sql`.
- active local trading health via required `app.port` metadata by default, limited to the `8084/8085` blue-green port set; `REQUIRE_DEPLOY_METADATA=0` may use default `8084` only for non-deploy diagnostics.
- local AgoraMarket exchange-rate dependency health through `http://127.0.0.1:8082/api/actuator/health` by default.
- optional public trading health URL.
- nginx `/api/trading/` path split presence by default; set `REQUIRE_NGINX_TRADING_PATH=0` only for non-nginx verification environments.
- nginx service must be active by default; set `REQUIRE_NGINX_SERVICE=0` only for non-nginx verification environments.

## Rollback

Use the previously active port if it is still running:

```bash
echo 8084 > /home/ubuntu/agora-trading-api/app.port
sudo nginx -t && sudo systemctl reload nginx
```

If the old process was already stopped, redeploy the prior git commit:

```bash
git reset --hard <previous-good-commit>
bash deploy.sh
```

For a deliberate rollback verification where the server commit should not match
`origin/main`, run:

```bash
VERIFY_GIT_CURRENT=0 bash scripts/verify_server.sh
```
