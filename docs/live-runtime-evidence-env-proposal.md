# Live Runtime Evidence Env Proposal

This is a review-only proposal for clearing
`RUNTIME_EVIDENCE_CONFIG_DISABLED` and beginning work on
`RUNTIME_EVIDENCE_NO_SHADOW_INTENT`. It is not authorization to edit production
env, deploy, restart the service, enable live trading, place orders, change
OCO, run grid/fund/Earn actions, send Telegram, run external backfill/import
jobs, mutate DB state, or change schedulers.

## Current Evidence

The recorded read-only runtime evidence RCA reported:

```text
observedAt=2026-06-18T22:50+08:00
serverCommit=224f550478b20a329775f503b3eaa70ba6a2f6a8
deployment_metadata_status=CURRENT
origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN
originMainCommit=b377be8e53d7908cfe57de2919fbd5136d6bb330
diagnosis=CONFIG_DISABLED
env.TRADING_RUNTIME_EVIDENCE_ENABLED=EMPTY
runtimeEvidenceStatus=NOT_READY_ENABLED_FALSE
runtimeEvidenceRows=200
shadowIntentCount=0
shadowExecutionIntents=0
orderSentEvidence=0
freshnessTerminalBlocks=51
noCurrentBuyCandidateReason=LATEST_SIGNAL_HOLD
currentSignalDecision=HOLD
currentSignalAgeMinutes=50
```

This RCA output is historical because the server worktree was not at the
observed `origin/main` commit. The latest recorded live-readiness bundle on
2026-06-19T10:59+08:00 still reinforced the same runtime-evidence blockers
against server commit `224f550478b20a329775f503b3eaa70ba6a2f6a8` while
`origin/main` was `30490f13ab316a533bb0b6762d04c55f774e6f31`:

```text
diagnosis=CONFIG_DISABLED
env.TRADING_RUNTIME_EVIDENCE_ENABLED=EMPTY
runtimeEvidenceStatus=NOT_READY_ENABLED_FALSE
runtimeEvidenceRows=200
shadowIntentCount=0
shadowExecutionIntents=0
orderSentEvidence=0
```

Treat both records as stale live-review evidence until a separately authorized
deploy refreshes the server to `origin/main` and the read-only runtime evidence
RCA plus full live-readiness bundle are rerun.

The live-readiness bundle currently includes:

```text
RUNTIME_EVIDENCE_CONFIG_DISABLED
RUNTIME_EVIDENCE_NO_SHADOW_INTENT
```

The bundle also treats `RUNTIME_EVIDENCE_NO_CANONICAL_ROWS`,
`RUNTIME_EVIDENCE_REVIEW_REQUIRED`, and `RUNTIME_EVIDENCE_ORDER_SENT` as
runtime-evidence blockers if the RCA smoke reports no canonical rows, an
unclassified runtime-evidence status, or any order-sent evidence in the bounded
evidence-only window.
`diagnosis=CANONICAL_ROWS_NO_SHADOW_INTENT` is a known diagnosis and maps to
`RUNTIME_EVIDENCE_NO_SHADOW_INTENT`, not to
`RUNTIME_EVIDENCE_REVIEW_REQUIRED`.

## Proposed Evidence-Only Diff

If separately authorized, the only production env diff for this phase is:

```dotenv
TRADING_RUNTIME_EVIDENCE_ENABLED=true
```

This proposal must not be bundled with:

- exchange enablement or credential changes
- order-capable execution flags
- OCO/grid/fund/Earn flags
- Telegram-send paths
- scheduler enablement
- guardian live actions
- external backfill/import flags
- DB migration, Flyway baseline regeneration, extra-table cleanup, or table
  drops

## Must Stay Disabled

These must remain disabled during runtime evidence collection:

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
TRADING_FUNDING_ARB_ENABLED=false
OKX_EARN_TOPUP_ENABLED=false
MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false
TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED=false
TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=false
MARKET_WS_AUTO_SUBSCRIBE_ENABLED=false
EVENT_SCAN_NOTIFICATION_ENABLED=false
EXECUTION_EVENT_ENABLED=false
TRADING_DAILY_TG_REPORT_ENABLED=false
TRADING_AUTONOMOUS_DIGEST_ENABLED=false
TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED=false
TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED=false
```

## Required Verification After Separate Authorization

After any authorized env change and service restart, run:

```powershell
.\scripts\verify_split_acceptance_ssh.ps1
.\scripts\smoke_runtime_evidence_rca_ssh.ps1
.\scripts\audit_live_readiness_ssh.ps1
.\scripts\smoke_live_readiness_bundle_ssh.ps1
```

Expected:

- `diagnosis` is no longer `CONFIG_DISABLED`
- `env.TRADING_RUNTIME_EVIDENCE_ENABLED=SET` or an equivalent non-empty masked
  state is printed
- `orderSentEvidence=0`
- `shadowIntentCount` becomes greater than 0 before live is discussed
- Missing or `N/A` shadow-intent evidence stays blocked
- Missing or unrecognized runtime-evidence diagnosis stays blocked
- `order_capable_flags` remain false
- `bundle_blockers` no longer includes `RUNTIME_EVIDENCE_CONFIG_DISABLED`
- `RUNTIME_EVIDENCE_NO_SHADOW_INTENT` remains blocked until `shadowIntentCount`
  is greater than 0
- runtime logs show no order placement, OCO modification, grid/fund/Earn
  operation, Telegram send, scheduler surprise, exchange write, external
  backfill/import, or DB mutation

## Rollback Criteria

Restore the prior env state and investigate if any of these occur:

- `orderSentEvidence` is greater than 0
- any order/OCO/grid/fund/Earn/Telegram/exchange/DB mutation appears
- app health or split acceptance fails
- runtime logs show unexpected errors outside the known baseline
- public MCP becomes externally offered as a service surface
- `smoke_live_readiness_bundle_ssh.ps1` reports a new blocker outside runtime
  evidence collection

## Live Boundary

Clearing `RUNTIME_EVIDENCE_CONFIG_DISABLED` is not live approval. The system
remains blocked while `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`, tiny-live hard stop,
market-condition, signal policy, or background automation blockers remain. A
later live proposal still needs exact env diff, current smoke outputs,
full-bundle `bundle_blockers=[]`,
`bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`, runtime evidence
rows, shadow-intent counts, tiny-live hard-stop state, signal governance
evidence, rollback steps, and separate operator authorization.
