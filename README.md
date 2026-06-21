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
- Public HTTP surface is intentionally narrow: OpenAPI docs, actuator probes, rate-limit JSON redirect, and API-key guarded internal report reads for the AgoraMarketAPI Telegram gateway. Trading MCP is internal-only through server-local `/api/mcp`; public dedicated-host `/api/mcp` and shared-host `/api/trading/mcp` must be blocked by nginx.
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
`https://agoratradingapi.purrtechllc.com/api` and verifies public Trading MCP
is blocked.

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

Focused strategy 485 open-position risk RCA:

```powershell
.\scripts\smoke_strategy485_position_risk_ssh.ps1
```

This read-only smoke reviews SCORE_BUY strategy 485 open positions, OCO health,
position-defense status, active-position EV, TP stretch, stop-sweep policy,
recent closed trades, execution events, and 3-month PnL through server-local
`/api/mcp`. It prints `strategy485_position_risk_recommendation` such as
`REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY`; this is review routing only and
does not authorize closing positions or modifying OCO.
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
`strategy485_position_review_gate_status`. It may route a separate operator
review packet, but it never authorizes close-position or OCO modification.

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
activation, closing positions, or OCO changes.

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

Read-only post-deploy profit validation:

```powershell
.\scripts\smoke_post_deploy_profit_validation_ssh.ps1
```

This aggregate gate runs the auto-trading review gate, profit loss review gate,
and profit experiment gate after a separately authorized deploy. It emits
`deploy_required_before_post_deploy_profit_validation`,
`origin_runtime_delta_paths`,
`post_deploy_profit_validation_status`,
`post_deploy_profit_validation_missing_requirements`,
`post_deploy_profit_validation_review_plan`,
`post_deploy_profit_validation_blocker_summary`,
`post_deploy_profit_validation_review_decision`,
`live_policy_change_allowed=false`, `position_or_oco_mutation_allowed=false`,
and `tiny_live_order_allowed=false`. It is a read-only readiness matrix for
profit review packets and does not deploy, restart, change production env, relax
policy, place orders, or modify OCO/grid/fund/Earn state.
The blocker summary is a machine-readable routing copy of the blocked child
gate evidence; each entry preserves `requiredEvidenceCount`, `requiredEvidence`,
`nextAction`, `runtimeDrift`, and no-live authorization text, and does not clear
blockers.
When `origin_delta_status=RUNTIME_DRIFT`, the aggregate output also carries
`server_worktree_commit`, `origin_main_commit`, `origin_runtime_delta_files`,
and `origin_runtime_delta_paths`, so the deploy-first blocker points at the
runtime files that must be refreshed before profit evidence is trusted.
The review decision is the top-level machine-readable routing object; it
includes `canPrepareReviewPacket`, `deployRequired`, `allowedReviewTypes`,
`blockerCount`, `missingRequirementCount`, and no-live authorization text.

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
forward-return proxy markers. It is evidence only and does not authorize
DataFreshnessGuard relaxation or any live mutation.
Use `docs/data-freshness-shadow-replay-input-plan.md` before proposing any
collector or shadow/replay change for this path.
Use `docs/data-freshness-shadow-replay-collector-design.md` before implementing
any collector: the current L0 block returns before candidate/EV/OCO snapshots,
so a future collector must be disabled by default, keep DataFreshnessGuard as
the terminal live decision, create only replay evidence with a stable
`replayCandidateId`, and must not create live signals, send Telegram, place
orders, modify OCO, mutate positions, or change scheduler/live policy.
New DataFreshness L0 audit rows carry a deterministic `replayCandidateId`
(`dfsr1_...`) plus explicit no-order/no-intent/no-OCO markers; this improves
future replay traceability but is still not executable evidence without
entry/TP/SL/EV/OCO snapshots.
After deploying that runtime, verify fresh production rows with:

```powershell
.\scripts\smoke_data_freshness_replay_candidate_id_ssh.ps1
```

Use `-RequireObserved` only after a new DataFreshnessGuard row is expected; a
pending result means no fresh row was available yet, not live approval.
The smoke also compares deployed `app.commit` with the expected local HEAD and
prints `DEPLOYED_RUNTIME_NOT_CURRENT` until the replay-id runtime has actually
been deployed.
For the full post-deploy replay observation chain, run:

```powershell
.\scripts\smoke_data_freshness_replay_observation_bundle_ssh.ps1
```

This wrapper combines origin-delta, replay-id, and counterfactual evidence and
fails closed to deploy-first routing while runtime is stale.

Read-only profit-improvement review bundle:

```powershell
.\scripts\smoke_profit_improvement_review_bundle_ssh.ps1
```

This wrapper runs the origin-delta classifier, profit-candidate review,
DataFreshness false-kill review, DataFreshness executability review, strategy
485 position-risk review, strategy 574 signal/governance review, and TinyLive
post-trade evidence. It prints `profit_improvement_review_items` and
`profit_improvement_candidate_scorecard`, `top_profit_improvement_candidate`,
and `profit_improvement_bundle_recommendation`, so DataFreshness alpha
pressure, strategy 485 open-position risk, strategy 574 near-BUY context, and
TinyLive sample gaps are reviewed together without authorizing any live change.
The scorecard ranks read-only candidates and required evidence only; it is not
permission to relax policy, close or modify positions, or place orders.

Read-only profit experiment gate:

```powershell
.\scripts\prepare_profit_experiment_gate_ssh.ps1
```

This gate runs the profit-improvement review bundle and converts the candidate
scorecard into `deploy_required_before_profit_experiment`,
`shadow_experiment_review_allowed`, `live_policy_change_allowed=false`, and
`profit_experiment_gate_status`. It is a routing check for shadow/small
experiment review only; it does not authorize deploy, live policy changes,
position/OCO changes, or order-capable actions.

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
