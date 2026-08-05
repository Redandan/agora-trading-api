Use `$autonomous-trading-research` in this repository. Treat the OAuth Research
MCP as the canonical status and queue path. Never run a local `.research-state`
heartbeat or write a local evidence review after server migration. The server
Research Worker owns the single deterministic heartbeat and canonical state;
routine `WAITING_FOR_EVIDENCE` remains silent.

Read `get_research_status` first. Call `request_research_heartbeat` only when
canonical `next_due` has arrived; early calls must remain `NOT_DUE`, and
concurrent calls must converge. Poll `get_research_run` briefly after a queued
request, otherwise inspect the durable result on the next cloud cycle. Do not
invent a candidate, change runtime code, widen permissions, or report routine
progress.

Treat Worker provenance as a hard integrity gate. Continue only when canonical
`worker_release.status=READY`, `source_git_dirty=false`, `source_git_commit` is
exactly 40 lowercase hexadecimal characters, and `source_manifest_sha256` is
exactly 64 lowercase hexadecimal characters. Missing, invalid, tampered, or
dirty provenance is an operational alert; do not enqueue a heartbeat or submit
a candidate until a clean release is deployed.

The fixed forward-source companion captures only the next complete UTC day.
When the final required day is canonically ingested, the deterministic pipeline
seals the mechanism-neutral dataset, diagnostic, typed evidence manifest, and
`READY_FOR_HYPOTHESIS` review without adding another MCP tool or timer. If the
review date arrives while the final day is still `CAPTURE_DUE`, capture remains
the next action; never skip it, backfill it, or write the review locally.

If the heartbeat response queues or resumes a companion capture, poll
`get_research_status` for a bounded part of the same cloud cycle and use only
canonical `evidence_capture_health`. Do not enqueue the heartbeat or capture a
second time. `SOURCE_CAPTURE_RETRYING`, `EVIDENCE_INGEST_RUNNING`, and
`EVIDENCE_INGEST_RETRYING` may be observed briefly; a terminal `SEALED` result
is routine and remains silent. `SOURCE_CAPTURE_FAILED`,
`SOURCE_CAPTURE_RETRY_STALLED`, `EVIDENCE_INGEST_FAILED`,
`EVIDENCE_INGEST_RETRY_STALLED`, `EVIDENCE_INGEST_DISPATCH_STALLED`, or
`INTEGRITY_BLOCKED` is an immediate integrity alert. If the bounded observation
ends before a terminal state, report `CAPTURE_OBSERVATION_PENDING` with the
canonical request id and deadline; never guess success, backfill, or add a
second schedule.

When canonical status becomes `EVIDENCE_READY_REQUIRES_CODEX_HYPOTHESIS`, use
the matching canonical evidence trigger's `candidate_context`. Require
`candidate_context.status=READY`, exactly one item in `eligible_mechanisms`, and
that item's `all_predictive_gates_pass=true`. Copy the exact `adapter`, `parent`,
`selection_cutoff`, `oos_cutoff`, `max_variants`, and
`adapter_config_template`; replace only the template `mechanism_key` with that
eligible mechanism. Do not invent a threshold, window, feature, source hash, or
second mechanism. Formulate at most one deduplicated causal hypothesis and its
matching frozen experiment manifest, then submit exactly one schema-bound
bundle through `submit_research_candidate_bundle`. If candidate context is
`NO_SUPPORTED_MECHANISM`, do not submit: preserve the sealed
`NO_CANDIDATE_FORWARD_DIAGNOSTIC` learning and wait for a newly preregistered
mechanism or untouched evidence window. The discovery window is not clean OOS.
The server must reverify all sealed evidence and stop at `PREREGISTERED`; do not
execute or promote the experiment in the same step.
Require canonical `registry.forward_candidate_readiness.status=READY` and use
only one adapter listed in `eligible_adapters`. If readiness reports
`NO_ELIGIBLE_FORWARD_CANDIDATE_ADAPTER`, emit a capability-readiness alert and
do not repurpose a parity adapter, diagnostic-only adapter, or closed historical
branch as a strategy candidate. A submitted forward candidate must retain a
sealed OOS cutoff. Registration creates a distinct `CANDIDATE_OOS` trigger
whose start is after the candidate manifest freeze. Do not reuse the discovery
dataset, open partial OOS, submit a second candidate, or change the frozen
mechanism while the candidate OOS trigger is waiting. Registration readiness is
not permission to omit OOS.
Use canonical `candidate_registration_sla`, not chat-side time arithmetic, to
observe the 24-hour contract. `PENDING_WITHIN_SLA` requires submission in the
same cloud cycle. `BREACH_PENDING_REGISTRATION` is an operational alert but
does not authorize dropping evidence or relaxing a scientific gate; submit the
still-valid bounded bundle once and preserve the measured `BREACH` result.

Create a Coach handoff only when a sealed server heartbeat returns
`should_notify_coach=true`. Require canonical `coach_outbox.status` to be
`EVENTS_PENDING_EXTERNAL_DELIVERY` and use only its hash-verified events. Treat
`COACH_OUTBOX_INVALID` as an integrity alert and do not infer or repair missing
fields from chat history. Include at least `event_type`, `artifact_path`,
`sha256`, `research_status`, `material_conclusion`, `pnl_drawdown_evidence`,
`evidence_diagnostic`, `uncertainty`, `next_action`, and `concept_to_teach`.
Use the sealed SHA-256 as `delivery_id` and target Coach task
`019fca63-4f8f-71e3-9d88-297bca468eb9`.

Direct delivery is valid only when the scheduled surface exposes a supported
tool that writes to that exact existing Codex task and the call succeeds. If no
such tool is available, return the complete handoff in the scheduled result
with `delivery_status=CROSS_TASK_DELIVERY_PENDING`; never describe Scheduled,
Activity, push/email/SMS, same-chat output, or manual copying as delivery to the
Coach task. Do not repeat a `delivery_id` already present in the scheduled chat.
This is a user-visible sealed outbox, not proof of Coach-thread delivery. Do not
ask the Coach or sponsor to operate research or alter frozen gates.
