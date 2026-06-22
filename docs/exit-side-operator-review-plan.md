# Exit-Side Operator Review Plan

This is a read-only operator review contract for exit-side profit work. It
combines the trailing-stop replay lane with the strategy 485 aged negative-EV
position lane. It is not authorization to enable live trading, enable the
trailing scheduler, close positions, modify or cancel OCO, place orders, change
production env, deploy, mutate DB/grid/fund/Earn/Telegram/exchange state, or
relax EntryDedup/DataFreshness/live policy.

## Current Evidence Snapshot

Fresh read-only SSH refresh on 2026-06-22T01:20Z, 2026-06-22T01:22Z, and
2026-06-22T02:21Z reported:

```text
exit_side_operator_decision_brief_status=READY_FOR_OPERATOR_DECISION_NOT_MUTATION
exit_side_operator_primary_recommendation=PREPARE_SEPARATE_EXIT_SIDE_OPERATOR_REVIEW
profit_operator_action_brief_status=READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE
profit_operator_review_summary_status=READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE
profit_operator_action_primary_recommendation=REVIEW_EXIT_SIDE_TRAILING_AND_STRATEGY485_NOT_MUTATION
profit_operator_review_summary_ready_lanes=exit-side: P1 EXIT_SIDE_REVIEW_READY_NOT_LIVE
profit_operator_review_summary_blocked_lanes=entry-filter: P2 ENTRY_FILTER_POLICY_BLOCKED, data-freshness-replay: P2 DATAFRESHNESS_REPLAY_BLOCKED
trailing_stop_acceptance=PASS
trailing_stop_improvement_pct=52.602%
trailing_stop_delta_pnl=12339.29590001
strategy485_oco_health_ok=True
strategy485_negative_ev_position_count=3
strategy485_close_or_modify_suggestion_count=3
```

The current strategy 485 position summaries were:

```text
positionId=148 decision=WATCH suggestion=CLOSE evUsdt=-0.29 paperPct=-4.09
positionId=149 decision=WATCH suggestion=CLOSE evUsdt=-0.28 paperPct=-4.03
positionId=150 decision=WATCH suggestion=CLOSE evUsdt=-0.20 paperPct=-3.67
```

This evidence is enough to prepare a separate operator review. It is not enough
to execute a live policy change or any position/OCO mutation.

The same fresh action brief kept these lanes blocked:

```text
entry-filter status=BLOCKED_GOVERNANCE_MISSED_OPPORTUNITY_REVIEW
entry-filter evidence=signal_policy_clear=false,data_freshness_current_status=NO_CURRENT_SAMPLE
data-freshness-replay status=PENDING_DATAFRESHNESS_CURRENT_SAMPLE
data-freshness-replay missing=fresh replayCandidateId rows, entry/TP/SL candidate snapshot, EV and OCO preflight snapshots, shadow replay removing only DataFreshnessGuard
```

Those blockers mean EntryDedup/DataFreshness/live policy must remain unchanged
until fresh current-sample and replay evidence clears the separate policy path.
The 2026-06-22T02:21Z summary reused the saved matrix at
`target/profit-review/profit-operator-matrix-20260622T022122Z-BTCUSDT-strategy485.log`
and confirmed the same routing: prepare the separate exit-side operator review
first; keep entry/filter and DataFreshness lanes blocked.

The 2026-06-22T03:39Z read-only refresh completed in 869 seconds and saved
`target/profit-review/profit-operator-matrix-20260622T033926Z-BTCUSDT-strategy485.log`.
The follow-up summary reused that matrix with
`profit_operator_review_summary_freshness_status=FRESH`,
`profit_operator_review_summary_matrix_age_minutes=0`, and
`profit_operator_review_summary_status=READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE`.
It preserved the same routing: exit-side P1 is ready for separate operator
review, while entry-filter and DataFreshness replay remain blocked.

## Draft Review Packet

This packet is the next safe output for the profit path. It is proposal-only,
read-only, and expires when the evidence window is stale. It must be refreshed
with the required inputs before an operator uses it for any later decision.
The summary output must include `profit_operator_review_summary_freshness_status`
so a downstream review can reject stale source-matrix evidence before reading
the ready-lane recommendation.

To convert the fresh summary into a single proposal-only packet without a new
SSH refresh, run:

```powershell
.\scripts\prepare_exit_side_operator_experiment_packet.ps1 -RequireReady
```

Expected output includes:

```text
packetType=EXIT_SIDE_OPERATOR_EXPERIMENT_REVIEW
exit_side_operator_experiment_packet_status=READY_FOR_EXIT_SIDE_EXPERIMENT_REVIEW_NOT_LIVE
proposalId=trailing-stop-dry-run-experiment-review
proposalId=strategy485-risk-reduction-shadow-review
```

### Proposal: trailing-stop-rollout-review

```text
proposalId=trailing-stop-rollout-review
proposalStatus=READY_TO_DRAFT_REVIEW_NOT_LIVE
proposalClass=DRY_RUN_OR_ROLLOUT_REVIEW_NOT_LIVE
candidateNextStep=Draft a trailing-stop dry-run or rollout review for operator approval only.
notAuthorization=No scheduler enablement, no live trading, no OCO mutation, no deploy, no production env change.
```

Required fresh evidence:

- `trailing_stop_acceptance=PASS`
- `exit_side_operator_decision_checklist` includes trailing-stop rollout checks
- scheduler is still disabled or dry-run for the proposed scope
- strategy opt-in scope is explicit
- ambiguous same-bar rows remain excluded from acceptance

### Proposal: strategy485-risk-reduction-review

```text
proposalId=strategy485-risk-reduction-review
proposalStatus=READY_TO_DRAFT_REVIEW_NOT_MUTATION
proposalClass=RISK_REDUCTION_REVIEW_NOT_MUTATION
candidateNextStep=Draft a strategy 485 risk-reduction decision packet for operator approval only.
notAuthorization=No close-position action, no OCO modification, no order placement, no deploy, no production env change.
```

Required fresh evidence:

- `strategy485_oco_health_ok=True`
- `strategy485_negative_ev_position_count > 0`
- `strategy485_close_or_modify_suggestion_count > 0`
- fresh active-position EV reassessment
- TP stretch, timeout, recent-closed PnL, and monthly PnL context

### Proposal Expiration

Refresh the read-only briefs before using this packet if any of these are true:

- the latest matrix is older than 180 minutes
- positions 148, 149, or 150 have closed or materially changed
- OCO health changed
- trailing acceptance is no longer `PASS`
- a new current DataFreshness sample appears
- the operator wants to convert this review into any live, OCO, order,
  scheduler, deploy, or production-env action

## Required Fresh Inputs

Before each operator review, rerun the read-only brief:

```powershell
.\scripts\prepare_exit_side_operator_decision_brief_ssh.ps1 -RequireDecisionReady
```

For a higher-level action routing packet that keeps the two exit-side proposal
lanes separate, run:

```powershell
.\scripts\prepare_profit_operator_action_brief_ssh.ps1 -RequireReady
```

The review packet must include these markers:

- `scope=READ_ONLY`
- `exit_side_profit_review_packet_status=READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION`
- `exit_side_operator_decision_brief_status=READY_FOR_OPERATOR_DECISION_NOT_MUTATION`
- `exit_side_operator_decision_lanes`
- `exit_side_operator_decision_checklist`
- `linkedActionProposalIds`
- `proposalId=trailing-stop-rollout-review`
- `proposalId=strategy485-risk-reduction-review`
- `reviewContract=docs/exit-side-operator-review-plan.md`
- `trailing_stop_acceptance=PASS`
- `strategy485_oco_health_ok=True`
- `strategy485_position_summaries`
- `exit_side_operator_action_proposals`
- `notAuthorization`

## Decision Lanes

### Trailing-Stop Rollout

Review status can be `READY_FOR_OPERATOR_REVIEW_NOT_LIVE` when the trailing
acceptance is `PASS`. The allowed output is a separate dry-run or rollout
proposal only.

Before any later rollout proposal, verify:

- scheduler remains disabled or dry-run
- strategy opt-in scope is explicit
- OCO modification path remains disabled unless separately approved
- ambiguous same-bar rows are excluded from acceptance
- production env and deployment changes have separate authorization

### Strategy 485 Risk Reduction

Review status can be `READY_FOR_OPERATOR_REVIEW_NOT_MUTATION` when OCO health is
OK and open positions have fresh negative-EV evidence. The allowed output is a
separate risk-reduction decision packet only.

Before any later risk-reducing mutation, verify:

- current OCO health remains OK
- active-position EV reassessment is fresh
- TP stretch and timeout evidence is attached
- recent-closed and monthly PnL context is attached
- operator explicitly approves each close-position or OCO action

### Entry Filter and DataFreshness

Entry-filter and DataFreshness policy are out of scope for this exit-side
review. Route them through the profit operator action brief and keep
EntryDedup/DataFreshness/live policy unchanged.

## Stop Conditions

Stop this exit-side profit path and refresh evidence if any of these occur:

- OCO health is not OK
- trailing acceptance is not `PASS`
- decision checklist is missing
- `notAuthorization` is missing
- entry/filter or DataFreshness policy is bundled into the exit-side review
- any output implies it can enable live trading, trailing scheduler, OCO
  modification, close-position, deploy, or production env changes
- any order/OCO/grid/fund/Earn/Telegram/exchange mutation happens during
  evidence collection

## Operator Output Boundary

This plan may produce:

- a separate trailing-stop dry-run or rollout review proposal
- a separate strategy 485 risk-reduction decision packet
- a recommendation to keep monitoring when evidence weakens

It must not produce executable commands for live trading, OCO changes,
close-position actions, scheduler enablement, deployment, or production env
changes without a later exact action plan and explicit authorization.
