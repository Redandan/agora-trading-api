Use `$autonomous-trading-research` in this repository. Treat the OAuth Research
MCP as the canonical status and queue path. Never run a local `.research-state`
heartbeat or write a local evidence review after server migration. The server
Research Worker owns the single deterministic heartbeat and canonical state;
routine `WAITING_FOR_EVIDENCE` remains silent.

When and only when this prompt is running as the frozen V8 ChatGPT Work cloud
schedule, read `get_research_status` as the first operation. The exact Coach
task may be inspected later only for the bounded cross-task delivery flow in
this prompt. Do not inspect or send to any other task. Call
`request_research_heartbeat` only when
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

Treat the versioned cloud Ops schedule contract as a caller-attestation gate.
This repository contains lifecycle-neutral frozen document
`CLOUD_OPS_SCHEDULE_V8` with `document_status=FROZEN` and exact SHA-256
`7c3df0a2ecd0279ce48f2b58d12f84ce8757270e616ab85e1db173a5df2301d1`.
Its external repository rollout state is `PREPARED_NOT_ACTIVE_V8`; activation
is separately proven outside the immutable document and never edits its bytes.
Before activation, if
canonical status still reports `CLOUD_OPS_SCHEDULE_V7`, stop before every V8
write call; do not attest V8, fall back to an older hash, or infer cutover.

After separately proven external V8 activation, continue only when canonical
`ops_schedule_contract.status=READY`, `contract_id=CLOUD_OPS_SCHEDULE_V8`,
`schedule_count=1`,
`timer_authority=CODEX_CLOUD_OPS_ONLY`,
`recurrence.timezone=Asia/Taipei`, `recurrence.local_time=09:05`,
`recurrence.end=NEVER`,
`canonical_heartbeat_due.timezone=Asia/Taipei`,
`canonical_heartbeat_due.local_time=09:00`,
`dispatch_margin.scheduled_seconds_after_canonical_due=300`,
`dispatch_margin.early_call_behavior=NOT_DUE`,
`dispatch_margin.additional_timer=DENY`, and
`sha256=7c3df0a2ecd0279ce48f2b58d12f84ce8757270e616ab85e1db173a5df2301d1`.
Pass that exact hash as `ops_schedule_contract_sha256` on every
`request_research_heartbeat` and `submit_research_candidate_bundle` call.
Missing, invalid, or mismatched contract/attestation is an operational alert;
fail closed without queueing either operation.

V8 preserves the V7 placement of the one cloud recurrence five minutes after the
unchanged 09:00 canonical heartbeat due boundary so a small platform early-fire
jitter cannot skip the only daily cycle. This margin never authorizes an early
heartbeat: always compare canonical `next_due`, preserve `NOT_DUE`, and never
add a catch-up call, retry timer, or second schedule.

Inspect canonical `candidate_registration_recovery` before formulating or
submitting any new candidate. `IDLE` permits the normal evidence-ready flow.
`EXACT_REPLAY_REQUIRED` means a prior candidate request left hash-verified
partial canonical registration: require queue `IDLE`, copy the canonical
`bundle` byte-for-value without changing its timestamp, text, mechanism, OOS
window, or any other field, and call `submit_research_candidate_bundle` exactly
once with the normal V8 attestation. Verify that the canonical
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
Use the sealed SHA-256 as `delivery_id` and target Coach task
`019fca63-4f8f-71e3-9d88-297bca468eb9`.

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

Require `coach_outbox.delivery_contract.status=READY` and, only after V8 is
canonically active, the cross-task contract
`SEALED_COACH_CROSS_TASK_DELIVERY_V4`. Require its canonical
`delivery_proof_sla.completion_window_seconds=10800` and never substitute a
chat-side clock. For each event, copy the exact
canonical `delivery_prompt`; do not reconstruct it from chat or edit its JSON.
Use only `list_threads`, `read_thread`, and `send_message_to_thread`, and only
for exact target task `019fca63-4f8f-71e3-9d88-297bca468eb9`.

After fresh canonical status reports pending events, resolve the exact target
id with `list_threads`; fuzzy title, host, recency, or inferred matching is
forbidden. Read the exact Coach task before any send. For each event, require
the exact complete token
`SEALED_RESEARCH_DELIVERY:<64 lowercase hex delivery id>`. If the preflight
read already contains the token, build a receipt with
`delivery_status=ALREADY_DELIVERED_TO_COACH_TASK`. If it is absent, record that
fact but do not send before the normally due heartbeat. Pass only exact
preflight-proven receipts to that heartbeat, then read fresh canonical status
again. If the heartbeat is not due, make no write call and preserve the
receipts for a future fresh preflight; never cache proof across cycles.

After the normally due heartbeat and fresh status read, deliver every still-
pending event whose exact token was absent in this cycle's preflight, including
any event newly queued by that heartbeat. Send its exact canonical
`delivery_prompt` once, then read the exact Coach task again. Only when that
post-send read contains the exact full token may a receipt use
`delivery_status=DELIVERED_TO_COACH_TASK_VERIFIED`. That new receipt waits for
the next normally due heartbeat. A Scheduled inbox entry, notification,
summary, truncated or altered token, user quote, send response, or inferred
task state is not readback proof.

Every receipt object contains only `schema_version=1`, `delivery_id`,
`delivery_token`, `target_thread_id`, and `delivery_status`. The only permitted
statuses are `DELIVERED_TO_COACH_TASK_VERIFIED` and
`ALREADY_DELIVERED_TO_COACH_TASK`; never acknowledge
`QUEUED_TO_COACH_TASK_UNVERIFIED` or `CROSS_TASK_DELIVERY_PENDING`. A preflight-
verified receipt may be carried by the current normally due heartbeat; a new
post-send receipt may be carried only by the following normally due heartbeat.
Neither authorizes an early call. After a receipt-bearing heartbeat, re-read
canonical status and require the id to leave pending state. The server must
match each receipt to a pending or already acknowledged delivery id before
mutating either queue.

If task discovery, preflight read, send, or post-send readback is unavailable,
emit one complete hash-identified `CROSS_TASK_DELIVERY_PENDING` result in the
scheduled task, keep the canonical event pending, and retry only on a later
normal cloud cycle when the exact token remains absent. Never reset
`delivery_queued_at` or `delivery_deadline_at`, add a timer/poller/messenger,
create another schedule, or ask the Coach or sponsor to operate delivery.
