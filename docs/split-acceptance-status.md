# Split Acceptance Status

Last refreshed: 2026-06-23

This file is the current handoff for deciding whether the extracted
`agora-trading-api` service is accepted enough to run as the Trading owner while
AgoraMarketAPI keeps the shared database and internal exchange-rate API.

## Current Architecture

- Code is split into a standalone Trading service.
- DB is not split. Trading and AgoraMarketAPI use the shared `agora_market`
  database.
- Nginx routes Trading through `/api/trading/` on the shared AgoraMarketAPI
  host for compatibility report/API paths and through `/api/` on the dedicated
  `https://agoratradingapi.purrtechllc.com` host.
- Trading MCP is internal-only through server-local `/api/mcp`. Public
  dedicated-host `/api/mcp` and shared-host `/api/trading/mcp` must be blocked
  by nginx with exact `return 404` blocks and no `proxy_pass`.
- Trading must not import AgoraMarketAPI marketplace entities or repositories.
- Trading may call AgoraMarketAPI through the internal-client SDK or internal
  HTTP DTOs for explicit internal APIs such as exchange rates.

## Proven

- The trading service has a deployment directory on the production host under
  `/home/ubuntu/agora-trading-api`.
- `scripts/verify_server.sh` proves:
  - server worktree equals `origin/main`
  - deployed `app.commit` equals the worktree commit, or differs only by
    docs/tooling files that do not require runtime deploy
  - active `app.pid` listens on `app.port`
  - local health works at `/api/actuator/health`
  - local MCP works at `/api/mcp`
  - nginx exposes `/api/trading/`
  - nginx exact MCP blocks return `404` directly with no `proxy_pass`
  - public health works through the dedicated Trading host
    `https://agoratradingapi.purrtechllc.com/api/actuator/health`
  - AgoraMarket dependency health works at
    `https://agoramarketapi.purrtechllc.com/api/actuator/health`

- Local validation is expected to pass with:

  ```powershell
  .\scripts\verify_local.ps1
  .\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180
  ```

- The current local handoff batch is not deployed evidence until it is pushed,
  deployed, and verified on the server. Local verification on 2026-06-18 passed
  through the current local handoff branch tip; GitHub issues #1/#2/#3 record
  the exact latest evidence commit. The validation command was
  `.\scripts\verify_local.ps1`: 51 tests, 0 failures,
  305 MCP tools registered during the local-smoke Spring context,
  split-boundary/schema-inventory/script-syntax/post-deploy-guardrail checks
  OK. This includes the reviewed shared-DB baseline guard and
  `schema_baseline_generate_server.sh` header guard so a future baseline dump
  cannot reintroduce pre-review Flyway wording. It also verifies that a custom
  `-EnvFile` is carried through server verification, split acceptance, and the
  server-local MCP acceptance smokes, that Windows SSH wrappers validate
  `SshHost` locally before invoking `ssh`, and that the production
  signal-correctness smoke hard-fails if `verifyStrategyExecution` does not
  provide the expected no-missed-evaluation/no-missed-order marker. The same
  commit also passed `.\scripts\verify_split_boundaries.ps1`: 39 explicit
  entity tables, 0 implicit entity names, 0 forbidden marketplace mappings,
  0 unsafe table names, and env-template coverage for 12 required server keys.
  Local smoke on 2026-06-18 passed
  `.\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180`, including
  `[mcp-parity] OK http://127.0.0.1:18084/api/mcp toolCount=305 required=35`
  plus the current parity contract requiring `required_tools=[...]` and
  `missing_required_tools=[]`; local `/api/actuator/health` was OK. Treat this
  as local readiness only;
  #1/#2/#3 closure still requires deployed server-local read-only acceptance
  after an explicitly authorized deploy.

- Full read-only live acceptance from Windows/Codex Desktop is expected to pass
  with:

  ```powershell
  .\scripts\verify_split_acceptance_ssh.ps1
  ```

  This wrapper runs Trading server verification with shared-DB schema compare,
  dedicated-host health plus public MCP blocked checks, nginx active-port
  checks, active runtime log smoke, and AgoraMarketAPI's cross-service live MCP
  ownership smoke.

- Current open issue acceptance after an explicitly authorized deploy is
  consolidated by:

  ```powershell
  .\scripts\verify_post_deploy_issue_acceptance_ssh.ps1 -RequireTrailingAcceptance
  ```

  This wrapper runs split acceptance plus the reusable server-local MCP parity
  smoke, the focused #1/#2 guardrail MCP smoke, the read-only
  signal-correctness MCP smoke, and #3 trailing-stop PnL replay smoke through
  server-local read-only calls. If `-EnvFile` is overridden, that same remote
  env file is used for the server verifier, split acceptance, and all
  server-local MCP smokes.
  Use `-RequireTrailingAcceptance` only when the deployed DB sample should prove
  the issue #3 30d PnL target; otherwise the trailing replay is reachability
  evidence only and the wrapper must end with `REACHABILITY_ONLY OK`, not the
  normal issue-acceptance OK. Do not use reachability-only output as #1/#2/#3
  closure evidence. The guardrail smoke is run in no-review-gaps mode, so
  `Operator action: REVIEW_POLICY_GAPS` fails #1/#2 issue acceptance instead of
  being treated as a closure signal. `-SkipSplitAcceptance` is diagnostic-only;
  output collected with that flag is not #1/#2/#3 closure evidence, and it
  cannot be combined with `-RequireTrailingAcceptance`. A diagnostic-only run
  must end with `DIAGNOSTIC_ONLY OK`, not the normal issue-acceptance OK.
  Only the full closure run may end with `CLOSURE_READY OK`, which means split
  acceptance, no-review-gaps guardrail smoke, signal-correctness smoke, and hard
  trailing replay acceptance all passed.

- Current open-issue closure matrix:

  | Issue | Local evidence already covered | Still required before closing |
  | --- | --- | --- |
  | #1 BTC small-TP / wide-disaster-SL guardrail | Risk-sized disaster-SL min-notional behavior, TP/SL asymmetry reporting, same-strategy and same-symbol BTCUSDT LONG exposure blocking, and strict no-review-gaps acceptance scripting are covered by unit/local verification. | A deployed server-local `/api/mcp` guardrail smoke must pass without `REVIEW_POLICY_GAPS`. |
  | #2 event-risk control | R2/R3 new-entry orchestration, MARKET_SIGNAL confluence escalation, config-only operator controls, and read-only MCP status markers are covered locally. | A deployed server-local `/api/mcp` guardrail smoke must prove the read-only event-risk status surface. Phase B OCO/position protection remains future live-promotion scope and is not enabled by this acceptance pass. |
  | #3 trailing stop | The split baseline carries `bt_live_signal` trailing columns; the scheduler is explicit opt-in at 30s, supports dry-run, +0.5 ATR breakeven, +1.0 ATR trailing activation, LONG/SHORT same-bar ambiguity handling, modifyOco retry x3 + alert, and read-only replay/MCP smoke coverage. The split repo uses the reviewed `V1__baseline.sql`; do not generate an extra standalone migration for the current shared-DB gate. ATR is currently a per-position snapshot initialized on first trailing tick; dynamic per-tick ATR recompute is not part of this closure gate unless separately promoted. | A deployed server-local trailing replay must return `sampleStatus=REPLAYED` and `acceptance=PASS` with `-RequireTrailingAcceptance`; no-sample or `NOT_PROVEN` results are reachability only. |

- Shared-DB schema compare is read-only. It proves every trading entity table is
  present in `agora_market`; marketplace/shared extra tables are expected and do
  not block acceptance in `SCHEMA_COMPARE_MODE=shared`.
- Trading MCP DataFreshnessGuard RCA is read-only. After a deploy containing
  the latest diagnostic changes, `diagnoseDataFreshnessGuardBlocks` should show
  current kline snapshot status per symbol/interval/source with
  `READY_NOW`, `STALE_NOW`, `NO_DATA_NOW`, or `QUERY_FAILED_NOW` and a
  `staleNowKeys` summary, so historical stale audit rows can be separated from
  an active collector/source outage.
- Trading MCP trailing-stop PnL replay is read-only. After a deploy containing
  `analyzeTrailingStopPnlReplay`, use
  `.\scripts\smoke_trailing_stop_pnl_replay_ssh.ps1` to call server-local
  `/api/mcp` and prove the boundary marker, the
  `acceptanceTarget: total trailing PnL improvement >= 5%` marker, and sample
  status. The 30d PnL acceptance for issue #3 is only proven when that smoke
  returns `sampleStatus=REPLAYED` and `acceptance=PASS`; the issue-closure
  smoke defaults to a 30d/500 sample, while smaller limits are only narrow
  diagnostics. Local H2 smoke or a no-sample production result is only
  reachability evidence. PnL acceptance
  totals exclude `ambiguousSameBar` rows where trigger/stop ordering cannot be
  proven from OHLC bars. The replay report also prints `acceptanceBlocker` and
  `acceptanceBlockerDetail` so `NOT_PROVEN` output identifies whether closure is
  blocked by all-ambiguous rows, no non-ambiguous rows, zero/missing original
  PnL, current +0.5/+1.0 ATR parameters producing no accepted-row improvement,
  or improvement below the required 5% target. `intervalCode` selects
  normalized backtest trades; `replayIntervalCode` defaults to `1m` and selects
  the K-lines used to resolve intrabar trigger/stop ordering.
- Trading live-readiness audit is read-only. Before any explicit live
  enablement, use `.\scripts\audit_live_readiness_ssh.ps1` to check masked
  server env status, order-capable flags, dry-run flags, server-local MCP
  readiness surfaces, runtime-log smoke, machine-readable `readiness_details`,
  `blocker_classification`, `next_actions`, blockers, and final verdict through
  server-local `/api/mcp`.
  `verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED` is only permission to
  review a separately authorized live-change plan; it does not enable live
  trading.
- Latest read-only profit operator evidence refresh on 2026-06-23T10:09+08:00
  ran `.\scripts\prepare_profit_operator_action_brief_ssh.ps1 -RequireReady`
  through SSH/server-local MCP and saved the fresh matrix to
  `target\profit-review\profit-operator-matrix-20260623T020707Z-BTCUSDT-strategy485.log`.
  It made no production env, DB, order, OCO, grid, fund, Earn, Telegram,
  scheduler, exchange, deploy, restart, or nginx changes. The machine-readable
  result proved the action-brief matrix timeout boundary fix in production
  read-only mode: the nested matrix child used
  `source_matrix_timeout_seconds=3900`, completed with `exitCode=0`,
  `timedOut=false`, and elapsed `882` seconds instead of being killed at the
  inner-child timeout. It returned
  `profit_operator_review_matrix_status=HAS_REVIEW_READY_ITEMS_NOT_LIVE`,
  `profit_operator_action_brief_status=READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE`,
  and primary recommendation
  `REVIEW_EXIT_SIDE_TRAILING_AND_STRATEGY485_NOT_MUTATION`. The ready lane is
  `exit-side`, with review-only proposals `trailing-stop-rollout-review` and
  `strategy485-risk-reduction-review`. Blocked lanes remain `entry-filter`
  (`BLOCKED_GOVERNANCE_MISSED_OPPORTUNITY_REVIEW`,
  `signal_policy_clear=false`, `data_freshness_current_status=NO_CURRENT_SAMPLE`)
  and `data-freshness-replay`
  (`BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`,
  `counterfactual_evidence_class=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`,
  `complete_replayable_candidate_rows=0`, and
  `profit_evidence_watch_reason=NO_CURRENT_SAMPLE`). The fresh
  signal/missed blocker child also completed with exit code 0 and returned
  `signal_missed_blocker_decision_brief_status=BLOCKED_SIGNAL_MISSED_GOVERNANCE_REVIEW`.
  Treat this as read-only operator-review routing only: it is not live approval,
  not deploy approval, and not authorization to enable trailing, close or modify
  positions/OCO, relax EntryDedup/DataFreshness/live policy, or place orders.
- Latest read-only exit-side operator decision brief on 2026-06-23T10:18+08:00
  ran `.\scripts\prepare_exit_side_operator_decision_brief_ssh.ps1 -RequireDecisionReady`
  through SSH/server-local MCP. It made no production env, DB, order, OCO, grid,
  fund, Earn, Telegram, scheduler, exchange, deploy, restart, or nginx changes.
  It returned
  `exit_side_operator_decision_brief_status=READY_FOR_OPERATOR_DECISION_NOT_MUTATION`
  and `exit_side_profit_review_packet_status=READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION`.
  The trailing-stop lane is review-ready with `trailing_stop_acceptance=PASS`,
  `trailing_stop_improvement_pct=54.044%`, `trailing_stop_delta_pnl=12860.69161894`,
  `acceptanceRows=327`, `improved=170`, `worsened=198`, and
  `ambiguousSameBar=113` excluded from acceptance. The strategy 485 risk lane
  is review-ready but non-mutating with `strategy485_oco_health_ok=True`,
  `strategy485_negative_ev_position_count=3`, and
  `strategy485_close_or_modify_suggestion_count=3`; the current read-only
  position summaries are `#148 WATCH/CLOSE evUsdt=-0.30 paperPct=-3.96`,
  `#149 WATCH/CLOSE evUsdt=-0.29 paperPct=-3.90`, and
  `#150 WATCH/CLOSE evUsdt=-0.21 paperPct=-3.53`. The brief explicitly keeps
  entry-filter/DataFreshness policy out of scope and routes those blockers back
  to the profit operator action brief. Treat this as an operator review packet
  only: it is not authorization to enable trailing, change strategy opt-in,
  close positions, modify/cancel OCO, deploy, change production env, or place
  orders.
- Latest recorded current-at-observation read-only live-readiness bundle on
  2026-06-20T20:28+08:00
  followed the explicitly authorized deploy of
  `ef6253a4ecff7c27a2e709f226e166389700a82d`. The server worktree,
  `origin/main`, and deployed `app.commit` all matched that commit, active port
  switched to `8084`, `deployment_metadata_status=CURRENT`,
  `origin_metadata_status=CURRENT_ORIGIN_MAIN`, `metadata_blockers=[]`, and
  `deploy_required_before_live_review=false`. Split/server verification passed
  in shared-DB mode with 39 source entity tables, 176 DB tables, 0 missing
  tables, and 137 expected extra shared tables. Local server MCP `/api/mcp`
  passed, while public dedicated `/api/mcp` and shared-host `/api/trading/mcp`
  remained blocked with 404. The full read-only bundle reported runtime log
  `PASS` with ERROR count 0 and WARN baseline total 13, MCP parity
  `required_tools=[...]`, `missing_required_tools=[]`,
  `toolCount=305 required=35`, `missing_readiness_detail_fields=[]`, and
  `autonomousOpportunity.eligible=false` in `readiness_details`.
  Background automation evidence printed
  `backgroundAutomationClear=false` and
  `background_automation_blockers=["HIGH_RISK_BACKGROUND_AUTOMATION_TRUE", "BACKGROUND_AUTOMATION_TRUE"]`.
  Do not chase docs-only deploy commits by rewriting this attached snapshot
  after every documentation refresh; the currentness source of truth is a
  freshly rerun deployment metadata smoke plus the full live-readiness bundle,
  not the SHA embedded in this handoff.
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
- Latest read-only metadata and diagnostic refresh on 2026-06-21T00:04+08:00
  followed the docs/tooling commit
  `76b5f00db9e93a249a55f93ff0f87b921ec262bb`. The metadata-only smoke observed
  server worktree and deployed `app.commit` still at
  `ef6253a4ecff7c27a2e709f226e166389700a82d`, while `origin/main` had advanced
  to `76b5f00db9e93a249a55f93ff0f87b921ec262bb`. It printed
  `deployment_metadata_status=CURRENT`,
  `origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN`,
  `metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `deploy_required_before_live_review=true`,
  `live_review_packet_allowed=false`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`. The local
  origin-delta classifier still showed docs/tooling-only drift:
  `origin_delta_status=DOCS_TOOLING_ONLY_DRIFT`, `origin_delta_files=23`,
  `origin_docs_tooling_delta_files=23`, `origin_runtime_delta_files=0`, and
  `origin_runtime_delta_paths=[]`.
  Diagnostic stale-runtime bundle output with `-ContinueWhenRuntimeStale`
  confirmed the active service on port `8084` is healthy and reachable:
  health `UP`, runtime log `PASS` with ERROR count 0 and
  WARN baseline total 18, server-local MCP parity `required_tools=[...]`,
  `missing_required_tools=[]`, `[mcp-parity-ssh] OK`, `toolCount=305`, and
  `required=35`. It remained `bundle_verdict=NOT_READY` with blockers
  `LIVE_READINESS_NOT_READY`, `EXECUTION_ELIGIBILITY_NOT_READY`,
  `BACKGROUND_AUTOMATION_REVIEW`, `RUNTIME_EVIDENCE_CONFIG_DISABLED`,
  `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`, `TINY_LIVE_LOSS_HARD_STOP`,
  `TINY_LIVE_ROLLOUT_NOT_READY`, `SIGNAL_POLICY_REVIEW_GAPS`, and
  `DEPLOYED_RUNTIME_NOT_CURRENT`. Current RCA details: all order-capable flags
  stayed false, all reviewed dry-run flags stayed true, `riskLevel=R0`,
  `missing_readiness_detail_fields=[]`, all nine reviewed background
  automation flags were true, runtime evidence was still
  `diagnosis=CONFIG_DISABLED` with `runtimeEvidenceStatus=NOT_READY_ENABLED_FALSE`,
  `shadowIntentCount=0`, and `orderSentEvidence=0`, tiny-live still had
  `hardStopDetected=true`, `completedTinyLiveSamples=2`,
  `falsePositiveCount=2`, `suspiciousNoBuyCount=10`,
  `falseBlockRiskCount=10`, and `canEnableProduction=false`, and signal policy
  still had `signalPolicyClear=false` because 7d governance drift was
  `TOO_STRICT` and missed-opportunity regression was `WARN`. `scoreBuyPostScoutAdd`
  was now `executionEligible=true` with state `ADD_ON_PULLBACK_READY`, but
  `scoreBuyPrePosition` remained blocked by `MAX_LOSS_EXCEEDS_PRE_POSITION_BUDGET`
  and `EXECUTION_POLICY_NOT_READY:BLOCKED`, and confirmed deploy/tiny-live gates
  still blocked review. Treat this as stale-runtime diagnostic/read-only RCA
  evidence only; it is not
  live-readiness evidence and not permission to enable live trading.
- Previous read-only metadata and diagnostic refresh on 2026-06-20T22:03+08:00
  followed the docs/tooling commit
  `f84ab1440cc7cc574ca8969203a2ade015dcfce8`. The metadata-only smoke observed
  server worktree and deployed `app.commit` still at
  `ef6253a4ecff7c27a2e709f226e166389700a82d`, while `origin/main` had advanced
  to `f84ab1440cc7cc574ca8969203a2ade015dcfce8`. It printed
  `deployment_metadata_status=CURRENT`,
  `origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN`,
  `metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `deploy_required_before_live_review=true`,
  `live_review_packet_allowed=false`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`. The local
  origin-delta classifier still showed docs/tooling-only drift:
  `origin_delta_status=DOCS_TOOLING_ONLY_DRIFT`, `origin_delta_files=18`,
  `origin_docs_tooling_delta_files=18`, `origin_runtime_delta_files=0`, and
  `origin_runtime_delta_paths=[]`.
  Diagnostic stale-runtime bundle output with `-ContinueWhenRuntimeStale`
  confirmed the active service on port `8084` is healthy and reachable:
  health `UP`, runtime log `PASS` with ERROR count 0 and
  WARN baseline total 16, server-local MCP parity `required_tools=[...]`,
  `missing_required_tools=[]`, `toolCount=305`, and `required=35`. It
  remained `bundle_verdict=NOT_READY` with blockers
  `LIVE_READINESS_NOT_READY`, `EXECUTION_ELIGIBILITY_NOT_READY`,
  `BACKGROUND_AUTOMATION_REVIEW`, `RUNTIME_EVIDENCE_CONFIG_DISABLED`,
  `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`, `TINY_LIVE_LOSS_HARD_STOP`,
  `TINY_LIVE_ROLLOUT_NOT_READY`, `SIGNAL_POLICY_REVIEW_GAPS`, and
  `DEPLOYED_RUNTIME_NOT_CURRENT`. Current RCA details: all order-capable flags
  stayed false, all reviewed dry-run flags stayed true, `riskLevel=R0`,
  `missing_readiness_detail_fields=[]`, all nine reviewed background
  automation flags were true, runtime evidence was still
  `diagnosis=CONFIG_DISABLED` with `runtimeEvidenceStatus=NOT_READY_ENABLED_FALSE`,
  `shadowIntentCount=0`, and `orderSentEvidence=0`, tiny-live still had
  `hardStopDetected=true`, `completedTinyLiveSamples=2`,
  `falsePositiveCount=2`, and `canEnableProduction=false`, and signal policy
  still had `signalPolicyClear=false` because 7d governance drift was
  `TOO_STRICT` and missed-opportunity regression was `WARN`. Treat this as
  stale-runtime diagnostic/RCA evidence only; it is not live-readiness evidence
  and not permission to enable live trading.
- Previous read-only metadata and diagnostic refresh on 2026-06-20T20:53+08:00
  followed the docs/tooling commit
  `0c033972b4bd39531d0e617d0f2702926108686f`. The metadata-only smoke observed
  server worktree and deployed `app.commit` still at
  `ef6253a4ecff7c27a2e709f226e166389700a82d`, while `origin/main` had advanced
  to `0c033972b4bd39531d0e617d0f2702926108686f`. It printed
  `deployment_metadata_status=CURRENT`,
  `origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN`,
  `metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `deploy_required_before_live_review=true`,
  `live_review_packet_allowed=false`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`.
  Read-only stale-runtime diagnostics with `-SkipGitCurrent` then confirmed the
  service itself was serving on active port `8084`: local health and
  server-local `/api/mcp` passed, public dedicated `/api/mcp` and shared-host
  `/api/trading/mcp` were blocked with 404, nginx exact MCP blocks had no
  `proxy_pass`, and nginx upstreams pointed at the active port. Server-local
  MCP parity passed with `required_tools=[...]`,
  `missing_required_tools=[]`, `toolCount=305`, and `required=35`. The
  diagnostic live-readiness bundle with
  `-ContinueWhenRuntimeStale` reported runtime log `PASS` with ERROR count 0
  and WARN baseline total 14, but remained `bundle_verdict=NOT_READY` with
  blockers `LIVE_READINESS_NOT_READY`, `EXECUTION_ELIGIBILITY_NOT_READY`,
  `BACKGROUND_AUTOMATION_REVIEW`, `RUNTIME_EVIDENCE_CONFIG_DISABLED`,
  `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`, `TINY_LIVE_LOSS_HARD_STOP`,
  `TINY_LIVE_ROLLOUT_NOT_READY`, `SIGNAL_POLICY_REVIEW_GAPS`, and
  `DEPLOYED_RUNTIME_NOT_CURRENT`. Signal correctness remained read-only and
  executable, but `signalPolicyClear=false` because 7d governance drift was
  `TOO_STRICT` and missed-opportunity regression was `WARN`. Treat this refresh
  as current stale-runtime diagnostic evidence only; it is not live-readiness
  evidence and not permission to enable live trading.
- Read-only local origin-delta classifier on 2026-06-20T21:40+08:00 observed
  the same server worktree commit
  `ef6253a4ecff7c27a2e709f226e166389700a82d` while local `origin/main` was
  `20425dd94eb04edddb5f60fc5eba5facb3c8e456`. It printed
  `origin_delta_local_evidence=true`,
  `origin_delta_status=DOCS_TOOLING_ONLY_DRIFT`, `origin_delta_files=16`,
  `origin_docs_tooling_delta_files=16`, `origin_runtime_delta_files=0`,
  `origin_runtime_delta_paths=[]`, and `live_review_packet_allowed=false`.
  This is routing evidence only: it explains that the current local delta is
  docs/tooling-only, but it does not replace a fresh full read-only
  live-readiness bundle and does not authorize live trading.
- Read-only blocker RCA refresh on 2026-06-20T21:40+08:00 confirmed the active
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
- Latest recorded read-only live-readiness bundle on 2026-06-19T14:24+08:00
  observed server worktree/deployed commit
  `224f550478b20a329775f503b3eaa70ba6a2f6a8` while `origin/main` was
  `23d22ce83dcdb1a7780e527787ed68d90296b5b8`. Health was `UP`,
  all order-capable flags were false, and dry-run flags were true. Runtime log
  smoke still failed on the deployed pre-classification runtime with
  Telegram/ExecutionEvent notification errors, and signal-correctness stopped
  because the deployed `verifyStrategyExecution` output did not yet provide the
  expected no-missed-evaluation/no-missed-order marker. The bundle therefore
  ended as incomplete evidence with
  `bundle_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE","DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `live_review_packet_allowed=false`,
  `deploy_required_before_live_review=true`, and `bundle_verdict=NO_EVIDENCE`.
  Treat this as no live-review evidence until a separately authorized deploy
  refreshes the server to `origin/main` and the full read-only bundle is rerun.
  The previous complete blocker snapshot on 2026-06-19T12:15+08:00 observed
  the same server worktree/deployed commit
  `224f550478b20a329775f503b3eaa70ba6a2f6a8` while `origin/main` was
  `0eef3ce5c3964e2520c1c5aa16a57e87f0ba26a0`; it remains historical stale
  live-review evidence for the detailed blocker set until refreshed by a
  complete post-deploy bundle:
  `LIVE_READINESS_NOT_READY`, `RUNTIME_HEALTH_OR_LOG_NOT_CLEAN`,
  `EXECUTION_ELIGIBILITY_NOT_READY`, `BACKGROUND_AUTOMATION_REVIEW`,
  `RUNTIME_EVIDENCE_CONFIG_DISABLED`, `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`,
  `TINY_LIVE_LOSS_HARD_STOP`, `TINY_LIVE_ROLLOUT_NOT_READY`,
  `SIGNAL_POLICY_REVIEW_GAPS`, and `DEPLOYED_RUNTIME_NOT_CURRENT`.
- Recorded read-only deployment metadata refresh on 2026-06-20T09:53+08:00
  observed the server worktree and deployed runtime still at
  `224f550478b20a329775f503b3eaa70ba6a2f6a8`, while `origin/main` had advanced
  to observed origin `4ee52d860fb18f79bd989801c471cd71be5c63d1`. This
  metadata-only refresh did not run a full bundle and is not live-readiness
  evidence; it only confirms the stale-runtime blocker remains until a
  separately authorized deploy and full read-only bundle rerun. The output
  preserved `originMainCommit=<observed origin commit at refresh time>`,
  `metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `deploy_required_before_live_review=true`,
  `live_review_packet_allowed=false`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`. Rerun
  `.\scripts\smoke_live_deployment_metadata_ssh.ps1` for a current
  metadata-only refresh.
- Historical read-only deployment metadata refresh on 2026-06-20T13:34+08:00
  still observed server worktree and deployed runtime at
  `224f550478b20a329775f503b3eaa70ba6a2f6a8`, while `origin/main` had advanced
  to `873b219171755401c40f3a676fb3c7c9477471ec`. The metadata-only check
  reported `liveBundleOriginStatus=WORKTREE_NOT_ORIGIN_MAIN`,
  `liveBundleDeployStatus=CURRENT`,
  `metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `deploy_required_before_live_review=true`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`. The default
  full read-only bundle then failed fast on the same stale metadata before child
  smokes, with
  `bundle_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE","DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `live_review_packet_allowed=false`, and `bundle_verdict=NO_EVIDENCE`.
  This is not live-readiness evidence; it only confirms a separately authorized
  deploy plus full bundle rerun is still required before live review.
- Read-only server runtime sanity on 2026-06-20T10:04+08:00 passed with
  `.\scripts\verify_server_ssh.ps1 -SkipGitCurrent`: preflight passed, deployed
  `app.commit` matched the server worktree at
  `224f550478b20a329775f503b3eaa70ba6a2f6a8`, active port `8084` was listening,
  non-active port `8085` had no listener, local health and server-local
  `/api/mcp` passed, public dedicated health passed, public dedicated
  `/api/mcp` and shared-host `/api/trading/mcp` both returned 404, nginx exact
  MCP blocks had no `proxy_pass`, and nginx was active. Because this check
  explicitly skipped git currentness and the server remains behind
  `origin/main`, it is service-health evidence only, not live-readiness
  evidence and not a substitute for deploy plus the full read-only bundle.
- Read-only server-local MCP parity sanity on 2026-06-20T10:11+08:00 passed
  with `.\scripts\smoke_mcp_parity_ssh.ps1` against
  `http://127.0.0.1:8084/api/mcp`: `required_tools=[...]`,
  `missing_required_tools=[]`, `toolCount=305`, and `required=35`. Because
  deployment metadata still shows the server worktree/deployed runtime behind
  `origin/main`, this is stale runtime MCP reachability evidence only; it does
  not clear
  `DEPLOYED_RUNTIME_NOT_CURRENT` and is not live-readiness evidence.
- Strict read-only runtime-log smoke on 2026-06-20T10:16+08:00 failed against
  the active run log
  `/home/ubuntu/agora-trading-api/logs/runs/app-20260618T070102Z-port8084.log`
  with `runtime ERROR lines present: count=2`: one `TelegramServiceImpl`
  send failure and one `ExecutionEventScheduler` scheduled scan failure. This
  keeps `RUNTIME_HEALTH_OR_LOG_NOT_CLEAN` active for the stale deployed runtime.
  Treat it as blocker RCA only; do not use `ALLOW_RUNTIME_ERROR=1` output as
  live-readiness evidence, and do not review live until a separately authorized
  deploy refreshes the runtime and the strict log smoke passes.
- Live-readiness bundle SSH access failures are not live-readiness evidence.
  If `.\scripts\smoke_live_readiness_bundle_ssh.ps1` reports
  `SSH_AUTH_FAILED`, `SSH_CONNECT_FAILED`, `SSH_COMMAND_FAILED`, or
  `READ_ONLY_SMOKE_FAILED` before the full bundle completes, it must emit
  `LIVE_READINESS_EVIDENCE_UNAVAILABLE`
  with `live_review_packet_allowed=false` and
  `bundle_verdict=NO_EVIDENCE`; fix SSH access, key selection, or the failing
  read-only smoke and rerun the bundle before drawing any server/live
  conclusion.
- Tiny-live loss hard-stop RCA is read-only. When live-readiness classifies
  `risk_hard_stop` or reports
  `AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES`, run
  `.\scripts\smoke_tiny_live_loss_rca_ssh.ps1` to summarize tiny-live
  execution readiness, auto-approval blockers, recent tiny-live audit rows,
  autonomous execution attribution, missed-opportunity context, and
  monitor/rollout state through server-local `/api/mcp`. The default 30-day
  window matches the auto-approval consecutive-loss guard, and the output
  includes `hardStopClearCriteria` plus rollout gates such as
  `completedTinyLiveSamples`, `falsePositiveCount`, `canEnableProduction`, and
  `canIncreaseDailyCap`. This is RCA evidence only; it must not be treated as
  permission to enable live flags.
- Runtime-evidence gap RCA is read-only. When live-readiness classifies
  `runtime_evidence_gap`, `RUNTIME_EVIDENCE_MISSING`, or
  `runtimeEvidenceStatus=NOT_READY_*`, run
  `.\scripts\smoke_runtime_evidence_rca_ssh.ps1` to classify whether the gap is
  disabled collection, no canonical rows, canonical rows without shadow intent,
  canonical shadow-ready, or needs operator review. This smoke calls
  server-local `/api/mcp`, prints recent evidence/candidate context, and must
  not write RuntimeDecisionEvidence or change production state. A
  `CANONICAL_SHADOW_READY` result only clears this one evidence question; it is
  not permission to enable live flags.
- Reusable MCP parity now has both local and SSH coverage paths. Local
  `smoke_local_health.ps1` invokes `smoke_mcp_parity.ps1`; deployed issue
  acceptance invokes `smoke_mcp_parity_ssh.ps1` before the guardrail, signal-correctness, and trailing replay smokes.
  `verify_local.ps1` parses and compares all three required-tool lists so local
  smoke, reusable parity smoke, and server-local SSH parity smoke cannot drift
  silently. Both local and SSH parity smokes require the trailing replay
  acceptance-target marker. H2 local smoke executes only H2-compatible
  read-only parity calls; MySQL-backed governance drift/relaxation/tightening
  diagnostics are executed by the server-local SSH parity smoke.
- Local validation passed on 2026-06-15 after the dedicated-host blue-green
  port-swap fix with:
  - `.\scripts\verify_local.ps1`
  - local Spring context registered 304 MCP tools, matching the deployed
    scheduler-list alias surface
- Production runtime was deployed on 2026-06-15:
  - deployed `app.commit` is runtime commit `31af005`
  - active `app.port` was `8085` and listened with matching per-port
    `app.pid` metadata
  - `AGORA_MARKET_BASE_URL` pointed at the stable AgoraMarketAPI nginx vhost
    `https://agoramarketapi.purrtechllc.com`
  - public health passed through
    `https://agoratradingapi.purrtechllc.com/api/actuator/health`
  - MCP registry check passed through the then-current legacy context path
    `/api/trading/mcp`
  - historical public dedicated-host MCP `tools/list` passed at
    `https://agoratradingapi.purrtechllc.com/api/mcp` with 304 tools,
    representative Trading tools present, and marketplace `updateCartItem`
    absent; this public route is now superseded by the MCP internal-only policy
  - post-deploy server verification passed with server worktree, `origin/main`,
    and deployed `app.commit` all at `31af005`
  - post-ready ERROR count was 0 in the active trading run log; WARN lines were
    the known startup baseline classes documented separately in the deploy
    runbook
  - deploy now updates both shared-host `/api/trading/` and dedicated-host
    `/api/*` nginx upstreams during blue-green port swaps
  - `SPRING_DATASOURCE_URL` database: `agora_market`
  - `META_CONTROL_ML_SQL_SCHEMA`: `agora_market`
  - latest full schema compare was rerun on 2026-06-14 through
    `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh` in
    shared mode after the docs-only handoff refresh at `1585942`
  - source entity tables: 39
  - missing database tables: 0
  - shared database tables: 176
  - database marketplace tables: 5, expected in shared DB mode
  - known system tables: 2, including AgoraMarketAPI's `flyway_schema_history`
    and Trading's `trading_flyway_schema_history`
  - extra database tables: 137, expected in shared DB mode
  - historical pre-internal-only production MCP parity smoke passed against
    `https://agoramarketapi.purrtechllc.com/api/trading/mcp` with 304 tools,
    all 21 representative Trading tools present, and the read-only
    `listSchedulerTasks` compatibility alias smoke returning
    `alias_call_ok=listSchedulerTasks`; this public route is now superseded by
    the MCP internal-only policy, and current parity smoke must use
    server-local MCP
  - cross-service live MCP ownership smoke passed from AgoraMarketAPI's
    `tools/codex/check-live-mcp-split-ownership.ps1`: AgoraMarketAPI
    `/api/mcp` exposed 153 marketplace/system/internal tools with
    representative legacy Trading tools absent, while `agora-trading-api`
    exposed 304 tools with representative Trading tools present through the
    then-current legacy context path `/api/trading/mcp`
  - 2026-06-15 nginx host alias smoke passed for the dedicated Trading host:
    `https://agoratradingapi.purrtechllc.com/api/actuator/health` returned
    `UP`, and `POST https://agoratradingapi.purrtechllc.com/api/mcp`
    returned 304 Trading tools including `previewPositionSizing` and
    `getTradingManagerDigest` while excluding marketplace `updateCartItem`.
  - 2026-06-15 dedicated-host blue-green regression was fixed: an earlier
    deploy switched the shared `/api/trading/` route to the new port while the
    dedicated host still pointed at the drained old port, briefly causing
    dedicated-host 502. Commit `1cb9e60` updated deploy/nginx tooling so both
    host routes follow the active port; commit `31af005` made the dedicated
    host MCP smoke parse the registry exactly.
  - hardened schema env values were active:
    `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`,
    `SPRING_FLYWAY_ENABLED=true`, and
    `SPRING_FLYWAY_TABLE=trading_flyway_schema_history`
  - `trading_flyway_schema_history` was created and baselined at version `1`
- 2026-06-15 runtime-log smoke deploy advanced production to commit `7e02307`
  on active port `8084`. Post-deploy verification passed with shared-mode
  schema compare, dedicated-host health, historical dedicated-host MCP
  `tools/list` reporting 304 Trading tools, and nginx shared/dedicated
  upstreams both pointing at active port `8084`. This public MCP route is now
  superseded by the MCP internal-only policy. The full
  `.\scripts\verify_split_acceptance_ssh.ps1` pass then confirmed:
  - active run log:
    `/home/ubuntu/agora-trading-api/logs/runs/app-20260615T094927Z-port8084.log`
  - runtime `ERROR` count: 0
  - WARN lines matched the known startup/runtime baseline; the runtime log smoke
    prints category counts so future 15/16-style changes can be traced to a
    known warning class instead of just a total count
  - no high-risk trading/OCO/grid/Earn/fund operation-like lines in the recent
    log tail
  - AgoraMarketAPI live MCP exposed 155 marketplace/system/internal tools with
    representative Trading tools absent
  - `agora-trading-api` live MCP exposed 304 Trading tools with representative
    Trading tools present
- 2026-06-15 read-only server verification after local diagnostic/smoke
  improvements confirmed the running service was healthy but not current:
  regular `.\scripts\verify_server_ssh.ps1` failed at git currentness because
  the server worktree was `8419bee` while the checked local head was `5c62887`;
  later docs-only handoff commits may advance `origin/main` without changing
  the deploy requirement.
  Re-running with `-SkipGitCurrent` performed no deploy or production mutation
  and passed active port `8084`, local health, local MCP
  `getMcpRegistryVersion`, public dedicated-host health, historical public
  dedicated-host MCP `tools/list` with 304 tools, and nginx shared/dedicated
  upstream checks. This public MCP route is now superseded by the MCP
  internal-only policy.
  A read-only runtime-log smoke against the active run log then passed with
  runtime `ERROR` count 0, known WARN counts
  `flyway_mysql_version=1`, `startup_bean_timing=11`, `cglib_proxy=2`,
  `open_in_view=1`, `thegraph_optional_key=6`,
  `autonomous_digest_severe=1`, `okx_ws_connection_reset=1`, `unknown=0`,
  and no high-risk
  trading/OCO/grid/Earn/fund operation-like lines in the last 3000 lines.
  Deploy is required before the read-only `verifyStrategyExecution` and
  DataFreshnessGuard RCA smoke improvements are production-current.
- 2026-06-15 production deploy advanced Trading runtime to commit `4636a08`
  on active port `8085`. Post-deploy `deploy.sh` verification passed with
  worktree, `origin/main`, and deployed `app.commit` all matching `4636a08`;
  local health, local MCP `getMcpRegistryVersion`, AgoraMarket dependency
  health, public dedicated-host health, historical public dedicated-host MCP
  `tools/list` with 304 tools, nginx shared/dedicated upstreams, and nginx service checks
  all passed. `.\scripts\verify_server_ssh.ps1 -SchemaCompare` passed after
  deploy: 39 source entity tables, 176 shared database tables, 0 missing
  trading tables, 5 marketplace/shared tables, 2 known system tables, and 137
  extra database tables expected in shared mode. Runtime-log smoke passed on
  `/home/ubuntu/agora-trading-api/logs/runs/app-20260615T155705Z-port8085.log`
  with `ERROR` count 0, WARN baseline counts
  `flyway_mysql_version=1`, `startup_bean_timing=16`, `cglib_proxy=2`,
  `open_in_view=1`, `thegraph_optional_key=0`,
  `autonomous_digest_severe=0`, `okx_ws_connection_reset=0`, `unknown=0`,
  and no high-risk trading/OCO/grid/Earn/fund operation-like lines in the last
  3000 lines. Full `.\scripts\verify_split_acceptance_ssh.ps1` passed,
  including cross-service live MCP ownership: AgoraMarketAPI exposed 155
  marketplace/system/internal tools with representative Trading tools absent,
  and `agora-trading-api` exposed 304 Trading tools with representative Trading
  tools present. Additional production MCP smoke confirmed
  `diagnoseDataFreshnessGuardBlocks` returns the read-only boundary and
  acceptance marker, and `verifyStrategyExecution` returns the read-only
  `no external import/backfill` marker without Binance API failure noise.
- After the 2026-06-16 MCP path tightening, the acceptance expectation is that
  public Trading MCP routes are blocked while server-local `/api/mcp` remains
  callable for SSH/operator verification. `/api/trading/mcp` is not a
  standalone MCP endpoint and must stay blocked on the public shared host.
- 2026-06-16 production deploy advanced Trading runtime to commit `5cc6782`
  on active port `8084`. The first public-MCP-block deploy attempt correctly
  rolled back because the dedicated-host `/api/mcp` route still returned HTTP
  200; commit `5cc6782` fixed the nginx rewrite to track nested server-block
  braces before replacing the dedicated Trading MCP location. The successful
  deploy post-verifier then confirmed:
  - local health passed at `http://127.0.0.1:8084/api/trading/actuator/health`
  - server-local MCP `getMcpRegistryVersion` passed at the then-current legacy
    context path `/api/trading/mcp`; current acceptance uses `/api/mcp`
  - public dedicated Trading MCP
    `https://agoratradingapi.purrtechllc.com/api/mcp` returned HTTP 404
  - public shared-host Trading MCP
    `https://agoramarketapi.purrtechllc.com/api/trading/mcp` returned HTTP 404
  - nginx shared/dedicated Trading upstreams pointed at active port `8084`
    while public MCP was blocked
  - only active port `8084` remained listening after deploy drain
- 2026-06-16 `.\scripts\verify_server_ssh.ps1 -SchemaCompare` passed against
  deployed commit `5cc6782`: 39 source entity tables, 176 shared database
  tables, 0 missing trading tables, 5 marketplace/shared tables, 2 known
  system tables, and 137 extra database tables expected in shared mode.
- 2026-06-16 full `.\scripts\verify_split_acceptance_ssh.ps1` passed:
  runtime log smoke on
  `/home/ubuntu/agora-trading-api/logs/runs/app-20260616T013714Z-port8084.log`
  found runtime `ERROR` count 0, WARN lines matching the known baseline, and
  no high-risk trading/OCO/grid/Earn/fund operation-like lines in the last
  3000 lines. Cross-service live MCP ownership smoke reported AgoraMarketAPI
  155 marketplace/system/internal tools with representative Trading tools
  absent, and `agora-trading-api` 304 Trading tools with representative
  Trading tools present.
- 2026-06-16 MCP path deploy advanced Trading runtime to commit `efff0d2` on
  active port `8085`. Deploy first rolled back safely when strict verification
  rejected the still-running old blue-green port before drain; commit `efff0d2`
  split deploy verification into pre-drain and post-drain phases. The
  successful deploy confirmed:
  - local health passed at `http://127.0.0.1:8085/api/actuator/health`
  - server-local MCP `getMcpRegistryVersion` passed at `/api/mcp`
  - public dedicated Trading MCP
    `https://agoratradingapi.purrtechllc.com/api/mcp` returned HTTP 404
  - public shared-host Trading MCP
    `https://agoramarketapi.purrtechllc.com/api/trading/mcp` returned HTTP 404
  - nginx shared/dedicated Trading upstreams pointed at active port `8085`
    while public MCP was blocked
  - non-active blue-green port `8084` had no listener after drain
- 2026-06-16 `.\scripts\verify_server_ssh.ps1 -SchemaCompare` passed against
  deployed commit `efff0d2`: 39 source entity tables, 176 shared database
  tables, 0 missing trading tables, 5 marketplace/shared tables, 2 known
  system tables, and 137 extra database tables expected in shared mode.
- 2026-06-16 full `.\scripts\verify_split_acceptance_ssh.ps1` passed against
  deployed commit `efff0d2`: runtime log smoke on
  `/home/ubuntu/agora-trading-api/logs/runs/app-20260616T025852Z-port8085.log`
  found runtime `ERROR` count 0, WARN lines matching the known baseline, and
  no high-risk trading/OCO/grid/Earn/fund operation-like lines in the last
  3000 lines. Cross-service live MCP ownership smoke reported AgoraMarketAPI
  155 marketplace/system/internal tools with representative Trading tools
  absent, and `agora-trading-api` 304 Trading tools with representative
  Trading tools present through server-local `/api/mcp`.

## Schema Hardening

Trading schema management is deployed in hardened mode with:

- `src/main/resources/db/migration/V1__baseline.sql`
- `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`
- `SPRING_FLYWAY_ENABLED=true`
- `SPRING_FLYWAY_TABLE=trading_flyway_schema_history`
- `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`

The Trading-owned Flyway table avoids mixing with AgoraMarketAPI's existing
`flyway_schema_history` rows in the shared database.

## Required Next Step

The trading service is deployed, verified, and ready for separate Trading-side
development. The schema baseline hardening step is complete. The next
Trading-side work is:

1. Keep AgoraMarketAPI internal exchange-rate APIs available.
2. Add future Trading schema changes as `V2__...` Flyway migrations under
   `src/main/resources/db/migration`.
3. Re-run local verify, local smoke, server verify with schema compare, public
   health, MCP registry smoke, and cross-service live MCP ownership smoke after
   any deploy-affecting change.

## Cutover Boundary

Shared-DB schema compare and Trading deployment acceptance have passed. Keep
these boundaries:

1. Keep order/OCO/grid/fund/Earn-capable jobs running in exactly one service.
2. Keep AgoraMarketAPI internal exchange-rate APIs available.
3. Use cross-service live MCP ownership smoke when validating the live boundary,
   not Trading parity smoke alone.
4. Monitor logs for duplicate scheduler execution, SQL errors, MCP auth errors,
   and nginx `/api/trading/` or dedicated-host `/api/` routing failures.

## Do Not Do

- Do not run extra-table cleanup in shared DB mode; marketplace/shared tables
  are expected in `agora_market`.
- Do not remove AgoraMarketAPI internal exchange-rate endpoints; trading still
  depends on them.
- Do not treat public health alone as split acceptance.
