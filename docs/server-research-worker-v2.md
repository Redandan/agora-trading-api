# Server Research Worker V2

Status: `AUTHORIZED_RESEARCH_CONTROL_PLANE`

V2 replaces the V1 server timer with one Codex cloud Ops schedule. It does not
change the scientific gates or authorize Trading runtime, database, strategy,
order, fund, OCO/Grid, SHADOW, PAPER, or LIVE behavior.

## Ownership

| Concern | Contract |
| --- | --- |
| AI clock | One scheduled task inside the Codex Ops task |
| Public entry | OAuth 2.1 MCP at `https://agoratradingapi.purrtechllc.com/research/mcp` |
| Listener | `agora-research-mcp.service` on loopback `127.0.0.1:8092` |
| Request handoff | Atomic `/var/lib/agora-research/requests/pending.json` |
| Event dispatch | `agora-research-dispatch.path`; it is not a timer |
| Mutable research state | Only the fixed heartbeat or candidate-registration subprocess |
| Canonical state | `/var/lib/agora-research/state` |
| Sealed results | `/var/lib/agora-research/inbox` |
| Forward evidence | Per-day sealed artifacts and SHA-256 chain under canonical trigger state |
| Identity | Unprivileged `agora-research` account |
| Credentials | Private OAuth store only; no Trading, exchange, DB, SSH, Telegram, or Codex secret |
| Public source | Credential-free `agora-evidence-source`; fixed OKX endpoint only |
| Source dispatch | `agora-research-source.path` and network-denied `agora-research-evidence-ingest.path`; neither is a timer |

The MCP process can read canonical state and sealed reports. Its systemd mount
policy makes those directories read-only. It can write only OAuth state and the
request queue. The dispatch service consumes a fixed request schema and invokes
either the same deterministic heartbeat launcher used by V1 or the bounded
`register-candidate-bundle` pipeline command. It cannot dispatch arbitrary
commands or caller-selected paths.

The existing status operation also reads the immutable release provenance next
to the deployed source. It exposes the release id, Git commit and branch, dirty
flag, install time, and the SHA-256 of the installed source manifest. The
manifest hash, every listed source-file hash, and the exact installed source
file inventory are rechecked on every status read and before either write.
Missing, extra, malformed, tampered, or dirty source/provenance fails closed:
both server write operations return
`WORKER_RELEASE_INTEGRITY_BLOCKED` before touching either queue. This never adds
a new MCP operation or exposes a server filesystem path.

The same status exposes the installed server-canonical frozen
`CLOUD_OPS_SCHEDULE_V9` contract, its unchanged `09:00 Asia/Taipei` canonical
heartbeat due boundary, the intended sole `09:05 Asia/Taipei` cloud recurrence,
the 300-second nominal dispatch margin, and its byte-level SHA-256. V1 through
V8 remain immutable predecessor evidence. The two write operations require the
V9 hash in the `ops_schedule_contract_sha256` argument. A missing or
changed contract returns
`OPS_SCHEDULE_CONTRACT_INTEGRITY_BLOCKED`; a missing or mismatched caller
attestation returns `OPS_SCHEDULE_CONTRACT_ATTESTATION_BLOCKED`. Both outcomes
occur before the request or companion evidence-capture queues are touched.

## OAuth contract

The private connector uses dynamic client registration, authorization code with
PKCE S256, a 15-minute opaque access token, and a rotating one-year refresh
token. Initial linking requires a 256-bit one-time enrollment code. The server
stores only its PBKDF2 hash and consumes it after the first successful token
exchange. Tool access requires both `research:read` and `research:heartbeat`.

The MCP exposes only five operations:

- read canonical research status and the latest sealed heartbeat;
- enqueue one due, idempotent heartbeat request;
- enqueue one idempotent, evidence-bound candidate bundle after canonical
  `READY_FOR_HYPOTHESIS` (maximum 131072 UTF-8 JSON bytes);
- read one request result by server-generated request id;
- generate a read-only weekly or monthly briefing from sealed state.

It exposes no shell, path, arbitrary command, upload, strategy activation, or
Trading application proxy.

## Long-running work

The MCP request returns after durable enqueue. `agora-research-dispatch.path`
starts a systemd oneshot outside the HTTP request lifetime. A cloud Ops run may
poll the request id briefly; if the research runner is still active, the next
Ops cycle reads the durable result. The queue is the shared single-operation
guard and the heartbeat lock remains the heartbeat's final single-writer guard.

Candidate submission is not strategy generation by the server. Codex supplies
one schema-bound hypothesis plus matching frozen experiment manifest. The
pipeline re-reads the canonical trigger, re-hashes the sealed review and every
evidence artifact, enforces V3 metrics/constraints and active-experiment budget,
then closes the trigger only after the experiment reaches `PREREGISTERED`.
Repeated identical submissions converge while queued and return the prior
completed result afterward. It cannot execute the experiment or promote it.
Canonical status exposes the evidence-ready registration deadline, signed
seconds remaining while pending, and the immutable measured `PASS` or `BREACH`
after registration, so the 24-hour SLA is server-observable rather than inferred
by the cloud prompt.

Canonical status also exposes `forward_candidate_readiness`. A forward-evidence
bundle may use only an explicitly eligible strategy adapter whose runner is
installed, whose required sealed corpus is hash-verified, and whose contract
retains OOS. Parity, diagnostic-only, and closed historical adapters are not
candidate capabilities. If no eligible adapter exists, status reports
`NO_ELIGIBLE_FORWARD_CANDIDATE_ADAPTER`; this is an honest capability gap, not
permission to relabel old work to satisfy the 24-hour metric.

The installed eligible adapter is `dra-forward-entry-admission-v1`. Readiness
also requires the frozen diagnostic contract and exact retained pre-2025 corpus
to rehash successfully. A ready discovery trigger exposes a canonical
`candidate_context` with no free-form parameter surface. Candidate registration
creates and source-binds a separate future 90-day `CANDIDATE_OOS` trigger.
Interrupted registration resumes only when the hypothesis, manifest, policy,
candidate binding, OOS window, and source contract remain identical; otherwise
it fails closed.
Once those artifacts and the candidate-bound OOS source contract are fully
frozen, the experiment state records `candidate_frozen_at`. A later interruption
while closing the discovery trigger must reuse that timestamp and its original
candidate context, so retry delay cannot create a false SLA breach or move the
already frozen OOS window. Canonical status recomputes the lead time from the
sealed readiness timestamp and fails closed if the stored time, lead, or
`PASS`/`BREACH` result disagree.
If the request itself ended `FAILED` after writing matching partial canonical
state, `get_research_status` exposes one bounded
`candidate_registration_recovery` with the exact hash-verified original bundle.
The next cloud cycle may replay it once without changing any field. A second
failed replay, multiple recoverable payloads, a run-payload hash mismatch, or
drift from the stored hypothesis/manifest becomes `INTEGRITY_BLOCKED` and never
authorizes bundle regeneration. Candidate submission enforces that recovery
state before queue creation: only the required hash may use the single replay,
and a second failed replay blocks any third request at the MCP write boundary.
For a normal new submission, the same preflight refuses queue creation unless
the named canonical trigger is `READY_FOR_HYPOTHESIS`, its readiness timestamp
matches the hash-verified latest ready review, and canonical state contains
exactly one verified evidence manifest. Missing, waiting, closed, or incomplete
ready state cannot create a failed durable run. The one exact recovery replay
is exempt because a successfully preregistered partial flow may already have
closed its trigger; its original payload hash and partial state are verified by
the stricter recovery gate instead.

The heartbeat also verifies the prospective evidence progress contract. It
does not fetch market data itself. When a heartbeat is due and canonical
progress is `CAPTURE_DUE`, the same MCP operation creates one deterministic
companion request in the isolated source queue. The credential-free public
source fetches the fixed OKX response and writes a hash-bound one-way drop; a
network-denied intake reconstructs and seals the bundle. The Worker accepts it
only inside the six-hour post-close window and only as the next untouched UTC
day. Missing the window produces an integrity alert and permanently forbids
backfill for that trigger.

After the final required complete UTC day is ingested, the network-denied
canonical pipeline revalidates the full chain and deterministically seals a
mechanism-neutral dataset, diagnostic, typed evidence manifest, and one
`READY_FOR_HYPOTHESIS` review. It does not select a strategy or calculate
strategy PnL. This reuses the existing heartbeat/ingest boundary: no sixth MCP
tool, third queue operation, second writer, or additional timer is introduced.
For the full 90-day discovery contract, the diagnostic evaluates only the two
preregistered market mechanisms and exposes at most one that passes every gate.
If none passes, it closes as `NO_CANDIDATE_FORWARD_DIAGNOSTIC`; the next
heartbeat places the sealed review into the Coach outbox exactly once.
If the calendar review boundary arrives before the final day is present,
capture remains the next action. The existing heartbeat can recover a legacy
complete-but-unreviewed state by producing the same sealed artifacts.

Before evidence starts, canonical state must contain one sealed source contract
binding the trigger to the producer and one-way delivery transport. The source
contract cannot grant the Worker network or database access. If it is absent,
status is `EVIDENCE_SOURCE_UNBOUND` and the next cloud cycle emits a readiness
alert instead of silently waiting until the 90-day review.

The queue rejects an early request as `NOT_DUE` from canonical heartbeat state.
Pending and running requests remain queryable by request id before the final run
record exists. A request older than the policy lease is preserved as
`STALE_RECOVERED`; only then may a new request be admitted. Weekly and monthly
briefings are sealed under canonical `state/reports` and return their artifact
id, relative path, and SHA-256. The MCP response also distinguishes the policy
embedded in that immutable report from the currently active canonical policy;
an older artifact is labelled `SEALED_HISTORICAL_POLICY` rather than silently
presented as a current-policy briefing.

Canonical status also exposes `evidence_capture_health`, which correlates the
asynchronous source request and network-denied ingest by deterministic request,
request hash, and bundle hash. The cloud run that queued a capture observes the
field for a bounded interval in the same cycle. `SEALED` stays quiet; retries
remain observable; terminal failure, stalled dispatch, or any correlation
mismatch fails closed immediately. No second heartbeat, source timer, upload,
or backfill operation is introduced.
An active source or intake request may remain nonblocking through the exact
six-hour deadline only. After that instant canonical health returns
`INTEGRITY_BLOCKED`, preserving the request id, day, deadline, and negative
seconds remaining; a stalled path therefore cannot hide an expired prospective
capture behind `QUEUED` or `RETRYING`.

Canonical status derives `coach_outbox` from durable server heartbeat state,
not from whichever heartbeat happened to run most recently. For every material
event it confines the relative path to canonical state,
re-hashes the artifact, rejects missing or duplicate delivery ids, and returns
the complete structured event under `EVENTS_PENDING_EXTERNAL_DELIVERY`. The
sealed artifact SHA-256 is the delivery id. Routine heartbeats leave older
pending events intact. Each verified event also includes a deterministic
delivery token and exact canonical delivery prompt. The V9 schedule contract
permits only task
list/read/send operations for this handoff. After proving the heartbeat is due,
the cloud cycle snapshots initial pending ids, reads the exact Coach task,
deduplicates by artifact SHA-256, sends an absent canonical prompt once, and
reads back before claiming verified delivery. An initial-snapshot receipt is
carried by that same due heartbeat. An event created by the heartbeat is sent
afterward and its receipt waits for the next normal cycle. If the target host
is unavailable, the event remains pending. The queue and
Worker both validate the receipt schema, target, token, status, and canonical
delivery id before removing the pending event. Repeated verified receipts are
idempotent; unverified or unknown receipts fail closed. An unavailable or
mismatched artifact makes the outbox `COACH_OUTBOX_INVALID`.

Each newly queued Coach event also freezes a queue timestamp and the next
normal daily cloud cycle's bounded three-hour completion window as its
delivery-proof deadline. Canonical status reports signed time remaining,
`PENDING_WITHIN_SLA`, or
`BREACH_PENDING_DELIVERY_PROOF`. When the next due heartbeat accepts a verified
task-readback receipt, canonical state preserves the queue time, deadline,
acknowledgement time, integer lead time, and `PASS` or `BREACH`. Untimed legacy
events or receipts remain explicit `MISSING_PROOF`; no task output or chat
history may synthesize the missing clock.
`SEALED_COACH_CROSS_TASK_DELIVERY_V5` freezes the same basis, 10,800-second
completion window, PASS/BREACH labels, and legacy missing-proof labels inside
the V9 write attestation. An older caller hash therefore fails before queue
mutation instead of silently opting out of delivery measurement. V9
binds the platform recurrence `09:05 Asia/Taipei` and the unchanged canonical
due boundary `09:00 Asia/Taipei`. The nominal five-minute delay was introduced
after the 2026-08-06 nominal 09:00 platform run was read back at
08:59:24.393178 while canonical `next_due` was still 09:00; the task correctly
made no early write. The delay absorbs comparable early-fire jitter without
adding a second timer or weakening the server gate.

Weekly and monthly report content binds the exact reporting period before its
artifact hash is calculated. A crash after report sealing but before heartbeat
state commit is recovered by adopting the newer current-period artifact on the
next normal heartbeat and placing it in the durable Coach outbox exactly once.
The Worker never overwrites the sealed report, and byte-identical quiet-period
summaries cannot collide under one pending delivery id.

The dispatch path watches both `pending.json` and an interrupted
`running.json`. The oneshot restarts only after an abnormal process stop and
resumes the same schema-bound request idempotently, preserving its original
`started_at` plus a recovery count. This also recovers an in-flight request
after a host reboot without waiting for the next cloud day. Restart attempts
are bounded; ordinary nonzero exits are sealed as `FAILED`, while the older
lease-based `STALE_RECOVERED` path remains the final fail-closed fallback. This
is event recovery, not a second research clock or timer.

## Immutable dual release lanes

Research Worker upgrades use exactly two fixed symlinks beneath
`/opt/agora-research-worker`:

- `control-current` is the only release used by the OAuth MCP, request dispatch,
  and heartbeat services. Their documentation, working directory, policy,
  launcher, and explicit application directory all resolve through this lane.
- `current` remains the only release used by the public candle source and
  ingest plus the continuous microstructure source and intake. Those units
  never reference `control-current`.

An ordinary inactive-source installation atomically points both symlinks at the
new immutable release. It retains the default fail-closed rejection of an
active or failed microstructure source. The deployment wrapper exposes one
explicit `-PreserveBoundDataPlane` switch for the separate case in which an
already-bound forward data plane must remain uninterrupted. The switch passes
only the fixed installer attestation `PRESERVE_BOUND_DATA_PLANE=1`; it is never
inferred from service state or an environment default, rejects binding creation
parameters, and is unavailable in package-only mode.

Before preserve mode creates a new release or changes a link, it requires the
source unit to be disabled and not failed, resolves `current` inside the
immutable releases root, and proves that the canonical V3 binding, installed
manifest and provenance, and unique V3 state all agree on the bound release.
It seals the literal and resolved data link, binding and state bytes plus
SHA-256, bound manifest and provenance hashes, active/inactive state, nonzero
MainPID when active, and fixed unit properties. Preserve mode installs only the
three control service units, atomically switches only `control-current`, and
restarts only the control MCP. It never relinks or changes `current`, installs a
data-plane unit, prepares a binding, changes data directories, touches sealed
state/evidence, or stops, starts, signals, enables, disables, reloads, or clears
the microstructure source.

After the control restart, the installer requires every sealed data-plane byte,
hash, link, source state, MainPID, and unit property to remain identical and
requires `control-current` to resolve to the new release. The deployment wrapper
then runs the installed verifier with exact expected control and data release
ids, the observed source-state expectation, and explicit intake preflight when
the source is active. Release provenance is printed only after that verifier
succeeds. The verifier independently validates both symlinks beneath the
immutable release root, checks Worker policy/provenance against
`control-current`, checks binding/distribution/intake/source evidence against
`current`, and proves the control/data unit paths are disjoint.

The canonical data root is fixed at `agora-research:agora-evidence 0710`.
This grants the unprivileged public-source identity traversal only; canonical
state and control directories keep their narrower ownership and modes. Ordinary
inactive-source installation may normalize this root metadata. Preserve mode
must verify the exact metadata and source traversal before creating a release,
and must fail closed without changing it when the invariant is absent.
An inactive-source installation still requires a strictly future V3 start day.
Once preserve mode has independently proved that the bound source is active,
verification accepts the immutable binding after that start day so later
control-only upgrades do not require restarting or rebinding the data plane.

`-PackageOnly` remains the clean-commit, offline, no-host/no-key closure gate and
does not exercise preserve mode. Real installation, unchanged live PID,
heartbeat recovery, forward candle completion, microstructure day acceptance,
and any strategy or economic result remain separate `MISSING_PROOF` gates.
This dual-lane preparation does not authorize deployment or another timer.

## Continuous microstructure producer preparation

The continuous OKX microstructure producer is packaged as a separate,
non-Spring Java 21 distribution. The inactive-by-default Maven profile
`microstructure-research-dist` emits only
`com/agora/research/OkxMicrostructure*.class` plus the three required Jackson
runtime jars under `target/microstructure-dist`. The Research Worker packager
requires a clean commit and builds a private package tree from `git archive`
bytes at that exact `HEAD`. Its committed runtime allowlist is only
`research_pipeline`, `research_mcp`, `research_source`, `research`, and
`scripts/research-worker`, plus the single committed file
`docs/autonomous-research-charter.md`. It invokes Git archive with
`core.autocrlf=false`, so package bytes are the committed `HEAD` blob bytes
independent of the Windows checkout setting. The broad `docs` tree, `src`,
`pom.xml`, the live worktree, and all other roots are excluded. Package closure
allows the `docs` parent only when its sole child is that regular non-symlink
charter file. The only non-Git input is the fresh offline profile output, whose
exact closure is the canonical microstructure jar plus the three Jackson jars.
Extra files or directories, links/reparse points, nested Git or
`.research-state`, Python bytecode/cache, and environment, credential, or
secret material fail closed.

The packager hashes every file in that private tree, creates the archive from
the same tree, extracts it into a second private directory, and requires exact
path-and-SHA-256 equality before either package-only success or upload. The
installer repeats the fixed-root, exact-distribution, no-link, and complete
manifest checks before creating a release. `verify-worker.sh` independently
requires the same installed runtime closure plus only the generated
`.release/source.sha256` and `.release/provenance.json`; the source manifest
must cover every pre-install package file, including the exact charter, and no
metadata, documentation sibling, or unknown path.

`scripts/deploy_research_worker_upgrade_ssh.ps1 -PackageOnly` performs the same
clean-commit gate, offline build, private staging, manifest, archive, extraction,
and equality proof, prints only commit/count/hash provenance, and exits before
SSH or SCP. It is the immediate post-commit release gate and must not be run as
a workaround from a dirty worktree. Successful local package-only validation
still leaves Linux installation, systemd, identity/capability, filesystem,
liveness, and future-evidence proof pending.

The fixed launcher is
`scripts/research-worker/run-microstructure-continuous-source.sh`. Before it
executes Java, it requires Java 21, resolves `current` inside the immutable
release tree, rejects symlinked binding/distribution files, verifies the whole
installed source manifest, and proves that the binding release id and manifest
hash equal installed provenance and the byte-level SHA-256 of
`.release/source.sha256`. It then uses a fixed classpath and the sole main class
`com.agora.research.OkxMicrostructureContinuousSourceCli`. It accepts no
caller-selected path, class, endpoint, instrument, channel, or argument and
never invokes Maven, Maven exec, Spring, or the Trading application.

An optional binding is installed only when both a strictly future UTC start day
and frozen diagnostic id are explicitly supplied to the upgrade. The fixed
path is
`/etc/agora-research/okx-microstructure-continuous-source-v3.json`; it is
atomically written as `root:agora-evidence` mode `0640`. Its
`producer_release_id` is the installed release id and its
`producer_manifest_sha256` is the actual installed source-manifest hash, never
a task hash. An existing V3 binding is accepted only when its canonical bytes,
fixed V3 contract hashes, release identity, manifest hash, ownership, and mode
are already exact; it is never replaced. The source service must be disabled,
inactive, and not retain a failed state. A prior expected stop recorded as
failed must be reviewed and cleared through a separate explicit preflight,
never silently normalized by the upgrade.
Ordinary upgrades without both parameters do not create or replace it.

The dedicated source unit runs as credential-free `agora-evidence-source`, has
`Restart=no`, a bounded runtime, no `EnvironmentFile`, no timer, no `[Install]`
enablement, no canonical-state or Trading-secret access, and write access only
through the fixed common `/var/lib/agora-evidence-source` mount-namespace
parent. That single `ReadWritePaths` entry preserves same-namespace atomic
rename from private staging to the microstructure drop. It does not grant Unix
write permission by itself: the common parent remains `root:root` mode `0755`,
and the existing ownership and modes of the staging and drop children remain
the DAC boundary. Installation leaves the unit disabled and inactive. The
legacy candle source and evidence-ingest units retain their existing
`pending.json` paths and are never reused.

The separate microstructure intake preparation is
`research_pipeline.microstructure_intake_cli`. The historical V2 commands
`initialize` and `ingest` retain their fixed V1-named binding, state namespace,
public API, validation, and byte transitions. Deployment uses only the explicit
V3 commands `initialize-v3` and `ingest-v3`; no command accepts a
caller-selected version, path, identity, endpoint, lifecycle, or policy. The V3
profile binds the fixed installed release manifest,
`/etc/agora-research/okx-microstructure-continuous-source-v3.json`, the five
exact frozen V3 contract hashes, the microstructure drop, and dedicated
`/var/lib/agora-research/state/microstructure-v3` namespace. `initialize-v3` is
an installer-only future-window operation. It never overwrites state. `ingest`
and `ingest-v3` each require their own versioned state and advance it only
through the corresponding frozen canonical validator and atomic commit APIs.
Invalid contract bytes seal `INTEGRITY_BLOCKED`; stale
locks, temporary files, symlinks, ambiguous structures, capacity failure, or
filesystem mismatch stop for manual recovery without moving or deleting bytes.

The failed R1 private-staging bytes and unmatched drop reservation remain
preserved and are unusable as a complete 14-day diagnostic. This namespace fix
does not recover, retry, move, remove, or reinterpret them. A separately
authorized Manager recovery must archive rather than delete R1, deploy only a
clean reviewed commit, create a new strictly future untouched generation, and
prove source and intake health as separate gates.

Every accepted day has one matching zero-byte publication reservation and
exactly one canonical bundle plus envelope. The envelope release id and
manifest hash must equal the installed binding. The drop and private staging
roots must share a filesystem, at least 2 GiB must remain free, and no more than
14 day/reservation pairs are allowed. The drop parent is
`root:agora-evidence` mode `1770`: the publisher group can create a new atomic
entry, while the sticky root-owned parent prevents it from removing a day after
intake first changes the day directory to `root:agora-research` mode `0550` and
then changes its reservation and files to mode `0440`. Byte hashes are checked
before and after that idempotent metadata-only freeze. Already-correct metadata
causes no `chown` or `chmod`, so a duplicate path event cannot retrigger itself.

`agora-research-microstructure-intake.service` is a network-denied oneshot with
`Restart=no`, no environment file, and no evidence-group membership. Its exact
capability set is `CAP_DAC_READ_SEARCH`, `CAP_CHOWN`, and `CAP_FOWNER`; it has no
`CAP_DAC_OVERRIDE`. The drop is exposed writable in the mount namespace only so
those metadata calls can succeed. Ordinary DAC denies this identity creation,
content writes, rename, and unlink. Only its dedicated state root is a data-write
target. The V3 intake unit executes only `ingest-v3`, sees only the V3 binding
and current release, writes only the shared drop metadata and
`state/microstructure-v3`, and makes the historical `state/microstructure`
namespace inaccessible. `agora-research-microstructure-intake.path` watches the fixed drop root
and is the only microstructure event trigger; it is not a timer or lifecycle
clock. The installer enables this intake path but leaves the producer disabled
and inactive.

Before any V3 installation step, the installer inventories the optional legacy
V1-named binding and the optional singular V2 state JSON as regular non-symlink
files and records their exact SHA-256 values without parsing or reinterpreting
their contents. A symlink, lock, temp, additional entry, or noncanonical state
filename blocks cutover. The identical path/type/byte/hash inventory is required
after all V3 steps and is sealed create-only outside both state namespaces;
later verification recomputes and compares it. Installer, V3 service, and
verifier never overwrite, delete, move, relabel, `chmod`, or `chown` those
legacy paths. V1 and V2 therefore remain byte-preserved historical inputs, not
fallbacks or selectable runtime profiles.

This remains deployment preparation, not deployment authorization. A separate
server preflight must still prove installed identities and capabilities,
same-filesystem atomicity, real capacity, path retrigger and crash behavior,
source denial, liveness, reconnects, and 14 complete future days. Predictive
value, fees, slippage, drawdown, and PnL remain `MISSING_PROOF`. Cloud Ops remains
the sole research lifecycle clock, Server Canonical remains the sole research
state writer, and the candle evidence chain remains byte-separated.

The existing daily heartbeat also reads, but never writes, the dedicated
`state/microstructure-v3` namespace through one strict monitor. `get_research_status`
and heartbeat results expose a separate `microstructure_diagnostic` object with
the frozen diagnostic identity, start and next day, accepted/required counts,
canonical state artifact path and SHA-256, and UTC-date lag classification. An
absent namespace is `NOT_CONFIGURED`; an empty, ambiguous, symlinked,
noncanonical, invalid, or recovery-marker namespace is `RECOVERY_BLOCKED`.
`WAITING_FOR_DAY` becomes `CAPTURE_OVERDUE` only when its next expected UTC day
is earlier than the heartbeat day. These values never change the candle
research status or its 90-day trigger.

On a changed canonical-state fingerprint, `DIAGNOSTIC_READY` adds exactly one
`EVIDENCE_REVIEW_DUE` item to the existing durable Coach outbox with next action
`DISPATCH_VALIDATED_LOCAL_MICROSTRUCTURE_DIAGNOSTIC_TASK`; `INTEGRITY_BLOCKED`,
`CAPTURE_OVERDUE`, or safely hash-backed recovery adds one `INTEGRITY_ALERT`.
The unchanged fingerprint is not re-enqueued, while an unacknowledged event
remains in the existing outbox. Every direct event uses the canonical state
file and its verified SHA-256. Recovery ambiguity without a safely hashable
state artifact raises into the existing heartbeat failure record. No monitor
artifact, timer, schedule change, retry, repair, backfill, source restart,
candidate action, OOS access, or second writer is introduced.

The fixed handoff exporter is
`agora-research-microstructure-handoff-export.service`, a disabled and inactive
network-denied oneshot with no timer, path unit, restart loop, environment
selection, or canonical-state write. Its documentation, working directory,
and zero-argument Python module come only from `control-current`; the immutable
V3 binding, canonical state, retained 14-day evidence, and installed producer
release remain read-only data-current inputs. It runs as `agora-research` with
the command-scoped supplementary group `agora-evidence`, while the account is
not added to that publisher group globally. Capabilities are empty, address
families are limited to `AF_UNIX`, and only the pre-provisioned handoff staging
and final roots are writable. The unchanged exporter module remains
create-once and fail closed.

`scripts/pull_microstructure_v3_handoff_ssh.ps1` makes one fixed remote call:
it starts exactly that oneshot and streams the fixed final package with
`sudo -n tar` only after the start succeeds. An early `NOT_READY` may therefore
end without a package; the pull does not retry, backfill, synthesize readiness,
or change state. A completed local retry validates its existing archive or
inbox without another remote call. Installation places and statically verifies
the unit in ordinary and `-PreserveBoundDataPlane` modes but never enables or
starts it. Real systemd execution, `NOT_READY` no-write behavior,
`DIAGNOSTIC_READY` export, transfer, Local diagnostic and interpretation, and
all economic or activation claims remain separate proof gates.

## Cutover

1. Deploy the V2 release, OAuth MCP, request directories, dispatch path, and
   nginx route while the V1 timer is still the clock.
2. Verify OAuth discovery, unauthenticated denial, DCR, PKCE, token refresh,
   tool listing, queue confinement, and read-only state mounts.
3. Register the private MCP connection in ChatGPT and test one manual Ops call.
4. Create one web scheduled task inside the Ops task and confirm it can use the
   connected plugin without the desktop project.
5. Disable `agora-research-heartbeat.timer` before enabling the cloud schedule.
6. Run one cloud-triggered heartbeat, verify the sealed result, and confirm the
   local desktop may be offline.

At no point may the V1 timer and cloud schedule both be active routine clocks.
The systemd path unit is an event consumer, not an additional schedule.

## Acceptance

- the Trading application binary, health, strategies, orders, funds, database,
  and runtime schedulers are unchanged;
- the MCP listens only on loopback and nginx exposes only its fixed routes;
- unauthenticated MCP calls return OAuth discovery challenges;
- the MCP cannot write canonical state or inbox and cannot read Trading secrets;
- the public source identity cannot read canonical state or Trading secrets,
  and the canonical evidence intake cannot access the network;
- a queued request survives HTTP completion and produces one sealed result;
- early and concurrent calls cannot create extra heartbeat requests;
- an invalid, oversized, evidence-unbound, or policy-incomplete candidate bundle
  cannot modify canonical research state;
- missing, tampered, or dirty Worker provenance blocks both queue write
  operations before any request or evidence-capture file is created;
- canonical status exposes the exact versioned cloud Ops schedule contract,
  and a missing, altered, or mismatched contract attestation blocks both write
  operations before queue mutation;
- an accepted candidate bundle records the exact candidate frozen time,
  evidence-ready lead time, and whether the 24-hour SLA passed; retry after a
  post-freeze finalize interruption preserves all three values;
- a parity, diagnostic-only, OOS-less, or closed historical adapter cannot be
  registered as a forward-evidence strategy candidate;
- the eligible adapter accepts only canonical `candidate_context`, creates a
  separate candidate-bound OOS trigger, and recovers interrupted registration;
- a failed candidate request with matching partial state is visible through one
  exact canonical replay bundle; repeated failure or payload drift blocks it;
- a normal candidate request cannot create a queue item while its canonical
  trigger is missing, waiting, closed, or lacks hash-verified ready proof;
- canonical status exposes the exact hash and row readiness of the retained
  pre-2025 selection corpus without importing or recomputing it;
- a crashed queue lease remains auditable and can no longer block the queue
  indefinitely;
- a hard-stopped or reboot-interrupted running request resumes under the same
  request id without waiting for the next daily cloud wake;
- every MCP briefing returns a sealed artifact id and SHA-256;
- a weekly or monthly artifact sealed before an interrupted heartbeat-state
  commit is adopted and queued exactly once on recovery, with its reporting
  period bound into the artifact hash;
- canonical status exposes every pending Coach event through a bounded,
  hash-verified, durable outbox that survives later routine heartbeats;
- the active schedule contract binds Coach delivery to exact task discovery,
  read-before-send deduplication, canonical prompt copying, and post-send
  readback, while only a verified receipt on the next due heartbeat can
  acknowledge it and target unavailability remains pending rather than adding
  a timer or messenger;
- canonical status measures queue-to-verified-receipt proof against the next
  normal cloud cycle's bounded three-hour completion window, preserves `PASS`
  or `BREACH`, and exposes legacy timing as `MISSING_PROOF`;
- canonical status returns a clean, hash-verified Worker release and Git commit;
- server verification proves the unprivileged Worker identity can read and
  validate that release provenance, every manifest-listed file, and the exact
  installed source inventory;
- canonical status exposes the forward-day count, lag, hash-chain head, and
  next capture deadline, and a missed day becomes an integrity alert;
- the last canonical day produces one typed manifest, mechanism-neutral
  diagnostic, and sealed ready review, while a still-missing final day remains
  capture-first;
- a complete-but-unreviewed candidate OOS window is recovered by the existing
  heartbeat, and a no-candidate discovery review reaches Coach exactly once;
- the systemd heartbeat timer is disabled after cloud cutover;
- no source timer exists; source and intake are event-driven path units;
- the single web Ops schedule runs with the local computer off.

## Active cross-task Coach delivery V9

Fresh server-canonical status at `2026-08-10T04:36:55Z` reports Worker release
`20260810T042111Z`, source commit
`9b436c4fcd8f996cc682e2c14bfe8b2f3148ce57`, and
`CLOUD_OPS_SCHEDULE_V9` `READY`. Platform cutover readback proved zero active
recurrences while the exact existing ChatGPT Work task
`6a71a1ed2f608191b0621c52bed3fd81` was paused and exactly one active recurrence
at `09:05 Asia/Taipei` after its in-place update. The Codex desktop automation
remains paused and is not a cloud substitute.

The repository freezes `CLOUD_OPS_SCHEDULE_V9` at exact SHA-256
`04d11ad095f64c6dda7d746cf36f26af773f53684765c368d6fe595533ab7d2c`.
V9 retains the Research MCP operation set and all server-side outbox state:
artifact SHA-256 delivery id, exact token prefix, maximum eight five-field
receipts, verified receipt statuses, idempotent replay, pending-to-delivered
transition, immutable queue/deadline timestamps, integer lead time, and the
10,800-second canonical `PASS`/`BREACH` calculation. No new ACK operation,
writer, timer, messenger, paid API, or user step is introduced.

V9 corrects a V8 orchestration contradiction discovered before V8's first
normal run. V8 delivered an initially pending event only after the due
heartbeat and deferred its verified receipt to another daily heartbeat, while
the immutable SLA deadline ended three hours after the first due heartbeat.
That ordering could not produce a timely canonical ACK.

V9 first requires the heartbeat to be normally due, snapshots the initial
pending ids, resolves and reads exact Coach task
`019fca63-4f8f-71e3-9d88-297bca468eb9`, sends only absent exact canonical
prompts, and requires post-send readback. Only those initial-snapshot verified
receipts enter the same due heartbeat. A new event created by that heartbeat is
delivered after it and may be acknowledged only on the next normal cycle. Any
discovery, read, send, or readback failure keeps the event pending as
`CROSS_TASK_DELIVERY_PENDING`.

The completed cutover paused and reused the exact existing ChatGPT Work
schedule; creating a second schedule remains forbidden. Repository
implementation, packaging, Worker deployment, and platform task
update/readback are proven. The first normally due V9 task execution, live
send/readback, same-cycle receipt acceptance, post-heartbeat delivery, and
terminal SLA outcome remain separate `MISSING_PROOF` gates.
Existing pending events keep their original timestamps and may honestly remain
`BREACH`.

## Repository-prepared heartbeat liveness decoupling V10

The repository contains frozen successor `CLOUD_OPS_SCHEDULE_V10`, SHA-256
`90e0de95fa34beff9447640a5dcdbb972278014664806df0a4bf5f36e2598faa`,
but V9 remains deployed and active until a separately bounded cutover. V10
preserves the exact existing schedule id, one `09:05 Asia/Taipei` recurrence,
the `09:00` canonical due boundary, 300-second margin, five Research MCP
operations, Server Canonical sole writer, and every receipt and scientific
gate. The server attestation must move to V10 before the platform prompt may
attest or activate it.

The existing request and Worker path already accepts a heartbeat payload with
an empty `coach_delivery_receipts` array. V10 makes that existing behavior an
explicit caller rule: when exact Coach list/read/send is unavailable or cannot
produce exact readback, the event contributes no receipt and the otherwise
valid normally due heartbeat may proceed. Canonical pending events, delivery
ids, queue timestamps, deadlines, and `PENDING_WITHIN_SLA` or
`BREACH_PENDING_DELIVERY_PROOF` debt remain unchanged. A successful heartbeat
reports research advancement separately and never claims Coach or user
delivery.

Verified receipts still require the exact five fields, full token, target task,
preflight or post-send readback, allowed status, canonical id, at-most-eight
initial snapshot, and next-cycle deferral for post-heartbeat new events.
Unknown, unverifiable, or changed receipts fail closed. Invalid outbox state,
bad attestation, dirty Worker provenance, not-due time, non-idle queue, and
evidence, candidate, OOS, deduplication, or scientific failures remain blocking.
No new ACK operation, timer, schedule, writer, messenger, paid API, user step,
or heartbeat/server semantic surface is added.

V10 Worker deployment, exact in-place schedule cutover, zero-active and
exactly-one-active readback, live empty-receipt acceptance, live pending-event
preservation, lawful R1/R2 advancement, future task-tool availability, Coach
delivery proof, and economic value remain `MISSING_PROOF`.
