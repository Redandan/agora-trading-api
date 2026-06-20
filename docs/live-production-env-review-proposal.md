# Live Production Env Review Proposal

This proposal converts the current read-only live-readiness evidence into an
operator review checklist for a future production env change. It is not
authorization to edit `/home/ubuntu/.env.trading.secrets`, deploy, restart the
service, enable live trading, place orders, change OCO, run grid/fund/Earn
actions, send Telegram, run external backfill/import jobs, mutate DB state, or
change schedulers.
This document is a review proposal only, not authorization.

## Required Evidence Before Review

Run these read-only checks and attach the outputs to the operator review:

```powershell
.\scripts\audit_live_readiness_ssh.ps1
.\scripts\smoke_live_background_automation_ssh.ps1
.\scripts\smoke_runtime_evidence_rca_ssh.ps1
.\scripts\smoke_tiny_live_loss_rca_ssh.ps1
.\scripts\smoke_signal_correctness_ssh.ps1
.\scripts\smoke_mcp_parity_ssh.ps1
.\scripts\smoke_live_readiness_bundle_ssh.ps1
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

Latest current read-only bundle snapshot:

```text
snapshotType=CURRENT_READ_ONLY_EVIDENCE
observedAt=2026-06-20T14:43+08:00
serverCommit=0b738cfe27f9dd2ea7d68c848f1501d3461a7b14
deployedCommit=0b738cfe27f9dd2ea7d68c848f1501d3461a7b14
deployment_metadata_status=CURRENT
origin_metadata_status=CURRENT_ORIGIN_MAIN
originMainCommit=0b738cfe27f9dd2ea7d68c848f1501d3461a7b14
health=UP
eventRisk=riskLevel=R0
mcpParity=[mcp-parity-ssh] OK toolCount=305 required=35
runtimeLog=PASS
runtimeLogErrors=0
orderCapableFlags=false
dryRunFlags=true
backgroundHighRiskFlags=["TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED","EVENT_SCAN_NOTIFICATION_ENABLED","EXECUTION_EVENT_ENABLED","TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED","TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED"]
runtimeEvidence=CONFIG_DISABLED shadowIntentCount=0 orderSentEvidence=0
tinyLive=hardStopDetected=true canEnableProduction=false completedTinyLiveSamples=2 falsePositiveCount=2
signalPolicy=governanceMode=TOO_STRICT missedOpportunityOverallStatus=WARN
bundle_blockers=["LIVE_READINESS_NOT_READY","MCP_AUDIT_TOOL_ERROR","EXECUTION_ELIGIBILITY_NOT_READY","BACKGROUND_AUTOMATION_REVIEW","RUNTIME_EVIDENCE_CONFIG_DISABLED","RUNTIME_EVIDENCE_NO_SHADOW_INTENT","TINY_LIVE_LOSS_HARD_STOP","TINY_LIVE_ROLLOUT_NOT_READY","SIGNAL_POLICY_REVIEW_GAPS"]
live_review_packet_allowed=false
deploy_required_before_live_review=false
bundle_verdict=NOT_READY
```

The current snapshot supersedes earlier stale 2026-06-19 and 2026-06-20 morning
metadata snapshots that included `DEPLOYED_RUNTIME_NOT_CURRENT` or
`RUNTIME_HEALTH_OR_LOG_NOT_CLEAN`. Keep those records only as historical RCA.
If `origin/main` advances again, rerun deployment metadata and the full bundle
before using this proposal.

Runtime currentness is currently clean. Use
`.\scripts\smoke_live_deployment_metadata_ssh.ps1` only for a fast metadata
refresh; it is not a substitute for the full bundle:

```text
refreshType=DEPLOYMENT_METADATA_ONLY
worktreeCommit=0b738cfe27f9dd2ea7d68c848f1501d3461a7b14
originMainCommit=0b738cfe27f9dd2ea7d68c848f1501d3461a7b14
deployedCommit=0b738cfe27f9dd2ea7d68c848f1501d3461a7b14
origin_metadata_status=CURRENT_ORIGIN_MAIN
deployment_metadata_status=CURRENT
metadata_blockers=[]
live_review_packet_allowed=false
deploy_required_before_live_review=false
bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY
```

The metadata-only output is still not live-readiness evidence. It only proves
there is no deploy/currentness blocker at the time it was run. The full bundle
remains `NOT_READY` because runtime evidence, tiny-live hard-stop, background
automation, execution eligibility, MCP audit details, and signal policy blockers
remain.

The stale 2026-06-20T10:16+08:00 runtime-log failure against
`app-20260618T070102Z-port8084.log` remains useful RCA for Telegram/ExecutionEvent
notification paths (`TelegramServiceImpl` and `ExecutionEventScheduler`), but it
is no longer the current blocker after the `0b738cf` deploy. If a future strict read-only runtime-log smoke fails, attach the
`ERROR category ...` line and `ERROR rca=TELEGRAM_EXECUTION_EVENT_NOTIFICATION_PATH`
line, then explicitly reconcile `EVENT_SCAN_NOTIFICATION_ENABLED`,
`EXECUTION_EVENT_ENABLED`, Telegram send health, and background automation
authorization before any live packet.
If the refreshed bundle emits `bundle_verdict=NO_EVIDENCE` or
`LIVE_READINESS_EVIDENCE_UNAVAILABLE`, stop the review and fix SSH access,
key selection, or the failing read-only smoke before using the output.

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
.\scripts\smoke_live_background_automation_ssh.ps1
.\scripts\smoke_runtime_evidence_rca_ssh.ps1
.\scripts\smoke_tiny_live_loss_rca_ssh.ps1
.\scripts\smoke_signal_correctness_ssh.ps1
.\scripts\smoke_mcp_parity_ssh.ps1
.\scripts\smoke_live_readiness_bundle_ssh.ps1
```

Expected evidence-only result:

- `order_capable_flags` remain false.
- `order_capable_flags_true=[]`.
- `high_risk_background_automation_true=[]` or each item has a separate
  explicit authorization.
- `smoke_runtime_evidence_rca_ssh.ps1` no longer reports `CONFIG_DISABLED`.
- `shadowIntentCount` becomes greater than 0 before live is discussed.
- `orderSentEvidence=0`.
- `smoke_live_readiness_bundle_ssh.ps1` no longer reports
  `LIVE_READINESS_EVIDENCE_UNAVAILABLE`.
- `smoke_live_readiness_bundle_ssh.ps1` no longer reports
  `bundle_verdict=NO_EVIDENCE`.
- `smoke_live_readiness_bundle_ssh.ps1` no longer reports
  `DEPLOYED_RUNTIME_NOT_CURRENT`.
- `smoke_live_readiness_bundle_ssh.ps1` no longer reports
  `RUNTIME_HEALTH_OR_LOG_NOT_CLEAN`.
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
tiny-live hard-stop status with `missing_tiny_live_fields=[]`, runtime evidence status, market-signal state, loss
budget, rollback steps, and explicit operator authorization.
