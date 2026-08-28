# Candidate Factory control-plane liveness V1

Status: research-only offline acceptance complete; live effectiveness `MISSING_PROOF`

Acceptance snapshot: 2026-08-27

Authorization: `RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE`

## Outcome

The repository now has one deterministic, read-only admission check that keeps
the frozen schedule declaration separate from live control-surface proof. A
`READY` V10 contract and a completed platform task are no longer sufficient to
claim that the Candidate Factory clock is live or that canonical evidence
advanced.

The validator requires all of the following at the same observation boundary:

- ready and identity-consistent Policy, Worker and V10 canonical contract;
- exactly one active `CODEX_CLOUD_OPS` clock with the frozen id, hash,
  recurrence, destination and a future `next_run_time`;
- exactly one independently inventoried active Server Canonical writer;
- a durable heartbeat queue claim plus canonical request id for any platform
  success label;
- canonical `HEARTBEAT_OK`; `HEARTBEAT_FAILED_CLOSED` remains a blocker even
  when its failure handler has already advanced `next_due`;
- a fresh canonical `next_due` effect after the due boundary;
- one coherent latest sealed capture/ingest pair;
- Coach delivery kept outside heartbeat and evidence liveness.

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

Fresh Server Canonical evidence on 2026-08-27 still reports the V10 contract as
ready, one declared schedule, idle heartbeat/capture/ingest queues, four open
families, 179 closed families, zero formal candidates, zero OOS and the active
forward trigger at 4 of 90 observations.

The most recent platform task result reports that the due heartbeat was
rejected before queueing and that the automation disabled itself. The canonical
`next_due` did not advance. That is direct operational-blocker evidence, but it
does not contain a fresh, independently normalized inventory of all active
clocks and writers. Therefore live cutover and runtime effectiveness remain
`MISSING_PROOF`; no re-arm, retry, replacement clock, candidate run or OOS
access is authorized by this acceptance.

The formal CLI audit over fresh canonical status at
`2026-08-27T03:06:30Z` and the closed unavailable-platform readback returned
`OPERATIONAL_BLOCKED` with both `CONTROL_SURFACE_READBACK_UNAVAILABLE` and
`CANONICAL_HEARTBEAT_EFFECT_MISSING`. It preserved the coherent sealed
`2026-08-25` day while keeping the current 4/90 `CAPTURE_DUE` progress
unproven. Cutover `live_effectiveness` remains `MISSING_PROOF`.

The control-plane change has zero immediate PnL or drawdown effect. Its value is
preventing false readiness, duplicate writers/clocks and silent loss of future
evidence days.

## Readback source disposition

Repository search found only frozen schedule declarations and historical
cutover assertions. OAuth Research MCP exposes canonical contract and writer
state, but not the platform's complete live clock inventory or future
`next_run_time`. Reading the ChatGPT Work task exposes its latest task result,
not an independently queryable scheduler inventory. None of those surfaces may
be converted into an `AVAILABLE` control-surface readback by inference.

Therefore the next safe integration boundary is an exact, read-only platform
readback that can normalize clock id, enabled state, recurrence, destination,
future next run, occurrence queue proof and sole-writer inventory into
`CLOUD_OPS_CONTROL_SURFACE_READBACK_V1`. Until such a supported source exists,
the caller must submit the closed `MISSING_PROOF` readback with empty inventories.
It must not re-arm the failed task, retry the rejected heartbeat, create a
replacement timer or infer one active clock from the V10 declaration.

## Acceptance

The new command is:

```text
python -m research_pipeline cloud-ops-liveness-audit CANONICAL_STATUS.json CONTROL_SURFACE_READBACK.json --require-ready
```

It emits canonical JSON and exits with code 3 unless the result is exactly
`READY`.

Acceptance was run against a byte-preserving archive of deployed commit
`2d2ba9f0ad3dd3b8187595abf7517fd4d9b4bbd5` with the new slice applied:

- 18 focused liveness, Schema and CLI tests passed;
- 34 liveness, report-failure injection and frozen V6/V7/V10
  delivery-contract tests passed;
- 147 Candidate Funnel, forward admission, evidence, local manager, frozen
  delivery-contract and liveness tests passed;
- Python compile validation passed;
- both JSON Schemas passed Draft 2020-12 meta-schema validation;
- diff whitespace validation passed.

The current Windows working tree presents historical V6/V7 bytes with checkout
line-ending differences, so its frozen-hash tests are not authoritative. The
deployed byte-preserving archive passed those same frozen-hash checks. No
historical contract, hash, sealed artifact, gate or OOS state was modified.
