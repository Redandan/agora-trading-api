# Trading Deploy Runbook

## Scope

This repo deploys the standalone trading service. It should not deploy marketplace frontend assets or AgoraMarket commerce APIs.

For the current acceptance state and remaining cutover blockers, start with
`docs/split-acceptance-status.md`.

## Required Environment

Server secrets file:

```bash
/home/ubuntu/.env.trading.secrets
```

Required before enabling AgoraMarket-backed exchange rates:

```bash
AGORA_MARKET_BASE_URL=https://agoramarketapi.purrtechllc.com
AGORA_MARKET_INTERNAL_API_KEY=<same internal key configured in AgoraMarketAPI>
AGORA_MARKET_INTERNAL_TIMEOUT_MS=3000
# Optional: independent inbound key for AgoraMarketAPI -> Trading report gateway.
# If unset, trading reuses AGORA_MARKET_INTERNAL_API_KEY.
TRADING_INTERNAL_API_KEY=<optional>
```

Trading service runtime:

```bash
TRADING_MCP_KEY=<set for MCP endpoints>
TELEGRAM_BOT_TOKEN=<set before enabling Telegram sends>
# New deployments may use TELEGRAM_BOT_CHANNEL_ID; TELEGRAM_CHANNEL_ID is kept
# as the compatibility key for the legacy AgoraMarketAPI Telegram channel env.
TELEGRAM_CHANNEL_ID=<set before enabling Telegram channel broadcasts>
SPRING_DATASOURCE_URL=jdbc:mysql://10.0.0.119:3306/agora_market?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=<set for shared DB>
SPRING_DATASOURCE_PASSWORD=<set for shared DB>
META_CONTROL_ML_SQL_SCHEMA=agora_market
META_CONTROL_ML_SQL_SIGNAL_SCORER_TRAINING_TABLE=bt_signal_training_v8_mat
META_CONTROL_ML_SQL_WEEKLY_RETRAIN_TRAINING_VIEW=vw_signal_training_v2
TRADING_CORS_ALLOWED_ORIGINS=http://localhost:*,http://127.0.0.1:*
# Hardened schema mode. Flyway uses a Trading-owned history table so it does not
# mix with AgoraMarketAPI's shared flyway_schema_history rows.
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
SPRING_FLYWAY_ENABLED=true
SPRING_FLYWAY_TABLE=trading_flyway_schema_history
SPRING_FLYWAY_BASELINE_ON_MIGRATE=true
SPRING_FLYWAY_BASELINE_VERSION=1
PORT=8084
```

ML training and evaluation table names are bound through `meta-control.ml.sql.*`.
Keep `META_CONTROL_ML_SQL_SCHEMA=agora_market` for the current shared-DB split.

`SPRING_JPA_HIBERNATE_DDL_AUTO=validate` is the production hardening target.
`SPRING_FLYWAY_ENABLED=true` uses `SPRING_FLYWAY_TABLE=trading_flyway_schema_history`,
so the Trading service does not read or validate AgoraMarketAPI's existing
`flyway_schema_history` rows in the shared `agora_market` database.
`SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` is required for the first Trading
Flyway adoption on the existing shared schema; with the Trading-owned history
table present, it is harmless on later deploys.
Deploy, server preflight, and verification require these hardened schema env
values.
Use `scripts/schema_baseline_inventory.ps1` and `docs/schema-baseline.md` as the
read-only inventory step for baseline drift review.
Use `scripts/schema_baseline_compare_server.sh` on the server for the read-only
database table comparison; it must not deploy or mutate schema.
Use `scripts/schema_baseline_generate_server.sh` only after the shared-mode
compare passes; it dumps reviewable trading-entity DDL into
`src/main/resources/db/migration/V1__baseline.sql` without enabling Flyway or
cleaning shared tables.
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
- The API-key guarded report gateway stays limited to read-only `/api/trading/internal/reports/**` endpoints for AgoraMarketAPI Telegram commands.
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
- Health passes at `http://127.0.0.1:18084/api/actuator/health`.
- MCP guard checks pass through `/api/mcp`, proving disabled responses for live sentiment reads, external health probes, and external backfill/import reads.
- DataFreshnessGuard RCA remains read-only. `diagnoseDataFreshnessGuardBlocks`
  may read recent `bt_decision_audit` rows and existing `md_kline` freshness,
  but it must only report current snapshot states such as `READY_NOW`,
  `STALE_NOW`, `NO_DATA_NOW`, and `QUERY_FAILED_NOW`; it must not import,
  backfill, trade, or mutate guard behavior.
- Trailing-stop PnL replay remains read-only. Local smoke calls
  `analyzeTrailingStopPnlReplay` through `/api/mcp` and requires the
  `boundary: READ_ONLY` marker, the
  `acceptanceTarget: total trailing PnL improvement >= 5%` marker, and an
  explicit `sampleStatus`, `acceptanceBlocker`, and
  `acceptanceBlockerDetail`. A local H2 `NO_REPLAYABLE_TRADES` result proves
  tool wiring and safety only; the 30d PnL acceptance for issue #3 still
  requires a deployed runtime with real normalized backtest/K-line samples.
  `intervalCode` selects normalized backtest trades, while
  `replayIntervalCode` defaults to `1m` and selects the K-lines used to resolve
  intrabar trigger/stop ordering.
- BTC spot anti-wick policy coverage remains read-only. Local smoke calls
  `analyzeSpotAntiWickPolicyCoverage` through `/api/mcp` and requires the
  `boundary: READ_ONLY`, `ULTRA_LOW_DISASTER`, and summary markers. This proves
  the #1 MCP surface wiring locally; closure still requires deployed
  server-local guardrail smoke without `REVIEW_POLICY_GAPS`.
- Smoke command-line overrides clear local external keys for AgoraMarket, OKX, Binance, Telegram, AI providers, and market-data providers even if host environment variables are set.
- Smoke command-line overrides clear both Telegram bot token and channel id, so local smoke cannot send channel messages even when host `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_CHANNEL_ID`, or legacy `TELEGRAM_CHANNEL_ID` are present.
- Smoke logs prove H2 local DB, exchange-rate fallback, cleared OKX API key, disabled OKX auto-trade, skipped private WS, and disabled startup refresh.
- Smoke logs prove `AiTaskRouter` initialized with 0 providers.
- Smoke logs prove Jina embeddings are disabled with `Jina embedding client initialised: enabled=false`.
- Smoke logs prove public market WebSocket auto-subscribe is disabled with `[MarketWS] auto-subscribe config: enabled=false`.
- When production intentionally enables public market WebSocket auto-subscribe, keep `MARKET_WS_AUTO_SUBSCRIBE_PROVIDERS=okx` unless Binance WS access from the host has been explicitly verified; Binance Spot can return HTTP 451 from restricted regions.
- Smoke logs prove OKX liquidation WebSocket is disabled with `[OkxLiqWS] disabled by market.liquidation-ws.enabled=false`.
- Local smoke forces `okx.earn-topup.enabled=false`; the OKX Earn top-up scheduler bean is explicit opt-in and smoke logs must not show Earn redemption or transfer side effects.
- Local smoke forces `polymarket.monitor.enabled=false`; the Polymarket monitor scheduler bean is explicit opt-in and smoke logs must not show Polymarket snapshot, backfill, or digest side effects.
- Local smoke forces `position-exit-manager.enabled=false` and `trailing-stop.enabled=false`; both OCO-management scheduler beans are explicit opt-in and smoke logs must not show OCO modification or trailing-state side effects.
- Local smoke forces short-squeeze alerting and Binance taker-buy collection off; both scheduler beans are explicit opt-in, and smoke logs must not show ShortSqueeze alerts or SpotTakerBuy collection.
- Smoke logs must not show startup market-data backfills (`DexFlowBackfill`, `HLFundingBackfill`, `CoinalyzeBackfill`, or `CMIBackfill`).
- Local verification dynamically checks every `ApplicationRunner`/`CommandLineRunner`, so startup jobs must be async and explicit opt-in before deploy prep can pass.
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
- `.env.trading.secrets.example` covers every server script `require_env_key` and `require_env_value` without committing real secret values.
- `.env.trading.secrets.example` lists optional runtime safety toggles for startup backfills, market WebSockets, trading execution, Telegram, AI providers, and external market-data providers.
- Env-template validation pins the AgoraMarket internal API timeout to `3000` and keeps default CORS local-only through `TRADING_CORS_ALLOWED_ORIGINS=http://localhost:*,http://127.0.0.1:*`; widen CORS only when the deployed ingress/domain is intentionally ready.
- Deploy, server preflight, and server verification require `AGORA_MARKET_INTERNAL_TIMEOUT_MS=3000`, so exchange-rate dependency failures remain bounded instead of stalling split deploy acceptance.
- Hourly orchestrator, market indicator collection, BTC price-move indicator writes, ETF pressure refresh/calculation fetches, and meta-control attribution default off in code and the tracked template; the hourly orchestrator and BTC price-move indicator schedulers are bean-level explicit opt-in. Enable `META_CONTROL_HOURLY_ORCHESTRATOR_ENABLED=true`, `META_CONTROL_INDICATOR_HISTORY_ENABLED=true`, `META_CONTROL_BTC_PRICE_MOVE_INDICATOR_ENABLED=true`, `META_CONTROL_ETF_PRESSURE_REFRESH_ENABLED=true`, or `META_CONTROL_ATTRIBUTION_ENABLED=true` only after this service should own external indicator API collection, `market_indicator_history` writes, ETF Yahoo Finance reads, K-line gap backfills, attribution writes, and wide-TP Telegram scans.
- Decision-audit cleanup, 1m kline pruning, and ephemeral strategy cleanup default off in code and the tracked template; enable `META_CONTROL_AUDIT_ENABLED=true`, `KLINE_PRUNING_ENABLED=true`, or `TRADING_EPHEMERAL_CLEANUP_ENABLED=true` only after this service should own those deletion jobs.
- Composite indicator scheduled evaluation is bean-level explicit opt-in and defaults off in code and the tracked template; set `META_CONTROL_COMPOSITE_INDICATOR_SCHEDULER_ENABLED=true` only after the trading service should persist CMI scores and emit CMI alerts.
- Market-indicator attention evaluation is bean-level explicit opt-in and defaults off in code and the tracked template; enable `META_CONTROL_MARKET_INDICATOR_ATTENTION_ENABLED=true` only after this service should evaluate MIH attention rules and emit resulting notifications.
- ML protection scans, shadow inference logging, edge staleness alerts, and auto-retrain default off in code and the tracked template; ML protection scanning and edge staleness watching are bean-level explicit opt-in. Enable `META_CONTROL_ML_PROTECTION_ENABLED=true`, `META_CONTROL_ML_SHADOW_ENABLED=true`, `META_CONTROL_ML_EDGE_WATCHER_ENABLED=true`, or `META_CONTROL_ML_AUTORETRAIN_ENABLED=true` only after the deployed trading service should own those ML automation writes, live-signal HeatWave prediction lookups, `ml_inference_log` writes, and Telegram alerts. Keep `META_CONTROL_ML_PROTECTION_AUTO_KILL_SECONDARY_LOAD=false` unless this service is explicitly allowed to kill stuck HeatWave connections.
- ML materialized training-table startup refresh defaults off in code and the tracked template; enable `META_CONTROL_ML_MATERIALIZED_REFRESH_STARTUP_CHECK_ENABLED=true` only after the deployed trading service should auto-populate `bt_signal_training_v8_mat` on startup. MCP-triggered manual refresh remains an explicit operator action.
- Daily ML digest notifications default off in code and the tracked template; set `META_CONTROL_DAILY_ML_DIGEST_ENABLED=true` only after Telegram and ML pipeline digest behavior are intended for the trading service.
- Market flip detection, analysis, and auto-escalation default off in code and the tracked template; analysis and auto-escalation schedulers are bean-level explicit opt-in. Set `META_CONTROL_MARKET_FLIP_DETECTOR_ENABLED=true`, `META_CONTROL_MARKET_FLIP_ANALYSIS_ENABLED=true`, or `META_CONTROL_MARKET_FLIP_AUTO_ESCALATE_ENABLED=true` only after this service should own flip event writes, immediate flip notifications, pending flip AI analysis, event status updates, audit writes, and Telegram escalation.
- Wick-capture shadow observer is bean-level explicit opt-in and defaults off in code and the tracked template; set `WICK_CAPTURE_SHADOW_ENABLED=true` only after the service should persist `bt_wick_capture_shadow` rows, write attention audit, and send Telegram context. Keep `WICK_CAPTURE_SHADOW_BOOTSTRAP_ENABLED=false` unless historical 15m bootstrap writes are intentionally scheduled.
- Shadow signal cleanup is bean-level explicit opt-in and defaults off in code and the tracked template; keep `SHADOW_CLEANUP_ENABLED=false` until this service should own automatic `bt_live_signal` timeout writes with `exit_reason='SHADOW_TIMEOUT'`.
- Daily TG report orchestration defaults off in code and the tracked template; keep `TRADING_DAILY_TG_REPORT_ENABLED=false` until the deployed trading service owns daily Telegram reporting.
- Scheduled trading notification digests default off in code and the tracked template, including attention weekly digest (`META_CONTROL_ATTENTION_WEEKLY_DIGEST_ENABLED=false`), scorecard digest (`META_CONTROL_SCORECARD_DIGEST_ENABLED=false`), autonomous digest (`TRADING_AUTONOMOUS_DIGEST_ENABLED=false`, `TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED=false`), and ScoreBuy forming-day notification (`TRADING_SCORE_BUY_FORMING_DAY_NOTIFICATION_ENABLED=false`, `TRADING_SCORE_BUY_FORMING_DAY_NOTIFICATION_TELEGRAM_ENABLED=false`). Scorecard, autonomous digest, and ScoreBuy forming-day notification schedulers are bean-level explicit opt-in; attention weekly digest keeps a method-level guard so MCP manual digest access remains available.
- Event-calendar freshness notifications default off in code and the tracked template; enable `TRADING_EVENT_CALENDAR_FRESHNESS_NOTIFICATION_ENABLED=true` only when this service should send weekly calendar-maintenance Telegram reminders.
- Live-signal retry notification defaults off in code and the tracked template; enable `TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED=true` only when this service should resend pending `bt_live_signal` Telegram notifications and mark them notified.
- Event-scan scheduled outbound notifications are bean-level explicit opt-in, default off, and dry-run in the tracked template; keep `EVENT_SCAN_NOTIFICATION_ENABLED=false` and `EVENT_SCAN_NOTIFICATION_DRY_RUN=true` until that outbound operator notification path belongs to trading production.
- Execution-event scheduled scanning is bean-level explicit opt-in, defaults off, and its notification path defaults dry-run in the tracked template; keep `EXECUTION_EVENT_ENABLED=false` and `EXECUTION_EVENT_NOTIFICATION_DRY_RUN=true` until normalized execution-event writes and Telegram cards belong to trading production.
- BTC price-move Telegram alerts default off in code and the tracked template; keep `TRADING_BTC_PRICE_MOVE_ALERT_ENABLED=false` until that alert stream belongs to trading production.
- DB slow-query monitoring is read-only in the split service; local verification rejects `KILL QUERY`/safe-kill code so the diagnostic report cannot terminate database queries.
- Gemini advisor scheduling and its hint flip/staleness detectors default off in code and the tracked template; the advisor and detector schedulers are bean-level explicit opt-in. Enable `TRADING_GEMINI_ADVISOR_ENABLED=true` plus detector flags only after AI hint generation, hint-table scans, and Telegram alerts belong to trading production.
- LongAiFilter defaults off in code and the tracked template; enable `TRADING_LONG_AI_FILTER_ENABLED=true` only after LONG entry guard reads to Fear&Greed/OKX public market endpoints belong to trading production.
- ShortAiFilter defaults off in code and the tracked template; enable `TRADING_SHORT_AI_FILTER_ENABLED=true` only after short-signal external AI/MCP shadow checks belong to trading production.
- Ensemble MCP preview live market reads default off in code and the tracked template; enable `TRADING_ENSEMBLE_PREVIEW_LIVE_MARKET_READS_ENABLED=true` only when manual ensemble previews should read Fear&Greed, OKX, whale flow, and Polymarket directly.
- Market-data MCP live sentiment reads default off in code and the tracked template; enable `TRADING_MARKET_DATA_MCP_LIVE_SENTIMENT_ENABLED=true` only when manual market-data tools such as sentiment dashboards, Polymarket risk checks, Fear&Greed history/backfill, or F&G trade-analysis should read Fear&Greed, whale flow, OKX, Polymarket, and orderbook endpoints directly.
- Market-data MCP external health probes default off in code and the tracked template; enable `TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED=true` only when `getSystemHealth` should actively ping OKX, Fear&Greed, Polymarket, whale flow, and orderbook endpoints.
- Market-data MCP external backfill/import tools default off in code and the tracked template; enable `TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=true` only when manual backfills should read OKX, The Graph, FRED, Hyperliquid, Polymarket, or Coinalyze and write `md_kline`, `market_indicator_history`, or historical odds rows.
- EventRiskControl remains a protective new-entry gate by default, but state-change Telegram notifications default off; enable `EVENT_RISK_CONTROL_STATUS_NOTIFY_ENABLED=true` only when this service should emit event-risk operator alerts.
- Autonomous exploration monitoring is bean-level explicit opt-in and defaults off in code and the tracked template; enable `TRADING_EXPLORATION_MONITOR_ENABLED=true` and `TRADING_EXPLORATION_MONITOR_TELEGRAM_ENABLED=true` only after that monitor belongs to the deployed trading service.
- AI strategy discovery scheduling defaults off in code and the tracked template; enable `AI_STRATEGY_DISCOVERY_ENABLED=true` only when this service should own scheduled AI strategy generation and `bt_strategy` writes.
- Autonomous exploration loop, auto-rollout promotion, runtime-evidence writes, funding-arb scheduler, discovery AI suggestions, and MCP guardian live actions are explicit opt-in keys in the tracked template and default off; auto-rollout scheduling is bean-level explicit opt-in. Keep `TRADING_EXPLORATION_LOOP_ENABLED=false`, `TRADING_EXPLORATION_LOOP_PRODUCTION_ENABLED=false`, `TRADING_EXPLORATION_ROLLOUT_AUTO_ENABLED=false`, `TRADING_EXPLORATION_ROLLOUT_ALLOW_PRODUCTION_PROMOTION=false`, and `TRADING_EXPLORATION_ROLLOUT_ALLOW_CAP_INCREASE=false` unless the deployed trading service should advance exploration state automatically.
- Signal outcome verification and alpha promotion scheduled tracking default off in code and the tracked template; alpha promotion scheduling is bean-level explicit opt-in. Enable `SIGNAL_VERIFICATION_SCHEDULER_ENABLED=true` or `AGORA_ALPHA_TRACKER_ENABLED=true` only when this service should own outcome verification writes, accuracy reports, snapshot-file writes, and related Telegram alerts.
- Market-signal risk-card scheduling is bean-level explicit opt-in, defaults off, and dry-run in the tracked template; keep `MARKET_SIGNAL_RISK_CARD_ENABLED=false` and `MARKET_SIGNAL_RISK_CARD_DRY_RUN=true` until scheduled market-signal cards should be emitted by trading production.
- Polymarket monitoring and WAI scheduled calculation default off in code and the tracked template; WAI scheduling is bean-level explicit opt-in. Enable `POLYMARKET_MONITOR_ENABLED=true` only when this service should call Polymarket APIs, persist odds/alert rows, and send related Telegram digests, and keep `TRADING_WAI_ENABLED=false` until it should persist WAI `market_indicator_history` rows.
- Market WebSocket side effects default off in code and the tracked template; set `MARKET_LIQUIDATION_WS_ENABLED=true` only after the trading runtime is ready to connect to OKX public liquidation streams.
- K-line divergence alerting defaults off in code and the tracked template; set `TRADING_KLINE_DIVERGENCE_ENABLED=true` only after manual/snapshot divergence scans should be allowed to send Telegram alerts.
- Grid runtime scheduling, auto-rebalance scheduling, and grid orphan recovery default off in code and the tracked template; grid runtime and orphan recovery are bean-level explicit opt-in. Enable `TRADING_GRID_ENABLED=true`, `TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=true`, and `GRID_RECOVERY_ENABLED=true` only after the deployed trading service should own grid order placement and recovery.
- OCO poller and OKX private WS OCO handling default off in code and the tracked template; enable `TRADING_OCO_POLLER_ENABLED=true` only when the deployed trading service should own OCO close detection, auto retry, reconciliation writes, and related Telegram alerts.
- OKX Earn trading-buffer top-up and trailing-stop scheduling default off in code and the tracked template; enable `OKX_EARN_TOPUP_ENABLED=true` only when this service should redeem/transfer Earn funds automatically, and enable `TRAILING_STOP_ENABLED=true` only when it should write trailing state or manage OCO updates.
- ScoreBuy pre-position, confirmed-deploy, post-scout add execution, and near-trigger notifications default off and dry-run in code and the tracked template; the execution schedulers are bean-level explicit opt-in. Enable `TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=true`, `TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=true`, `TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=true`, `TRADING_SCORE_BUY_POST_SCOUT_ADD_NOTIFICATION_ENABLED=true`, and `TRADING_SCORE_BUY_POST_SCOUT_ADD_NOTIFICATION_TELEGRAM_ENABLED=true` only after those dry-run/execution and Telegram alert paths belong to trading production.
- TinyLive auto-execution scheduling is bean-level explicit opt-in, disabled, and dry-run by default in the tracked template; keep `TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false` and `TRADING_TINY_LIVE_AUTO_EXECUTION_DRY_RUN=true` until that order-capable sweep belongs to trading production.
- schema baseline compare tooling is syntax-checked but is not run automatically by preflight.
- required secret keys are present and non-empty without printing values.
- `AgoraMarketAPI/internal-client` exists for local SDK install during deploy.
- AgoraMarket exchange-rate dependency health is checked.
- nginx `/api/trading/` path split is reported.

Last verified server state from 2026-06-15 Asia/Taipei:

- AgoraMarketAPI exists at `/home/ubuntu/AgoraMarketAPI`.
- AgoraMarketAPI active port file reports `8082`.
- Local AgoraMarketAPI health is `UP`.
- `git`, `mvn`, `java`, and `curl` are installed.
- `/home/ubuntu/agora-trading-api` has been bootstrapped and can fast-forward from `origin/main`.
- `/home/ubuntu/agora-trading-api/.env.trading.secrets.example` has been created.
- `/home/ubuntu/.env.trading.secrets` has been created without printing secret values.
- `AGORA_MARKET_BASE_URL` points at the stable AgoraMarketAPI nginx vhost:
  `https://agoramarketapi.purrtechllc.com`.
- an independent trading database was created during the earlier standalone-DB
  path; the current target is shared `agora_market`.
- nginx `/api/trading/` location has been installed and reloaded.
- nginx also exposes the dedicated Trading API host
  `https://agoratradingapi.purrtechllc.com/api`, where public non-MCP `/api/*`
  maps to the standalone service's internal `/api/trading/*` paths. Trading MCP
  is internal-only: public dedicated-host `/api/mcp` and shared-host
  `/api/trading/mcp` must be blocked by nginx.
- production was deployed from `origin/main` commit `31af005`; the active
  blue-green port is `8085` and is recorded in `app.port`.
- current server verification requires worktree, `origin/main`, and deployed
  `app.commit` to match unless the delta is explicitly docs/tooling-only and
  accepted by the verifier.
- `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh` passed in
  shared mode before schema hardening with 39 source entity tables, 0 missing
  trading tables, 175 database tables, and 136 extra marketplace/shared tables
  expected in shared DB mode.
- hardened schema deployment passed with
  `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`,
  `SPRING_FLYWAY_ENABLED=true`,
  `SPRING_FLYWAY_TABLE=trading_flyway_schema_history`,
  `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`, and
  `SPRING_FLYWAY_BASELINE_VERSION=1`.
- `trading_flyway_schema_history` exists in the shared `agora_market` database
  with Flyway baseline version `1`; this keeps Trading schema history separate
  from AgoraMarketAPI's `flyway_schema_history`.
- post-hardening `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh`
  passed in shared mode with 39 source entity tables, 0 missing trading tables,
  176 database tables, 2 known system tables, and 137 extra marketplace/shared
  tables expected in shared DB mode.
- 2026-06-14 post-alias verification reran
  `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh` successfully.
  The server worktree matched `origin/main` at `6a656fe`, deployed
  `app.commit` matched `HEAD`, and shared-mode compare found 39 source entity
  tables, 0 implicit entity names, 0 forbidden marketplace source mappings,
  176 database tables, 2 known system tables, 0 missing trading tables, and
  137 extra marketplace/shared tables expected in shared DB mode.
- 2026-06-14 maintenance verification reran
  `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh` after the
  docs-only handoff refresh. The server worktree matched `origin/main` at
  `1585942`, deployed `app.commit` remained `6a656fe`, the delta was
  docs/tooling-only, and shared-mode compare still found 39 source entity
  tables, 0 implicit entity names, 0 forbidden marketplace source mappings,
  176 database tables, 2 known system tables, 0 missing trading tables, and
  137 extra marketplace/shared tables expected in shared DB mode.
- 2026-06-14 historical pre-internal-only production MCP parity passed against
  `/api/mcp` with 21 representative trading tools present from 304
  registered tools, plus direct production smoke for the read-only
  `listSchedulerTasks` compatibility alias. This public route is now superseded
  by the MCP internal-only policy; current parity smoke must use server-local
  MCP.
- 2026-06-15 `scripts/verify_server.sh` passed after the `31af005` deploy with:
  - local trading health on the active `app.port`
  - local MCP `getMcpRegistryVersion` through `/api/mcp`
  - AgoraMarket exchange-rate dependency health: `https://agoramarketapi.purrtechllc.com/api/actuator/health`
  - public trading health: `https://agoratradingapi.purrtechllc.com/api/actuator/health`
  - historical public Trading MCP tools/list:
    `https://agoratradingapi.purrtechllc.com/api/mcp` with 304 tools,
    required Trading tools present, and marketplace `updateCartItem` absent;
    this public route is now superseded by the MCP internal-only policy
- the same deploy confirmed `ERROR=0` in the active `8085` trading run log;
  startup-only warnings remain classified by the warning baseline below.
- dedicated-host blue-green routing was fixed in commit `1cb9e60`: deploy and
  `scripts/install_nginx_path.sh` now update both shared-host `/api/trading/`
  and dedicated-host `/api/*` upstreams to the active `app.port`. This prevents
  the dedicated host from pointing at a drained old port after blue-green
  switch.
- hardened startup logs showed Flyway creating and baselining the Trading-owned
  history table. No Hibernate `alter table` attempt or schema-validation error
  was observed in the post-hardening log sample. Flyway may warn that MySQL 9.7
  is newer than the Flyway version it was tested against; this warning did not
  block startup.

Historical note: 2026-06-05 first observed deployment snapshot used commit
`11612b9` on active port `8084`; it is no longer the current deployment.

Deploy after secrets and nginx path are ready:

```bash
cd /home/ubuntu/agora-trading-api
bash scripts/install_nginx_path.sh
bash deploy.sh
```

Defaults:

- ports: `8084` and `8085`
- health: `http://127.0.0.1:<port>/api/actuator/health`
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
`https://agoratradingapi.purrtechllc.com/api/actuator/health`, and verifies
public Trading MCP is blocked through `DEFAULT_PUBLIC_TRADING_MCP_BLOCKED_URL`
and `DEFAULT_PUBLIC_TRADING_CONTEXT_MCP_BLOCKED_URL`, defaulting to
`https://agoratradingapi.purrtechllc.com/api/mcp` and
`https://agoramarketapi.purrtechllc.com/api/trading/mcp`. Set
`RUN_POST_DEPLOY_VERIFY=0` only for deliberate emergency bypasses. When it is
used, deploy keeps the previous blue-green instance and nginx backup because the
new instance has not been proven by server verification.

Blue-green deploy keeps the previous instance alive until pre-drain
post-deploy verification passes. During that first verifier run,
`ALLOW_INACTIVE_PORT_LISTENER=1` allows the old blue-green port to remain
listening while the new metadata, health, MCP path, public MCP blocks, nginx,
and dependency checks are proven. Deploy then logs
`draining old instance after verification`, stops the old port, and runs strict
post-drain verification with the inactive-port listener guard restored. If
verification fails, the old process is still available and deploy logs
`post-deploy verification failed; rolling back active metadata`.
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

If blue-green is used, deploy updates nginx to the active `app.port`, matching
the existing AgoraMarketAPI style. The port swap must cover both the shared
host `/api/trading/` upstream and the dedicated Trading host `/api/*` upstreams.

## Post-Deploy Smoke

```bash
PORT=$(cat /home/ubuntu/agora-trading-api/app.port)
curl -fsS "http://127.0.0.1:${PORT}/api/actuator/health"
curl -fsS "https://agoramarketapi.purrtechllc.com/api/actuator/health"
```

Or run the server verifier:

```bash
cd /home/ubuntu/agora-trading-api
bash scripts/verify_server.sh
```

From Windows/Codex Desktop, run the server-side verifier over SSH so tool
checks such as `lsof`, `systemctl`, and nginx inspection run on the production
host:

```powershell
.\scripts\verify_server_ssh.ps1 -SchemaCompare
```

For deploys from Windows/Codex Desktop, prefer the SSH wrapper so the deploy
continues on the server with a durable `logs/deploy/deploy-*.log` and
`logs/deploy/deploy-*.exit` marker even if the local SSH session is interrupted:

```powershell
.\scripts\deploy_ssh.ps1
```

The server verifier rejects a listener on the non-active blue-green port. This
guards against a failed or interrupted deploy leaving a second scheduler-capable
Trading process alive after the active metadata has settled.

For the full read-only split acceptance pass, run the Trading server verifier
with shared-DB schema compare, active runtime log smoke, and then
AgoraMarketAPI's live MCP ownership smoke:

```powershell
.\scripts\verify_split_acceptance_ssh.ps1
```

For the current open-issue acceptance handoff after an explicitly authorized
deploy, run the read-only wrapper:

```powershell
.\scripts\verify_post_deploy_issue_acceptance_ssh.ps1 -RequireTrailingAcceptance
```

This wrapper runs split acceptance, the reusable server-local MCP parity smoke,
the focused #1/#2 guardrail MCP smoke, the read-only signal-correctness MCP
smoke, and the #3 trailing-stop PnL replay smoke through server-local MCP/API
checks. If `-EnvFile` is overridden, the wrapper passes the same remote env file
through server verification, split acceptance, and every server-local MCP smoke
so the issue-closure run verifies one consistent runtime configuration. MCP
parity output must include `required_tools=[...]` plus
`missing_required_tools=[]`; missing either list or non-empty required-tool
evidence is not live-readiness proof. Use
`-RequireTrailingAcceptance` when closing issue #3; without it, trailing replay
can still prove deployed reachability but not the 30d PnL acceptance target.
Reachability-only runs must end with `REACHABILITY_ONLY OK`, not the normal
issue-acceptance OK; do not use that output as #1/#2/#3 closure evidence.
The wrapper runs the guardrail smoke in no-review-gaps mode, so
`Operator action: REVIEW_POLICY_GAPS` fails #1/#2 issue acceptance.
`-SkipSplitAcceptance` is diagnostic-only; output collected with that flag is
not #1/#2/#3 closure evidence, and it cannot be combined with `-RequireTrailingAcceptance`.
A diagnostic-only run must end with `DIAGNOSTIC_ONLY OK`, not the normal
issue-acceptance OK.
Only the full closure run may end with `CLOSURE_READY OK`, which means split
acceptance, no-review-gaps guardrail smoke, signal-correctness smoke, and hard
trailing replay acceptance all passed.

For a read-only production signal-correctness check, run:

```powershell
.\scripts\smoke_signal_correctness_ssh.ps1
```

After a separately authorized evidence-only review path has resolved signal
policy gaps, run the hard gate:

```powershell
.\scripts\smoke_signal_correctness_ssh.ps1 -RequireClear
```

Expected:

- `verifyStrategyExecution` reports no missed evaluation/order bug; the smoke
  must fail if that no-missed-evaluation/no-missed-order marker is missing.
- `analyzeBlockedSignalOutcomes` prints blocked-signal outcome counts and
  false-kill rate for recent governance review.
- `getSignalAccuracyReport` prints PASS/BLOCK finalized sample counts; treat
  finalized samples below 30 as internal-only evidence.
- `diagnoseDataFreshnessGuardBlocks` prints current `staleNowKeys`,
  `noDataNowKeys`, and `queryFailedNowKeys`; keep DataFreshnessGuard strict
  unless the current snapshot is clean and relaxation is backed by separate
  blocker-level evidence.
- `getEntryDedupGovernanceDashboard` and `getMissedOpportunityRegressionReport`
  separate governance false-block statistics from staged-add live-readiness.
  Do not treat high EntryDedup/DataFreshness false-block rates as permission to
  relax live execution: EntryDedup relaxation remains not live-ready when
  `wouldAllowStagedAddGroups=0` or `dedupTooCoarseSuspects=0`.
- Governance drift output is live-review evidence only. A `TOO_STRICT` result
  or other drift finding must be paired with missed-opportunity evidence and an
  explicit shadow/tiny-live review plan before any policy relaxation is
  discussed.
- `missing_signal_policy_fields=[]` is required. Missing governance,
  missed-opportunity, freshness, or EntryDedup review fields remain a live
  blocker even if later free-text output looks clean.
- With `-RequireClear`, the smoke exits 0 only when there are no
  `REVIEW_POLICY_GAPS`, `missing_signal_policy_fields=[]`, 7d governance drift
  is not `TOO_STRICT`, `TOO_LOOSE`, or `INSUFFICIENT_DATA`, and
  missed-opportunity `overallStatus=PASS`; otherwise it prints the review
  details and exits non-zero.
- `dataFreshnessCurrentStatus=NO_CURRENT_SAMPLE` means the read-only RCA did
  not observe a current DataFreshnessGuard sample. Treat it as missing
  clearance evidence, not as a source outage and not as permission to relax
  DataFreshnessGuard.
- The smoke also prints `signal_policy_review_plan`, a machine-readable
  review packet with `riskCategory`, `evidenceMarkers`, `requiredEvidence`,
  `nextAction`, and `notAuthorization` for each blocked/review gate. Use it to
  draft the next read-only evidence task only; it is not live approval and is
  not permission to relax signal policy.
- The full bundle also requires `signalPolicyClear=true` and
  `signal_policy_review_plan` to be present without `state=BLOCKED` or
  `state=REVIEW` entries when signal policy is otherwise clear.
- The smoke prints no-buy row classifications, top blocker families, row-level
  next actions, high-return no-buy strategy distribution, and EntryDedup group
  blocker families so operators can decide whether the next safe step is data
  freshness repair, runtime-evidence coverage, threshold observation, or an
  explicitly approved tiny-live/shadow experiment.
- The smoke calls `getNoBuyReasonTruthTable` and prints a
  `No-Buy Reason Truth Table` summary so policy review can compare the current
  no-buy explanations against the missed-opportunity and EntryDedup evidence.
- The script only calls read-only MCP tools and must not change
  order/OCO/strategy/grid/fund/Earn state.

For a read-only entry/filter operator review packet, run:

```powershell
.\scripts\prepare_entry_filter_operator_review_packet_ssh.ps1 -RequireReview
```

Expected:

- The packet runs `smoke_signal_correctness_ssh.ps1` and emits
  `entry_filter_operator_review_packet`.
- `entry_filter_operator_packet_status=REVIEW_REQUIRED_NOT_POLICY_CHANGE`
  means governance drift, missed-opportunity, or no-buy row evidence is
  reviewable but still not policy approval.
- The packet carries `signalPolicyClear`, `governanceMode`,
  `missedOpportunityStatus`, no-buy classifications, EntryDedup staged-add
  evidence, and `signal_policy_review_plan`.
- The packet is read-only. It does not deploy, restart, reload nginx, change
  production env, enable live trading, relax EntryDedup/DataFreshness/live
  policy, place orders, modify OCO, close positions, mutate
  DB/grid/fund/Earn/Telegram/exchange state, run external backfill/import, or
  authorize strategy/filter changes.

For a read-only no-buy row review packet, run:

```powershell
.\scripts\prepare_no_buy_row_review_packet_ssh.ps1 -RequireReview
```

Expected:

- The packet runs `smoke_signal_correctness_ssh.ps1` and emits
  `no_buy_row_review_packet`.
- `rowActionFamilyCounts` groups rows into review lanes such as
  `WAIT_FOR_SIGNAL_CONFIRMATION`, `MISSED_OPPORTUNITY_REVIEW`,
  `BUDGET_CAPACITY_REVIEW`, and `KEEP_HARD_SAFETY`.
- `no_buy_row_review_packet_status=REVIEW_REQUIRED_NOT_EXPERIMENT` means
  row-level evidence is reviewable but blocked by governance,
  missed-opportunity, or signal-policy evidence.
- `no_buy_row_review_packet_status=READY_FOR_SHADOW_DESIGN_NOT_LIVE` means the
  rows can support a bounded shadow design only; it is not live approval.
- The packet is read-only. It does not deploy, restart, reload nginx, change
  production env, enable live trading, relax EntryDedup/DataFreshness/live
  policy, place orders, modify OCO, close positions, mutate
  DB/grid/fund/Earn/Telegram/exchange state, run external backfill/import, or
  authorize strategy/filter changes.

For a read-only missed-opportunity shadow design packet preflight, run:

```powershell
.\scripts\prepare_missed_opportunity_shadow_design_packet_ssh.ps1 -RequireReview
```

Expected:

- The packet runs `prepare_no_buy_row_review_packet_ssh.ps1` and emits
  `missed_opportunity_shadow_design_packet`.
- It extracts only `MISSED_OPPORTUNITY_REVIEW` rows into
  `candidateMissedOpportunityRows` and keeps `WAIT_FOR_SIGNAL_CONFIRMATION`
  rows as observation-only evidence.
- Output includes `shadow_design_review_allowed`,
  `tiny_live_order_allowed=false`, and `live_policy_change_allowed=false`.
- `missed_opportunity_shadow_design_packet_status=BLOCKED_SIGNAL_POLICY_REVIEW_REQUIRED`
  means the candidate row is reviewable but still blocked by governance drift,
  missed-opportunity regression, or signal-policy evidence.
- `missed_opportunity_shadow_design_packet_status=READY_FOR_MISSED_OPPORTUNITY_SHADOW_DESIGN_NOT_LIVE`
  means a separate shadow-only design can be drafted. It is not permission to
  execute tiny-live/live orders or relax EntryDedup/DataFreshness/live policy.
- The packet is read-only. It does not deploy, restart, reload nginx, change
  production env, enable live trading, execute tiny-live orders, relax
  EntryDedup/DataFreshness/live policy, place orders, modify OCO, close
  positions, mutate DB/grid/fund/Earn/Telegram/exchange state, run external
  backfill/import, or authorize strategy/filter changes.

For a read-only governance relaxation review packet, run:

```powershell
.\scripts\prepare_governance_relaxation_review_packet_ssh.ps1 -RequireReview
```

Expected:

- The packet runs `smoke_signal_correctness_ssh.ps1`, including the
  `findGovernanceRelaxationCandidates` evidence, and emits
  `governance_relaxation_review_packet`.
- Output includes `relaxationCandidateCount`,
  `shadow_governance_review_allowed`, `tiny_live_order_allowed=false`, and
  `live_policy_change_allowed=false`.
- `governance_relaxation_review_packet_status=REVIEW_REQUIRED_NOT_POLICY_CHANGE`
  means candidates are reviewable but blocked by signal-policy,
  governance-drift, missed-opportunity, or no-buy evidence.
- `governance_relaxation_review_packet_status=READY_FOR_GOVERNANCE_SHADOW_REVIEW_NOT_LIVE`
  means a separate shadow-only governance review can be drafted. It is not
  permission to relax EntryDedup/DataFreshness/live policy or execute orders.
- The packet is read-only. It does not deploy, restart, reload nginx, change
  production env, enable live trading, execute tiny-live orders, relax
  EntryDedup/DataFreshness/live policy, place orders, modify OCO, close
  positions, mutate DB/grid/fund/Earn/Telegram/exchange state, run external
  backfill/import, or authorize strategy/filter changes.

For the focused strategy 574 TinyLive near-BUY / governance RCA, run:

```powershell
.\scripts\smoke_strategy574_signal_governance_ssh.ps1
```

Expected:

- The script calls server-local `/api/mcp`, not public Trading MCP.
- Output compares 1d/3d/7d/14d `governanceMode` values so operators can
  distinguish short-window `INSUFFICIENT_DATA` from 7d/14d `TOO_STRICT`.
- Output prints `strategy574RowCount`, strategy 574 no-buy classifications,
  current DataFreshness fields, TinyLive trigger status, and autonomous
  readiness evidence.
- Output must include `policy_change_recommendation`; values such as
  `DO_NOT_RELAX_ENTRY_DEDUP_OR_DATAFRESHNESS_LIVE` or
  `KEEP_HARD_GATES_AND_OBSERVE_TINY_LIVE_THRESHOLD_CROSS` are read-only
  routing guidance, not live approval.
- The script must not change production env, DB, order, OCO, grid, fund, Earn,
  Telegram, scheduler, exchange, or external backfill/import state.

For the read-only strategy 574 signal review gate, run:

```powershell
.\scripts\prepare_strategy574_signal_review_gate_ssh.ps1
```

Expected:

- The gate invokes `smoke_live_origin_delta_local.ps1` and
  `smoke_strategy574_signal_governance_ssh.ps1` only.
- Output includes `deploy_required_before_strategy574_review`,
  `shadow_observation_review_allowed`, `tiny_live_order_allowed=false`,
  `live_policy_change_allowed=false`, `strategy574_review_missing_requirements`,
  and `strategy574_signal_review_gate_status`.
- `READY_FOR_OBSERVATION_REVIEW_NOT_ORDER` only means continued read-only
  observation is routed; it is not permission to pre-buy, execute TinyLive,
  relax EntryDedup/DataFreshness, deploy, restart, or change live policy.
- `BLOCKED_DEPLOY_CURRENT_RUNTIME` means deploy and server verification are
  required before the strategy 574 gate can be trusted.

For the focused strategy 485 open-position risk RCA, run:

```powershell
.\scripts\smoke_strategy485_position_risk_ssh.ps1
```

Expected:

- The script calls server-local `/api/mcp`, not public Trading MCP.
- Output prints open strategy 485 position ids, OCO health, active-position EV,
  TP stretch/aging counts, recent closed trades, stop-sweep policy, and monthly
  PnL evidence.
- Output must include `strategy485_position_risk_recommendation`. A value such
  as `REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY` means operator review is
  needed, not that the script is allowed to close positions or modify OCO.
- Output must include `strategy485_position_review_decision`, a
  machine-readable routing object with OCO health, open/negative-EV position
  counts, close/modify suggestion counts, timeout/TP-stretch counts,
  per-position EV summaries, required evidence, next action, and
  non-authorization text.
- Use `docs/strategy485-aged-position-review-plan.md` before drafting any
  operator packet for aged negative-EV strategy 485 positions. The packet must
  include fresh OCO, active-position EV, TP stretch, stop-sweep, timeout,
  recent-closed, and monthly PnL evidence, and actual close/OCO-modification
  actions require separate explicit authorization.
- The script must not change production env, DB, order, OCO, grid, fund, Earn,
  Telegram, scheduler, exchange, or external backfill/import state.

For the read-only strategy 485 position review gate, run:

```powershell
.\scripts\prepare_strategy485_position_review_gate_ssh.ps1
```

Expected:

- The gate invokes `smoke_live_origin_delta_local.ps1` and
  `smoke_strategy485_position_risk_ssh.ps1` only.
- Output includes `deploy_required_before_strategy485_review`,
  `operator_review_packet_allowed`, `position_or_oco_mutation_allowed=false`,
  `strategy485_position_review_decision`,
  `strategy485_review_missing_requirements`, and
  `strategy485_position_review_gate_status`.
- `READY_FOR_OPERATOR_REVIEW_NOT_MUTATION` means a separate operator packet can
  be drafted with fresh evidence. It is not permission to close positions,
  modify OCO, deploy, restart, or change live policy.
- `BLOCKED_DEPLOY_CURRENT_RUNTIME` means current origin/main must be deployed
  and verified before the strategy 485 packet can be trusted.

For the read-only strategy 485 operator review packet preflight, run after the
gate is expected to be ready:

```powershell
.\scripts\prepare_strategy485_operator_review_packet_ssh.ps1 -RequireReady
```

Expected:

- The preflight invokes `prepare_strategy485_position_review_gate_ssh.ps1`
  only.
- Output includes `strategy485_operator_review_packet`,
  `strategy485_operator_packet_status`,
  `strategy485_operator_packet_missing_requirements`,
  `strategy485_position_review_decision`, and
  `position_or_oco_mutation_allowed=false`.
- `READY_FOR_OPERATOR_PACKET_NOT_MUTATION` means the emitted packet can be
  attached to a separate operator review. It is still not permission to close
  positions, modify OCO, deploy, restart, change production env, or relax live
  policy.
- `BLOCKED_DEPLOY_CURRENT_RUNTIME` means current origin/main must be deployed
  and verified before the packet can be trusted.

For the read-only auto-trading review bundle, run:

```powershell
.\scripts\smoke_auto_trading_review_bundle_ssh.ps1
```

Expected:

- The wrapper invokes only existing read-only SSH/local smokes: origin-delta,
  live-authorized audit, strategy 485 position risk, strategy 574
  signal/governance, and TinyLive post-trade evidence.
- Output includes `origin_delta_status`, `live_authorized_audit_verdict`,
  `strategy485_position_risk_recommendation`,
  `strategy485_position_review_decision`,
  `strategy574_policy_change_recommendation`, `tiny_live_post_trade_status`,
  `review_items`, and `auto_trading_review_recommendation`.
- The bundle summary also prints strategy 485 negative-EV, close/modify, and
  timeout counts parsed from `strategy485_position_review_decision`.
- A recommendation such as `OPERATOR_REVIEW_STRATEGY485_POSITION_RISK` is
  review routing only; it is not permission to close positions, modify OCO,
  deploy, restart, or relax live policy.
- The wrapper must not change production env, DB, order, OCO, grid, fund, Earn,
  Telegram, scheduler, exchange, external backfill/import, deploy, restart, or
  nginx state.

For the read-only auto-trading review gate, run:

```powershell
.\scripts\prepare_auto_trading_review_gate_ssh.ps1
```

Expected:

- The gate invokes `smoke_auto_trading_review_bundle_ssh.ps1` only.
- Output includes `deploy_required_before_auto_trading_review`,
  `operator_review_packet_allowed`, `position_or_oco_mutation_allowed=false`,
  `tiny_live_order_allowed=false`, `live_policy_change_allowed=false`,
  `auto_trading_review_missing_requirements`, and
  `auto_trading_review_gate_status`.
- `READY_FOR_OPERATOR_POSITION_REVIEW_NOT_MUTATION` means a separate read-only
  operator packet can be drafted. It is not permission to close positions,
  modify OCO, pre-buy, execute TinyLive, deploy, restart, or change live
  policy.
- `BLOCKED_DEPLOY_CURRENT_RUNTIME` means current origin/main must be deployed
  and verified before the auto-trading review packet can be trusted.

For a read-only profit-candidate review, run:

```powershell
.\scripts\smoke_profit_candidate_review_ssh.ps1
```

Expected:

- The smoke calls only server-local `/api/mcp` read-only evidence tools:
  monthly PnL, enabled strategy scorecard, ExpectedValueGate stats, signal
  accuracy, blocked-signal outcomes, missed-opportunity regression, no-buy
  truth table, shadow readiness, shadow activation candidates, and
  trailing-stop PnL replay.
- Output includes `profit_candidate_items` and
  `profit_candidate_review_recommendation`.
- This direct smoke does not run origin-delta/currentness checks. Use
  `smoke_profit_improvement_review_bundle_ssh.ps1` or
  `prepare_profit_experiment_gate_ssh.ps1` before treating the evidence as
  current-post-deploy profit review input.
- A recommendation such as
  `REVIEW_DATAFRESHNESS_FALSE_KILL_WITH_SHADOW_REPLAY` is review routing only;
  it is not permission to relax DataFreshness, enable live trading, activate
  strategies, close positions, modify OCO, deploy, or restart.
- The smoke must not change production env, DB, order, OCO, grid, fund, Earn,
  Telegram, scheduler, exchange, external backfill/import, deploy, restart, or
  nginx state.

For the read-only profit loss review gate, run:

```powershell
.\scripts\prepare_profit_loss_review_gate_ssh.ps1
```

Expected:

- The gate invokes only `smoke_live_origin_delta_local.ps1` and
  `smoke_profit_candidate_review_ssh.ps1`.
- Output includes `monthlyPnlTotalUsdt`, `profit_loss_candidate_items`,
  `deploy_required_before_profit_loss_review`, `loss_source_review_allowed`,
  `live_policy_change_allowed=false`, `position_or_oco_mutation_allowed=false`,
  `tiny_live_order_allowed=false`, `profit_loss_review_missing_requirements`,
  and `profit_loss_review_gate_status`.
- `READY_FOR_LOSS_SOURCE_REVIEW_NOT_LIVE` means a separate read-only loss-source
  packet can be drafted. It is not permission to relax DataFreshness, close
  positions, modify OCO, pre-buy, execute TinyLive, deploy, restart, or change
  live policy.
- `BLOCKED_DEPLOY_CURRENT_RUNTIME` means current origin/main must be deployed
  and verified before the loss-source packet can be trusted.

For the read-only post-deploy profit validation bundle, run after a separately
authorized deploy and verification:

```powershell
.\scripts\smoke_post_deploy_profit_validation_ssh.ps1
```

Expected:

- `deploy_required_before_post_deploy_profit_validation=false` is required
  before any profit packet can be trusted.
- `server_worktree_commit`, `origin_main_commit`, `origin_runtime_delta_files`,
  `origin_runtime_delta_paths`, and `origin_runtime_delta_impact` identify
  runtime drift when `origin_delta_status=RUNTIME_DRIFT`, so the deploy-first
  blocker points at concrete files and blocked evidence categories instead of
  only a status.
- `post_deploy_profit_validation_status` summarizes the auto-trading review
  gate, profit loss review gate, and profit experiment gate into one matrix.
- `post_deploy_profit_validation_missing_requirements` lists replay, OCO, EV,
  DataFreshness, or runtime evidence still missing.
- `data_freshness_counterfactual_gate_missing_requirements` carries the exact
  replayable candidate row and counterfactual field blockers inherited from the
  profit experiment gate.
- `strategy485_position_review_decision` carries the read-only aged
  negative-EV position routing decision inherited from the profit experiment
  gate; it is evidence for operator review only and does not authorize
  position or OCO mutation.
- `post_deploy_profit_validation_review_plan` gives one machine-readable entry
  per child gate with `riskCategory`, `requiredEvidence`, `nextAction`, and
  `notAuthorization` so the next profit-review step is explicit.
- `post_deploy_profit_validation_blocker_summary` gives a machine-readable
  blocked-child-gate summary with `requiredEvidenceCount`, `requiredEvidence`,
  `nextAction`, `runtimeDrift`, and no-live authorization text. It routes
  review work but does not clear blockers or authorize live changes.
- `post_deploy_profit_validation_review_decision` is the top-level routing
  object. It includes `canPrepareReviewPacket`, `deployRequired`,
  `allowedReviewTypes`, `blockerCount`, `blockedGateCount`, `blockedGates`,
  `missingRequirementCount`, `runtimeDrift`, and no-live authorization text.
- DataFreshness false-kill profit review remains blocked if
  `complete DataFreshness replayable candidate rows` or any
  `DataFreshness counterfactual field:*` requirement is still present.
- `live_policy_change_allowed=false`, `position_or_oco_mutation_allowed=false`,
  and `tiny_live_order_allowed=false` remain hard non-authorization markers.
- The bundle is read-only. It does not deploy, restart, change production env,
  relax policy, place orders, modify OCO/grid/fund/Earn state, send Telegram,
  touch DB state, run external backfill/import, or change nginx.

For a focused read-only profit runtime deploy review packet, run before asking
for a deploy decision:

```powershell
.\scripts\prepare_profit_runtime_deploy_review_packet_ssh.ps1 -RequireReady
```

Expected:

- `profit_runtime_deploy_review_packet` combines origin-currentness evidence
  with the post-deploy profit validation blockers.
- `profit_runtime_deploy_packet_status=READY_FOR_DEPLOY_REVIEW_NOT_DEPLOYED`
  means runtime drift and profit blockers are proven enough to ask for a
  separate deploy review, but the script still does not deploy.
- `origin_delta_status`, `origin_runtime_delta_paths`, and
  `origin_runtime_delta_impact` identify the runtime files that must be
  deployed before profit evidence can be trusted.
- The packet names the required post-deploy commands:
  `prepare_profit_shadow_experiment_packet_ssh.ps1 -RequireReady` and
  `prepare_strategy485_operator_review_packet_ssh.ps1 -RequireReady`.
- The packet is read-only. It does not deploy, restart, reload nginx, change
  production env, enable live trading, place orders, modify OCO/grid/fund/Earn
  state, send Telegram, mutate DB state, touch exchange state, or run external
  backfill/import.

For a focused read-only profit blocker ledger, run:

```powershell
.\scripts\prepare_profit_blocker_ledger_ssh.ps1 -RequireActionable
```

Expected:

- `profit_blocker_ledger_packet` merges the runtime deploy packet, profit
  shadow experiment packet, strategy 485 operator packet, and DataFreshness
  replay observation bundle.
- `profit_blocker_ledger_items` is the prioritized blocker list with source,
  status, next action, and required read-only commands.
- `profit_blocker_ledger_status=BLOCKED_DEPLOY_CURRENT_RUNTIME` means runtime
  currentness remains the first blocker before profit evidence can be trusted.
- The ledger is read-only. It does not deploy, restart, reload nginx, change
  production env, enable live trading, relax policy, place orders, modify OCO,
  close positions, mutate DB/grid/fund/Earn/Telegram/exchange state, run
  external backfill/import, or authorize strategy/DataFreshness policy changes.

For a read-only profit readiness brief, run:

```powershell
.\scripts\prepare_profit_readiness_brief_ssh.ps1 -RequireBrief
```

Expected:

- `profit_readiness_brief_packet` merges signal correctness and
  missed-opportunity evidence, trailing-stop PnL replay, and the profit blocker
  ledger.
- `entry_filter_lane_status` shows whether governance drift and missed
  opportunity rows still block entry/filter experiments.
- `exit_lane_status` and `trailing_stop_acceptance` show whether exit-side
  trailing/TP-stop evidence is review-ready.
- `data_freshness_current_status` distinguishes `CLEAN`,
  `NO_CURRENT_SAMPLE`, and stale/query issue states before the brief summarizes
  entry/filter readiness.
- Long child smokes print `child_start`, periodic `child_heartbeat`, and
  `child_complete` markers. `-ChildTimeoutSeconds` bounds a stuck local child
  wrapper and reports `timedOut=true` without changing production state.
- The brief is read-only. It does not deploy, restart, reload nginx, change
  production env, enable live trading, relax EntryDedup/DataFreshness/live
  policy, place orders, modify OCO, close positions, mutate
  DB/grid/fund/Earn/Telegram/exchange state, run external backfill/import, or
  authorize strategy changes.

For a bounded read-only profit evidence watch, run:

```powershell
.\scripts\watch_profit_evidence_readiness_ssh.ps1 -MaxAttempts 3 -SleepSeconds 300
```

Expected:

- The watcher calls `prepare_profit_readiness_brief_ssh.ps1` and
  `smoke_data_freshness_replay_observation_bundle_ssh.ps1` only.
- Output includes `profit_evidence_watch_status`,
  `attempt_data_freshness_current_status`,
  `attempt_replay_candidate_id_recommendation`, and
  `attempt_replay_observation_bundle_recommendation`.
- `PENDING_DATAFRESHNESS_CURRENT_SAMPLE` means no current DataFreshness sample
  is available yet; rerun later instead of treating the absence as source
  health clearance or policy approval.
- `EVIDENCE_READY_FOR_REVIEW_NOT_LIVE` means the collected read-only evidence
  can be reviewed separately; it is not permission to enable live trading or
  relax EntryDedup/DataFreshness/live policy.
- `-RequireEvidenceReady` fails unless the watcher reaches
  `EVIDENCE_READY_FOR_REVIEW_NOT_LIVE`.
- Long child smokes emit `child_start`, periodic `child_heartbeat`, and
  `child_complete` markers from the watcher itself.
- The watcher is read-only. It does not deploy, restart, reload nginx, change
  production env, enable live trading, relax EntryDedup/DataFreshness/live
  policy, place orders, modify OCO, close positions, mutate
  DB/grid/fund/Earn/Telegram/exchange state, run external backfill/import, or
  authorize strategy changes.

For a read-only DataFreshness profit blocker brief, run:

```powershell
.\scripts\prepare_data_freshness_profit_blocker_brief_ssh.ps1
```

Expected:

- The brief calls `smoke_signal_correctness_ssh.ps1` and
  `smoke_data_freshness_replay_observation_bundle_ssh.ps1` only.
- It emits `data_freshness_profit_blocker_brief_packet`,
  `data_freshness_profit_blockers`, and
  `data_freshness_profit_blocker_status`.
- It also emits DataFreshness sample recency from the replay bundle: latest row
  time, row age, 1d/3d/7d/14d/30d row counts, and
  `data_freshness_sample_gap_status`, so a pending current sample can be
  separated from a recent-window gap with older historical samples.
- `PENDING_DATAFRESHNESS_CURRENT_SAMPLE` means the current-source RCA still has
  no current DataFreshness sample; rerun after a new sample is expected.
- `READY_FOR_DATAFRESHNESS_REPLAY_REVIEW_NOT_LIVE` means the current sample,
  replay candidate id, and counterfactual replay evidence can be reviewed
  separately; it is not approval to relax DataFreshnessGuard or live policy.
- The brief does not deploy, restart, reload nginx, change production env,
  enable live trading, relax EntryDedup/DataFreshness/live policy, place orders,
  modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange
  state, run external backfill/import, or authorize strategy changes.

For a read-only profit operator review matrix, run:

```powershell
.\scripts\prepare_profit_operator_review_matrix_ssh.ps1 -RequireReviewItems
```

Expected:

- The matrix calls `prepare_profit_readiness_brief_ssh.ps1`,
  `watch_profit_evidence_readiness_ssh.ps1`, and
  `prepare_exit_side_profit_review_packet_ssh.ps1` in read-only mode.
- It emits `profit_operator_review_items`,
  `profit_operator_review_matrix_packet`, and
  `profit_operator_review_matrix_status`.
- `HAS_REVIEW_READY_ITEMS_NOT_LIVE` means at least one lane, normally
  `exit-side`, is ready for a separate operator review while blocked lanes such
  as `entry-filter` or `data-freshness-replay` stay visible.
- `REVIEW_SIGNAL_POLICY` is still pending entry-filter evidence, not an
  operator-ready lane; entry-filter readiness requires the readiness brief to
  report `CLEAR`.
- The matrix does not deploy, restart, reload nginx, change production env,
  enable live trading, relax EntryDedup/DataFreshness/live policy, enable the
  trailing scheduler, place orders, modify OCO, close positions, mutate
  DB/grid/fund/Earn/Telegram/exchange state, run external backfill/import, or
  authorize strategy changes.

For a read-only profit operator action brief, run:

```powershell
.\scripts\prepare_profit_operator_action_brief_ssh.ps1 -RequireReady
```

Expected:

- The brief calls `prepare_profit_operator_review_matrix_ssh.ps1` only and
  emits `profit_operator_action_items`,
  `profit_operator_action_brief_packet`, and
  `profit_operator_action_brief_status`.
- `READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE` means the exit-side lane can be
  reviewed separately with
  `REVIEW_EXIT_SIDE_TRAILING_AND_STRATEGY485_NOT_MUTATION`, while entry-filter
  and DataFreshness replay blockers stay visible.
- The brief does not deploy, restart, reload nginx, change production env,
  enable live trading, relax EntryDedup/DataFreshness/live policy, enable the
  trailing scheduler, place orders, modify OCO, close positions, mutate
  DB/grid/fund/Earn/Telegram/exchange state, run external backfill/import, or
  authorize strategy changes.

For a focused read-only DataFreshness false-kill review, run:

```powershell
.\scripts\smoke_data_freshness_false_kill_review_ssh.ps1
```

Expected:

- The smoke proves DataFreshnessGuard RCA through short, review, and longer
  windows, then separates current source snapshot health from historical stale
  kline rows.
- Output includes `currentDataFreshnessClean`, `historicalStaleOnly`,
  `dataFreshnessFalseKillPct`, `data_freshness_shadow_replay_plan`, and
  `data_freshness_false_kill_recommendation`.
- A recommendation such as
  `REVIEW_COLLECTOR_CADENCE_SHADOW_REPLAY_KEEP_HARD_GATE` means review
  collector cadence and shadow replay while keeping the hard gate intact; it is
  not permission to relax DataFreshnessGuard, enable live trading, activate
  strategies, modify OCO, deploy, restart, or change production env.
- The smoke must not change production env, DB, order, OCO, grid, fund, Earn,
  Telegram, scheduler, exchange, external backfill/import, deploy, restart, or
  nginx state.

For a focused read-only DataFreshness executability review, run:

```powershell
.\scripts\smoke_data_freshness_executability_review_ssh.ps1
```

Expected:

- The smoke checks the historical DataFreshness decision window, current
  autonomous readiness, runtime-evidence rows, EV sample coverage, OCO plan
  coverage, shadow-intent coverage, and TinyLive current preview.
- Output includes `windowOnlyDataFreshness`, `windowHasLiveSignalIds`,
  `evSamples`, `ocoPlansCreated`, `shadowIntentCount`,
  `missing_executability_evidence`, `counterfactual_required_evidence`, and
  `data_freshness_executability_recommendation`.
- A recommendation such as
  `ALPHA_NOT_EXECUTABILITY_PROVEN_COLLECT_SHADOW_REPLAY` means the historical
  +24h false-kill return is not yet executable profit evidence; collect a
  shadow/counterfactual replay that keeps EV, OCO, daily cap, duplicate,
  exposure, event-risk, and hard safety gates intact.
- The smoke must not change production env, DB, order, OCO, grid, fund, Earn,
  Telegram, scheduler, exchange, external backfill/import, deploy, restart, or
  nginx state.

For a focused read-only DataFreshness counterfactual replay-input review, run:

```powershell
.\scripts\smoke_data_freshness_counterfactual_review_ssh.ps1
```

Expected:

- The smoke performs direct production MySQL `SELECT` queries only, reading
  DataFreshnessGuard `bt_decision_audit` rows, linked
  `bt_runtime_decision_evidence`, and OKX `md_kline` forward windows.
- Output includes `data_freshness_counterfactual_rows`,
  `runtime_evidence_linked_rows`, `live_signal_linked_rows`,
  `explicit_candidate_entry_rows`, `explicit_candidate_tp_rows`,
  `explicit_candidate_sl_rows`, `ev_snapshot_rows`, `oco_plan_snapshot_rows`,
  `hard_gate_snapshot_rows`, `complete_replayable_candidate_rows`,
  `ev_preview_only_rows`, `oco_preview_only_rows`,
  `hard_gate_preview_only_rows`, `preview_only_input_rows`,
  `positive_forward_24h_rows`, `avg_forward_24h_pct`,
  `missing_counterfactual_fields`, `preview_only_missing_counterfactual_fields`,
  `replay_input_stage`, `collector_status_counts`,
  `hard_gate_preview_status_counts`, `replay_input_next_action`,
  `preview_only_note`, and
  `data_freshness_counterfactual_recommendation`.
- `replay_input_stage=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE` means the sample
  predates replay-id/collector markers; wait for new terminal DataFreshness
  rows before shadow review. `COLLECTOR_DISABLED_TRACE_ONLY` means collector
  markers are present but evidence collection is disabled. `PREVIEW_ONLY_NOT_REPLAYABLE`
  means placeholder fields exist but evaluated EV/OCO/hard-gate snapshots are
  still missing.
- `preview_only_*` rows prove field presence and terminal-block traceability
  only. They do not count as evaluated EV/OCO/risk pass evidence and do not
  count toward `complete_replayable_candidate_rows`.
- A recommendation such as
  `COUNTERFACTUAL_NOT_REPLAYABLE_CANDIDATE_SNAPSHOT_MISSING` means the
  historical forward-return alpha proxy still lacks replayable candidate
  snapshots; collect shadow/replay inputs before any policy review.
- Use `docs/data-freshness-shadow-replay-input-plan.md` before proposing a
  collector or shadow/replay change for this path. The plan defines required
  replay input fields, stop conditions, minimum sample expectations, and the
  live-mutation boundaries that must remain closed.
- Use `docs/data-freshness-shadow-replay-collector-design.md` before
  implementing any collector. The current L0 DataFreshnessGuard path returns
  before candidate/EV/OCO snapshots, so the only acceptable future collector is
  disabled by default, evidence-only, keeps DataFreshnessGuard as the terminal
  live decision, writes a stable `replayCandidateId`, and never creates live
  signals, sends Telegram, places orders, modifies OCO, mutates positions, or
  changes scheduler/live policy.
- The tracked template and runtime config must keep
  `TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=false` /
  `trading.data-freshness.shadow-replay.collector.enabled=false` unless a
  separate evidence-only collector rollout is explicitly reviewed.
- The current `DataFreshnessShadowReplayCollector` hook is only a local/runtime
  skeleton under that flag. Disabled mode emits safety markers only; enabled
  mode is still not replayable and only captures scalar K-line/strategy context
  plus fixed-config entry/TP/SL snapshots when those can be derived without ATR
  or side-effectful helpers. EV/TQS/OCO/hard-gate fields are currently explicit
  `NOT_EVALUATED_REPLAY_INPUT_ONLY` previews, not pass evidence. Dynamic ATR
  candidate plans are not guessed. It must not be treated as complete replay
  evidence or live-readiness evidence.
- New DataFreshness L0 audit rows include deterministic `replayCandidateId`
  values and explicit no-order/no-intent/no-OCO markers. Treat those ids as
  traceability only; policy review still requires entry/TP/SL/EV/OCO snapshots
  and complete hard-gate evidence.
- After deploying a runtime that writes those ids, run
  `.\scripts\smoke_data_freshness_replay_candidate_id_ssh.ps1`. Use
  `-RequireObserved` only when a new DataFreshnessGuard row is expected. A
  `PENDING_NO_NEW_DATAFRESHNESS_ROWS` result is incomplete evidence, not live
  approval.
  `DEPLOYED_RUNTIME_NOT_CURRENT` means the replay-id runtime has not been
  deployed yet; deploy and server verification must happen before replay-id
  evidence can be trusted.
  The smoke also prints DataFreshness sample recency: latest row time, latest
  row age, 1d/3d/7d/14d/30d row counts, and
  `data_freshness_sample_gap_status`, so operators can distinguish all-time
  sample absence from a review-window gap.
- Use `.\scripts\smoke_data_freshness_replay_observation_bundle_ssh.ps1` for
  the post-deploy replay observation chain. It combines origin-delta,
  replay-id, and counterfactual smokes and routes stale runtime to
  `DEPLOY_CURRENT_RUNTIME_THEN_OBSERVE_REPLAY_ID`.
- The smoke must not change production env, DB, order, OCO, grid, fund, Earn,
  Telegram, scheduler, exchange, external backfill/import, deploy, restart, or
  nginx state.

For a focused read-only DataFreshness shadow candidate packet, run:

```powershell
.\scripts\prepare_data_freshness_shadow_candidate_packet_ssh.ps1 -RequireReview
```

Expected:

- The packet invokes only
  `prepare_governance_relaxation_review_packet_ssh.ps1` and
  `smoke_data_freshness_counterfactual_review_ssh.ps1`.
- Output includes `data_freshness_shadow_candidate_packet`,
  `data_freshness_shadow_candidate_packet_status`,
  `data_freshness_shadow_candidate_missing_requirements`,
  `shadow_candidate_review_allowed`,
  `replay_input_stage`, `collector_status_counts`,
  `hard_gate_preview_status_counts`, `replay_input_next_action`,
  `data_freshness_policy_relaxation_allowed=false`,
  `tiny_live_order_allowed=false`, and `live_policy_change_allowed=false`.
- `BLOCKED_COUNTERFACTUAL_REPLAY_INPUT_MISSING` means the candidate remains
  blocked by missing complete replayable rows or counterfactual fields.
- `READY_FOR_DATAFRESHNESS_SHADOW_CANDIDATE_NOT_LIVE` means the emitted packet
  can be attached to a separate shadow-only candidate review. It is not
  permission to relax DataFreshnessGuard, enable live trading, deploy, restart,
  execute TinyLive, place orders, or modify OCO/grid/fund/Earn state.
- The packet is read-only and does not deploy, restart, change production env,
  mutate DB/order/OCO/grid/fund/Earn/Telegram/exchange state, run external
  backfill/import, or change scheduler/live policy.

For the read-only profit-improvement review bundle, run:

```powershell
.\scripts\smoke_profit_improvement_review_bundle_ssh.ps1
```

Expected:

- The wrapper invokes only existing read-only SSH/local smokes: origin-delta,
  profit-candidate review, DataFreshness false-kill review, DataFreshness
  executability review, strategy 485 position risk, strategy 574
  signal/governance, and TinyLive post-trade evidence.
- Output includes `profit_candidate_review_recommendation`,
  `data_freshness_false_kill_recommendation`,
  `data_freshness_executability_recommendation`,
  `data_freshness_counterfactual_recommendation`,
  `data_freshness_counterfactual_rows`,
  `complete_replayable_candidate_rows`, `missing_counterfactual_fields`,
  `strategy485_position_risk_recommendation`,
  `strategy574_policy_change_recommendation`, `tiny_live_post_trade_status`,
  `profit_improvement_review_items`, `profit_improvement_candidate_scorecard`,
  `profit_improvement_review_decision`,
  `deploy_required_before_profit_improvement_review`,
  `profit_improvement_missing_requirement_count`,
  `profit_improvement_missing_requirements`, `top_profit_improvement_candidate`,
  and `profit_improvement_bundle_recommendation`.
- The candidate scorecard ranks read-only profit-improvement candidates and
  required evidence, including deploy/replay evidence gaps, DataFreshness
  counterfactual replay-input coverage, aged negative-EV position review, and
  TinyLive near-BUY observation. It does not authorize live mutations.
- `profit_improvement_review_decision` is the top-level routing object for
  shadow/small experiment review. It includes
  `canDraftShadowExperimentReview`, `deployRequired`, `allowedReviewTypes`,
  `rankedEvidenceRefs`, `strategy485ReviewDecision`, missing-requirement
  counts, and no-live authorization text.
- A recommendation such as `COLLECT_DATAFRESHNESS_COUNTERFACTUAL_EVIDENCE` is
  review routing only; it is not permission to relax DataFreshnessGuard,
  activate strategies, close positions, modify OCO, deploy, restart, or change
  production env.
- The wrapper must not change production env, DB, order, OCO, grid, fund, Earn,
  Telegram, scheduler, exchange, external backfill/import, deploy, restart, or
  nginx state.

For the read-only profit experiment gate, run:

```powershell
.\scripts\prepare_profit_experiment_gate_ssh.ps1
```

Expected:

- The gate invokes `smoke_profit_improvement_review_bundle_ssh.ps1` only.
- Output includes `deploy_required_before_profit_experiment`,
  `shadow_experiment_review_allowed`, `live_policy_change_allowed=false`,
  `strategy485_position_review_decision`,
  `profit_experiment_missing_requirements`, and
  `profit_experiment_gate_status`.
- `shadow_experiment_review_allowed=true` is only permission to draft a separate
  shadow-only proposal. It is not permission to deploy, relax
  DataFreshnessGuard, close positions, modify OCO, enable live trading, or place
  orders.
- `BLOCKED_DEPLOY_CURRENT_RUNTIME` means deploy and server verification are
  required before replay/counterfactual evidence can be trusted.

For the read-only profit shadow experiment packet preflight, run after the
profit experiment gate is expected to be ready:

```powershell
.\scripts\prepare_profit_shadow_experiment_packet_ssh.ps1 -RequireReady
```

Expected:

- The preflight invokes `prepare_profit_experiment_gate_ssh.ps1` only.
- Output includes `profit_shadow_experiment_packet`,
  `profit_shadow_packet_status`, `profit_shadow_packet_missing_requirements`,
  `profit_improvement_review_decision`, `live_policy_change_allowed=false`,
  and `position_or_oco_mutation_allowed=false`.
- `READY_FOR_SHADOW_EXPERIMENT_PACKET_NOT_LIVE` means the emitted packet can be
  attached to a separate shadow-only experiment review. It is not permission to
  relax DataFreshnessGuard, place orders, deploy, restart, change production
  env, or mutate OCO/grid/fund/Earn state.
- `BLOCKED_DEPLOY_CURRENT_RUNTIME` means current origin/main must be deployed
  and verified before the packet can be trusted.

For a focused read-only guardrail acceptance check after deploying a runtime
that contains the latest issue #1/#2 local guardrail changes, run:

```powershell
.\scripts\smoke_guardrail_acceptance_ssh.ps1
```

Expected:

- The script calls server-local `/api/mcp`, not public Trading MCP.
- `analyzeSpotAntiWickPolicyCoverage` returns `boundary: READ_ONLY`, the
  BTC spot `ULTRA_LOW_DISASTER` policy marker, a summary, and an operator
  action.
- `getEventRiskControlStatus` returns `boundary=READ_ONLY`, `riskLevel=R0-R3`,
  policy text, and `operatorControls=CONFIG_ONLY_NO_RUNTIME_MUTATION`.
- `Operator action: REVIEW_POLICY_GAPS` is a review result, not a script
  transport failure; do not promote live strategies until the review gap is
  resolved.
- Add `-RequireNoReviewGaps` when this smoke is used as issue-acceptance
  evidence; that mode fails if `REVIEW_POLICY_GAPS` is present. The
  `verify_post_deploy_issue_acceptance_ssh.ps1` wrapper enables this mode.
- The script must not change order/OCO/strategy/grid/fund/Earn/Telegram/DB
  state.

For a read-only live-readiness audit before any explicit live enablement, run:

```powershell
.\scripts\audit_live_readiness_ssh.ps1
```

Expected:

- The script calls server-local `/api/mcp`, not public Trading MCP.
- Secret values are never printed; exchange, Telegram, and MCP keys are shown
  only as `SET` or `EMPTY`.
- Output includes order-capable flags, dry-run flags, background automation
  warnings, runtime-log smoke, machine-readable `readiness_details`,
  `blocker_classification`, `next_actions`, blockers, and a final verdict.
- Output includes `missing_readiness_detail_fields=[]` when the reviewed
  `readiness_details` sections and execution fields are complete. Missing or
  non-empty summaries keep the MCP audit evidence blocked.
- `blocker_classification` separates market-condition waits, runtime evidence
  gaps, risk hard stops, execution-disabled guards, background automation review
  items, and runtime/security gaps so operator review does not confuse secondary
  capacity issues with live-opening blockers.
- `verdict=NOT_READY` means do not enable live yet; address the listed blockers
  or intentionally narrow the planned live scope first.
- `verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED` is not live enablement.
  It only means the operator can review a separate, explicitly authorized env
  change plan.
- After a separately authorized TinyLive live launch, rerun the audit with
  `-LiveAuthorized`. In that mode, the expected live flags
  `TRADING_OKX_ENABLED=true` and
  `TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true` with TinyLive dry-run false
  are reviewed as evidence instead of pre-live blockers. The mode still fails
  closed on unexpected order-capable flags, missing TinyLive hard-scope proof,
  missing order-sent=false markers, non-R0 event risk, missing OKX secrets,
  guardian write mode, runtime errors, and unexpected high-risk logs.
- After the first authorized TinyLive execution, run
  `.\scripts\smoke_tiny_live_post_trade_ssh.ps1`. Until a new execution exists,
  `post_trade_status=PENDING_NO_NEW_TINY_LIVE_EXECUTION` is expected. Once an
  execution exists, the smoke checks the latest TinyLive execution audit, OCO
  attach/protection effectiveness, runtime `orderSentEvidence`, active
  execution events, and TinyLive Telegram history. Use `-RequireExecution` only
  when a new execution is expected and absence should fail the check.
- The audit must not change order/OCO/strategy/grid/fund/Earn/Telegram/DB
  state.

When the audit reports `risk_hard_stop` or
`AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES`, run the read-only
tiny-live loss RCA smoke before drafting any live env-change plan:

```powershell
.\scripts\smoke_tiny_live_loss_rca_ssh.ps1
```

After a separately authorized evidence-only review path has collected fresh
tiny-live proof, use the hard gate:

```powershell
.\scripts\smoke_tiny_live_loss_rca_ssh.ps1 -RequireClear
```

Expected:

- The script calls server-local `/api/mcp`, not public Trading MCP.
- It prints `hardStopDetected`, auto-approval blockers, trigger/dry-run state,
  recent tiny-live execution audit summary, autonomous execution attribution,
  missed-opportunity context, rollout gates, and monitor/rollout excerpts. The
  default 30-day window matches the consecutive-loss guard used by the
  auto-approval policy.
- It prints `missing_tiny_live_hard_stop_fields=[]`,
  `missing_tiny_live_rollout_fields=[]`, and `missing_tiny_live_fields=[]`
  when reviewed hard-stop and rollout evidence is complete. Any non-empty list
  keeps the related live-readiness blocker open.
- It prints `hardStopClearCriteria` so review stays tied to the policy gate:
  consecutive tiny-live losses below 2, a current BUY candidate, runtime
  evidence available, and any execution flag change handled by a separately
  authorized env plan.
- It requires read-only/no-order markers from the called MCP surfaces.
- It may report `hardStopDetected=false` after the blocker is legitimately
  cleared; that is not live approval. Re-run the full live-readiness audit and
  prepare a separately authorized env-change plan before enabling anything.
- A separately authorized tiny-live launch may set
  `TRADING_TINY_LIVE_AUTO_APPROVAL_IGNORE_CONSECUTIVE_LOSS_HARD_STOP=true` to
  override only the consecutive-loss blocker. This does not bypass
  `NO_CURRENT_BUY_CANDIDATE`, OCO preflight, EV, runtime-evidence, daily loss
  budget, scope, duplicate, position, notional, or event-risk gates; the
  approval preview must print
  `consecutiveTinyLiveLossHardStopOverride=true` and
  `"ignoreConsecutiveLossHardStop":true` when the override is active.
- With `-RequireClear`, the smoke exits 0 only when `hardStopDetected=false`,
  `missing_tiny_live_fields=[]`, and `canEnableProduction=true`; otherwise it
  prints the RCA details and exits non-zero.
- The script must not change order/OCO/strategy/grid/fund/Earn/Telegram/DB
  state.

When the audit reports `runtime_evidence_gap`, `RUNTIME_EVIDENCE_MISSING`, or
`runtimeEvidenceStatus=NOT_READY_*`, run the read-only runtime-evidence RCA
smoke before any live env-change plan:

```powershell
.\scripts\smoke_runtime_evidence_rca_ssh.ps1
```

After a separately authorized evidence-only env change and restart, use the
hard gate:

```powershell
.\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady
```

Expected:

- The script calls server-local `/api/mcp`, not public Trading MCP.
- It prints the masked `TRADING_RUNTIME_EVIDENCE_ENABLED` state, dashboard
  `enabled` flag, preview `runtimeEvidenceStatus`, recent evidence row count,
  shadow-intent counts, `orderSentEvidence`,
  `missing_runtime_evidence_fields`, candidate context, and no-buy context.
- It prints `runtime_evidence_review_plan`, a machine-readable review-routing
  list with `gate`, `state`, `riskCategory`, `evidenceMarkers`,
  `requiredEvidence`, `nextAction`, and `notAuthorization`; this is not
  authorization to mutate production env or enable live behavior.
- It classifies the gap as `CONFIG_DISABLED`, `NO_CANONICAL_ROWS`,
  `CANONICAL_ROWS_NO_SHADOW_INTENT`, `CANONICAL_SHADOW_READY`, or
  `REVIEW_RUNTIME_EVIDENCE_STATUS`.
- `missing_runtime_evidence_fields` must be empty before
  `CANONICAL_SHADOW_READY` can clear the runtime-evidence review gate.
- The full bundle also requires `runtime_evidence_review_plan` to be present;
  if the diagnosis is otherwise ready but the plan still contains
  `state=BLOCKED` or `state=HARD_BLOCKED`, the bundle keeps
  `RUNTIME_EVIDENCE_REVIEW_REQUIRED`.
- With `-RequireReady`, the smoke exits 0 only when
  `diagnosis=CANONICAL_SHADOW_READY`, `missing_runtime_evidence_fields=[]`,
  `shadowIntentCount > 0`, and `orderSentEvidence=0`; otherwise it prints the
  RCA details and exits non-zero.
- `CANONICAL_SHADOW_READY` is not live approval; it only means this one gate
  should be rechecked by the full live-readiness audit.
- The script must not write RuntimeDecisionEvidence, place orders, change OCO,
  enable flags, send Telegram, mutate production env, or change DB state.

Before drafting an evidence-only production env change, review
`docs/live-dry-run-evidence-plan.md`. It allows
`TRADING_RUNTIME_EVIDENCE_ENABLED=true` only as a separately authorized
evidence candidate and keeps live/order/OCO/grid/Earn/fund/Telegram/scheduler,
guardian live-action, and external-backfill/import flags disabled. The checklist
must not be used as live approval.
Use `docs/live-production-env-review-proposal.md` to classify any proposed
production env diff. It documents which currently enabled background automation
flags should be disabled or separately justified before live; it is not an env
mutation script and does not authorize production changes.
Use its "Pre-Live Review Decision Checklist" before drafting a live packet:
runtime currentness first, full read-only bundle second, packet preflight last.
Any stale runtime, `NOT_READY`, `NO_EVIDENCE`, or non-empty blocker output keeps
the live review blocked.

To isolate already-enabled background automation before live review, run:

```powershell
.\scripts\smoke_live_background_automation_ssh.ps1
```

Expected:

- The script reads server env and app metadata only.
- Output includes `background_automation_true`,
  `high_risk_background_automation_true`,
  `missing_background_automation_flags`,
  `background_automation_review_plan`, `background_automation_blockers`,
  `backgroundAutomationClear`, `classification`, and `verdict`.
- `background_automation_review_plan` lists every true or missing reviewed flag
  with `riskCategory`, `concern`, `requiredReview`, `requiredEvidence`,
  `nextAction`, and `notAuthorization`; it routes operator review only and
  does not clear blockers.
- After a separately authorized background-automation env diff, rerun with
  `-RequireClear`; that mode exits non-zero if any reviewed flag is still true
  or missing, after printing the blocker details.
- `missing_background_automation_flags` must be empty before the background
  automation blocker can clear; absent reviewed env keys are not treated as
  explicit false evidence.
- `backgroundAutomationClear=true` and `background_automation_blockers=[]` are
  required before the background automation blocker can clear.
- The full bundle also requires `background_automation_review_plan` to be
  present and empty when background automation is otherwise clear; missing plan
  evidence or a remaining `state=TRUE`/`state=MISSING` entry keeps
  `BACKGROUND_AUTOMATION_REVIEW`.
- `verdict=NOT_READY_BACKGROUND_AUTOMATION_REVIEW` means live remains blocked
  until the listed flags are reviewed or separately authorized.
- The script must not change production env, DB, order, OCO, grid, fund, Earn,
  Telegram, scheduler, or external backfill/import state.

To run the full read-only live-readiness evidence bundle:

```powershell
.\scripts\smoke_live_readiness_bundle_ssh.ps1
```

To run the read-only live review packet preflight:

```powershell
.\scripts\prepare_live_review_packet_ssh.ps1 -RequireReady
```

This wrapper runs the full bundle and emits `packet_status`. It is packet-ready
only when the underlying bundle proves `bundle_blockers=[]`,
`live_review_packet_allowed=true`, `deploy_required_before_live_review=false`,
and `bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`. With
`-RequireReady`, it must exit 0 with
`packet_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED` and
`packet_missing_requirements=[]`. It also carries
`packet_bundle_blocker_summary` from the underlying bundle so each blocker keeps
machine-readable `requiredEvidence`, `evidenceMarkers`, and `nextAction`; a
missing, invalid, incomplete, or non-empty `bundle_blocker_summary` when
`bundle_blockers=[]` is incomplete evidence. Ready packet output must include
`packet_bundle_blocker_summary=[]`. `NOT_READY` and `NO_EVIDENCE` output is not live approval, does not
authorize production env changes, and must not be used to enable live trading.
When `NO_EVIDENCE` includes `DEPLOYED_RUNTIME_NOT_CURRENT`, run the read-only
origin-delta classifier before choosing the next action. If it prints
`origin_delta_status=RUNTIME_DRIFT`, the next action is a separately authorized
deploy and verification of current `origin/main`. If it prints
`origin_delta_status=DOCS_TOOLING_ONLY_DRIFT`, review and attach the classifier
evidence separately. If it prints `origin_delta_status=NO_LOCAL_EVIDENCE`,
refresh local git evidence or rerun the metadata smoke. In all cases, rerun the
full read-only bundle before drafting any live packet.

To prepare a local review packet preflight for production-env review:

```powershell
.\scripts\prepare_live_env_review_packet.ps1 -RequireReady
```

Expected:

- The script does not use SSH and does not change production env, deploy,
  restart, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange,
  external backfill/import, or policy state.
- `env_review_packet_status=READY_FOR_OPERATOR_ENV_REVIEW_NOT_AUTHORIZED`
  means the proposal docs are internally consistent enough to attach to a
  separate operator env-change request with fresh read-only SSH smokes; it is
  not authorization, and operators must not apply changes from this output. Do
  not apply changes from this output.
- `forbidden_true_candidates=[]`.
- `env_review_missing_requirements=[]`.
- The only runtime-evidence candidate is
  `TRADING_RUNTIME_EVIDENCE_ENABLED=true`.
- The background-automation candidate only sets the nine reviewed background
  flags to `false`.

To check only whether the server worktree/deployed runtime is stale relative to
current `origin/main`, run the faster metadata-only smoke:

```powershell
.\scripts\smoke_live_deployment_metadata_ssh.ps1
```

Expected:

- The script prints `refreshType=DEPLOYMENT_METADATA_ONLY`.
- It may print `DEPLOYED_RUNTIME_NOT_CURRENT` when server metadata is stale.
- It always prints `live_review_packet_allowed=false`.
- It is metadata-only, not live-readiness evidence and not a substitute for the
  full bundle after a deploy.
- Even when it prints `metadata_current=true` or
  `deployment_metadata_status=DOCS_TOOLING_ONLY_DRIFT`, treat it only as a
  currentness probe; it does not replace the full live-readiness bundle.
- If SSH access or the remote read-only command fails, it prints
  `read_only_metadata_error=SSH_AUTH_FAILED`, `SSH_CONNECT_FAILED`,
  `SSH_COMMAND_FAILED`, or `READ_ONLY_SMOKE_FAILED`, plus
  `metadata_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE"]`,
  `live_review_packet_allowed=false`,
  `deploy_required_before_live_review=unknown`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`; treat that as an
  incomplete metadata refresh, not live-readiness evidence.

Optional local read-only origin-delta classifier:

```powershell
.\scripts\smoke_live_origin_delta_local.ps1
```

Expected:

- It runs the metadata-only SSH smoke, then uses local `git diff --name-only`
  evidence between the server worktree commit and `origin/main`.
- It prints `origin_delta_status=DOCS_TOOLING_ONLY_DRIFT`, `RUNTIME_DRIFT`,
  `CURRENT_ORIGIN_MAIN`, or `NO_LOCAL_EVIDENCE`.
- It always prints `live_review_packet_allowed=false`; use it only to route the
  next operator step, not as live-readiness evidence.

Expected:

- The wrapper runs the live-readiness audit, background automation smoke,
  runtime-evidence RCA, tiny-live loss RCA, signal-correctness smoke, and MCP
  parity smoke.
- Evidence windows stay bounded and are passed to child smokes:
  `RuntimeEvidenceMinutes=43200`, `TinyLiveDays=30`,
  `SignalExecutionDays=5`, `SignalBlockedDays=7`, and
  `SignalAccuracyDays=14` by default. Override them only for a documented
  read-only diagnostic, not as live approval evidence.
- Output includes deployment metadata status, `bundle_blockers`,
  `bundle_blocker_summary`, `live_review_packet_allowed`,
  `deploy_required_before_live_review`, and `bundle_verdict`. Treat
  `DEPLOYED_RUNTIME_NOT_CURRENT` as stale live-review
  evidence until a separate deploy and verification refresh the runtime and
  server worktree to `origin/main`. By default the full bundle stops after
  stale deployment metadata and prints `bundle_verdict=NO_EVIDENCE`; use
  `-ContinueWhenRuntimeStale` only for diagnostic stale-runtime child-smoke
  output.
- Do not draft a live review packet unless the latest full bundle prints
  `bundle_blockers=[]`, `live_review_packet_allowed=true`,
  `deploy_required_before_live_review=false`, and
  `bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`; `NOT_READY`,
  `NO_EVIDENCE`, `live_review_packet_allowed=false`, and stale runtime metadata
  remain blocking evidence.
- If the bundle cannot collect complete evidence because of `SSH_AUTH_FAILED`,
  `SSH_CONNECT_FAILED`, `SSH_COMMAND_FAILED`, or `READ_ONLY_SMOKE_FAILED`, it emits
  `bundle_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE"]`,
  `bundle_blocker_summary`,
  `live_review_packet_allowed=false`,
  `deploy_required_before_live_review=unknown`, and
  `bundle_verdict=NO_EVIDENCE`; treat that as an incomplete evidence problem,
  not live-readiness evidence.
- If deployment metadata was already collected before a later child smoke
  fails, the failure output also preserves `deployment_metadata_status`,
  `origin_metadata_status`, and, when stale, adds
  `DEPLOYED_RUNTIME_NOT_CURRENT` to `bundle_blockers` with
  `deploy_required_before_live_review=true`.
- Both fail-fast paths still print `bundle_blocker_summary`, so automation can
  route incomplete-evidence or stale-runtime output without treating it as
  live-readiness evidence.
- `bundle_verdict=NOT_READY` is the expected result while runtime evidence,
  tiny-live hard stop, signal policy, or background automation blockers remain.
- The wrapper must not change production env, DB, order, OCO, grid, fund, Earn,
  Telegram, scheduler, exchange, or external backfill/import state.
- Use `docs/live-readiness-blocker-remediation.md` to translate
  `bundle_blockers` into clear conditions and required read-only evidence before
  drafting any live review packet.
- Treat `bundle_blocker_summary` as a machine-readable copy of the same
  remediation mapping. Each entry includes `category`, `requiredEvidence`,
  `evidenceMarkers`, and `nextAction`; those markers explain why the blocker
  was emitted, but they do not clear blockers or authorize production env
  changes.
- If the refreshed runtime log smoke fails after deploying the classified log
  checker, attach the `ERROR category ...` line and
  `ERROR rca=TELEGRAM_EXECUTION_EVENT_NOTIFICATION_PATH` marker before reviewing
  `EVENT_SCAN_NOTIFICATION_ENABLED`, `EXECUTION_EVENT_ENABLED`, Telegram send
  health, or background automation authorization.
- Use `docs/live-background-automation-env-diff-proposal.md` to review the
  proposed background automation env diff. It must not be treated as
  authorization to edit production env.
- Use `docs/live-runtime-evidence-env-proposal.md` to review the separate
  evidence-only runtime evidence diff. It must not be bundled with execution,
  Telegram, scheduler, external-backfill/import, exchange, OCO, grid, fund, or
  Earn enablement.

For a read-only trailing-stop 30d PnL replay check after deploying a runtime
that contains `analyzeTrailingStopPnlReplay`, run:

```powershell
.\scripts\smoke_trailing_stop_pnl_replay_ssh.ps1
```

Expected:

- The script calls server-local `/api/mcp`, not public Trading MCP.
- Output includes `boundary: READ_ONLY`.
- Output includes `acceptanceTarget: total trailing PnL improvement >= 5%`.
- Output includes `backtestInterval`, `replayInterval`, and
  `replayIntervalNote=backtest interval selects normalized trades`; the default
  `replayIntervalCode=1m` is intended to reduce false same-bar ambiguity for
  1h normalized backtest trades.
- Output includes `sampleStatus=NO_REPLAYABLE_TRADES`, `sampleStatus=REPLAYED`,
  or `sampleStatus=NO_REPLAYED_ROWS`.
- The default issue-closure sample is 30d/500; use smaller limits only as
  narrow diagnostics, not as the final deployed acceptance signal.
- Treat `NO_REPLAYABLE_TRADES` or `NO_REPLAYED_ROWS` as deploy reachability
  evidence only, not PnL acceptance.
- PnL acceptance totals exclude `ambiguousSameBar` rows where trigger/stop
  ordering cannot be proven from OHLC bars.
- `NOT_PROVEN` output must include `acceptanceBlocker` and
  `acceptanceBlockerDetail`; use these fields to decide whether the next safe
  step is collecting/choosing non-ambiguous samples, fixing missing replay
  inputs, or reviewing the +0.5/+1.0 ATR parameters. Do not treat those fields
  as approval to relax live trailing/OCO behavior.
- To make issue #3 PnL acceptance a hard gate, run with `-RequireAcceptance`;
  this fails unless the deployed DB sample returns `acceptance=PASS`.
- The script must not change order/OCO/strategy/grid/fund/Earn/Telegram/DB
  state.

For a read-only trailing-stop operator review packet, run:

```powershell
.\scripts\prepare_trailing_stop_operator_review_packet_ssh.ps1 -RequireReady
```

Expected:

- The packet runs `smoke_trailing_stop_pnl_replay_ssh.ps1 -RequireAcceptance`
  and emits `trailing_stop_operator_review_packet`.
- `trailing_stop_operator_packet_status=READY_FOR_OPERATOR_PACKET_NOT_LIVE`
  means the exit-side evidence can be attached to a separate operator review.
- The packet carries `sampleStatus`, `acceptanceRows`,
  `acceptanceDeltaPnl`, `improvementPct`, `acceptance=PASS`,
  `acceptanceBlocker=NONE`, and the ambiguous same-bar exclusion marker.
- The packet is read-only. It does not deploy, restart, reload nginx, change
  production env, enable live trading, enable the trailing scheduler, change
  strategy opt-in, place orders, modify OCO, close positions, mutate
  DB/grid/fund/Earn/Telegram/exchange state, run external backfill/import, or
  authorize exit policy changes.

For a read-only exit-side profit review packet, run:

```powershell
.\scripts\prepare_exit_side_profit_review_packet_ssh.ps1 -RequireReady
```

Expected:

- The packet runs `prepare_trailing_stop_operator_review_packet_ssh.ps1` and
  `prepare_strategy485_operator_review_packet_ssh.ps1`.
- Output includes `exit_side_profit_review_packet`,
  `exit_side_profit_review_packet_status`,
  `trailing_stop_acceptance`, `trailing_stop_improvement_pct`,
  `strategy485_operator_packet_status`, and
  `strategy485_negative_ev_position_count`.
- `READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION` means exit-side evidence
  can be attached to a separate operator review.
- The packet is read-only. It does not deploy, restart, reload nginx, change
  production env, enable live trading, enable the trailing scheduler, change
  strategy opt-in, place orders, modify OCO, close positions, mutate
  DB/grid/fund/Earn/Telegram/exchange state, run external backfill/import, or
  authorize exit policy changes.

For a read-only exit-side operator decision brief, run:

```powershell
.\scripts\prepare_exit_side_operator_decision_brief_ssh.ps1 -RequireDecisionReady
```

Expected:

- The brief calls `prepare_exit_side_profit_review_packet_ssh.ps1` only and
  emits `exit_side_operator_review_recommendations`,
  `exit_side_operator_decision_brief_packet`, and
  `exit_side_operator_decision_brief_status`.
- `READY_FOR_OPERATOR_DECISION_NOT_MUTATION` means the exit-side evidence can
  be attached to a separate operator decision with
  `PREPARE_SEPARATE_EXIT_SIDE_OPERATOR_REVIEW`.
- The brief keeps trailing-stop policy review separate from strategy 485 aged
  negative-EV position review, and lists separate authorizations required for
  trailing enablement, close-position, OCO modification, deployment, or
  production env changes.
- The brief emits top-level `strategy485_position_summaries` and carries
  trailing acceptance sample counts in `evidenceSummary`, so the key operator
  evidence is visible without inspecting nested source packets.
- The brief does not deploy, restart, reload nginx, change production env,
  enable live trading, enable the trailing scheduler, change strategy opt-in,
  place orders, modify OCO, close positions, mutate
  DB/grid/fund/Earn/Telegram/exchange state, run external backfill/import, or
  authorize exit policy changes.

Optional public path check:

```bash
PUBLIC_TRADING_HEALTH_URL="https://agoratradingapi.purrtechllc.com/api/actuator/health" \
  bash scripts/verify_server.sh
```

Dedicated Trading host check:

```bash
curl -fsS "https://agoratradingapi.purrtechllc.com/api/actuator/health"
curl -sS -o /dev/null -w '%{http_code}\n' \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}' \
  "https://agoratradingapi.purrtechllc.com/api/mcp"
```

Expected MCP status is one of `401`, `403`, `404`, or `405`; `200` means public
Trading MCP is exposed and the deploy must be treated as failed. Server
verification also checks that the nginx exact MCP blocks return `404` directly
and do not contain `proxy_pass`.

Optional schema baseline table comparison for Flyway baseline drift review:

```bash
RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh
```

Cross-service live MCP ownership smoke is maintained by AgoraMarketAPI tooling.
Run it from the AgoraMarketAPI checkout when the acceptance question is whether
representative legacy Trading tools are absent from AgoraMarketAPI and present
in `agora-trading-api`:

```powershell
powershell -ExecutionPolicy Bypass -File C:\Users\Redan\IdeaProjects\AgoraMarketAPI\tools\codex\check-live-mcp-split-ownership.ps1
```

The Trading-side `scripts/verify_split_acceptance_ssh.ps1` wrapper calls that
same AgoraMarketAPI smoke after `scripts/verify_server_ssh.ps1 -SchemaCompare`
and `scripts/check_server_runtime_log.sh`; it does not deploy, reload nginx,
mutate database schema, or call MCP write tools.
Windows SSH wrappers validate `SshHost` locally and reject unsupported SSH
target syntax before invoking `ssh`; this keeps deploy and acceptance tooling
from being redirected through option-like targets.

## Startup Warning Baseline

Observed again on 2026-06-15 after the `7e02307` deploy, the latest Trading run
log contained only known WARN baseline lines while `scripts/verify_server.sh`,
local health, local MCP registry, public health, nginx checks, runtime log smoke,
and cross-service MCP ownership smoke all passed. Classify these as startup
audit evidence, not deploy failure evidence, unless they prevent readiness or
reappear after the app is already serving traffic.

Current warning classes:

| Warning | Current handling |
|---|---|
| Flyway reports MySQL 9.7 is newer than the verified Flyway version | Known compatibility warning; schema hardening remains valid because `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`, `SPRING_FLYWAY_ENABLED=true`, and `SPRING_FLYWAY_TABLE=trading_flyway_schema_history` are verified by deploy. |
| `StartupBeanTimingProbe` slow bean warnings | Internal startup timing telemetry. Investigate only if readiness approaches the deploy timeout or a specific bean regresses materially. |
| Spring AOP proxy warning for final servlet filter methods | Framework proxy warning around final methods; do not treat as trading behavior failure without a related request/filter bug. |
| `external.thegraph.api-key not configured` | Optional external data-provider warning; acceptable only while The Graph-backed reads/backfills remain disabled by split guards. |
| `spring.jpa.open-in-view is enabled by default` | Do not flip to `false` as a drive-by cleanup; it can change lazy-loading behavior and should be handled through a focused API/DTO audit. |
| `DailyAutonomousTradingDigest` severe notification sent | Known operator-alert warning only when production explicitly enables autonomous digest Telegram/severe-scan flags. It is not an order/OCO/grid/Earn/fund action, but the category count should still be reviewed after each deploy. |
| `OkxWsKlineService` public WS `Connection reset` | Treated as transient only while below `MAX_OKX_WS_CONNECTION_RESET_WARN` (default `3`) and followed by fresh persisted K-line rows. Exceeding the threshold is a runtime-log smoke failure and should be investigated as collector/network instability. |

The warning baseline is intentionally separate from Trading split acceptance.
Acceptance still requires `scripts/verify_local.ps1`, `scripts/verify_server.sh`,
public dedicated-host `/api/actuator/health`, public Trading MCP blocked smoke,
server-local MCP registry smoke, and
cross-service live MCP ownership smoke when live ownership boundaries are being
validated.

`scripts/check_server_runtime_log.sh` enforces this warning baseline for the
active run log. It fails on runtime `ERROR` lines, WARN lines outside the known
baseline above, and operation-like live trading/OCO/grid/Earn/fund lines in the
recent log tail. On success, it also prints the known WARN category counts:
Flyway/MySQL version, startup bean timing, CGLIB proxy, open-in-view, and
optional TheGraph key, autonomous digest severe-notification, and bounded OKX
public WS connection-reset warnings.

Reviewable Flyway baseline generation after a clean shared-mode compare:

```bash
bash scripts/schema_baseline_generate_server.sh
```

When `RUN_SCHEMA_BASELINE_COMPARE=1` is set for `deploy.sh`, the deploy script
passes that flag into post-deploy `scripts/verify_server.sh`; the default remains
`0` for normal deploy acceptance because the heavier shared-DB schema compare is
reserved for baseline drift review or schema-change acceptance.

## AgoraMarketAPI Trading Cutover Plan

This is a plan only. Do not stop or disable the legacy AgoraMarketAPI trading
runtime until the schema baseline compare is clean and the owner explicitly
starts cutover.

Prerequisites:

- `agora-trading-api` is deployed from current `origin/main`.
- `scripts/verify_server.sh` passes without override-only dependency routing.
- `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh` passes with no
  `missing-in-db.txt`; extra marketplace/shared tables are expected while
  `SCHEMA_COMPARE_MODE=shared`.
- `/api/actuator/health` passes locally and through the dedicated Trading host.
- `/api/mcp` `getMcpRegistryVersion` passes server-local with `TRADING_MCP_KEY`.
- AgoraMarketAPI's `check-live-mcp-split-ownership.ps1` passes, proving
  representative legacy Trading tools are absent from AgoraMarketAPI `/api/mcp`
  and present in `agora-trading-api` server-local `/api/mcp`.
- Scheduler ownership is reviewed so order/OCO/grid/fund/Earn-capable jobs are
  either disabled in both services or intentionally enabled in exactly one
  service.
- AgoraMarketAPI still owns only the marketplace/internal APIs needed by trading,
  including `/api/internal/exchange-rates/usdt`.

Cutover sequence:

1. Confirm Trading env points at the shared `agora_market` database.
2. Re-run schema compare in shared mode and resolve any missing trading tables.
3. Re-run server verify with schema compare enabled.
4. Keep trading env on `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`,
   `SPRING_FLYWAY_ENABLED=true`, and
   `SPRING_FLYWAY_TABLE=trading_flyway_schema_history`.
5. Re-run local verify, server verify with schema compare, public health, and
   MCP registry smoke.
6. In AgoraMarketAPI, disable only the legacy trading HTTP/MCP/scheduler entry
   points after confirming the new trading service owns the path.
7. Keep AgoraMarketAPI internal exchange-rate endpoints available for
   `agora-trading-api`.
8. Monitor logs for duplicate scheduler execution, SQL errors, MCP auth errors,
   and nginx `/api/trading/` routing failures before removing any legacy code.

Rollback:

- Re-enable the legacy AgoraMarketAPI trading entry points.
- Point nginx `/api/trading/` back to the previous proven target if needed.
- Keep the previous deploy metadata and DB backup evidence until post-cutover
  monitoring is clean.

Exchange-rate behavior:

- with `AGORA_MARKET_INTERNAL_API_KEY`: trading calls AgoraMarket internal API.
- without key, timeout, or 401: trading falls back to static rates.

AgoraMarket checks in these scripts are dependency checks for the trading
exchange-rate client. They do not deploy, configure, or mutate AgoraMarketAPI.

`scripts/verify_server.sh` checks:

- deploy, preflight, and server verification fail fast on required local tools for git sync, build, blue-green process launch, active metadata checks, health probes, nginx swaps, and post-verify parsing.
- server verification passes `EXPECTED_AGORA_MARKET_BASE_URL` into preflight so custom dependency-routing checks use one expected base URL throughout the server acceptance run.
- bootstrap and nginx path installation fail fast on their repo/nginx inspection and file-update tools, including `grep`, `ls`, `cp`, `mv`, `nginx`, and `rm` where used.
- schema baseline database comparison fails fast on its inventory and comparison tools, including `find`, `xargs`, `perl`, `mysql`, `comm`, `sort`, `wc`, and `tr`.
- schema baseline database comparison rejects datasource targets outside the expected shared database before querying MySQL.
- schema baseline source inventory and server comparison reject unsafe table
  names outside `[A-Za-z0-9_]` before baseline generation can pass them to
  `mysqldump`.
- shell syntax passes for `deploy.sh` and `scripts/*.sh` via `scripts/preflight_server.sh`.
- server worktree commit matches `origin/main` by default; set `VERIFY_GIT_CURRENT=0` only for explicit rollback verification.
- deployed `app.commit`, `app.pid`, and `app.port` metadata must exist by default; set `REQUIRE_DEPLOY_METADATA=0` only for explicit non-deploy diagnostics.
- deployed `app.commit` metadata matches the current worktree HEAD.
- if deployed `app.commit` differs from worktree `HEAD`, server verification
  fails when runtime files differ from deployed `app.commit`; it may pass only
  when the delta is docs/tooling-only and logs
  `deployed app.commit differs from worktree HEAD only by docs/tooling files`.
- active blue-green `app.pid.<app.port>` metadata exists by default and matches `app.pid`.
- deployed `app.pid` metadata points to a running process that is listening on the active `app.port`.
- public HTTP allowlist stays minimal: OpenAPI docs, actuator probes/metrics, rate-limit JSON redirect, and favicon; Trading MCP is internal-only and must not be exposed on public dedicated or shared-host routes.
- `AGORA_MARKET_BASE_URL` must point at stable AgoraMarketAPI nginx vhost dependency `https://agoramarketapi.purrtechllc.com`; deploy, preflight, and server verification fail on stale values.
- `SPRING_DATASOURCE_URL` must point at expected shared database `agora_market`; deploy, preflight, and server verification fail on unexpected datasource targets.
- `deploy.sh` checks AgoraMarket exchange-rate dependency health before starting the blue-green switch, so dependency failure stops the deploy before a new instance or nginx change is attempted.
- preflight and server verification require AgoraMarket exchange-rate dependency health by default; `REQUIRE_AGORA_MARKET_HEALTH=0` is only for diagnostic preflight and does not make deploy acceptance pass.
- local MCP `getMcpRegistryVersion` passes through `/api/mcp` using `TRADING_MCP_KEY`, proving the trading context path and MCP auth mapping.
- public Trading MCP blocked checks pass through
  `PUBLIC_TRADING_MCP_BLOCKED_URL` and
  `PUBLIC_TRADING_CONTEXT_MCP_BLOCKED_URL`; public `tools/list` must not return
  `200`. This catches dedicated-host or shared-host route drift and host mixups.
- deploy runs this server verification after switching active metadata by default; set `RUN_POST_DEPLOY_VERIFY=0` only for deliberate emergency bypasses.
- deploy restores active metadata and nginx backup when post-deploy verification fails or the post-deploy verifier is missing.
- deploy drains the previous blue-green instance only after verification passes; logs include `draining old instance after verification`. If post-deploy verification is skipped, deploy keeps the previous instance and nginx backup.
- nginx deploy verifies public trading health through `DEFAULT_PUBLIC_TRADING_HEALTH_URL` by default.
- required server env keys exist and are non-empty in `/home/ubuntu/.env.trading.secrets` without printing secret values.
- deploy refuses to overwrite staged or unstaged server worktree changes before syncing from `origin/main`.
- deploy and server verification fail fast if `AgoraMarketAPI/internal-client` is missing; deploy installs it into the server Maven local repo before building trading.
- Remaining `enabled:true` fallbacks are deliberately limited to protective or internal behavior and enforced by `scripts/verify_local.ps1`: MCP master-approval probe wait, Telegram noise reduction, enabled-strategy kline data validation, and deterministic regime filtering. Any new default-on fallback must be classified or changed to explicit opt-in before deploy prep is considered clean.
- Remaining `@DefaultValue("true")` properties are deliberately limited to protective gates, dry-run flags, internal diagnostics, or subordinate behavior behind disabled parent switches and are enforced by `scripts/verify_local.ps1`.
- Remaining `@Value` `:true` fallbacks are deliberately limited to protective/internal checks and dry-run flags; new default-on `@Value` fallbacks must be classified or changed to explicit opt-in before deploy prep is considered clean.
- Remaining `Environment.getProperty` default-`true` fallbacks are deliberately limited to MCP master-approval protection, ScoreBuy/TinyLive dry-run flags, and post-scout add sub-options behind disabled execution; new default-on environment property fallbacks must be classified or changed to explicit opt-in before deploy prep is considered clean.
- Remaining direct `System.getenv().getOrDefault(..., "true")` fallbacks are deliberately limited to `STARTUP_BEAN_TIMING_ENABLED`, an internal startup timing diagnostic that does not call external services or mutate runtime state.
- Coinalyze credentials use `TRADING_MARKET_DATA_COINALYZE_API_KEY`, which maps to `trading.market-data.coinalyze.api-key`; legacy external-style Coinalyze env names are not used by the trading code.
- trading uses the shared MySQL database, currently `agora_market`.
- `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` and `SPRING_FLYWAY_ENABLED=true` are required after the Flyway baseline exists.
- Deploy, server preflight, and verification require the real server env to use the Trading-owned `trading_flyway_schema_history` table.
- schema baseline database comparison is available through `scripts/schema_baseline_compare_server.sh`; run it through `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh` before regenerating `V1__baseline.sql` for review or before accepting a future `V2__...` migration.
- Extra marketplace/shared tables are expected in shared DB mode. The standalone-only cleanup planner is disabled unless `SCHEMA_COMPARE_MODE=standalone`.
- active local trading health via required `app.port` metadata by default, limited to the `8084/8085` blue-green port set; `REQUIRE_DEPLOY_METADATA=0` may use default `8084` only for non-deploy diagnostics.
- AgoraMarket exchange-rate dependency health through `https://agoramarketapi.purrtechllc.com/api/actuator/health` by default.
- optional public trading health URL.
- nginx `/api/trading/` path split presence by default; set `REQUIRE_NGINX_TRADING_PATH=0` only for non-nginx verification environments.
- nginx shared `/api/trading/` and dedicated Trading host `/api/` upstreams
  must point at the active blue-green `app.port` by default; set
  `REQUIRE_NGINX_DEDICATED_API=0` only for non-nginx diagnostics.
- nginx service must be active by default; set `REQUIRE_NGINX_SERVICE=0` only for non-nginx verification environments.
- guarded standalone-only empty-table cleanup through `scripts/schema_extra_tables_cleanup_apply_server.sh`; the script is disabled in shared DB mode and refuses to drop tables unless `SCHEMA_COMPARE_MODE=standalone` plus `APPLY_SCHEMA_EXTRA_TABLE_CLEANUP=1` are explicitly set.

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
