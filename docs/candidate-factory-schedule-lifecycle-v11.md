# Candidate Factory schedule lifecycle V11

Status: `ACTIVE_V11_PARTIAL_LIVE_ACCEPTANCE`

Authorization: `RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE`

## Outcome

V11 separates failure of one scheduled research occurrence from lifecycle of
the sole cloud research clock. A failed occurrence remains fail closed and
cannot claim a heartbeat, evidence day, hypothesis, experiment, candidate, OOS,
PnL, or drawdown result. The same occurrence is not retried or caught up, but it
also cannot pause, disable, delete, replace, or otherwise mutate the schedule.
The enabled schedule and its next normal occurrence must remain present.

This fixes the avoidable blast-radius fault observed when one
`REJECTED_BEFORE_QUEUEING` occurrence disabled the only V10 clock. It does not
claim that a platform-wide failure before task execution can never lose one
prospective evidence day.

## Frozen contract

- contract: `CLOUD_OPS_SCHEDULE_V11`
- SHA-256: `9b30c944f2a7d3d1d23a7b01a87eb72dadb1368749039e6ea279c1b07be37c61`
- predecessor: immutable `CLOUD_OPS_SCHEDULE_V10`, SHA-256
  `90e0de95fa34beff9447640a5dcdbb972278014664806df0a4bf5f36e2598faa`
- existing schedule id: `6a71a1ed2f608191b0621c52bed3fd81`
- recurrence: daily `09:05 Asia/Taipei`
- canonical due: `09:00 Asia/Taipei`
- clocks: exactly one Codex cloud Ops schedule
- writers: exactly one Server Canonical writer

V10 bytes remain unchanged. Repository preparation did not authorize V11
deployment or platform activation; the user separately authorized and the
2026-08-28 cutover completed the zero-overlap procedure below.

## Live acceptance

The first natural V11 occurrence ran on 2026-08-29 and correlated to canonical
request `60faaacd76c1419ca73edcf5b283c220`. It ended
`HEARTBEAT_FAILED_CLOSED` because a legitimate fresh rollover state omitted its
zero-length `evidence_observations` field. The occurrence did not retry,
backfill, register a candidate, open OOS, or perform a Trading action.

Fresh post-failure platform readback retained exact schedule id
`6a71a1ed2f608191b0621c52bed3fd81` as the only active and only total clock.
Canonical advanced only to the next normal due boundary at
`2026-08-30T01:00:00Z`. This proves the narrower fail-closed-cycle and
keep-enabled-clock behavior. The platform does not expose `next_run_time`, so
full `schedule_lifecycle_preserved` acceptance remains `MISSING_PROOF`.

The empty-rollover compatibility fix and the corrected read-only liveness
identity check are deployed in Worker release `20260829T033523Z`, source commit
`4564325b0e31dd420aab6329ff08683e07b90c07`. The next natural occurrence must
still prove successful forward-evidence continuation; it is not retried today.

## Failure lifecycle

The frozen caller contract requires:

- `failed_occurrence_effect=FAIL_CLOSED_CURRENT_OCCURRENCE_ONLY`
- `schedule_enabled_state_after_failure=KEEP_ENABLED`
- `automatic_pause_disable_or_delete=DENY`
- `schedule_self_mutation=DENY`
- `next_normal_occurrence=PRESERVE`
- `same_occurrence_heartbeat_retry=DENY`
- `manual_catchup=DENY`
- `evidence_backfill=DENY`
- `schedule_mutation_authority=EXPLICIT_USER_AUTHORIZATION_ONLY`

The schedule prompt does not receive automation-management authority. Policy,
provenance, attestation, queue, evidence, candidate, delivery, or integrity
failure therefore stops the current occurrence without changing the clock.

## Existing durable recovery retained

No second retry system is added. After an MCP request is durably queued, the
existing path-dispatched Worker already keeps the request queryable, resumes an
abnormally interrupted `running.json` under the same request id, bounds restart
attempts, and preserves a final fail-closed stale-lease recovery. V11 changes
only the pre-queue schedule lifecycle contract.

## Read-only liveness acceptance

The liveness auditor now exposes `schedule_lifecycle_preserved`. It is true only
when the exact sole clock is active, a future `next_run_time` is independently
visible, and the latest occurrence did not disable the schedule.

The same audit binds the canonical and platform readbacks to the exact known
frozen V10 or V11 SHA-256 rather than accepting two mutually consistent but
unknown values. For V11 it additionally requires the exact nine-field
`failure_lifecycle` object and exposes
`v11_failure_lifecycle_contract_proven=true`. Missing, altered, or unsupported
contract identity is an integrity blocker even when the platform clock repeats
the same altered id or hash.

The live clock must also retain exact schedule id
`6a71a1ed2f608191b0621c52bed3fd81`. A replacement clock carrying the same
contract, hash, recurrence, and destination is an identity mismatch, not a
successful in-place cutover. Canonical V11 status exposes both the exact
`failure_lifecycle` and `platform_schedule` objects needed for that independent
comparison.

Zero overlap is an inventory claim, not only an active-count claim. Readback
must contain exactly one total clock and exactly one total writer. An extra
paused clock or inactive writer is an integrity blocker even when only one of
each is active.

`automation_disabled=true` produces the explicit operational blocker
`SOLE_CLOCK_DISABLED_BY_FAILED_OCCURRENCE`. A rejected occurrence that leaves
the exact clock active with a future next run remains an occurrence failure, but
passes the narrower lifecycle-preservation claim.

## Historical cutover gate

The completed activation required separate authorization and this exact
zero-overlap order; any later migration must preserve it:

1. Pause the exact active V10 schedule and prove zero active research clocks.
2. Deploy and verify Server Canonical V11 attestation.
3. Update the same paused schedule id to the exact V11 prompt and hash.
4. Prove the updated schedule is still paused and no other clock exists.
5. Activate that schedule and prove exactly one active clock and one writer.

Creating a replacement or second schedule is denied. Rollback must first pause
V11 and prove zero active clocks before restoring V10 Worker and prompt.

## Acceptance matrix

| Scenario | Research write | Schedule after occurrence | Required result |
| --- | --- | --- | --- |
| Normal due heartbeat queues and completes | one canonical request | enabled, future next run | canonical `HEARTBEAT_OK` and advanced `next_due` |
| Platform or MCP rejection before queue | none | enabled, future next run | occurrence blocker, no success claim |
| Policy, provenance, or attestation rejection | none | enabled, future next run | exact integrity blocker |
| Queued Worker interruption or host reboot | same request id only | enabled | existing bounded idempotent resume |
| Evidence deadline missed | no backfill | enabled | sealed failure and untouched rollover |
| Duplicate or concurrent caller | at most one canonical request | enabled | idempotent convergence |

No test may activate a strategy, open OOS, write Trading state, access orders or
funds, add a timer/writer, or count control-plane success as alpha.

## Residual availability limit

With exactly one daily platform occurrence, no same-occurrence retry, no catchup,
and no second timer, V11 cannot guarantee collection of a day when the entire
platform occurrence never executes. Eliminating that final single-day failure
mode would require a separately authorized, bounded platform retry for the same
occurrence or a prospectively versioned evidence missingness contract. Neither
is part of V11, and neither may be retrofitted to the current sealed successor.

## Economic boundary

V11 has zero immediate PnL and drawdown effect. Its value is limited to reducing
multi-day clock outage and avoiding unnecessary future evidence-window resets.
Candidate Factory throughput must be measured separately by verified direct
economic closures, risk-adjusted PnL, drawdown, stability, and decision latency;
schedule health or test count is not strategy output.
