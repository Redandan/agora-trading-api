# Live Runtime Evidence Env Proposal

This is a review-only proposal for clearing
`RUNTIME_EVIDENCE_CONFIG_DISABLED` and beginning work on
`RUNTIME_EVIDENCE_NO_SHADOW_INTENT`. It is not authorization to edit production
env, deploy, restart the service, enable live trading, place orders, change
OCO, run grid/fund/Earn actions, send Telegram, run external backfill/import
jobs, mutate DB state, or change schedulers.

## Current Evidence

The read-only runtime evidence RCA currently reports:

```text
diagnosis=CONFIG_DISABLED
env.TRADING_RUNTIME_EVIDENCE_ENABLED=EMPTY
runtimeEvidenceStatus=NOT_READY_ENABLED_FALSE
shadowIntentCount=0
orderSentEvidence=0
```

The live-readiness bundle currently includes:

```text
RUNTIME_EVIDENCE_CONFIG_DISABLED
RUNTIME_EVIDENCE_NO_SHADOW_INTENT
```

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
TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=false
EVENT_SCAN_NOTIFICATION_ENABLED=false
EXECUTION_EVENT_ENABLED=false
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
later live proposal still needs exact env diff, current smoke outputs, runtime
evidence rows, shadow-intent counts, tiny-live hard-stop state, signal
governance evidence, rollback steps, and separate operator authorization.
