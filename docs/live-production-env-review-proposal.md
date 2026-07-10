# Live Production Env Review Proposal

This proposal converts the latest attached read-only live-readiness evidence into an
operator review checklist for a future production env change. It is not
authorization to edit `/home/ubuntu/.env.trading.secrets`, deploy, restart the
service, enable live trading, place orders, change OCO, run grid/fund/Earn
actions, send Telegram, run external backfill/import jobs, mutate DB state, or
change schedulers.
This document is a review proposal only, not authorization.

## Required Evidence Before Review

First run the local review packet preflight:

```powershell
.\scripts\prepare_live_env_review_packet.ps1 -RequireReady
```

`env_review_packet_status=READY_FOR_OPERATOR_ENV_REVIEW_NOT_AUTHORIZED` only
means the proposal docs are internally consistent enough to attach to a
separate operator env-change request with fresh read-only SSH smokes. It is not
authorization, it does not change production env, and operators must not apply changes from this output. Do not apply changes from this output.

Run these read-only checks and attach the outputs to the operator review:

```powershell
.\scripts\audit_live_readiness_ssh.ps1
.\scripts\smoke_live_background_automation_ssh.ps1
.\scripts\smoke_runtime_evidence_rca_ssh.ps1
.\scripts\smoke_tiny_live_loss_rca_ssh.ps1
.\scripts\smoke_signal_correctness_ssh.ps1
.\scripts\smoke_mcp_parity_ssh.ps1
.\scripts\smoke_live_readiness_bundle_ssh.ps1
.\scripts\prepare_live_review_packet_ssh.ps1 -RequireReady
```

The current server evidence keeps live blocked while these remain true:

- `verdict=NOT_READY`
- `diagnosis=CONFIG_DISABLED`
- `shadowIntentCount=0`
- `AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES`
- non-empty `missing_tiny_live_fields`,
  `missing_tiny_live_hard_stop_fields`, or
  `missing_tiny_live_rollout_fields`
- `verdict=NOT_READY_BACKGROUND_AUTOMATION_REVIEW`
- `HIGH_RISK_BACKGROUND_AUTOMATION_TRUE`
- `REVIEW_POLICY_GAPS` or unresolved signal correctness / governance drift
  findings from `smoke_signal_correctness_ssh.ps1`
- missing MCP parity `required_tools=[...]`, missing
  `missing_required_tools=[]`, non-empty `missing_required_tools`, or missing
  `[mcp-parity-ssh] OK`

Latest attached read-only bundle snapshot.
This block is evidence captured at the observation time, not a currentness
claim after later docs, scripts, or runtime commits. Before any operator review,
rerun `.\scripts\smoke_live_deployment_metadata_ssh.ps1` and the full
`.\scripts\smoke_live_readiness_bundle_ssh.ps1`, then attach the fresh output.

```text
snapshotType=ATTACHED_READ_ONLY_EVIDENCE
observedAt=2026-07-03T17:35+08:00
serverCommit=a8253e2b058e1a696b65ba9769b00458ab47aedc
deployedCommit=a8253e2b058e1a696b65ba9769b00458ab47aedc
deployment_metadata_status=CURRENT
origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN
origin_delta_status=DOCS_TOOLING_ONLY_DRIFT
originMainCommit=8aff0bce8fcca9c46c025869ea970aa919c347ec
health=UP
eventRisk=riskLevel=R0
mcpParityRequiredTools=required_tools=[...]
mcpParityMissingTools=missing_required_tools=[]
mcpParityOk=[mcp-parity-ssh] OK toolCount=312 required=35
runtimeLog=PASS
runtimeLogErrors=0
runtimeLogWarnBaselineTotal=17
missing_readiness_detail_fields=[]
orderCapableFlags=["TRADING_OKX_ENABLED","TRAILING_STOP_ENABLED","TRADING_GRID_ENABLED"]
dryRunFlags=true
backgroundHighRiskFlags=[]
backgroundAutomationClear=false
backgroundAutomationBlockers=["BACKGROUND_AUTOMATION_TRUE"]
runtimeEvidence=NO_CANONICAL_ROWS shadowIntentCount=2 orderSentEvidence=0
tinyLive=hardStopDetected=false canEnableProduction=false completedTinyLiveSamples=1 falsePositiveCount=1
signalPolicy=governanceMode=INSUFFICIENT_DATA missedOpportunityOverallStatus=WARN
localTradingView=currentCandidateStatus=NO_CURRENT_BUY_CANDIDATE_RECENT_INTENTS dryRunReceiptArmed=false liveMicroArmed=true ocoLifecycleTracked=false executionMode=LIVE_MICRO
bundle_blocker_summary=present
bundle_blockers=["LIVE_READINESS_NOT_READY","ORDER_CAPABLE_FLAGS_REVIEW","EXECUTION_ELIGIBILITY_NOT_READY","BACKGROUND_AUTOMATION_REVIEW","RUNTIME_EVIDENCE_NO_CANONICAL_ROWS","TINY_LIVE_ROLLOUT_NOT_READY","SIGNAL_POLICY_REVIEW_GAPS","LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE","LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED"]
live_review_packet_allowed=false
deploy_required_before_live_review=false
bundle_verdict=NOT_READY
```

This attached snapshot superseded earlier stale 2026-06-19 and 2026-06-20
morning metadata snapshots that included `DEPLOYED_RUNTIME_NOT_CURRENT` or
`RUNTIME_HEALTH_OR_LOG_NOT_CLEAN` at the time it was captured. Keep those
records only as historical RCA. If `origin/main`, server worktree, or deployed
runtime changes again, rerun deployment metadata and the full bundle before
using this proposal.
Do not chase docs-only deploy commits by rewriting this attached snapshot after
every documentation refresh. A committed SHA here is traceability only; the
authoritative currentness evidence is the freshly rerun metadata and full
bundle output attached to the operator review.

The attached runtime currentness was clean at the observation time. Use
`.\scripts\smoke_live_deployment_metadata_ssh.ps1` for a fast metadata refresh,
but it is not a substitute for the full bundle:

```text
refreshType=DEPLOYMENT_METADATA_ONLY
worktreeCommit=ef6253a4ecff7c27a2e709f226e166389700a82d
originMainCommit=ef6253a4ecff7c27a2e709f226e166389700a82d
deployedCommit=ef6253a4ecff7c27a2e709f226e166389700a82d
origin_metadata_status=CURRENT_ORIGIN_MAIN
deployment_metadata_status=CURRENT
metadata_blockers=[]
live_review_packet_allowed=false
deploy_required_before_live_review=false
bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY
```

The metadata-only output is still not live-readiness evidence. It only proves
whether there is a deploy/currentness blocker at the time it is run. The full bundle
remains `NOT_READY` because runtime evidence, tiny-live hard-stop, background
automation, execution eligibility, and signal policy blockers remain. The
previous MCP audit detail gap was cleared in the attached deployed evidence by
`missing_readiness_detail_fields=[]`.

The stale 2026-06-20T10:16+08:00 runtime-log failure against
`app-20260618T070102Z-port8084.log` remains useful RCA for Telegram/ExecutionEvent
notification paths (`TelegramServiceImpl` and `ExecutionEventScheduler`), but it
was no longer the current blocker after the `ef6253a` deploy. If a future strict read-only runtime-log smoke fails, attach the
`ERROR category ...` line and `ERROR rca=TELEGRAM_EXECUTION_EVENT_NOTIFICATION_PATH`
line, then explicitly reconcile `EVENT_SCAN_NOTIFICATION_ENABLED`,
`EXECUTION_EVENT_ENABLED`, Telegram send health, and background automation
authorization before any live packet.
If the refreshed bundle emits `bundle_verdict=NO_EVIDENCE` or
`LIVE_READINESS_EVIDENCE_UNAVAILABLE`, stop the review and fix SSH access,
key selection, or the failing read-only smoke before using the output.

## Pre-Live Review Decision Checklist

Use this checklist before drafting or attaching any live-review packet. It is
read-only routing only; it is not authorization to deploy, edit production env,
restart, enable live flags, place orders, change OCO/grid/fund/Earn state, send
Telegram, run external backfills/imports, mutate DB state, or enable schedulers.

1. Prove runtime currentness first.

   ```powershell
   .\scripts\smoke_live_deployment_metadata_ssh.ps1
   .\scripts\smoke_live_origin_delta_local.ps1
   ```

   Required routing markers: `deployment_metadata_status=CURRENT`,
   `origin_metadata_status=CURRENT_ORIGIN_MAIN`, `metadata_blockers=[]`, and
   `origin_delta_status=CURRENT_ORIGIN_MAIN` before a full bundle can be used as
   current evidence. `origin_delta_status=DOCS_TOOLING_ONLY_DRIFT` may explain
   why the server is behind a docs/tooling commit, but it is still not
   live-readiness evidence. `origin_delta_status=RUNTIME_DRIFT`,
   `DEPLOYED_RUNTIME_NOT_CURRENT`, `NO_LOCAL_EVIDENCE`, or
   `LIVE_READINESS_EVIDENCE_UNAVAILABLE` means stop and route to deploy,
   local-git refresh, SSH repair, or smoke repair before any live review.

2. Prove every read-only blocker gate.

   ```powershell
   .\scripts\smoke_live_readiness_bundle_ssh.ps1
   ```

   The bundle must print `bundle_blockers=[]`,
   `bundle_blocker_summary=[]`, `live_review_packet_allowed=true`,
   `deploy_required_before_live_review=false`, and
   `bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`. Any
   `NOT_READY`, `NO_EVIDENCE`, non-empty `bundle_blockers`, non-empty
   `bundle_blocker_summary`, missing `missing_required_tools=[]`, missing
   `missing_readiness_detail_fields=[]`, missing `order_capable_flags_true=[]`,
   non-zero `orderSentEvidence`, missing `shadowIntentCount`, or non-empty
   background/tiny-live/signal review plan keeps the packet blocked.

3. Prove packet readiness last.

   ```powershell
   .\scripts\prepare_live_review_packet_ssh.ps1 -RequireReady
   ```

   Required packet markers: `packet_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`,
   `packet_missing_requirements=[]`, `packet_bundle_blocker_summary=[]`,
   `live_review_packet_allowed=true`, `deploy_required_before_live_review=false`,
   and `bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`. This result
   is still only permission to attach evidence to a separate operator review;
   it is not live approval.

## Evidence-Only Candidate

The only candidate that may be proposed before live execution is runtime
evidence collection:

```dotenv
TRADING_RUNTIME_EVIDENCE_ENABLED=true
```

This candidate still requires a separate production-env authorization. It must
not be bundled with exchange, order, Telegram, scheduler, OCO, grid, fund, Earn,
external backfill/import, or guardian live-action enablement.

## Disable Or Justify Before Live

The read-only background automation smoke currently treats these as
pre-live review items. A future env review must either set them to `false` or
document a separate, explicit authorization and blast-radius reason.

High-risk items should normally be disabled before any live trading review:

```dotenv
TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=false
EVENT_SCAN_NOTIFICATION_ENABLED=false
EXECUTION_EVENT_ENABLED=false
TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED=false
TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED=false
```

Additional background automation items should be reviewed before live:

```dotenv
TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED=false
MARKET_WS_AUTO_SUBSCRIBE_ENABLED=false
TRADING_DAILY_TG_REPORT_ENABLED=false
TRADING_AUTONOMOUS_DIGEST_ENABLED=false
```

Do not apply these changes from this document. This document only defines the
review scope that an operator can separately authorize later.

## Must Stay Disabled Until Live Approval

These must remain disabled unless a later live proposal explicitly authorizes a
bounded scope:

```dotenv
TRADING_OKX_ENABLED=false
TRADING_OCO_POLLER_ENABLED=false
TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false
TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false
TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false
TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false
TRAILING_STOP_ENABLED=false
POSITION_EXIT_MANAGER_ENABLED=false
TRADING_GRID_ENABLED=false
TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false
TRADING_FUNDING_ARB_ENABLED=false
OKX_EARN_TOPUP_ENABLED=false
MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false
TRADING_EXPLORATION_LOOP_PRODUCTION_ENABLED=false
TRADING_EXPLORATION_ROLLOUT_AUTO_ENABLED=false
TRADING_EXPLORATION_ROLLOUT_ALLOW_PRODUCTION_PROMOTION=false
TRADING_EXPLORATION_ROLLOUT_ALLOW_CAP_INCREASE=false
```

## Post-Authorization Verification

After any separately authorized evidence-only env change, verify before any
live proposal:

```powershell
.\scripts\verify_split_acceptance_ssh.ps1
.\scripts\audit_live_readiness_ssh.ps1
.\scripts\smoke_live_background_automation_ssh.ps1 -RequireClear
.\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady
.\scripts\smoke_tiny_live_loss_rca_ssh.ps1 -RequireClear
.\scripts\smoke_signal_correctness_ssh.ps1 -RequireClear
.\scripts\smoke_mcp_parity_ssh.ps1
.\scripts\smoke_live_readiness_bundle_ssh.ps1
.\scripts\prepare_live_review_packet_ssh.ps1 -RequireReady
```

Expected evidence-only result:

- `order_capable_flags` remain false.
- `order_capable_flags_true=[]`.
- `smoke_live_background_automation_ssh.ps1 -RequireClear` exits 0.
- `background_automation_true=[]`.
- `high_risk_background_automation_true=[]`.
- `missing_background_automation_flags=[]`.
- `background_automation_blockers=[]`.
- `backgroundAutomationClear=true`.
- `smoke_runtime_evidence_rca_ssh.ps1 -RequireReady` exits 0.
- `diagnosis=CANONICAL_SHADOW_READY`.
- `targetStrategyShadowLikeRows > 0` before live is discussed.
- `orderSentEvidence=0`.
- `smoke_tiny_live_loss_rca_ssh.ps1 -RequireClear` exits 0.
- `hardStopDetected=false`.
- `canEnableProduction=true`.
- `missing_tiny_live_fields=[]`.
- `smoke_signal_correctness_ssh.ps1 -RequireClear` exits 0.
- `signalPolicyClear=true`.
- `missing_signal_policy_fields=[]`.
- 7d governance drift is not `TOO_STRICT`, `TOO_LOOSE`, or
  `INSUFFICIENT_DATA`.
- Missed-opportunity `overallStatus=PASS`.
- `signal_policy_review_plan` is present and any remaining gate is handled as
  review evidence only, with `notAuthorization` confirming it is not live
  approval or permission to relax policy.
- `smoke_mcp_parity_ssh.ps1` exits 0.
- MCP parity output includes `required_tools=[...]`.
- MCP parity output includes `missing_required_tools=[]`.
- MCP parity output includes `[mcp-parity-ssh] OK`.
- Any hard-gate smoke exiting non-zero means the review remains blocked even
  if the full bundle can still print diagnostic child-smoke details.
- `smoke_live_readiness_bundle_ssh.ps1` no longer reports
  `LIVE_READINESS_EVIDENCE_UNAVAILABLE`.
- `smoke_live_readiness_bundle_ssh.ps1` no longer reports
  `bundle_verdict=NO_EVIDENCE`.
- `smoke_live_readiness_bundle_ssh.ps1` no longer reports
  `DEPLOYED_RUNTIME_NOT_CURRENT`.
- `smoke_live_readiness_bundle_ssh.ps1` no longer reports
  `RUNTIME_HEALTH_OR_LOG_NOT_CLEAN`.
- `prepare_live_review_packet_ssh.ps1 -RequireReady` exits 0 with
  `packet_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED` and
  `packet_missing_requirements=[]`, plus `packet_bundle_blocker_summary=[]`.
- Runtime-log smoke is clean after background automation review, or any
  remaining Telegram/ExecutionEvent notification error has separate written
  authorization and rollback evidence.
- Runtime logs remain free of order placement, OCO modification, live exchange
  writes, grid/fund/Earn operations, Telegram sends, unexpected scheduler
  execution, external backfill/import, and DB mutation.

## Rollback Criteria

If any of these appear after an authorized evidence-only env change, restore the
previous env state before continuing:

- `orderSentEvidence` is greater than 0.
- Any order/OCO/grid/fund/Earn/Telegram/live exchange write appears in logs.
- External backfill/import jobs run unexpectedly.
- Public MCP becomes externally offered as a service surface.
- DB migration, Flyway baseline regeneration, extra-table cleanup, or table
  drops are attempted.
- `audit_live_readiness_ssh.ps1` reports new blockers outside the planned
  evidence collection scope.

## Live Approval Boundary

Clearing this proposal is still not live approval. A live proposal must be a
separate change with exact env diff, current smoke outputs, full-bundle
`bundle_blockers=[]`, `live_review_packet_allowed=true`,
`deploy_required_before_live_review=false`,
`bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`,
`packet_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`,
`packet_missing_requirements=[]`,
`packet_bundle_blocker_summary=[]`,
tiny-live hard-stop status with `missing_tiny_live_fields=[]`, runtime evidence status, market-signal state, loss
budget, rollback steps, and explicit operator authorization.
