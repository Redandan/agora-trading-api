# Candidate Factory control-plane liveness V1

Status: V11 failure lifecycle live-proven; full liveness `MISSING_PROOF`

Acceptance snapshot: 2026-08-29

Authorization: `RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE`

## Outcome

The repository now has one deterministic, read-only admission check that keeps
the frozen schedule declaration separate from live control-surface proof. A
`READY` V11 contract and a completed platform task are no longer sufficient to
claim that the Candidate Factory clock is live or that canonical evidence
advanced.

## V11 live failed-occurrence acceptance

The first natural V11 occurrence ran on 2026-08-29. Platform and Server
Canonical evidence correlated it to request
`60faaacd76c1419ca73edcf5b283c220`. It failed closed at
`2026-08-29T01:06:47.636795Z` with sealed artifact
`heartbeat/failures/20260829T010647636795Z-1.json`, exact SHA-256
`4b49eeacf0105fce960527f26b327420ef43ea55087fbe19340581901f420a27`,
because a fresh rollover state legitimately omitted its zero-length
`evidence_observations` inventory while one frozen diagnostic reader required
an explicit list.

The failure did not retry, enqueue a candidate, run a strategy, open OOS, or
perform a Trading action. Canonical queues returned to `IDLE`, the failure
count became one, and canonical `next_due` advanced only to the next normal
boundary at `2026-08-30T01:00:00Z`. Fresh platform readback after the failure
showed the same schedule id still `ACTIVE`, `active_count=1`, `total_count=1`,
the exact V11 hash, unchanged daily 09:05 Asia/Taipei recurrence and no second
schedule. This is live proof of the V11 fail-closed-current-occurrence and
keep-enabled-clock lifecycle.

The platform still does not expose a future `next_run_time` or a typed terminal
occurrence status, so the full liveness audit remains `MISSING_PROOF` rather
than `READY`. The next natural cycle must prove that the corrected reader can
resume prospective evidence collection; the failed occurrence is never
retried or backfilled.

The validator requires all of the following at the same observation boundary:

- ready and identity-consistent Policy, Worker and V11 canonical contract;
- exactly one active `CODEX_CLOUD_OPS` clock with the frozen id, hash,
  recurrence, cloud Ops execution-task destination and a future
  `next_run_time`;
- exactly one independently inventoried active Server Canonical writer;
- a durable heartbeat queue claim plus canonical request id for any platform
  success label;
- canonical `HEARTBEAT_OK`; `HEARTBEAT_FAILED_CLOSED` remains a blocker even
  when its failure handler has already advanced `next_due`;
- a fresh canonical `next_due` effect after the due boundary;
- one coherent latest sealed capture/ingest pair;
- Coach delivery kept outside heartbeat and evidence liveness.

The clock destination and Coach delivery target are intentionally different
identities. The existing schedule runs inside cloud Ops task
`6a71a167-be58-83ec-aed2-f1736e31dd45`; optional delivery targets Coach task
`019fca63-4f8f-71e3-9d88-297bca468eb9`. Treating the Coach target as the
platform clock destination is an audit integrity defect, not evidence of a
schedule cutover or replacement.

Unknown fields, duplicate JSON keys, unsupported clock kinds, a second clock,
a second writer, stale cross-surface evidence, missing evidence history and
success without canonical effect all fail closed.

The readback and audit output structures are closed Draft 2020-12 JSON Schemas:

- `research_pipeline/cloud-ops-control-surface-readback.v1.schema.json`;
- `research_pipeline/cloud-ops-liveness-audit.v1.schema.json`.

Structural rules live in those schemas. The Python validator retains only
cross-field semantics such as distinct identities, UTC timing, exact contract
matching, zero overlap and canonical effect correlation.

## Retain

- Server Canonical as the only state writer.
- One research clock and zero-overlap cutover.
- V3 policy and historical V6, V7 and V10 sealed bytes.
- Preregistration, point-in-time data, matched-capital economics, permanent
  family tombstones, at most three variants and one-time sealed OOS.
- Candidate Funnel ranking as readiness and opportunity-cost information, not
  an alpha score.

## Replace

- Replace `schedule_count=1` as live schedule proof with independent clock
  inventory and future-run proof.
- Replace platform completion labels as heartbeat success proof with canonical
  queue, terminal outcome and `next_due` effects.
- Replace Coach delivery availability as a heartbeat liveness dependency with
  separately evidenced optional delivery.
- Replace optimistic unknown-state handling with `MISSING_PROOF`,
  `OPERATIONAL_BLOCKED` or `INTEGRITY_BLOCKED`.

This slice does not replace or create a live scheduler. Any later scheduler
cutover needs separate authorization and must prove zero overlap; the read-only
auditor always denies a second clock and a second writer.

## Current disposition

Fresh Server Canonical evidence on 2026-08-29 reports the deployed V11
contract and Worker release `20260829T033523Z` READY, all four control queues
IDLE, four open and 179 closed families, zero active experiments, zero formal
candidates and zero candidate OOS. The active successor trigger is waiting at
0 of 90 observations for complete UTC day 2026-08-29; this is evidence waiting,
not an empty research queue.

The first natural V11 occurrence ended `HEARTBEAT_FAILED_CLOSED`. Exact
post-failure platform inventory retained the same schedule ACTIVE with one
active and one total clock, exact V11 hash, unchanged recurrence and no second
schedule. Canonical advanced only to next normal due
`2026-08-30T01:00:00Z`, with no retry or backfill. This proves the narrower V11
failure lifecycle without converting the failed heartbeat into success.

The corrected formal CLI audit returns `INTEGRITY_BLOCKED` because the latest
canonical heartbeat truly failed, plus `ACTIVE_CLOCK_NEXT_RUN_MISSING` because
the platform does not expose a future run. It separately proves exact
`single_clock_proven=true`, `single_writer_proven=true`, frozen V11 identity and
the failure-lifecycle contract. The next natural occurrence must prove that the
deployed empty-rollover reader resumes forward-evidence collection.

The control-plane change has zero immediate PnL or drawdown effect. Its value is
preventing false readiness, duplicate writers/clocks and silent loss of future
evidence days.

## Readback source disposition

The platform view/list surface now provides exact schedule id, enabled state,
active and total inventory counts, recurrence, destination and last-run time.
It still does not provide a future `next_run_time` or typed terminal occurrence
status. OAuth Research MCP independently exposes the canonical contract and the
sole-writer state but cannot fill those platform gaps.

The available fields are normalized into
`CLOUD_OPS_CONTROL_SURFACE_READBACK_V1`; unavailable fields remain explicit
null or `MISSING_PROOF`. The audit must not infer future liveness from ACTIVE,
re-arm the task, retry the failed heartbeat, or create a replacement timer.

## Acceptance

The new command is:

```text
python -m research_pipeline cloud-ops-liveness-audit CANONICAL_STATUS.json CONTROL_SURFACE_READBACK.json --require-ready
```

It emits canonical JSON and exits with code 3 unless the result is exactly
`READY`.

Acceptance for deployed control release `20260829T033523Z`, source commit
`4564325b0e31dd420aab6329ff08683e07b90c07`, established:

- 26 focused liveness, Schema and CLI tests passed;
- 158 forward persistence, activation, evidence, post-shock, recovery and
  liveness tests passed with one existing conditional skip;
- deployed Worker verification passed 109 control/data tests and 7 fixed
  isolation tests;
- Python compile validation passed;
- both JSON Schemas passed Draft 2020-12 meta-schema validation;
- diff whitespace validation passed.

The current Windows working tree presents historical V6/V7 bytes with checkout
line-ending differences, so its frozen-hash tests are not authoritative. The
deployed byte-preserving archive passed those same frozen-hash checks. No
historical contract, hash, sealed artifact, gate or OOS state was modified.
