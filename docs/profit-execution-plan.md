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
"waiting for separate trailing-stop dry-run env/deploy authorization".

Known current facts:

- Strategy 574 trailing-stop opt-in has been applied.
- `trailingStopEnabled=true` is confirmed for strategy 574.
- OCO writes were not performed by the opt-in action.
- Current next blocker is
  `AWAIT_SEPARATE_TRAILING_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION`.
- Global `TRAILING_STOP_ENABLED` is still false, so trailing-stop is not yet
  active in runtime observation.
- `TRAILING_STOP_DRY_RUN` is expected to stay true for the next phase.
- The next profitable path is evidence-first trailing-stop dry-run, not
  immediate live OCO mutation.

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

If the packet still reports the same blocker, the next executable phase is:

- apply reviewed env diff:
  - `TRAILING_STOP_ENABLED=true`
  - `TRAILING_STOP_DRY_RUN=true`
- deploy and restart;
- run read-only post-env verification;
- collect dry-run trailing observations;
- compare dry-run exit decisions against actual forward outcome;
- promote to live OCO mutation only after dry-run evidence proves benefit and
  no abnormal OCO health is present.

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
.\scripts\prepare_profit_next_execution_blocker_packet.ps1 -RequireReady
.\scripts\prepare_trailing_stop_dry_run_env_deploy_handoff_ssh.ps1 -RequireReady
```

If verification passes, commit and push the readiness work. The next separate
execution step is the trailing-stop dry-run env/deploy phase. The exact
operator authorization must match the handoff packet:

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
