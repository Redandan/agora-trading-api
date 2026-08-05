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
manifest hash is rechecked on every status read. Missing, malformed, tampered,
or dirty provenance fails closed: both server write operations return
`WORKER_RELEASE_INTEGRITY_BLOCKED` before touching either queue. This never adds
a new MCP operation or exposes a server filesystem path.

The same status exposes the active frozen `CLOUD_OPS_SCHEDULE_V3` contract and its
byte-level SHA-256. The two write operations require that hash in the
`ops_schedule_contract_sha256` argument. A missing or changed contract returns
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

Canonical status derives `coach_outbox` from durable server heartbeat state,
not from whichever heartbeat happened to run most recently. For every material
event it confines the relative path to canonical state,
re-hashes the artifact, rejects missing or duplicate delivery ids, and returns
the complete structured event under `EVENTS_PENDING_EXTERNAL_DELIVERY`. The
sealed artifact SHA-256 is the delivery id. Routine heartbeats leave older
pending events intact. Each verified event also includes a deterministic
delivery token and exact canonical delivery prompt. The V3 schedule contract
permits only task
list/read/send operations for this handoff: the cloud cycle reads the exact
Coach task before sending, deduplicates by artifact SHA-256, sends once, and
reads back before claiming verified delivery. If the target host is unavailable,
the event remains pending. A bounded exact receipt for a preflight or post-send
readback may be carried only by the next normally due heartbeat. The queue and
Worker both validate the receipt schema, target, token, status, and canonical
delivery id before removing the pending event. Repeated verified receipts are
idempotent; unverified or unknown receipts fail closed. An unavailable or
mismatched artifact makes the outbox `COACH_OUTBOX_INVALID`.

The dispatch path watches both `pending.json` and an interrupted
`running.json`. The oneshot restarts only after an abnormal process stop and
resumes the same schema-bound request idempotently, preserving its original
`started_at` plus a recovery count. This also recovers an in-flight request
after a host reboot without waiting for the next cloud day. Restart attempts
are bounded; ordinary nonzero exits are sealed as `FAILED`, while the older
lease-based `STALE_RECOVERED` path remains the final fail-closed fallback. This
is event recovery, not a second research clock or timer.

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
- an accepted candidate bundle records the evidence-ready lead time and whether
  the 24-hour SLA passed;
- a parity, diagnostic-only, OOS-less, or closed historical adapter cannot be
  registered as a forward-evidence strategy candidate;
- the eligible adapter accepts only canonical `candidate_context`, creates a
  separate candidate-bound OOS trigger, and recovers interrupted registration;
- canonical status exposes the exact hash and row readiness of the retained
  pre-2025 selection corpus without importing or recomputing it;
- a crashed queue lease remains auditable and can no longer block the queue
  indefinitely;
- a hard-stopped or reboot-interrupted running request resumes under the same
  request id without waiting for the next daily cloud wake;
- every MCP briefing returns a sealed artifact id and SHA-256;
- canonical status exposes every pending Coach event through a bounded,
  hash-verified, durable outbox that survives later routine heartbeats;
- the active schedule contract binds Coach delivery to exact task discovery,
  read-before-send deduplication, canonical prompt copying, and post-send
  readback, while only a verified receipt on the next due heartbeat can
  acknowledge it and target unavailability remains pending rather than adding
  a timer or messenger;
- canonical status returns a clean, hash-verified Worker release and Git commit;
- server verification proves the unprivileged Worker identity can read and
  validate that release provenance;
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
