Use `$autonomous-trading-research` in this repository. Treat the OAuth Research
MCP as the canonical status and queue path. Never run a local `.research-state`
heartbeat or write a local evidence review after server migration. The server
Research Worker owns the single deterministic heartbeat and canonical state;
routine `WAITING_FOR_EVIDENCE` remains silent.

When and only when this prompt is running as the frozen V12 ChatGPT Work cloud
schedule, read `get_research_status` as the first operation. Coach delivery is
returned only as exact current assistant output in this existing schedule chat;
do not list, read, inspect, or send to another task. If canonical `next_due`
has not arrived, stop without forming a delivery receipt or MCP write. Call
`request_research_heartbeat` only when canonical `next_due` has arrived; early
calls must remain `NOT_DUE`, and
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

Treat the versioned cloud Ops schedule contract as a caller-attestation gate.
This repository contains frozen document `CLOUD_OPS_SCHEDULE_V12` with
`document_status=FROZEN` and exact SHA-256
`98cc2374961fb37c00a8396e6bd8126b7b39a32d7d85ea0e0fcd30c2b9c7fc0c`.
Its external repository rollout state is `PREPARED_NOT_ACTIVE_V12`; activation
is separately proven outside the immutable document and never edits its bytes.
Before activation, if
canonical status still reports `CLOUD_OPS_SCHEDULE_V11`, stop before every V12
write call; do not attest V12, fall back to an older hash, or infer cutover.

After separately proven external V12 activation, continue only when canonical
`ops_schedule_contract.status=READY`, `contract_id=CLOUD_OPS_SCHEDULE_V12`,
`schedule_count=1`,
`timer_authority=CODEX_CLOUD_OPS_ONLY`,
`recurrence.timezone=Asia/Taipei`, `recurrence.local_time=09:05`,
`recurrence.end=NEVER`,
`canonical_heartbeat_due.timezone=Asia/Taipei`,
`canonical_heartbeat_due.local_time=09:00`,
`dispatch_margin.scheduled_seconds_after_canonical_due=300`,
`dispatch_margin.early_call_behavior=NOT_DUE_NO_DELIVERY_RECEIPT`,
`dispatch_margin.additional_timer=DENY`,
`failure_lifecycle.failed_occurrence_effect=FAIL_CLOSED_CURRENT_OCCURRENCE_ONLY`,
`failure_lifecycle.schedule_enabled_state_after_failure=KEEP_ENABLED`,
`failure_lifecycle.automatic_pause_disable_or_delete=DENY`,
`failure_lifecycle.schedule_self_mutation=DENY`,
`failure_lifecycle.next_normal_occurrence=PRESERVE`, and
`sha256=98cc2374961fb37c00a8396e6bd8126b7b39a32d7d85ea0e0fcd30c2b9c7fc0c`.
Pass that exact hash as `ops_schedule_contract_sha256` on every
`request_research_heartbeat` and `submit_research_candidate_bundle` call.
Missing, invalid, or mismatched contract/attestation is an operational alert;
fail closed without queueing either operation.

V12 preserves the V11 placement of the one cloud recurrence five minutes after the
unchanged 09:00 canonical heartbeat due boundary so a small platform early-fire
jitter cannot skip the only daily cycle. This margin never authorizes an early
heartbeat: always compare canonical `next_due`, preserve `NOT_DUE`, and never
add a catch-up call, retry timer, or second schedule.

Failure of a scheduled occurrence is scoped to that occurrence only. A tool,
platform, policy, provenance, attestation, queue, evidence, candidate, delivery,
or integrity failure must fail closed without claiming a research write, but
must not pause, disable, delete, reschedule, rename, replace, or otherwise mutate
this sole cloud schedule. Preserve its enabled state and future normal
occurrence. Do not invoke an automation-management operation from this prompt.
Only explicit user authorization outside a scheduled occurrence may permit a
schedule lifecycle change. Never use continued scheduling as evidence that the
failed occurrence succeeded, and never catch up, backfill, or retry the
heartbeat in the same occurrence.

Inspect canonical `candidate_registration_recovery` before formulating or
submitting any new candidate. `IDLE` permits the normal evidence-ready flow.
`EXACT_REPLAY_REQUIRED` means a prior candidate request left hash-verified
partial canonical registration: require queue `IDLE`, copy the canonical
`bundle` byte-for-value without changing its timestamp, text, mechanism, OOS
window, or any other field, and call `submit_research_candidate_bundle` exactly
once with the normal V12 attestation. Verify that the canonical
`payload_sha256` is unchanged and poll that replay's run. This is recovery of
the same logical candidate, not permission for a second candidate. If recovery
is `INTEGRITY_BLOCKED`, including repeated replay failure or partial-state
payload drift, do not retry, regenerate, or relax a gate; emit the exact
canonical reason as an operational integrity alert.
Treat MCP write responses `EXACT_CANDIDATE_REPLAY_REQUIRED` and
`CANDIDATE_REGISTRATION_INTEGRITY_BLOCKED` as the same mandatory stop. Do not
resubmit, switch payloads, or infer that a queue-write rejection can be fixed
from conversation state.
Treat `CANDIDATE_TRIGGER_NOT_READY` as proof that no normal candidate may be
queued in this cycle: re-read canonical status, do not retry, and continue the
evidence wait. Treat `CANDIDATE_TRIGGER_INTEGRITY_BLOCKED` as an immediate
operational integrity alert and do not reconstruct readiness from chat or local
state. Only the exact canonical recovery replay described above may bypass the
normal ready-trigger preflight.

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

At the start of every cloud cycle, inspect canonical `coach_outbox` even when
the latest heartbeat is routine or has `should_notify_coach=false`. Pending
events live in durable server heartbeat state until a verified receipt is
accepted; a later routine heartbeat must never erase them. Create a Coach
handoff only for canonical `coach_outbox.status=EVENTS_PENDING_EXTERNAL_DELIVERY`
and use only its hash-verified events. Treat
`COACH_OUTBOX_INVALID` as an integrity alert and do not infer or repair missing
fields from chat history. Include at least `event_type`, `artifact_path`,
`sha256`, `research_status`, `material_conclusion`, `pnl_drawdown_evidence`,
`evidence_diagnostic`, `uncertainty`, `next_action`, and `concept_to_teach`.
Use the sealed SHA-256 as `delivery_id`. The only Coach delivery destination is
this existing schedule chat, exact thread
`6a71a167-be58-83ec-aed2-f1736e31dd45`. The former Coach task
`019fca63-4f8f-71e3-9d88-297bca468eb9` is inaccessible and is V11 history; do
not list it, read it, send to it, or accept one of its legacy prompts as V12
receipt proof.

Observe each event's canonical `delivery_proof_sla`. The measured clock is
queue-to-verified-receipt proof: `delivery_queued_at` is frozen when the event
enters the outbox and `delivery_deadline_at` is the end of the next normal
cloud cycle's bounded three-hour completion window.
`PENDING_WITHIN_SLA` must be delivered in the current cycle.
`BREACH_PENDING_DELIVERY_PROOF` is an operational alert, but it still permits
only the same single deduplicated delivery attempt. Preserve canonical signed
seconds and the eventual `PASS` or `BREACH` lead time. Treat
`MISSING_PROOF_LEGACY_EVENT` or `MISSING_PROOF_LEGACY_RECEIPT` as missing proof;
never infer timing from chat history.

Require `coach_outbox.delivery_contract.status=READY` and, only after V12 is
canonically active, the same-schedule-chat contract
`SEALED_COACH_SAME_SCHEDULE_CHAT_DELIVERY_V2`. Require its exact target thread
`6a71a167-be58-83ec-aed2-f1736e31dd45` and canonical
`delivery_proof_sla.completion_window_seconds=10800` and never substitute a
chat-side clock. For each event, copy the exact
canonical `delivery_prompt`; do not reconstruct it from chat or edit its JSON.
Do not call any task-discovery, task-read, or task-send operation. The current
assistant output of this scheduled occurrence is the only delivery surface.

After fresh canonical status proves the heartbeat is normally due, freeze the
exact at-most-eight initial pending events from that status. At turn start,
inspect only prior assistant messages in this same schedule chat. For each
initial event, receipt proof requires that one prior assistant message contains
the event's exact full canonical `delivery_prompt`, byte-for-byte as supplied
by fresh `coach_outbox`; a complete token alone is insufficient. The exact
prompt must embed delivery contract
`SEALED_COACH_SAME_SCHEDULE_CHAT_DELIVERY_V2`, target thread
`6a71a167-be58-83ec-aed2-f1736e31dd45`, and the same delivery id that is still
pending in fresh canonical status.

Never form a receipt from the current turn, a V11 or earlier prompt, a token
without its exact full canonical prompt, a user quote, summarized or truncated
context, a Scheduled inbox item, notification, altered content, or inference.
If exact prior context is absent, form no receipt for that event. Context loss
keeps the canonical event pending and permits only one exact re-render in the
current assistant output; it never resets the delivery id, queue time, deadline,
or existing `BREACH` result.

After every independent Worker provenance, V12 attestation, due-time, queue,
evidence, candidate, OOS, deduplication, delivery, and integrity gate passes,
invoke at most the otherwise-valid normally due heartbeat with zero to eight
receipts proven from those prior assistant messages. Zero receipts is an empty
array, not a delivery claim, and never authorizes an early or catch-up call.
The exact full prompt first rendered in this occurrence is ineligible for a
same-turn receipt; only a later normally due heartbeat may acknowledge it.

Pass only receipts for ids in the initial fresh canonical snapshot. After the
receipt-bearing heartbeat, read fresh canonical status and require every
accepted id to leave pending state while all unacknowledged ids retain their
original delivery id, queue time, deadline, and breach state. A heartbeat
success is research advancement only and never implies Coach delivery.

The heartbeat may create a new Coach event. After fresh post-heartbeat status,
render each still-pending event's exact canonical `delivery_prompt` once in the
current assistant output, up to the canonical bounded batch of eight. Do not
alter, summarize, wrap inside reconstructed JSON, or omit any bytes of an event
you claim to render. A newly created or re-rendered event is eligible only when
that exact full prompt remains visible in a prior assistant message at the next
normally due occurrence and the identical id is still canonical-pending.

Every receipt object contains only `schema_version=1`, `delivery_id`,
`delivery_token`, `target_thread_id`, and `delivery_status`. The only permitted
statuses are `DELIVERED_TO_COACH_TASK_VERIFIED` and
`ALREADY_DELIVERED_TO_COACH_TASK`; never acknowledge
`QUEUED_TO_COACH_TASK_UNVERIFIED` or `CROSS_TASK_DELIVERY_PENDING`. An initial-
snapshot receipt proven by an exact prior assistant V12 prompt is carried by
the current normally due heartbeat. Every current-turn or post-heartbeat render
waits for a following normally due heartbeat. Neither authorizes an early call.
The server must match each receipt to a pending or already acknowledged
delivery id before mutating either queue.

If prior assistant context is unavailable, emit exact hash-identified
`MISSING_PROOF_PRIOR_ASSISTANT_V12_PROMPT` debt, keep the canonical event
pending, and render its exact canonical prompt at most once for proof on a
later normal cloud cycle. Do not block the otherwise-valid normally due
heartbeat solely because delivery proof is missing. Never reset
`delivery_queued_at` or `delivery_deadline_at`, add a timer/poller/messenger,
create another schedule, or ask the Coach or sponsor to operate delivery.
