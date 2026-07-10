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
The bundle also prints `bundle_blocker_summary`, a machine-readable list of the
same blockers with category, required read-only evidence, evidence markers, and
next action. That summary is for automation and review-packet drafting only; it
does not clear `bundle_blockers` and does not authorize production env changes.

When the question is narrower - whether the LOCAL_TRADINGVIEW parity path alone
would act on a TradingView-style BUY - run the focused smoke instead:

```powershell
.\scripts\smoke_local_tradingview_only_readiness_ssh.ps1
```

It deliberately excludes TinyLive, ScoreBuy, runtime-evidence, and
signal-policy blockers and prints
`legacy_tiny_scorebuy_runtime_evidence_not_evaluated=true`,
`local_tradingview_only_status`, `local_tradingview_only_blockers`,
`local_tradingview_only_health_warnings`,
`local_tradingview_only_legacy_blockers_excluded=true`, and
`notAuthorization=read-only LOCAL_TRADINGVIEW-only readiness evidence`.
Use `WAIT_BUY`, `READY_CURRENT_BUY_CANDIDATE_LIVE_MICRO_ARMED`, or `BLOCKED` as
the focused LOCAL_TRADINGVIEW status. Runtime-log findings such as
`RUNTIME_LOG_NOT_CLEAN`, and event-risk audit findings such as
`EVENT_RISK_NOT_BASELINE`, stay in `local_tradingview_only_health_warnings`;
use the full bundle when a complete live-readiness review is needed.
The focused smoke also prints `local_tradingview_pre_execution_evidence_status`,
`local_tradingview_pre_execution_readiness`,
`local_tradingview_pre_execution_blockers`,
`local_tradingview_okx_auto_trade_enabled`,
`local_tradingview_okx_private_credentials_configured`,
`local_tradingview_notional_accepted`,
`local_tradingview_daily_cap_available`,
`local_tradingview_open_position_cap_available`,
`local_tradingview_open_exact_position_exists`, and
`local_tradingview_duplicate_bar_exists` so the daily check can fail closed on
the same DB/env gates that would stop a real LOCAL_TRADINGVIEW order.
Origin/main drift that contains only docs or local tooling is reported as
`deployment_metadata_effective_status=DOCS_TOOLING_ONLY_DRIFT` plus
`DOCS_TOOLING_ONLY_DRIFT_NOT_DEPLOYED`, not as a LOCAL_TRADINGVIEW blocker.

## Blocker Matrix

| Bundle blocker | Required read-only evidence | Clear condition | Allowed next action |
| --- | --- | --- | --- |
| `LIVE_READINESS_EVIDENCE_UNAVAILABLE` | `.\scripts\smoke_live_readiness_bundle_ssh.ps1` | Bundle reaches deployment metadata and all read-only smoke sections without `SSH_AUTH_FAILED`, `SSH_CONNECT_FAILED`, `SSH_COMMAND_FAILED`, or `READ_ONLY_SMOKE_FAILED`. | Fix SSH access, key selection, or the failing read-only smoke and rerun the bundle before drawing any server/live conclusion. |
| `LIVE_READINESS_NOT_READY` | `.\scripts\audit_live_readiness_ssh.ps1` | Audit explicitly prints `verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`; missing readiness verdicts stay blocked, and any ready result is still not live approval. | Draft or update a separate operator review packet only. |
| `ORDER_CAPABLE_FLAGS_REVIEW` | `.\scripts\audit_live_readiness_ssh.ps1`, then `.\scripts\prepare_live_order_capable_scope_review_packet.ps1` when unexpected flags are true | Audit explicitly prints `order_capable_flags_true`, `order_capable_flags_accepted`, and `order_capable_flags_unexpected=[]`; missing order-capable evidence stays blocked, including missing unexpected-flag evidence. Accepted true flags are limited to the exact active scope, such as LOCAL_TRADINGVIEW LIVE_MICRO effective OKX/OCO/execution flags or BTC_BASE_LIVE_MICRO effective OKX/execution flags plus OCO poller for existing OCO lifecycle cleanup. Any unexpected order/OCO/grid/fund/Earn/guardian live-action flag must still be covered by a local scope packet with per-flag coverage, current risk items, rollback env diff, and `order_allowed=false` before an operator can decide whether to accept or roll back that exact scope. | Stop live review for unexpected flags; assemble the order-capable scope review packet and either request a separate reconcile/rollback decision or roll the scope back before any new proposal. |
| `SECRET_PREREQUISITES_MISSING` | `.\scripts\audit_live_readiness_ssh.ps1` | Audit prints masked secret presence and `TRADING_OKX_API_KEY`, `TRADING_OKX_SECRET_KEY`, and `TRADING_OKX_PASSPHRASE` are all `SET`; missing masked secret evidence stays blocked. | Fix server secret prerequisites through a separately authorized ops change, then rerun read-only audit. |
| `RUNTIME_HEALTH_OR_LOG_NOT_CLEAN` | `.\scripts\audit_live_readiness_ssh.ps1` and `scripts/check_server_runtime_log.sh` | Audit explicitly prints health `UP` and `runtime_log_status=PASS`; missing health/log evidence stays blocked, and runtime logs must have no ERROR or unexpected operation-like lines. If the log smoke prints `ERROR rca=TELEGRAM_EXECUTION_EVENT_NOTIFICATION_PATH`, the Telegram/ExecutionEvent notification path and related background automation flags must be reviewed together before any live proposal. | Investigate runtime health/logs before any live proposal. |

Runtime-log `ERROR category ... unknown=N` with `N > 0` remains
`RUNTIME_HEALTH_OR_LOG_NOT_CLEAN` even when known Telegram/ExecutionEvent counts
are zero. Unknown runtime errors require RCA before any live-review packet; do
not use the Telegram/ExecutionEvent RCA text to explain unrelated ERROR lines.
| `EVENT_RISK_NOT_BASELINE` | `.\scripts\audit_live_readiness_ssh.ps1` | Audit explicitly prints `riskLevel=R0`; missing event-risk evidence stays blocked, and any elevated level needs a separate written event-risk operating decision. | Do not open new live scope while event risk is elevated without explicit operator review. |
| `MCP_AUDIT_TOOL_ERROR` | `.\scripts\audit_live_readiness_ssh.ps1` | All required read-only audit MCP tool calls complete without `MCP_TOOL_ERROR`, `missing_readiness_detail_fields=[]`, and parsed `readiness_details` JSON includes tiny-live, autonomous-opportunity, and all ScoreBuy gate sections; missing MCP readiness-details evidence stays blocked. | Fix MCP tool/runtime reachability before trusting bundle evidence. |
| `EXECUTION_ELIGIBILITY_NOT_READY` | `.\scripts\audit_live_readiness_ssh.ps1` | Audit no longer reports any `*_NOT_EXECUTION_ELIGIBLE` blocker, and parsed `readiness_details` JSON explicitly shows tiny-live plus all ScoreBuy execution gates with `executionEligible=true`; missing execution eligibility evidence stays blocked. | Keep live disabled; wait for or diagnose the current signal/execution gate before any live proposal. |
| `BACKGROUND_AUTOMATION_REVIEW` | `.\scripts\smoke_live_background_automation_ssh.ps1` | Smoke explicitly prints `verdict=OK_BACKGROUND_AUTOMATION_DISABLED` or `verdict=OK_BACKGROUND_AUTOMATION_REVIEWED`, `backgroundAutomationClear=true`, `background_automation_blockers=[]`, `background_automation_unreviewed_true=[]`, `high_risk_background_automation_true=[]`, `missing_background_automation_flags=[]`, and a review plan. A fully disabled background state still prints `background_automation_review_plan=[]`. Missing OK verdict, missing clear summary, missing review plan evidence, missing high-risk background evidence, or missing reviewed env keys stay blocked. A reviewed true `MARKET_WS_AUTO_SUBSCRIBE_ENABLED` is accepted only for LOCAL_TRADINGVIEW OKX kline-close ingestion with warm-up disabled; any unreviewed true or missing flag requires separate written authorization and rollback plan. | Review production env diff; do not apply it from this document. |

For this blocker, missing high-risk background evidence stays blocked. Missing
reviewed env keys also stay blocked because absent keys are not explicit false
evidence for live review. New background automation env flags must be added to
the background smoke, live-readiness audit, env diff proposal, and local
coverage test before any live-review packet can use the evidence.
The full bundle also fails closed when `background_automation_review_plan` is
missing, when `background_automation_unreviewed_true` is non-empty, or when
background automation is otherwise clear but the plan still has `state=TRUE` or
`state=MISSING` entries.
| `RUNTIME_EVIDENCE_CONFIG_DISABLED` | `.\scripts\smoke_runtime_evidence_rca_ssh.ps1` | Diagnosis is no longer `CONFIG_DISABLED` after a separately authorized evidence-only env change. | Continue evidence collection; do not enable execution flags. |
| `RUNTIME_EVIDENCE_NO_CANONICAL_ROWS` | `.\scripts\smoke_runtime_evidence_rca_ssh.ps1` | Diagnosis is no longer `NO_CANONICAL_ROWS`; canonical evidence rows exist in the bounded window. | Keep collecting evidence; do not enable execution flags. |
| `RUNTIME_EVIDENCE_NO_TARGET_STRATEGY_CANONICAL_ROWS` | `.\scripts\smoke_runtime_evidence_rca_ssh.ps1` | Diagnosis is no longer `NO_TARGET_STRATEGY_CANONICAL_ROWS`; `targetStrategyEvidenceRows` is greater than 0 for the reviewed strategy id. Symbol-level canonical rows alone are not strategy-specific approval evidence. | Keep collecting target-strategy dry-run/shadow evidence; do not enable execution flags. |
| `RUNTIME_EVIDENCE_NO_SHADOW_INTENT` | `.\scripts\smoke_runtime_evidence_rca_ssh.ps1` | The smoke explicitly prints `diagnosis=CANONICAL_SHADOW_READY`, `targetStrategyShadowLikeRows` greater than 0, and `orderSentEvidenceBlockerCount=0` for the reviewed target-strategy window. Missing or `N/A` target-strategy shadow-intent evidence stays blocked. | Keep collecting dry-run/shadow evidence and re-run the bundle. |
| `RUNTIME_EVIDENCE_ORDER_SENT` | `.\scripts\smoke_runtime_evidence_rca_ssh.ps1` | The smoke explicitly prints `orderSentEvidenceBlockerCount=0` for the reviewed target-strategy window. The raw `orderSentEvidence` count may be positive when the script also lists known non-target order rows such as Strategy 508 or non-autonomous grid evidence; target-strategy or unclassified order evidence remains blocked. | Stop live review; investigate target-strategy or unclassified order-sent evidence before any new env plan. |
| `RUNTIME_EVIDENCE_REVIEW_REQUIRED` | `.\scripts\smoke_runtime_evidence_rca_ssh.ps1` | Diagnosis is `CANONICAL_SHADOW_READY`, not `REVIEW_RUNTIME_EVIDENCE_STATUS`; `missing_runtime_evidence_fields=[]`; diagnosis/order-sent markers are present and documented; and `runtime_evidence_review_plan` is present with no `BLOCKED` or `HARD_BLOCKED` state when the diagnosis is otherwise ready. Missing runtime-evidence fields, a missing review plan, an unrecognized diagnosis, or a blocked review-plan entry stays blocked. | Require operator review of runtime evidence before any live proposal. |

Missing or unrecognized runtime-evidence diagnosis stays blocked. Missing
runtime-evidence fields also stay blocked and must not be interpreted as
`CANONICAL_SHADOW_READY`. A missing `runtime_evidence_review_plan`, or a
`BLOCKED`/`HARD_BLOCKED` review-plan state on an otherwise ready diagnosis,
also fails closed.
| `TINY_LIVE_LOSS_HARD_STOP` | `.\scripts\smoke_tiny_live_loss_rca_ssh.ps1 -RequireClear` | The smoke exits 0 only when it explicitly prints `hardStopDetected=false`, `missing_tiny_live_hard_stop_fields=[]`, consecutive tiny-live losses are below policy limit, a current BUY/add candidate exists, runtime evidence is available, and the rollout gate is clear. Missing or `N/A` hard-stop evidence stays blocked. | Prepare a live review packet only after other blockers clear. |
| `TINY_LIVE_ROLLOUT_NOT_READY` | `.\scripts\smoke_tiny_live_loss_rca_ssh.ps1 -RequireClear` | The smoke exits 0 only when it explicitly prints `canEnableProduction=true`, `missing_tiny_live_rollout_fields=[]`, and `missing_tiny_live_fields=[]` for the reviewed rollout gate, with completed tiny-live samples and false-positive counts documented. Missing or `N/A` rollout evidence stays blocked. | Keep live disabled; continue dry-run/tiny-live evidence collection before any production enablement review. |
| `SIGNAL_POLICY_REVIEW_GAPS` | `.\scripts\smoke_signal_correctness_ssh.ps1 -RequireClear` | The smoke exits 0 only when it prints `signalPolicyClear=true`, no `REVIEW_POLICY_GAPS`, `missing_signal_policy_fields=[]`, explicit 7d `governanceMode` is present and not `governanceMode=TOO_STRICT`, `governanceMode=TOO_LOOSE`, or `governanceMode=INSUFFICIENT_DATA`, missed-opportunity `overallStatus=PASS`, and `signal_policy_review_plan` is present without `BLOCKED` or `REVIEW` state when signal policy is otherwise clear; Missing signal-policy fields, missing `signalPolicyClear=true`, missing review-plan evidence, and missing or `N/A` governance/missed-opportunity evidence stay blocked. The smoke also prints `signal_policy_review_plan` with `riskCategory`, `evidenceMarkers`, `requiredEvidence`, `nextAction`, and `notAuthorization` for each blocked/review gate. | Keep hard safety gates; review relaxation candidates in shadow/tiny-live caps only. |
| `LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE` | `.\scripts\smoke_local_tradingview_candidate_ssh.ps1 -RequireCurrentCandidate` | The smoke explicitly prints `currentCandidateStatus=HAS_CURRENT_BUY_CANDIDATE`. Missing current-candidate evidence, stale latest-bar evidence, or `LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE` stays blocked. | Wait for the latest closed bar to emit a LOCAL_TRADINGVIEW parity BUY before any live plan. |
| `LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_NOT_ARMED` | `.\scripts\smoke_local_tradingview_candidate_ssh.ps1 -RequireDryRunArmed` and `.\scripts\prepare_local_tradingview_dry_run_receipt_env_handoff.ps1 -RequireReady` before any env request | The smoke explicitly prints `localTradingViewExecutionDryRunArmed=true`, `effectiveExecutionDryRun=true`, and `effectiveLiveOrderEnabled=false`. Missing dry-run receipt evidence or `LOCAL_TRADINGVIEW_DRY_RUN_NOT_ARMED` stays blocked. | Use the read-only dry-run receipt env handoff packet and obtain separate exact env/deploy authorization before changing production env. |
| `LOCAL_TRADINGVIEW_LIVE_MICRO_NOT_ARMED` | `.\scripts\smoke_local_tradingview_candidate_ssh.ps1 -RequireLiveMicroArmed` | When `executionMode=LIVE_MICRO`, the smoke must explicitly print `localTradingViewLiveMicroArmed=true`. Missing live-micro armed evidence, wrong source/evaluator env, or `LOCAL_TRADINGVIEW_LIVE_MICRO_NOT_ARMED` stays blocked. This does not authorize an order. | Fix LOCAL_TRADINGVIEW LIVE_MICRO source/execution env through a separately authorized env plan, then rerun read-only evidence. |
| `LOCAL_TRADINGVIEW_BTC_BASE_LIVE_MICRO_NOT_ARMED` | `.\scripts\smoke_local_tradingview_candidate_ssh.ps1 -RequireBtcBaseLiveMicroArmed` | When `executionMode=BTC_BASE_LIVE_MICRO`, the smoke must explicitly print `localTradingViewBtcBaseLiveMicroArmed=true`. Missing BTC_BASE live armed evidence, wrong source/evaluator env, or `LOCAL_TRADINGVIEW_BTC_BASE_LIVE_MICRO_NOT_ARMED` stays blocked. This does not authorize an order. OCO lifecycle tracking is intentionally not required for BTC_BASE live mode. | Fix LOCAL_TRADINGVIEW BTC_BASE_LIVE_MICRO source/execution env through a separately authorized env plan, then rerun read-only evidence. |
| `LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED` | `.\scripts\smoke_local_tradingview_candidate_ssh.ps1 -RequireOcoLifecycleTracked` and `.\scripts\prepare_local_tradingview_oco_lifecycle_env_handoff.ps1 -RequireReady` before any env request | When `executionMode=LIVE_MICRO` is armed, the smoke must explicitly print `localTradingViewOcoLifecycleTracked=true` and `localTradingViewOcoLifecycleStatus=TRACKED_BY_OCO_POLLER`. Missing close-detection evidence, `TRADING_OCO_POLLER_ENABLED=false`, or `LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED` stays blocked. The handoff packet must emit `LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_PACKET`, `READY_FOR_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_NOT_MUTATION`, exact OCO lifecycle authorization for `TRADING_OCO_POLLER_ENABLED=true`, and `POSITION_EXIT_MANAGER_ENABLED=false`. | Use the read-only OCO lifecycle env handoff packet and obtain separate exact env/deploy authorization before changing production env. Post-env verify with `.\scripts\smoke_local_tradingview_candidate_ssh.ps1 -RequireLiveMicroArmed -RequireOcoLifecycleTracked`. |
| `LOCAL_TRADINGVIEW_EVALUATOR_NOT_ACTIVE` | `.\scripts\smoke_local_tradingview_candidate_ssh.ps1` | The smoke explicitly prints `primary=LOCAL_TRADINGVIEW`, `localEnabled=true`, and `localTradingViewEvaluatorActive=true`. Missing evaluator evidence stays blocked. | Keep live disabled and fix LOCAL_TRADINGVIEW source/evaluator env through a separately authorized env plan. |
| `LOCAL_TRADINGVIEW_DATA_COVERAGE_NOT_OK` | `.\scripts\smoke_local_tradingview_candidate_ssh.ps1` | The smoke prints `coverage=OK` or a reviewed `coverage=WARN` with bounded trailing gap. `LOCAL_TRADINGVIEW_DATA_COVERAGE_NOT_OK` stays blocked. | Fix local TradingView parity data coverage before treating candidate evidence as valid. |
| `LOCAL_TRADINGVIEW_OCO_PREFLIGHT_FAILED` | `.\scripts\smoke_live_readiness_bundle_ssh.ps1` and TP/SL/OCO feasibility smokes | No `OCO_PREFLIGHT_FAILED` marker appears unless it is explicitly paired with `ocoPreflightPendingUntilBuyCandidate=NOT_READY_MISSING_ENTRY_TP_SL`. A pending-until-buy-candidate warning is not a primary OCO failure; a real OCO preflight failure stays blocked. | Stop live review and inspect TP/SL/OCO feasibility before any live order path. |
| `LOCAL_TRADINGVIEW_ORDER_SENT_EVIDENCE` | `.\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady` and `.\scripts\smoke_local_tradingview_candidate_ssh.ps1` | Runtime evidence prints `orderSentEvidenceBlockerCount=0`, and the local TradingView smoke keeps `orderSentAllowed=false` and `liveOrderMutationAllowed=false`. Known non-target order rows must be listed under `order_sent_evidence_rows`; target/unclassified order evidence or true mutation markers stay blocked. | Stop live review and investigate target/unclassified order evidence or LOCAL_TRADINGVIEW mutation markers. |

Focused LOCAL_TRADINGVIEW-only pre-execution blockers are emitted by
`.\scripts\smoke_local_tradingview_candidate_ssh.ps1` and folded into
`.\scripts\smoke_local_tradingview_only_readiness_ssh.ps1`, but they are not
full-bundle blocker rows:

- `LOCAL_TRADINGVIEW_PRE_EXECUTION_DB_EVIDENCE_UNAVAILABLE`: clear only when the smoke prints `localTradingViewPreExecutionEvidenceStatus=OK`, `local_tradingview_pre_execution_evidence_status=OK`, and a parseable `local_tradingview_pre_execution_blockers` array.
- `LOCAL_TRADINGVIEW_DAILY_CAP_REACHED`: clear only when `localTradingViewDailyCapAvailable=true` and `localTradingViewOrdersToday` is below `localTradingViewMaxOrdersPerDay`.
- `LOCAL_TRADINGVIEW_OPEN_POSITION_CAP_REACHED`: clear only when `localTradingViewOpenPositionCapAvailable=true`.
- `LOCAL_TRADINGVIEW_OPEN_POSITION_EXISTS`: clear only when `localTradingViewOpenExactPositionExists=false` for the same strategy, symbol, side, and interval.
- `LOCAL_TRADINGVIEW_DUPLICATE_BAR`: clear only when a current BUY candidate exists and `localTradingViewDuplicateBarExists=false`.
- `LOCAL_TRADINGVIEW_SIGNAL_STALE`: clear only when a current BUY candidate exists, `localTradingViewSignalStale=false`, and signal age is within `TRADINGVIEW_LOCAL_MAX_SIGNAL_AGE_HOURS`.

Signal policy clear evidence requires no `REVIEW_POLICY_GAPS`, a signal
correctness and governance drift summary, and explicit review-plan evidence.
Missing signal-policy fields stay blocked. For machine checks, missing or `N/A` governance/missed-opportunity evidence stays blocked.
A missing `signal_policy_review_plan`, `state=BLOCKED`, or `state=REVIEW`
entry on an otherwise clear signal-policy summary also fails closed.
| `MCP_PARITY_NOT_PROVEN` | `.\scripts\smoke_mcp_parity_ssh.ps1` | Output includes `required_tools=[...]`, `missing_required_tools=[]`, `[mcp-parity-ssh] OK`, and required read-only MCP tools are present on server-local `/api/mcp`. Missing or non-empty required-tool evidence stays blocked: missing `required_tools`, missing or non-empty `missing_required_tools`, or missing OK evidence is not live-readiness proof. | Continue live-readiness review; do not expose public MCP service. |
| `DEPLOYED_RUNTIME_NOT_CURRENT` | `.\scripts\smoke_live_readiness_bundle_ssh.ps1` deployment metadata section, or `.\scripts\smoke_live_deployment_metadata_ssh.ps1` for a metadata-only refresh | `deployment_metadata_status=CURRENT` or `DOCS_TOOLING_ONLY_DRIFT`, and `origin_metadata_status=CURRENT_ORIGIN_MAIN`; missing metadata, runtime drift, a server worktree behind `origin/main`, or unknown metadata must not be used for live review. `DEPLOYMENT_METADATA_ONLY` output is not live-readiness evidence and is not a substitute for the full bundle. | Deploy and verify separately, or treat the bundle as stale evidence only. |

The full bundle also stops before child smokes when deployment metadata is
already stale, emitting `read_only_bundle_error=DEPLOYED_RUNTIME_NOT_CURRENT`
`bundle_blocker_summary`, and `bundle_verdict=NO_EVIDENCE`. Use
`-ContinueWhenRuntimeStale` only for
diagnostic stale-runtime child-smoke output; do not use that output as current
live-readiness evidence.

For standalone metadata-only refreshes, `read_only_metadata_error=SSH_AUTH_FAILED`,
`SSH_CONNECT_FAILED`, `SSH_COMMAND_FAILED`, or `READ_ONLY_SMOKE_FAILED` maps to
`LIVE_READINESS_EVIDENCE_UNAVAILABLE`. The script also prints
`metadata_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE"]`,
`live_review_packet_allowed=false`, `deploy_required_before_live_review=unknown`,
and `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`; this is an
incomplete metadata refresh and must not be used as live-readiness evidence.
Even a successful metadata-only refresh with `metadata_current=true` or
`deployment_metadata_status=DOCS_TOOLING_ONLY_DRIFT` still prints
`live_review_packet_allowed=false`; it is only a currentness probe and does not
replace the full live-readiness bundle.

`.\scripts\smoke_live_origin_delta_local.ps1` is an optional read-only routing
aid for this same question. It runs the metadata-only SSH smoke and uses local
`git diff --name-only` evidence to classify the server worktree to
`origin/main` delta as `DOCS_TOOLING_ONLY_DRIFT`, `RUNTIME_DRIFT`,
`CURRENT_ORIGIN_MAIN`, or `NO_LOCAL_EVIDENCE`. It also prints
`live_review_packet_allowed=false`; do not use it to clear live-readiness
blockers without a fresh full bundle.

For full-bundle child-smoke failures after deployment metadata was collected,
`LIVE_READINESS_EVIDENCE_UNAVAILABLE` remains the primary evidence-status
blocker, but the failure output also preserves `deployment_metadata_status` and
`origin_metadata_status`. If those metadata lines prove stale runtime or a
server worktree behind `origin/main`, the same failure output includes
`DEPLOYED_RUNTIME_NOT_CURRENT` and `deploy_required_before_live_review=true`.
The same failure output also includes `bundle_blocker_summary` for automation;
it remains incomplete evidence and does not clear blockers.

## Latest Attached Expected Blockers

The attached read-only server bundle on 2026-07-03T17:35+08:00 observed
deployed runtime `a8253e2b058e1a696b65ba9769b00458ab47aedc` and
`origin/main` at `8aff0bce8fcca9c46c025869ea970aa919c347ec`. The origin delta
was docs/tooling-only, so `deploy_required_before_live_review=false`;
`runtime_log_status=PASS`, `missing_readiness_detail_fields=[]`, and
`live_review_packet_allowed=false`.

That attached full-bundle evidence may legitimately report:

```text
LIVE_READINESS_NOT_READY
ORDER_CAPABLE_FLAGS_REVIEW
EXECUTION_ELIGIBILITY_NOT_READY
BACKGROUND_AUTOMATION_REVIEW
RUNTIME_EVIDENCE_NO_CANONICAL_ROWS
SIGNAL_POLICY_REVIEW_GAPS
TINY_LIVE_ROLLOUT_NOT_READY
LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE
LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED
```

Those are live-blocking until the clear conditions above are proven by fresh
read-only evidence. Treat this block as an attached example for traceability,
not as currentness evidence after later commits. MCP parity is expected to pass
with `required_tools=[...]`, `missing_required_tools=[]`, and
`[mcp-parity-ssh] OK`.
Do not chase docs-only deploy commits by rewriting this attached snapshot after
every documentation refresh; currentness must come from a freshly rerun
`smoke_live_deployment_metadata_ssh.ps1` plus the full
`smoke_live_readiness_bundle_ssh.ps1` output, not from the SHA embedded in this
document.
The attached MCP audit details were complete enough for this gate
(`missing_readiness_detail_fields=[]`), so `MCP_AUDIT_TOOL_ERROR` is no longer
part of the attached blocker set. The attached runtime blocker is not a
runtime-log failure; strict runtime-log evidence is clean, runtime evidence is
enabled, and the current gap is `diagnosis=NO_CANONICAL_ROWS` with
`shadowIntentCount=2` and no scoped order-sent blocker.
The attached background automation evidence also printed
`backgroundAutomationClear=false` and
`background_automation_blockers=["BACKGROUND_AUTOMATION_TRUE"]` with
`high_risk_background_automation_true=[]`.
After the LOCAL_TRADINGVIEW LIVE_MICRO readiness split, a fresh bundle that sees
`executionMode=LIVE_MICRO` must not require the dry-run receipt marker; it
instead reports `LOCAL_TRADINGVIEW_LIVE_MICRO_NOT_ARMED` or
`LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED` when the live-micro path or its OCO
close-detection lifecycle is not proven.
The attached bundle also printed `bundle_blocker_summary` with categories and
required read-only follow-up evidence plus evidence markers for every listed
blocker.

With the current fail-fast bundle behavior, if future deployment metadata
already shows the server worktree or deployed runtime is behind `origin/main`,
the default `.\scripts\smoke_live_readiness_bundle_ssh.ps1` output is incomplete
evidence only:

```text
LIVE_READINESS_EVIDENCE_UNAVAILABLE
DEPLOYED_RUNTIME_NOT_CURRENT
```

That default fail-fast result is the expected output only for a stale-runtime
scenario until a separately authorized deploy refreshes the server runtime. Run
`.\scripts\smoke_live_readiness_bundle_ssh.ps1 -ContinueWhenRuntimeStale` only
for diagnostic stale-runtime child-smoke output. Any such diagnostic output is
not as current live-readiness evidence.

Latest stale-runtime diagnostic refresh:

- 2026-06-21T00:04+08:00 metadata-only smoke followed docs/tooling commit
  `76b5f00db9e93a249a55f93ff0f87b921ec262bb`. Server worktree and deployed
  `app.commit` remained at `ef6253a4ecff7c27a2e709f226e166389700a82d`, while
  `origin/main` was `76b5f00db9e93a249a55f93ff0f87b921ec262bb`.
- Metadata output printed `deployment_metadata_status=CURRENT`,
  `origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN`,
  `metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `deploy_required_before_live_review=true`,
  `live_review_packet_allowed=false`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`.
- Stale-runtime diagnostics confirmed active port `8084`, local health and
  server-local `/api/mcp` passed, MCP parity printed
  `required_tools=[...]`, `missing_required_tools=[]`,
  `[mcp-parity-ssh] OK`, `toolCount=305`, and `required=35`, and the runtime
  log smoke passed with ERROR count 0 plus WARN baseline total 18.
- The diagnostic bundle with `-ContinueWhenRuntimeStale` still printed
  `bundle_verdict=NOT_READY` with blockers `LIVE_READINESS_NOT_READY`,
  `EXECUTION_ELIGIBILITY_NOT_READY`, `BACKGROUND_AUTOMATION_REVIEW`,
  `RUNTIME_EVIDENCE_CONFIG_DISABLED`, `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`,
  `TINY_LIVE_LOSS_HARD_STOP`, `TINY_LIVE_ROLLOUT_NOT_READY`,
  `SIGNAL_POLICY_REVIEW_GAPS`, and `DEPLOYED_RUNTIME_NOT_CURRENT`.
  Signal correctness stayed blocked with `signalPolicyClear=false`,
  `governanceMode=TOO_STRICT`, missed-opportunity `overallStatus=WARN`,
  `suspiciousNoBuyCount=10`, and `falseBlockRiskCount=10`. `scoreBuyPostScoutAdd`
  was now `executionEligible=true` with state `ADD_ON_PULLBACK_READY`, but
  pre-position, confirmed-deploy, and tiny-live review gates still blocked
  current live review.

This refresh is stale-runtime diagnostic/read-only RCA evidence only. It is not
live-readiness evidence, not a replacement for a post-deploy full bundle, and
not permission to enable live trading.

- 2026-06-20T20:53+08:00 metadata-only smoke followed docs/tooling commit
  `0c033972b4bd39531d0e617d0f2702926108686f`. Server worktree and deployed
  `app.commit` remained at `ef6253a4ecff7c27a2e709f226e166389700a82d`, while
  `origin/main` was `0c033972b4bd39531d0e617d0f2702926108686f`.
- Metadata output printed `deployment_metadata_status=CURRENT`,
  `origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN`,
  `metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `deploy_required_before_live_review=true`,
  `live_review_packet_allowed=false`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`.
- Stale-runtime diagnostics confirmed active port `8084`, local health and
  server-local `/api/mcp` passed, public dedicated `/api/mcp` and shared-host
  `/api/trading/mcp` were blocked with 404, nginx exact MCP blocks had no
  `proxy_pass`, server-local MCP parity printed `required_tools=[...]`,
  `missing_required_tools=[]`, `toolCount=305`, and `required=35`, and the
  runtime log smoke passed with ERROR count 0 plus WARN baseline total 14.
- The diagnostic bundle with `-ContinueWhenRuntimeStale` still printed
  `bundle_verdict=NOT_READY` with blockers `LIVE_READINESS_NOT_READY`,
  `EXECUTION_ELIGIBILITY_NOT_READY`, `BACKGROUND_AUTOMATION_REVIEW`,
  `RUNTIME_EVIDENCE_CONFIG_DISABLED`, `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`,
  `TINY_LIVE_LOSS_HARD_STOP`, `TINY_LIVE_ROLLOUT_NOT_READY`,
  `SIGNAL_POLICY_REVIEW_GAPS`, and `DEPLOYED_RUNTIME_NOT_CURRENT`.
  Signal correctness stayed blocked with `signalPolicyClear=false`,
  `governanceMode=TOO_STRICT`, and missed-opportunity `overallStatus=WARN`.

This refresh is stale-runtime diagnostic evidence only. It is not
live-readiness evidence, not a replacement for a post-deploy full bundle, and
not permission to enable live trading.

Latest read-only routing and blocker RCA refresh:

- 2026-06-21T00:04+08:00 origin-delta classifier observed server worktree
  `ef6253a4ecff7c27a2e709f226e166389700a82d` while local `origin/main` was
  `76b5f00db9e93a249a55f93ff0f87b921ec262bb`. It printed
  `origin_delta_local_evidence=true`,
  `origin_delta_status=DOCS_TOOLING_ONLY_DRIFT`, `origin_delta_files=23`,
  `origin_docs_tooling_delta_files=23`, `origin_runtime_delta_files=0`,
  `origin_runtime_delta_paths=[]`, and `live_review_packet_allowed=false`.
- The full read-only bundle remained `bundle_verdict=NOT_READY` with blockers
  `LIVE_READINESS_NOT_READY`, `EXECUTION_ELIGIBILITY_NOT_READY`,
  `BACKGROUND_AUTOMATION_REVIEW`, `RUNTIME_EVIDENCE_CONFIG_DISABLED`,
  `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`, `TINY_LIVE_LOSS_HARD_STOP`,
  `TINY_LIVE_ROLLOUT_NOT_READY`, `SIGNAL_POLICY_REVIEW_GAPS`, and
  `DEPLOYED_RUNTIME_NOT_CURRENT`.
- `audit_live_readiness_ssh` reported health `UP`, `runtime_log_status=PASS`,
  runtime ERROR count 0, WARN baseline total 18, `order_capable_flags_true=[]`,
  all reviewed dry-run flags true, `riskLevel=R0`, and
  `missing_readiness_detail_fields=[]`.
- Server-local MCP parity printed `required_tools=[...]`,
  `missing_required_tools=[]`, `[mcp-parity-ssh] OK`, `toolCount=305`, and
  `required=35`.
- Signal correctness remained executable and showed
  `executionMachineStatus=no missing evaluation; no missed order`, but
  `signalPolicyClear=false` because 7d governance drift was `TOO_STRICT` and
  missed-opportunity `overallStatus=WARN`.

This is read-only RCA evidence only. It is not live-readiness evidence and not
permission to enable live trading.

- 2026-06-20T21:40+08:00 origin-delta classifier observed server worktree
  `ef6253a4ecff7c27a2e709f226e166389700a82d` while local `origin/main` was
  `20425dd94eb04edddb5f60fc5eba5facb3c8e456`. It printed
  `origin_delta_local_evidence=true`,
  `origin_delta_status=DOCS_TOOLING_ONLY_DRIFT`, `origin_delta_files=16`,
  `origin_docs_tooling_delta_files=16`, `origin_runtime_delta_files=0`,
  `origin_runtime_delta_paths=[]`, and `live_review_packet_allowed=false`.
- The packet preflight remained `packet_status=NO_EVIDENCE` because
  `origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN`,
  `deploy_required_before_live_review=true`, and
  `bundle_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE","DEPLOYED_RUNTIME_NOT_CURRENT"]`.
  Its next action now routes stale runtime through `smoke_live_origin_delta_local.ps1`
  before deciding whether runtime deploy is required.
- `audit_live_readiness_ssh` reported health `UP`, `runtime_log_status=PASS`,
  runtime ERROR count 0, WARN baseline total 15, `order_capable_flags_true=[]`,
  all reviewed dry-run flags true, `riskLevel=R0`, and
  `missing_readiness_detail_fields=[]`.
- Background automation remained the clearest env-review blocker:
  `backgroundAutomationClear=false`,
  `background_automation_true` contained all nine reviewed flags,
  `high_risk_background_automation_true` contained
  `TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED`,
  `EVENT_SCAN_NOTIFICATION_ENABLED`, `EXECUTION_EVENT_ENABLED`,
  `TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED`, and
  `TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED`, and
  `background_automation_blockers=["HIGH_RISK_BACKGROUND_AUTOMATION_TRUE","BACKGROUND_AUTOMATION_TRUE"]`.
- Runtime evidence remained `diagnosis=CONFIG_DISABLED` with
  `runtimeEvidenceStatus=NOT_READY_ENABLED_FALSE`, `shadowIntentCount=0`, and
  no scoped order-sent blocker.
- Tiny-live stayed blocked with `hardStopDetected=true`,
  `autoApprovalMode=BLOCKED`, `completedTinyLiveSamples=2`,
  `falsePositiveCount=2`, and `canEnableProduction=false`.
- Signal correctness found no missed-evaluation/order bug and current
  DataFreshnessGuard snapshot was clean, but `signalPolicyClear=false` because
  7d `governanceMode=TOO_STRICT` and missed-opportunity `overallStatus=WARN`.

This refresh is read-only RCA evidence only. It does not authorize production
env mutation, live trading, scheduler enablement, order/OCO/grid/fund/Earn,
Telegram send, exchange mutation, DB changes, external backfill/import, deploy,
restart, or policy relaxation.

Historical complete blocker snapshot from stale deployed runtime:

- 2026-06-20T13:34+08:00 deployment metadata observed server/deployed commit
  `224f550478b20a329775f503b3eaa70ba6a2f6a8` while `origin/main` had advanced to
  `873b219171755401c40f3a676fb3c7c9477471ec`.
- The default full bundle then failed fast on `DEPLOYED_RUNTIME_NOT_CURRENT`
  before child smokes and printed
  `bundle_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE","DEPLOYED_RUNTIME_NOT_CURRENT"]`
  with `bundle_verdict=NO_EVIDENCE`.
- A strict read-only runtime-log smoke on 2026-06-20T10:16+08:00 reached the
  stale deployed runtime and failed against
  `/home/ubuntu/agora-trading-api/logs/runs/app-20260618T070102Z-port8084.log`
  with `runtime ERROR lines present: count=2`, including
  `TelegramServiceImpl` and `ExecutionEventScheduler`.
- That stale runtime-log failure preserves `RUNTIME_HEALTH_OR_LOG_NOT_CLEAN`
  for historical RCA only and is no longer the current blocker after the
  2026-06-20T20:28+08:00 clean runtime-log bundle.

Those stale outputs do not clear any blocker and are not a substitute for
rerunning the full bundle after a separately authorized deploy; they are stale
live-review evidence only. Their
`originMainCommit` fields are observed origin values, not currentness claims
after later docs/guardrail commits.

## Audit Classifications

`audit_live_readiness_ssh.ps1` also prints `blocker_classification` and
`next_actions`. These labels are triage hints for the review packet only. They
are not live approval, do not clear `bundle_blockers`, and must not be used to
enable live trading, scheduler, order, OCO, grid, Earn, fund, Telegram,
exchange, external backfill/import, or DB mutation.

| Audit classification | Meaning | Required follow-up |
| --- | --- | --- |
| `market_condition_wait` | The read-only evidence does not show a current BUY/add candidate or confirmed signal gate. | Keep observing read-only signal/MCP evidence; do not relax gates only to create a live candidate. |
| `runtime_evidence_gap` | Runtime evidence is missing, disabled, or not ready while execution remains disabled. | Run the runtime-evidence RCA and collect dry-run/shadow evidence; do not enable execution flags. |
| `risk_hard_stop` | Tiny-live loss protection or another hard safety stop is active. | Run the tiny-live loss RCA and require fresh dry-run proof before any review packet. |
| `execution_disabled_guard` | Execution-capable gates report `*_NOT_EXECUTION_ELIGIBLE`. | Treat disabled execution as intentional protection; only change via a separate authorized env plan. |
| `background_automation_review` | Background automation flags are already true or need explicit review. | Run background automation smoke and reconcile the env diff before any live scope expansion. |
| `security_or_secret_gap` | Required server secret material or env prerequisites are missing. | Fix secret prerequisites through a separately authorized ops change and rerun read-only audit. |
| `runtime_health_gap` | Health, runtime log smoke, or event-risk baseline is not clean. | Fix the specific health, runtime log, and/or event-risk evidence named in the audit before any live operator review. |
| `capacity_not_primary` | Notional or capacity limits are visible in the read-only evidence. | Secondary sizing review only; handle after primary blockers are clear. |

`capacity_not_primary` is explicitly secondary. It must not be used to bypass primary blockers
such as `LIVE_READINESS_NOT_READY`, `RUNTIME_EVIDENCE_*`,
`TINY_LIVE_LOSS_HARD_STOP`, `SIGNAL_POLICY_REVIEW_GAPS`,
`BACKGROUND_AUTOMATION_REVIEW`, `DEPLOYED_RUNTIME_NOT_CURRENT`, or
`LIVE_READINESS_EVIDENCE_UNAVAILABLE`.

The audit's `missing_readiness_detail_fields` line is required evidence for
the MCP audit gate. Missing or non-empty readiness-detail field summaries stay
blocked because parsed `readiness_details` must prove the exact tiny-live,
autonomous-opportunity, and ScoreBuy sections used by the execution gate.

Runtime-log allow flags such as `ALLOW_RUNTIME_ERROR=1`,
`ALLOW_HIGH_RISK_LOG=1`, or `ALLOW_UNKNOWN_WARN=1` are diagnostic-only. They
must not be used as live-readiness evidence; live audit and split acceptance
force those values back to `0`.

## Non-Negotiable Guards

These remain forbidden unless the audit classifies them as accepted for the
exact active scope; any other true flag requires a separate live proposal that
explicitly authorizes a bounded scope:

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

- latest full-bundle output with `bundle_blockers=[]`,
  `live_review_packet_allowed=true`, and
  `deploy_required_before_live_review=false`, plus
  `bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`
- latest `.\scripts\prepare_live_review_packet_ssh.ps1 -RequireReady` output
  with `packet_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED` and
  `packet_missing_requirements=[]`, plus
  `packet_bundle_blocker_summary=[]`
- explicit confirmation that the packet does not rely on
  `bundle_verdict=NOT_READY`, `bundle_verdict=NO_EVIDENCE`,
  `LIVE_READINESS_EVIDENCE_UNAVAILABLE`, `live_review_packet_allowed=false`,
  or `DEPLOYED_RUNTIME_NOT_CURRENT`
- full outputs from every required read-only smoke listed above
- production env diff proposal
- latest `.\scripts\prepare_live_env_review_packet.ps1 -RequireReady` output
  with `env_review_packet_status=READY_FOR_OPERATOR_ENV_REVIEW_NOT_AUTHORIZED`,
  `forbidden_true_candidates=[]`, and `env_review_missing_requirements=[]`
- expected blast radius and rollback plan
- runtime evidence status and shadow-intent counts
- tiny-live loss hard-stop status
- signal correctness and governance drift summary
- confirmation that `orderSentEvidenceBlockerCount=0` during the reviewed target-strategy phase
- confirmation that `shadowIntentCount` is greater than 0 for the reviewed
  evidence-only window
- confirmation that `hardStopDetected=false` and `canEnableProduction=true`
  for the reviewed tiny-live rollout window, with
  `missing_tiny_live_fields=[]`
- confirmation that 7d governance drift is not `TOO_STRICT`, `TOO_LOOSE`, or
  `INSUFFICIENT_DATA`, and missed-opportunity `overallStatus=PASS`

Clearing this matrix is still not live approval. It only proves the evidence is
ready for a separate operator decision.
