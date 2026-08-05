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
- an accepted candidate bundle records the evidence-ready lead time and whether
  the 24-hour SLA passed;
- a crashed queue lease remains auditable and can no longer block the queue
  indefinitely;
- every MCP briefing returns a sealed artifact id and SHA-256;
- canonical status exposes the forward-day count, lag, hash-chain head, and
  next capture deadline, and a missed day becomes an integrity alert;
- the last canonical day produces one typed manifest, mechanism-neutral
  diagnostic, and sealed ready review, while a still-missing final day remains
  capture-first;
- the systemd heartbeat timer is disabled after cloud cutover;
- no source timer exists; source and intake are event-driven path units;
- the single web Ops schedule runs with the local computer off.
