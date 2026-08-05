# Server Research Worker V1

Status: `SUPERSEDED_BY_SERVER_RESEARCH_WORKER_V2`

The migration and rollback evidence in this document is retained. The active
clock and remote-control contract now live in
`docs/server-research-worker-v2.md`.

## Purpose

Move the deterministic autonomous-research heartbeat and its single mutable
state authority off the Codex desktop host. This change improves control-plane
availability; it makes no strategy, PnL, drawdown, SHADOW, PAPER, or LIVE claim.

## Deployment boundary

The worker is not part of `TradingApiApplication` and is never deployed by the
Trading blue-green runtime workflow.

| Concern | Contract |
| --- | --- |
| Code | Root-owned releases under `/opt/agora-research-worker/releases` |
| Current release | Atomic `/opt/agora-research-worker/current` symlink |
| Process identity | System user/group `agora-research` with no login shell |
| Canonical state | `/var/lib/agora-research/state` |
| Sealed heartbeat inbox | `/var/lib/agora-research/inbox` |
| Schedule | One systemd timer at 09:00 `Asia/Taipei`, persistent across reboot |
| Entry point | `python3 -m research_pipeline --state-dir ... --policy ... heartbeat` |
| Network | Denied by the systemd service sandbox |
| Credentials | No Trading, exchange, database, SSH, Telegram, or Codex secrets |
| Runtime integration | No Spring bean, HTTP/MCP endpoint, port, database, or strategy catalog |

The code release may contain offline research adapters so their frozen sources
remain inspectable. The timer does not receive capabilities or credentials that
would let an adapter reach Production. A future step that needs unavailable
evidence fails closed and requires a separately versioned read-only evidence
adapter; it must not borrow Trading application secrets.

## State and event contract

`state/authority.json` must contain `mode=SERVER_CANONICAL`. A migrated local
state must contain `mode=REMOTE_READ_ONLY_REPLICA`; acquiring its pipeline lock
then fails before any research mutation.

Every heartbeat writes its JSON result atomically to a timestamped inbox file
and updates `latest.json` atomically. Scientific WAIT is a successful eventless
result. A non-zero heartbeat result remains sealed and makes the oneshot service
fail so systemd exposes the operational fault; it never retries research logic
inside the same invocation.

The accepted notification event types remain:

- `MATERIAL_LEARNING`
- `WEEKLY_BRIEF_READY`
- `MONTHLY_REVIEW_READY`
- `EVIDENCE_REVIEW_DUE`
- `INTEGRITY_ALERT`

The worker does not send messages. Codex Ops may read and interpret sealed inbox
events, then teach the sponsor through the Coach task. Reading does not grant
the Ops task another mutable state writer.

## Single-writer cutover

The cutover order is mandatory:

1. Validate and package the exact local code and state snapshots with SHA-256
   manifests while no `pipeline.lock` exists.
2. Install the release and migrate state with the server timer disabled.
3. Verify every migrated state file, `SERVER_CANONICAL`, Unix ownership, and
   systemd sandbox configuration.
4. Disable, but do not delete, the Codex automation
   `autonomous-research-heartbeat`.
5. Run exactly one manual server heartbeat and verify its sealed inbox result.
6. Enable and start `agora-research-heartbeat.timer`.
7. Mark the local state `REMOTE_READ_ONLY_REPLICA` and verify a local heartbeat
   is rejected before lock creation.

Never enable the server timer while the Codex heartbeat is active. Never copy
server state back over the local replica as a new writer.

## Rollback

Disable the server timer first. Preserve the entire canonical state and inbox,
including failure artifacts. Restore the pre-cutover state archive only into a
new directory, compare hashes, and choose one authority explicitly. Re-enable
the Codex automation only after the server timer is disabled and the chosen
state is made writable. Rollback must not merge two independently mutated
state trees.

## Acceptance

- the Trading application commit, ports, health, strategies, OCO/Grid, orders,
  funds, database, and environment files are unchanged;
- `agora-research-heartbeat.timer` is the only autonomous-research lifecycle
  timer and has a valid next run;
- the old Codex heartbeat is disabled and recoverable;
- the manual server heartbeat reports `HEARTBEAT_OK` and the expected research
  state, or fails closed with a sealed `INTEGRITY_ALERT`;
- local heartbeat mutation is refused as a remote read-only replica;
- no worker process listens on a network port and the worker cannot read the
  Trading secret file.
