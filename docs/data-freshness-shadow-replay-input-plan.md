# DataFreshness Shadow Replay Input Plan

This is a review-only plan for turning the DataFreshness false-kill alpha proxy
into replayable evidence. It is not authorization to edit production env,
deploy, restart the service, relax DataFreshnessGuard, enable live trading,
place orders, create live positions, change OCO, run grid/fund/Earn actions,
send Telegram, run external backfill/import jobs, mutate DB schema, or change
schedulers.

## Current Evidence

The latest read-only counterfactual review showed:

```text
data_freshness_counterfactual_rows=74
runtime_evidence_linked_rows=74
live_signal_linked_rows=0
explicit_candidate_entry_rows=0
explicit_candidate_tp_rows=0
explicit_candidate_sl_rows=0
ev_snapshot_rows=0
oco_plan_snapshot_rows=0
hard_gate_snapshot_rows=74
complete_replayable_candidate_rows=0
positive_forward_24h_rows=74
avg_forward_24h_pct=+4.90%
avg_mfe_24h_pct=+5.12%
avg_mae_24h_pct=-0.53%
data_freshness_counterfactual_recommendation=COUNTERFACTUAL_NOT_REPLAYABLE_CANDIDATE_SNAPSHOT_MISSING
```

Interpretation:

- The forward-return proxy is positive, but it is not executable profit
  evidence.
- DataFreshnessGuard blocked at L0 before entry/TP/SL, EV, OCO, and complete
  hard-gate snapshots were captured.
- The next useful step is not policy relaxation. The next useful step is
  evidence collection that can replay "remove only DataFreshnessGuard" while
  keeping every other hard gate intact.

Fresh read-only production refresh on 2026-06-22T01:40Z and
2026-06-22T01:41Z showed that this path is still blocked:

```text
data_freshness_current_status=NO_CURRENT_SAMPLE
data_freshness_replay_candidate_id_recommendation=PENDING_NO_NEW_DATAFRESHNESS_ROWS
latest_data_freshness_row_time=2026-06-14T15:38:16
latest_data_freshness_row_age_hours=178
data_freshness_rows_1d=0
data_freshness_rows_3d=0
data_freshness_rows_7d=0
data_freshness_rows_14d=74
data_freshness_rows_30d=110
data_freshness_sample_gap_status=NO_ROWS_IN_REVIEW_WINDOW
complete_replayable_candidate_rows=0
missing_counterfactual_fields=["liveSignalId","replayCandidateId","explicit entry/TP/SL candidate plan","EV snapshot","OCO plan","complete replayable candidate rows"]
```

The same no-buy row review classified the current no-buy evidence as:

```text
signalPolicyClear=false
governanceMode=INSUFFICIENT_DATA
missedOpportunityStatus=WARN
noBuyClassifications=VALID_SIGNAL_NOT_READY:2, WATCH_SIGNAL_NEAR_BUY_THRESHOLD:1, VALID_HARD_SAFETY_BLOCK:1
noBuyBlockerFamilies=SIGNAL_NOT_READY:3, CAPACITY:1
no_buy_row_action_family_counts=[{"family":"WAIT_FOR_SIGNAL_CONFIRMATION","count":4}]
no_buy_row_review_packet_status=REVIEW_REQUIRED_NOT_EXPERIMENT
```

Interpretation:

- The latest blocker is not evidence that DataFreshnessGuard is currently
  over-blocking; there are no recent terminal DataFreshness rows to replay.
- Recent no-buy evidence is dominated by wait-for-signal / not-ready rows, not
  an immediately actionable DataFreshness relaxation candidate.
- The next useful step remains observation: wait for a fresh terminal
  DataFreshnessGuard sample or instrument a separately authorized
  replay-input collector. Do not relax DataFreshnessGuard, EntryDedup, or live
  policy from the historical 74-row proxy.

## Replay Input Contract

For a DataFreshnessGuard-only block to become replayable, the evidence row must
contain or reference:

- `decisionId` plus a stable replay candidate id. `liveSignalId` is acceptable
  only if it already exists naturally; do not create a live signal just to make
  the replay pass.
- New L0 DataFreshness audit rows should carry `replayCandidateId` with the
  `dfsr1_...` format so replay candidates can be tracked before a live signal
  exists. This is an identifier only, not executable evidence.
- symbol, side, strategy id, interval, bar open time, and decision time.
- Data freshness snapshot: source, latest bar open time, stale minutes,
  threshold minutes, current/source status, and whether the failure is a true
  stale kline.
- candidate plan: entry price, TP price, SL price, current price, quantity or
  notional preview, and max-loss preview.
- EV snapshot: `expected_r`, minimum expected R, EV pass/fail, and EV reason.
- TQS snapshot: quality score, band, and blocking components.
- OCO preflight snapshot: OCO capable true/false, TP/SL validity, min-notional
  status, and sync-health status. This must be a dry-run/preflight result, not
  an OCO placement.
- hard-gate snapshot: duplicate bar, daily cap, exposure cap, open-position
  guard, event-risk level, risk budget, and loss breaker state.
- runtime evidence safety markers: `orderSent=false`, no exchange order id, no
  OCO modification, no OCO algo id created, no Telegram send, no
  scheduler/live mutation, and no DB schema mutation.

## Collector Boundary

The collector, if implemented later, must be shadow/replay input only:

- It may persist evidence rows only after a separate evidence-only
  authorization and deploy.
- It must not change the DataFreshnessGuard decision outcome.
- It must not create live orders, live positions, OCO orders, grid/fund/Earn
  actions, Telegram sends, external backfills/imports, or scheduler mutations.
- It must not enable `TRADING_OKX_ENABLED`,
  `TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED`, ScoreBuy execution flags,
  `TRAILING_STOP_ENABLED`, `POSITION_EXIT_MANAGER_ENABLED`,
  `TRADING_GRID_ENABLED`, `TRADING_FUNDING_ARB_ENABLED`,
  `OKX_EARN_TOPUP_ENABLED`, or `MCP_GUARDIAN_LIVE_ACTIONS_ENABLED`.
- It must preserve public MCP blocking and server-local `/api/mcp` operator
  verification.

## Required Verification

Before using DataFreshness false-kill evidence for any policy review, collect a
fresh read-only bundle:

```powershell
.\scripts\smoke_data_freshness_false_kill_review_ssh.ps1
.\scripts\smoke_data_freshness_executability_review_ssh.ps1
.\scripts\smoke_data_freshness_counterfactual_review_ssh.ps1
.\scripts\smoke_profit_improvement_review_bundle_ssh.ps1
```

Expected before a policy-review packet is even draftable:

- `currentDataFreshnessClean=true`
- `data_freshness_executability_recommendation` is no longer
  `ALPHA_NOT_EXECUTABILITY_PROVEN_COLLECT_SHADOW_REPLAY`
- `data_freshness_counterfactual_recommendation=REVIEW_COUNTERFACTUAL_REPLAY_CANDIDATES`
- `complete_replayable_candidate_rows > 0`
- `ev_snapshot_rows > 0`
- `oco_plan_snapshot_rows > 0`
- `hard_gate_snapshot_rows > 0`
- `missing_counterfactual_fields=[]`
- replay sample size is at least 30 mature rows before it is treated as an
  internal policy-review sample
- replay keeps ExpectedValueGate, OCO preflight, duplicate, daily cap,
  exposure, event-risk, open-position, and loss-budget gates intact
- `orderSentEvidence=0`
- runtime logs show no order placement, OCO modification, grid/fund/Earn
  operation, Telegram send, exchange write, external backfill/import, DB schema
  mutation, deploy, restart, or nginx change caused by the evidence pass

## Decision Gate

Only after the verification above is met can an operator review whether a
bounded shadow or tiny-live experiment is worth proposing. Even then:

- It is not permission to relax DataFreshnessGuard.
- It is not permission to enable live trading.
- It is not permission to close positions or modify OCO.
- It is not permission to change EntryDedup, live policy, scheduler, exchange,
  grid, fund, Earn, Telegram, or production env.
- A later experiment still needs a separate exact diff, rollback plan, current
  read-only evidence, and explicit operator authorization.

## Stop Conditions

Stop the DataFreshness improvement path and review safety first if any of these
appear:

- current freshness is not clean
- candidate snapshots are missing required hard-gate fields
- `orderSentEvidence > 0`
- any exchange order, OCO modification, live position mutation, Telegram send,
  grid/fund/Earn action, external backfill/import, or DB schema mutation appears
- replay removes more than DataFreshnessGuard
- forward-return edge disappears after EV/OCO/daily-cap/exposure/event-risk
  gates are applied
- sample size remains too small or dominated by one stale outage window
