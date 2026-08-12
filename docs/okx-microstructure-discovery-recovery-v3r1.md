# OKX microstructure discovery recovery contract V3R1

## Status and version boundary

Status: `FROZEN_PRE_IMPLEMENTATION_RESEARCH_ONLY`.

V3R1 is a new discovery-source lifecycle for one strictly future generation.
It does not edit, reopen, resume, extend, backfill, or reinterpret any V1, V2,
V3, R1, or R2 byte.

The name V3R1 is deliberate. The previously sealed future V4 design remains
reserved for the single-active 42-day standalone economic route that can exist
only after a positive V3-family discovery interpretation and discovery
economic veto. V3R1 cannot instantiate, replace, or borrow authority from that
future V4 route.

The deployed generation
`okx-btcusdt-microstructure-forward-v3-20260811-r2` is closed as
`NO_EVIDENCE_CLOSE_INTERRUPTED_GENERATION`:

- frozen start day `2026-08-11`;
- canonical intake accepted `0 / 14` days;
- source stopped at `2026-08-11T09:24:17Z` with
  `UNEXPECTED_EXCHANGE_EVENT`;
- no raw control-event payload was retained, so the exact event remains
  `MISSING_PROOF`;
- no market outcome, prediction, candidate, OOS result, PnL, or drawdown result
  exists for R2.

This recovery is based only on liveness evidence obtained before any R2 day was
accepted. It is not outcome-driven gate relaxation. R2 remains inactive and
preserved.

## Performance purpose and claim boundary

The economic question is unchanged: determine whether the frozen OKX
`BTC-USDT` `trades` plus `books5` microstructure tiers identify a broad and
stable forward response that can justify at most one later preregistered
hypothesis.

V3R1 changes source recovery and liveness accounting only. It does not change
the V3 day payload, midline formula, `1.50` event threshold, simplest-first
tier order, 60-minute cooldown, next-minute entry reference, 5/15/60/240/1440
minute responses, matched control, event-count gates, match-coverage gate, or
predictive direction.

Immediate fee-adjusted PnL and drawdown effects are zero. Strategy value,
fees, slippage, capacity, utilization, holding time, matched-capital PnL, path
risk, candidate readiness, and OOS value remain `MISSING_PROOF`.

## Why contiguity remains mandatory

The existing deterministic diagnostic requires one minute-contiguous record
sequence and labels responses through 1,440 minutes. Concatenating unrelated
complete days would create false cross-gap returns and invalid matched
controls. V3R1 therefore does not replace the V3 contiguity gate with a looser
complete-day count.

V3R1 selects only the first source-liveness-defined streak of fourteen complete
and consecutive UTC days. Selection never uses a market value, feature,
response, event count, return, volatility, or diagnostic result.

## Frozen collection lifecycle

The binding freezes these values before the source starts:

- `required_consecutive_days = 14`;
- `calendar_day_budget = 42`;
- `start_day` is a strictly future complete UTC day;
- `end_day` is exactly `start_day + 41 calendar days`;
- every elapsed calendar day receives exactly one immutable source disposition;
- accepted days form the current consecutive streak;
- an allowlisted transport rejection resets the streak before the next UTC day;
- previously complete days from a broken streak remain preserved as
  `NONSELECTED_COMPLETE_PREFIX` and can never enter the diagnostic;
- the first streak that reaches fourteen days is the sole selected discovery
  window and ends collection immediately;
- failure to form one fourteen-day streak by the frozen deadline ends at
  `NO_COMPLETE_STREAK_CLOSE`; the deadline cannot be extended.

The 42-day budget is an availability bound, not a 42-day economic dataset. It
fits the existing bounded 45-day source-runtime envelope and permits at most
three non-overlapping fourteen-day opportunities without creating sequential
generations, a second timer, or a user-operated restart loop. No later streak
can replace the first complete streak after outcome access.

V3R1 may not search for the best streak, restart its calendar deadline, merge
prefixes across a rejected day, or add days after diagnostic access.

## Complete-day contract

A complete day retains the exact V3 market payload contract:

- one complete UTC day and exactly 1,440 completed minute records;
- fixed public OKX endpoint, `BTC-USDT`, `trades`, and `books5` only;
- latest eligible `books5` reference at or before each trade;
- zero unreferenced trades, crossed books, malformed records, timestamp or
  sequence regressions, and unresolved-buffer overflow;
- both subscription acknowledgements before the UTC day starts;
- continuous data-plane observation for the whole UTC day;
- raw-arrival and predecessor integrity;
- canonical bundle and envelope bytes published once by same-filesystem atomic
  rename;
- no overwrite, source read-after-publish, database access, Trading access, or
  historical backfill.

The accepted market bundle continues to conform to the immutable V3 day schema
so the existing diagnostic formulas can consume it. Admission into V3R1 also
requires a new V3R1 envelope, source-contract hash, release/manifest binding,
generation identity, calendar disposition, and intake-state chain. Historical
V3 bundles cannot be relabeled or copied into V3R1.

## Rejected-day contract

A day can reset the streak without blocking the whole generation only for one
of these source-liveness reasons:

- `SERVICE_UPGRADE_NOTICE_64008`;
- `TRANSPORT_DISCONNECT_UNPROVED_GAP`;
- `PROCESS_RESTART_BEFORE_DAY_COMPLETE`;
- `HOST_REBOOT_BEFORE_DAY_COMPLETE`;
- `DUAL_CHANNEL_NOT_READY_AT_DAY_START`.

Every rejected day produces one immutable, schema-bound rejection envelope
containing only:

- generation, binding, and source-release identity;
- day and frozen-calendar position;
- exact allowlisted reason;
- first and last observation timestamps when available;
- acknowledged channel names;
- completed minute count;
- data-message and control-event counts;
- raw-arrival and control-event chain heads;
- sanitized control-event type and code when applicable;
- rejection time, canonical seal, and one-way delivery proof.

Partial market aggregates, returns, feature values, event counts, labels, or
directional outcomes are forbidden in a rejection envelope. A rejected day is
never repaired, retried, stitched, backfilled, or promoted to complete.

Complete days from a streak that is later reset are also never reused. Their
sealed market bytes remain historical liveness evidence but are excluded from
the selected fourteen-day handoff and every diagnostic calculation.

## WebSocket event and reconnect policy

The producer classifies control events before the market-data collector:

- `subscribe` for exactly `trades` or `books5` is an acknowledgement;
- `channel-conn-count` is a non-market operational observation that is sealed
  into the control-event chain and otherwise ignored;
- `notice` with exact code `64008` rejects the active day, closes the old
  session, and permits preparation for the next not-yet-started UTC day;
- `error`, `channel-conn-count-error`, `unsubscribe`, a changed instrument or
  channel, an unknown event, or an unknown notice code blocks the generation;
- any disconnect or reconnect during an active day rejects that whole day
  unless lossless continuity is proven by a separately frozen future contract;
- V3R1 does not claim lossless cross-session continuity and never stitches two
  sessions into one complete day;
- reconnecting outside an active day is allowed only after both fixed channel
  acknowledgements and pre-day continuity warm-up succeed.

An allowlisted transport rejection does not excuse a market-integrity anomaly.
Malformed messages, crossed books, unreferenced trades, sequence or timestamp
regression, hash drift, publication failure, conflicting duplicates, and
unsafe filesystem state block the whole generation.

Protocol design input is limited to the official OKX WebSocket documentation:
`https://www.okx.com/docs-v5/en/#overview-websocket-notification`. It documents
the `notice` event and code `64008` before a service-upgrade disconnect. This
supports the V3R1 classification but does not prove that R2 observed that exact
event.

## Canonical state and one-way transport

V3R1 uses byte-separated namespaces:

- binding:
  `/etc/agora-research/okx-microstructure-continuous-source-v3r1.json`;
- source private staging:
  `/var/lib/agora-evidence-source/microstructure-v3r1-private-staging`;
- one-way drop:
  `/var/lib/agora-evidence-source/microstructure-v3r1-drop`;
- canonical intake state:
  `/var/lib/agora-research/state/microstructure-v3r1`;
- preserved history:
  `/var/lib/agora-research/state/microstructure-archive`.

The existing V3 binding, state, drop metadata, release provenance, service
journal, and failure evidence are hash-inventoried into a create-only R2 archive
manifest while remaining present at their original paths. V3R1 does not move,
rewrite, or delete them.

The existing `agora-research-microstructure-intake.path` and service are updated
in place for the V3R1 namespace during a zero-source cutover. No second path
unit, timer, scheduler, writer, or local poller is allowed. The path unit
remains an event consumer, not a lifecycle clock. Only the network-denied
`agora-research` intake identity advances canonical V3R1 state.

The source remains credential-free `agora-evidence-source`, cannot read or
write canonical state, cannot access Trading secrets, and cannot select or
enqueue research actions. The Worker and source retain no database, order,
fund, SHADOW, PAPER, or LIVE authority.

## Canonical V3R1 state

The intake state contains:

- exact source, schema, release, manifest, binding, diagnostic, and generation
  hashes;
- frozen `start_day`, `end_day`, streak target, and calendar budget;
- one ordered disposition for every elapsed day;
- current-streak length and exact day identities;
- preserved nonselected complete prefixes;
- rejected-day count and reason inventory;
- selected fourteen-day accepted-data SHA-256 chain when ready;
- full-calendar disposition chain covering every complete and rejected
  envelope;
- next calendar day and remaining budget;
- terminal state and one bounded failure object;
- readiness fields denying candidate, OOS, PnL, and promotion authority.

Allowed lifecycle states are:

- `WAITING_FOR_CALENDAR_DAY`;
- `BUILDING_CONSECUTIVE_STREAK`;
- `DIAGNOSTIC_READY` after the first exact fourteen-day streak;
- `NO_COMPLETE_STREAK_CLOSE` at the immutable deadline;
- `INTEGRITY_BLOCKED` for any non-allowlisted or structural defect.

Every transition is atomic, hash-bound, idempotent for identical bytes, and
fail-closed for a changed duplicate. No caller can choose a path, generation,
day, reason, schema version, selected streak, or disposition.

## Diagnostic and handoff invariants

The handoff exports only the selected first fourteen-day streak. It never
exports rejected-day envelopes or nonselected complete prefixes as diagnostic
market input, although their hashes and counts remain in the canonical manifest
for missingness audit.

The exported market days must remain exactly fourteen complete, strictly
ordered, consecutive UTC days and 20,160 contiguous minute records. The
existing V3 diagnostic code and frozen contracts remain unchanged:

- above-mid buy to below-mid sell quote ratio;
- `1.50` threshold and simplest-first tier precedence;
- 60-minute per-tier cooldown;
- next-complete-minute entry;
- 5/15/60/240/1440-minute responses;
- closest unused earlier-day same-minute matched control;
- 30-event, seven-day-half, 80% coverage, seal, and anomaly gates.

The V3R1 handoff adds only generation, selected-streak, calendar-disposition,
and missingness proofs around those unchanged fourteen market bundles.

Missingness remains a generalization limitation. A positive diagnostic cannot
claim that rejected calendar days would have behaved like the selected streak.
The discovery window can authorize at most one separately frozen hypothesis
design and cannot later become clean OOS for a hypothesis derived from it.

## Implementation and activation gates

Implementation is incomplete until all of these are independently proven:

1. versioned V3R1 source, rejection-envelope, drop-envelope, intake-state,
   archive-manifest, and handoff contracts are frozen and Draft 2020-12 valid;
2. the immutable V3 accepted-day and diagnostic contracts are reused without
   byte changes;
3. Java 21 producer and Python intake pass deterministic complete, rejection,
   streak-reset, first-streak, deadline, duplicate, restart, unknown-event, and
   integrity tests;
4. V1/V2/V3 bytes and tests remain independently usable as historical evidence;
5. Worker packaging, installer, verifier, monitor, and handoff paths bind only
   exact V3R1 identities and never admit historical V3 bytes;
6. the exact R2 archive inventory is create-only and hash verified;
7. one clean reviewed release passes package-only closure;
8. server preflight proves R2 inactive, PID zero, no failed state, queues idle,
   one cloud clock, no source timer, and no second writer;
9. deployment preserves the control plane and all existing canonical research
   bytes;
10. one strictly future R3/V3R1 binding is created once and the source is
    started once without adding a schedule;
11. fresh canonical readback proves exact release, binding, service, path,
    state, archive, and single-clock identities.

Failure of any gate leaves V3R1 inactive. It does not authorize manual repair,
backfill, R2 reuse, candidate creation, OOS access, or Trading action.

## Terminal research dispositions

- `V3R1_SOURCE_OR_INTAKE_INTEGRITY_CLOSE`: structural, unknown-event, or
  non-allowlisted failure;
- `V3R1_NO_COMPLETE_STREAK_CLOSE`: no fourteen-day streak within the frozen
  calendar budget;
- `V3R1_DIAGNOSTIC_NO_MECHANISM_CLOSE`: selected evidence fails unchanged
  predictive gates;
- `V3R1_DIAGNOSTIC_WAIT_MISSING_PROOF`: selected evidence cannot support a
  unique interpretation without an unfrozen assumption;
- `V3R1_ONE_HYPOTHESIS_DESIGN_READY`: selected evidence passes every frozen
  predictive and missingness disclosure gate, permitting exactly one later
  preregistered hypothesis-design task and nothing more.

No V3R1 disposition directly registers a candidate, opens OOS, changes a
strategy, or activates SHADOW, PAPER, or LIVE.
