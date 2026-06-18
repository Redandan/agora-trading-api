# Live Readiness Blocker Remediation Matrix

This matrix maps `smoke_live_readiness_bundle_ssh.ps1` blockers to the evidence
required before a future live proposal can be reviewed. It is read-only
documentation, not authorization to deploy, edit production env, enable live
trading, place orders, change OCO, run grid/fund/Earn actions, send Telegram,
run external backfill/import jobs, mutate DB state, or change schedulers.

Run the bundle first:

```powershell
.\scripts\smoke_live_readiness_bundle_ssh.ps1
```

`bundle_verdict=NOT_READY` is expected until all blockers below are cleared or
separately authorized for a narrower non-live phase. Do not use this matrix to
skip a blocker.

## Blocker Matrix

| Bundle blocker | Required read-only evidence | Clear condition | Allowed next action |
| --- | --- | --- | --- |
| `LIVE_READINESS_NOT_READY` | `.\scripts\audit_live_readiness_ssh.ps1` | Audit no longer prints `verdict=NOT_READY`; any `READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED` result is still not live approval. | Draft or update a separate operator review packet only. |
| `BACKGROUND_AUTOMATION_REVIEW` | `.\scripts\smoke_live_background_automation_ssh.ps1` | `high_risk_background_automation_true=[]` or every listed flag has a separate written authorization and rollback plan. | Review production env diff; do not apply it from this document. |
| `RUNTIME_EVIDENCE_CONFIG_DISABLED` | `.\scripts\smoke_runtime_evidence_rca_ssh.ps1` | Diagnosis is no longer `CONFIG_DISABLED` after a separately authorized evidence-only env change. | Continue evidence collection; do not enable execution flags. |
| `RUNTIME_EVIDENCE_NO_SHADOW_INTENT` | `.\scripts\smoke_runtime_evidence_rca_ssh.ps1` | `shadowIntentCount` is greater than 0 and `orderSentEvidence=0` for the reviewed window. | Keep collecting dry-run/shadow evidence and re-run the bundle. |
| `TINY_LIVE_LOSS_HARD_STOP` | `.\scripts\smoke_tiny_live_loss_rca_ssh.ps1` | `hardStopDetected=false`, consecutive tiny-live losses are below policy limit, a current BUY/add candidate exists, and runtime evidence is available. | Prepare a live review packet only after other blockers clear. |
| `SIGNAL_POLICY_REVIEW_GAPS` | `.\scripts\smoke_signal_correctness_ssh.ps1` | No `REVIEW_POLICY_GAPS`; governance drift and missed-opportunity recommendations are documented for operator review. | Keep hard safety gates; review relaxation candidates in shadow/tiny-live caps only. |
| `MCP_PARITY_NOT_PROVEN` | `.\scripts\smoke_mcp_parity_ssh.ps1` | Output includes `[mcp-parity-ssh] OK` and required read-only MCP tools are present on server-local `/api/mcp`. | Continue live-readiness review; do not expose public MCP service. |

## Current Expected Blockers

The current server bundle may legitimately report:

```text
LIVE_READINESS_NOT_READY
BACKGROUND_AUTOMATION_REVIEW
RUNTIME_EVIDENCE_CONFIG_DISABLED
RUNTIME_EVIDENCE_NO_SHADOW_INTENT
TINY_LIVE_LOSS_HARD_STOP
```

Those are live-blocking until the clear conditions above are proven by fresh
read-only evidence. MCP parity is expected to pass with `[mcp-parity-ssh] OK`.

## Non-Negotiable Guards

These remain forbidden until a separate live proposal explicitly authorizes a
bounded scope:

- `TRADING_OKX_ENABLED=true`
- `TRADING_OCO_POLLER_ENABLED=true`
- `TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true`
- `TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=true`
- `TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=true`
- `TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=true`
- `TRAILING_STOP_ENABLED=true`
- `POSITION_EXIT_MANAGER_ENABLED=true`
- `TRADING_GRID_ENABLED=true`
- `TRADING_FUNDING_ARB_ENABLED=true`
- `OKX_EARN_TOPUP_ENABLED=true`
- `MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=true`
- Telegram-send paths
- DB migration, Flyway baseline regeneration, extra-table cleanup, or table
  drops

## Review Packet Minimum

A future live review packet must include:

- latest `bundle_blockers` and `bundle_verdict`
- full outputs from every required read-only smoke listed above
- production env diff proposal
- expected blast radius and rollback plan
- runtime evidence status and shadow-intent counts
- tiny-live loss hard-stop status
- signal correctness and governance drift summary
- confirmation that `orderSentEvidence=0` during the evidence-only phase

Clearing this matrix is still not live approval. It only proves the evidence is
ready for a separate operator decision.
