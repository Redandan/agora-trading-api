# Live Background Automation Env Diff Proposal

This is a review-only proposal for clearing the
`BACKGROUND_AUTOMATION_REVIEW` blocker reported by
`smoke_live_readiness_bundle_ssh.ps1`. It is not authorization to edit
production env, deploy, restart the service, enable live trading, place orders,
change OCO, run grid/fund/Earn actions, send Telegram, run external
backfill/import jobs, mutate DB state, or change schedulers.

## Current Evidence

The read-only background automation smoke currently reports:

```text
verdict=NOT_READY_BACKGROUND_AUTOMATION_REVIEW
blocker=HIGH_RISK_BACKGROUND_AUTOMATION_TRUE
```

Current true flags:

```text
TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED
TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED
MARKET_WS_AUTO_SUBSCRIBE_ENABLED
EVENT_SCAN_NOTIFICATION_ENABLED
EXECUTION_EVENT_ENABLED
TRADING_DAILY_TG_REPORT_ENABLED
TRADING_AUTONOMOUS_DIGEST_ENABLED
TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED
TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED
```

High-risk true flags:

```text
TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED
EVENT_SCAN_NOTIFICATION_ENABLED
EXECUTION_EVENT_ENABLED
TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED
TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED
```

## Proposed Evidence-Only Diff

If separately authorized, the following production env diff would reduce
background side effects before any live review:

```dotenv
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

This diff must not be bundled with runtime-evidence enablement, exchange
credentials, order-capable flags, OCO/grid/fund/Earn flags, Telegram-send
enablement, scheduler enablement, DB migrations, Flyway baseline regeneration,
extra-table cleanup, or table drops.

## Blast Radius

Expected reductions if the diff is separately authorized:

- no external backfill/import writes from market-data MCP tools unless manually
  re-authorized
- no event-scan scheduled outbound notification path
- no execution-event scheduled scanning path
- no autonomous digest Telegram send path
- no live-signal retry notification resend path
- no market WebSocket auto-subscribe side effects
- no daily Telegram report/autonomous digest background work

Expected non-goals:

- no change to signal generation quality
- no change to runtime evidence collection
- no change to exchange order capability
- no change to OCO/grid/fund/Earn behavior
- no change to database schema or Flyway state

## Required Verification After Separate Authorization

After any authorized env change and service restart, run:

```powershell
.\scripts\verify_split_acceptance_ssh.ps1
.\scripts\smoke_live_background_automation_ssh.ps1
.\scripts\audit_live_readiness_ssh.ps1
.\scripts\smoke_live_readiness_bundle_ssh.ps1
```

Expected:

- `background_automation_true=[]`
- `high_risk_background_automation_true=[]`
- `background_automation_false` lists all nine reviewed background flags.
- `verdict=OK_BACKGROUND_AUTOMATION_DISABLED`
- `BACKGROUND_AUTOMATION_REVIEW` no longer appears in `bundle_blockers`
- `order_capable_flags` remain false
- runtime logs show no order placement, OCO modification, grid/fund/Earn
  operation, Telegram send, scheduler surprise, exchange write, external
  backfill/import, or DB mutation

## Rollback Criteria

Restore the prior env state and investigate if any of these occur:

- required market-data or operator notification workflow is missing and was
  separately approved to run
- app health or split acceptance fails
- runtime logs show unexpected errors outside the known baseline
- any order/OCO/grid/fund/Earn/Telegram/exchange/DB mutation appears
- `smoke_live_readiness_bundle_ssh.ps1` reports a new blocker unrelated to
  background automation
- `background_automation_false` does not list every reviewed background flag
  after the authorized env change

## Live Boundary

Clearing `BACKGROUND_AUTOMATION_REVIEW` is not live approval. The system remains
blocked while runtime evidence, tiny-live hard stop, market-condition, or signal
policy blockers remain. A later live proposal still needs exact env diff,
current smoke outputs, full-bundle `bundle_blockers=[]`,
`live_review_packet_allowed=true`,
`deploy_required_before_live_review=false`,
`bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`, runtime evidence,
tiny-live hard-stop state, signal governance evidence, and separate operator
authorization.
