# Autonomous Research Acceleration V1

Status: `FORWARD_SOURCE_ACTIVE_AWAITING_FIRST_CANONICAL_DAY_AND_COACH_DELIVERY_PROOF`

This plan defines how the research program shortens the path to a credible
strategy candidate without weakening the V3 scientific or runtime boundaries.
It measures time to trustworthy decisions, not experiment count.

## Long-term objective

Build a durable autonomous research loop that continuously turns untouched,
causal evidence into one interpretable hypothesis at a time, rejects weak
branches quickly, and reports performance knowledge to the sponsor without
requiring the sponsor to operate research tasks.

The program targets:

- evidence-ready to a frozen registered experiment in no more than 24 hours;
- a sealed experiment outcome to a Coach briefing by the next cloud Ops cycle;
- one active experiment and at most three interpretable variants;
- zero gate relaxation, sealed-OOS reopening, or hidden terminal inventory;
- zero Trading runtime, database, order, fund, SHADOW, PAPER, or LIVE changes.

The first target is implemented and tested as a deterministic pipeline SLA but
still awaits a real evidence-ready trigger. The second target now has the V12
same-schedule-chat delivery contract: the sole cloud cycle copies each exact
canonical prompt into its current assistant output, and only a later normally
due cycle may acknowledge that event when the exact full prompt is visible in
a prior assistant message and the identical id remains canonical-pending.
Missing prior context remains hash-identified
`MISSING_PROOF_PRIOR_ASSISTANT_V12_PROMPT` debt; it does not authorize a second
schedule, messenger, inferred receipt, retry, or user relay.

The 24-hour target starts only after the frozen evidence trigger is actually
ready. It does not convert a 90-day prospective evidence requirement into a
shorter window and does not guarantee that a profitable strategy exists.

## Strategy-production funnel

```text
forward evidence
  -> verified evidence manifest
  -> mechanism-neutral diagnostic
  -> one deduplicated causal hypothesis
  -> frozen experiment manifest within 24 hours
  -> Design / Validation / sealed OOS
  -> REPORTED_NOT_ACTIVATED or scientific rejection
  -> sealed Coach briefing by the next Ops cycle
```

The funnel may end in `DATA_REJECT`, `LEAKAGE_REJECT`, `NO_CANDIDATE`, or
`OOS_FAIL`. Those outcomes preserve capital and prevent repeated mining of a
closed branch.

## P0 contracts

### Evidence integrity

`READY_FOR_HYPOTHESIS` requires exactly one typed forward-evidence manifest.
The deterministic validator derives the observation count from its inventory,
checks exact trigger/source/fingerprint binding, enforces contiguous complete
UTC days, verifies dataset and diagnostic hashes, and requires every frozen
integrity check to pass. A date alone or an unrelated artifact cannot make the
trigger ready.

For `COMPLETE_UTC_DAY`, readiness also requires coverage through the entire
frozen `review_not_before` boundary; reaching a smaller minimum count early
does not stop capture. Trigger validation rejects a minimum that cannot fit
inside the frozen complete-day window. When the final day is sealed, the same
network-denied canonical intake deterministically seals the normalized dataset,
mechanism-neutral market-path diagnostic, typed evidence manifest, and one
`READY_FOR_HYPOTHESIS` review. Unknown integrity-check names fail closed. This
step selects no strategy and computes no strategy PnL.

The 90-day V1 diagnostic is nevertheless machine-actionable: it evaluates only
two preregistered entry-admission mechanisms, applies fixed predictive support,
breadth, response, concentration, and split-window gates, and exposes at most
one passing mechanism. Zero passing mechanisms closes as a sealed
`NO_CANDIDATE_FORWARD_DIAGNOSTIC`; it does not invite parameter tuning.

Each prospective UTC day is accepted only through the fixed
`FORWARD_EVIDENCE_DAY` contract. The canonical writer derives all 24 hourly
positions, validates time-grid and OHLC/volume integrity, refuses out-of-order
days, seals the normalized day, and extends a SHA-256 chain in trigger state.
The V3 capture window is six hours after UTC day close. A missing day becomes
`EVIDENCE_CAPTURE_MISSED`; later backfill cannot repair that trigger.

An eligible missed `HYPOTHESIS_DISCOVERY` window may now recover only through
the existing heartbeat and `run_tick` lock. The predecessor remains immutable
evidence and closes with `MISSED_CAPTURE_WINDOW_NO_BACKFILL` only after one
deterministic successor trigger and its cloned source contract have both been
verified. The successor starts at the first UTC midnight strictly after that
heartbeat, preserves the predecessor's whole-day duration and scientific
constraints, and starts with zero observations, chain, dataset, diagnostic,
manifest, review, hypothesis, candidate, or performance claim. Its source
contract preserves the exact source, producer, transport, and artifact format
while continuing to deny Worker network, Worker database, and backfill.

Rollover is discovery-only. Candidate OOS, unbound sources, non-daily triggers,
non-missed windows, completed or review-ready lifecycles, tampered partial
successors, and incompatible predecessor links remain fail closed. A compatible
partial successor converges under the same store lock; no second successor,
command, queue, timer, service, writer, capture, or retry loop is introduced.
The successful heartbeat emits one hash-backed integrity/recovery event and the
fixed companion remains inactive until the successor's first complete UTC day
has actually closed.

Before the untouched start, one immutable source contract must bind the trigger
to a named producer and one-way transport while explicitly denying Worker
network access, Worker database access, and backfill. Without it, canonical
status is `EVIDENCE_SOURCE_UNBOUND`, not a misleading passive wait.

### Clock and queue

The server accepts a heartbeat request only when canonical `next_due` has
arrived and the queue is idle. Concurrent requests converge on one id. Queued
and running ids are observable immediately. A queue lease older than the frozen
policy limit is preserved as `STALE_RECOVERED` before one replacement request
may be admitted.

### Canonical status and reporting

The OAuth Research MCP is the canonical status path. A local `.research-state`
is diagnostic replica evidence and must be labelled as such. Weekly and monthly
MCP briefings are sealed under canonical state and return an artifact id,
relative path, and SHA-256 for the Coach handoff. Their response keeps the
artifact's embedded policy separate from the current canonical policy so a
pre-cutover report remains immutable but is visibly historical.

Canonical status also binds every decision to the deployed Worker release. It
must report a clean Git source commit plus a verified source-manifest hash from
the immutable release. Missing, malformed, tampered, or dirty release
provenance is an integrity blocker, not permission to continue from local
assumptions. Both MCP write operations enforce this before any queue mutation;
the cloud prompt is not the only guard.

Canonical status also reports the installed frozen cloud Ops contract id, the
intended platform recurrence `09:05 Asia/Taipei`, the unchanged canonical due
boundary `09:00 Asia/Taipei`, the 300-second nominal dispatch margin, and
byte-level SHA-256. V1 through V7 remain immutable history. The prepared V8
successor preserves the V7
margin that prevents a small platform early-fire from consuming the only daily
call before the server due boundary; it does not permit an early heartbeat or
add a catch-up timer. Each heartbeat and candidate submission must attest the
active server-canonical exact hash. Missing or altered
contract bytes and a stale/missing attestation
fail before either the research request queue or companion capture queue is
created. The live schedule definition is read back after a contract change,
because server-side caller attestation cannot by itself prove the opaque UI
prompt text.

### Evidence-ready candidate registration

After canonical status reaches `READY_FOR_HYPOTHESIS`, Codex may submit exactly
one `candidate-bundle.schema.json` object through the OAuth MCP. The bundle is
limited to 131072 UTF-8 JSON bytes and contains only a hypothesis plus its
matching frozen experiment manifest. The Worker re-verifies the trigger hash,
sealed review, evidence manifest, dataset/diagnostic hashes, observation count,
policy metrics and constraints, adapter capability, duplicate fingerprint,
active budget, and timestamps before any registration. A successful operation
ends at `PREREGISTERED`, closes the evidence trigger, and records the measured
evidence-ready lead time and 24-hour SLA result.
The eligible adapter copies canonical `candidate_context`, uses the exact
retained corpus, and creates a distinct future candidate-bound OOS trigger.
Partial registration is resumable only when every sealed identity still
matches. Candidate OOS remains opaque until its full window is sealed.
Canonical status exposes the exact registration deadline and signed seconds
remaining before submission, then preserves the measured `PASS` or `BREACH`.
After the experiment and candidate-bound OOS source contract are fully frozen,
their state also preserves `candidate_frozen_at`. Recovery from a later
discovery-finalize interruption validates the original context against that
time and derives the lead from it, so elapsed retry time cannot rewrite the
24-hour result or shift the OOS window.
The same canonical status exposes `candidate_registration_recovery` when one
failed request matches partial canonical registration. It returns the exact
hash-verified bundle for one replay; a repeated failure, multiple recoverable
payloads, or partial-state drift fails closed and withholds the bundle. The MCP
candidate-write preflight also rejects a different payload while replay is due
and rejects a third submission after that single replay fails.
For every normal new candidate, that preflight also requires the named
canonical trigger to be `READY_FOR_HYPOTHESIS`, its readiness timestamp to
match the hash-verified latest ready review, and exactly one verified evidence
manifest in canonical state. Missing, waiting, closed, incomplete, or tampered
readiness stops before queue creation. Only the exact hash-bound recovery replay
may bypass this normal readiness check because partial registration can already
have closed the trigger.

The queue accepts only `RESEARCH_HEARTBEAT` and
`REGISTER_CANDIDATE_BUNDLE`. It exposes no shell command, environment override,
server path, runner execution, or activation field. Identical candidate
submissions converge while active and return the sealed prior result after
completion.

No extra review tool or queue operation is required. If the review date arrives
before the final companion capture has been ingested, canonical progress stays
capture-first. A legacy complete-but-unreviewed state is finalized by the next
existing heartbeat operation using the same deterministic artifacts.

## Delivery order

1. Enforce the evidence manifest and observation/integrity gates.
2. Seal each complete forward UTC day into an append-only hash chain and expose
   count, lag, next day, and capture deadline in canonical status.
3. Enforce heartbeat due/idempotency and stale queue recovery.
4. Seal MCP briefings and make MCP status canonical-first.
5. Add focused contract tests for these control-plane boundaries.
6. Add the bounded AI-to-canonical candidate registration channel.
7. Bind hash-deduplicated Coach delivery to exact current-turn rendering and
   later prior-assistant-context proof in the same schedule chat.
8. Deploy the independent Research Worker and verify the old timer remains
   disabled.
9. Start the prospective evidence cycle only through a separately authorized,
   research-only source contract.

## Current source boundary

Policy V3 still denies the Research Worker exchange credentials, network and
database access, and all external imports/backfills. The sponsor separately
authorized one credential-free public OKX forward source on 2026-08-04. It runs
under its own identity, accepts no caller-selected URL/symbol/interval/path,
and hands a hash-bound bundle to a network-denied canonical intake. If the
current trigger's `evidence_start` is missed, preserve it and close it as an
integrity failure; this authorization never permits backfill.

The authorized isolated, no-second-timer source boundary is specified in
`docs/server-forward-evidence-source-v1.md`. The existing cloud heartbeat
automatically emits its deterministic companion capture request only when the
canonical next day is due.

The deterministic intake command is:

```text
python -m research_pipeline ingest-evidence-day <trigger-id> <day-bundle.json>
```

The source must first be frozen with:

```text
python -m research_pipeline register-evidence-source-contract <trigger-id> <source-contract.json>
```

Its input must match `research_pipeline/evidence-day.schema.json`. This command
is not a source connector and does not authorize an MCP upload, exchange call,
database query, or backfill.

## Acceptance

- focused evidence and durable-queue contract tests pass;
- an untyped or under-count evidence review cannot become ready;
- the final complete UTC day seals one deterministic diagnostic, typed manifest,
  and review without a local writer or additional timer;
- a review date cannot skip a still-due final capture, and an impossible
  complete-day minimum/window contract is rejected at registration;
- a missed discovery window can create only one strictly future,
  zero-observation successor with a pre-start DENY-backfill source contract;
- rollover preserves and closes the predecessor without copying observations,
  rejects candidate OOS and incompatible partial state, and emits one deduped
  existing-heartbeat recovery event;
- a malformed, late, duplicate-with-different-content, or out-of-order UTC day
  cannot enter the evidence chain;
- canonical status exposes sealed count, lag, chain head, and next capture
  deadline before the 90-day review;
- canonical status correlates source capture and network-denied intake as one
  `evidence_capture_health` state, including retries, terminal failures,
  dispatch stalls, matched hashes, and sealed completion;
- after queueing a companion capture, the same cloud cycle observes that
  canonical health for a bounded interval, remains silent on `SEALED`, and
  surfaces a fail-closed alert without adding a timer or attempting backfill;
- an unbound producer is visible before evidence starts and cannot submit a day;
- an early heartbeat returns `NOT_DUE` without creating `pending.json`;
- a repeated due heartbeat returns the same queued/running request;
- a stale queue item remains auditable through a terminal run record;
- an abnormal stop or host reboot resumes the same in-flight request through
  the existing dispatch path, with bounded retries and no second timer;
- generated Coach briefings include an immutable artifact id and SHA-256;
- the sole cloud cycle renders only the exact V12 canonical delivery prompt in
  its existing schedule chat and requires that exact full prompt in a prior
  assistant message before a later normally due receipt;
- when prior assistant context is missing, the event remains hash-identified
  `MISSING_PROOF_PRIOR_ASSISTANT_V12_PROMPT` debt and is never acknowledged from
  a token alone, current-turn output, V11 prompt, summary, or user quote;
- canonical status supplies that handoff through a bounded read-only outbox and
  fails closed when any event artifact path or hash cannot be re-verified;
- Worker release id, clean Git commit, and verified source-manifest hash are
  visible through the existing canonical status operation;
- either MCP write operation fails before queue mutation when release provenance
  is missing, invalid, tampered, or dirty;
- the same two operations fail before queue mutation when the versioned cloud
  Ops contract is missing or altered, or the caller omits/mismatches its
  canonical SHA-256 attestation;
- evidence-ready Codex output can register one frozen candidate within 24 hours
  without a manual local task;
- an isolated 90-day passing-mechanism rehearsal produces one sealed Coach
  material event, one canonical candidate context, one idempotent
  `PREREGISTERED` experiment inside the SLA, and one distinct unopened
  `CANDIDATE_OOS` trigger without executing a runner;
- the candidate path rejects tampered evidence, omitted performance metrics,
  unsupported operations, oversized payloads, and concurrent queue replacement;
- a missing, waiting, closed, incomplete, or tampered evidence trigger is
  rejected by the MCP write preflight without creating a candidate queue item;
- the Research Worker has no Trading secrets or writable Production mounts;
- `agora-research-heartbeat.timer` remains disabled and the path unit remains
  an event consumer only.

## Historical V11 cross-task and failure-lifecycle contract

Fresh server-canonical status at `2026-08-29T03:40:47Z` reports deployed
Worker release `20260829T033523Z`, source commit
`4564325b0e31dd420aab6329ff08683e07b90c07`, and
`CLOUD_OPS_SCHEDULE_V11` `READY` with one daily recurrence. Exact platform
readback retains the existing ChatGPT Work task as the sole cloud clock; the
desktop automation remains paused.

V11 succeeded `CLOUD_OPS_SCHEDULE_V10`, exact SHA-256
`90e0de95fa34beff9447640a5dcdbb972278014664806df0a4bf5f36e2598faa`.
V10 remains immutable cross-task delivery history and is not an alternate
active contract or fallback path.

The historical failure-lifecycle contract is `CLOUD_OPS_SCHEDULE_V11`, frozen at
exact SHA-256
`9b30c944f2a7d3d1d23a7b01a87eb72dadb1368749039e6ea279c1b07be37c61`.
It keeps the scientific gates, one 09:05 cloud clock, 09:00 canonical due
boundary, 300-second margin, Server Canonical single writer, durable outbox,
five-field receipts, and the 10,800-second delivery SLA.

V11 preserves V10's receipt ordering and heartbeat/delivery decoupling while
scoping any failure to the current occurrence.
After proving the heartbeat is due, the cycle delivers and readback-verifies
the initial pending snapshot before the heartbeat, so those receipts can be
accepted in that same due cycle. Events newly created by the heartbeat are
delivered after it and wait only until the next normal cycle for receipt ACK.
Tool or host unavailability remains a hash-identified
`CROSS_TASK_DELIVERY_PENDING` event and does not authorize a timer, messenger,
user step, paid API, or inferred ACK.

Cutover reused the exact existing ChatGPT Work task and created no second
schedule. V11 implementation, focused tests, Worker deployment and canonical
attestation are proven. The first natural V11 occurrence failed closed without
retrying or disabling the schedule; its reader fix is deployed. A future
`next_run_time`, the next natural successful evidence continuation, live Coach
receipt acceptance, SLA result, and any quantified learning-latency or economic
benefit remain separate `MISSING_PROOF` gates.
Immediate PnL and drawdown effect are
zero.

## Authorized V12 same-schedule Coach delivery

The successor `CLOUD_OPS_SCHEDULE_V12`, exact SHA-256
`98cc2374961fb37c00a8396e6bd8126b7b39a32d7d85ea0e0fcd30c2b9c7fc0c`,
preserves V11's lifecycle, one clock, Server Canonical writer, research gates,
evidence progression, receipt schema, delivery SLA, and sealed OOS boundary.
It replaces only the inaccessible Coach task with the exact existing schedule
chat `6a71a167-be58-83ec-aed2-f1736e31dd45` after fresh inventory proved the
former target unavailable.

V12 has no Codex task operations. Turn N renders the exact full canonical V12
prompt as assistant output. Turn N+1 may include a five-field receipt only when
that exact prompt is visible in a prior assistant message and the identical id
remains pending in fresh canonical state. Current-turn output, V11 or earlier
prompts, token-only matches, summaries, truncations, user quotes, notifications,
and inferred context are insufficient. The 12-event backlog retains its ids,
queue/deadline timestamps, and existing breach results. Immediate PnL and
drawdown effect remain zero; live render, receipt acceptance, monotonic drain,
and quantified learning latency remain `MISSING_PROOF` until natural cycles.
