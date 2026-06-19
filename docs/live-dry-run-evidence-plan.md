# Live Dry-Run Evidence Plan

This plan is a read-only/operator-review checklist for moving from the current
`NOT_READY` live-readiness state toward enough evidence to draft a later live
enablement proposal. It is not authorization to change production env, enable
live trading, place orders, enable schedulers, send Telegram, mutate DB state,
or run external backfill/import jobs. This is not live approval.

Use it only after the current read-only checks have been run:

```powershell
.\scripts\audit_live_readiness_ssh.ps1
.\scripts\smoke_runtime_evidence_rca_ssh.ps1
.\scripts\smoke_tiny_live_loss_rca_ssh.ps1
.\scripts\smoke_signal_correctness_ssh.ps1
```

## Current Blockers

Treat live as blocked while any of these are true:

- `audit_live_readiness_ssh.ps1` ends with `verdict=NOT_READY`.
- `smoke_tiny_live_loss_rca_ssh.ps1` reports
  `AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES`,
  `hardStopDetected=true`, or rollout gates that cannot enable production.
- `smoke_runtime_evidence_rca_ssh.ps1` reports `diagnosis=CONFIG_DISABLED`,
  `NO_CANONICAL_ROWS`, `CANONICAL_ROWS_NO_SHADOW_INTENT`, or
  `runtimeEvidenceStatus=NOT_READY_*`.
- `shadowIntentCount` is 0 for the reviewed window.
- `orderSentEvidence` is not 0 during the evidence-only phase.
- Signal correctness is unresolved: `smoke_signal_correctness_ssh.ps1` reports
  `REVIEW_POLICY_GAPS` or unresolved governance drift / missed-opportunity
  evidence that has not been documented for operator review.
- The live-readiness bundle reports `RUNTIME_HEALTH_OR_LOG_NOT_CLEAN`, including
  runtime ERROR lines from Telegram-send or scheduler paths.
- Runtime logs show unexpected order, OCO, grid, Earn, fund, Telegram, scheduler,
  or external provider activity.

## Separately Authorized Evidence Candidate

The only candidate env change for this phase is:

```dotenv
TRADING_RUNTIME_EVIDENCE_ENABLED=true
```

This must be handled as a separate, explicit, production-env authorization. It
is intended to collect canonical runtime/shadow decision evidence only. It must
not place orders, change OCO, enable scheduler execution, send Telegram, enable
exchange writes, run DB migrations, or mutate marketplace/shared tables. Any
implementation path for this phase must not place orders.

Keep external import/backfill toggles off in this phase, including:

```dotenv
TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED=false
TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=false
```

Do not treat evidence collection as live approval. After any authorized
evidence-only env change, live remains blocked until the full audit and smoke
suite prove the gates below.

## Must Remain Disabled

These flags must remain disabled during the evidence-only phase:

```dotenv
TRADING_OKX_ENABLED=false
TRADING_OCO_POLLER_ENABLED=false
TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false
TRADING_TINY_LIVE_AUTO_EXECUTION_DRY_RUN=true
TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false
TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_DRY_RUN=true
TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false
TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_DRY_RUN=true
TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false
TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_DRY_RUN=true
TRAILING_STOP_ENABLED=false
TRAILING_STOP_DRY_RUN=true
POSITION_EXIT_MANAGER_ENABLED=false
POSITION_EXIT_MANAGER_DRY_RUN=true
TRADING_GRID_ENABLED=false
TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false
TRADING_FUNDING_ARB_ENABLED=false
OKX_EARN_TOPUP_ENABLED=false
MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false
TRADING_EXPLORATION_LOOP_PRODUCTION_ENABLED=false
TRADING_EXPLORATION_ROLLOUT_AUTO_ENABLED=false
TRADING_EXPLORATION_ROLLOUT_ALLOW_PRODUCTION_PROMOTION=false
TRADING_EXPLORATION_ROLLOUT_ALLOW_CAP_INCREASE=false
TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED=false
TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=false
MARKET_WS_AUTO_SUBSCRIBE_ENABLED=false
TRADING_DAILY_TG_REPORT_ENABLED=false
TRADING_AUTONOMOUS_DIGEST_ENABLED=false
TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED=false
TRADING_SCORE_BUY_FORMING_DAY_NOTIFICATION_TELEGRAM_ENABLED=false
TRADING_SCORE_BUY_POST_SCOUT_ADD_NOTIFICATION_TELEGRAM_ENABLED=false
EVENT_SCAN_NOTIFICATION_ENABLED=false
EXECUTION_EVENT_ENABLED=false
TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED=false
```

## Verification After Any Authorized Evidence-Only Change

Run these read-only checks from the local repo:

```powershell
.\scripts\verify_split_acceptance_ssh.ps1
.\scripts\audit_live_readiness_ssh.ps1
.\scripts\smoke_runtime_evidence_rca_ssh.ps1
.\scripts\smoke_tiny_live_loss_rca_ssh.ps1
.\scripts\smoke_signal_correctness_ssh.ps1
.\scripts\smoke_mcp_parity_ssh.ps1
```

Expected evidence-only outcome:

- `smoke_runtime_evidence_rca_ssh.ps1` no longer reports `CONFIG_DISABLED`.
- Prefer `diagnosis=CANONICAL_SHADOW_READY`; otherwise document why the
  remaining diagnosis is still acceptable for review.
- `shadowIntentCount` is greater than 0 for the reviewed window.
- `orderSentEvidence=0`.
- `/api/mcp` server-local smoke remains protected by `TRADING_MCP_KEY`.
- Public MCP remains unavailable as a service surface unless separately
  authorized by product/security.
- No logs indicate orders, OCO modifications, grid/fund/Earn operations,
  Telegram sends, scheduler execution, external backfill/import, or DB mutation.

## Failure Criteria

Immediately stop the evidence-only change review and restore the prior flag
state if any of these are observed:

- `orderSentEvidence` is greater than 0.
- Runtime logs include order placement, OCO modification, live exchange writes,
  grid/fund/Earn operations, Telegram sends, or unexpected scheduler execution.
- Public MCP becomes reachable as an externally offered service.
- External backfill/import jobs run.
- DB migration, baseline regeneration, extra-table cleanup, or table drops are
  attempted.
- `audit_live_readiness_ssh.ps1` reports a new blocker outside the planned
  runtime evidence gap.

## Live Approval Boundary

This checklist can only move the system toward better evidence. It must not be
used as approval to open live trading. A later live proposal must still include
the exact env diff, blast-radius classification, current smoke outputs,
full-bundle `bundle_blockers=[]`,
`live_review_packet_allowed=true`,
`deploy_required_before_live_review=false`,
`bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`, tiny-live live loss hard-stop status,
runtime evidence status, rollback steps, and a separate operator authorization.
