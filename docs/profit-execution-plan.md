# Profit Execution Plan

Last updated: 2026-06-30

## Purpose

This plan defines how Codex should push `agora-trading-api` toward real
profitability. The target is not to claim guaranteed profit. The target is to
turn the system into a controlled trading runtime with repeatable evidence,
positive expectancy, bounded downside, and an auditable path from replay to
dry-run to small live exposure.

The operator has granted Codex full execution authority for this project goal:
Codex may inspect evidence, edit code, add tests, commit, push, deploy, restart,
change reviewed production env flags, run server verification, and execute
approved trading-control MCP actions when the relevant gate proves the action is
ready. This authority is operational permission, not permission to bypass
preflight evidence, risk caps, or rollback requirements.

## Profit Objective

The objective is to make the trading system net profitable after fees and risk
controls.

Primary success criteria:

- Positive net realized PnL over a reviewed live observation window.
- Positive replay or shadow expected value before any live relaxation.
- Max loss per experiment capped before execution.
- Every profitable or missed opportunity replayable by symbol, strategy,
  entry time, entry plan, block reason, TP/SL/OCO plan, forward return, and
  final outcome.
- No increase in live exposure unless the previous phase produced acceptable
  evidence.

Secondary success criteria:

- Fewer false-kill blocks on profitable BTCUSDT candidates.
- Fewer unreadable Telegram alerts; operator messages must be short and
  action-oriented.
- Higher quality grid activation decisions, including trend context before
  opening or resizing grids.
- Cleaner exit-side management through trailing-stop dry-run evidence before
  live OCO mutation.

## Current State

The current profit path has advanced from "waiting for strategy 574 opt-in" to
active trailing-stop dry-run observation with background automation cleared.

Known current facts:

- Strategy 574 trailing-stop opt-in has been applied.
- `trailingStopEnabled=true` is confirmed for strategy 574.
- OCO writes were not performed by the opt-in action.
- A0 env diff was applied on 2026-06-30:
  - `TRAILING_STOP_ENABLED=true`
  - `TRAILING_STOP_DRY_RUN=true`
- Deploy/restart completed on current `origin/main` commit `8fcf3c0`.
- Post-deploy split acceptance passed on active port `8085`.
- A2 background automation safety diff was applied on 2026-06-30 and verified:
  all nine reviewed background automation flags are `false`,
  `backgroundAutomationClear=true`, and
  `background_automation_blockers=[]`.
- `getTrailingStopStatus` confirms `global.enabled=true`,
  `global.dryRun=true`, and `open_oco_positions=0`.
- 30d BTCUSDT trailing replay remains `acceptance=PASS`, with
  latest observation refresh `improvementPct=56.299%` and
  `acceptanceDeltaPnl=13391.79229093`.
- Runtime evidence is enabled and canonical rows are available, but
  `shadowIntentCount=0`, so live review remains blocked by missing shadow
  intent evidence.
- The latest full live-readiness bundle remains `NOT_READY` with blockers:
  `LIVE_READINESS_NOT_READY`, `ORDER_CAPABLE_FLAGS_REVIEW`,
  `EXECUTION_ELIGIBILITY_NOT_READY`, `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`,
  `TINY_LIVE_ROLLOUT_NOT_READY`, and `SIGNAL_POLICY_REVIEW_GAPS`.
- Latest micro-grid blocker board readiness is `91.67%` (`11/12` gates), but
  `createGrid` remains blocked by trend-regime override or fresh sideways
  clearance first, then capital cap override (`candidateCapitalUsdt=10` vs
  `effectiveReviewCapitalCapUsdt=5`). Grid mutation remains disallowed.
- Current next blocker is `NO_OPEN_OCO_POSITIONS`: the trailing dry-run lane is
  active and read-only, but there is no open OCO position to observe yet.
- Current observation status command:
  `.\scripts\prepare_trailing_stop_dry_run_observation_status_ssh.ps1 -ExpectedOptInStrategyId 574 -RequireReady`.
- With `open_oco_positions=0`, the expected status is
  `ACTIVE_WAITING_FOR_OPEN_OCO_SAMPLE` and the immediate blocker is
  `NO_OPEN_OCO_POSITIONS`.
- The next profitable path is evidence-first trailing-stop dry-run observation,
  not immediate live OCO mutation.

## Authority Model

Codex is authorized to execute the work required to reach the profitability
goal, including:

- local code and documentation changes;
- focused tests and full local verification;
- git commit and push;
- production deploy and restart after reviewed env diffs;
- read-only production verification through SSH and MCP;
- reviewed MCP write actions when the action packet explicitly proves readiness;
- bounded live trading-control changes only after the matching gate, cap, and
  rollback plan are present.

Codex must still fail closed when evidence is missing. Full authority means
Codex should not stop at suggestions, but it does not mean blind live risk.

## Aggressive Activation Review

Use the aggressive packet when the operator wants a faster route toward a small
profit attempt while preserving auditability:

```powershell
.\scripts\prepare_profit_aggressive_activation_operator_packet.ps1 -RequireReady
```

The packet emits `PROFIT_AGGRESSIVE_ACTIVATION_OPERATOR_PACKET` with three
separate lanes: `HIGH_RISK_MICRO_LIVE_PROBE`,
`GRID10_EXISTING_ACTIVE_GRID_ORDER_PATH`, and `EVIDENCE_ONLY_ACCELERATOR`.
Each lane includes machine-readable `proposedEnvDiff`,
`riskAcceptanceConditions`, `postEnvReadOnlyVerificationCommands`,
`killSwitchEnvDiff`, and `rollbackCommands`. The packet is not live approval:
it keeps `order_allowed=false`, `deploy_or_env_change_allowed=false`, and
`live_policy_change_allowed=false`. A later env/deploy/live action still
requires separate exact operator authorization matching the selected lane.

For the high-risk lane that is closest to a real-money probe, package the
review handoff with:

```powershell
.\scripts\prepare_profit_high_risk_micro_live_probe_handoff.ps1 -RequireReady
```

This emits `PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_HANDOFF_PACKET`,
`micro_probe_exact_authorization_text`,
`micro_probe_required_env_diff`, `micro_probe_hard_gate_checklist`,
`micro_probe_post_env_read_only_verification`,
`micro_probe_kill_switch_env_diff`, and `micro_probe_rollback_commands`. It
preserves maxOrders=1 and maxNotionalUsdt=10, but it is still not execution
approval: `micro_probe_env_deploy_request_allowed=false`,
`deploy_allowed=false`, `order_allowed=false`, and
`live_policy_change_allowed=false` until current BUY/scout, OCO/EV,
event-risk, runtime evidence, kill-switch, and exact authorization evidence are
refreshed in the same session.

After collecting those hard-gate logs, aggregate them with:

```powershell
.\scripts\prepare_profit_high_risk_micro_live_probe_preflight_review_packet.ps1 -RequireReady
```

This emits `PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_PREFLIGHT_REVIEW_PACKET`,
`micro_probe_hard_gate_clear`, `micro_probe_exact_authorization_review_allowed`,
and `runtime_order_sent_evidence`. The only ready status is
`READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_EXACT_AUTHORIZATION_REVIEW_NOT_MUTATION`,
which means the exact authorization text can be reviewed. It still keeps
`micro_probe_env_deploy_request_allowed=false`, `deploy_allowed=false`,
`order_allowed=false`, and `live_policy_change_allowed=false`; it does not
authorize deployment or orders.

Once the handoff and preflight logs are saved and ready, package the final
exact activation review prompt with:

```powershell
.\scripts\prepare_profit_high_risk_micro_live_probe_activation_authorization_bundle.ps1 -RequireReady
```

This emits `PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_BUNDLE`,
`micro_probe_activation_authorization_review_ready`,
`micro_probe_activation_authorization_text`, the selected env diff, post-env
read-only verification, kill-switch env diff, and rollback commands. The ready
status is
`READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION`.
It remains a prompt packet only: `micro_probe_activation_execution_allowed=false`,
`micro_probe_env_deploy_request_allowed=false`, `deploy_allowed=false`,
`order_allowed=false`, and `live_policy_change_allowed=false`.

For the current recommended non-order lane, generate the exact evidence-only
handoff with:

```powershell
.\scripts\prepare_profit_evidence_only_accelerator_env_deploy_handoff.ps1 -RequireReady
```

This emits `PROFIT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_HANDOFF_PACKET` with
the exact operator authorization text, proposed env diff
`TRADING_RUNTIME_EVIDENCE_ENABLED=true` plus
`TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true`, required disabled
flags, post-env read-only verification, kill-switch env diff, and rollback
commands. It remains non-authorization and keeps
`production_env_change_allowed=false`, `deploy_allowed=false`, and
`order_allowed=false`.

After the operator separately authorizes and applies only that evidence-only
env diff, the post-env evidence review must use:

```powershell
.\scripts\prepare_profit_evidence_only_accelerator_post_env_read_only_bundle_ssh.ps1 -RequireReady
```

This emits `PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_READ_ONLY_BUNDLE`,
`profit_evidence_only_post_env_bundle_status`,
`runtime_shadow_intent_count`, and `runtime_order_sent_evidence`. The only
ready status is
`READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_REVIEW_NOT_LIVE`, which
means the evidence-only path can continue to operator review. It still keeps
`live_policy_change_allowed=false`, `order_allowed=false`, and
`deploy_allowed=false`; it does not authorize live relaxation or execution.

## Current Target Authorization

The current executable target is trailing-stop dry-run activation only. The
purpose is to observe exit-side decisions in production runtime without placing
orders or mutating OCO state.

The exact operator authorization required before Codex may change production
env or deploy this phase is:

```text
I authorize production env diff TRAILING_STOP_ENABLED=true and TRAILING_STOP_DRY_RUN=true, deploy/restart current origin/main, and post-env read-only verification only. I do not authorize TRAILING_STOP_DRY_RUN=false, live OCO mutation, order placement, position close, scheduler/live policy relaxation, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import.
```

Authorized env diff:

- `TRAILING_STOP_ENABLED=true`
- `TRAILING_STOP_DRY_RUN=true`

Flags and actions that must remain disabled or not performed:

- `TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false`
- `TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false`
- `TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false`
- `TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false`
- `POSITION_EXIT_MANAGER_ENABLED=false`
- `TRADING_OCO_POLLER_ENABLED=false`
- `MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false`
- `EVENT_SCAN_NOTIFICATION_ENABLED=false`
- `EXECUTION_EVENT_ENABLED=false`
- no order placement;
- no position close;
- no live OCO mutation;
- no scheduler or live policy relaxation;
- no Telegram send;
- no DB, grid, fund, Earn, exchange, backfill, or import mutation.

Execution command after exact authorization:

```powershell
.\scripts\deploy_ssh.ps1 -Branch main
```

Post-deploy verification is read-only only:

```powershell
.\scripts\verify_split_acceptance_ssh.ps1
.\scripts\prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1 -ExpectedOptInStrategyId 574 -RequireReady
.\scripts\smoke_trailing_stop_pnl_replay_ssh.ps1 -Symbol BTCUSDT -IntervalCode 1h -ReplayIntervalCode 1m -Days 30 -Limit 500 -RequireAcceptance
.\scripts\audit_live_readiness_ssh.ps1 -Symbol BTCUSDT
.\scripts\prepare_profit_next_execution_blocker_packet.ps1 -RequireReady
```

Rollback condition: if runtime logs show any order, OCO write, grid, fund, Earn,
Telegram, scheduler enablement, or unexpected mutation outside this scope,
restore the previous trailing-stop env state and rerun the same read-only
verification.

## Standing Authorization Runway

This section records the operator's standing authorization so Codex does not
stop only to ask for already listed approval text. Codex may proceed under one
of these lanes without another chat interruption only when the named preflight
or operator packet is fresh, returns the required ready status, git/runtime
currentness is proven, the exact diff or action matches this section or the
packet output, and the rollback/verification commands are ready.

If any lane reports stale evidence, missing fields, non-empty blockers, dirty
worktree, runtime drift, abnormal OCO health, unclear order path, or a cap above
the listed limit, Codex must keep working on evidence, code, tests, reports, or
docs, but must not perform that production or trading mutation.

Global execution caps for all future profit lanes unless a later document raises
them with evidence:

- maximum new live experiment notional: `10 USDT`;
- maximum new live orders per day: `1`;
- maximum grid candidate capital: `10 USDT`;
- no live scaling until realized net PnL is positive after fees and slippage;
- no policy relaxation without replayable counterfactual rows and bounded
  downside evidence.

Always authorized without another prompt:

- read-only SSH/MCP/database inspection through existing safe scripts;
- local code, test, script, and documentation edits inside this repo;
- focused tests, `.\scripts\verify_local.ps1`, and `git diff --check`;
- git commit and push for completed repo changes;
- read-only issue/comment evidence updates;
- deploy only when the lane below explicitly authorizes deploy/restart and the
  preflight packet is ready.

### A0: Trailing Dry-Run Env Deploy

Status: complete; keep dry-run active and collect observation evidence.

Gate:

```powershell
.\scripts\prepare_trailing_stop_dry_run_env_deploy_handoff_ssh.ps1 -RequireReady
```

Standing authorization when the gate is ready:

```text
I authorize production env diff TRAILING_STOP_ENABLED=true and TRAILING_STOP_DRY_RUN=true, deploy/restart current origin/main, and post-env read-only verification only. I do not authorize TRAILING_STOP_DRY_RUN=false, live OCO mutation, order placement, position close, scheduler/live policy relaxation, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import.
```

### A1: Evidence-Only Runtime Evidence Collection

Purpose: clear runtime-evidence blind spots before any live relaxation.

Status: runtime evidence is enabled and canonical rows exist, but this lane is
not complete until `shadowIntentCount > 0` and `orderSentEvidence=0` are
observed in a reviewed read-only window.

Gate:

```powershell
.\scripts\smoke_runtime_evidence_rca_ssh.ps1
.\scripts\smoke_live_background_automation_ssh.ps1 -RequireClear
.\scripts\smoke_live_readiness_bundle_ssh.ps1
.\scripts\prepare_live_review_packet_ssh.ps1 -RequireReady
```

Standing authorization when the reviewed proposal still matches
`docs/live-runtime-evidence-env-proposal.md`:

```text
I authorize evidence-only production env diff TRADING_RUNTIME_EVIDENCE_ENABLED=true, deploy/restart current origin/main, and post-env read-only verification only. I do not authorize TRADING_OKX_ENABLED=true, TinyLive/ScoreBuy execution, OCO/grid/fund/Earn actions, Telegram send, guardian live actions, external backfill/import, DB migration, policy relaxation, or order placement.
```

Required env diff:

- `TRADING_RUNTIME_EVIDENCE_ENABLED=true`

Must remain disabled:

- `TRADING_OKX_ENABLED=false`
- `TRADING_OCO_POLLER_ENABLED=false`
- `TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false`
- `TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false`
- `TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false`
- `TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false`
- `TRAILING_STOP_ENABLED=false` unless A0 has already been separately applied
  in dry-run mode;
- `POSITION_EXIT_MANAGER_ENABLED=false`
- `TRADING_GRID_ENABLED=false`
- `MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false`
- `TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=false`
- `EVENT_SCAN_NOTIFICATION_ENABLED=false`
- `EXECUTION_EVENT_ENABLED=false`

### A2: Background Automation Safety Diff

Purpose: remove high-risk background side effects before live review.

Status: complete as of 2026-06-30 on commit `8fcf3c0` / active port `8085`.
Post-env read-only verification returned `backgroundAutomationClear=true`,
`background_automation_true=[]`, and `background_automation_blockers=[]`.

Gate:

```powershell
.\scripts\smoke_live_background_automation_ssh.ps1
.\scripts\audit_live_readiness_ssh.ps1
.\scripts\smoke_live_readiness_bundle_ssh.ps1
```

Standing authorization when the reviewed proposal still matches
`docs/live-background-automation-env-diff-proposal.md`:

```text
I authorize production env diff to set reviewed background automation flags false, deploy/restart current origin/main, and post-env read-only verification only. I do not authorize runtime-evidence enablement, exchange credentials, order-capable flags, OCO/grid/fund/Earn flags, Telegram-send enablement, scheduler enablement, DB migration, Flyway baseline regeneration, extra-table cleanup, or table drops.
```

Required env diff:

- `TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED=false`
- `TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=false`
- `MARKET_WS_AUTO_SUBSCRIBE_ENABLED=false`
- `EVENT_SCAN_NOTIFICATION_ENABLED=false`
- `EXECUTION_EVENT_ENABLED=false`
- `TRADING_DAILY_TG_REPORT_ENABLED=false`
- `TRADING_AUTONOMOUS_DIGEST_ENABLED=false`
- `TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED=false`
- `TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED=false`

### A3: DataFreshness Evidence-Only Collector

Purpose: collect replayable false-kill rows for issue #7 without relaxing
DataFreshnessGuard or trading live.

Gate:

```powershell
.\scripts\prepare_data_freshness_replay_collector_activation_packet.ps1 -RequireDecisionReady
.\scripts\prepare_data_freshness_collector_activation_preflight_review_packet.ps1 -RequireReady
```

Standing authorization when the preflight is ready:

```text
I authorize evidence-only production env diff TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true, deploy/restart current origin/main, and post-activation read-only verification only. I do not authorize DataFreshnessGuard relaxation, live trading, staged-add, TinyLive, scheduler mutation, order placement, OCO modification, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import.
```

Required post-activation verification:

```powershell
.\scripts\verify_split_acceptance_ssh.ps1
.\scripts\smoke_data_freshness_replay_candidate_id_ssh.ps1
.\scripts\smoke_data_freshness_replay_observation_bundle_ssh.ps1
.\scripts\prepare_data_freshness_replay_evidence_readiness_ssh.ps1
.\scripts\prepare_filter_block_false_kill_issue7_close_readiness.ps1
```

### A4: Grid Env and Micro-Grid Open Review

Purpose: enable only a 10 USDT BTCUSDT grid review lane after trend, event risk,
capital, and currentness gates are ready.

Gate:

```powershell
.\scripts\prepare_grid_open_complete_operator_packet_ssh.ps1 -GridCount 2 -PerLevelUsdt 5 -StopOutPct 5 -CandidateHalfWidthPct 10 -RequireCompletePacketReady
```

Standing authorization when the complete operator packet is ready:

```text
I authorize the grid-open authorization sequence printed by the fresh GRID_OPEN_COMPLETE_OPERATOR_PACKET for BTCUSDT only, with maximum candidateCapitalUsdt=10, GridCount=2, PerLevelUsdt=5, StopOutPct=5, and CandidateHalfWidthPct=10. I authorize the exact packet-listed trend/capital/env/deploy/post-env/createGrid sequence only when each prior step verifies cleanly. I understand TRADING_OKX_ENABLED=true may activate the existing ACTIVE grid order path. I do not authorize scheduler/recovery/Earn enablement, OCO mutation, Telegram send, extra grid capital, DB/fund/exchange mutation outside reviewed grid order path, or any createGrid input drift.
```

Required env diff, only if printed by the ready packet:

- `TRADING_OKX_ENABLED=true`
- `TRADING_GRID_ENABLED=true`

Must remain disabled:

- `TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false`
- `GRID_RECOVERY_ENABLED=false`
- `OKX_EARN_TOPUP_ENABLED=false`
- `EVENT_SCAN_NOTIFICATION_ENABLED=false`
- `EXECUTION_EVENT_ENABLED=false`

### A5: TinyLive or ScoreBuy Small Live

Purpose: test one small execution lane only after full live-readiness evidence
is clean.

Gate:

```powershell
.\scripts\prepare_live_review_packet_ssh.ps1 -RequireReady
.\scripts\prepare_strategy574_tiny_live_governance_operator_packet.ps1 -RequireReady
.\scripts\audit_live_readiness_ssh.ps1 -Symbol BTCUSDT
```

Standing authorization when the live packet and governance packet are both
ready and report no hard-stop or policy blockers:

```text
I authorize one BTCUSDT small-live execution lane with max new notional 10 USDT and max 1 new live order per day, using only the exact env diff and execution route printed by the fresh ready packets. I do not authorize multiple lanes at once, cap increase, DataFreshness/EntryDedup/live policy relaxation, OCO mutation beyond the packet-listed protective attachment, grid/fund/Earn actions, Telegram send unless the three-line alert format is verified, or any order when OCO health is abnormal.
```

If the packet does not print an exact env diff and route, this lane is not
executable; continue evidence work instead.

### A6: Exit-Side Live OCO or Position Risk Reduction

Purpose: reduce loss on already reviewed positions or promote trailing from
dry-run to live only after dry-run benefit is proven.

Gate:

```powershell
.\scripts\prepare_tp_sl_oco_feasibility_operator_packet.ps1 -RequireReady
.\scripts\prepare_oco_sync_reconciliation_packet_ssh.ps1 -RequireReviewReady
.\scripts\audit_live_readiness_ssh.ps1 -Symbol BTCUSDT
```

Standing authorization when the packet identifies exact position ids, OCO
routes, expected writes, and rollback verification:

```text
I authorize only the exact BTCUSDT exit-side risk-reduction write printed by the fresh ready packet, limited to the listed position ids and OCO routes, with post-change read-only verification. I do not authorize opening new positions, unrelated OCO changes, EntryDedup/DataFreshness/live policy relaxation, grid/fund/Earn actions, Telegram send, scheduler enablement, or DB/exchange mutation outside the exact packet-listed risk-reduction write.
```

If OCO health is abnormal and the packet is not a reconciliation packet for the
same abnormality, this lane remains blocked.

### A7: Telegram Notification Formatting

Purpose: make operator messages readable without enabling new send paths.

Standing authorization:

```text
I authorize code, template, and deploy changes that reduce existing trading Telegram/operator notifications to at most three lines, followed by read-only verification. I do not authorize enabling a new Telegram send path, changing recipients, enabling event/execution scan notifications, or sending test/live Telegram messages unless the specific notification lane is separately approved and already verified as three-line formatted.
```

Acceptance:

- normal alert line 1: symbol, decision, action required;
- normal alert line 2: primary blocker or reason;
- normal alert line 3: next action or MCP/report reference;
- raw arrays and long diagnostics move to MCP/report output.

## Execution Rules

1. Evidence comes before exposure.
2. Replay comes before dry-run.
3. Dry-run comes before live mutation.
4. Small live cap comes before scaling.
5. Profitability is measured after fees, slippage, failed orders, and stops.
6. A guard can be relaxed only when the false-kill evidence is replayable and
   the downside scenario is bounded.
7. A grid can be opened or resized only when trend context, capital cap,
   exchange env, existing active grid risk, and post-env verification are all
   reviewed.
8. Telegram messages must tell the operator what changed, whether action is
   required, and the one next action. Long raw diagnostic dumps should be moved
   to MCP/report output.

## Priority Lanes

### P0: Exit-Side Trailing Stop

Goal: reduce realized losses and preserve profitable moves.

Current next step:

```powershell
.\scripts\prepare_profit_next_execution_blocker_packet.ps1 -RequireReady
```

If the packet reports
`BLOCKED_AWAIT_SEPARATE_TRAILING_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION`,
the next executable phase is:

- apply reviewed env diff:
  - `TRAILING_STOP_ENABLED=true`
  - `TRAILING_STOP_DRY_RUN=true`
- deploy and restart;
- run read-only post-env verification;
- collect dry-run trailing observations;
- compare dry-run exit decisions against actual forward outcome;
- promote to live OCO mutation only after dry-run evidence proves benefit and
  no abnormal OCO health is present.

If the packet reports
`TRAILING_DRY_RUN_ACTIVE_READ_ONLY_OBSERVATION`, A0 is complete and the only
next step is dry-run observation evidence. The packet now consumes the
observation-status packet when available, so the expected current blocker is
`NO_OPEN_OCO_POSITIONS`, not a strategy-relaxation or deploy blocker:

- keep `TRAILING_STOP_DRY_RUN=true`;
- run
  `.\scripts\prepare_trailing_stop_dry_run_observation_status_ssh.ps1 -ExpectedOptInStrategyId 574 -RequireReady`;
- wait for a real open OCO position before interpreting dry-run trailing
  behavior;
- verify runtime logs show no order/OCO modification/close-position/Telegram/
  grid/fund/Earn/exchange mutation;
- collect dry-run trailing observation rows before any live OCO promotion.

Promotion evidence required:

- trailing replay acceptance remains `PASS`;
- dry-run observation rows exist;
- no unexpected OCO mutation in dry-run;
- runtime log has no high-risk operation lines outside authorized scope;
- estimated saved loss or captured profit remains positive after fees.

### P1: Grid Trading Profit Path

Goal: use small controlled grid exposure only when trend and range context are
compatible.

Current direction:

- minimum practical review capital is 10 USDT;
- trend context must be checked before grid activation;
- existing active Grid #10 order-path activation risk must be explicit;
- `TRADING_OKX_ENABLED=true` and grid env state require deploy/restart and
  post-env read-only verification before live grid actions.

Grid must not scale until:

- post-open smoke passes;
- grid remains in range;
- failed or partial levels are zero or reconciled;
- scheduler/recovery/Earn remain disabled unless separately reviewed;
- realized grid PnL beats fees and spread cost.

### P2: False-Kill and Missed-Buy Reduction

Goal: stop blocking profitable candidates for the wrong reason.

Focus areas:

- DataFreshnessGuard false-kill replay rows;
- EntryDedup overblocking;
- strategy 574 near-threshold false positives;
- panic-bottom missed rebound contexts;
- no-buy attention flow that never becomes a terminal candidate.

Relaxation is allowed only after a packet can prove:

- candidate entry plan;
- block reason;
- forward return;
- TP/SL/OCO feasibility;
- whether the candidate should have passed;
- expected loss if the relaxation is wrong.

### P3: Notification Simplification

Goal: alerts should help the operator act, not dump raw internals.

Telegram rule:

- maximum three lines for normal alerts;
- line 1: symbol, decision, action required;
- line 2: primary reason or blocker;
- line 3: next action or MCP/report link;
- raw arrays, scheduler internals, and cap ledgers go to MCP/report only.

This improves operator decision speed without changing live policy.

## Phase Plan

### Phase 1: Commit Current Readiness Fixes

Deliverables:

- finish local verification;
- commit and push the current trailing-stop readiness/parser fixes;
- keep docs aligned with the new blocker state.

Acceptance:

```powershell
git diff --check
.\scripts\verify_local.ps1
```

### Phase 2: Activate Trailing Dry-Run

Deliverables:

- produce the exact env/deploy handoff packet;
- apply only the reviewed dry-run env diff;
- deploy/restart;
- verify health, split acceptance, MCP registry, runtime logs, and blocker
  packet;
- confirm no OCO writes occur while dry-run is true.

Acceptance:

```powershell
.\scripts\prepare_trailing_stop_dry_run_env_deploy_handoff_ssh.ps1 -RequireReady
.\scripts\verify_split_acceptance_ssh.ps1
.\scripts\prepare_profit_next_execution_blocker_packet.ps1 -RequireReady
```

### Phase 3: Collect Dry-Run Outcome Evidence

Deliverables:

- gather trailing dry-run rows;
- compare suggested OCO modifications to actual market outcome;
- compute saved loss, captured upside, false exits, and missed exits.

Acceptance:

- enough observations for a meaningful decision;
- net effect positive after fees and slippage assumptions;
- no OCO health abnormality.

### Phase 4: Small Live Promotion

Deliverables:

- promote only the narrowest proven lane;
- keep exposure cap small;
- enable one live mechanism at a time;
- collect realized PnL and incident evidence.

Acceptance:

- positive realized net PnL;
- bounded drawdown;
- no duplicate scheduler/order path;
- rollback command documented and tested.

### Phase 5: Scale Only What Proves Itself

Deliverables:

- increase capital only for lanes with positive live evidence;
- keep false-kill reduction and grid trend optimization under separate caps;
- keep notification output short.

Acceptance:

- positive multi-window net PnL;
- stable runtime logs;
- no unreviewed live mutation path.

## Stop Conditions

Stop or roll back if any of these occur:

- runtime log shows unexpected order, OCO, grid, Earn, fund, or Telegram
  operation outside the authorized phase;
- OCO health is abnormal;
- dry-run or live result shows negative expectancy after fees;
- market-data freshness is stale for the traded interval;
- scheduler ownership is ambiguous;
- DB/schema verification fails;
- public MCP exposure violates the current access policy.

## Next Command Board

Current immediate command board:

```powershell
git status --short --branch
git diff --check
.\scripts\verify_local.ps1
.\scripts\prepare_profit_live_blocker_source_refresh.ps1 -ReuseLatestProfitOperatorMatrix -ContinueOnStepFailure -AllowBlockedStepFailures
.\scripts\prepare_data_freshness_replay_evidence_readiness_ssh.ps1 -Symbol BTCUSDT
.\scripts\smoke_buy_like_candidate_progression_ssh.ps1 -ReviewDays 14 -FollowupHours 6 -Limit 20 -MaxCandidateRows 1000
.\scripts\smoke_buy_like_candidate_progression_ssh.ps1 -ReviewDays 30 -FollowupHours 6 -Limit 20 -MaxCandidateRows 2000
.\scripts\smoke_signal_correctness_ssh.ps1
.\scripts\smoke_no_terminal_followup_continuity_ssh.ps1 -Symbol BTCUSDT -ReviewDays 30 -FollowupHours 6 -ExtendedFollowupHours 72 -Limit 20
.\scripts\prepare_buy_like_candidate_loss_review_packet.ps1 -BuyLike14dLogPath target/profit-review/buy-like-candidate-progression-14d-latest.log -BuyLike30dLogPath target/profit-review/buy-like-candidate-progression-30d-latest.log -DataFreshnessReadinessLogPath target/profit-review/data-freshness-replay-evidence-readiness-refresh.log -SignalCorrectnessLogPath target/profit-review/signal-correctness-maintenance-latest.log -NoTerminalContinuityLogPath target/profit-review/no-terminal-followup-continuity-current.log -MaxAgeMinutes 60 -RequireReady
.\scripts\prepare_buy_like_continuity_matcher_review_packet.ps1 -BuyLikeLossReviewLogPath target/profit-review/buy-like-candidate-loss-review-packet-latest.log -NoTerminalContinuityLogPath target/profit-review/no-terminal-followup-continuity-current.log -MaxAgeMinutes 60 -RequireReady
.\scripts\prepare_profit_next_execution_blocker_packet.ps1 -RequireReady
```

The latest source refresh can now reuse a fresh profit operator matrix and still
finish successfully when the final audit is live-blocked. The current refreshed
audit has `profit_live_readiness_conclusion=NOT_READY_FOR_LIVE_ENABLEMENT`,
10 lanes, 8 review-ready non-live lanes (`profit-priority`,
`trailing-stop-dry-run`, `strategy485-risk-reduction`,
`entry-dedup-semantics`, `data-freshness-replay-blocker`,
`data-freshness-collector-activation`, `tp-sl-oco-feasibility`, and
`strategy574-tiny-live-governance`), 1 no-action lane
(`strategy485-risk-escalation` with `NO_POSITION_RISK_ACTION`), and 1 blocked
lane (`governance-relaxation`). This is valid evidence, not live approval.
The exit-side review summary now emits a replayable `NOT_READY` summary packet
before enforcing `-RequireReady`, so downstream trailing/strategy485 packets no
longer lose `source_matrix_freshness_status=FRESH` when the latest matrix is
fresh but has `NO_REVIEW_READY_ITEMS`.
The profit operator review chain also preserves parseable blocked child packets:
nonzero child `-RequireReady` exits are classified as `completed` failures only
when the expected JSON packet is missing. `profit_verified_recommendations` now
requires the source exit-side packet to be
`READY_FOR_EXIT_SIDE_EXPERIMENT_REVIEW_NOT_LIVE` and the individual proposal
status to be ready before emitting a ready recommendation. A blocked matrix can
therefore stay replayable as `NOT_READY` without being upgraded into an
operator-ready packet.
For the exit-side packet, Strategy485 `NO_POSITION_RISK_ACTION` and `WATCH_ONLY`
are no-action evidence, not missing-packet blockers. A trailing-stop review can
therefore be ready while Strategy485 has no negative-EV position to close or
modify. The source refresh also re-creates each step log parent directory before
writing output so long-running read-only refreshes cannot lose a child packet
when an upstream step refreshes `target/profit-review`.
The TP/SL/OCO feasibility packet follows the same split: trailing PASS plus
healthy Strategy485 OCO evidence can make the packet review-ready, while an
empty Strategy485 risk set is recorded as `NO_POSITION_RISK_ACTION` instead of
blocking the packet.
The refresh now sources the EntryDedup lane from the fresh production
`prepare_entry_dedup_operator_decision_brief_ssh.ps1` packet using the reviewed
720h / 24h forward / 50-row window; local static EntryDedup shadow rows are not
allowed to make the live blocker audit look more ready than current production
evidence. The latest fresh EntryDedup packet reports
`READY_FOR_ENTRY_DEDUP_OPERATOR_DECISION_NOT_LIVE`, `entry_dedup_skip_rows=11`,
`positive_24h_rows=10`, `tp_hit_rows=11`, `sl_hit_rows=0`, and
`avg_net_return_pct=0.8`, while keeping `entry_dedup_policy_change_allowed=false`,
`live_policy_change_allowed=false`, and `order_allowed=false`.

The latest DataFreshness replay evidence status is no longer a runtime deploy
blocker: `deployment_runtime_current_for_replay_id=true`, zero recent replay
candidate rows, and
`data_freshness_replay_evidence_readiness_status=PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS`.
When the replay input stage is `NO_DATAFRESHNESS_SAMPLE` with 1d/3d
DataFreshness rows still at zero, the collector activation lane is reviewable as
an evidence-only collector activation question. It remains non-authorization:
`collector_activation_allowed=false`, `deploy_or_env_change_allowed=false`,
`live_policy_change_allowed=false`, `scheduler_enablement_allowed=false`, and
`order_allowed=false`.
The highest-ROI current loss review is BUY-like candidate progression:
`buy_like_candidate_loss_review_status=READY_FOR_BUY_LIKE_CANDIDATE_LOSS_OPERATOR_REVIEW_NOT_LIVE`,
dominant blocker `ENTRY_SKIP:EntryDedup`, 30d BUY-like rows `703`, 30d
EntryDedup rows `418`, 30d no-terminal rows `110`, and live/order/policy
permissions all remain false. The fresh cross-tab ranks the largest EntryDedup
sources as strategy574/1h `161`, strategy566/1h `50`, strategy579/1h `40`, and
strategy485/1d `18`.

The continuity matcher packet further narrows the no-terminal concern:
`matcher_artifact_explained_rows=108` out of `no_terminal_followup_rows=110`
(`98.18%`), leaving `residual_potential_true_gap_rows=2`. The current
profit-improvement focus should therefore be EntryDedup/exposure semantics and
protective duplicate handling, while DataFreshness, no-terminal matching, and
live execution remain review-only.

Latest read-only profit blocker refresh after the EntryDedup window fix still
shows live execution is not ready: EntryDedup is review-ready as a shadow-only
operator lane, DataFreshness `replay_candidate_id_rows=0` and
`complete_replayable_candidate_rows=0`, and governance relaxation remains
`NOT_READY` with no reviewable relaxation candidates.
The 2026-07-01 post-deploy profit validation refresh now has parseable blocker
evidence on deployed `38b6480`: `origin_delta_status=CURRENT_ORIGIN_MAIN`,
`origin_runtime_delta_files=0`,
`deploy_required_before_post_deploy_profit_validation=false`,
`profit_loss_review_gate_status=READY_FOR_LOSS_SOURCE_REVIEW_NOT_LIVE`,
`profit_experiment_gate_status=BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE`, and
`post_deploy_profit_validation_status=BLOCKED_COLLECT_READ_ONLY_EVIDENCE`.
The top profit-improvement lane is DataFreshness false-kill counterfactual, but
it is still blocked by missing fresh replayCandidateId rows, entry/TP/SL
candidate snapshots, EV/OCO preflight snapshots, and complete replayable
candidate rows. This is read-only evidence, not live approval.
The runtime-evidence RCA is also still blocked by
`CANONICAL_ROWS_NO_SHADOW_INTENT`: `TRADING_RUNTIME_EVIDENCE_ENABLED=true`,
canonical rows exist, `shadowIntentCount=0`, `orderSentEvidence=0`, and
`missing_runtime_evidence_fields=[]`. The next-execution blocker packet routes
the highest-ROI execution lane to trailing dry-run observation with
`profit_next_execution_blocker_status=TRAILING_DRY_RUN_ACTIVE_READ_ONLY_OBSERVATION`,
`profit_next_execution_unique_blocker=NO_OPEN_OCO_POSITIONS`,
`profit_next_execution_open_oco_positions=0`, `order_allowed=false`, and
`live_policy_change_allowed=false`.

If verification passes, commit and push the readiness work. The next separate
execution step remains evidence-gated: only promote a lane after the relevant
packet is fresh, review-ready, and explicitly authorizes the narrow action. For
the currently active trailing dry-run observation lane, any future operator
authorization must still match the handoff packet:

```text
TRAILING_STOP_ENABLED=true
TRAILING_STOP_DRY_RUN=true
deploy/restart
read-only post-env verification
dry-run observation collection
```

## Profitability Definition

The system is considered to have reached the "can make money" milestone only
when it has live, replayable, net-positive evidence under bounded exposure. A
single missed rebound or a single profitable hypothetical trade is not enough.
The required standard is repeated evidence that the system improves net PnL
without increasing uncontrolled downside.
