# Exit-Side Operator Review Plan

This is a read-only operator review contract for exit-side profit work. It
combines the trailing-stop replay lane with the strategy 485 aged negative-EV
position lane. It is not authorization to enable live trading, enable the
trailing scheduler, close positions, modify or cancel OCO, place orders, change
production env, deploy, mutate DB/grid/fund/Earn/Telegram/exchange state, or
relax EntryDedup/DataFreshness/live policy.

## Current Evidence Snapshot

The latest production read-only decision brief reported:

```text
exit_side_operator_decision_brief_status=READY_FOR_OPERATOR_DECISION_NOT_MUTATION
exit_side_operator_primary_recommendation=PREPARE_SEPARATE_EXIT_SIDE_OPERATOR_REVIEW
trailing_stop_acceptance=PASS
trailing_stop_improvement_pct=52.602%
trailing_stop_delta_pnl=12339.29590001
strategy485_oco_health_ok=True
strategy485_negative_ev_position_count=3
strategy485_close_or_modify_suggestion_count=3
```

The current strategy 485 position summaries were:

```text
positionId=148 decision=WATCH suggestion=CLOSE evUsdt=-0.36 paperPct=-5.22
positionId=149 decision=WATCH suggestion=CLOSE evUsdt=-0.36 paperPct=-5.16
positionId=150 decision=WATCH suggestion=CLOSE evUsdt=-0.26 paperPct=-4.80
```

This evidence is enough to prepare a separate operator review. It is not enough
to execute a live policy change or any position/OCO mutation.

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
