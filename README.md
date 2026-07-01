# agora-trading-api

Standalone Trading service extracted from AgoraMarketAPI.

## Local run

Compile/test-only verification:

```powershell
.\scripts\verify_local.ps1
```

HTTP startup smoke test with an in-memory local database:

```powershell
.\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180
```

Run against a real configured database:

```powershell
mvn spring-boot:run
```

Health check:

```powershell
curl http://localhost:8084/api/actuator/health
```

AgoraMarket exchange-rate integration:

- Configure `AGORA_MARKET_BASE_URL=https://agoramarketapi.purrtechllc.com`, `AGORA_MARKET_INTERNAL_TIMEOUT_MS=3000`, and `AGORA_MARKET_INTERNAL_API_KEY` to call AgoraMarket internal API in production.
- Leave `AGORA_MARKET_INTERNAL_API_KEY` blank for local static fallback.
- Install the provider SDK first when building from a fresh machine:

```powershell
mvn -f C:\Users\Redan\IdeaProjects\AgoraMarketAPI\internal-client\pom.xml install
```

AgoraMarketAPI Telegram gateway integration:

- `GET /api/trading/internal/reports/current`
- `GET /api/trading/internal/reports/analysis`
- `GET /api/trading/internal/reports/weekly`
- Header: `X-Internal-Api-Key`
- Configure `TRADING_INTERNAL_API_KEY` for an independent inbound key, or leave it unset to reuse `AGORA_MARKET_INTERNAL_API_KEY` during the split.

## Initial boundaries

- Owns trading strategy, OCO/grid, signal, market data, backtest, trading diagnostics, and trading MCP.
- Does not depend on AgoraMarket commerce users, orders, products, or wallet tables.
- Current baseline keeps the extracted trading/system repositories needed for the Spring context to start.
- Cross-service dependencies must go through an internal-client SDK or HTTP DTOs, not shared entities/repositories.
- Public HTTP surface is intentionally narrow: OpenAPI docs, actuator probes, rate-limit JSON redirect, API-key guarded internal report reads for the AgoraMarketAPI Telegram gateway, and the dedicated Trading MCP endpoint. Public dedicated-host `/api/mcp` is reachable only with MCP bearer auth; shared-host `/api/trading/mcp` remains blocked by nginx.
- Schema baseline prep remains read-only against the shared `agora_market` database; marketplace-owned table names are rejected in trading source mappings, while shared DB extra tables are expected.

See:

- [AGENTS.md](AGENTS.md)
- [SERVICE_BOUNDARY.md](SERVICE_BOUNDARY.md)
- [INTERNAL_API_TODO.md](INTERNAL_API_TODO.md)
- [SPLIT_PROGRESS.md](SPLIT_PROGRESS.md)
- [docs/deploy-runbook.md](docs/deploy-runbook.md)
- [docs/schema-baseline.md](docs/schema-baseline.md)
- [docs/legacy-trading-parity-inventory.md](docs/legacy-trading-parity-inventory.md)

Server verification after deploy:

```bash
bash scripts/verify_server.sh
```

From Windows, run the same server-side verifier through SSH so Linux-only
tools such as `lsof` are checked on the server instead of the workstation:

```powershell
.\scripts\verify_server_ssh.ps1 -SchemaCompare
```

Full read-only split acceptance from Windows, including cross-service live MCP
ownership smoke and active runtime log smoke:

```powershell
.\scripts\verify_split_acceptance_ssh.ps1
```

Post-deploy open-issue acceptance wrapper for the current #1/#2/#3 trading
guardrail handoffs:

```powershell
.\scripts\verify_post_deploy_issue_acceptance_ssh.ps1 -RequireTrailingAcceptance
```

This wrapper runs split acceptance, the reusable server-local MCP parity smoke,
the #1/#2 guardrail MCP smoke, the read-only signal-correctness MCP smoke, and
the #3 trailing-stop replay smoke through server-local read-only calls.
Windows SSH wrappers validate `SshHost` locally and reject unsupported SSH
target syntax before invoking `ssh`, so acceptance tooling cannot be redirected
through option-like targets.
If `-EnvFile` is overridden, the same remote env file is passed through server
verification, split acceptance, and every server-local MCP smoke so the closure
command verifies one consistent runtime configuration.
Omit
`-RequireTrailingAcceptance` only when collecting reachability evidence before
the deployed DB sample is expected to satisfy the 30d PnL target. The wrapper
then ends with `REACHABILITY_ONLY OK`, not the normal issue-acceptance OK.
Do not use that output as #1/#2/#3 closure evidence. The wrapper also fails
#1/#2 acceptance if anti-wick coverage returns
`Operator action: REVIEW_POLICY_GAPS`.
`-SkipSplitAcceptance` is diagnostic-only; output collected with that flag is
not #1/#2/#3 closure evidence, and it cannot be combined with
`-RequireTrailingAcceptance`. A diagnostic-only run must end with
`DIAGNOSTIC_ONLY OK`, not the normal issue-acceptance OK.
Only the full closure run may end with `CLOSURE_READY OK`, which means split
acceptance, no-review-gaps guardrail smoke, signal-correctness smoke, and hard
trailing replay acceptance all passed.

Local verification does not prove production currentness. Treat production as current only after an explicit deploy and server verification pass.
When nginx is updated, deploy also verifies dedicated Trading host health at
`https://agoratradingapi.purrtechllc.com/api`, verifies authenticated public
Trading MCP at `https://agoratradingapi.purrtechllc.com/api/mcp`, and keeps the
shared-host legacy `/api/trading/mcp` route blocked.

Server-local MCP parity smoke against a running local or deployed Trading
service:

```powershell
.\scripts\smoke_mcp_parity.ps1 -BaseUrl http://127.0.0.1:18084/api -McpKey local-smoke-mcp
.\scripts\smoke_mcp_parity_ssh.ps1
```

Both parity smokes print `required_tools=[...]` plus
`missing_required_tools=[]` when the representative read-only Trading MCP
surface is complete. Missing either list, or a non-empty missing list, is not
live-readiness evidence.

Read-only Grid trend adjustment review smoke after a deploy that contains the
`getGridTrendAdjustmentReview` MCP tool:

```powershell
.\scripts\smoke_grid_trend_adjustment_review_ssh.ps1
```

This calls server-local `/api/mcp` only and verifies the Grid trend review
packet carries `boundary=READ_ONLY`, `mutationAllowed=false`,
`orderAllowed=false`, `gridMutationAllowed=false`, `trend1h=`, `trend4h=`,
`trendAlignment=`, `decisionSet=KEEP,PAUSE,WATCH,REBUILD_REVIEW,RESIZE_REVIEW`,
`automationAllowed=false`, and `recommendation=`. It is operator evidence only:
it does not create, pause, resume, close, or rebuild a grid, does not place
orders, does not send Telegram, and does not change scheduler, DB, fund, Earn,
OCO, or exchange state. Any scheduler integration or `closeGrid`/`createGrid`
action requires separate explicit approval.
The SSH wrapper owns only remote env/app-port handling; the JSON-RPC call,
marker validation, and packet rendering are owned by the Java smoke runner
`com.agora.trading.smoke.McpSmokeCli` so the smoke logic is unit-testable.

Read-only Grid resize/rebuild operator packet after the trend review recommends
`RESIZE_REVIEW` or `REBUILD_REVIEW`:

```powershell
.\scripts\prepare_grid_resize_rebuild_operator_packet_ssh.ps1
```

This calls server-local `/api/mcp` only and combines
`getGridTrendAdjustmentReview`, `listGrids`, `getGridPriceAlignment`,
`getCurrentExposure`, and `getEventRiskControlStatus`. It emits
`GRID_RESIZE_REBUILD_OPERATOR_PACKET`, `grid_resize_rebuild_operator_status`,
`grid_resize_rebuild_candidate_grid_ids`, capital/range deltas, event-risk
gate evidence, and per-grid candidate plans. `READY_FOR_GRID_RESIZE_REBUILD_OPERATOR_REVIEW_NOT_MUTATION`
means the evidence is ready for a separate operator decision only; it keeps
`production_env_change_allowed=false`, `deploy_allowed=false`,
`close_grid_allowed=false`, `create_grid_allowed=false`,
`grid_mutation_allowed=false`, `scheduler_enablement_allowed=false`,
`order_allowed=false`, `oco_mutation_allowed=false`, and
`telegram_send_allowed=false`.

Read-only Grid open readiness packet before any separate grid enablement review:

```powershell
.\scripts\prepare_grid_open_readiness_packet_ssh.ps1
```

This calls server-local `/api/mcp` only and emits `GRID_OPEN_READINESS_PACKET`,
`grid_open_readiness_status`, blockers, required evidence, trend/ATR evidence,
event-risk status, historical grid state, exposure/alignment context, and grid
runtime env flags. `BLOCKED_GRID_OPEN_READINESS_NOT_MUTATION` means grid opening
must stay blocked. `READY_FOR_GRID_OPEN_OPERATOR_REVIEW_NOT_MUTATION` means the
evidence packet is ready for a separate operator review only; it does not enable
grid, create/pause/resume/close/rebalance grids, place orders, send Telegram,
change scheduler/env/deploy state, or mutate DB/OCO/grid/fund/Earn/exchange
state.
The packet also emits `grid_candidate_plan` from read-only `md_kline` replay:
entry reference price, lower/upper range, grid count, per-level capital, stop
bounds, replay rows, inside/stop-break counts, and `replayScore`. A complete
candidate plan can clear the "missing explicit plan" evidence gap, but it is
not `createGrid` input authorization and does not override trend, event-risk,
historical failure, OKX, scheduler, or live-trading blockers.
Replay-local trend markers such as `candidateTrendRegimeReviewRequired`,
`candidateTrendRegimeSource=md_kline_replay`, and
`GRID_CANDIDATE_PLAN_TREND_REVIEW_REQUIRED_NOT_OPEN_APPROVAL` describe the
candidate replay context only. They are intentionally separate from the MCP
`trendGate`; a fresh `trendGate=CLEAR_TREND_REGIME` means a separate
trend-regime override is not required unless the gate becomes blocked again.
Historical `SELL_FAILED` rows are classified as material vs dust-only. A
dust-only stale sell failure is emitted as review evidence and does not block
grid opening by itself; any material historical `SELL_FAILED` remains a blocker
until reconciled.
The packet also emits `grid_open_gate_review` and
`grid_open_operator_authorization_required`, separating trend-regime,
event-risk, and OKX-env gates from the later operator actions needed to open a
grid. Override entries are review inputs only; they do not authorize env
changes, `createGrid`, scheduler enablement, orders, Telegram sends, or DB
mutation.

Read-only Grid open operator packet:

```powershell
.\scripts\prepare_grid_open_operator_packet_ssh.ps1
```

This wraps the readiness packet into `GRID_OPEN_OPERATOR_PACKET` and emits
`grid_open_operator_packet`, `grid_open_operator_status`, gate statuses,
missing requirements, proposed separate env diff, reviewed `createGrid` inputs,
`trendOverrideRiskEnvelope`, `eventRiskOverrideRiskEnvelope`,
`combinedOverrideRiskEnvelope`, `okxGridEnvPreflightEnvelope`, and
post-authorization verification steps.
`BLOCKED_GRID_OPEN_OPERATOR_REVIEW_NOT_MUTATION`
means the grid must remain closed until the trend/event-risk/OKX gates clear or
receive separate written override/authorization. The packet is still read-only:
it does not change production env, call `createGrid`, enable scheduler/recovery,
place orders, modify OCO, send Telegram, deploy, restart, or mutate
DB/grid/fund/Earn/exchange state.
The OKX/grid env preflight envelope reports whether masked OKX credentials are
present, whether `TRADING_OKX_ENABLED` and `TRADING_GRID_ENABLED` still need a
separate production env diff, and whether scheduler/recovery/Earn flags remain
off for the initial open review. `ENV_PREFLIGHT_READY_NOT_GRID_APPROVAL` is
still only an env-review state, not permission to open a grid.
The trend override envelope is decision support only: it reports risk grade,
risk points, replay score, stop-break count, a recommended capital cap, and
required override conditions, but it does not clear the trend gate by itself.
The event-risk override envelope does the same for `riskLevel`; `R3` is marked
not recommended for override, and every non-R0 review requires a fresh
`getEventRiskControlStatus` check immediately before any later `createGrid`
request.
The combined envelope reports the effective review capital cap by taking the
more conservative cap across trend and event-risk envelopes, plus the separate
written override/env/createGrid documents still required.

Read-only Grid candidate parameter sweep:

```powershell
.\scripts\prepare_grid_candidate_parameter_sweep_ssh.ps1
```

This invokes only `prepare_grid_open_operator_packet_ssh.ps1` across bounded
`GridCount`, `PerLevelUsdt`, `StopOutPct`, and `CandidateHalfWidthPct`
combinations. It emits
`GRID_CANDIDATE_PARAMETER_SWEEP_PACKET`,
`grid_candidate_parameter_sweep_rows`,
`grid_candidate_parameter_sweep_best_candidate`,
`grid_candidate_parameter_sweep_best_quality_candidate`,
`grid_candidate_parameter_sweep_remaining_blockers`, and
`grid_candidate_parameter_sweep_status`. A best candidate requires replay score
at least 70, zero replay stop-break rows, a complete candidate plan, and
capital within the effective review cap. `CandidateHalfWidthPct=0` preserves
the existing ATR/trend-derived width; explicit values are evidence-only
candidate range tests. `gridCount=2` is allowed only as a micro-grid
read-only review lane because runtime `createGrid` already supports
`gridCount(2-50)`; it is not createGrid authorization. A best quality candidate
means the replay blocker can clear, but trend override, capital cap, env, and
createGrid authorization can still remain blocked. The sweep is read-only; it
does not change env, deploy,
call `createGrid`, enable grid/scheduler/recovery, place orders, modify OCO,
send Telegram, or mutate DB/grid/fund/Earn/exchange state.

Read-only Grid trend-clearance watch packet:

```powershell
.\scripts\prepare_grid_trend_clearance_watch_packet_ssh.ps1
```

This consumes the grid open operator packet and emits
`GRID_TREND_CLEARANCE_WATCH_PACKET`, `grid_trend_clearance_watch_status`,
`trendDistanceToSidewaysPct`, the direction needed to reach the SIDEWAYS gate,
override capital caps, clearance criteria, abort criteria, and the next
verification steps. `WATCH_TREND_CLEARANCE_PENDING_NOT_MUTATION` keeps grid
opening blocked until a fresh operator packet shows the trend gate clear or a
separate written trend override exists. It is read-only and does not clear the
trend gate, change env, call `createGrid`, enable grid/scheduler/recovery,
place orders, modify OCO, send Telegram, deploy, restart, or mutate
DB/grid/fund/Earn/exchange state.

Read-only Grid MCP tool coverage packet:

```powershell
.\scripts\prepare_grid_mcp_tool_coverage_packet_ssh.ps1
```

This calls server-local MCP `tools/list` only and emits
`GRID_MCP_TOOL_COVERAGE_PACKET`, `grid_mcp_tool_coverage_status`,
`readOnlyReviewTools`, `futureActionToolsPresentButNotInvoked`, and
`boundaryContextTools`. It proves grid review, future grid action, OCO, Earn,
fund, and scheduler boundary tools are registered before any separate grid-open
review. Future action tools such as `createGrid`, `pauseGrid`, `resumeGrid`,
`closeGrid`, and `enableGridAutoRebalance` are presence evidence only; the
script does not invoke them and does not authorize their use.

Read-only Grid open decision snapshot:

```powershell
.\scripts\prepare_grid_open_decision_snapshot_ssh.ps1
```

This combines the trend-clearance watch and MCP tool coverage packets into one
`GRID_OPEN_DECISION_SNAPSHOT`. It emits
`grid_open_decision_snapshot_status`, `grid_open_ready_for_authorization`,
`grid_open_decision_snapshot_remaining_blockers`, and
`grid_open_allowed=false`. The snapshot is the consolidated operator handoff for
whether the grid-open review is ready for separate env and `createGrid`
authorization. It does not change env, deploy, call `createGrid`, enable
grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate
DB/grid/fund/Earn/exchange state.

Read-only Grid trend-override review packet:

```powershell
.\scripts\prepare_grid_trend_override_review_packet_ssh.ps1
```

This consumes the decision snapshot and emits
`GRID_TREND_OVERRIDE_REVIEW_PACKET`, `grid_trend_override_review_status`,
`grid_trend_override_review_ready`, `trend_override_allowed=false`, and
`grid_open_allowed=false`. It packages the current trend distance, replay
score, stop-break rows, effective capital cap, event-risk gate, MCP coverage,
hard blockers, abort criteria, and required separate operator authorization for
a trend-regime override review. It does not approve the override and does not
change env, deploy, call `createGrid`, enable grid/scheduler/recovery, place
orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange state.

Read-only Grid env-diff preflight packet:

```powershell
.\scripts\prepare_grid_env_diff_preflight_packet_ssh.ps1
```

This consumes the grid open operator and trend-override review packets and
emits `GRID_ENV_DIFF_PREFLIGHT_PACKET`, `grid_env_diff_preflight_status`,
`grid_env_diff_review_ready`, `grid_env_diff_preflight_proposed_env_diff`,
`production_env_change_allowed=false`, and `grid_open_allowed=false`. It
isolates the proposed env diff (`TRADING_OKX_ENABLED=true`,
`TRADING_GRID_ENABLED=true`, scheduler/recovery/Earn still false), credential
readiness, pre-apply requirements, and post-apply read-only verification plan.
It does not change env, deploy, restart, call `createGrid`, enable grid,
enable scheduler/recovery, place orders, modify OCO, send Telegram, or mutate
DB/grid/fund/Earn/exchange state.

Read-only Grid create authorization preflight packet:

```powershell
.\scripts\prepare_grid_create_authorization_preflight_packet_ssh.ps1
```

This consumes the grid open operator and env-diff preflight packets and emits
`GRID_CREATE_AUTHORIZATION_PREFLIGHT_PACKET`,
`grid_create_authorization_preflight_status`,
`grid_create_authorization_review_ready`, `create_grid_allowed=false`, and
`grid_open_allowed=false`. It packages `reviewedCreateGridInputs`, replay
score, trend/event/OKX gates, `capitalCapCheck`, missing evidence, blockers,
required pre-create authorizations, and post-create read-only verification.
It does not change env, deploy, restart, call `createGrid`, enable grid,
enable scheduler/recovery, place orders, modify OCO, send Telegram, or mutate
DB/grid/fund/Earn/exchange state.

Read-only Grid capital-override review packet:

```powershell
.\scripts\prepare_grid_capital_override_review_packet_ssh.ps1
```

This consumes the create authorization preflight packet when the current
blocker is `CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP`. It emits
`GRID_CAPITAL_OVERRIDE_REVIEW_PACKET`, `grid_capital_override_review_status`,
`grid_capital_override_review_ready`, `capital_override_allowed=false`,
`create_grid_allowed=false`, and `grid_open_allowed=false`. It quantifies the
requested capital cap raise, required multiplier, replay/stop-break evidence,
hard blockers, approval conditions, abort criteria, and post-approval
read-only verification. When `trendGate=CLEAR_TREND_REGIME`, the capital packet
records the fresh trend clearance instead of asking for a trend override; if the
trend gate becomes blocked again, trend-regime override review remains a
separate lane. It does not approve a capital override, change env, deploy,
restart, call `createGrid`, enable grid/scheduler/recovery, place orders,
modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange state.

Read-only Grid open authorization bundle:

```powershell
.\scripts\prepare_grid_open_authorization_bundle_ssh.ps1
```

This consumes the capital-override review packet and emits
`GRID_OPEN_AUTHORIZATION_BUNDLE_PACKET`,
`grid_open_authorization_bundle_status`,
`grid_open_authorization_bundle_ready`, `grid_open_allowed=false`, and
`create_grid_allowed=false`. It consolidates the trend override, capital cap
override, production env diff, post-env read-only verification, and createGrid
authorization lanes into one operator-review bundle with explicit remaining
execution blockers and required authorization order. A fresh `trendGate`
clearance is accepted as `CLEAR_BY_FRESH_TREND_GATE_NOT_MUTATION`, so a
no-override-needed trend packet does not block the bundle by itself. It does
not approve any
override, change env, deploy, restart, call `createGrid`, enable
grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate
DB/grid/fund/Earn/exchange state.

Read-only Grid open operator authorization request:

```powershell
.\scripts\prepare_grid_open_operator_authorization_request_ssh.ps1
```

This consumes the authorization bundle and emits
`GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_PACKET`,
`grid_open_operator_authorization_request_status`,
`grid_open_operator_authorization_request_ready`, `grid_open_allowed=false`,
and `create_grid_allowed=false`. It renders copyable, separate authorization
request lines for trend override only when the trend gate remains blocked; if a
fresh `trendGate` clearance is accepted, it records that no separate
trend-regime override is required unless the gate becomes blocked again.
Capital-cap override, production env diff, deploy/restart plus post-env
verification, and createGrid review remain separate approvals. It does not
approve any request, change env, deploy, restart, call `createGrid`, enable
grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate
DB/grid/fund/Earn/exchange state.

Read-only Grid post-env verification plan:

```powershell
.\scripts\prepare_grid_post_env_verification_plan_ssh.ps1
```

This consumes the operator authorization request and emits
`GRID_POST_ENV_VERIFICATION_PLAN_PACKET`,
`grid_post_env_verification_plan_status`,
`grid_post_env_verification_plan_ready`, `deploy_allowed=false`,
`grid_open_allowed=false`, and `create_grid_allowed=false`. It packages the
required read-only verification commands, pass criteria, abort criteria, and
refreshed createGrid inputs that must still match after any separately
authorized env/deploy/restart. The emitted command list preserves the reviewed
`Symbol`, `LookbackHours`, `CandidateLookbackHours`, `GridCount`,
`PerLevelUsdt`, `StopOutPct`, and `CandidateHalfWidthPct` values, and uses
`AcceptAlreadyAppliedEnvDiff` only for post-env-capable child packets. It does
not approve env changes, deploy,
restart, call `createGrid`, enable grid/scheduler/recovery, place orders,
modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange state.

Read-only Grid complete operator packet:

```powershell
.\scripts\prepare_grid_open_complete_operator_packet_ssh.ps1
```

This aggregates the split/currentness deploy handoff, the pre-env operator
authorization request, and the post-env verification plan into
`GRID_OPEN_COMPLETE_OPERATOR_PACKET`. It emits
`grid_open_complete_operator_packet_status`,
`grid_open_complete_operator_packet_ready`, the currentness authorization line,
operator authorization lines, remaining execution blockers, post-deploy
commands, and post-env commands. A
`READY_FOR_GRID_OPEN_COMPLETE_OPERATOR_PACKET_NOT_MUTATION` status means the
complete evidence packet can be reviewed as separate operator decisions; it
does not approve trend/capital/env/deploy/createGrid or open a grid.
When the split handoff proves `runtimeCurrentForGridOpen=true`, the packet
decision is
`AWAIT_SEPARATE_ENV_CAPITAL_POST_ENV_AND_CREATEGRID_AUTHORIZATIONS`: split
runtime currentness is accepted for review, while env, capital, post-env
verification, and createGrid remain separate operator authorizations.
The packet separates filtered `remainingExecutionBlockers` from
`rawExecutionBlockers` and `coveredExecutionReviewBlockers`; for example,
`CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP` remains raw risk evidence but is not
duplicated as an execution blocker when a ready capital-cap override request
line already covers it.

Read-only Grid post-env verification bundle:

```powershell
.\scripts\prepare_grid_post_env_read_only_verification_bundle_ssh.ps1
```

This executes the post-env read-only verification lane by invoking split
acceptance plus the fresh grid plan, decision snapshot, trend override, env
diff, createGrid preflight, authorization bundle, and authorization request
packets. It emits `GRID_POST_ENV_READ_ONLY_VERIFICATION_BUNDLE`,
`grid_post_env_read_only_verification_status`,
`grid_post_env_read_only_verification_ready`, `deploy_allowed=false`,
`grid_open_allowed=false`, and `create_grid_allowed=false`.
It also emits child `child_start`, `child_heartbeat`, and `child_complete`
markers with timeout handling for each nested read-only verification step.
`POST_ENV_TRADING_OKX_ENABLED_NOT_TRUE` or
`POST_ENV_TRADING_GRID_ENABLED_NOT_TRUE` means the separately authorized env
diff/deploy has not been applied yet, so createGrid review must remain blocked.
It does not approve env changes, deploy, restart, call `createGrid`, enable
grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate
DB/grid/fund/Earn/exchange state.

Read-only Grid open blocker priority board:

```powershell
.\scripts\prepare_grid_open_blocker_priority_board_ssh.ps1
```

This consumes the post-env verification bundle through bounded child execution
with `ChildTimeoutSeconds` and `ChildHeartbeatSeconds`, then emits
`GRID_OPEN_BLOCKER_PRIORITY_BOARD`,
`grid_open_blocker_priority_board_status`,
`grid_open_readiness_score_pct`,
`grid_split_runtime_current_for_grid_open`,
`grid_split_tooling_only_currentness_follow_up`,
`grid_trend_gate`, `grid_trend_gate_clearance_accepted`,
`grid_trend_override_required`,
`grid_open_blocker_priority_ranked_blockers`, and `grid_open_allowed=false`.
It ranks split/deploy, event-risk, trend-regime, env, replay-score, capital-cap,
scheduler/recovery/Earn, and operator-authorization-chain blockers into the
next safest read-only action. If a child packet times out or cannot produce
valid JSON, the board returns
`REFRESH_GRID_OPEN_BLOCKER_PRIORITY_BOARD_EVIDENCE` with
`GRID_PRIORITY_BOARD_EVIDENCE_INCOMPLETE` before ranking market or env blockers.
`SPLIT_ACCEPTANCE_NOT_PASSING` remains a hard
runtime blocker only when origin-delta evidence shows runtime drift or unknown
runtime currentness; when `origin_runtime_delta_files=0` and deployment
metadata is current, it is a server tooling-sync follow-up for read-only grid
review and the board decision becomes
`CONTINUE_GRID_OPEN_REVIEW_WITH_RUNTIME_CURRENT_TOOLING_DRIFT` if no stricter
runtime blocker is present. `EVENT_RISK_NOT_R0` and
`OPERATOR_TREND_REGIME_OVERRIDE_REQUIRED_OR_TREND_GATE_CLEARANCE` remain hard
blockers and are ranked before `GRID_ENV_DIFF_NOT_APPLIED`, so a blocked trend
gate cannot be hidden by the pending env diff. This board does not approve
env changes, deploy, restart, call `createGrid`, enable grid/scheduler/recovery,
place orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange
state.
The board preserves candidate grid inputs from the freshest available
read-only packet, falling back to the plan packet so `replayScore` and
`candidateCapitalUsdt` stay visible even when env or operator authorization
gates remain blocked.

Bounded read-only Grid open readiness watch:

```powershell
.\scripts\watch_grid_open_readiness_ssh.ps1 -MaxAttempts 3 -SleepSeconds 300
```

This invokes only `prepare_grid_open_blocker_priority_board_ssh.ps1`, adds a
bounded watch loop around the board's own child heartbeat/timeout handling, and
emits `grid_open_readiness_watch_status`,
`grid_open_readiness_watch_score_pct`, `grid_open_readiness_watch_top_blocker`,
`grid_open_readiness_watch_ranked_blockers`, and
`grid_open_readiness_watch_next_action`. Pending states include
`PENDING_GRID_DEPLOY_OR_SPLIT_ACCEPTANCE`, `PENDING_GRID_ENV_DIFF`,
`PENDING_GRID_EVENT_RISK_R0`, and `PENDING_GRID_OPEN_BLOCKERS`.
`GRID_OPEN_READINESS_READY_FOR_SEPARATE_CREATEGRID_AUTHORIZATION_NOT_MUTATION`
means the evidence is ready for a separate createGrid authorization review
only. The watcher does not deploy, restart, change production env, call
`createGrid`, enable grid/scheduler/recovery, place orders, modify OCO, send
Telegram, or mutate DB/grid/fund/Earn/exchange state.

Read-only Grid split-acceptance deploy handoff:

```powershell
.\scripts\prepare_grid_split_acceptance_deploy_handoff_ssh.ps1
```

This consumes the grid open readiness watch and the metadata-only origin-delta
classifier, then emits `GRID_SPLIT_ACCEPTANCE_DEPLOY_HANDOFF_PACKET`,
`grid_split_acceptance_deploy_handoff_status`, the grid readiness score/top
blocker, deployment metadata, runtime delta evidence, child
`child_start`/`child_heartbeat`/`child_complete` markers, the expected
post-deploy or runtime-current next blockers after the split/currentness lane
clears, and the exact read-only post-deploy verification list. The post-deploy
checklist keeps
the reviewed grid candidate parameters on the blocker board, readiness watch,
and post-env bundle commands, so a micro-grid handoff cannot be replayed with
default sizing by accident. A status of
`READY_FOR_SEPARATE_GRID_SPLIT_ACCEPTANCE_DEPLOY_AUTHORIZATION_NOT_MUTATION`
means only that an operator can separately consider deploying current
`origin/main` and rerunning read-only grid verification. A status of
`READY_FOR_GRID_SPLIT_RUNTIME_CURRENT_TOOLING_SYNC_FOLLOW_UP_NOT_MUTATION`
means the deployed runtime is current for grid review because origin runtime
delta is zero; server worktree/tooling sync remains a follow-up, not a runtime
deploy blocker. It does not deploy, change production env, call `createGrid`,
enable grid/scheduler/recovery, place orders, modify OCO, send Telegram, or
mutate DB/grid/fund/Earn/exchange state.

Read-only trailing-stop PnL replay smoke after a deploy that contains the
`analyzeTrailingStopPnlReplay` MCP tool:

```powershell
.\scripts\smoke_trailing_stop_pnl_replay_ssh.ps1
```

The default mode proves server-local `/api/mcp` reachability, the read-only
boundary marker, the `acceptanceTarget: total trailing PnL improvement >= 5%`
marker, an explicit replay sample status, and `acceptanceBlocker` diagnostics.
The acceptance smoke defaults to a 30d/500 sample so a very recent 100-trade
slice does not dominate the deployed closure signal.
`intervalCode` selects normalized backtest trades; `replayIntervalCode`
defaults to `1m` and selects the K-lines used to resolve intrabar
trigger/stop ordering.
Add `-RequireAcceptance` only when the deployed DB sample is expected to prove
the 30d PnL target (`acceptance=PASS`). Ambiguous same-bar replay rows are
reported but excluded from PnL acceptance totals.

Read-only trailing-stop operator review packet:

```powershell
.\scripts\prepare_trailing_stop_operator_review_packet_ssh.ps1 -RequireReady
```

This wraps the hard replay smoke with `-RequireAcceptance` and emits
`trailing_stop_operator_review_packet` plus
`trailing_stop_operator_packet_status`. `READY_FOR_OPERATOR_PACKET_NOT_LIVE`
means exit-side evidence can be attached to a separate operator review. It
keeps `trailing_stop_acceptance` evidence separate from entry/filter blockers
and does not deploy, enable live trading, enable the trailing scheduler, change
strategy opt-in, place orders, modify OCO, close positions, or mutate DB/grid/
fund/Earn state.

Read-only exit-side profit review packet:

```powershell
.\scripts\prepare_exit_side_profit_review_packet_ssh.ps1 -RequireReady
```

This combines the trailing-stop operator packet and strategy 485 aged
negative-EV operator packet into `exit_side_profit_review_packet` and
`exit_side_profit_review_packet_status`.
`READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION` means exit-side evidence is
ready for a separate operator review; it does not enable live trailing, change
strategy opt-in, place orders, modify OCO, close positions, or mutate
DB/grid/fund/Earn state. It keeps `trailing_stop_acceptance` and
`strategy485_operator_packet_status` in one packet while preserving all
no-mutation guardrails. Strategy485 `NO_POSITION_RISK_ACTION` and `WATCH_ONLY`
are treated as no-action evidence for this packet, not as missing aged
negative-EV blockers.

Read-only exit-side operator decision brief:

```powershell
.\scripts\prepare_exit_side_operator_decision_brief_ssh.ps1 -RequireDecisionReady
```

This converts the exit-side packet into
`exit_side_operator_decision_brief_packet`,
`exit_side_operator_review_recommendations`, and
`exit_side_operator_decision_brief_status`.
`READY_FOR_OPERATOR_DECISION_NOT_MUTATION` means the evidence can be attached
to a separate exit-side operator review with
`PREPARE_SEPARATE_EXIT_SIDE_OPERATOR_REVIEW`. The brief also emits
`exit_side_operator_decision_lanes` / `decisionLanes` to separate
`trailing-stop-rollout`, `strategy485-risk-reduction`, and
`entry-filter-datafreshness-policy`; the last lane is explicitly
`NOT_DECIDED_BY_EXIT_SIDE_BRIEF` and must stay routed through the profit
operator action brief. It also emits `exit_side_operator_decision_checklist`
and `decisionChecklist`, separating the authorization checklist for trailing
rollout, strategy 485 risk reduction, and entry/DataFreshness out-of-scope
policy decisions. The decision brief carries proposal ids matching the profit
operator action brief, including `trailing-stop-rollout-review` and
`strategy485-risk-reduction-review`, plus the
`docs/exit-side-operator-review-plan.md` review contract. It also emits
`strategy485_position_summaries` and carries
trailing acceptance sample counts in `evidenceSummary` so the operator does not
need to dig through nested source packets for the key exit-side evidence. It
does not deploy, enable live
trading, enable trailing, place orders, modify OCO, close positions, change
strategy opt-in, or mutate DB/grid/fund/Earn state.
Use [docs/exit-side-operator-review-plan.md](docs/exit-side-operator-review-plan.md)
as the review contract before drafting a separate trailing rollout or strategy
485 risk-reduction decision.

Local TP/SL/OCO feasibility operator packet from the refreshed exit-side brief:

```powershell
.\scripts\prepare_tp_sl_oco_feasibility_operator_packet.ps1 -RequireReady
```

This reuses `target/profit-review/exit-side-operator-decision-brief-refresh.log`
and emits `tp_sl_oco_feasibility_operator_packet` plus
`tp_sl_oco_feasibility_status`. It does not rerun SSH. A
`TP_SL_OCO_FEASIBILITY_OPERATOR_PACKET` with
`READY_FOR_TP_SL_OCO_FEASIBILITY_OPERATOR_REVIEW_NOT_MUTATION` means the
latest trailing acceptance and strategy 485 OCO health can be reviewed together
for a separate TP/SL/OCO feasibility decision. When there is no current aged
negative-EV Strategy485 position, the packet records the Strategy485 sub-lane
as `NO_POSITION_RISK_ACTION` instead of blocking the trailing/OCO review. It
preserves `close_position_allowed=false`,
`position_or_oco_mutation_allowed=false`, `deploy_or_env_change_allowed=false`,
and `order_allowed=false`; it is not authorization to enable live trading,
enable scheduler mutation, place orders, close positions, modify/cancel OCO,
deploy, change production env, or relax EntryDedup/DataFreshness/live policy.

Read-only TP/SL/OCO feasibility preflight review packet:

```powershell
.\scripts\prepare_tp_sl_oco_feasibility_preflight_review_packet.ps1 -RequireReady
```

This wraps the TP/SL/OCO feasibility operator packet and emits
`tp_sl_oco_feasibility_preflight_review_packet` plus
`tp_sl_oco_feasibility_preflight_status`.
`READY_FOR_TP_SL_OCO_FEASIBILITY_PREFLIGHT_REVIEW_NOT_MUTATION` means the
operator can review TP/SL/OCO feasibility scope and future prerequisites without
changing runtime or trading state. It keeps `close_position_allowed=false`,
`position_or_oco_mutation_allowed=false`, `order_allowed=false`,
`telegram_send_allowed=false`, and `deploy_or_env_change_allowed=false`; it is
not authorization to place orders, close positions, modify/cancel OCO, send
Telegram, deploy, change production env, or relax trading policy.

Read-only OCO sync reconciliation packet for stale DB-open positions:

```powershell
.\scripts\prepare_oco_sync_reconciliation_packet_ssh.ps1 -RequireReviewReady
```

This calls server-local `/api/mcp` read tools only, or parses an existing
`-SourceLogPath`, and emits `oco_sync_reconciliation_packet` plus
`oco_sync_reconciliation_status`. An `OCO_SYNC_RECONCILIATION_PACKET` with
`READY_FOR_OPERATOR_RECONCILIATION_REVIEW_NOT_MUTATION` lists affected
positions, OKX child-fill evidence, proposed SL close price/reason, and
`positionsRequiringWrite` for a separate operator decision. It keeps
`force_close_position_allowed=false`, `close_position_allowed=false`,
`position_or_oco_mutation_allowed=false`, and `order_allowed=false`; it is not
authorization to call `forceClosePosition`, close positions, modify/cancel OCO,
place orders, deploy, change production env, send Telegram, or mutate
DB/grid/fund/Earn/exchange state.

Read-only profit operator priority decision brief:

```powershell
.\scripts\prepare_profit_operator_priority_decision_brief.ps1 -RequireReady
```

This wraps the consolidated profit operator packet and emits
`profit_operator_priority_decision_brief_packet`,
`profit_operator_priority_primary_focus`, and
`profit_operator_priority_decision_brief_status`.
`READY_FOR_OPERATOR_DECISION_NOT_LIVE` ranks the current review-only items as
trailing-stop dry-run review first, strategy485 risk-reduction shadow review
second, and EntryDedup semantics shadow review third. It keeps entry-filter and
DataFreshness replay lanes blocked, and it does not deploy, enable live
trading, enable scheduler paths, place orders, modify OCO, close positions,
relax EntryDedup/DataFreshness/live policy, change production env, or mutate
DB/grid/fund/Earn/Telegram/exchange state.

Read-only trailing-stop dry-run operator decision packet:

```powershell
.\scripts\prepare_trailing_stop_dry_run_operator_decision_packet.ps1 -RequireReady
```

This wraps the priority decision brief plus the exit-side operator review
packet and emits `trailing_stop_dry_run_operator_decision_packet` and
`trailing_stop_dry_run_operator_decision_status`.
`READY_FOR_TRAILING_DRY_RUN_OPERATOR_DECISION_NOT_LIVE` means the first-ranked
review item can be attached to operator review as a dry-run design only. It
keeps `scheduler_enablement_allowed=false`, `order_allowed=false`, and
`position_or_oco_mutation_allowed=false`; it does not enable trailing, live
trading, scheduler paths, orders, OCO modification, deploy/env changes, or
policy relaxation.

Read-only trailing-stop dry-run preflight review packet:

```powershell
.\scripts\prepare_trailing_stop_dry_run_preflight_review_packet.ps1 -RequireReady
```

This wraps the trailing-stop dry-run operator decision packet and emits
`trailing_stop_dry_run_preflight_review_packet` plus
`trailing_stop_dry_run_preflight_status`.
`READY_FOR_TRAILING_DRY_RUN_PREFLIGHT_REVIEW_NOT_LIVE` means the operator can
review dry-run-only scope, inputs, and future prerequisites. It keeps
`scheduler_enablement_allowed=false`, `order_allowed=false`,
`telegram_send_allowed=false`, `position_or_oco_mutation_allowed=false`, and
`deploy_or_env_change_allowed=false`; it is not authorization to change env,
enable scheduler/live paths, place orders, modify OCO, send Telegram, or
deploy.

Read-only trailing-stop dry-run activation review packet:

```powershell
.\scripts\prepare_trailing_stop_dry_run_activation_review_packet_ssh.ps1 -RequireReady
```

This refreshes the hard trailing replay packet, the live-readiness audit, and
server-local `getTrailingStopStatus` / `getStrategyConfig` evidence through
`/api/mcp`. It emits
`TRAILING_STOP_DRY_RUN_ACTIVATION_REVIEW_PACKET`,
`trailing_stop_dry_run_activation_review_packet`, and
`trailing_stop_dry_run_activation_status`.
`READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_REVIEW_NOT_APPLIED` means the next
step is a separate operator decision for exactly `TRAILING_STOP_ENABLED=true`
and `TRAILING_STOP_DRY_RUN=true`, followed by deploy/restart and read-only
verification only. It keeps `order_allowed=false`,
`position_or_oco_mutation_allowed=false`, `scheduler_enablement_allowed=false`,
`telegram_send_allowed=false`, and `deploy_or_env_change_allowed=false`; the
packet itself does not change production env, deploy, place orders, modify OCO,
or relax live policy. If production `getStrategyConfig` shows no reviewed
strategy has `trailingStopEnabled=true`, the status is
`BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED`; the next step is a separate
operator authorization for strategy opt-in before any global dry-run env diff.

Read-only trailing-stop strategy opt-in review packet:

```powershell
.\scripts\prepare_trailing_stop_strategy_opt_in_review_packet_ssh.ps1 -RequireReady
```

This consumes the activation packet output, or a saved activation `-SourceLog`,
and emits `TRAILING_STOP_STRATEGY_OPT_IN_REVIEW_PACKET`,
`trailing_stop_strategy_opt_in_review_packet`, and
`trailing_stop_strategy_opt_in_review_status`.
`READY_FOR_STRATEGY_TRAILING_OPT_IN_OPERATOR_REVIEW_NOT_MUTATION` means the
current activation blocker is exactly missing strategy `trailingStopEnabled`
opt-in and the packet has a proposed `setTrailingStopOptIn(...)` write plus a
rollback write for separate operator authorization. The packet itself keeps
`trailing_stop_strategy_opt_in_change_allowed=false`,
`production_env_change_allowed=false`, `deploy_allowed=false`,
`scheduler_enablement_allowed=false`, `order_allowed=false`,
`telegram_send_allowed=false`, and `position_or_oco_mutation_allowed=false`;
it does not call `setTrailingStopOptIn`, change env, deploy, restart, place
orders, modify OCO, or relax live policy. After any separately authorized
opt-in write, rerun the activation packet and use the post-opt-in read-only
verification commands before requesting any global dry-run env diff.

Read-only trailing-stop post-opt-in readiness packet:

```powershell
.\scripts\prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1 -RequireReady
```

This reruns or consumes the activation packet after a separately authorized
strategy opt-in write. It emits `TRAILING_STOP_POST_OPT_IN_READINESS_PACKET`,
`trailing_stop_post_opt_in_readiness_packet`, and
`trailing_stop_post_opt_in_readiness_status`.
`READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION` means
the expected strategy, default `574`, now has `trailingStopEnabled=true`, the
trailing replay still passes, global trailing remains disabled, and dry-run is
still true. The next step is only a separate operator review for
`TRAILING_STOP_ENABLED=true` plus `TRAILING_STOP_DRY_RUN=true`, followed by
deploy/restart and read-only verification. The packet keeps
`production_env_change_allowed=false`, `deploy_allowed=false`,
`scheduler_enablement_allowed=false`, `order_allowed=false`,
`telegram_send_allowed=false`, and `position_or_oco_mutation_allowed=false`;
it does not call `setTrailingStopOptIn`, change env, deploy, restart, enable
live trading, place orders, modify OCO, or relax live policy.

Read-only trailing-stop dry-run env/deploy handoff packet:

```powershell
.\scripts\prepare_trailing_stop_dry_run_env_deploy_handoff_ssh.ps1 -RequireReady
```

This consumes the post-opt-in readiness packet and local git metadata, then
emits `TRAILING_STOP_DRY_RUN_ENV_DEPLOY_HANDOFF_PACKET`,
`trailing_stop_dry_run_env_deploy_handoff_packet`, and
`trailing_stop_dry_run_env_deploy_handoff_status`.
`READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DEPLOY_HANDOFF_NOT_MUTATION` means the
exact operator authorization can be requested for only
`TRAILING_STOP_ENABLED=true` and `TRAILING_STOP_DRY_RUN=true`, followed by
deploy/restart and read-only verification. The packet prints the exact operator
authorization text, required env diff, flags that must remain disabled, deploy
command, post-deploy read-only verification, and rollback plan. It keeps
`production_env_change_allowed=false`, `deploy_allowed=false`,
`position_or_oco_mutation_allowed=false`, `order_allowed=false`, and
`telegram_send_allowed=false`; it does not change production env, deploy,
restart, set dry-run false, place orders, modify OCO, close positions, send
Telegram, or relax live policy.

Guarded trailing-stop strategy opt-in execution wrapper:

```powershell
.\scripts\execute_trailing_stop_strategy_opt_in_ssh.ps1 -StrategyId 574 -RequireReady
```

The default run is non-mutating and emits
`TRAILING_STOP_STRATEGY_OPT_IN_EXECUTION_PACKET`,
`trailing_stop_strategy_opt_in_execution_packet`, and
`trailing_stop_strategy_opt_in_execution_status=DRY_RUN_READY_FOR_SEPARATE_EXECUTION_AUTHORIZATION_NOT_MUTATION`.
It proves the reviewed `setTrailingStopOptIn(strategyId=574, enabled=true, ...)`
write is still the only strategy-config blocker. The wrapper performs that
single write only when rerun with:

```powershell
.\scripts\execute_trailing_stop_strategy_opt_in_ssh.ps1 -StrategyId 574 -Execute -ConfirmText EXECUTE_TRAILING_STOP_OPT_IN_574 -RequireReady
```

Even in execute mode it does not change production env, deploy, restart, enable
scheduler/live trading, place orders, modify OCO, send Telegram, or mutate
grid/fund/Earn/exchange state. A successful execution must immediately reach
`EXECUTED_POST_OPT_IN_READY_FOR_ENV_DIFF_REVIEW`, after which the next step is
still a separate operator authorization for `TRAILING_STOP_ENABLED=true` and
`TRAILING_STOP_DRY_RUN=true`.

Read-only profit next execution blocker packet:

```powershell
.\scripts\prepare_profit_next_execution_blocker_packet.ps1 -RequireReady
```

This emits `PROFIT_NEXT_EXECUTION_BLOCKER_PACKET`,
`profit_next_execution_blocker_packet`,
`profit_next_execution_goal_satisfied=false`, and
`profit_next_execution_blocker_status`. It consolidates the trailing-stop
strategy opt-in dry-run wrapper, Strategy574/TinyLive negative evidence,
DataFreshness replay gaps, Strategy485 current-position risk, and signal-policy
status into one machine-readable answer for why the make-money goal is not yet
satisfied. When the trailing evidence is still the best route, the expected
status is `BLOCKED_AWAIT_EXPLICIT_EXECUTE_CONFIRMATION` with
`profit_next_execution_route=TRAILING_STOP_STRATEGY574_OPT_IN` and an exact
unlock command for the separate guarded opt-in execution. After that opt-in is
applied, the same packet should move to
`BLOCKED_AWAIT_SEPARATE_TRAILING_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION`,
meaning the next separate decision is only `TRAILING_STOP_ENABLED=true` plus
`TRAILING_STOP_DRY_RUN=true`, followed by deploy/restart and read-only
verification. Its `profit_next_execution_exact_unlock_command` should point to
the read-only handoff command
`.\scripts\prepare_trailing_stop_dry_run_env_deploy_handoff_ssh.ps1 -RequireReady`
so the exact operator authorization text, rollback plan, and verification list
are generated before any env/deploy action. The packet remains read-only: it
does not call
`setTrailingStopOptIn`, change env, deploy, restart, enable scheduler/live
trading, place orders, modify OCO, send Telegram, or mutate
DB/grid/fund/Earn/exchange state.
After the dry-run env deploy is active, the same packet should report
`profit_next_execution_route=TRAILING_STOP_DRY_RUN_OBSERVATION`,
`profit_next_execution_blocker_status=TRAILING_DRY_RUN_ACTIVE_READ_ONLY_OBSERVATION`,
and `profit_next_execution_unique_blocker=COLLECT_TRAILING_DRY_RUN_OBSERVATION_SAMPLE`.
That state means A0 is complete and the next step is observation evidence only;
it is not live OCO mutation approval.

Bounded read-only profit next execution watch:

```powershell
.\scripts\watch_profit_next_execution_readiness_ssh.ps1 -MaxAttempts 3 -SleepSeconds 300
```

This wrapper reruns the DataFreshness replay evidence readiness packet and the
profit next-execution blocker packet, saving replayable attempt logs under
`target/profit-review`. It emits `profit_next_execution_watch_status`,
`profit_next_execution_watch_unique_blocker`,
`profit_next_execution_watch_observation_sample_ready`,
`profit_next_execution_watch_sample_collection_blocked_by`, and
`profit_next_execution_watch_data_freshness_replay_candidate_id_rows`.
`PENDING_OPEN_OCO_SAMPLE` means trailing dry-run is active but no open OCO
sample is available yet. `PENDING_DATAFRESHNESS_REPLAY_EVIDENCE` means the
counterfactual replay rows are still missing.
`EVIDENCE_READY_FOR_OPERATOR_REVIEW_NOT_LIVE` only starts a separate read-only
operator review; it is not authorization to relax policy, deploy, enable live
trading, place orders, or mutate OCO/grid/fund/Earn state. Long child checks
emit watcher-level `child_start`, `child_heartbeat`, and `child_complete`
markers.

Read-only trailing-stop dry-run observation status packet:

```powershell
.\scripts\prepare_trailing_stop_dry_run_observation_status_ssh.ps1 -ExpectedOptInStrategyId 574 -RequireReady
```

This emits `TRAILING_STOP_DRY_RUN_OBSERVATION_STATUS_PACKET`,
`trailing_stop_dry_run_observation_status_packet`, and
`trailing_stop_dry_run_observation_status`. When A0 is active but there is no
open OCO position, the expected status is
`ACTIVE_WAITING_FOR_OPEN_OCO_SAMPLE` with
`trailing_stop_dry_run_observation_sample_collection_blocked_by=NO_OPEN_OCO_POSITIONS`.
That is a valid read-only observation status, not live promotion evidence.

The same wrapper also provides controlled rollback. First dry-run it:

```powershell
.\scripts\execute_trailing_stop_strategy_opt_in_ssh.ps1 -StrategyId 574 -Rollback -RequireReady
```

Then, only with separate rollback approval:

```powershell
.\scripts\execute_trailing_stop_strategy_opt_in_ssh.ps1 -StrategyId 574 -Rollback -Execute -ConfirmText ROLLBACK_TRAILING_STOP_OPT_IN_574 -RequireReady
```

Rollback only sets `trailingStopEnabled=false`; it is not an env/deploy,
scheduler, order, OCO, grid, fund, Earn, Telegram, or live-policy action.

Read-only strategy485 risk-reduction operator decision packet:

```powershell
.\scripts\prepare_strategy485_risk_reduction_operator_decision_packet.ps1 -RequireReady
```

This wraps the priority decision brief plus the exit-side operator review
packet and emits `strategy485_risk_reduction_operator_decision_packet` and
`strategy485_risk_reduction_operator_decision_status`.
`READY_FOR_STRATEGY485_RISK_REDUCTION_OPERATOR_DECISION_NOT_MUTATION` means the
second-ranked review item can be attached to operator review as a shadow
risk-reduction design only. It keeps `close_position_allowed=false`,
`position_or_oco_mutation_allowed=false`, and `order_allowed=false`; it does
not close positions, modify/cancel OCO, place orders, enable live trading,
deploy, change production env, or relax policy.

Read-only strategy485 risk-reduction preflight review packet:

```powershell
.\scripts\prepare_strategy485_risk_reduction_preflight_review_packet.ps1 -RequireReady
```

This wraps the strategy485 risk-reduction operator decision packet and emits
`strategy485_risk_reduction_preflight_review_packet` plus
`strategy485_risk_reduction_preflight_status`.
`READY_FOR_STRATEGY485_RISK_REDUCTION_PREFLIGHT_REVIEW_NOT_MUTATION` means the
operator can review non-mutating risk-reduction scope and future prerequisites.
It keeps `close_position_allowed=false`,
`position_or_oco_mutation_allowed=false`, `order_allowed=false`,
`telegram_send_allowed=false`, and `deploy_or_env_change_allowed=false`; it is
not authorization to close positions, modify/cancel OCO, place orders, send
Telegram, deploy, or change production env.

Read-only strategy485 risk escalation brief from the latest exit-side evidence:

```powershell
.\scripts\prepare_strategy485_risk_escalation_brief.ps1 -RequireReady
```

This reads only `target/profit-review/exit-side-operator-decision-brief-refresh.log`
and emits `strategy485_risk_escalation_brief_packet` plus
`strategy485_risk_escalation_brief_status`. A
`STRATEGY485_RISK_ESCALATION_BRIEF` with
`READY_FOR_STRATEGY485_RISK_ESCALATION_REVIEW_NOT_MUTATION` summarizes current
negative-EV position risk, severe paper-loss count, total EV, worst/average
paper loss, and per-position risk buckets for operator review. It keeps
`close_position_allowed=false`, `position_or_oco_mutation_allowed=false`,
`order_allowed=false`, and `telegram_send_allowed=false`; it is not
authorization to close positions, modify OCO, place orders, deploy, change
production env, or enable live trading.

Read-only EntryDedup semantics operator decision packet:

```powershell
.\scripts\prepare_entry_dedup_semantics_operator_decision_packet.ps1 -RequireReady
```

This wraps the priority decision brief plus the EntryDedup semantics shadow
experiment packet and emits `entry_dedup_semantics_operator_decision_packet`
and `entry_dedup_semantics_operator_decision_status`.
`READY_FOR_ENTRY_DEDUP_SEMANTICS_OPERATOR_DECISION_NOT_LIVE` means the
third-ranked review item can be attached to operator review as shadow-only
EntryDedup semantics evidence. It keeps
`entry_dedup_policy_change_allowed=false`,
`data_freshness_policy_change_allowed=false`, and `order_allowed=false`; it
does not relax EntryDedup/DataFreshness/live policy, enable staged-add or live
execution, place orders, modify OCO, deploy, or change production env.

Read-only EntryDedup semantics direct operator packet:

```powershell
.\scripts\prepare_entry_dedup_semantics_direct_operator_packet.ps1 -RequireReady
```

This narrows only the EntryDedup semantics shadow experiment packet into
`ENTRY_DEDUP_SEMANTICS_DIRECT_OPERATOR_PACKET` and emits
`entry_dedup_semantics_direct_operator_packet` plus
`entry_dedup_semantics_direct_operator_status`. It intentionally bypasses the
full profit-priority matrix with
`sourcePriorityDependency=NOT_REQUIRED_ENTRY_DEDUP_DIRECT_REVIEW`, so unrelated
exit-side or DataFreshness lanes cannot block a separate EntryDedup-only
read-only review. It keeps `entry_dedup_policy_change_allowed=false`,
`data_freshness_policy_change_allowed=false`,
`staged_add_execution_allowed=false`, `order_allowed=false`,
`grid_mutation_allowed=false`, and `telegram_send_allowed=false`. This is not a
full profit-priority decision and does not relax policy, enable staged-add/live
execution, place orders, modify OCO, deploy, change env, send Telegram, or
mutate DB/grid/fund/Earn/exchange state.

For a fresh production source, first run
`.\scripts\prepare_entry_dedup_semantics_shadow_experiment_packet_ssh.ps1 -StrategyId 508 -IntervalCode 1h -Hours 720 -ForwardHours 24 -Limit 50 -RequireReady`.
The SSH packet includes exact-opportunity staged-add review fields such as
`exactOpportunityCount`, `exactDuplicateSuppressedRows`, and
`stagedAddReviewCandidateOpportunities`, then the direct packet can consume the
saved log through `-SourceShadowLogPath` without invoking the full
profit-priority matrix.

Read-only EntryDedup runtime proof gap packet:

```powershell
.\scripts\prepare_entry_dedup_runtime_proof_gap_packet.ps1 -RequireReady
```

This reads the saved direct EntryDedup packet, gate preflight, and synthetic
EV/OCO preview logs and emits `ENTRY_DEDUP_RUNTIME_PROOF_GAP_PACKET` plus
`entry_dedup_runtime_proof_gap_packet`. It now separates review evidence gaps
from mutation blockers with
`entry_dedup_runtime_proof_gap_blocker_semantics=REVIEW_AND_MUTATION_SPLIT_V1`.
The top review evidence gap is candidate-level runtime EV/entry/TP/SL/OCO
snapshot coverage; the top mutation blocker remains exact OCO route proof
before any order, staged-add execution, or live policy relaxation. It keeps
`entry_dedup_policy_change_allowed=false`,
`data_freshness_policy_change_allowed=false`,
`staged_add_execution_allowed=false`, `order_allowed=false`,
`grid_mutation_allowed=false`, and `telegram_send_allowed=false`.

Read-only EntryDedup candidate runtime snapshot collector review packet:

```powershell
.\scripts\prepare_entry_dedup_candidate_runtime_snapshot_collector_review_packet.ps1 -RequireReady
```

This wraps the EntryDedup runtime proof gap packet, BUY-like candidate loss
review, BUY-like continuity matcher review, runtime-evidence RCA, and
panic-bottom RCA into
`ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_PACKET`. It emits
`entry_dedup_candidate_runtime_snapshot_collector_review_status` and
`entry_dedup_candidate_runtime_snapshot_collector_local_implementation_status`,
then checks the local `ExposureOptimizer` / `LiveSignalEvaluator` markers for
candidate context keys needed to close the runtime snapshot gap, including
entry/TP/SL, EV, OCO plan-shape, event-risk, daily-cap, max-loss, duplicate
hash, `replayCandidateId`, and shadow suppression markers.
`READY_FOR_ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_NOT_LIVE`
with `LOCAL_IMPLEMENTED_NOT_DEPLOYED_NOT_ACTIVE` means the local shadow snapshot
context implementation is reviewable but not deployed or activated; it keeps
`runtime_evidence_write_allowed=false`, `collector_activation_allowed=false`,
`order_allowed=false`, and `strategy_threshold_change_allowed=false`. It is not
authorization to activate a collector, deploy, relax EntryDedup/DataFreshness/
live policy, change thresholds, stage-add, place orders, modify OCO, send
Telegram, or mutate DB/grid/fund/Earn/exchange state.
After the 2026-07-01 deploy of `38b6480`, the runtime implementation is current
on production, but close-readiness still requires fresh shadow/runtime evidence
rows; `shadowIntentCount=0` and DataFreshness replay candidate rows remain
blocked evidence, not live approval.

Read-only EntryDedup semantics preflight review packet:

```powershell
.\scripts\prepare_entry_dedup_semantics_preflight_review_packet.ps1 -RequireReady
```

This wraps the EntryDedup semantics operator decision packet and emits
`entry_dedup_semantics_preflight_review_packet` plus
`entry_dedup_semantics_preflight_status`.
`READY_FOR_ENTRY_DEDUP_SEMANTICS_PREFLIGHT_REVIEW_NOT_LIVE` means the operator
can review shadow-only EntryDedup semantics scope and future prerequisites. It
keeps `entry_dedup_policy_change_allowed=false`,
`data_freshness_policy_change_allowed=false`, `staged_add_execution_allowed=false`,
`order_allowed=false`, `telegram_send_allowed=false`, and
`deploy_or_env_change_allowed=false`; it is not authorization to relax
EntryDedup/DataFreshness/live policy, enable staged-add or live execution,
place orders, modify OCO, send Telegram, deploy, or change production env.

Read-only exit-side operator experiment packet from the latest saved profit
matrix:

```powershell
.\scripts\prepare_exit_side_operator_experiment_packet.ps1 -RequireReady
```

This reuses `target/profit-review/latest-profit-operator-matrix.path` through
the profit operator review summary and emits
`exit_side_operator_experiment_packet` plus
`exit_side_operator_experiment_packet_status`.
`READY_FOR_EXIT_SIDE_EXPERIMENT_REVIEW_NOT_LIVE` means the packet is ready for
operator review only. It contains `trailing-stop-dry-run-experiment-review` and
`strategy485-risk-reduction-shadow-review`, requires a fresh matrix, and does
not rerun SSH, deploy, enable live trading, enable the trailing scheduler,
place orders, modify OCO, close positions, relax EntryDedup/DataFreshness/live
policy, or mutate DB/grid/fund/Earn/Telegram/exchange state.

Read-only verified profit recommendations from the latest saved profit matrix:

```powershell
.\scripts\prepare_profit_verified_recommendations.ps1 -RequireReady
```

This wraps the exit-side experiment packet plus the recorded EntryDedup
semantics shadow experiment packet into
`profit_verified_recommendations_packet` with
`packetType=PROFIT_VERIFIED_RECOMMENDATIONS` and
`profit_verified_recommendations_status=READY_WITH_REVIEW_ONLY_RECOMMENDATIONS`.
It lists the exit-side review-only ready recommendations only when the source
exit-side packet is `READY_FOR_EXIT_SIDE_EXPERIMENT_REVIEW_NOT_LIVE` and each
exit-side proposal status is ready. It also includes the
`entry-dedup-semantics-shadow-experiment-review` candidate, and the
still-blocked policy lanes in one packet. EntryDedup remains review-only shadow
evidence, not policy approval. It does not rerun SSH, deploy, enable live
trading, enable the trailing scheduler, place orders, modify OCO, close
positions, relax EntryDedup/DataFreshness/live policy, or mutate
DB/grid/fund/Earn/Telegram/exchange state.

Read-only exit-side experiment readiness from those verified recommendations:

```powershell
.\scripts\prepare_exit_side_verified_experiment_readiness.ps1 -RequireReady
```

This emits `exit_side_verified_experiment_readiness_packet` with
`packetType=EXIT_SIDE_VERIFIED_EXPERIMENT_READINESS` and
`exit_side_verified_experiment_readiness_status=READY_FOR_EXIT_SIDE_DRY_RUN_AND_SHADOW_REVIEW_NOT_LIVE`.
It turns the trailing dry-run and strategy 485 shadow recommendations into
review-only experiment plans with minimum evidence, success evidence, stop
criteria, `live_policy_change_allowed=false`, and
`position_or_oco_mutation_allowed=false`. It does not authorize live trading,
scheduler enablement, deploy/env changes, orders, OCO modification, position
close, policy relaxation, DB/grid/fund/Earn/Telegram/exchange mutation, or
external backfill/import.

Read-only exit-side experiment operator review packet:

```powershell
.\scripts\prepare_exit_side_experiment_operator_review_packet.ps1 -RequireReady
```

This emits `exit_side_experiment_operator_review_packet` with
`packetType=EXIT_SIDE_EXPERIMENT_OPERATOR_REVIEW_PACKET` and
`exit_side_experiment_operator_review_status=READY_FOR_OPERATOR_REVIEW_PACKET_NOT_LIVE`.
It carries `trailing-stop-dry-run-operator-review` and
`strategy485-risk-reduction-shadow-operator-review`, a review-only
`small_experiment_review_cap_usdt`, observation hours, operator decision
choices, and `order_allowed=false`. It is an attachment for operator review,
not approval to trade, deploy, modify OCO, close positions, relax policy, or
change production env.

Read-only strategy574/TinyLive governance preflight review packet:

```powershell
.\scripts\prepare_strategy574_tiny_live_governance_preflight_review_packet.ps1 -RequireReady
```

This local packet invokes only
`prepare_strategy574_tiny_live_governance_operator_packet.ps1` against existing
strategy574 gate and TinyLive loss RCA logs. It emits
`strategy574_tiny_live_governance_preflight_review_packet` with
`packetType=STRATEGY574_TINY_LIVE_GOVERNANCE_PREFLIGHT_REVIEW_PACKET` and
`strategy574_tiny_live_governance_preflight_status=READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_PREFLIGHT_REVIEW_NOT_LIVE`.
It is an operator-review attachment for the governance blocker lane, not
TinyLive/live approval. It keeps `tiny_live_order_allowed=false`,
`live_policy_change_allowed=false`, `scheduler_enablement_allowed=false`,
`deploy_or_env_change_allowed=false`, `order_allowed=false`, and
`telegram_send_allowed=false`. It does not rerun SSH, deploy, change production
env, execute TinyLive, place orders, enable scheduler mutation, send Telegram,
modify OCO, or relax EntryDedup/DataFreshness/live policy.

Read-only consolidated profit operator review packet:

```powershell
.\scripts\prepare_profit_operator_consolidated_review_packet.ps1 -RequireReady
```

This emits `profit_operator_consolidated_review_packet` with
`packetType=PROFIT_OPERATOR_CONSOLIDATED_REVIEW_PACKET` and
`profit_operator_consolidated_review_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE`.
It puts the ready exit-side review items, the
`entry-dedup-semantics-shadow-operator-review` item, and
`profit_operator_consolidated_blocked_lanes` in one packet, including
entry-filter and DataFreshness replay policy lanes that must remain unchanged
until separate evidence and authorization exist. It keeps `order_allowed=false`,
`live_policy_change_allowed=false`, `entry_dedup_policy_change_allowed=false`,
and `position_or_oco_mutation_allowed=false`.

Read-only post-deploy guardrail acceptance smoke for the BTC anti-wick and
event-risk-control issue handoffs:

```powershell
.\scripts\smoke_guardrail_acceptance_ssh.ps1
```

Read-only live-readiness audit before any explicit live enablement:

```powershell
.\scripts\audit_live_readiness_ssh.ps1
```

This prints masked server env status, order-capable flags, dry-run flags,
server-local MCP readiness surfaces, runtime-log smoke, machine-readable
`readiness_details`, warnings, blockers, and a final verdict.
`missing_readiness_detail_fields=[]` is required before the audit's MCP
readiness details can be used as complete live-review evidence.
`verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED` means the operator may
review a separately authorized live-change plan; the script never changes
production env, DB, order, OCO, grid, Earn, fund, or Telegram state.

Read-only tiny-live loss hard-stop RCA when live-readiness reports
`risk_hard_stop` or `AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES`:

After TinyLive has been explicitly authorized and enabled, use the live-aware
readiness audit mode:

```powershell
.\scripts\audit_live_readiness_ssh.ps1 -LiveAuthorized
```

This treats `TRADING_OKX_ENABLED=true` plus
`TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true` / dry-run false as expected
evidence, while still failing closed on other order-capable flags, missing
secrets, missing TinyLive hard-scope proof, order-sent markers, runtime errors,
guardian write mode, and non-R0 event risk.

After the first authorized TinyLive execution, run the post-trade evidence smoke:

```powershell
.\scripts\smoke_tiny_live_post_trade_ssh.ps1
```

Before a new execution appears it prints
`post_trade_status=PENDING_NO_NEW_TINY_LIVE_EXECUTION`. After execution it
checks TinyLive audit rows, OCO attach/protection evidence, runtime
`orderSentEvidence`, active execution events, and TinyLive Telegram history.

```powershell
.\scripts\smoke_tiny_live_loss_rca_ssh.ps1
```

After a separately authorized evidence-only review path has collected fresh
tiny-live proof, run the hard gate:

```powershell
.\scripts\smoke_tiny_live_loss_rca_ssh.ps1 -RequireClear
```

This calls server-local `/api/mcp` only and summarizes tiny-live execution
readiness, auto-approval blockers, recent tiny-live audit rows, autonomous
execution attribution, missed-opportunity context, and monitor/rollout status.
`missing_tiny_live_fields` must be empty before the tiny-live RCA can clear the
hard-stop or rollout live-readiness gates.
`-RequireClear` exits 0 only when `hardStopDetected=false`,
`missing_tiny_live_fields=[]`, and `canEnableProduction=true`; otherwise it
exits non-zero after printing RCA details.
The default 30-day window matches the consecutive-loss guard used by the
auto-approval policy.
If an operator separately authorizes a bounded tiny-live launch, the production
env may set `TRADING_TINY_LIVE_AUTO_APPROVAL_IGNORE_CONSECUTIVE_LOSS_HARD_STOP=true`
to override only that consecutive-loss blocker. It does not bypass the current
BUY-candidate, OCO preflight, EV, runtime-evidence, daily loss budget, scope,
duplicate, open-position, notional, or event-risk gates.
It does not place orders, enable scheduler/live flags, send Telegram, modify
OCO, or change production env/DB state.

Read-only runtime-evidence gap RCA when live-readiness reports
`runtime_evidence_gap`, `RUNTIME_EVIDENCE_MISSING`, or
`runtimeEvidenceStatus=NOT_READY_*`:

```powershell
.\scripts\smoke_runtime_evidence_rca_ssh.ps1
```

After a separately authorized evidence-only env change and restart, run the
hard gate:

```powershell
.\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady
```

This calls server-local `/api/mcp` only and classifies the gap as disabled
collection, no canonical rows, canonical rows without shadow intent, canonical
shadow-ready, or requiring operator review. It does not write
RuntimeDecisionEvidence, place orders, enable flags, send Telegram, or change
production env/DB state. It also prints `runtime_evidence_review_plan`, a
machine-readable review-routing list with `gate`, `state`, `riskCategory`,
`evidenceMarkers`, `requiredEvidence`, `nextAction`, and `notAuthorization`;
this is not authorization to mutate production env or enable live behavior.
`missing_runtime_evidence_fields` must be empty before
`CANONICAL_SHADOW_READY` can clear the runtime-evidence review gate.
The full bundle also requires `runtime_evidence_review_plan` to be present and
free of `BLOCKED` or `HARD_BLOCKED` states when the diagnosis is otherwise
ready.
`-RequireReady` exits 0 only when `diagnosis=CANONICAL_SHADOW_READY`,
`missing_runtime_evidence_fields=[]`, `shadowIntentCount > 0`, and
`orderSentEvidence=0`; otherwise it exits non-zero after printing RCA details.

Read-only signal-correctness and policy review smoke before any live scope
expansion:

```powershell
.\scripts\smoke_signal_correctness_ssh.ps1
```

After a separately authorized evidence-only review path has resolved signal
policy gaps, run the hard gate:

```powershell
.\scripts\smoke_signal_correctness_ssh.ps1 -RequireClear
```

This calls server-local `/api/mcp` only and checks strategy execution parity,
blocked-signal outcome quality, DataFreshnessGuard current status, governance
drift, EntryDedup governance, missed-opportunity regression, and the no-buy
reason truth table. `REVIEW_POLICY_GAPS` or unresolved signal correctness /
governance drift findings are live blockers, not permission to relax policy.
`missing_signal_policy_fields=[]` is required; absent reviewed signal-policy
fields are not treated as passing evidence.
`-RequireClear` exits 0 only when there are no `REVIEW_POLICY_GAPS`,
`missing_signal_policy_fields=[]`, 7d governance drift is not `TOO_STRICT`,
`TOO_LOOSE`, or `INSUFFICIENT_DATA`, and missed-opportunity
`overallStatus=PASS`; otherwise it exits non-zero after printing the review
details, including `signalPolicyClear` and the machine-readable
`signal_policy_review_plan`. That plan lists the blocked/review gates,
`riskCategory`, `evidenceMarkers`, `requiredEvidence`, and `notAuthorization`
for operator review; it is not live approval and must not be used to enable
trading or relax policy by itself.
The full bundle also requires `signalPolicyClear=true` and
`signal_policy_review_plan` to be present without `BLOCKED` or `REVIEW` states
when signal policy is otherwise clear.
The smoke also prints `dataFreshnessCurrentStatus`. `NO_CURRENT_SAMPLE` means
the read-only RCA did not observe a current DataFreshnessGuard sample; it is
blocked evidence, not proof of a stale source and not clearance to relax
DataFreshnessGuard.

Read-only entry/filter operator review packet:

```powershell
.\scripts\prepare_entry_filter_operator_review_packet_ssh.ps1 -RequireReview
```

This wraps `smoke_signal_correctness_ssh.ps1` into
`entry_filter_operator_review_packet` and
`entry_filter_operator_packet_status`. `REVIEW_REQUIRED_NOT_POLICY_CHANGE`
means governance drift or missed-opportunity rows are reviewable but not safe
to treat as EntryDedup/DataFreshness/live policy approval. The packet does not
deploy, enable live trading, relax EntryDedup/DataFreshness/live policy, place
orders, modify OCO, close positions, or mutate DB/grid/fund/Earn state.

Read-only no-buy row review packet:

```powershell
.\scripts\prepare_no_buy_row_review_packet_ssh.ps1 -RequireReview
```

This converts the signal-correctness `rowActions`, high-return no-buy
breakdown, and no-buy truth table into `no_buy_row_review_packet` with
`rowActionFamilyCounts`. `REVIEW_REQUIRED_NOT_EXPERIMENT` means the rows are
useful for operator review but still blocked by governance/missed-opportunity
or signal-policy evidence. `READY_FOR_SHADOW_DESIGN_NOT_LIVE` means the rows can
be used to draft a bounded shadow design only. The packet does not deploy,
enable live trading, relax EntryDedup/DataFreshness/live policy, place orders,
modify OCO, close positions, or mutate DB/grid/fund/Earn state.

Read-only missed-opportunity shadow design packet preflight:

```powershell
.\scripts\prepare_missed_opportunity_shadow_design_packet_ssh.ps1 -RequireReview
```

This wraps the no-buy row packet and extracts only `MISSED_OPPORTUNITY_REVIEW`
rows into `missed_opportunity_shadow_design_packet`. It emits
`shadow_design_review_allowed`, `tiny_live_order_allowed=false`, and
`live_policy_change_allowed=false`. `BLOCKED_SIGNAL_POLICY_REVIEW_REQUIRED`
means the candidate row can be reviewed but still cannot be used to draft a
shadow/tiny-live experiment until signal policy, governance drift, and
missed-opportunity regression are clear. `READY_FOR_MISSED_OPPORTUNITY_SHADOW_DESIGN_NOT_LIVE`
is shadow-design-only evidence, not live execution approval. The packet does
not deploy, enable live trading, execute tiny-live orders, relax
EntryDedup/DataFreshness/live policy, place orders, modify OCO, close positions,
or mutate DB/grid/fund/Earn state.

Read-only governance relaxation review packet:

```powershell
.\scripts\prepare_governance_relaxation_review_packet_ssh.ps1 -RequireReview
```

This wraps `smoke_signal_correctness_ssh.ps1` and the underlying
`findGovernanceRelaxationCandidates` evidence into
`governance_relaxation_review_packet`. It emits
`shadow_governance_review_allowed`, `tiny_live_order_allowed=false`, and
`live_policy_change_allowed=false`. `REVIEW_REQUIRED_NOT_POLICY_CHANGE` means
relaxation candidates are reviewable but blocked by signal-policy,
governance-drift, or missed-opportunity evidence.
When `NO_EVIDENCE` is caused by `NO_CURRENT_SAMPLE` and zero relaxation
candidates, the packet routes the next read-only follow-up to the DataFreshness
profit blocker brief and no-buy attention flow review before any
DataFreshness/governance policy review.
`READY_FOR_GOVERNANCE_SHADOW_REVIEW_NOT_LIVE` is shadow-review-only evidence,
not approval to relax live policy. The packet does not deploy, enable live
trading, execute tiny-live orders, relax EntryDedup/DataFreshness/live policy,
place orders, modify OCO, close positions, or mutate DB/grid/fund/Earn state.

Read-only governance relaxation preflight review packet:

```powershell
.\scripts\prepare_governance_relaxation_preflight_review_packet.ps1 -RequireReady
```

This reads an existing governance relaxation review log, normally
`target/profit-review/governance-relaxation-review-packet-latest.log`, plus the
latest no-buy attention packet when present, normally
`target/profit-review/no-buy-attention-flow-review-packet-latest.log`, and emits
`governance_relaxation_preflight_review_packet` plus
`governance_relaxation_preflight_status`. A
`READY_FOR_GOVERNANCE_RELAXATION_PREFLIGHT_REVIEW_NOT_LIVE` status means the
blocked or shadow-only governance relaxation review can be attached to operator
review; it does not permit any policy change. If the source review packet is
valid but `NO_EVIDENCE`, the preflight normally emits
`BLOCKED_SOURCE_GOVERNANCE_RELAXATION_EVIDENCE`; when the no-buy attention
packet is ready it carries `no_buy_attention_next_action`,
`no_buy_signal_eval_near_threshold_gap_count`, and the closest threshold-gap
evidence so the next action points at the completed no-buy/threshold review
instead of telling the operator to rerun the same source log. If the parsed
source packet has zero relaxation candidates, no missed eval/order bug,
`missedOpportunityStatus=PASS`, and zero suspicious/false-block/high-return
no-buy counts, the preflight emits
`NO_GOVERNANCE_RELAXATION_CANDIDATES_NOT_LIVE`; that is neutral no-action
evidence for this lane only, not shadow-review readiness or policy approval.
The preflight
accepts both the legacy `READY_FOR_ATTENTION_NO_BUY_FLOW_REVIEW_NOT_LIVE`
status and the current `READY_FOR_ATTENTION_FLOW_REVIEW_NOT_LIVE` status as
ready no-buy attention routing evidence. It keeps
`live_policy_change_allowed=false`, `tiny_live_order_allowed=false`,
`entry_dedup_policy_change_allowed=false`,
`data_freshness_policy_change_allowed=false`, `order_allowed=false`, and
`telegram_send_allowed=false`. It does not rerun SSH, deploy, change production
env, relax governance/EntryDedup/DataFreshness/live policy, enable live or
staged execution, place orders, modify OCO, or send Telegram.

DataFreshness shadow candidate packet:

```powershell
.\scripts\prepare_data_freshness_shadow_candidate_packet_ssh.ps1 -RequireReview
```

This read-only packet joins the governance relaxation candidate evidence with
the DataFreshness counterfactual replay-input smoke into
`data_freshness_shadow_candidate_packet`. It emits
`data_freshness_shadow_candidate_packet_status`,
`shadow_candidate_review_allowed`, `data_freshness_policy_relaxation_allowed=false`,
`tiny_live_order_allowed=false`, and `live_policy_change_allowed=false`.
It also carries `replay_input_stage`, `collector_status_counts`,
`hard_gate_preview_status_counts`, `replay_input_next_action`,
`counterfactualEvidenceClass`, and `replayInputEvidenceMarkers` from the
counterfactual smoke so pre-collector historical samples are not mistaken for
enabled replay evidence.
`BLOCKED_COUNTERFACTUAL_REPLAY_INPUT_MISSING` means the DataFreshness candidate
is still missing complete replayable rows or counterfactual fields.
`BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE` means the current evidence is
historical proxy data that predates replay-id/collector evidence and is not
shadow-reviewable.
`READY_FOR_DATAFRESHNESS_SHADOW_CANDIDATE_NOT_LIVE` is shadow-candidate review
evidence only. The packet does not deploy, restart, change production env,
enable live trading, relax DataFreshnessGuard, execute tiny-live orders, place
orders, modify OCO, close positions, or mutate DB/grid/fund/Earn state.

Local DataFreshness replay blocker decision packet from the latest saved profit
matrix:

```powershell
.\scripts\prepare_data_freshness_replay_blocker_decision_packet.ps1 -RequireBlocked
```

This reuses `target/profit-review/latest-profit-operator-matrix.path` and emits
`data_freshness_replay_blocker_decision_packet` plus
`data_freshness_replay_blocker_decision_status`. It does not rerun SSH. A
`READY_FOR_DATAFRESHNESS_REPLAY_BLOCKER_OPERATOR_DECISION_NOT_LIVE` status
means the blocker packet is ready for operator review as a wait/refresh
decision, not that DataFreshness is shadow-reviewable. It preserves
`complete_replayable_candidate_rows=0`,
`shadow_candidate_review_allowed=false`,
`data_freshness_policy_relaxation_allowed=false`, and `order_allowed=false`;
it does not relax DataFreshnessGuard, enable live/staged-add/tiny-live
execution, place orders, modify OCO, deploy, or change production env.

Local DataFreshness replay blocker preflight review packet:

```powershell
.\scripts\prepare_data_freshness_replay_blocker_preflight_review_packet.ps1 -RequireReady
```

This invokes only the local DataFreshness replay blocker decision packet and
emits `data_freshness_replay_blocker_preflight_review_packet` plus
`data_freshness_replay_blocker_preflight_status`. A
`READY_FOR_DATAFRESHNESS_REPLAY_BLOCKER_PREFLIGHT_REVIEW_NOT_LIVE` status means
the wait/refresh blocker can be attached to review. It requires the source lane
to remain blocked, `complete_replayable_candidate_rows=0`, and
`shadow_candidate_review_allowed=false`; it keeps
`data_freshness_policy_relaxation_allowed=false`,
`data_freshness_shadow_review_allowed=false`,
`collector_activation_allowed=false`, `order_allowed=false`, and
`telegram_send_allowed=false`. It does not authorize DataFreshnessGuard
relaxation, DataFreshness shadow review, collector activation, live/staged-add/
tiny-live execution, orders, OCO mutation, deploy, production env changes, or
Telegram sends.

DataFreshness replay collector activation decision packet:

```powershell
.\scripts\prepare_data_freshness_replay_collector_activation_packet.ps1 -RequireDecisionReady
```

This local read-only packet reuses an existing replay evidence readiness log,
normally `target/profit-review/data-freshness-replay-evidence-readiness-refresh.log`,
and emits `data_freshness_collector_activation_packet` plus
`data_freshness_collector_activation_status`. It does not rerun SSH. A
`DATAFRESHNESS_REPLAY_COLLECTOR_ACTIVATION_DECISION_PACKET` with
`READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_OPERATOR_DECISION_NOT_LIVE` means
the next review item is whether to separately authorize evidence-only collector
activation, for example
`TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true`. It keeps
`collector_activation_allowed=false`,
`deploy_or_env_change_allowed=false`,
`data_freshness_policy_relaxation_allowed=false`, and `order_allowed=false`.
It is not authorization to deploy, change production env, relax
DataFreshnessGuard, enable live/staged-add/tiny-live execution, enable
scheduler mutation, send Telegram, place orders, modify OCO, or mutate
DB/grid/fund/Earn/exchange state.

When the source readiness log reports `replay_input_stage=NO_DATAFRESHNESS_SAMPLE`
with `complete_replayable_candidate_rows=0` and no fresh 1d/3d
DataFreshnessGuard terminal rows, this packet may still be
`READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_OPERATOR_DECISION_NOT_LIVE`.
That status only means the collector activation question is ready for separate
evidence-only operator review; it is not collector activation, deploy
authorization, DataFreshnessGuard relaxation, or live execution approval.

Read-only DataFreshness collector activation preflight review packet:

```powershell
.\scripts\prepare_data_freshness_collector_activation_preflight_review_packet.ps1 -RequireReady
```

This invokes only the local DataFreshness collector activation decision packet
and emits `data_freshness_collector_activation_preflight_review_packet` plus
`data_freshness_collector_activation_preflight_status`. A
`READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_PREFLIGHT_REVIEW_NOT_LIVE` status
means the evidence-only collector activation question can be reviewed, not that
the collector may be enabled. It keeps
`collector_activation_allowed=false`,
`deploy_or_env_change_allowed=false`,
`data_freshness_policy_relaxation_allowed=false`,
`data_freshness_shadow_review_allowed=false`, `order_allowed=false`, and
`telegram_send_allowed=false`. It is not authorization to activate the
collector, deploy, change production env, relax DataFreshnessGuard, enable live
or staged execution, place orders, modify OCO, or send Telegram.

Read-only profit operator next-action board:

```powershell
.\scripts\prepare_profit_operator_next_action_board.ps1 -RequireReady
```

This board combines the profit priority decision brief with the
strategy574/TinyLive governance operator packet, including any saved
strategy574 near-threshold shadow observation evidence. When the latest
`profit-live-blocker-audit-packet-latest.log` is present, add `-RequireAudit`
to require a fresh audit-backed queue across all live-blocker lanes. Pass a
fresh `-PriorityDecisionLogPath` when source refresh already saved
`profit-operator-priority-decision-brief-latest.log`, so the board reuses that
packet instead of rebuilding the heavy matrix chain. Reused priority logs are
still fail-closed against the underlying matrix evidence: the board emits
`source_priority_matrix_freshness_status`,
`source_priority_matrix_age_minutes`, and
`source_priority_matrix_fresh_for_board_max_age`, and it rejects a saved
priority packet when the wrapped matrix age exceeds the board `-MaxAgeMinutes`
even if the priority log file itself is fresh. It emits
`profit_operator_next_action_board_packet`,
`profit_operator_next_action_board_status`,
`profit_operator_next_action_audit_counts`, and
`profit_operator_next_action_audit_review_queue`. A
`PROFIT_OPERATOR_NEXT_ACTION_BOARD` with
`READY_FOR_PROFIT_OPERATOR_NEXT_ACTION_REVIEW_NOT_LIVE` keeps review work
ordered from trailing-stop dry-run, strategy485 risk-reduction shadow,
EntryDedup semantics shadow, DataFreshness evidence collector/blocker review,
TP/SL/OCO feasibility, strategy574/TinyLive governance, and aggregate profit
priority context. It keeps `tiny_live_order_allowed=false`,
`live_policy_change_allowed=false`, `scheduler_enablement_allowed=false`,
`deploy_or_env_change_allowed=false`, `order_allowed=false`,
`telegram_send_allowed=false`, and `strategy574_threshold_relaxation_allowed=false`.

Read-only profit operator authorization request packet:

```powershell
.\scripts\prepare_profit_operator_authorization_request_packet.ps1 -RequireReady
```

This packet consumes the saved
`profit-operator-next-action-board-latest.log` and
`profit-live-blocker-audit-packet-latest.log` only. It emits
`PROFIT_OPERATOR_AUTHORIZATION_REQUEST_PACKET`,
`profit_operator_authorization_request_status`,
`profit_operator_authorization_request_next_authorization_required`, and a
review queue with exact per-lane authorization text. A ready packet means the
operator can review the listed lanes separately; it is not live approval. It
keeps `live_policy_change_allowed=false`,
`scheduler_enablement_allowed=false`, `order_allowed=false`,
`position_or_oco_mutation_allowed=false`, `deploy_or_env_change_allowed=false`,
`telegram_send_allowed=false`, and
`db_grid_fund_earn_exchange_mutation_allowed=false`.

Read-only profit live blocker audit packet:

```powershell
.\scripts\prepare_profit_live_blocker_audit_packet.ps1 -RequireAuditReady
```

This reads existing local profit-review logs and emits
`profit_live_blocker_audit_packet` with
`packetType=PROFIT_LIVE_BLOCKER_AUDIT_PACKET` and
`liveReadinessConclusion=NOT_READY_FOR_LIVE_ENABLEMENT`. It audits
profit-priority, trailing dry-run, strategy485 risk reduction, strategy485
risk escalation, EntryDedup semantics, DataFreshness replay blocker,
DataFreshness collector activation, TP/SL/OCO feasibility,
strategy574/TinyLive governance, and governance relaxation lanes. Missing or
stale source logs are blockers, not passes. This
falls back to the source governance relaxation review packet when the
governance preflight packet is not saved, so `NO_EVIDENCE` remains blocker
evidence instead of being treated as a pass or ignored missing input. It also
emits `profit_live_blocker_no_action_count`; no-action statuses such as
Strategy485 `NO_POSITION_RISK_ACTION` and governance preflight
`NO_GOVERNANCE_RELAXATION_CANDIDATES_NOT_LIVE` are neutral evidence, not
primary blockers, review-ready action packets, or live approval. This
audit is for choosing the next read-only evidence refresh; it does not rerun
SSH, call MCP, deploy, change production env, enable live/TinyLive/scheduler,
place orders, send Telegram, modify OCO, or relax EntryDedup/DataFreshness/live
policy.

Read-only source refresh plan for the profit live blocker audit:

```powershell
.\scripts\prepare_profit_live_blocker_source_refresh.ps1 -PlanOnly
```

To refresh the source logs, run the same script without `-PlanOnly`. It
orchestrates existing read-only SSH/MCP/SELECT evidence scripts plus local
packet assembly, writes every source log consumed by
`prepare_profit_live_blocker_audit_packet.ps1`, writes
`trailing-stop-dry-run-preflight-review-packet-latest.log` from the freshly
saved trailing decision log, writes the trailing dry-run activation,
post-opt-in readiness, observation status, and
`profit-next-execution-blocker-packet-latest.log` chain from the same refreshed
evidence set, then reruns the audit, writes
`profit-operator-next-action-board-latest.log` with `-RequireAudit`, and saves
`profit-operator-authorization-request-latest.log` so the next exact operator
review authorization is refreshed with the same evidence set. It finishes with
`profit-operator-quick-status-latest.log`, which surfaces the saved
profit-next-execution blocker fields without rerunning SSH or refreshing the
blocker again. It
refreshes the no-buy attention-flow packet before governance preflight so
governance `NO_EVIDENCE` can inherit the latest no-buy/threshold-gap routing
or become `NO_GOVERNANCE_RELAXATION_CANDIDATES_NOT_LIVE` when the parsed source
packet proves there are no relaxation candidates and no false-block/high-return
no-buy pressure.
The EntryDedup semantics step uses the reviewed 720h / 24h forward / 50-row
window so source refresh does not downgrade a 30-day EntryDedup opportunity set
into a short-window `NO_EVIDENCE` blocker.
When `target/profit-review/latest-profit-operator-matrix.path` points at a
fresh matrix within `-MatrixMaxAgeMinutes`, the source refresh automatically
reuses it through `-MatrixOutputPath` so the live-blocker refresh does not
rerun the heavy SSH matrix chain on every pass. Use
`-ForceFreshProfitOperatorMatrix` only when a fresh matrix rebuild is explicitly
needed.
Long steps emit `step_heartbeat`, `step_timeout`, and `step_complete` markers
from the source-refresh wrapper itself, so a fresh matrix rebuild is visible
while it is still running.
It preserves governance relaxation `NO_EVIDENCE` or `NOT_READY` output as
blocker evidence instead of forcing that source step to fail early, except when
the local preflight emits the explicit neutral
`NO_GOVERNANCE_RELAXATION_CANDIDATES_NOT_LIVE` no-action status. The final
`-RequireAuditReady` audit still exits non-zero until live-readiness remains
properly non-enabled and any required operator authorizations are separate. It
does not deploy, change production env, enable live/TinyLive/scheduler, place
orders, send Telegram, modify OCO, close positions, or relax
EntryDedup/DataFreshness/live policy.

Focused strategy 574 signal/governance RCA:

```powershell
.\scripts\smoke_strategy574_signal_governance_ssh.ps1
```

This read-only smoke compares 1d/3d/7d/14d governance drift, extracts strategy
574 no-buy rows from missed-opportunity and truth-table evidence, checks current
DataFreshness, TinyLive trigger, and autonomous readiness markers, and prints a
bounded conclusion such as `WAIT_BUY_THRESHOLD_CROSS` or
`DO_NOT_RELAX_ENTRY_DEDUP_OR_DATAFRESHNESS_LIVE`. It is evidence only and does
not authorize policy relaxation or live mutations.

Read-only strategy 574 signal review gate:

```powershell
.\scripts\prepare_strategy574_signal_review_gate_ssh.ps1
```

This gate combines origin-delta and strategy 574 governance evidence into
`deploy_required_before_strategy574_review`,
`shadow_observation_review_allowed`, `tiny_live_order_allowed=false`,
`live_policy_change_allowed=false`, and `strategy574_signal_review_gate_status`.
It may route continued read-only observation, but it never authorizes
pre-buying, TinyLive order execution, or EntryDedup/DataFreshness relaxation.

Strategy574/TinyLive governance operator packet from refreshed read-only logs:

```powershell
.\scripts\prepare_strategy574_tiny_live_governance_operator_packet.ps1
```

Before running it, refresh and save the source logs:
`.\scripts\prepare_strategy574_signal_review_gate_ssh.ps1` to
`target/profit-review/strategy574-signal-review-gate-refresh.log` and
`.\scripts\smoke_tiny_live_loss_rca_ssh.ps1` to
`target/profit-review/tiny-live-loss-rca-refresh.log`. If threshold relaxation
is being reviewed, also save
`.\scripts\smoke_strategy574_near_threshold_shadow_observation_ssh.ps1` to
`target/profit-review/strategy574-near-threshold-shadow-observation-latest.log`.
The packet emits
`strategy574_tiny_live_governance_operator_packet` and
`strategy574_tiny_live_governance_status`. A
`STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_PACKET` with
`READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_REVIEW_NOT_LIVE` means the
evidence is usable for operator review only; it keeps
`tiny_live_order_allowed=false`, `live_policy_change_allowed=false`,
`scheduler_enablement_allowed=false`, `deploy_or_env_change_allowed=false`,
`order_allowed=false`, `telegram_send_allowed=false`, and
`strategy574_near_threshold_threshold_relaxation_allowed=false`. A
`STRATEGY574_NEAR_THRESHOLD_FALSE_POSITIVE_RISK_HIGH` recommendation is
negative evidence for threshold relaxation in the current window, not a
TinyLive/live approval route.

Focused strategy 485 open-position risk RCA:

```powershell
.\scripts\smoke_strategy485_position_risk_ssh.ps1
```

This read-only smoke reviews SCORE_BUY strategy 485 open positions, OCO health,
position-defense status, active-position EV, TP stretch, stop-sweep policy,
recent closed trades, execution events, and 3-month PnL through server-local
`/api/mcp`. It prints `strategy485_position_risk_recommendation` such as
`REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY` plus
`strategy485_position_review_decision`, a machine-readable routing object with
OCO health, position EV counts, close/modify suggestions, timeout and TP
stretch counts, per-position EV summaries, required evidence, next action, and
non-authorization text. This is review routing only and does not authorize
closing positions or modifying OCO.
Use `docs/strategy485-aged-position-review-plan.md` before drafting any
operator packet for aged negative-EV strategy 485 positions. The plan requires
fresh OCO, active-position EV, TP stretch, stop-sweep, timeout, recent-closed,
and monthly PnL evidence, and it keeps close-position/OCO-modification actions
behind separate explicit authorization.

Read-only strategy 485 position review gate:

```powershell
.\scripts\prepare_strategy485_position_review_gate_ssh.ps1
```

This gate combines origin-delta and strategy 485 position-risk evidence into
`deploy_required_before_strategy485_review`,
`operator_review_packet_allowed`, `position_or_oco_mutation_allowed=false`, and
`strategy485_position_review_decision`, and
`strategy485_position_review_gate_status`. It may route a separate operator
review packet, but it never authorizes close-position or OCO modification.

Read-only strategy 485 operator review packet preflight:

```powershell
.\scripts\prepare_strategy485_operator_review_packet_ssh.ps1 -RequireReady
```

This wraps the strategy 485 gate into a machine-readable
`strategy485_operator_review_packet` and emits
`strategy485_operator_packet_status`. `READY_FOR_OPERATOR_PACKET_NOT_MUTATION`
means the packet can be attached to a separate operator review; it still keeps
`position_or_oco_mutation_allowed=false` and does not authorize close-position
or OCO modification.

Use `prepare_exit_side_profit_review_packet_ssh.ps1 -RequireReady` when the
operator needs one read-only packet covering both trailing-stop PnL acceptance
and strategy 485 aged negative-EV position risk. The combined packet emits
`exit_side_profit_review_packet_status` and stays non-mutating.

Read-only auto-trading review bundle:

```powershell
.\scripts\smoke_auto_trading_review_bundle_ssh.ps1
```

This wrapper runs the origin-delta classifier, live-authorized audit, strategy
485 position-risk smoke, strategy 574 signal/governance smoke, and TinyLive
post-trade smoke, then prints `auto_trading_review_recommendation`. It is a
review bundle only; it does not deploy, restart, close positions, modify OCO, or
relax live policy.

Read-only auto-trading review gate:

```powershell
.\scripts\prepare_auto_trading_review_gate_ssh.ps1
```

This gate converts the auto-trading review bundle into
`deploy_required_before_auto_trading_review`, `operator_review_packet_allowed`,
`position_or_oco_mutation_allowed=false`, `tiny_live_order_allowed=false`,
`live_policy_change_allowed=false`, and `auto_trading_review_gate_status`. It
may route a separate operator packet for read-only position review, but it never
authorizes close-position, OCO modification, pre-buying, TinyLive order
execution, or live policy changes.

Read-only profit-candidate review:

```powershell
.\scripts\smoke_profit_candidate_review_ssh.ps1
```

This smoke ranks current risk-adjusted profit-improvement candidates from
server-local `/api/mcp` evidence: 3-month PnL, enabled strategy scorecard,
ExpectedValueGate stats, signal accuracy, blocked-signal outcomes, missed
opportunity and no-buy truth-table rows, shadow readiness, activation
candidates, and trailing-stop PnL replay. It prints
`profit_candidate_items` and `profit_candidate_review_recommendation`, for
example `REVIEW_DATAFRESHNESS_FALSE_KILL_WITH_SHADOW_REPLAY`. The result is
evidence only; it does not authorize live trading, policy relaxation, strategy
activation, closing positions, or OCO changes. This direct smoke does not run
origin-delta/currentness checks; use
`smoke_profit_improvement_review_bundle_ssh.ps1` or
`prepare_profit_experiment_gate_ssh.ps1` before treating the evidence as
current-post-deploy profit review input.
The default trailing replay sample for this smoke is `TrailingLimit=500`, so
profit routing uses the same hard trailing sample size as issue acceptance and
dry-run activation review.

Read-only profit loss review gate:

```powershell
.\scripts\prepare_profit_loss_review_gate_ssh.ps1
```

This gate combines origin-delta and profit-candidate evidence into
`deploy_required_before_profit_loss_review`, `loss_source_review_allowed`,
`live_policy_change_allowed=false`, `position_or_oco_mutation_allowed=false`,
`tiny_live_order_allowed=false`, and `profit_loss_review_gate_status`. It can
route a separate read-only loss-source review packet, but it never authorizes
DataFreshness relaxation, close-position, OCO modification, TinyLive order
execution, deploy, or live policy changes.
The default child trailing replay sample is `TrailingLimit=500`; use a smaller
limit only for diagnostics, not for loss-source routing.

Read-only post-deploy profit validation:

```powershell
.\scripts\smoke_post_deploy_profit_validation_ssh.ps1
```

This aggregate gate runs the auto-trading review gate, profit loss review gate,
and profit experiment gate after a separately authorized deploy. It emits
`deploy_required_before_post_deploy_profit_validation`,
`origin_runtime_delta_paths`, `origin_runtime_delta_impact`,
`post_deploy_profit_validation_status`,
`post_deploy_profit_validation_missing_requirements`,
`data_freshness_counterfactual_gate_missing_requirements`,
`strategy485_position_review_decision`,
`post_deploy_profit_validation_review_plan`,
`post_deploy_profit_validation_blocker_summary`,
`post_deploy_profit_validation_review_decision`,
`live_policy_change_allowed=false`, `position_or_oco_mutation_allowed=false`,
and `tiny_live_order_allowed=false`. It is a read-only readiness matrix for
profit review packets and does not deploy, restart, change production env, relax
policy, place orders, or modify OCO/grid/fund/Earn state.
DataFreshness false-kill candidates stay blocked until replayable candidate rows
and all counterfactual fields are present.
If the DataFreshness counterfactual smoke returns
`COUNTERFACTUAL_NOT_REPLAYABLE_CANDIDATE_SNAPSHOT_MISSING`, the profit
improvement bundle must still emit a ranked DataFreshness scorecard lane with
`BLOCKED_WAIT_REPLAY_EVIDENCE`; an empty scorecard is a tooling regression, not
proof that there is no candidate.
The blocker summary is a machine-readable routing copy of the blocked child
gate evidence; each entry preserves `requiredEvidenceCount`, `requiredEvidence`,
`nextAction`, `runtimeDrift`, and no-live authorization text, and does not clear
blockers.
When `origin_delta_status=RUNTIME_DRIFT`, the aggregate output also carries
`server_worktree_commit`, `origin_main_commit`, `origin_runtime_delta_files`,
`origin_runtime_delta_paths`, and `origin_runtime_delta_impact`, so the
deploy-first blocker points at the runtime files and evidence categories that
must be refreshed before profit evidence is trusted.
The review decision is the top-level machine-readable routing object; it
includes `canPrepareReviewPacket`, `deployRequired`, `allowedReviewTypes`,
`blockerCount`, `blockedGateCount`, `blockedGates`,
`missingRequirementCount`, `runtimeDrift`, and no-live authorization text.

Read-only profit runtime deploy review packet:

```powershell
.\scripts\prepare_profit_runtime_deploy_review_packet_ssh.ps1 -RequireReady
```

This combines the origin-delta classifier and post-deploy profit validation
into `profit_runtime_deploy_review_packet` and
`profit_runtime_deploy_packet_status`. `READY_FOR_DEPLOY_REVIEW_NOT_DEPLOYED`
means the packet can be attached to a separate deploy authorization request; it
does not deploy, restart, reload nginx, change production env, enable live
trading, relax policy, place orders, modify OCO, or mutate DB/grid/fund/Earn
state.

Read-only profit blocker ledger:

```powershell
.\scripts\prepare_profit_blocker_ledger_ssh.ps1 -RequireActionable
```

This combines the runtime deploy packet, shadow experiment packet, strategy 485
operator packet, and DataFreshness replay observation bundle into
`profit_blocker_ledger_packet`, `profit_blocker_ledger_items`, and
`profit_blocker_ledger_status`. `BLOCKED_DEPLOY_CURRENT_RUNTIME` means runtime
currentness is still the first blocker; the ledger does not deploy, restart,
reload nginx, change production env, enable live trading, relax policy, place
orders, modify OCO, close positions, or mutate DB/grid/fund/Earn state.

Read-only profit readiness brief:

```powershell
.\scripts\prepare_profit_readiness_brief_ssh.ps1 -RequireBrief
```

This combines signal correctness/missed-opportunity evidence, trailing-stop PnL
replay, the profit blocker ledger, and the EntryDedup operator decision brief
into `profit_readiness_brief_packet` and `profit_readiness_brief_status`. It
emits `entry_filter_lane_status`, `exit_lane_status`,
`entry_dedup_shadow_lane_status`, `entry_dedup_operator_decision_brief_status`,
and `trailing_stop_acceptance` so entry/filter policy blockers, EntryDedup
shadow review, and exit-side candidates can be reviewed separately. It does
not deploy, change production env, enable live trading, relax
EntryDedup/DataFreshness/live policy, place orders, modify OCO, close
positions, or mutate DB/grid/fund/Earn state.
It also carries `data_freshness_current_status` so no-current-sample evidence
is not confused with a source outage.
Long child smokes emit `child_start`, periodic `child_heartbeat`, and
`child_complete` markers. Use `-ChildTimeoutSeconds` to fail a stuck child
locally without changing production state.

Read-only entry-filter blocker decision brief:

```powershell
.\scripts\prepare_entry_filter_blocker_decision_brief_ssh.ps1 -RequireBrief
```

This narrows the current entry/filter blocker into
`entry_filter_blocker_decision_brief_packet` and
`entry_filter_blocker_decision_brief_status`. It calls only signal correctness,
DataFreshness replay evidence readiness, and the EntryDedup operator decision
brief, then emits `entry_filter_policy_lane_status`,
`data_freshness_replay_lane_status`, `entry_dedup_shadow_lane_status`, and
`entry_filter_blocker_missing_requirements`. Use it when
`profit_readiness_brief_status=BLOCKED_ENTRY_FILTER_REVIEW` but the EntryDedup
shadow lane is ready. It does not deploy, change production env, enable live
trading, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO,
close positions, or mutate DB/grid/fund/Earn state.

Read-only signal/missed blocker decision brief:

```powershell
.\scripts\prepare_signal_missed_blocker_decision_brief_ssh.ps1 -RequireBrief
```

This is the focused follow-up when
`entry_filter_blocker_decision_brief_status=BLOCKED_SIGNAL_POLICY_OR_MISSED_OPPORTUNITY_REVIEW`.
It combines the entry/filter operator packet, no-buy row review packet,
missed-opportunity shadow design preflight, and governance relaxation review
packet into `signal_missed_blocker_decision_brief_packet` and
`signal_missed_blocker_decision_brief_status`. It emits
`entry_filter_operator_lane_status`, `no_buy_row_review_lane_status`,
`missed_opportunity_shadow_lane_status`, `governance_relaxation_lane_status`,
and `signal_missed_blocker_missing_requirements`. It does not deploy, change
production env, enable live trading, execute tiny-live orders, relax
EntryDedup/DataFreshness/live policy, place orders, modify OCO, close
positions, or mutate DB/grid/fund/Earn state.

Bounded read-only profit evidence watch:

```powershell
.\scripts\watch_profit_evidence_readiness_ssh.ps1 -MaxAttempts 3 -SleepSeconds 300
```

This wrapper repeatedly runs the profit readiness brief and DataFreshness
replay observation bundle, then emits `profit_evidence_watch_status`,
`attempt_data_freshness_current_status`, `attempt_replay_candidate_id_recommendation`,
and `attempt_replay_observation_bundle_recommendation`. It is intended for
waiting on new DataFreshness/replay evidence after the current result is
`PENDING_DATAFRESHNESS_CURRENT_SAMPLE`; `EVIDENCE_READY_FOR_REVIEW_NOT_LIVE`
means a separate read-only review can start, not live approval. It does not
deploy, change production env, enable live trading, relax
EntryDedup/DataFreshness/live policy, place orders, modify OCO, close
positions, or mutate DB/grid/fund/Earn state.
Long child smokes emit `child_start`, periodic `child_heartbeat`, and
`child_complete` markers from the watcher itself.

Bounded read-only profit next execution watch:

```powershell
.\scripts\watch_profit_next_execution_readiness_ssh.ps1 -MaxAttempts 3 -SleepSeconds 300
```

This watcher is narrower than the general profit evidence watcher: it tracks
the current highest-ROI next-execution lane by chaining
`prepare_data_freshness_replay_evidence_readiness_ssh.ps1` into
`prepare_profit_next_execution_blocker_packet.ps1`. Output includes
`profit_next_execution_watch_status`,
`profit_next_execution_watch_unique_blocker`,
`profit_next_execution_watch_observation_sample_ready`, and
`profit_next_execution_watch_data_freshness_complete_replayable_candidate_rows`.
It remains read-only and does not deploy, change production env, enable live
trading, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO,
close positions, or mutate DB/grid/fund/Earn state.

Consolidated remaining open issue status from saved local evidence logs:

```powershell
.\scripts\prepare_remaining_open_issues_status.ps1 -RequireBlocked
```

This local packet tracks the active open set #6/#7/#8. It reads the latest #6
profit-improvement bundle, #7 post-deploy bundle, #8 BUY-like loss packet, and
bounded profit evidence watcher logs, then emits
`REMAINING_OPEN_ISSUES_STATUS_PACKET`, `remaining_open_issues_status`,
`remaining_open_issues_global_blocker`, `issue6_status`, `issue7_remaining_blocker`,
`issue8_status`, `active_open_issue_numbers`, `active_remaining_issue_count`,
and `profit_evidence_watch_status`. Closed #9/#10/#11/#12 lanes may appear only
under completed context and do not affect the active remaining count, global
blocker, or next action. It does not run SSH or GitHub calls; refresh the
underlying read-only evidence logs first when the packet reports stale or
missing local evidence. If the full #7 bundle stops before its summary marker
because split-acceptance currentness fails on docs/tooling drift, the packet can
fall back to a fresh #7 collector post-activation status log for the blocker
classification. `NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS` keeps #6/#7
open until fresh post-collector replay rows appear, while #8 stays open until
the upstream BUY-like/DataFreshness terminal-flow gap is resolved. This packet does not close
issues, deploy, change production env, enable live trading, relax
EntryDedup/DataFreshness/live policy, place orders, modify OCO, close
positions, or mutate DB/grid/fund/Earn/Telegram/exchange state.

Read-only DataFreshness profit blocker brief:

```powershell
.\scripts\prepare_data_freshness_profit_blocker_brief_ssh.ps1
```

This narrower brief combines signal correctness current-source status, the
DataFreshness replay observation bundle, and the sample-gap RCA smoke, then emits
`data_freshness_profit_blocker_brief_packet` plus
`data_freshness_profit_blocker_status`. `PENDING_DATAFRESHNESS_CURRENT_SAMPLE`
means current DataFreshness evidence is still missing;
`READY_FOR_DATAFRESHNESS_REPLAY_REVIEW_NOT_LIVE` means a separate replay review
can start. The brief also surfaces DataFreshness sample recency from the replay
bundle, including latest row time, row age, 1d/3d/7d/14d/30d row counts, and
`data_freshness_sample_gap_status`. It now also carries
`data_freshness_sample_gap_rca_recommendation` plus recent audit, SIGNAL_EVAL,
BUY-like, ATTENTION_HIT, filter-block, and DataFreshness row counts, so
`NO_CURRENT_SAMPLE` can be separated from a no-BUY-style-candidate window before
any DataFreshness policy review. It does not deploy,
enable live trading, relax
EntryDedup/DataFreshness/live policy, place orders, modify OCO, close
positions, or mutate DB/grid/fund/Earn state.

Read-only profit candidate-flow review packet:

```powershell
.\scripts\prepare_profit_candidate_flow_review_packet_ssh.ps1 -RequireActionable
```

This wrapper combines the DataFreshness replay evidence readiness packet with
BUY-like candidate progression evidence into
`PROFIT_CANDIDATE_FLOW_REVIEW_PACKET`. It emits
`profit_candidate_flow_review_packet`, `profit_candidate_flow_review_status`,
`profit_candidate_flow_review_items`, `profit_candidate_flow_blockers`, and
`profit_candidate_flow_required_evidence`. A status such as
`READY_FOR_ENTRY_SKIP_CANDIDATE_FLOW_REVIEW_NOT_LIVE` means the next review lane
is EntryDedup/ShadowExecutionIntent row-level RCA and TP/SL/OCO shadow
feasibility; it does not authorize deploy, live trading, EntryDedup or
DataFreshness relaxation, order/OCO mutation, or production env changes.

Read-only profit operator review matrix:

```powershell
.\scripts\prepare_profit_operator_review_matrix_ssh.ps1 -RequireReviewItems
```

This wrapper combines the profit readiness brief, bounded evidence watch,
exit-side packet, and DataFreshness shadow candidate packet into
`profit_operator_review_matrix_packet`,
`profit_operator_review_items`, and `profit_operator_review_matrix_status`.
`HAS_REVIEW_READY_ITEMS_NOT_LIVE` means at least one lane, such as `exit-side`,
has enough read-only evidence for a separate operator review while other lanes
such as `entry-filter` or `data-freshness-replay` can remain blocked. It does
not treat `REVIEW_SIGNAL_POLICY` as an operator-ready entry-filter lane; the
entry-filter lane is ready only when the readiness brief reports `CLEAR`. It does
surface `data_freshness_shadow_candidate_packet_status`, including
`BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`, in the
`data-freshness-replay` lane so historical proxy blockers remain visible in the
operator overview. It does
not deploy, change production env, enable live trading, relax
EntryDedup/DataFreshness/live policy, place orders, modify OCO, close
positions, or mutate DB/grid/fund/Earn state.

Read-only profit operator action brief:

```powershell
.\scripts\prepare_profit_operator_action_brief_ssh.ps1 -RequireReady
```

This converts the review matrix into `profit_operator_action_items`,
`profit_operator_action_brief_packet`, and
`profit_operator_action_brief_status`. When the exit-side lane is ready, it
prints `READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE` and the recommendation
`REVIEW_EXIT_SIDE_TRAILING_AND_STRATEGY485_NOT_MUTATION`, while preserving
blocked entry-filter and DataFreshness replay actions. It also emits
`exit_side_operator_action_proposals`, splitting the ready exit-side lane into
trailing-stop rollout and strategy 485 risk-reduction proposal skeletons that
use proposal ids `trailing-stop-rollout-review` and
`strategy485-risk-reduction-review`, reference
`docs/exit-side-operator-review-plan.md`, and remain non-executable.
It also emits
`profit_operator_decision_lanes` / `decisionLanes` with machine-readable lane
classes such as `EXIT_SIDE_REVIEW_READY_NOT_LIVE`,
`ENTRY_FILTER_POLICY_BLOCKED`, and `DATAFRESHNESS_REPLAY_BLOCKED`, so the
operator dashboard can show ready and blocked profit lanes together. Long child
matrix runs emit `child_start`, periodic `child_heartbeat`, and
`child_complete` markers; `-ChildTimeoutSeconds` bounds a stuck child locally
without changing production state. Fresh action-brief runs give the nested
matrix child a separate outer timeout through `-MatrixTimeoutSeconds`; when it
is left at `0`, the wrapper derives a larger timeout from
`-ChildTimeoutSeconds` so the matrix can finish its own bounded child scripts
instead of being killed at the first inner-child limit. The brief prints
`source_matrix_timeout_seconds` and `child_timeout_seconds` for auditability.
Use `-SaveMatrixOutputPath` on a fresh run
to retain the raw matrix output, then use `-MatrixOutputPath` to rebuild the
action brief from that read-only evidence without rerunning the long SSH matrix.
Fresh SSH runs also call
`prepare_signal_missed_blocker_decision_brief_ssh.ps1` and emit
`profit_operator_signal_missed_blocker_decision` plus
`signal_missed_blocker_decision_brief_status` so entry-filter blockers point to
the current signal/missed/governance lane detail. If the matrix child does not
produce a usable packet but the signal/missed blocker child succeeds, the action
brief stays fail-closed with
`MATRIX_COLLECTION_INCOMPLETE_SIGNAL_MISSED_BLOCKER_COLLECTED` rather than
claiming review readiness. Matrix reuse mode prints
`signal_missed_blocker_decision_brief_status=NOT_COLLECTED_REUSED_MATRIX`,
because it intentionally avoids fresh SSH evidence collection.
Fresh runs default the saved matrix log to `target/profit-review/` and update
`target/profit-review/latest-profit-operator-matrix.path` so the next review can
find the latest evidence file. Use
`.\scripts\prepare_profit_operator_latest_action_brief.ps1 -RequireReady` to
rebuild from that latest pointer with the same freshness guard and without
rerunning the long SSH matrix. Use
`.\scripts\prepare_profit_operator_quick_status.ps1` as the fastest first check
of the latest saved matrix. It prints `profit_operator_quick_status_packet`,
`profit_operator_quick_status`, `profit_operator_quick_refresh_required`, and
the saved profit-next-execution blocker fields
`profit_operator_quick_next_execution_unique_blocker`,
`profit_operator_quick_next_execution_open_oco_positions`, and
`profit_operator_quick_next_execution_data_freshness_replay_candidate_id_rows`;
`REFRESH_REQUIRED_NO_MATRIX` or `REFRESH_REQUIRED_STALE_MATRIX` means a fresh
read-only matrix should be collected before using the operator status. It does
not rerun SSH, does not refresh the profit-next-execution blocker log, and does
not deploy. Use
`.\scripts\prepare_profit_aggressive_activation_operator_packet.ps1 -RequireReady`
to package a more aggressive review path. It emits
`PROFIT_AGGRESSIVE_ACTIVATION_OPERATOR_PACKET`,
`profit_aggressive_activation_options`, and exact authorization text for
`HIGH_RISK_MICRO_LIVE_PROBE`, `GRID10_EXISTING_ACTIVE_GRID_ORDER_PATH`, and
`EVIDENCE_ONLY_ACCELERATOR`. The packet is read-only: it keeps
`order_allowed=false`, does not deploy, does not change production env, and does
not enable live/TinyLive/scheduler/OCO/grid mutations by itself.
Use
`.\scripts\prepare_profit_operator_compact_status.ps1 -RequireReady` for the
fastest local check of the latest saved matrix; it prints
`profit_operator_compact_status_packet`, `profit_operator_compact_ready_lanes`,
`profit_operator_compact_blocked_lanes`, and
`profit_operator_compact_status=READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE` when the
fresh matrix still supports exit-side review. It does not rerun SSH and does
not deploy. Use
`.\scripts\prepare_profit_operator_review_summary.ps1 -RequireReady` to convert
the latest action brief into `profit_operator_review_summary_packet`, ready
lanes, exit-side proposals, blocked lanes, and required evidence for operator
review.
Then use `.\scripts\prepare_exit_side_operator_experiment_packet.ps1 -RequireReady`
to convert the fresh latest summary into
`EXIT_SIDE_OPERATOR_EXPERIMENT_REVIEW` with
`READY_FOR_EXIT_SIDE_EXPERIMENT_REVIEW_NOT_LIVE`, carrying
`trailing-stop-dry-run-experiment-review` and
`strategy485-risk-reduction-shadow-review` as review-only proposals.
Reused matrix output prints `source_matrix_mode=REUSED_OUTPUT_FILE`; fresh runs
print `source_matrix_mode=FRESH_CHILD_RUN`. Reused matrix output is guarded by
`-MatrixMaxAgeMinutes` (default `180`) and fails closed with
`matrix_freshness_status=STALE` when the saved evidence is too old. It does not deploy,
change production env, enable live trading, enable trailing, relax
EntryDedup/DataFreshness/live policy, place orders, modify OCO, close
positions, or mutate DB/grid/fund/Earn state.

Focused DataFreshness false-kill review:

```powershell
.\scripts\smoke_data_freshness_false_kill_review_ssh.ps1
```

This read-only smoke separates current data-source health from historical
DataFreshnessGuard false-kill pressure. It checks recent/current
DataFreshnessGuard RCA, blocked-signal outcomes, governance drift, relaxation
candidates, missed-opportunity regression, and the no-buy truth table, then
prints `data_freshness_false_kill_recommendation` and
`data_freshness_shadow_replay_plan`. A recommendation such as
`REVIEW_COLLECTOR_CADENCE_SHADOW_REPLAY_KEEP_HARD_GATE` is review routing only;
it does not authorize relaxing DataFreshnessGuard or changing live policy.

Focused DataFreshness executability review:

```powershell
.\scripts\smoke_data_freshness_executability_review_ssh.ps1
```

This read-only smoke checks whether the historical DataFreshness false-kill
alpha evidence is also executable evidence. It reviews the DataFreshness event
decision window, current autonomous readiness, runtime evidence rows, EV sample
coverage, OCO plan coverage, shadow-intent coverage, and TinyLive current
preview. It prints `missing_executability_evidence`,
`counterfactual_required_evidence`, and
`data_freshness_executability_recommendation`. A recommendation such as
`ALPHA_NOT_EXECUTABILITY_PROVEN_COLLECT_SHADOW_REPLAY` means the +24h
false-kill return is not enough to justify live policy changes.

Focused DataFreshness counterfactual replay-input review:

```powershell
.\scripts\smoke_data_freshness_counterfactual_review_ssh.ps1
```

This read-only smoke uses production MySQL `SELECT` queries to check whether
historical DataFreshness-only block rows have the candidate snapshots required
for a replay that removes only DataFreshnessGuard while keeping EV, OCO,
duplicate, daily-cap, exposure, event-risk, and other hard gates intact. It
prints `data_freshness_counterfactual_recommendation`,
`complete_replayable_candidate_rows`, `missing_counterfactual_fields`, and
forward-return proxy markers. It also prints `replay_input_stage`,
`collector_status_counts`, `hard_gate_preview_status_counts`,
`replay_input_next_action`, `preview_only_input_rows`, and `preview_only_note`;
these distinguish pre-collector historical samples, disabled trace-only
collector rows, preview-only rows, and real replayable candidates. Preview-only
rows prove placeholder field presence and terminal-block traceability only, not
evaluated EV/OCO/risk pass evidence. They do not count as
`complete_replayable_candidate_rows`. It is evidence only and does not
authorize DataFreshnessGuard relaxation or any live mutation.
Use `docs/data-freshness-shadow-replay-input-plan.md` before proposing any
collector or shadow/replay change for this path.
Use `docs/data-freshness-shadow-replay-collector-design.md` before implementing
any collector: the current L0 block returns before candidate/EV/OCO snapshots,
so a future collector must be disabled by default, keep DataFreshnessGuard as
the terminal live decision, create only replay evidence with a stable
`replayCandidateId`, and must not create live signals, send Telegram, place
orders, modify OCO, mutate positions, or change scheduler/live policy.
The tracked template and runtime config keep
`TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=false` /
`trading.data-freshness.shadow-replay.collector.enabled=false` as the default
guardrail.
The current `DataFreshnessShadowReplayCollector` hook is only a
disabled-by-default skeleton: when disabled it adds no executable replay
evidence, and when separately enabled it can only mark scalar snapshot fields
or fixed-config entry/TP/SL candidate snapshots as not replayable until EV,
TQS, OCO, and hard-gate fields are evaluated. Current EV/TQS/OCO/hard-gate
fields are explicit `NOT_EVALUATED_REPLAY_INPUT_ONLY` previews, not pass
evidence. Dynamic ATR candidate plans are not guessed. It does not create live
signals, send Telegram, place orders, modify OCO, or change policy.

Focused issue #7 BTCUSDT 1h filter-block false-kill review:

```powershell
.\scripts\smoke_filter_block_false_kill_issue7_ssh.ps1
.\scripts\prepare_filter_block_false_kill_issue7_packet.ps1 -RequireBlocked
.\scripts\smoke_data_freshness_replay_observation_bundle_ssh.ps1 *> target\profit-review\issue7-df-replay-observation-latest.log
.\scripts\prepare_filter_block_false_kill_issue7_close_readiness.ps1 -RequireBlocked
.\scripts\prepare_filter_block_false_kill_issue7_operator_handoff.ps1 -RequireActionable
.\scripts\prepare_filter_block_false_kill_issue7_collector_activation_review_packet.ps1 -RequireReady
```

This read-only smoke uses production MySQL `SELECT` queries only to rank
`BTCUSDT` `1h` `FILTER_BLOCK` blocker families by false-kill pressure. It
prints `filter_block_false_kill_rows`, `filter_block_correct_block_rows`,
`filter_block_avg_forward_24h_pct`, `False-Kill Source Ranking`,
`Actionable False-Kill Summary`,
`actionable_filter_block_false_kill_pct`,
`severe_stale_outage_rows_excluded`, `severe_stale_outage_incidents`,
`Actionable False-Kill Source Ranking`,
`DataFreshnessGuard RCA`, `data_freshness_stale_class_counts`,
`data_freshness_complete_replayable_candidate_rows`,
`data_freshness_preview_only_input_rows`, `data_freshness_trace_only_rows`,
`replay_input_stage`, `data_freshness_stale_minutes_min`,
`data_freshness_stale_minutes_avg`, `data_freshness_stale_minutes_max`,
`data_freshness_near_miss_rows`, `data_freshness_recoverable_grace_rows`,
`data_freshness_severe_stale_rows`,
`data_freshness_guard_optimization_verdict`, `collector_status_counts`, and
replayable candidate examples with entry, block reason, forward return,
`shouldHavePassedProxy`, collector stage, and missing replay fields.
The `DataFreshness Guard Optimization Counterfactual` section reports how many
historical rows a candidate stale-threshold grace would release, including
false-kill rows released and correct-block rows leaked. A verdict such as
`DO_NOT_RELAX_GRACE_FIX_COLLECTOR_OR_SOURCE_OUTAGE` means the sample is
severe-stale/outage evidence, so lowering false-kill pressure should start with
collector/source freshness and replay snapshots, not live guard relaxation.
The actionable false-kill section excludes severe DataFreshness outage rows from
the policy-error denominator and points `issue7_actionable_next_blocker` at the
next blocker family to optimize, so repeated outage audits do not hide the real
policy review queue.
When that next blocker is `ExpectedValueGate`, the smoke also prints
`ExpectedValueGate Optimization Counterfactual`,
`expected_value_gate_optimization_verdict`, and
`issue7_expected_value_gate_verdict`, comparing candidate `minExpectedR`
thresholds by false-kill rows released versus correct-block rows leaked.
It also prints `TP/SL Proxy Actionable Summary`, `tp_sl_proxy_verdict`, and
`issue7_tp_sl_proxy_verdict` so the actionable error rate can be narrowed from
24h close-positive rows to candidates that actually hit TP without also touching
SL in the 24h OHLC window. The EV section also carries
`expected_value_projected_actionable_false_kill_pct_after_review` for the
remaining non-EV blocker queue after a shadow-reviewed EV threshold candidate,
plus `expected_value_projected_next_blocker_after_review` for the next lane.
`shouldHavePassedProxy` is only a 24h forward-return
proxy and is not a live-policy pass verdict. A result such as
`DATAFRESHNESS_FALSE_KILL_PROXY_HIGH_PRE_REPLAY_COLLECTOR` means
DataFreshnessGuard is the leading profit false-kill source, but complete
liveSignal/replayCandidateId, entry/TP/SL, EV, OCO, and hard-gate snapshots are
still required before any live relaxation review. It does not authorize live
trading, scheduler changes, DataFreshnessGuard relaxation, DB writes, deploy,
or order/OCO/grid/fund/Earn/Telegram/exchange mutations.
The packet step reads the saved smoke log and emits
`issue7_filter_block_false_kill_packet`,
`issue7_filter_block_false_kill_status`, missing requirements,
`issue7_filter_block_false_kill_review_allowed`, and
`issue7_live_relaxation_allowed=false`. Status values distinguish
`BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`,
`BLOCKED_COLLECTOR_TRACE_ONLY_REPLAY_SNAPSHOTS_MISSING`,
`BLOCKED_PREVIEW_ONLY_REPLAY_SNAPSHOTS_NOT_EVALUATED`, and
`BLOCKED_DATAFRESHNESS_REPLAY_SNAPSHOTS_MISSING`. Use `-RequireBlocked` while complete
DataFreshness replay snapshots are still missing so accidental review-ready
classification fails closed.
The close-readiness step combines the issue #7 packet with the DataFreshness
replay observation bundle and emits `ISSUE7_CLOSE_READINESS_PACKET`,
`issue7_close_readiness_status`, `issue7_close_allowed`, and
`issue7_close_missing_requirements`. It allows closing issue #7 only when stable
`replayCandidateId` rows, complete replayable candidate snapshots, and
`missing_counterfactual_fields=[]` are present; otherwise it stays blocked with
`issue7_live_relaxation_allowed=false`.
The operator handoff step combines close-readiness with the DataFreshness
collector activation preflight packet and emits `ISSUE7_OPERATOR_HANDOFF_PACKET`
plus `issue7_operator_handoff_status`. A status of
`READY_FOR_EVIDENCE_COLLECTOR_REVIEW_NOT_CLOSEABLE` means #7 must stay open,
but the next review packet is ready for a separately authorized evidence-only
collector activation discussion. It keeps `collector_activation_allowed=false`,
`deploy_or_env_change_allowed=false`, `order_allowed=false`, and
`issue7_live_relaxation_allowed=false`.
The collector activation review packet emits
`ISSUE7_EVIDENCE_COLLECTOR_ACTIVATION_REVIEW_PACKET` and
`issue7_evidence_collector_activation_review_status`. A
`READY_FOR_ISSUE7_EVIDENCE_COLLECTOR_ACTIVATION_REVIEW_NOT_LIVE` status means
the review can discuss the single proposed separate env diff
`TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true`, required disabled
flags, post-change read-only verification, and stop conditions. It is not
authorization to enable the collector, deploy, change production env, close #7,
relax DataFreshnessGuard, enable live execution, place orders, modify OCO, or
send Telegram.
After a separately authorized evidence-only collector rollout, use the
post-activation status packet:

```powershell
.\scripts\prepare_filter_block_false_kill_issue7_collector_post_activation_status.ps1 -RequireBlocked
```

It emits `ISSUE7_COLLECTOR_POST_ACTIVATION_STATUS_PACKET`,
`issue7_collector_post_activation_status`, and `issue7_remaining_blocker`.
If local HEAD or the replay evidence runtime is ahead of the deployed service,
the packet reports `BLOCKED_DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE` with
`DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE`; request separate push/deploy
authorization, deploy the current runtime, and rerun read-only replay
observation before waiting for fresh rows.
When the collector is active but no fresh post-collector DataFreshnessGuard rows
exist, the expected status is
`BLOCKED_WAITING_FOR_FRESH_DATAFRESHNESS_ROWS` with blocker
`NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS`. This keeps #7 open until stable
`replayCandidateId` rows, entry/TP/SL, EV/OCO/hard-gate snapshots,
`complete_replayable_candidate_rows > 0`, and
`missing_counterfactual_fields=[]` are present. It is read-only and does not
authorize live relaxation, deploy, production env changes, order/OCO actions,
or Telegram sends.
New DataFreshness L0 audit rows carry a deterministic `replayCandidateId`
(`dfsr1_...`) plus explicit no-order/no-intent/no-OCO markers; this improves
future replay traceability but is still not executable evidence without
entry/TP/SL/EV/OCO snapshots.
After deploying that runtime, verify fresh production rows with:

If the post-activation packet reports
`DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE`, prepare the separate
push/deploy authorization packet before any runtime action:

```powershell
.\scripts\prepare_filter_block_false_kill_issue7_push_deploy_handoff.ps1 -RequireReady
```

It emits `ISSUE7_PUSH_DEPLOY_HANDOFF_PACKET`,
`issue7_push_deploy_handoff_status`, and the exact read-only post-deploy
verification commands. `READY_FOR_PUSH_DEPLOY_AUTHORIZATION_NOT_DEPLOYED`
means local evidence is packaged for operator authorization only; it does not
push, deploy, restart, change production env, close #7, relax
DataFreshnessGuard, enable live/staged-add/TinyLive execution, enable
scheduler mutation, place orders, modify OCO, send Telegram, or mutate
DB/grid/fund/Earn/exchange state.
After a separately authorized push/deploy, run the replayable verification as
one read-only bundle:

```powershell
.\scripts\smoke_filter_block_false_kill_issue7_post_deploy_read_only_bundle_ssh.ps1 -RequireBlocked
```

Use `-PlanOnly` before the deploy window to inspect the command plan; it emits
`issue7_post_deploy_read_only_bundle_status=PLAN_READY_NOT_EXECUTED`. The real
bundle refreshes split acceptance, the issue #7 filter-block source log,
DataFreshness replay-id evidence, replay observation, replay evidence
readiness, the runtime evidence-only env smoke
(`smoke_filter_block_false_kill_issue7_runtime_evidence_only_env_ssh.ps1`,
writing `issue7-runtime-evidence-only-env-current.log`), and the
post-activation gate with `-RuntimeEvidenceLog`, then emits
`issue7_post_deploy_read_only_bundle_status`. It is read-only and does not
authorize deployment, production env changes, issue closure, live relaxation,
scheduler mutation, order/OCO actions, Telegram, or DB/grid/fund/Earn/exchange
mutation.

```powershell
.\scripts\smoke_data_freshness_replay_candidate_id_ssh.ps1
```

Use `-RequireObserved` only after a new DataFreshnessGuard row is expected; a
pending result means no fresh row was available yet, not live approval.
The smoke also compares deployed `app.commit` with the expected local HEAD and
prints `DEPLOYED_RUNTIME_NOT_CURRENT` until the replay-id runtime has actually
been deployed.
It also prints DataFreshness sample recency, including latest row time, latest
row age, 1d/3d/7d/14d/30d row counts, and
`data_freshness_sample_gap_status`, so `PENDING_NO_NEW_DATAFRESHNESS_ROWS` can
be separated from an all-time sample absence or a review-window gap.
For the full post-deploy replay observation chain, run:

```powershell
.\scripts\smoke_data_freshness_replay_observation_bundle_ssh.ps1
```

This wrapper combines origin-delta, replay-id, and counterfactual evidence and
fails closed to deploy-first routing while runtime is stale. Its summary also
promotes the replay-id smoke's latest DataFreshness row time, row age,
1d/3d/7d/14d/30d row counts, and `data_freshness_sample_gap_status`, so
downstream blocker briefs do not depend on truncated child output.
To convert that observation chain into a focused readiness packet, run:

```powershell
.\scripts\prepare_data_freshness_replay_evidence_readiness_ssh.ps1
```

It emits `data_freshness_replay_evidence_readiness_packet`,
`data_freshness_replay_evidence_readiness_status`,
`data_freshness_replay_evidence_blockers`, and
`data_freshness_replay_evidence_required`. Status values such as
`PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS` and
`BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE` are read-only routing only:
they do not authorize deploy, live trading, DataFreshnessGuard relaxation, or
position/OCO changes.
When the bundle reports a recent-window sample gap, run:

```powershell
.\scripts\smoke_data_freshness_sample_gap_rca_ssh.ps1
```

This read-only production DB smoke explains whether the gap is due to no recent
BUY-style trading candidates, dominant non-DataFreshness blockers, candidates
that were not DataFreshness-blocked, or a present current sample. It reports
`ATTENTION_HIT` counts separately because attention rows can be macro or
watch-only warnings rather than entry candidates. It emits
`data_freshness_sample_gap_rca_recommendation`, event-type counts, top
`FILTER_BLOCK` blockers, and DataFreshness recency markers. It does not deploy,
change production env, relax DataFreshnessGuard, place orders, modify OCO, or
mutate DB/grid/fund/Earn/Telegram/exchange state.
If it reports `CANDIDATES_EXIST_BUT_NOT_DF_BLOCKED`, run:

```powershell
.\scripts\smoke_attention_hit_progression_ssh.ps1
```

This read-only production DB smoke follows recent `ATTENTION_HIT` rows to the
next same strategy/interval terminal event (`SIGNAL_BUY`, `FILTER_BLOCK`,
`ENTRY_SKIP`, or `AUTOTRADE_*`) within a bounded follow-up window. It emits
`attention_hit_progression_recommendation`,
`attention_followup_classification`, event-type counts, strategy distribution,
strategy-scoped follow-up counts, macro/watch-only attention counts, and
examples. Use `strategy_scoped_attention_rows` and
`strategy_scoped_no_terminal_followup_rows` to separate real strategy review
work from macro/watch-only background alerts. It does not authorize
entry-filter, DataFreshness, EntryDedup, or live execution changes.
When sample-gap RCA reports no recent BUY-like candidates, split recent
`SIGNAL_EVAL` rows before changing policy:

```powershell
.\scripts\smoke_signal_eval_no_buy_generation_ssh.ps1
```

This read-only production DB smoke emits
`signal_eval_no_buy_generation_recommendation`, `signal_eval_rows`,
`buy_like_signal_eval_rows`, v2 context coverage, hold-reason distribution,
strategy/interval distribution, threshold-gap distribution, context decision
distribution, and examples. Recommendations such as
`NO_BUY_LIKE_SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT` route the next review
toward strategy threshold/no-condition evidence instead of DataFreshnessGuard,
EntryDedup, strategy activation, or live execution changes.
For true BUY-like pre-terminal trading candidates, excluding watch-only
attention rows and terminal `ENTRY_SKIP` rows, run:

```powershell
.\scripts\smoke_buy_like_candidate_progression_ssh.ps1
```

This read-only production DB smoke follows recent BUY-like `SIGNAL_EVAL` /
`SIGNAL_BUY` audit rows to the next same strategy/interval terminal event and emits
`buy_like_candidate_progression_recommendation`,
`buy_like_followup_classification`, terminal event counts, candidate type
distribution, and examples. Use it to locate candidate-to-terminal-event loss
before proposing entry-filter, strategy, or live execution changes.
For a combined operator packet that also carries DataFreshness replay blockers,
run `.\scripts\prepare_profit_candidate_flow_review_packet_ssh.ps1`.
When sample-gap RCA reports no recent BUY-like candidates but ATTENTION_HIT rows
exist, run the read-only no-buy attention-flow packet:

```powershell
.\scripts\prepare_no_buy_attention_flow_review_packet_ssh.ps1 -RequireReviewReady
```

It invokes the DataFreshness blocker brief, ATTENTION_HIT progression,
SIGNAL_EVAL no-buy generation smoke, BUY-like progression smoke, and
no-terminal continuity smoke, then emits
`NO_BUY_ATTENTION_FLOW_REVIEW_PACKET` and
`no_buy_attention_flow_review_status`. A
`READY_FOR_ATTENTION_NO_BUY_FLOW_REVIEW_NOT_LIVE` result means the operator can
review why attention rows have no terminal trading follow-up, why `SIGNAL_EVAL`
rows did not become BUY-like candidates, and why BUY-like candidates were not
generated. The packet includes `SIGNAL_EVAL_NO_BUY_GENERATION_REVIEW` when
recent `SIGNAL_EVAL` rows exist but none are BUY-like, plus
`SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT` when the v2 context shows strategy
threshold misses dominate. It carries `signal_eval_threshold_gap_distribution`,
`signal_eval_near_threshold_gap_count`, and `signal_eval_closest_threshold_gap_*`
so the operator can see which strategy/indicator thresholds are closest before
opening any strategy-threshold review. It also emits
`attention_candidate_interpretation`, `attention_macro_watch_only_rows`,
`attention_strategy_scoped_rows`,
`attention_strategy_scoped_no_terminal_followup_rows`, and
`attention_strategy_scoped_recommendation` so macro/watch-only attention rows
are not mistaken for trading candidates with missing terminal follow-up. It also
emits `no_terminal_continuity_status`,
`no_terminal_continuity_primary_classification`, and
`terminal_after_primary_window_distribution` so primary-window matcher gaps are
not mistaken for a broken trading pipeline. It does not authorize
DataFreshnessGuard or
EntryDedup relaxation, live execution, orders, scheduler enablement, deploy, or
production env changes.
When that packet routes review to strategy threshold gaps, especially strategy
574 near threshold, run the read-only decision packet:

```powershell
.\scripts\prepare_strategy574_near_threshold_decision_packet_ssh.ps1 -RequireReady
```

It invokes or reuses `smoke_signal_eval_no_buy_generation_ssh.ps1` and emits
`STRATEGY574_NEAR_THRESHOLD_DECISION_PACKET`,
`strategy574_near_threshold_decision_packet`, and
`strategy574_near_threshold_decision_status`. A
`READY_FOR_STRATEGY574_NEAR_THRESHOLD_SHADOW_REVIEW_NOT_LIVE` result means the
operator can review a shadow-only observation plan for strategy 574 threshold
misses such as `market_entropy_index` at 69 versus buy threshold 70. It keeps
`strategy_threshold_change_allowed=false`,
`strategy_activation_allowed=false`, `tiny_live_order_allowed=false`,
`live_policy_change_allowed=false`, `scheduler_enablement_allowed=false`,
`deploy_or_env_change_allowed=false`, `order_allowed=false`,
`telegram_send_allowed=false`, `entry_dedup_policy_change_allowed=false`, and
`data_freshness_policy_change_allowed=false`; it does not authorize threshold
changes, strategy activation, TinyLive/live execution, orders, deploy,
production env changes, Telegram sends, EntryDedup/DataFreshness relaxation, or
DB/OCO/grid/fund/Earn/exchange mutation.
Then score those near-threshold rows with read-only forward K-line and TP/SL
proxy evidence:

```powershell
.\scripts\smoke_strategy574_near_threshold_shadow_observation_ssh.ps1
```

This smoke reads only `bt_decision_audit` and `md_kline` through production
MySQL `SELECT` queries and emits `near_threshold_rows`,
`reviewable_forward_rows`, `false_positive_rows`,
`false_positive_rate_pct`, `avg_24h_return_pct`, `avg_mfe_24h_pct`,
`avg_mae_24h_pct`, `tp_hit_rows`, `sl_hit_rows`,
`ambiguous_same_bar_rows`, `avg_net_return_pct`, `oco_preflight_status`, and
`strategy574_near_threshold_shadow_recommendation`. It is shadow-observation
evidence only; `oco_preflight_status` remains required review evidence and the
smoke does not deploy, change production env, change thresholds, activate
strategy 574, execute TinyLive/live orders, send Telegram, relax
EntryDedup/DataFreshness, or mutate DB/OCO/grid/fund/Earn/exchange state.
When the dominant follow-up is `ENTRY_SKIP:EntryDedup` for strategy 508, run:

```powershell
.\scripts\smoke_strategy508_entry_dedup_exposure_ssh.ps1
```

This read-only smoke combines server-local MCP staged-add readiness with direct
production DB `SELECT` evidence for strategy 508 / 1h EntryDedup skips and open
same-strategy exposure. It emits
`strategy508_entry_dedup_exposure_recommendation`, staged-add blockers,
remaining add budget, OCO/exposure counts, and example open positions. It does
not relax EntryDedup or authorize staged-add/live execution.
If that smoke shows recent EntryDedup skips but no auto-traded same-strategy
open position, run the narrower consistency check:

```powershell
.\scripts\smoke_entry_dedup_exposure_consistency_ssh.ps1
```

This read-only production DB smoke compares the EntryDedup open-signal
exposure definition with the staged-add auto-traded-position definition. It
emits `entry_dedup_exposure_consistency_recommendation`,
`open_signal_rows`, `auto_traded_open_rows`, `non_auto_open_rows`,
`non_auto_zero_qty_rows`, and `non_auto_eventrisk_rows`. A
`ENTRY_DEDUP_EXPOSURE_SEMANTICS_MISMATCH_REVIEW` result means review semantics
with replay or shadow evidence; it is not permission to relax EntryDedup,
place/add orders, or mutate production.
Then score the skipped candidates with read-only forward K-line evidence:

```powershell
.\scripts\smoke_entry_dedup_semantics_shadow_review_ssh.ps1
```

This checks 4h/24h forward returns plus 24h MFE/MAE for the skipped rows and
emits `entry_dedup_semantics_shadow_recommendation` and
`entry_dedup_semantics_shadow_review_plan`. A positive result can only support
a shadow experiment review; it is not live EntryDedup relaxation or staged-add
approval.
If forward alpha is positive, run the fee-adjusted TP/SL/OCO feasibility layer:

```powershell
.\scripts\smoke_entry_dedup_semantics_feasibility_review_ssh.ps1
```

This read-only smoke applies explicit TP/SL/fee assumptions to the skipped rows
and reports `entry_dedup_semantics_feasibility_recommendation`,
`tp_hit_rows`, `sl_hit_rows`, `ambiguous_same_bar_rows`,
`avg_net_return_pct`, and `net_win_rate_pct`. Same-bar TP/SL ambiguity is not
treated as pass evidence, and a positive result is still shadow-review-only.

Then check the remaining mutation blockers with read-only MCP/DB evidence:

```powershell
.\scripts\smoke_entry_dedup_semantics_gate_preflight_ssh.ps1 -StrategyId 508 -IntervalCode 1h -Hours 720 -McpDays 14
```

This combines direct MySQL SELECTs with server-local read-only MCP calls for
`getEventRiskControlStatus` and `getExpectedValueGateStats`. It classifies
ExpectedValueGate, EventRiskControl, duplicate protection, daily cap/max-loss,
OCO feasibility, and candidate-level RuntimeDecisionEvidence EV/OCO coverage as
review evidence only; `order_allowed=false` and live policy relaxation remain
false.

If candidate-level runtime snapshots are missing, create a synthetic EV/OCO
preview packet from existing K-lines:

```powershell
.\scripts\smoke_entry_dedup_semantics_synthetic_ev_oco_preview_ssh.ps1 -StrategyId 508 -IntervalCode 1h -Hours 720 -ForwardHours 24
```

This emits candidate entry/TP/SL, fee-adjusted replay outcome,
`expectedRProxy`, OCO plan-shape status, and OCO route-not-proven status. It is
`READ_ONLY_SYNTHETIC_REPLAY_PROXY_NOT_RUNTIME_EV`; it does not write
RuntimeDecisionEvidence, place orders, modify OCO, or relax policy.

To avoid counting repeated audit rows as separate alpha opportunities, run the
exact-opportunity staged-add review:

```powershell
.\scripts\smoke_entry_dedup_exact_opportunity_staged_add_review_ssh.ps1 -StrategyId 508 -IntervalCode 1h -Hours 720 -ForwardHours 24
```

This read-only smoke groups same-bar EntryDedup skips into synthetic
opportunity keys, reports `exact_opportunity_count`,
`exact_duplicate_suppressed_rows`, staged-add budget proxy fields,
`staged_add_budget_proxy_allowed_opportunities`, and
`staged_add_review_candidate_opportunities`. It is not runtime EV/OCO evidence
and keeps `staged_add_execution_allowed=false`, `order_allowed=false`, and
`entry_dedup_policy_change_allowed=false`.

Read-only EntryDedup semantics shadow experiment packet from the recorded
production evidence:

```powershell
.\scripts\prepare_entry_dedup_semantics_shadow_experiment_packet.ps1 -RequireReady
```

This emits `entry_dedup_semantics_shadow_experiment_packet` with
`packetType=ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_REVIEW_PACKET` and
`entry_dedup_semantics_shadow_packet_status=READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE`
when the consistency, forward-return, TP/SL/fee feasibility, and exact
opportunity grouping evidence is documented. The packet carries both raw
`entryDedupSkipRows` and de-duplicated `exactOpportunityCount`; use the latter
for opportunity sizing because repeated same-bar audit rows can otherwise
overstate the alpha opportunity. It is a local packaging check only; it does
not rerun SSH, deploy, enable live trading, relax EntryDedup/DataFreshness/live
policy, place orders, modify OCO, close positions, or mutate
DB/grid/fund/Earn/Telegram/exchange state. Ready output still prints
`order_allowed=false`.

For a fresh production rerun before operator review, use the SSH packet:

```powershell
.\scripts\prepare_entry_dedup_semantics_shadow_experiment_packet_ssh.ps1 -RequireReady
```

This invokes the three read-only production smokes directly and emits the same
`ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_REVIEW_PACKET` with
`freshProductionRerun=true`, child exit codes, parsed exposure/forward-return/
TP-SL evidence, `order_allowed=false`, `live_policy_change_allowed=false`, and
`entry_dedup_policy_change_allowed=false`. It is still review-only and does not
authorize EntryDedup relaxation, live trading, staged-add execution, orders,
OCO modification, deploy, or production env changes.

Read-only EntryDedup operator decision brief from the fresh SSH packet:

```powershell
.\scripts\prepare_entry_dedup_operator_decision_brief_ssh.ps1 -RequireDecisionReady
```

This emits `entry_dedup_operator_decision_brief_packet` with
`packetType=ENTRY_DEDUP_OPERATOR_DECISION_BRIEF` and
`entry_dedup_operator_decision_brief_status=READY_FOR_ENTRY_DEDUP_OPERATOR_DECISION_NOT_LIVE`.
The default review window is 720 hours with a 24-hour forward return check and
50-row cap, matching the source-refresh audit window for the current 30-day
EntryDedup opportunity set.
It turns the fresh EntryDedup shadow evidence into the
`entry-dedup-semantics-shadow-operator-review` decision lane and keeps the
entry-filter/DataFreshness policy lane blocked outside the shadow review. Its
primary recommendation is `PREPARE_SEPARATE_ENTRY_DEDUP_SHADOW_REVIEW`. The
brief carries `entry_dedup_operator_decision_checklist`,
`entry_dedup_policy_change_allowed=false`, `live_policy_change_allowed=false`,
and `order_allowed=false`; it does not deploy, change production env, relax
EntryDedup/DataFreshness/live policy, enable staged-add/live execution, place
orders, or modify OCO.

Read-only profit-improvement review bundle:

```powershell
.\scripts\smoke_profit_improvement_review_bundle_ssh.ps1
```

This wrapper runs the origin-delta classifier, profit-candidate review,
DataFreshness false-kill review, DataFreshness executability review,
DataFreshness counterfactual replay-input review, strategy 485 position-risk
review, strategy 574 signal/governance review, and TinyLive post-trade
evidence. It prints `profit_improvement_review_items` and
`profit_improvement_candidate_scorecard`, `profit_improvement_review_decision`,
`deploy_required_before_profit_improvement_review`,
`profit_improvement_missing_requirement_count`,
`profit_improvement_missing_requirements`, `top_profit_improvement_candidate`,
and `profit_improvement_bundle_recommendation`,
so DataFreshness alpha
pressure, strategy 485 open-position risk, strategy 574 near-BUY context, and
TinyLive sample gaps are reviewed together without authorizing any live change.
The DataFreshness candidate evidence includes
`data_freshness_counterfactual_recommendation`,
`complete_replayable_candidate_rows`, and `missing_counterfactual_fields`, so
false-kill alpha is not confused with executable replay evidence.
The strategy 485 scorecard evidence includes `strategy485_position_review_decision`
so negative-EV position counts, close/modify suggestions, timeout evidence, and
OCO health stay attached to the ranked profit candidate.
The scorecard ranks read-only candidates and required evidence only; it is not
permission to relax policy, close or modify positions, or place orders.
The review decision is the top-level machine-readable routing object with
`canDraftShadowExperimentReview`, `deployRequired`, `allowedReviewTypes`,
`rankedEvidenceRefs`, `strategy485ReviewDecision`, missing-requirement counts,
and no-live authorization text. If the top-ranked candidate is still blocked by
deploy or replay evidence, the decision remains
`BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE` even when a secondary lane, such as
strategy 485 position risk, is reviewable.

Read-only profit experiment gate:

```powershell
.\scripts\prepare_profit_experiment_gate_ssh.ps1
```

This gate runs the profit-improvement review bundle and converts the candidate
scorecard into `deploy_required_before_profit_experiment`,
`shadow_experiment_review_allowed`, `live_policy_change_allowed=false`,
`strategy485_position_review_decision`, `profit_experiment_blocker_items`, and
`profit_experiment_gate_status`. It is a routing check for shadow/small
experiment review only; it does not authorize deploy, live policy changes,
position/OCO changes, or order-capable actions.
`profit_experiment_blocker_items` splits blocked evidence by lane, including
DataFreshness replay/candidate snapshot gaps and strategy 485 risk-reduction
operator approval gaps, so dashboards do not have to infer blockers from the
flat missing-requirements list.

Read-only profit shadow experiment packet preflight:

```powershell
.\scripts\prepare_profit_shadow_experiment_packet_ssh.ps1 -RequireReady
```

This wraps the profit experiment gate into a machine-readable
`profit_shadow_experiment_packet` and emits `profit_shadow_packet_status`.
`READY_FOR_SHADOW_EXPERIMENT_PACKET_NOT_LIVE` means the packet can be attached
to a separate shadow-only experiment review; it keeps
`live_policy_change_allowed=false` and `position_or_oco_mutation_allowed=false`
and does not authorize live trading or DataFreshnessGuard relaxation.

Before drafting any evidence-only production env change, use
`docs/live-dry-run-evidence-plan.md`. That checklist keeps
`TRADING_RUNTIME_EVIDENCE_ENABLED=true` as a separately authorized evidence
candidate only, while order-capable, Telegram, scheduler, OCO, grid, Earn, fund,
external-backfill/import, and guardian live-action flags remain disabled.
Use `docs/live-production-env-review-proposal.md` to review which server
background automation flags must be disabled or separately justified before any
live proposal. That file is a review artifact only; it is not authorization to
edit production env.
Its "Pre-Live Review Decision Checklist" is the operator routing gate: prove
runtime currentness, then prove the full read-only bundle, then run the packet
preflight. Any stale runtime, `NOT_READY`, `NO_EVIDENCE`, or non-empty blocker
output stops live review.

Read-only background automation env smoke before any live scope expansion:

```powershell
.\scripts\smoke_live_background_automation_ssh.ps1
```

This prints server env flags that should be reviewed before live, including
external backfill/import, notification, digest, market WebSocket auto-subscribe,
and retry notification toggles. It also prints
`background_automation_review_plan`, a machine-readable review-routing list for
each true or missing flag with `riskCategory`, `concern`, `requiredReview`,
`requiredEvidence`, `nextAction`, and `notAuthorization`.
`missing_background_automation_flags` must be empty; absent reviewed env keys
are not treated as explicit false evidence. It does not change production
env/DB or perform order/OCO/grid/fund/Earn/Telegram or scheduler actions.
After a separately authorized background-automation env diff, rerun it with
`-RequireClear` so any remaining true or missing reviewed flag exits non-zero.
`backgroundAutomationClear=true` and `background_automation_blockers=[]` are
required before this blocker can clear.
The full bundle also requires `background_automation_review_plan` to be present
and empty when background automation is otherwise clear.

Read-only live readiness bundle that runs the audit, background automation,
runtime evidence, tiny-live loss, signal correctness, and MCP parity smokes:

```powershell
.\scripts\smoke_live_readiness_bundle_ssh.ps1
```

Read-only live review packet preflight:

```powershell
.\scripts\prepare_live_review_packet_ssh.ps1 -RequireReady
```

This wrapper runs the full bundle and refuses to treat the output as packet
ready unless it proves `bundle_blockers=[]`, `live_review_packet_allowed=true`,
`deploy_required_before_live_review=false`, and
`bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`. With
`-RequireReady`, it must exit 0 with
`packet_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED` and
`packet_missing_requirements=[]`. It also carries
`packet_bundle_blocker_summary` from the underlying bundle so each blocker has
machine-readable `requiredEvidence`, `evidenceMarkers`, and `nextAction`; a
missing, invalid, incomplete, or non-empty `bundle_blocker_summary` when
`bundle_blockers=[]` is incomplete evidence. Ready packet output must include
`packet_bundle_blocker_summary=[]`. `NOT_READY` and `NO_EVIDENCE` output is not live approval and does
not authorize production env changes.
When `NO_EVIDENCE` includes `DEPLOYED_RUNTIME_NOT_CURRENT`, run the read-only
origin-delta classifier before choosing the next action. If it prints
`origin_delta_status=RUNTIME_DRIFT`, separately deploy and verify current
`origin/main`. If it prints `origin_delta_status=DOCS_TOOLING_ONLY_DRIFT`,
review and attach the classifier evidence separately. If it prints
`origin_delta_status=NO_LOCAL_EVIDENCE`, refresh local git evidence or rerun the
metadata smoke. In all cases, rerun the full read-only bundle before drafting
any live review packet.

Local production-env review packet preflight:

```powershell
.\scripts\prepare_live_env_review_packet.ps1 -RequireReady
```

This local review packet preflight reads the review proposal docs and verifies that the
runtime-evidence candidate is only `TRADING_RUNTIME_EVIDENCE_ENABLED=true`,
that the background-automation candidate only disables the nine reviewed
background flags, and that no order/OCO/grid/fund/Earn/Telegram/exchange/live
candidate is enabled. `env_review_packet_status=READY_FOR_OPERATOR_ENV_REVIEW_NOT_AUTHORIZED`
means the docs are internally consistent enough to attach to a separate
operator env-change request with fresh read-only SSH smokes; it is not
authorization, does not apply changes, and the operator must not apply changes from this output. Do not apply changes from this output.

Fast read-only deployment metadata check when the only question is whether the
server worktree/deployed runtime still matches current `origin/main`:

```powershell
.\scripts\smoke_live_deployment_metadata_ssh.ps1
```

This emits `refreshType=DEPLOYMENT_METADATA_ONLY`. It is metadata-only, not
live-readiness evidence and not a substitute for the full bundle.
Even when metadata-only output reports `metadata_current=true` or
`deployment_metadata_status=DOCS_TOOLING_ONLY_DRIFT`, it still prints
`live_review_packet_allowed=false` and must only be used to decide whether a
fresh full bundle can be trusted.
If SSH access or the remote read-only command fails, the metadata-only smoke
prints `read_only_metadata_error=SSH_AUTH_FAILED`, `SSH_CONNECT_FAILED`,
`SSH_COMMAND_FAILED`, or `READ_ONLY_SMOKE_FAILED`, plus
`metadata_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE"]`,
`live_review_packet_allowed=false`,
`deploy_required_before_live_review=unknown`, and
`bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`; treat that as an
incomplete metadata refresh, not live-readiness evidence.

Optional read-only local classifier for the same currentness question:

```powershell
.\scripts\smoke_live_origin_delta_local.ps1
```

This runs the metadata-only SSH smoke and then classifies the server worktree
commit to `origin/main` diff from the local git object database as
`DOCS_TOOLING_ONLY_DRIFT`, `RUNTIME_DRIFT`, `CURRENT_ORIGIN_MAIN`, or
`NO_LOCAL_EVIDENCE`. It still prints `live_review_packet_allowed=false`; it is
only a routing aid for whether a runtime deploy is likely needed before the
fresh full bundle.

The bundle keeps evidence windows bounded and passes them through to the child
smokes: runtime evidence defaults to 43,200 minutes, tiny-live RCA defaults to
30 days, signal execution defaults to 5 days, blocked-signal/governance review
defaults to 7 days, and signal accuracy defaults to 14 days.
It prints `deployment_metadata_status`, `origin_metadata_status`,
`bundle_blockers`, `bundle_blocker_summary`, `live_review_packet_allowed`,
`deploy_required_before_live_review`, and `bundle_verdict`. Treat
`DEPLOYED_RUNTIME_NOT_CURRENT` as stale live-review evidence until the server
runtime and worktree are separately refreshed and verified against
`origin/main`. By default the full bundle stops after stale deployment metadata
and prints `bundle_verdict=NO_EVIDENCE`; use `-ContinueWhenRuntimeStale` only
for diagnostic stale-runtime child-smoke output. Add `-RequireReady` only when
the caller wants `NOT_READY` to fail the command.
Do not draft a live review packet unless the latest full bundle prints
`bundle_blockers=[]`, `live_review_packet_allowed=true`,
`deploy_required_before_live_review=false`, and
`bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`; `NOT_READY`,
`NO_EVIDENCE`, `live_review_packet_allowed=false`, and stale runtime metadata
remain blocking evidence.
If the bundle cannot collect complete evidence because of `SSH_AUTH_FAILED`,
`SSH_CONNECT_FAILED`, `SSH_COMMAND_FAILED`, or `READ_ONLY_SMOKE_FAILED`, it emits
`bundle_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE"]`,
`bundle_blocker_summary`,
`live_review_packet_allowed=false`,
`deploy_required_before_live_review=unknown`, and
`bundle_verdict=NO_EVIDENCE`; treat that output as an incomplete evidence
problem, not live-readiness evidence.
If deployment metadata was already collected before a later child smoke fails,
the failure output also preserves `deployment_metadata_status`,
`origin_metadata_status`, and, when stale, adds `DEPLOYED_RUNTIME_NOT_CURRENT`
to `bundle_blockers` with `deploy_required_before_live_review=true`.
Both fail-fast paths still print `bundle_blocker_summary`, so automation can
route the incomplete-evidence or stale-runtime result without treating it as
live-readiness evidence.
Use `docs/live-readiness-blocker-remediation.md` to map each
`bundle_blockers` value to the read-only evidence required before a later live
review packet can be drafted.
`bundle_blocker_summary` is a machine-readable helper for the same mapping. Each
entry includes `category`, `requiredEvidence`, `evidenceMarkers`, and
`nextAction`; those markers explain why the blocker was emitted without
authorizing any production env change. It does not relax `bundle_blockers`.
If the refreshed runtime log smoke fails after deploying the classified log
checker, attach the `ERROR category ...` line and
`ERROR rca=TELEGRAM_EXECUTION_EVENT_NOTIFICATION_PATH` marker before reviewing
`EVENT_SCAN_NOTIFICATION_ENABLED`, `EXECUTION_EVENT_ENABLED`, Telegram send
health, or background automation authorization.
The runtime log smoke treats the narrow `OkxTradingService` startup echo
`[OKX] Auto-trade enabled : true` as configuration evidence only. It still
fails on actual order placement, OKX submit/fill/execute, `createGrid`, OCO,
Earn, or fund operation-like lines unless the diagnostic-only high-risk allow
flag is used outside acceptance.
Grid post-env verification uses an explicit `AcceptAlreadyAppliedEnvDiff`
packet mode so `TRADING_OKX_ENABLED=true` and `TRADING_GRID_ENABLED=true` are
accepted only after an env diff has already been applied and split acceptance
has passed; the normal pre-apply env-diff packet still treats already-true
flags as review blockers.
The env-diff packet also emits `existingActiveGridActivationReview`. When an
ACTIVE grid already exists with `TRADING_GRID_ENABLED=true`, changing
`TRADING_OKX_ENABLED=true` is existing active grid order-path activation: it
can allow market buy/sell on grid price-cross events. Operator authorization
must name that risk explicitly and cite fresh post-open smoke/runtime-log
evidence.
After Grid #10 is opened, use `.\scripts\smoke_grid_post_open_ssh.ps1 -GridId 10`
for read-only first-day observation. It verifies Grid #10 is ACTIVE, level
counts have no failed/partial states, price alignment is in range,
`TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false`,
`GRID_RECOVERY_ENABLED=false`, `OKX_EARN_TOPUP_ENABLED=false`, and the active
runtime log remains clean. It emits `grid_post_open_smoke_packet` and does not
authorize auto-rebalance, recovery, close/pause/resume, order, OCO, Earn, fund,
Telegram, env, deploy, or scheduler changes.
`previewPanicBottomContext(symbol=BTCUSDT)` is a read-only ScoreBuy companion
MCP for 200WMA/Fear&Greed/multi-wave BTC panic-bottom context. It emits
`orderAllowed=false` and `gridMutationAllowed=false`; OCO abnormal evidence or
1h/4h `TRENDING_BEARISH` forces `suggestedAction` to `SCOUT_PRE_POSITION` or
`WATCH`, never confirmed deploy. `previewScoreBuyConviction` includes this
context for operator visibility only and does not change live execution.
For issue #17 panic-bottom missed rebound RCA, refresh the read-only source
logs and assemble the local packet:

```powershell
New-Item -ItemType Directory -Force target/profit-review | Out-Null
.\scripts\smoke_profit_candidate_review_ssh.ps1 -ReviewDays 7 -BlockedDays 3 -MissedHours 24 -TrailingLimit 50 *> target/profit-review/panic-bottom-profit-candidate-review-latest.log
.\scripts\smoke_signal_eval_no_buy_generation_ssh.ps1 -ReviewDays 1 -Limit 20 *> target/profit-review/panic-bottom-signal-eval-no-buy-latest.log
.\scripts\smoke_buy_like_candidate_progression_ssh.ps1 -ReviewDays 1 -FollowupHours 6 -Limit 20 *> target/profit-review/panic-bottom-buy-like-progression-latest.log
.\scripts\smoke_panic_bottom_context_ssh.ps1 *> target/profit-review/panic-bottom-context-latest.log
.\scripts\prepare_panic_bottom_missed_rebound_rca_packet.ps1 -RequireReady
```

The packet emits `PANIC_BOTTOM_MISSED_REBOUND_RCA_PACKET`,
`panic_bottom_missed_rebound_rca_status`, and a machine-readable
blocker-layer classification covering signal/threshold, BUY-like continuity,
EntryDedup/DataFreshness/filter, OCO preflight or trend guard, and
execution/live boundary layers. `READY_FOR_PANIC_BOTTOM_MISSED_REBOUND_RCA_REVIEW_NOT_LIVE`
is evidence readiness only and does not authorize live trading, strategy
threshold changes, EntryDedup/DataFreshness/live policy relaxation, orders,
OCO/grid/fund/Earn/Telegram/exchange mutations, scheduler enablement, deploy,
production env changes, DB mutation, or external backfill/import.
Use `docs/live-background-automation-env-diff-proposal.md` when reviewing the
specific env diff that would clear `BACKGROUND_AUTOMATION_REVIEW`; it is a
proposal only and must not be applied without separate authorization.
Use `docs/live-runtime-evidence-env-proposal.md` when reviewing the separate
evidence-only diff for `TRADING_RUNTIME_EVIDENCE_ENABLED=true`; it is not live
approval and must not be bundled with execution flags.

The script calls server-local `/api/mcp` only. It verifies
`analyzeSpotAntiWickPolicyCoverage` and `getEventRiskControlStatus` boundary
and operator-control markers without changing order/OCO/strategy/grid/fund/Earn
state. Add `-RequireNoReviewGaps` when this smoke is used as issue-acceptance
evidence instead of diagnostic reachability evidence.

Cross-service live ownership smoke is maintained in the AgoraMarketAPI repo.
Run it when validating that representative legacy Trading tools are absent from
AgoraMarketAPI and present in `agora-trading-api`:

```powershell
powershell -ExecutionPolicy Bypass -File C:\Users\Redan\IdeaProjects\AgoraMarketAPI\tools\codex\check-live-mcp-split-ownership.ps1
```
