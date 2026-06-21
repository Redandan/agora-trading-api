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
  missed-opportunity evidence, the no-buy reason truth table, and
  `signal_policy_review_plan` markers (`riskCategory`, `evidenceMarkers`,
  `requiredEvidence`, `notAuthorization`) so signal-policy review contract drift
  fails locally before a server smoke is trusted.
- `scripts/smoke_strategy574_signal_governance_ssh.ps1` provides a focused
  read-only production RCA for the TinyLive strategy 574 near-BUY path. It
  compares 1d/3d/7d/14d governance drift, extracts strategy 574 rows from
  `getMissedOpportunityRegressionReport` and `getNoBuyReasonTruthTable`, checks
  current DataFreshness, TinyLive trigger, and autonomous readiness markers, and
  prints a bounded `policy_change_recommendation` such as
  `DO_NOT_RELAX_ENTRY_DEDUP_OR_DATAFRESHNESS_LIVE` or
  `KEEP_HARD_GATES_AND_OBSERVE_TINY_LIVE_THRESHOLD_CROSS`. The smoke is
  server-local `/api/mcp` only and does not change production env, DB, order,
  OCO, grid, Earn, fund, Telegram, scheduler, exchange, or external
  backfill/import state. `scripts/test_strategy574_signal_governance_smoke.ps1`
  guards the read-only tool calls, no-order markers, window comparison, strategy
  574 row extraction, and non-authorization wording.
- `scripts/prepare_strategy574_signal_review_gate_ssh.ps1` wraps origin-delta
  plus the strategy 574 signal/governance smoke into a read-only gate. It emits
  `deploy_required_before_strategy574_review`,
  `shadow_observation_review_allowed`, `tiny_live_order_allowed=false`,
  `live_policy_change_allowed=false`, `strategy574_review_missing_requirements`,
  and `strategy574_signal_review_gate_status`. The gate can route continued
  read-only observation only; it never authorizes pre-buying, TinyLive order
  execution, EntryDedup/DataFreshness relaxation, deploy, restart, or live
  policy changes.
- `scripts/smoke_strategy485_position_risk_ssh.ps1` provides a focused
  read-only production RCA for SCORE_BUY strategy 485 open-position risk. It
  calls server-local `/api/mcp` to summarize open positions, OCO health,
  position-defense status, active-position EV, TP stretch/aging, stop-sweep
  policy, recent closed trades, execution events, and 3-month PnL, then prints
  `strategy485_position_risk_recommendation` such as
  `REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY`. This smoke is review routing
  only; it does not close positions, modify OCO, change production env, DB,
  order, grid, Earn, fund, Telegram, scheduler, exchange, or external
  backfill/import state. `scripts/test_strategy485_position_risk_smoke.ps1`
  guards the read-only tool calls, no-order/no-OCO markers, risk recommendation
  markers, and non-authorization wording.
- `docs/strategy485-aged-position-review-plan.md` defines the operator packet
  contract for `REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY`: fresh OCO,
  active-position EV, TP stretch, stop-sweep, timeout, recent-closed, and
  monthly PnL evidence are required before review, while close-position and
  OCO-modification actions remain separate explicit-authorized operations.
  `scripts/test_strategy485_aged_position_review_plan.ps1` keeps this plan
  linked to the smoke, profit bundle, and operator docs.
- `scripts/prepare_strategy485_position_review_gate_ssh.ps1` wraps origin-delta
  plus the strategy 485 position-risk smoke into a read-only gate. It emits
  `deploy_required_before_strategy485_review`, `operator_review_packet_allowed`,
  `position_or_oco_mutation_allowed=false`,
  `strategy485_review_missing_requirements`, and
  `strategy485_position_review_gate_status`. The gate can route a separate
  operator review packet only after runtime and evidence stop conditions are
  clear; it never authorizes close-position or OCO modification.
- `scripts/smoke_auto_trading_review_bundle_ssh.ps1` wraps the read-only
  origin-delta classifier, live-authorized audit, strategy 485 position-risk
  smoke, strategy 574 signal/governance smoke, and TinyLive post-trade smoke
  into one review command. It prints `review_items` plus
  `auto_trading_review_recommendation` such as
  `OPERATOR_REVIEW_STRATEGY485_POSITION_RISK` or
  `CONTINUE_TINYLIVE_MONITORING`. The wrapper invokes existing read-only smokes
  only and does not change production env, DB, order, OCO, grid, Earn, fund,
  Telegram, scheduler, exchange, external backfill/import, deploy, restart, or
  nginx state. `scripts/test_auto_trading_review_bundle.ps1` guards the child
  smoke list, output markers, and non-authorization wording.
- `scripts/prepare_auto_trading_review_gate_ssh.ps1` converts the read-only
  auto-trading review bundle into a packet gate with
  `deploy_required_before_auto_trading_review`,
  `operator_review_packet_allowed`, `position_or_oco_mutation_allowed=false`,
  `tiny_live_order_allowed=false`, `live_policy_change_allowed=false`,
  `auto_trading_review_missing_requirements`, and
  `auto_trading_review_gate_status`. The gate can route a separate operator
  packet for read-only position review after runtime currentness is proven, but
  it never authorizes close-position, OCO modification, pre-buying, TinyLive
  order execution, deploy, restart, or live policy changes.
- `scripts/smoke_profit_candidate_review_ssh.ps1` provides a read-only
  production review for risk-adjusted profit-improvement candidates. It calls
  server-local `/api/mcp` for monthly PnL, enabled strategy scorecard,
  ExpectedValueGate stats, signal accuracy, blocked-signal outcomes,
  missed-opportunity/no-buy truth-table evidence, shadow readiness, shadow
  activation candidates, and trailing-stop PnL replay, then prints
  `profit_candidate_items` and `profit_candidate_review_recommendation` such as
  `REVIEW_DATAFRESHNESS_FALSE_KILL_WITH_SHADOW_REPLAY`. The smoke is evidence
  only and does not authorize live trading, policy relaxation, strategy
  activation, closing positions, OCO modification, production env changes, DB,
  order, grid, Earn, fund, Telegram, scheduler, exchange, external
  backfill/import, deploy, restart, or nginx state.
  `scripts/test_profit_candidate_review_smoke.ps1` guards the read-only MCP
  calls, hard-fail boundary checks, output markers, docs coverage, and
  non-authorization wording.
- `scripts/prepare_profit_loss_review_gate_ssh.ps1` converts origin-delta and
  profit-candidate evidence into a loss-source packet gate with
  `deploy_required_before_profit_loss_review`, `loss_source_review_allowed`,
  `live_policy_change_allowed=false`, `position_or_oco_mutation_allowed=false`,
  `tiny_live_order_allowed=false`, `profit_loss_review_missing_requirements`,
  and `profit_loss_review_gate_status`. It can route a separate read-only
  loss-source review packet after runtime currentness is proven, but it never
  authorizes DataFreshness relaxation, close-position, OCO modification,
  pre-buying, TinyLive order execution, deploy, restart, or live policy
  changes.
- `scripts/smoke_post_deploy_profit_validation_ssh.ps1` aggregates the
  auto-trading review gate, profit loss review gate, and profit experiment gate
  after a separately authorized deploy. It emits
  `deploy_required_before_post_deploy_profit_validation`,
  `post_deploy_profit_validation_status`,
  `post_deploy_profit_validation_missing_requirements`,
  `post_deploy_profit_validation_review_plan`,
  `live_policy_change_allowed=false`, `position_or_oco_mutation_allowed=false`,
  and `tiny_live_order_allowed=false` so profit review readiness can be checked
  from one read-only command. `scripts/test_post_deploy_profit_validation.ps1`
  guards the child gate list, safety markers, docs coverage, and local input
  validation.
- `scripts/smoke_data_freshness_false_kill_review_ssh.ps1` provides a focused
  read-only production review for the DataFreshnessGuard false-kill profit
  candidate. It calls server-local `/api/mcp` for short/review/long
  DataFreshnessGuard RCA windows, blocked-signal outcomes, governance drift,
  relaxation candidates, missed-opportunity regression, and no-buy truth-table
  evidence. It prints `currentDataFreshnessClean`, `historicalStaleOnly`,
  `data_freshness_shadow_replay_plan`, and
  `data_freshness_false_kill_recommendation` such as
  `REVIEW_COLLECTOR_CADENCE_SHADOW_REPLAY_KEEP_HARD_GATE`. The smoke is
  evidence only and does not authorize DataFreshnessGuard relaxation, live
  trading, policy changes, strategy activation, closing positions, OCO
  modification, production env changes, DB, order, grid, Earn, fund, Telegram,
  scheduler, exchange, external backfill/import, deploy, restart, or nginx
  state. `scripts/test_data_freshness_false_kill_review_smoke.ps1` guards the
  read-only MCP calls, hard-fail boundary checks, output markers, docs
  coverage, and non-authorization wording.
- `scripts/smoke_data_freshness_executability_review_ssh.ps1` provides a
  focused read-only production review for whether the DataFreshness false-kill
  alpha evidence is also executable evidence. It calls server-local `/api/mcp`
  for the historical DataFreshness decision window, current autonomous
  readiness, runtime evidence rows, TinyLive current preview, and autonomous
  opportunity readiness. It prints `missing_executability_evidence`,
  `counterfactual_required_evidence`, and
  `data_freshness_executability_recommendation` such as
  `ALPHA_NOT_EXECUTABILITY_PROVEN_COLLECT_SHADOW_REPLAY`, so +24h false-kill
  return cannot be mistaken for live-tradable profit without EV/OCO/daily-cap/
  duplicate/exposure/event-risk counterfactual proof. The smoke is evidence
  only and does not authorize DataFreshnessGuard relaxation, live trading,
  policy changes, strategy activation, closing positions, OCO modification,
  production env changes, DB, order, grid, Earn, fund, Telegram, scheduler,
  exchange, external backfill/import, deploy, restart, or nginx state.
  `scripts/test_data_freshness_executability_review_smoke.ps1` guards the
  read-only MCP calls, hard-fail boundary checks, output markers, docs
  coverage, and non-authorization wording.
- `scripts/smoke_data_freshness_counterfactual_review_ssh.ps1` provides the
  next read-only evidence layer after executability review: it uses production
  MySQL `SELECT` queries to inspect DataFreshnessGuard `bt_decision_audit`
  rows, linked `bt_runtime_decision_evidence`, and OKX `md_kline` forward
  windows. It prints replay-input coverage markers including
  `complete_replayable_candidate_rows`, `missing_counterfactual_fields`,
  `positive_forward_24h_rows`, `avg_forward_24h_pct`, and
  `data_freshness_counterfactual_recommendation`. A result such as
  `COUNTERFACTUAL_NOT_REPLAYABLE_CANDIDATE_SNAPSHOT_MISSING` means the
  historical alpha proxy still cannot justify DataFreshness policy relaxation
  because liveSignal/candidate plan/EV/OCO/hard-gate snapshots are missing.
  The smoke is evidence only and does not change production env, DB, order,
  OCO, grid, Earn, fund, Telegram, scheduler, exchange, external
  backfill/import, deploy, restart, or nginx state.
  `scripts/test_data_freshness_counterfactual_review_smoke.ps1` guards the
  direct-SELECT boundary, marker contract, docs coverage, and
  non-authorization wording.
- `docs/data-freshness-shadow-replay-input-plan.md` defines the follow-up
  replay-input contract for DataFreshness false-kill evidence: stable replay
  candidate id, DataFreshness snapshot, candidate entry/TP/SL plan, EV/TQS,
  OCO preflight, duplicate/daily-cap/exposure/event-risk/open-position/loss
  hard-gate snapshots, `orderSent=false`, and stop conditions. It makes clear
  that the current 74-row positive forward-return proxy is not executable
  evidence until replayable candidate snapshots exist, and it blocks any
  DataFreshnessGuard relaxation or live mutation without fresh read-only
  counterfactual evidence. `scripts/test_data_freshness_shadow_replay_input_plan.ps1`
  guards the plan markers and links it to the existing read-only smokes.
- `docs/data-freshness-shadow-replay-collector-design.md` records the current
  code inventory and future collector boundary: L0 returns before candidate,
  EV, OCO, and hard-gate snapshots, so a future collector must be disabled by
  default, evidence-only, keep DataFreshnessGuard terminal, use a stable
  `replayCandidateId`, avoid live signal creation, and never send Telegram,
  place orders, modify OCO, mutate positions, or change scheduler/live policy.
  `scripts/test_data_freshness_shadow_replay_collector_design.ps1` is wired
  into local verification to keep that boundary explicit.
- DataFreshness L0 audit context now writes deterministic `replayCandidateId`
  values (`dfsr1_...`) plus explicit `orderSent=false`, `intentCreated=false`,
  and `ocoPlanCreated=false` markers. This improves future replay traceability
  only; entry/TP/SL/EV/OCO snapshots are still required before any policy review.
- `scripts/smoke_data_freshness_replay_candidate_id_ssh.ps1` is the read-only
  post-deploy verifier for those ids. It reports
  `PENDING_NO_NEW_DATAFRESHNESS_ROWS`, `REPLAY_CANDIDATE_ID_EVIDENCE_OK`, or
  `REPLAY_CANDIDATE_ID_EVIDENCE_INCOMPLETE`, and `-RequireObserved` should only
  be used when a fresh DataFreshnessGuard row is expected. It also reports
  `DEPLOYED_RUNTIME_NOT_CURRENT` when deployed `app.commit` has not reached the
  replay-id runtime.
- `scripts/smoke_data_freshness_replay_observation_bundle_ssh.ps1` combines
  origin-delta, replay-id, and counterfactual smokes into a read-only
  post-deploy observation chain. It routes stale runtime to
  `DEPLOY_CURRENT_RUNTIME_THEN_OBSERVE_REPLAY_ID` and only treats replay-id
  rows as useful after deployed runtime is current.
- `scripts/smoke_profit_improvement_review_bundle_ssh.ps1` wraps the read-only
  origin-delta classifier, profit-candidate review, DataFreshness false-kill
  review, DataFreshness executability review, strategy 485 position-risk smoke,
  strategy 574 signal/governance smoke, and TinyLive post-trade smoke into one
  profit-improvement routing command. It prints
  `profit_improvement_review_items`, `profit_improvement_candidate_scorecard`,
  `top_profit_improvement_candidate`, and
  `profit_improvement_bundle_recommendation` such as
  `COLLECT_DATAFRESHNESS_COUNTERFACTUAL_EVIDENCE`, so DataFreshness alpha
  pressure cannot be reviewed without its executability gap, and strategy 485
  risk plus strategy 574/TinyLive context stay visible. The scorecard ranks
  read-only candidates and required evidence only; it does not authorize live
  mutations. The wrapper invokes existing read-only smokes only and does not
  change production env, DB, order, OCO, grid, Earn, fund, Telegram, scheduler,
  exchange, external backfill/import, deploy, restart, or nginx state.
  `scripts/test_profit_improvement_review_bundle.ps1` guards the child smoke
  list, summary markers, docs coverage, and non-authorization wording.
- `scripts/prepare_profit_experiment_gate_ssh.ps1` wraps the profit-improvement
  bundle into a read-only experiment gate. It emits
  `deploy_required_before_profit_experiment`,
  `shadow_experiment_review_allowed`, `live_policy_change_allowed=false`,
  `profit_experiment_missing_requirements`, and
  `profit_experiment_gate_status`. The gate can route a candidate toward a
  separate shadow-only proposal only after required replay/counterfactual
  evidence is present; it does not authorize deploy, live policy changes,
  position/OCO changes, or order-capable actions.
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
  verdict plus review-plan markers (`riskCategory`, `requiredReview`,
  `requiredEvidence`, `nextAction`, `notAuthorization`) without changing
  production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, or
  external backfill/import state.
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
- Latest recorded current-at-observation read-only live-readiness bundle on
  2026-06-20T20:28+08:00 after the explicitly authorized deploy of
  `ef6253a4ecff7c27a2e709f226e166389700a82d`: server worktree,
  `origin/main`, and deployed `app.commit` all matched that commit, active port
  switched to `8084`, `deployment_metadata_status=CURRENT`,
  `origin_metadata_status=CURRENT_ORIGIN_MAIN`, `metadata_blockers=[]`, and
  `deploy_required_before_live_review=false`. Split/server verification passed
  in shared-DB mode with 39 source entity tables, 176 DB tables, 0 missing
  tables, and 137 expected extra shared tables. Local server MCP
  `/api/mcp` passed, while public dedicated `/api/mcp` and shared-host
  `/api/trading/mcp` remained blocked with 404. The full read-only bundle
  reported runtime log `PASS` with ERROR count 0 and WARN baseline total 13,
  MCP parity `required_tools=[...]`, `missing_required_tools=[]`,
  `toolCount=305 required=35`, `missing_readiness_detail_fields=[]`,
  and `autonomousOpportunity.eligible=false` in `readiness_details`.
  Background automation evidence printed
  `backgroundAutomationClear=false` and
  `background_automation_blockers=["HIGH_RISK_BACKGROUND_AUTOMATION_TRUE", "BACKGROUND_AUTOMATION_TRUE"]`.
  Do not chase docs-only deploy commits by rewriting this attached snapshot
  after every documentation refresh; the currentness source of truth is a
  freshly rerun deployment metadata smoke plus the full live-readiness bundle,
  not the SHA embedded in this progress note.
  The bundle also printed machine-readable `bundle_blocker_summary` entries
  mapping each blocker to a category, required read-only evidence, and next
  action.
  `MCP_AUDIT_TOOL_ERROR`, `DEPLOYED_RUNTIME_NOT_CURRENT`, and
  `RUNTIME_HEALTH_OR_LOG_NOT_CLEAN` are no longer current blockers. The bundle
  still printed `live_review_packet_allowed=false` and `bundle_verdict=NOT_READY`
  with blockers `LIVE_READINESS_NOT_READY`,
  `EXECUTION_ELIGIBILITY_NOT_READY`, `BACKGROUND_AUTOMATION_REVIEW`,
  `RUNTIME_EVIDENCE_CONFIG_DISABLED`, `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`,
  `TINY_LIVE_LOSS_HARD_STOP`, `TINY_LIVE_ROLLOUT_NOT_READY`, and
  `SIGNAL_POLICY_REVIEW_GAPS`. Treat this as the latest recorded blocker set
  for traceability, not as a substitute for rerunning the full read-only bundle
  before any future live-review packet; it is not permission to enable live
  trading.
- 2026-06-21T00:04+08:00 read-only metadata and diagnostic refresh followed
  docs/tooling commit `76b5f00db9e93a249a55f93ff0f87b921ec262bb`. The server
  worktree and deployed `app.commit` still matched
  `ef6253a4ecff7c27a2e709f226e166389700a82d`, while `origin/main` had advanced
  to `76b5f00db9e93a249a55f93ff0f87b921ec262bb`; metadata-only output printed
  `deployment_metadata_status=CURRENT`,
  `origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN`,
  `metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `deploy_required_before_live_review=true`,
  `live_review_packet_allowed=false`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`. The local
  origin-delta classifier still reported docs/tooling-only drift with
  `origin_delta_files=23`, `origin_docs_tooling_delta_files=23`,
  `origin_runtime_delta_files=0`, and `origin_runtime_delta_paths=[]`.
  Diagnostic stale-runtime bundle output still showed the active service
  healthy on port `8084`, local health and server-local `/api/mcp` passing,
  MCP parity `required_tools=[...]`, `missing_required_tools=[]`,
  `[mcp-parity-ssh] OK`, `toolCount=305`, `required=35`, and runtime log
  `PASS` with ERROR count 0 and WARN baseline total 18. The diagnostic bundle
  remained `bundle_verdict=NOT_READY` with blockers
  `LIVE_READINESS_NOT_READY`, `EXECUTION_ELIGIBILITY_NOT_READY`,
  `BACKGROUND_AUTOMATION_REVIEW`, `RUNTIME_EVIDENCE_CONFIG_DISABLED`,
  `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`, `TINY_LIVE_LOSS_HARD_STOP`,
  `TINY_LIVE_ROLLOUT_NOT_READY`, `SIGNAL_POLICY_REVIEW_GAPS`, and
  `DEPLOYED_RUNTIME_NOT_CURRENT`. Order-capable flags were still all false,
  reviewed dry-run flags true, `riskLevel=R0`,
  `missing_readiness_detail_fields=[]`, all nine reviewed background
  automation flags true, runtime evidence disabled with `shadowIntentCount=0`
  and `orderSentEvidence=0`, tiny-live still blocked by the consecutive-loss
  hard stop and rollout gates, `suspiciousNoBuyCount=10`,
  `falseBlockRiskCount=10`, and signal policy remained blocked by
  `TOO_STRICT` governance drift plus `WARN` missed-opportunity regression.
  `scoreBuyPostScoutAdd` was now `executionEligible=true` with state
  `ADD_ON_PULLBACK_READY`, but `scoreBuyPrePosition`,
  `scoreBuyConfirmedDeploy`, and tiny-live remained blocked. This
  is stale-runtime diagnostic/read-only RCA evidence only, not live-readiness
  evidence or live approval.
- 2026-06-20T22:03+08:00 read-only metadata and diagnostic refresh followed
  docs/tooling commit `f84ab1440cc7cc574ca8969203a2ade015dcfce8`. The server
  worktree and deployed `app.commit` still matched
  `ef6253a4ecff7c27a2e709f226e166389700a82d`, while `origin/main` had advanced
  to `f84ab1440cc7cc574ca8969203a2ade015dcfce8`; metadata-only output printed
  `deployment_metadata_status=CURRENT`,
  `origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN`,
  `metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `deploy_required_before_live_review=true`,
  `live_review_packet_allowed=false`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`. The local
  origin-delta classifier still reported docs/tooling-only drift with
  `origin_delta_files=18`, `origin_docs_tooling_delta_files=18`,
  `origin_runtime_delta_files=0`, and `origin_runtime_delta_paths=[]`.
  Diagnostic stale-runtime bundle output still showed the active service
  healthy on port `8084`, local health and server-local `/api/mcp` passing,
  MCP parity `required_tools=[...]`, `missing_required_tools=[]`,
  `toolCount=305`, `required=35`, and runtime log `PASS` with ERROR count 0
  and WARN baseline total 16. The
  diagnostic bundle remained `bundle_verdict=NOT_READY` with blockers
  `LIVE_READINESS_NOT_READY`, `EXECUTION_ELIGIBILITY_NOT_READY`,
  `BACKGROUND_AUTOMATION_REVIEW`, `RUNTIME_EVIDENCE_CONFIG_DISABLED`,
  `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`, `TINY_LIVE_LOSS_HARD_STOP`,
  `TINY_LIVE_ROLLOUT_NOT_READY`, `SIGNAL_POLICY_REVIEW_GAPS`, and
  `DEPLOYED_RUNTIME_NOT_CURRENT`. Order-capable flags were still all false,
  reviewed dry-run flags true, `riskLevel=R0`,
  `missing_readiness_detail_fields=[]`, all nine reviewed background
  automation flags true, runtime evidence disabled with `shadowIntentCount=0`
  and `orderSentEvidence=0`, tiny-live still blocked by the consecutive-loss
  hard stop and rollout gates, and signal policy remained blocked by
  `TOO_STRICT` governance drift plus `WARN` missed-opportunity regression. This
  is stale-runtime diagnostic/RCA evidence only, not live-readiness evidence or
  live approval.
- 2026-06-20T20:53+08:00 read-only metadata and diagnostic refresh followed
  docs/tooling commit `0c033972b4bd39531d0e617d0f2702926108686f`. The server
  worktree and deployed `app.commit` still matched
  `ef6253a4ecff7c27a2e709f226e166389700a82d`, while `origin/main` had advanced
  to `0c033972b4bd39531d0e617d0f2702926108686f`; metadata-only output printed
  `deployment_metadata_status=CURRENT`,
  `origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN`,
  `metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `deploy_required_before_live_review=true`,
  `live_review_packet_allowed=false`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`.
  Read-only stale-runtime diagnostics still showed the service healthy on
  active port `8084`, local health and server-local `/api/mcp` passing, public
  dedicated `/api/mcp` and shared-host `/api/trading/mcp` blocked with 404,
  nginx exact MCP blocks without `proxy_pass`, server-local MCP parity
  `required_tools=[...]`, `missing_required_tools=[]`, `toolCount=305`,
  `required=35`, and runtime log `PASS` with ERROR count 0 and
  WARN baseline total 14. The diagnostic bundle
  remained `bundle_verdict=NOT_READY` with blockers
  `LIVE_READINESS_NOT_READY`, `EXECUTION_ELIGIBILITY_NOT_READY`,
  `BACKGROUND_AUTOMATION_REVIEW`, `RUNTIME_EVIDENCE_CONFIG_DISABLED`,
  `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`, `TINY_LIVE_LOSS_HARD_STOP`,
  `TINY_LIVE_ROLLOUT_NOT_READY`, `SIGNAL_POLICY_REVIEW_GAPS`, and
  `DEPLOYED_RUNTIME_NOT_CURRENT`. Signal correctness remained executable but
  `signalPolicyClear=false` because 7d governance drift was `TOO_STRICT` and
  missed-opportunity regression was `WARN`. This is current stale-runtime
  diagnostic evidence only, not live-readiness evidence or live approval.
- 2026-06-20T21:40+08:00 read-only local origin-delta classifier observed the
  same server worktree commit
  `ef6253a4ecff7c27a2e709f226e166389700a82d` while local `origin/main` was
  `20425dd94eb04edddb5f60fc5eba5facb3c8e456`. It printed
  `origin_delta_local_evidence=true`,
  `origin_delta_status=DOCS_TOOLING_ONLY_DRIFT`, `origin_delta_files=16`,
  `origin_docs_tooling_delta_files=16`, `origin_runtime_delta_files=0`,
  `origin_runtime_delta_paths=[]`, and `live_review_packet_allowed=false`.
  This is routing evidence only: it explains that the current local delta is
  docs/tooling-only, but it does not replace a fresh full read-only
  live-readiness bundle and does not authorize live trading.
- 2026-06-20T21:40+08:00 read-only blocker RCA refresh confirmed the active
  runtime is healthy but still not live-review ready. `audit_live_readiness_ssh`
  reported health `UP`, `runtime_log_status=PASS`, runtime ERROR count 0,
  WARN baseline total 15, `order_capable_flags_true=[]`, all reviewed dry-run
  flags true, `riskLevel=R0`, and `missing_readiness_detail_fields=[]`.
  Background automation stayed blocked with all nine reviewed flags true,
  including high-risk
  `TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED`,
  `EVENT_SCAN_NOTIFICATION_ENABLED`, `EXECUTION_EVENT_ENABLED`,
  `TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED`, and
  `TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED`;
  `backgroundAutomationClear=false` and
  `background_automation_blockers=["HIGH_RISK_BACKGROUND_AUTOMATION_TRUE","BACKGROUND_AUTOMATION_TRUE"]`.
  Runtime evidence remained `diagnosis=CONFIG_DISABLED` with
  `runtimeEvidenceStatus=NOT_READY_ENABLED_FALSE`, `shadowIntentCount=0`, and
  `orderSentEvidence=0`. Tiny-live stayed blocked with
  `hardStopDetected=true`, `autoApprovalMode=BLOCKED`,
  `completedTinyLiveSamples=2`, `falsePositiveCount=2`, and
  `canEnableProduction=false`. Signal correctness found no missed
  evaluation/order bug and current DataFreshnessGuard snapshot was clean, but
  `signalPolicyClear=false` because 7d `governanceMode=TOO_STRICT` and
  missed-opportunity `overallStatus=WARN`. This is read-only RCA evidence only,
  not permission to change env or enable live trading.
- Latest recorded read-only live-readiness bundle observed on
  2026-06-19T12:15+08:00 against server commit
  `224f550478b20a329775f503b3eaa70ba6a2f6a8` while `origin/main` was
  `0eef3ce5c3964e2520c1c5aa16a57e87f0ba26a0`: health UP, deployed metadata
  CURRENT, but the server worktree was not at `origin/main`. The origin hash is
  historical evidence captured at bundle time; later docs or guardrail commits
  can advance `origin/main` without making this recorded snapshot current.
  The audit now explicitly prints `riskLevel=R0`, so event-risk baseline
  evidence is present and `EVENT_RISK_NOT_BASELINE` is no longer part of the
  latest bundle blockers.
  Runtime log smoke failed on two Telegram-send related ERROR lines from
  `TelegramServiceImpl` and `ExecutionEventScheduler`; the deployed runtime
  predates the classified log smoke, so `ERROR category ...` and
  `ERROR rca=TELEGRAM_EXECUTION_EVENT_NOTIFICATION_PATH` must be refreshed
  after the next authorized deploy. MCP parity passed with
  `required_tools=[...]`, `missing_required_tools=[]`, and `toolCount=305
  required=35`. All order-capable
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
  regression was WARN. The bundle printed `live_review_packet_allowed=false`,
  `deploy_required_before_live_review=true`, and verdict NOT_READY with blockers
  `LIVE_READINESS_NOT_READY`, `RUNTIME_HEALTH_OR_LOG_NOT_CLEAN`,
  `EXECUTION_ELIGIBILITY_NOT_READY`, `BACKGROUND_AUTOMATION_REVIEW`,
  `RUNTIME_EVIDENCE_CONFIG_DISABLED`, `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`,
  `TINY_LIVE_LOSS_HARD_STOP`, `TINY_LIVE_ROLLOUT_NOT_READY`,
  `SIGNAL_POLICY_REVIEW_GAPS`, and `DEPLOYED_RUNTIME_NOT_CURRENT`. Treat this
  as stale live-review evidence until the server is refreshed to `origin/main`
  by a separately authorized deploy and the bundle is rerun.
- A recorded read-only deployment metadata refresh on 2026-06-20T09:53+08:00
  observed the server worktree and deployed runtime still at
  `224f550478b20a329775f503b3eaa70ba6a2f6a8`, while `origin/main` had advanced
  to observed origin `4ee52d860fb18f79bd989801c471cd71be5c63d1`. This was
  metadata-only, not a full live-readiness bundle, and only confirms
  `DEPLOYED_RUNTIME_NOT_CURRENT` remains until a separately authorized deploy
  and fresh read-only bundle. The output preserved
  `originMainCommit=<observed origin commit at refresh time>`,
  `metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `deploy_required_before_live_review=true`,
  `live_review_packet_allowed=false`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`. Rerun
  `scripts/smoke_live_deployment_metadata_ssh.ps1` for a current metadata-only
  refresh.
- A historical read-only deployment metadata refresh on 2026-06-20T13:34+08:00
  still observed server worktree and deployed runtime at
  `224f550478b20a329775f503b3eaa70ba6a2f6a8`, while `origin/main` had advanced
  to `873b219171755401c40f3a676fb3c7c9477471ec`. The metadata-only check
  reported `liveBundleOriginStatus=WORKTREE_NOT_ORIGIN_MAIN`,
  `liveBundleDeployStatus=CURRENT`,
  `metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `deploy_required_before_live_review=true`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`. The default
  `scripts/smoke_live_readiness_bundle_ssh.ps1` fail-fast path then stopped on
  stale metadata before child smokes with
  `bundle_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE","DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `live_review_packet_allowed=false`, and `bundle_verdict=NO_EVIDENCE`. This is
  currentness/blocker evidence only, not live-readiness evidence.
- A read-only server runtime sanity check on 2026-06-20T10:04+08:00 passed with
  `scripts/verify_server_ssh.ps1 -SkipGitCurrent`: server preflight, active
  port `8084`, non-active port `8085` drained, local health, server-local
  `/api/mcp`, public dedicated health, public dedicated/shared MCP 404 blocks,
  nginx exact MCP blocks, nginx upstreams, and nginx service were healthy for
  the deployed `224f550478b20a329775f503b3eaa70ba6a2f6a8` runtime. Because git
  currentness was skipped and the server worktree/deployed runtime remains
  behind `origin/main`, this is service-health evidence only, not live-readiness evidence,
  and not a substitute for a separately authorized deploy plus the full read-only
  bundle.
- A read-only server-local MCP parity sanity check on 2026-06-20T10:11+08:00
  passed with `scripts/smoke_mcp_parity_ssh.ps1` on the deployed
  `224f550478b20a329775f503b3eaa70ba6a2f6a8` runtime:
  `required_tools=[...]`, `missing_required_tools=[]`, `toolCount=305`, and
  `required=35` through server-local `/api/mcp`. Because deployment metadata
  remains stale relative to `origin/main`, this is MCP reachability evidence
  only; it does not clear
  `DEPLOYED_RUNTIME_NOT_CURRENT` and is not live-readiness evidence.
- A strict read-only runtime-log smoke on 2026-06-20T10:16+08:00 failed against
  active run log
  `/home/ubuntu/agora-trading-api/logs/runs/app-20260618T070102Z-port8084.log`
  with `runtime ERROR lines present: count=2`: one `TelegramServiceImpl` send
  failure and one `ExecutionEventScheduler` scheduled scan failure. This keeps
  `RUNTIME_HEALTH_OR_LOG_NOT_CLEAN` active for the stale deployed runtime.
  `ALLOW_RUNTIME_ERROR=1` remains diagnostic-only and must not be used as
  live-readiness evidence.
- `scripts/smoke_live_deployment_metadata_ssh.ps1` now provides a reusable
  read-only `DEPLOYMENT_METADATA_ONLY` check for that server-currentness
  question. It is faster than the full bundle but deliberately prints
  `live_review_packet_allowed=false`; metadata-only output is not
  live-readiness evidence.
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
  with MCP parity `required_tools=[...]`, `missing_required_tools=[]`, and
  `toolCount=305 required=35` on local `/api/mcp`; local health was OK.
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
  plus `required_tools=[...]` and `missing_required_tools=[]`; local
  `/api/actuator/health` was OK.
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
