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
- Trading exposes API-key guarded, read-only internal report endpoints for the AgoraMarketAPI Telegram gateway:
  - `GET /api/trading/internal/reports/current`
  - `GET /api/trading/internal/reports/analysis`
  - `GET /api/trading/internal/reports/weekly`
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
- Unused one-shot OKX OI backfill service residue was removed; OI history backfill remains only through the guarded market-data MCP external-backfill path.
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
  - `scripts/smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180` starts the service under `local-smoke`, proves `/api/actuator/health`, calls `/api/mcp` with `getMcpRegistryVersion`, and checks logs for disabled external side effects.
  - Local smoke also calls representative MCP tools and verifies disabled guard responses for live sentiment reads, external health probes, and external backfill/import reads.
  - `scripts/verify_local.ps1` runs compile/tests, split boundary scanners, env-template checks, shell syntax checks, schema source inventory, and documentation drift guards.
  - `scripts/schema_baseline_inventory.ps1` writes `target/schema-baseline/entity-tables.txt`, `implicit-entities.txt`, `forbidden-marketplace-tables.txt`, and `unsafe-table-names.txt`; the latest local guard run found no implicit entity tables, no obvious marketplace-owned table mappings, and no unsafe table names.
  - Runtime side effects that could surprise a split deployment now default off in code and/or the tracked env template, including scheduled market-data writes, startup backfills, attribution startup work, Telegram digests/alerts, market-flip detector/analyzer escalation, Polymarket/WAI, market WebSockets, K-line divergence Telegram alerting, OCO/grid/Earn/trailing-stop automation, ScoreBuy execution/notification paths, short-squeeze/taker-buy alerting, ShortAiFilter external AI/MCP shadow checks, ML shadow inference logging, ML materialized startup refresh, ML protection/autoretrain/digest automation, Gemini advisor, Tiny Live auto-execution, guardian live actions, runtime evidence, and discovery AI suggestions.
  - `scripts/smoke_local_health.ps1` explicitly clears high-risk host env values and passes matching boot args so local smoke cannot inherit accidental trading/deletion/AI automation from the developer or CI environment.
  - `scripts/verify_local.ps1` dynamically scans every `ApplicationRunner`/`CommandLineRunner` and requires async, explicit opt-in startup behavior.
  - Remaining `enabled:true` fallbacks are enforced by `scripts/verify_local.ps1` as a four-item protective/internal allowlist: MCP master-approval probe wait, Telegram noise reduction, enabled-strategy kline data validation, and deterministic regime filtering.
  - Remaining `@DefaultValue("true")` properties are enforced by `scripts/verify_local.ps1` as protective, dry-run, internal diagnostic, or subordinate options behind disabled parent switches.
  - Remaining `@Value` `:true` fallbacks are deliberately limited to protective/internal checks and dry-run flags; `scripts/verify_local.ps1` rejects new default-on `@Value` fallbacks until they are classified or changed to explicit opt-in.
  - Remaining `Environment.getProperty` default-`true` fallbacks are deliberately limited to MCP master-approval protection, ScoreBuy/TinyLive dry-run flags, and post-scout add sub-options behind disabled execution; `scripts/verify_local.ps1` rejects new default-on environment property fallbacks until they are classified or changed to explicit opt-in.
  - Remaining direct `System.getenv().getOrDefault(..., "true")` fallbacks are deliberately limited to `STARTUP_BEAN_TIMING_ENABLED`, an internal startup diagnostic logger with no network, DB, order, or notification side effects.
  - The exact public HTTP allowlist is enforced by `scripts/verify_local.ps1`; retained public paths are limited to OpenAPI docs, actuator probes/metrics with filter gates, rate-limit JSON redirect, and favicon. Trading MCP is internal-only through server-local `/api/mcp`; public dedicated-host `/api/mcp` and shared-host `/api/trading/mcp` must be blocked by nginx.
- Deploy/server scripts now reject stale AgoraMarket dependency routing unless `AGORA_MARKET_BASE_URL` points at `https://agoramarketapi.purrtechllc.com`.
- `deploy.sh` now checks AgoraMarket exchange-rate dependency health before starting the blue-green switch.
- Server preflight now requires AgoraMarket exchange-rate dependency health by default, with `REQUIRE_AGORA_MARKET_HEALTH=0` reserved for diagnostic-only checks.
- Server verification uses the same AgoraMarket exchange-rate dependency health rule: required by default, warning-only only when `REQUIRE_AGORA_MARKET_HEALTH=0` is explicitly set for diagnostics.
- Server verification now requires deploy metadata (`app.commit`, `app.pid`, `app.port`) by default, with `REQUIRE_DEPLOY_METADATA=0` reserved for non-deploy diagnostics.
- Server verification now also requires active per-port pid metadata (`app.pid.<app.port>`) by default and rejects values that do not match `app.pid`.
- Server verification now requires nginx service active by default, with `REQUIRE_NGINX_SERVICE=0` reserved for non-nginx diagnostics.
- Deploy, server preflight, and verification now require hardened schema env values (`SPRING_JPA_HIBERNATE_DDL_AUTO=validate`, `SPRING_FLYWAY_ENABLED=true`, `SPRING_FLYWAY_TABLE=trading_flyway_schema_history`) after the Flyway baseline is added.
- Deploy, server preflight, and verification now also require `AGORA_MARKET_INTERNAL_TIMEOUT_MS=3000`, so AgoraMarket internal API failure stays bounded during split deploys.
- Env-template verification now discovers both required server env keys and fixed-value env guards from deploy/server scripts.
- Env-template verification now also pins the AgoraMarket internal API timeout and local-only default CORS, so deploy prep cannot silently widen browser access or slow exchange-rate dependency failure.
- Deploy keeps the previous blue-green instance and nginx backup when `RUN_POST_DEPLOY_VERIFY=0`, so skipped verification does not drain the last proven instance.
- Deploy now passes its actual app/env/port/AgoraMarket/nginx context into post-deploy server verification instead of letting the verifier fall back to default paths.
- Deploy now also preserves an explicit `RUN_SCHEMA_BASELINE_COMPARE=1` request into post-deploy server verification, so the Flyway-baseline DB compare cannot be silently skipped during acceptance.
- Direct schema-baseline DB compare now rejects missing or empty datasource env keys before querying MySQL.
- Direct schema-baseline DB compare now rejects datasource targets outside the expected shared trading database before querying MySQL.
- Server schema-baseline DB compare now classifies obvious marketplace-owned database tables separately and allows them in `SCHEMA_COMPARE_MODE=shared`.
- Server schema-baseline source inventory now rejects unsafe table names before
  baseline generation can pass them to `mysqldump`.
- Server schema-baseline DB compare now classifies known system tables such as `flyway_schema_history` and `trading_flyway_schema_history` separately; in shared mode, extra database tables are reported for visibility and do not block acceptance.
- Server schema-baseline source and database marketplace-table checks now share one shell pattern to avoid future drift.
- Server schema-baseline DB compare now fails fast when required inventory and comparison tools are unavailable.
- Deploy/preflight now fail fast when `seq` or `tail` is unavailable before blue-green readiness loops or failure-log diagnostics need them.
- Deploy/preflight now also fail fast for core process-launch and post-verify tools (`bash`, `date`, `env`, `grep`, `nohup`, `sleep`).
- Deploy now fail fast checks metadata, log-directory, and cleanup tools (`cat`, `kill`, `mkdir`, `rm`) before blue-green work begins.
- Deploy/nginx installation now fail fast checks nginx file swap and validation tools (`cp`, `mv`, `nginx`) before changing nginx config.
- Server verification now also fails fast when env/metadata parsing tools (`grep`, `tail`, `tr`) are unavailable.
- Bootstrap and nginx path installation now fail fast when their repo/nginx inspection and file-update tools are unavailable.
- Local verification now checks every local-smoke external key and boot-argument clear marker, including exchange secrets, external data-provider keys, warm-up disables, and dry-run guards.
- Package boundary verification now rejects residual marketplace package segments for account/member, PWA, support-ticket, referrer, recharge/withdraw, transaction, Web3, and WalletConnect code.
- Short-squeeze alerting and Binance taker-buy collection now default off in code as well as in the tracked env template and local smoke.
- Market-flip detector now defaults off in code and the tracked env template, so flip event writes and related notifications are production opt-in.
- ML shadow inference logging now defaults off in code and the tracked env template, so live-signal HeatWave prediction lookups and `ml_inference_log` writes are production opt-in.
- ML protection auto-kill now defaults off in code and the tracked env template, so killing stuck HeatWave connections requires `META_CONTROL_ML_PROTECTION_AUTO_KILL_SECONDARY_LOAD=true`.
- DB slow-query monitoring is read-only; unused safe-kill query termination code was removed and is blocked by local verification.
- Disabled legacy SQI startup backfill runner residue was removed; SQI and ShortBuild history remain covered by the gated composite indicator backfill path.
- K-line divergence alerting now defaults off in code and the tracked env template; both manual scan and alert paths require `TRADING_KLINE_DIVERGENCE_ENABLED=true`.
- LongAiFilter now defaults off in code and the tracked env template, so LONG entry guard reads to Fear&Greed/OKX public market endpoints require `TRADING_LONG_AI_FILTER_ENABLED=true`.
- ETF pressure calculation no longer fetches Yahoo Finance data unless `META_CONTROL_ETF_PRESSURE_REFRESH_ENABLED=true`; without a refreshed snapshot it returns no-data output instead of issuing an implicit external read.
- ShortAiFilter now defaults off in code and the tracked env template, so short-signal external AI/MCP shadow checks require `TRADING_SHORT_AI_FILTER_ENABLED=true`.
- Ensemble MCP preview no longer reads live Fear&Greed/OKX/whale/Polymarket inputs unless `TRADING_ENSEMBLE_PREVIEW_LIVE_MARKET_READS_ENABLED=true`.
- Market-data MCP sentiment dashboard, Polymarket risk, Fear&Greed history/backfill, and F&G trade-analysis tools no longer read live Fear&Greed/OKX/whale/Polymarket/orderbook inputs unless `TRADING_MARKET_DATA_MCP_LIVE_SENTIMENT_ENABLED=true`.
- Market-data MCP system health no longer actively pings OKX/Fear&Greed/whale/Polymarket/orderbook endpoints unless `TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED=true`.
- Market-data MCP external backfill/import tools no longer read OKX/The Graph/FRED/Hyperliquid/Polymarket/Coinalyze or write indicator/import rows unless `TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=true`.
- EventRiskControl keeps protective new-entry blocking default-on, while state-change Telegram notifications now default off and require `EVENT_RISK_CONTROL_STATUS_NOTIFY_ENABLED=true`.
- Deploy/nginx scripts fail fast when `systemctl` is unavailable before attempting nginx reloads.
- Local and server verification now prove the trading MCP context path through `/api/mcp`; `/api/trading/mcp` is not a standalone MCP endpoint and remains only a public shared-host block target.
- Legacy AgoraMarketAPI trading HTTP/MCP/scheduler parity inventory is documented
  in `docs/legacy-trading-parity-inventory.md`; standalone carries the trading
  MCP/scheduler classes through `/api/mcp` while intentionally not
  carrying legacy trading admin HTTP controllers as public HTTP.
- Server-local MCP parity smoke coverage now checks representative standalone
  trading tools through `tools/list` in `scripts/smoke_local_health.ps1` and
  the reusable `scripts/smoke_mcp_parity.ps1`.
- Local smoke now also calls the #1 read-only
  `analyzeSpotAntiWickPolicyCoverage` MCP surface and checks its boundary,
  disaster-SL policy, and summary markers before deployed guardrail acceptance.
- The reusable MCP parity smoke now also invokes representative #1/#2/#3
  read-only acceptance surfaces, not just `tools/list`, while keeping calls
  server-local and non-mutating.
- `scripts/smoke_mcp_parity_ssh.ps1` provides the same representative
  read-only MCP parity surface over SSH for deployed server-local `/api/mcp`,
  and the post-deploy issue acceptance wrapper runs it before the focused
  guardrail, signal-correctness, and trailing replay smokes.
- Trailing-stop parity now includes a read-only `analyzeTrailingStopPnlReplay`
  MCP diagnostic, local-smoke registration/call coverage, and
  `scripts/smoke_trailing_stop_pnl_replay_ssh.ps1` for post-deploy 30d replay
  evidence through server-local `/api/mcp`. The script does not modify
  order/OCO/strategy/grid/fund/Earn/Telegram/DB state; `-RequireAcceptance`
  should be used only when the deployed runtime has enough normalized recent
  non-ambiguous backtest rows to prove `acceptance=PASS`. The issue-closure
  default is a 30d/500 sample; smaller limits are diagnostic only. Same-bar
  trigger/stop rows are reported for diagnostics but excluded from PnL
  acceptance totals.
- `diagnoseDataFreshnessGuardBlocks` now distinguishes historical
  DataFreshnessGuard blocks from current source freshness: the read-only MCP
  RCA prints `READY_NOW`, `STALE_NOW`, `NO_DATA_NOW`, or `QUERY_FAILED_NOW`
  per symbol/interval/source and summarizes `staleNowKeys` so operators do not
  mistake recovered historical stale rows for an active collector failure.
- `scripts/smoke_signal_correctness_ssh.ps1` provides a repeatable read-only
  production signal-correctness smoke. It checks strategy execution parity,
  blocked-signal outcome false-kill rates, PASS/BLOCK finalized sample counts,
  DataFreshnessGuard current-source recovery, and the 24h signal-correctness
  dashboard without changing order/OCO/strategy/grid/fund/Earn state. The
  smoke also cross-checks EntryDedup governance with missed-opportunity
  regression so operators can distinguish statistical false-block pressure from
  staged-add live-readiness; relaxation is not considered live-ready when
  staged-add would allow no groups or dedup-too-coarse suspects are absent. It
  now also prints row-level no-buy classifications, blocker-family breakdowns,
  high-return no-buy strategy distribution, and a `getNoBuyReasonTruthTable`
  summary for safer next-action triage. `scripts/test_signal_policy_review_plan.ps1`
  now guards the signal-policy review contract, including read-only tool calls,
  `SIGNAL_POLICY_REVIEW_GAPS` blocker mapping, governance drift documentation,
  missed-opportunity evidence, and the no-buy reason truth table.
- `scripts/audit_live_readiness_ssh.ps1` provides a read-only live-readiness
  audit before any explicitly authorized live enablement. It masks secrets,
  reports order-capable flags, dry-run flags, background automation warnings,
  server-local MCP readiness surfaces, runtime-log smoke, machine-readable
  `readiness_details`, `blocker_classification`, `next_actions`, blockers, and
  a final verdict without changing production env, DB, order, OCO, grid, Earn,
  fund, or Telegram state.
- `scripts/smoke_tiny_live_loss_rca_ssh.ps1` provides a read-only RCA smoke for
  the live-readiness `risk_hard_stop` / consecutive tiny-live loss blocker. It
  calls server-local `/api/mcp` to summarize tiny-live execution readiness,
  auto-approval blockers, recent tiny-live audit rows, autonomous execution
  attribution, missed-opportunity context, and monitor/rollout state over the
  policy-aligned default 30-day window. It prints `hardStopClearCriteria` and
  rollout gates such as `completedTinyLiveSamples`, `falsePositiveCount`,
  `canEnableProduction`, and `canIncreaseDailyCap` without changing production
  env, DB, order, OCO, grid, Earn, fund, Telegram, or live scheduler state.
- `scripts/smoke_runtime_evidence_rca_ssh.ps1` provides a read-only RCA smoke
  for the live-readiness `runtime_evidence_gap`. It calls server-local
  `/api/mcp` to report runtime-evidence env/dashboard state, preview
  `runtimeEvidenceStatus`, recent evidence rows, shadow-intent counts, candidate
  context, and no-buy context, then classifies the gap as `CONFIG_DISABLED`,
  `NO_CANONICAL_ROWS`, `CANONICAL_ROWS_NO_SHADOW_INTENT`,
  `CANONICAL_SHADOW_READY`, or `REVIEW_RUNTIME_EVIDENCE_STATUS` without writing
  RuntimeDecisionEvidence or changing production env, DB, order, OCO, grid,
  Earn, fund, Telegram, or live scheduler state.
- `docs/live-dry-run-evidence-plan.md` records the evidence-only path toward a
  later live proposal. It treats `TRADING_RUNTIME_EVIDENCE_ENABLED=true` as a
  separately authorized candidate only, keeps order-capable, Telegram,
  scheduler, OCO, grid, Earn, fund, guardian live-action, and
  external-backfill/import flags disabled, and requires read-only smokes before
  any future live approval discussion.
- `scripts/smoke_live_background_automation_ssh.ps1` provides a read-only
  server env smoke for already-enabled background automation before live review.
  It reports `background_automation_true`,
  `high_risk_background_automation_true`, classification, recommendation, and
  verdict without changing production env, DB, order, OCO, grid, fund, Earn,
  Telegram, scheduler, or external backfill/import state.
- `docs/live-production-env-review-proposal.md` turns the current read-only
  live blockers into an operator review checklist for a future production env
  proposal. It names the evidence-only runtime evidence candidate, the
  background automation flags that should be disabled or separately justified,
  the order-capable flags that must stay disabled until live approval, and the
  required post-authorization smokes without authorizing env mutation.
- `scripts/smoke_live_readiness_bundle_ssh.ps1` wraps the live-readiness audit,
  background automation smoke, runtime-evidence RCA, tiny-live loss RCA,
  signal-correctness smoke, and MCP parity smoke into one read-only command that
  prints `bundle_blockers` and `bundle_verdict` without changing production env,
  DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, or external
  backfill/import state.
- `docs/live-readiness-blocker-remediation.md` maps each bundle blocker to the
  required read-only evidence, clear condition, and allowed next action, while
  keeping live/order/OCO/grid/Earn/fund/Telegram/exchange/DB/scheduler mutation
  forbidden without a separate live proposal.
- `docs/live-background-automation-env-diff-proposal.md` isolates the env diff
  that would clear `BACKGROUND_AUTOMATION_REVIEW` after separate authorization:
  all currently true background automation flags are proposed false, with
  read-only verification and rollback criteria, without authorizing production
  env mutation.
- `docs/live-runtime-evidence-env-proposal.md` isolates the evidence-only env
  diff for clearing `RUNTIME_EVIDENCE_CONFIG_DISABLED` after separate
  authorization: only `TRADING_RUNTIME_EVIDENCE_ENABLED=true` is proposed, all
  execution/Telegram/scheduler/external-backfill/exchange/OCO/grid/fund/Earn
  mutation paths remain disabled, and `shadowIntentCount > 0` plus
  `orderSentEvidence=0` are required before live review.
- `scripts/verify_local.ps1` now executable-negative-tests the newer live
  readiness SSH wrappers (`smoke_live_background_automation_ssh.ps1`,
  `smoke_runtime_evidence_rca_ssh.ps1`, `smoke_tiny_live_loss_rca_ssh.ps1`,
  `smoke_signal_correctness_ssh.ps1`, and `smoke_live_readiness_bundle_ssh.ps1`)
  so unsafe SSH targets, invalid read-only query windows, or signal-policy
  review contract drift fail locally before any SSH call.
- Latest read-only live-readiness bundle observed on 2026-06-19T09:11+08:00
  against server commit `224f550478b20a329775f503b3eaa70ba6a2f6a8` while
  `origin/main` was `12219d6867ec2761f8a8fcae2a5ad78299523904`: health UP,
  deployed metadata CURRENT, but the server worktree was not at `origin/main`.
  The audit now explicitly prints `riskLevel=R0`, so event-risk baseline
  evidence is present and `EVENT_RISK_NOT_BASELINE` is no longer part of the
  latest bundle blockers.
  Runtime log smoke failed on two Telegram-send related ERROR lines from
  `TelegramServiceImpl` and `ExecutionEventScheduler`. MCP parity passed
  (`toolCount=305 required=35`). All order-capable
  flags were false and dry-run flags were true, but high-risk background
  automation was already true for external backfills, event/execution
  notification scanning, autonomous digest Telegram, and live-signal retry
  notification. Runtime evidence remained disabled
  (`TRADING_RUNTIME_EVIDENCE_ENABLED=EMPTY`,
  `runtimeEvidenceStatus=NOT_READY_ENABLED_FALSE`, `shadowIntentCount=0`,
  `orderSentEvidence=0`). Tiny-live still had the consecutive-loss hard stop
  (`hardStopDetected=true`, `completedTinyLiveSamples=2`,
  `falsePositiveCount=2`, `canEnableProduction=false`). Signal correctness
  found no missed-evaluation/order bug and current DataFreshnessGuard snapshot
  was clean, but 7d governance drift remained TOO_STRICT and missed-opportunity
  regression was WARN. The bundle verdict stayed NOT_READY with blockers
  `LIVE_READINESS_NOT_READY`, `RUNTIME_HEALTH_OR_LOG_NOT_CLEAN`,
  `EXECUTION_ELIGIBILITY_NOT_READY`, `BACKGROUND_AUTOMATION_REVIEW`,
  `RUNTIME_EVIDENCE_CONFIG_DISABLED`, `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`,
  `TINY_LIVE_LOSS_HARD_STOP`, `TINY_LIVE_ROLLOUT_NOT_READY`,
  `SIGNAL_POLICY_REVIEW_GAPS`, and `DEPLOYED_RUNTIME_NOT_CURRENT`. Treat this
  as stale live-review evidence until the server is refreshed to `origin/main`
  by a separately authorized deploy and the bundle is rerun.
- `scripts/smoke_live_readiness_bundle_ssh.ps1` now prints
  `deployment_metadata_status` and adds `DEPLOYED_RUNTIME_NOT_CURRENT` when
  deployed runtime metadata is missing/unknown or runtime files differ from the
  server worktree, so stale runtime evidence cannot be mistaken for current
  live-readiness. It also classifies SSH/read-only command failures such as
  `SSH_AUTH_FAILED`, `SSH_CONNECT_FAILED`, and `SSH_COMMAND_FAILED` before the
  full bundle completes and
  emits `bundle_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE"]` plus
  `bundle_verdict=NO_EVIDENCE`, so rejected keys, connection problems, or a
  failed child smoke cannot be mistaken for a live blocker result.
- `scripts/smoke_guardrail_acceptance_ssh.ps1` provides a focused read-only
  post-deploy acceptance smoke for the BTC spot anti-wick and event-risk
  guardrail handoffs. It calls server-local `/api/mcp` to verify
  `analyzeSpotAntiWickPolicyCoverage` and `getEventRiskControlStatus` boundary,
  policy, and operator-control markers without changing
  order/OCO/strategy/grid/fund/Earn/Telegram/DB state.
- Shared-DB Flyway baseline prep now includes
  `scripts/schema_baseline_generate_server.sh`, which re-runs shared-mode
  compare and dumps reviewable trading entity DDL for `V1__baseline.sql` without
  enabling Flyway or cleaning extra shared tables.

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

Current standalone trading acceptance handoff:

- `docs/split-acceptance-status.md`

Trading deployment prep:

- Trading has a deploy skeleton in `deploy.sh`.
- `scripts/bootstrap_server.sh` can clone/fetch the repo on the server and write a non-secret env template.
- 2026-06-05 server preflight confirmed AgoraMarketAPI is healthy on local port `8080`.
- 2026-06-05 server bootstrap installed `/home/ubuntu/agora-trading-api` and verified fast-forward from `origin/main`.
- 2026-06-05 server bootstrap created `/home/ubuntu/agora-trading-api/.env.trading.secrets.example`.
- 2026-06-05 server configuration created `/home/ubuntu/.env.trading.secrets` without printing secret values.
- 2026-06-05 server configuration created an independent MySQL database for an earlier standalone-DB path. The current target is code split only with shared `agora_market` DB.
- 2026-06-05 server configuration installed nginx `/api/trading/` routing.
- 2026-06-05 observed deployment snapshot used `origin/main` commit `11612b9`; this is historical evidence, not a current-deployment claim.
- 2026-06-05 trading service started on active port `8084`.
- 2026-06-05 `scripts/verify_server.sh` passed with public health check:
  - `https://agoramarketapi.purrtechllc.com/api/trading/actuator/health`
- Production defines `AGORA_MARKET_INTERNAL_API_KEY` in `/home/ubuntu/.env.trading.secrets`, so trading can call AgoraMarket exchange rates and still fall back on timeout or failure.
- 2026-06-13 production deploy advanced `/home/ubuntu/agora-trading-api` to
  the then-current `origin/main` and switched the active blue-green port
  recorded in `app.port`.
- 2026-06-13 post-deploy verification passed: server worktree matched
  `origin/main`, deployed `app.commit` matched `HEAD`, local health passed,
  MCP registry check passed through the then-current legacy context path
  `/api/trading/mcp`, AgoraMarket dependency
  health passed through the stable AgoraMarketAPI nginx vhost, public trading health passed through nginx,
  and nginx service was active.
- 2026-06-13 shared-mode schema compare passed through
  `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh`: 39 source
  entity tables, 0 implicit entity names, 0 forbidden marketplace source
  mappings, 175 database tables, 0 missing trading tables, 136 extra
  marketplace/shared tables expected in shared DB mode.
- 2026-06-13 production MCP parity passed on the active service:
  the then-current legacy context path `/api/trading/mcp` registered 303 tools
  and included the 21 representative trading tools checked by the parity smoke.
- 2026-06-13 read-only validate smoke showed schema validation can start after
  aligning `market_indicator_history.error_flag` mapping with MySQL
  `tinyint(1)`/Boolean semantics.
- 2026-06-13 hardened schema deploy moved production to
  `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`,
  `SPRING_FLYWAY_ENABLED=true`, and
  `SPRING_FLYWAY_TABLE=trading_flyway_schema_history`; Flyway successfully
  created the Trading-owned history table and baselined version `1`.
- 2026-06-13 post-hardening schema compare passed through
  `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh`: 39 source
  entity tables, 0 implicit entity names, 0 forbidden marketplace source
  mappings, 176 database tables, 2 known system tables, 0 missing trading
  tables, and 137 extra marketplace/shared tables expected in shared DB mode.
- 2026-06-14 production deploy advanced `/home/ubuntu/agora-trading-api` to
  `02fd886`, switched the active blue-green port to `8084`, and updated
  `AGORA_MARKET_BASE_URL` to the stable AgoraMarketAPI nginx vhost
  `https://agoramarketapi.purrtechllc.com`.
- 2026-06-14 post-deploy verification passed: server worktree matched
  `origin/main`, deployed `app.commit` matched `HEAD`, local health passed,
  MCP registry check passed through the then-current legacy context path
  `/api/trading/mcp`, AgoraMarket dependency
  health passed through the stable AgoraMarketAPI nginx vhost, public trading
  health passed through nginx, and nginx service was active.
- 2026-06-14 verifier/docs updates advanced the server worktree beyond the
  deployed runtime commit without a runtime deploy. `scripts/verify_server.sh`
  passed because deployed `app.commit` `02fd886` differed from worktree `HEAD`
  only by docs/tooling files; runtime drift still classified as
  `deploy_needed=no`. Treat the latest verifier output, not a stale handoff
  SHA, as the current worktree evidence. The same maintenance pass confirmed
  post-startup WARN/ERROR counts are 0 for the running trading service.
- 2026-06-14 shared-mode schema compare was rerun through
  `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh`: 39 source
  entity tables, 0 implicit entity names, 0 forbidden marketplace source
  mappings, 176 database tables, 2 known system tables, 0 missing trading
  tables, and 137 extra marketplace/shared tables expected in shared DB mode.
- 2026-06-14 historical pre-internal-only production MCP parity smoke passed
  through `/api/trading/mcp` with 303 registered tools and all 21
  representative Trading tools present; this public route is now superseded by the MCP
  internal-only policy.
- 2026-06-14 scheduler-alias deploy advanced the runtime to `6a656fe`,
  switched the active blue-green port to `8085`, and kept
  `AGORA_MARKET_BASE_URL` on the stable AgoraMarketAPI nginx vhost.
- 2026-06-14 post-alias verification passed: server worktree matched
  `origin/main`, deployed `app.commit` matched `HEAD`, local health passed,
  MCP registry check passed through the then-current legacy context path
  `/api/trading/mcp`, AgoraMarket dependency
  health passed, public trading health passed through nginx, schema compare
  passed in shared mode, post-ready WARN/ERROR counts were 0, and direct
  production smoke confirmed the read-only `listSchedulerTasks` compatibility
  alias.
- 2026-06-14 production MCP parity now includes 304 registered tools: the 21
  representative Trading tools plus the read-only `listSchedulerTasks`
  scheduler-list alias.
- 2026-06-14 post-alias local validation passed with
  `.\scripts\verify_local.ps1` and
  `.\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180`; the
  local Spring context registered 304 MCP tools.
- 2026-06-14 maintenance server verification passed after the docs-only
  handoff refresh at `1585942`: deployed `app.commit` remained `6a656fe`,
  the delta was docs/tooling-only, local/public health and local MCP passed,
  AgoraMarket exchange-rate dependency health passed, nginx was active, and
  shared-mode schema compare still found 39 source entity tables, 0 missing
  trading tables, and 137 expected extra marketplace/shared tables.
- 2026-06-14 cross-service live MCP ownership smoke passed from
  `AgoraMarketAPI/tools/codex/check-live-mcp-split-ownership.ps1`:
  AgoraMarketAPI `/api/mcp` exposed 153 marketplace/system/internal tools with
  representative legacy Trading tools absent, while `agora-trading-api`
  exposed 304 tools with representative Trading tools present through the
  then-current legacy context path `/api/trading/mcp`.
- 2026-06-15 nginx added the dedicated Trading API host
  `https://agoratradingapi.purrtechllc.com/api`, mapping public `/api/*` to the
  standalone service's `/api/trading/*` paths on the active port. Smoke showed
  `/api/actuator/health` returned `UP`, `POST /api/mcp` returned 304 Trading
  tools including `previewPositionSizing` and `getTradingManagerDigest`, and
  marketplace `updateCartItem` remained absent from the dedicated Trading host.
- 2026-06-15 production deploy advanced runtime to `1cb9e60` on active port
  `8085`. Post-deploy `scripts/verify_server.sh` passed with server worktree,
  `origin/main`, and deployed `app.commit` all matching `1cb9e60`; dedicated
  host health passed at `https://agoratradingapi.purrtechllc.com/api/actuator/health`.
  During the first deploy, the dedicated host briefly returned 502 because its
  upstream still pointed at the drained old port after the shared
  `/api/trading/` route switched. Commit `1cb9e60` fixed `deploy.sh` and
  `scripts/install_nginx_path.sh` so both shared-host and dedicated-host
  upstreams follow the active blue-green port.
- Server verification now supports `PUBLIC_TRADING_MCP_BLOCKED_URL` and
  `PUBLIC_TRADING_CONTEXT_MCP_BLOCKED_URL`, and deploy passes the dedicated
  `https://agoratradingapi.purrtechllc.com/api/mcp` plus shared
  `https://agoramarketapi.purrtechllc.com/api/trading/mcp` URLs by default
  when nginx is updated. Public Trading MCP must be blocked; server-local
  `/api/mcp` is the operator/verification path; `/api/trading/mcp` remains a
  shared-host public block target.
- 2026-06-15 production deploy advanced runtime to `31af005` on active port
  `8085`. Post-deploy verification and full read-only schema compare passed:
  39 source entity tables, 0 missing DB tables, 176 DB tables, 2 known system
  tables, and 137 extra marketplace/shared tables expected in shared DB mode.
  Historical dedicated-host MCP smoke reported exactly 304 Trading tools before
  Trading MCP was made internal-only.
- 2026-06-15 runtime-log smoke deploy advanced runtime to `7e02307` on active
  port `8084`. The deploy post-verifier passed with shared-mode schema compare,
  dedicated-host health, historical dedicated-host MCP `tools/list` with 304
  Trading tools, and nginx shared/dedicated upstreams on active port `8084`.
  This public MCP route is now superseded by the MCP internal-only policy. The full
  `scripts/verify_split_acceptance_ssh.ps1` pass also checked the active run log:
  0 runtime ERROR lines, WARN lines matching the known baseline, and no
  high-risk trading/OCO/grid/Earn/fund operation-like lines in the recent log
  tail. Runtime log smoke now prints known WARN category counts, so future total
  count movement can be explained by warning class. Cross-service MCP ownership
  smoke reported AgoraMarketAPI 155 tools with representative Trading tools
  absent and `agora-trading-api` 304 Trading tools present.
- 2026-06-16 public Trading MCP was made internal-only in production. Runtime
  commit `5cc6782` is active on port `8084`; deploy post-verification,
  `scripts/verify_server_ssh.ps1 -SchemaCompare`, and
  `scripts/verify_split_acceptance_ssh.ps1` all passed. Public dedicated
  `https://agoratradingapi.purrtechllc.com/api/mcp` and public shared-host
  `https://agoramarketapi.purrtechllc.com/api/trading/mcp` both returned HTTP
  404, while the then-current server-local legacy context path
  `/api/trading/mcp` `getMcpRegistryVersion` check passed before the later
  `/api/mcp` canonical-path deploy.
  Schema compare remained in shared mode with 39 source entity tables, 0
  missing DB tables, and 137 expected extra shared/marketplace tables. Runtime
  log smoke found 0 ERROR lines and no high-risk trading/OCO/grid/Earn/fund
  operation-like lines in the recent tail; cross-service MCP ownership smoke
  reported AgoraMarketAPI 155 tools with representative Trading tools absent
  and `agora-trading-api` 304 Trading tools present.
- 2026-06-16 MCP path deploy advanced production runtime to commit `efff0d2`
  on active port `8085`. Server-local MCP now verifies through `/api/mcp`;
  `/api/trading/mcp` is not a standalone MCP endpoint and remains only a public
  shared-host block target. Public dedicated
  `https://agoratradingapi.purrtechllc.com/api/mcp` and public shared-host
  `https://agoramarketapi.purrtechllc.com/api/trading/mcp` both returned HTTP
  404. Dedicated public health returned `UP` at
  `https://agoratradingapi.purrtechllc.com/api/actuator/health`; strict
  post-drain verification confirmed non-active port `8084` had no listener.
  Shared-mode schema compare found 39 source entity tables, 0 missing DB
  tables, and 137 expected extra shared/marketplace tables. Full split
  acceptance passed with runtime `ERROR` count 0, no high-risk
  trading/OCO/grid/Earn/fund operation-like lines, AgoraMarketAPI 155 tools
  with representative Trading tools absent, and `agora-trading-api` 304 Trading
  tools present through server-local `/api/mcp`.
- Deploy hardening now keeps the nginx Trading route rewrite in
  `scripts/rewrite_nginx_trading_routes.awk` with a local regression fixture
  in `scripts/test_nginx_route_rewrite.ps1`, so nested nginx `location {}`
  blocks cannot cause public dedicated-host MCP to remain proxied. Windows
  deploys should use `scripts/deploy_ssh.ps1` for durable remote
  `logs/deploy` output, and server verification fails if the non-active
  blue-green port still has a listener.
- 2026-06-17 local handoff batch is ahead of the deployed runtime until it is
  explicitly pushed, deployed, and verified. Local `scripts/verify_local.ps1`
  passed with 51 tests, 305 MCP tools registered in the local-smoke Spring
  context,
  split-boundary/schema-inventory/script-syntax/post-deploy-guardrail checks
  OK, and the stale Flyway wording guard now rejects pre-baseline, legacy V10x
  migration, and generic follow-up migration wording in docs/source comments.
  After the signal-correctness parity-list expansion, local
  `scripts/smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180` also passed
  with MCP parity `toolCount=305 required=35` on local `/api/mcp` and local
  health OK.
  The reusable MCP parity smoke has matching local and SSH required-tool lists:
  local smoke invokes `scripts/smoke_mcp_parity.ps1`, the post-deploy issue
  wrapper invokes `scripts/smoke_mcp_parity_ssh.ps1` before the guardrail,
  signal-correctness, and trailing replay smokes, and `scripts/verify_local.ps1`
  fails on any required-tool divergence across the three scripts. H2 local smoke
  executes the H2-compatible read-only parity calls; MySQL-backed governance
  drift/relaxation/tightening diagnostics are executable in the server-local SSH
  parity smoke. This is local readiness only; #1/#2/#3 closure still requires
  deployed server-local read-only acceptance after an explicitly authorized
  deploy.
- 2026-06-18 local verification passed again through the current local handoff
  branch tip; GitHub issues #1/#2/#3 record the exact latest evidence commit.
  `scripts/verify_local.ps1` passed with 51 tests, 305 MCP tools registered in the
  local-smoke Spring context, split-boundary/schema-inventory/script-syntax and
  post-deploy guardrail checks OK. The reviewed shared-DB baseline guard now
  covers both the committed `V1__baseline.sql` and
  `scripts/schema_baseline_generate_server.sh`, so a future guarded baseline
  dump cannot reintroduce pre-review Flyway wording or imply extra-table cleanup.
  The post-deploy issue acceptance wrapper also carries any custom `-EnvFile`
  through split acceptance, server verification, and server-local MCP smokes so
  issue-closure evidence is collected against one consistent runtime
  configuration. Windows SSH wrappers validate `SshHost` locally before
  invoking `ssh`, so deploy and acceptance tooling rejects option-like targets
  before remote execution. The signal-correctness SSH smoke now hard-fails when
  `verifyStrategyExecution` does not provide the expected
  no-missed-evaluation/no-missed-order marker, so issue acceptance cannot pass
  on a missing strategy-execution parity result. A follow-up boundary-only
  check passed with `scripts/verify_split_boundaries.ps1`: 39 explicit entity
  tables, 0 implicit entity names, 0 forbidden marketplace mappings, 0 unsafe
  table names, and env-template coverage for 12 required server keys. Local
  HTTP/MCP smoke also passed with
  `scripts/smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180`, including
  `[mcp-parity] OK http://127.0.0.1:18084/api/mcp toolCount=305 required=35`
  and local `/api/actuator/health` OK.
  The post-deploy issue acceptance wrapper now reserves `CLOSURE_READY OK` for
  the full closure mode only: split acceptance, no-review-gaps guardrail smoke,
  signal-correctness smoke, and hard trailing replay acceptance must all pass.
  This remains local readiness only until pushed, deployed, and verified on the
  server.

## Cleanup Priority

1. Keep `scripts/schema_extra_tables_cleanup_plan_server.sh` and `scripts/schema_extra_tables_cleanup_apply_server.sh` disabled in shared DB mode; extra marketplace/shared tables are expected.
2. Keep the reviewed Flyway baseline under `src/main/resources/db/migration` and add future Trading schema changes as `V2__...` migrations.
3. Keep production on schema validation plus Flyway with the Trading-owned
   `trading_flyway_schema_history` table.
4. Re-run local verify, local smoke, deploy, server verify with schema compare,
   public health, public Trading MCP blocked checks, server-local MCP registry
   smoke, and cross-service live MCP ownership smoke after deploy-affecting
   changes.

## Do Not Do Yet

- Do not share marketplace JPA entities with trading.
- Do not map marketplace entities or repositories in trading code; the DB is shared, but commerce access must still go through explicit internal APIs or SDK contracts.
- Do not add or predefine identity internal API until shared login is required.
- Do not convert every leftover marketplace service into an internal API.
