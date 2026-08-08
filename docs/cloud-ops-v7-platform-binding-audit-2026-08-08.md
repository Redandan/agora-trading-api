# Cloud Ops V7 Platform Binding Audit — 2026-08-08

## Disposition

`PLATFORM_CROSS_SURFACE_REBIND_UNSUPPORTED_KEEP_V6_PAUSED`

The existing ChatGPT Work scheduled task must remain paused. Its recurrence is
the intended single `09:05 Asia/Taipei` clock, but its saved prompt and chat
binding are V6. The platform update surface exposed to that task can change the
prompt, recurrence, title, timezone, start offset, and enabled state; it cannot
change the destination chat. Updating or resuming it therefore cannot establish
the V7 same-Coach-chat contract.

The supported V7 path is the frozen contract's fallback path: create the V7
task from the intended Coach chat while it is disabled, prove zero active
recurrences and the exact destination, activate only that task, and delete the
paused V6 task only after live acceptance. No such task was created in this
audit because the current Codex session did not expose a scheduled-task update
tool. That absence is session-specific evidence, not a claim that Codex cannot
schedule tasks generally.

## Canonical server evidence

Fresh OAuth Research MCP status at `2026-08-08T11:20:29Z` reported:

- policy `AUTONOMOUS_TRADING_RESEARCH_V3` `READY`, SHA-256
  `a82ccff13c13765d1e94a29698a43b35b847ed19190965590fa72e9a102981f6`;
- Worker `READY`, release `20260808T052741Z`, source commit
  `c6247e8074803227c8a83b44dcc331aec2956e6d`, clean and source verified;
- `CLOUD_OPS_SCHEDULE_V7` `READY`, SHA-256
  `426f4a9d1f252a610a89e30fcd2a7f890b6bc26f2cb9e7fbf003a08839d5f144`;
- all request queues `IDLE`;
- general forward evidence `2/90`, lag `0`, awaiting the 2026-08-08 day close;
- microstructure R1
  `okx-btcusdt-microstructure-forward-v3-20260809-r1` in
  `PRE_START / WAITING_FOR_DAY`, `0/14`, start day `2026-08-09`;
- latest heartbeat `HEARTBEAT_OK`, next due
  `2026-08-09T01:00:00Z` (`09:00 Asia/Taipei`);
- one Coach outbox event still pending with
  `BREACH_PENDING_DELIVERY_PROOF`.

These facts prove server readiness only. They do not prove a platform
same-chat binding or an active cloud recurrence.

## Platform schedule readback

The Scheduled view and a read-only automation audit found exactly one existing
research task:

- title: `Autonomous Research Ops｜每日研究心跳`;
- schedule id: `6a71a1ed2f608191b0621c52bed3fd81`;
- state: `PAUSED`, `is_enabled=false`;
- recurrence: daily at `09:05`, `Asia/Taipei`, never ends;
- raw recurrence:
  `DTSTART;TZID=Asia/Taipei:20260805T090500` and
  `RRULE:FREQ=DAILY`;
- saved prompt: V6 id/hash and V6 cross-task delivery rules, not V7;
- destination conversation:
  `6a71a167-be58-83ec-aed2-f1736e31dd45`, the prior ChatGPT Work Ops
  conversation, not Coach task
  `019fca63-4f8f-71e3-9d88-297bca468eb9`;
- exposed update schema: required `jawbone_id`; optional `prompt`, `schedule`,
  `default_timezone`, `is_enabled`, `title`, and `dtstart_offset_json`;
- no destination, conversation, Codex thread, or host field was exposed.

The UI also displayed `10:05`. The authoritative recurrence readback remained
`09:05 Asia/Taipei`; the unexplained display difference is `MISSING_PROOF` and
must not be used to alter the frozen cadence.

The audit made no schedule mutation. In particular, it did not update or resume
the V6 task.

## Official platform capability boundary

OpenAI's Scheduled tasks documentation says that a task created inside an
existing chat returns to that chat and uses its existing context. It also says
ChatGPT or Codex chats can request a task whose runs return to the current chat,
while web-created tasks cannot directly retain a local folder or worktree.

Source, reviewed 2026-08-08:
<https://learn.chatgpt.com/docs/automations>

This supports creating the successor from the intended Coach chat. It does not
establish that an existing task owned by another chat can be rebound, and the
observed update schema provides no such operation.

## Safe activation sequence

Proceed only when the intended Coach chat exposes scheduled-task management:

1. Re-read platform state and prove the V6 task remains paused and the active
   recurrence count is zero.
2. Create a V7 task from the intended Coach chat with the exact frozen daily
   prompt, exact `09:05 Asia/Taipei` recurrence, and `is_enabled=false`.
3. Read it back before activation. Prove its destination is the current Coach
   chat, its prompt binds only the exact V7 hash, and the active recurrence
   count is still zero.
4. Re-read canonical Research status. Continue only while policy, Worker, and
   the exact V7 contract remain `READY`, queues are `IDLE`, and the normal due
   boundary has not been bypassed.
5. Enable the V7 task and immediately prove exactly one active recurrence and
   the fixed same-chat destination. Do not manually trigger or catch up a
   heartbeat.
6. Observe normal Turn N exact rendering and normal Turn N+1 receipt acceptance.
   Keep delivery proof `MISSING_PROOF` until canonical acknowledgement exists.
7. Delete the paused V6 task only after live V7 acceptance, as required by the
   frozen fallback order.

If disabled preparation, destination readback, or one-active-recurrence readback
is unavailable, stop with V6 paused. Do not weaken the same-chat contract, use
the Scheduled inbox or a notification as receipt proof, or add another timer,
poller, writer, messenger, webhook, paid API, or user-operated research step.

## Scope and performance boundary

This was a read-only platform and canonical-state audit. It created no
hypothesis, candidate, experiment, OOS access, heartbeat, Research MCP write,
schedule change, Trading scheduler, database mutation, order, fund action, or
SHADOW/PAPER/LIVE action.

The immediate PnL and drawdown effect is zero. The value of resolving the
binding is lower learning latency and reliable delivery of sealed research
results; that value remains unquantified until a live V7 two-turn cycle is
accepted.
