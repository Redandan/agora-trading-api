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
Canonical status supplies the exact 24-hour registration deadline, signed time
remaining, and the preserved measured result, so the cloud task never derives
the SLA from conversation history or its own clock.
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

The scheduled Work surface currently has no supported tool that can write into
an arbitrary existing Codex task id. A material sealed event must therefore be
returned in the single scheduled chat as a user-visible outbox with
`delivery_status=CROSS_TASK_DELIVERY_PENDING`, the Coach task id, and the sealed
artifact hash as its delivery id. The OAuth status `coach_outbox` re-verifies
each artifact and supplies the structured event; Work must not reconstruct it
from prose or unverified chat history. Scheduled/Activity output or a device
notification is not direct Coach-task delivery. The Coach task re-verifies the
canonical event and artifact hash when it next runs. Until a supported
cross-task delivery tool exists and succeeds, the next-cycle Coach-thread SLA
remains `MISSING_PROOF`; this gap must not be hidden by adding a second timer or
an unapproved messaging service.

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
