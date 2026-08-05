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
still awaits a real evidence-ready trigger. The second target is proven through
sealed report/event construction, but direct delivery into the separate Coach
Codex task is not currently available from the cloud Work surface. Material
events remain visible in the one scheduled chat as a hash-identified
`CROSS_TASK_DELIVERY_PENDING` outbox. This is not counted as Coach-thread
delivery and does not authorize a second schedule or external messenger.

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

Each prospective UTC day is accepted only through the fixed
`FORWARD_EVIDENCE_DAY` contract. The canonical writer derives all 24 hourly
positions, validates time-grid and OHLC/volume integrity, refuses out-of-order
days, seals the normalized day, and extends a SHA-256 chain in trigger state.
The V3 capture window is six hours after UTC day close. A missing day becomes
`EVIDENCE_CAPTURE_MISSED`; later backfill cannot repair that trigger.

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
7. deploy the independent Research Worker and verify the old timer remains
   disabled.
8. Start the prospective evidence cycle only through a separately authorized,
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
- a malformed, late, duplicate-with-different-content, or out-of-order UTC day
  cannot enter the evidence chain;
- canonical status exposes sealed count, lag, chain head, and next capture
  deadline before the 90-day review;
- an unbound producer is visible before evidence starts and cannot submit a day;
- an early heartbeat returns `NOT_DUE` without creating `pending.json`;
- a repeated due heartbeat returns the same queued/running request;
- a stale queue item remains auditable through a terminal run record;
- an abnormal stop or host reboot resumes the same in-flight request through
  the existing dispatch path, with bounded retries and no second timer;
- generated Coach briefings include an immutable artifact id and SHA-256;
- when direct cross-task delivery is unavailable, the single scheduled result
  exposes a complete hash-identified `CROSS_TASK_DELIVERY_PENDING` handoff and
  never claims it reached the Coach task;
- Worker release id, clean Git commit, and verified source-manifest hash are
  visible through the existing canonical status operation;
- either MCP write operation fails before queue mutation when release provenance
  is missing, invalid, tampered, or dirty;
- evidence-ready Codex output can register one frozen candidate within 24 hours
  without a manual local task;
- the candidate path rejects tampered evidence, omitted performance metrics,
  unsupported operations, oversized payloads, and concurrent queue replacement;
- the Research Worker has no Trading secrets or writable Production mounts;
- `agora-research-heartbeat.timer` remains disabled and the path unit remains
  an event consumer only.
