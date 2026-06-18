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
| `ORDER_CAPABLE_FLAGS_REVIEW` | `.\scripts\audit_live_readiness_ssh.ps1` | `order_capable_flags_true=[]`; if any order/OCO/grid/fund/Earn/guardian live-action flag is true, it has separate written authorization and rollback evidence. | Stop live review; reconcile the already-enabled order-capable scope before any new proposal. |
| `SECRET_PREREQUISITES_MISSING` | `.\scripts\audit_live_readiness_ssh.ps1` | Required secret/env prerequisites are present without printing secret values. | Fix server secret prerequisites through a separately authorized ops change, then rerun read-only audit. |
| `RUNTIME_HEALTH_OR_LOG_NOT_CLEAN` | `.\scripts\audit_live_readiness_ssh.ps1` and `scripts/check_server_runtime_log.sh` | Health is `UP`, runtime log smoke passes, and no runtime ERROR/unexpected operation-like log lines are present. | Investigate runtime health/logs before any live proposal. |
| `EVENT_RISK_NOT_BASELINE` | `.\scripts\audit_live_readiness_ssh.ps1` | Event risk status is back to `riskLevel=R0`, or the elevated level has a separate written event-risk operating decision. | Do not open new live scope while event risk is elevated without explicit operator review. |
| `MCP_AUDIT_TOOL_ERROR` | `.\scripts\audit_live_readiness_ssh.ps1` | All required read-only audit MCP tool calls complete without `MCP_TOOL_ERROR`. | Fix MCP tool/runtime reachability before trusting bundle evidence. |
| `EXECUTION_ELIGIBILITY_NOT_READY` | `.\scripts\audit_live_readiness_ssh.ps1` | Audit no longer reports any `*_NOT_EXECUTION_ELIGIBLE` blocker; tiny-live and ScoreBuy execution gates have a current eligible candidate before review. | Keep live disabled; wait for or diagnose the current signal/execution gate before any live proposal. |
| `BACKGROUND_AUTOMATION_REVIEW` | `.\scripts\smoke_live_background_automation_ssh.ps1` | `high_risk_background_automation_true=[]` or every listed flag has a separate written authorization and rollback plan. | Review production env diff; do not apply it from this document. |
| `RUNTIME_EVIDENCE_CONFIG_DISABLED` | `.\scripts\smoke_runtime_evidence_rca_ssh.ps1` | Diagnosis is no longer `CONFIG_DISABLED` after a separately authorized evidence-only env change. | Continue evidence collection; do not enable execution flags. |
| `RUNTIME_EVIDENCE_NO_CANONICAL_ROWS` | `.\scripts\smoke_runtime_evidence_rca_ssh.ps1` | Diagnosis is no longer `NO_CANONICAL_ROWS`; canonical evidence rows exist in the bounded window. | Keep collecting evidence; do not enable execution flags. |
| `RUNTIME_EVIDENCE_NO_SHADOW_INTENT` | `.\scripts\smoke_runtime_evidence_rca_ssh.ps1` | `shadowIntentCount` is greater than 0 and `orderSentEvidence=0` for the reviewed window. | Keep collecting dry-run/shadow evidence and re-run the bundle. |
| `RUNTIME_EVIDENCE_ORDER_SENT` | `.\scripts\smoke_runtime_evidence_rca_ssh.ps1` | `orderSentEvidence=0` for the reviewed evidence-only window. | Stop live review; investigate why order-sent evidence exists before any new env plan. |
| `RUNTIME_EVIDENCE_REVIEW_REQUIRED` | `.\scripts\smoke_runtime_evidence_rca_ssh.ps1` | Diagnosis is no longer `REVIEW_RUNTIME_EVIDENCE_STATUS` and the runtime-evidence status has a documented interpretation. | Require operator review of runtime evidence before any live proposal. |
| `TINY_LIVE_LOSS_HARD_STOP` | `.\scripts\smoke_tiny_live_loss_rca_ssh.ps1` | `hardStopDetected=false`, consecutive tiny-live losses are below policy limit, a current BUY/add candidate exists, and runtime evidence is available. | Prepare a live review packet only after other blockers clear. |
| `TINY_LIVE_ROLLOUT_NOT_READY` | `.\scripts\smoke_tiny_live_loss_rca_ssh.ps1` | `canEnableProduction=true` for the reviewed rollout gate, with completed tiny-live samples and false-positive counts documented. | Keep live disabled; continue dry-run/tiny-live evidence collection before any production enablement review. |
| `SIGNAL_POLICY_REVIEW_GAPS` | `.\scripts\smoke_signal_correctness_ssh.ps1` | No `REVIEW_POLICY_GAPS`, no `governanceMode=TOO_STRICT` or `governanceMode=TOO_LOOSE`, and missed-opportunity `overallStatus` is not `FAIL` or `WARN`; any recommendations are documented for operator review. | Keep hard safety gates; review relaxation candidates in shadow/tiny-live caps only. |
| `MCP_PARITY_NOT_PROVEN` | `.\scripts\smoke_mcp_parity_ssh.ps1` | Output includes `[mcp-parity-ssh] OK` and required read-only MCP tools are present on server-local `/api/mcp`. | Continue live-readiness review; do not expose public MCP service. |
| `DEPLOYED_RUNTIME_NOT_CURRENT` | `.\scripts\smoke_live_readiness_bundle_ssh.ps1` deployment metadata section | `deployment_metadata_status=CURRENT` or `DOCS_TOOLING_ONLY_DRIFT`, and `origin_metadata_status=CURRENT_ORIGIN_MAIN`; runtime drift, a server worktree behind `origin/main`, or unknown metadata must not be used for live review. | Deploy and verify separately, or treat the bundle as stale evidence only. |

## Current Expected Blockers

The current server bundle may legitimately report:

```text
LIVE_READINESS_NOT_READY
EXECUTION_ELIGIBILITY_NOT_READY
BACKGROUND_AUTOMATION_REVIEW
RUNTIME_EVIDENCE_CONFIG_DISABLED
RUNTIME_EVIDENCE_NO_SHADOW_INTENT
SIGNAL_POLICY_REVIEW_GAPS
TINY_LIVE_LOSS_HARD_STOP
TINY_LIVE_ROLLOUT_NOT_READY
DEPLOYED_RUNTIME_NOT_CURRENT
```

Those are live-blocking until the clear conditions above are proven by fresh
read-only evidence. MCP parity is expected to pass with `[mcp-parity-ssh] OK`.
Because the latest recorded snapshot includes `DEPLOYED_RUNTIME_NOT_CURRENT`,
and the observed signal smoke showed governance drift (`governanceMode=TOO_STRICT`),
the snapshot is stale live-review evidence only and is reclassified by the
current local blocker rules. A future review must refresh the server runtime and
rerun the full read-only bundle; do not combine stale server output with local
or GitHub HEAD evidence.

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
