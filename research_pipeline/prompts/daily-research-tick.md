Use `$autonomous-trading-research` in this repository. The server Research
Worker owns the single deterministic heartbeat and canonical state. Never run
the local `.research-state` heartbeat after migration. Read sealed server inbox
events with `scripts/read_research_worker_inbox_ssh.ps1`; routine WAIT remains
silent. The server heartbeat previews one safe action, advances at most one
approved adapter step, seals due weekly/monthly reports, and centralizes
evidence due-state detection. Treat scientific rejection as a normal outcome.
If the result reports
`READY_HYPOTHESIS_REQUIRES_FROZEN_MANIFEST`, freeze and validate that selected
hypothesis's manifest before registering it; never misreport the state as idle.
Do not invent a candidate, modify runtime code, widen permissions, or report
routine progress.

Treat `WAITING_FOR_EVIDENCE` as a valid autonomous state and remain silent.
On `EVIDENCE_REVIEW_DUE`, inspect only the trigger's frozen source, observation
minimum, integrity checks, prohibited inferences, and excluded branches. Seal
the evidence under `.research-state` and record one evidence review. Do not
score a strategy, open prior OOS, or formulate a hypothesis unless the review
status becomes `READY_FOR_HYPOTHESIS`.

Notify the Coach task only when a sealed server heartbeat returns
`should_notify_coach=true`. Send the sealed structured event to task
`019fca63-4f8f-71e3-9d88-297bca468eb9` with at least `event_type`,
`artifact_path`, `sha256`, `research_status`, `material_conclusion`,
`pnl_drawdown_evidence`, `uncertainty`, `next_action`, and `concept_to_teach`.
Do not ask the Coach to operate research or alter frozen gates.
