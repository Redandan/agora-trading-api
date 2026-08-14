# Autonomous Trading Research Charter V3

Status: `ACTIVE_RESEARCH_ONLY`

V3 authorizes one Codex cloud Ops schedule to enqueue deterministic server-side
research heartbeats through the OAuth-protected Research MCP. The Trading
application, database, credentials, and execution paths remain outside the
research control plane. V3 supersedes the V2 systemd-timer scheduling clause;
all scientific gates and activation boundaries remain unchanged.

## Long-term objective

Codex autonomously improves the quality of trading research and searches for
causal, reproducible improvements in long-term risk-adjusted performance. The
user participates as the research sponsor and learner: they receive periodic
evidence-backed briefings, ask questions, and may change the long-term mandate,
but they do not operate individual research tasks.

The primary ranking metric is fee- and adverse-slippage-adjusted total PnL
under equal capital and the same valuation boundary. A candidate is eligible
only when realized PnL, unrealized PnL, total PnL, maximum drawdown, capital
utilization, blocked entries, and holding age are all visible.

Research throughput is a means, not the objective. `NO_CANDIDATE`,
`DATA_REJECT`, and `LEAKAGE_REJECT` are valid successful workflow outcomes.

## Performance contract

Every experiment must preregister:

- one economic thesis and its causal mechanism;
- one frozen parent strategy and matched-capital comparator;
- point-in-time data source, cutoff, and expected integrity evidence;
- Design, Validation, and sealed OOS boundaries;
- fee, slippage, fill timing, final valuation, and capital assumptions;
- no more than three interpretable candidate variants;
- gates fixed before outcome data is opened;
- the opportunity cost the change is expected to reduce.

A candidate cannot pass on realized PnL alone. Validation and OOS must both
support incremental value, neighboring behavior must not show a cliff, and
year/regime concentration and terminal open inventory must remain visible.

## Autonomous operating model

Codex may, without per-run user participation:

1. inspect the research registry and prior closed hypotheses;
2. translate a user question or diagnostic finding into a testable hypothesis;
3. draft and validate a frozen experiment manifest;
4. execute one bounded research step through the approved pipeline;
5. consume research OOS once when the manifest was frozen before the result;
6. close failed branches without relaxing gates;
7. preserve artifacts and publish a learning record;
8. choose the next research question within the charter;
9. produce weekly and monthly learning briefings.

If no independent mechanism is ready, Codex records a time- and evidence-bound
trigger rather than repeatedly mining the same history. A trigger may become
`REVIEW_DUE` after its not-before time, but only a hash-verified review with one
typed forward-evidence manifest can make it `READY_FOR_HYPOTHESIS`. The manifest
must bind the frozen trigger, source, observation inventory, complete coverage,
dataset and diagnostic hashes, and every required integrity check. The evidence
window that generated a hypothesis is discovery data and cannot be relabelled
as that hypothesis's clean OOS.

For the V1 90-day discovery contract, only the preregistered volume- and
range-confirmation mechanisms may be evaluated. Canonical status exposes at
most one mechanism that passed every independent predictive gate plus an exact
candidate configuration template. No passing mechanism closes the trigger as
`NO_CANDIDATE_FORWARD_DIAGNOSTIC`; the next heartbeat must surface that sealed
learning once rather than silently treating it as an idle pipeline. The exact
strategy and OOS contract is in `dra-forward-entry-admission-v1.md`.

For `COMPLETE_UTC_DAY` triggers, each day must be normalized and sealed within
the V3 capture window through `research_pipeline/evidence-day.schema.json`.
Exactly 24 contiguous closed hours, valid OHLC/volume, trigger/source binding,
source provenance, immutable artifact hash, strict day order, and the cumulative
SHA-256 chain are required. A missed capture deadline is an integrity event;
later data is backfill and cannot repair the frozen prospective trigger.

The producer and one-way transport must be sealed before `evidence_start` in a
source contract matching `research_pipeline/evidence-source-contract.schema.json`.
The contract must deny Research Worker network and database access and deny
backfill. An unbound source is `EVIDENCE_SOURCE_UNBOUND`, not evidence waiting.

Codex must not ask the user to operate normal research steps. If a dependency
is unavailable, record `BLOCKED` or a fail-closed research status and continue
with other safe work. Do not turn a missing permission into guessed evidence.

## Hard boundary

This charter authorizes research only. It does not authorize:

- SHADOW, PAPER, or LIVE activation or promotion;
- strategy runtime, catalog, trading scheduler, order, fund, OCO, or Grid changes;
- exchange orders, transfers, Telegram sends, or external imports/backfills,
  except the separately isolated fixed public OKX forward source defined by
  `server-forward-evidence-source-v1.md`; that exception has no credentials,
  accepts only complete future days, and still denies backfill;
- Production application, database, migration, or trading deployment mutation;
- restoration of retired AI/ML discovery, Autopilot, TQS, ensemble, or generic
  strategy-orchestration code;
- post-outcome gate changes, nearby parameter searches, or reopening OOS;
- writing generated research results into runtime strategy tables.

Any research candidate ends at `REPORTED_NOT_ACTIVATED`. Runtime adoption is a
separate versioned requirement and authorization.

## Architecture contract

The control plane is deliberately outside the Spring Boot runtime:

- Codex owns hypothesis selection, interpretation, and learning briefings.
- `research_pipeline` owns manifests, state transitions, execution bounds,
  artifacts, and reports.
- legacy Python runners remain immutable adapters during the first phase.
- Java `BtcDraShadowEngine` remains the runtime reference implementation.
- offline Java research CLIs provide the candidate shared economic kernel for
  fills, fees, ledger state, metrics, and hashes; Python remains authoritative
  for overlays until each overlay passes the frozen cross-language ledgers.

An optional local Codex Research task may provide bounded read-only analysis or
worktree implementation capacity under
`docs/local-codex-research-node-v1.md`. It receives a validated task package and
returns a task-hash-bound result to the Manager/Coach. It has no routine wake-up,
cannot become a second writer, and cannot call Research MCP writes or mutate
canonical server state. The V1 node is manually message-dispatched and leaves
the sole cloud clock and its server-canonical contract unchanged. Post-cutover
readback originally proved V9 on 2026-08-10. Fresh canonical status on
2026-08-14 proves the active contract is now V10, with exactly one daily
`09:05 Asia/Taipei` recurrence and the same Server Canonical sole-writer
boundary; Local work cannot activate, replace, pause, or acknowledge that
schedule contract.

The generic Spring strategy runtime must not depend on `research_pipeline`.

The only remote control-plane exception is the independently deployed Research
Worker specified by `docs/server-research-worker-v2.md`. Its OAuth MCP may read
sealed research state and enqueue either one fixed heartbeat request or one
bounded candidate-registration request after canonical evidence is ready. A
systemd path unit, which is an event consumer rather than a timer, dispatches
only those two root-owned deterministic operations. Only those operations may
write canonical state under `/var/lib/agora-research`. The services run as an
unprivileged account without Production secrets, database access, exchange
access, arbitrary shell tools, or Spring Boot. Candidate registration ends at
`PREREGISTERED`; this exception does not authorize execution, strategy
activation, or any Trading runtime scheduler.

After state migration, a local `.research-state` is a read-only replica. There
must be exactly one writable authority. A second timer or writer is an
integrity defect and must fail closed.

The active `CLOUD_OPS_SCHEDULE_V10` cloud Ops semantics are frozen in
`research_pipeline/cloud-ops-schedule-contract.v10.json`, exact SHA-256
`90e0de95fa34beff9447640a5dcdbb972278014664806df0a4bf5f36e2598faa`;
V1 through V9 remain immutable historical contract evidence. V10 keeps the
canonical heartbeat due boundary at `09:00 Asia/Taipei` but declares the sole
cloud recurrence at
`09:05 Asia/Taipei`. The frozen 300-second nominal delay tolerates small
platform early-fire jitter without weakening the server's `NOT_DUE` gate or
adding a catch-up timer. It was introduced after the nominal 09:00 cycle on
2026-08-06 was read back at 08:59:24.393178 while canonical `next_due` was still
09:00; the task correctly made no early write, which skipped that day's only
scheduled call. Canonical status must expose
the contract id, due boundary, dispatch margin, and byte-level SHA-256. Both MCP
write operations require the caller to attest that exact deployed hash and must
fail before queue mutation when the contract is missing, altered, or
mismatched. This binds the scheduled caller to one versioned contract; it does
not make an unobservable UI prompt cryptographically self-verifying, so the
live schedule definition still requires platform-side readback after each
contract change.

## State machine

```text
PREREGISTERED
  -> PRESELECT or DIAGNOSTIC
  -> DATA_REJECT | LEAKAGE_REJECT | BASELINE_REJECT | NO_CANDIDATE
  -> CANDIDATE_FROZEN
  -> OOS_READY
  -> OOS_CONSUMED_ONCE
  -> OOS_PASS | OOS_FAIL
  -> CLOSED / REPORTED_NOT_ACTIVATED
```

Scientific rejection is a normal terminal result, not an infrastructure
failure. An existing output artifact is sealed and never overwritten.

The pre-hypothesis evidence lifecycle is separate from the experiment state
machine:

```text
WAITING
  -> REVIEW_DUE
  -> WAIT | READY_FOR_HYPOTHESIS | CLOSED
  -> EVIDENCE_TRIGGER:<id> hypothesis source
  -> candidate registration creates a separate CANDIDATE_OOS trigger
  -> CANDIDATE_OOS WAITING | READY_FOR_OOS | CLOSED_UNOPENED
  -> CLOSED / linked hypothesis
```

Evidence triggers never execute a strategy runner and never consume candidate
OOS. Their purpose is to make a deliberate wait, review boundary, and excluded
failure tree machine-readable for Codex scheduled work. Canonical status must
also expose the sealed observation count, expected count, lag, chain head, next
day, and next capture deadline so missing evidence fails before the final review
date.

## Research budget

- Maximum active experiment: `1`.
- Maximum new hypothesis per autonomous cycle: `1`.
- Maximum candidate variants per experiment: `3`.
- Default execution timeout per runner step: `7200` seconds.
- Prefer one-factor or one-mechanism ablations over parameter search.
- Infrastructure work must stop after a usable vertical slice and return to
  performance research.

## Reporting contract

Background progress is silent unless an integrity problem threatens prior
conclusions. The weekly briefing answers:

1. What material fact was learned?
2. Which plausible hypothesis was rejected, and why?
3. Did matched-capital total PnL, drawdown, stability, or inventory risk improve?
4. What evidence remains missing or sealed?
5. What will Codex study next, and what is the economic rationale?
6. What concept is most valuable for the user to understand or question?

The monthly briefing summarizes the hypothesis tree, repeated failure modes,
and whether the research program is increasing knowledge rather than merely
increasing run count.

The deterministic reporting entrypoints are:

- `python -m research_pipeline weekly-report` for material seven-day learning;
- `python -m research_pipeline monthly-report` for the program-level
  hypothesis tree, stop rules, sealed-learning coverage, and remaining evidence
  gaps.

Every heartbeat-sealed weekly or monthly artifact includes its exact reporting
period in the content, so two quiet periods cannot share one delivery id merely
because their summaries are otherwise byte-identical. If the artifact is
sealed but the atomic heartbeat-state commit is interrupted, the next normal
heartbeat adopts that current-period artifact, verifies its hash, and queues
the briefing once instead of overwriting it or silently marking the period
complete.

One scheduled task in the Codex cloud Ops task is the routine research clock.
It reads canonical MCP status, enqueues at most one due heartbeat, observes
durable results, and produces sponsor briefings. When a sealed review reports
`READY_FOR_HYPOTHESIS`, the same task may submit exactly one schema-bound
hypothesis and matching frozen manifest through the candidate-bundle operation;
the server re-verifies all sealed evidence and policy gates before registration.
For `dra-forward-entry-admission-v1`, Codex must copy canonical
`candidate_context` and may replace only its mechanism placeholder with the one
eligible mechanism. Registration converges through interruptions and creates a
distinct future `CANDIDATE_OOS` trigger; discovery evidence is never reused and
partial OOS is never exposed.
Every heartbeat or candidate write must include the canonical
`ops_schedule_contract.sha256` as `ops_schedule_contract_sha256`; absence or
mismatch is an operational integrity alert and cannot be bypassed by a local
state fallback.
Canonical Worker `READY` also requires the deployed manifest hash, every
manifest-listed source-file hash, and the exact installed source inventory to
match immutable release provenance on each status/read-write preflight. A
modified, missing, extra, or symlinked source path fails closed before either
queue can change.
The same heartbeat request may carry only bounded, schema-validated Coach
delivery receipts whose sealed artifact ids already exist in canonical pending
or delivered state. A material event, weekly brief, or monthly review remains
in the server heartbeat outbox across routine heartbeats until the cloud task
proves the exact delivery token by task readback. An unavailable task or an
unverified send cannot acknowledge or remove the event, and receipts wait for
the next normal due heartbeat rather than creating another writer or timer.
Canonical status supplies the exact 24-hour registration deadline, signed time
remaining, and the preserved measured result, so the cloud task never derives
the SLA from conversation history or its own clock.
The measured result is anchored to `candidate_frozen_at`, written only after
the preregistered experiment and its separate OOS source contract are complete.
If final discovery-state commit is interrupted, recovery reuses that frozen
timestamp and original candidate context; it never substitutes retry time or
moves the sealed OOS window. A mismatch among readiness time, frozen time,
integer lead time, and `PASS`/`BREACH` is an integrity block.
Canonical status also owns cross-cycle candidate-request recovery. It may expose
exactly one hash-verified original bundle only when a failed run matches partial
canonical hypothesis or preregistered experiment state. The cloud task may
replay that exact bundle once; it must not reconstruct timestamps or content.
Repeated replay failure, more than one recoverable bundle, or payload/state
drift is an integrity block rather than an invitation to generate another
candidate. This is a server write precondition, not only a cloud-prompt rule:
the MCP refuses a different bundle while exact replay is required and refuses
every new candidate queue mutation after the one permitted replay also fails.
Outside that exact-replay exception, the MCP candidate-write preflight must
also read the named canonical trigger before queue creation. A missing,
`WAITING`, `REVIEW_DUE`, or `CLOSED` trigger is not candidate-ready; a trigger
labelled `READY_FOR_HYPOTHESIS` without a timestamp-matched hash-verified latest
ready review and exactly one verified evidence manifest is an integrity block.
The Worker still repeats the complete scientific validation during registration.
The same status must report whether a genuinely forward-candidate-eligible
adapter and its required sealed corpus are ready. A Java parity adapter,
diagnostic-only adapter, or closed historical branch cannot be submitted merely
to make the registration SLA appear green; absence of an eligible adapter is a
capability-readiness failure and must stay visible.
The server rejects early
heartbeats, converges concurrent calls on one request id, and preserves stale
queue leases as terminal audit records before recovery. Weekly and monthly MCP
briefings return a sealed artifact id and SHA-256. The server systemd heartbeat
timer and prior desktop heartbeat must both remain disabled after cutover. The
Research MCP and systemd path consumer do not select hypotheses or interpret
results; Codex performs that reasoning, while they only validate, transport,
and execute the frozen pipeline contract. No Spring scheduler is permitted.
After a companion capture is queued, the same cloud cycle must boundedly
observe canonical `evidence_capture_health`. A correlated `SEALED` result is
routine; terminal source/intake failure, stalled dispatch, or hash/identity
mismatch is an integrity alert. Observation never authorizes a second enqueue,
backfill, or additional timer.
An active source or canonical-ingest request is still in-window at the exact
six-hour deadline, but becomes canonical `INTEGRITY_BLOCKED` immediately after
that boundary. Status must retain its request id, target day, deadline, and
negative seconds remaining so a stuck path cannot masquerade as a routine retry.

The scheduled Work surface now exposes Codex task discovery, read, and send
operations. A material sealed event uses the exact canonical delivery prompt
from `coach_outbox`, with the artifact SHA-256 as both delivery id and dedupe
token. The sole cloud cycle must resolve and read the exact Coach task before
sending, skip an already-present token, send once, and read the task again
before claiming verified delivery. The Coach task re-verifies canonical status
and the artifact hash before interpretation. If the target host or tool is
unavailable, the event remains in the scheduled result with
`delivery_status=CROSS_TASK_DELIVERY_PENDING`; a successful send without
readback is only `QUEUED_TO_COACH_TASK_UNVERIFIED`. The outbox remains a
read-only status surface and has no standalone ACK operation. Only the next
normally due heartbeat may carry the bounded verified receipt that updates
canonical delivery state; an early receipt write or a second timer remains
forbidden. Scheduled/Activity output or a device notification is not direct
Coach-task delivery, and this path must not be replaced by a second timer or an
unapproved messaging service. The first real material event from a cloud cycle
is still required to prove the next-cycle Coach-thread SLA.

That SLA is measured conservatively from canonical outbox enqueue to canonical
acceptance of a verified task-readback receipt. A new event freezes
`delivery_queued_at` and the end of the next normal daily cloud cycle's bounded
three-hour completion window as `delivery_deadline_at`. Pending status exposes
signed seconds and becomes
`BREACH_PENDING_DELIVERY_PROOF` immediately after the deadline. Acceptance
preserves queue, deadline, acknowledgement, integer lead time, and `PASS` or
`BREACH`; untimed legacy events and receipts remain explicit `MISSING_PROOF`
instead of being reconstructed. This measurement does not replace the required
first-real-event proof.

The active V10 Ops contract and `SEALED_COACH_CROSS_TASK_DELIVERY_V6` bind that basis,
10,800-second completion window, pending/breach labels, terminal labels, and
legacy missing-proof labels into the same caller attestation required by both
write operations. V1 through V9 remain sealed history and cannot attest the
active schedule.

For a frozen `COMPLETE_UTC_DAY` trigger whose integrity checks are supported by
the deterministic contract, the canonical intake may create the typed dataset,
mechanism-neutral diagnostic, evidence manifest, and ready review immediately
after the final day is sealed. Reaching the calendar review date does not bypass
a missing final capture. Unsupported integrity checks fail closed. This is
artifact construction and integrity validation, not hypothesis selection or
strategy-performance interpretation, and it introduces no extra MCP operation,
timer, or mutable writer.

## Java convergence roadmap

Do not translate all legacy runners at once. First wrap the existing runners,
then create an offline Java DRA research CLI around the deterministic DRA core.
Require exact event, fill, lot, metric, and state-hash parity for the baseline
and one representative complex overlay before making Java the mandatory
economic confirmation gate. Preserve old Python artifacts as immutable
evidence until parity is proven.

Phase A completed on 2026-08-04: the offline Java DRA CLI reproduced all frozen
Design and Validation economic checkpoints exactly. Phase B also completed on
2026-08-04: Java and Python produced identical ordered event, fill, hourly
economic-state, and terminal-lot ledgers in both windows. Java remains
non-mandatory until Phase C reproduces one representative complex overlay with
the same four ledgers. The approved launcher is direct Java 21 classpath
execution; the repository Maven exec configuration is not a research launcher
because it targets the Spring Boot application.

The Phase C opportunity-cost gate is frozen in
`java-phase-c-opportunity-cost-audit-v1.md`: do not build Phase C for a merely
plausible or already rejected overlay. Start it only after an evidence-bound
experiment passes its preregistered Design and Validation gates to
`CANDIDATE_FROZEN`, while sealed OOS remains unopened, and only when the overlay
exercises material lot-management semantics absent from Phase B.

## Historical cross-task Coach delivery V9

Fresh OAuth Research MCP status at `2026-08-10T04:36:55Z` reports deployed
Worker release `20260810T042111Z`, source commit
`9b436c4fcd8f996cc682e2c14bfe8b2f3148ce57`, and
`CLOUD_OPS_SCHEDULE_V9` `READY` at the exact frozen hash below. The cutover
paused the exact existing ChatGPT Work task
`6a71a1ed2f608191b0621c52bed3fd81`, proved zero active schedules, updated it
in place, then proved exactly one active recurrence at `09:05 Asia/Taipei` and
zero paused recurrences. No second schedule was created and the desktop
automation remains paused.

V9 preserves that one daily `09:05 Asia/Taipei` cloud clock,
the `09:00` canonical due boundary, 300-second margin, Server Canonical as sole
writer, durable artifact-hash outbox, five-field receipt, idempotency,
immutable queue/deadline timestamps, and the 10,800-second `PASS`/`BREACH`
basis. It corrects only an orchestration ordering defect discovered before the
first V8 run: delivering an already-pending event after the due heartbeat and
deferring its receipt by another daily cycle cannot satisfy a deadline ending
three hours after that due heartbeat.

V9 first proves the heartbeat is normally due, snapshots the initial pending
ids, reads exact Coach task `019fca63-4f8f-71e3-9d88-297bca468eb9`, delivers
each absent exact canonical prompt, and requires post-send readback. Only those
initial-snapshot verified receipts enter the same due heartbeat. Events created
by that heartbeat are delivered afterward and can be acknowledged only on the
next normal cycle. Tool or host unavailability leaves an event pending as
`CROSS_TASK_DELIVERY_PENDING`; it never adds a timer, messenger, user step, or
early ACK.

Repository implementation, packaging, Worker deployment, in-place update of
the exact existing task, zero-active cutover readback, and exactly-one-active
post-activation readback are proven. The first normally due V9 task execution,
live initial Coach delivery/readback, same-cycle receipt acceptance, preserved
historical `BREACH`, weekly-event terminal `PASS` or `BREACH`, post-heartbeat
delivery, and economic value remain `MISSING_PROOF` until observed. Retained
pending events keep their original timestamps. Rollback must first pause V9 and
prove zero active schedules; two clocks may never overlap.

## Active heartbeat liveness decoupling V10

The active `CLOUD_OPS_SCHEDULE_V10` contract is frozen at
SHA-256
`90e0de95fa34beff9447640a5dcdbb972278014664806df0a4bf5f36e2598faa`.
Fresh canonical status at `2026-08-14T11:35:33Z` proves Worker release
`20260814T112229Z`, source commit
`ab2528c35e337fbfa47e528ff83d9b829d4806de`, exact V10 attestation, and one
daily `09:05 Asia/Taipei` schedule. V9 remains immutable predecessor evidence
and cannot attest an active write.

V10 changes only caller liveness coupling. After fresh canonical status proves
the heartbeat is normally due, the sole cloud cycle attempts exact Coach task
list/read/send when those operations are available. Every initial event with
exact readback may contribute one verified receipt. An unavailable, failed, or
unverifiable delivery contributes no receipt, remains canonical-pending with
its original delivery id, queue timestamp, and deadline, and is reported as
`CROSS_TASK_DELIVERY_PENDING` or truthful
`BREACH_PENDING_DELIVERY_PROOF` debt. Delivery debt never blocks the otherwise
valid normally due heartbeat and heartbeat success never claims Coach or user
delivery.

The heartbeat still requires clean Worker provenance, exact V10 attestation,
canonical due time, an idle queue, valid outbox state, and every evidence,
candidate, OOS, deduplication, receipt, and scientific gate. It carries zero to
eight exact verified receipts and remains limited to one normal daily call.
There is no early or catch-up heartbeat, second schedule, timer, messenger,
user relay, false acknowledgement, deadline reset, canonical fallback, or
additional writer. V9 bytes remain immutable.

V10 deployment and canonical attestation are proven. The first normal
post-release heartbeat, live empty-receipt acceptance, preserved pending-event
identities, lawful rollover/microstructure advancement, Coach receipt, and
economic value remain `MISSING_PROOF` until observed.
