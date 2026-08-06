# Local Codex Research Node V1

Status: `PILOT_V1`

## Purpose

The local Codex research node adds bounded compute and implementation capacity
without adding another research clock or another source of truth. The user sees
two long-lived Codex tasks:

1. the Manager/Coach task, which sets priorities, teaches, and interprets
   results; and
2. the Local Research task, which performs only an explicitly dispatched task
   package while this computer and Codex are available.

The existing cloud Ops task remains the only routine timer. The server Research
Worker remains the only canonical research-state authority.

## Authority model

| Concern | Authority |
| --- | --- |
| Routine wake-up | `CODEX_CLOUD_OPS_ONLY` |
| Canonical research state | `SERVER_CANONICAL` |
| Research priorities and learning | Manager/Coach task |
| Bounded local execution | Local Codex Research task |
| Trading activation and money movement | Not authorized |

The local task never infers work from idle time. V1 dispatch is deliberate and
message-based. Adding automated dispatch later requires a new reviewed contract;
it must reuse the one cloud clock and cannot introduce a local scheduler.

## Task and result contracts

Every local assignment must conform to:

- `research_pipeline/local-research-task.schema.json`
- `research_pipeline/local-research-result.schema.json`

The executable validator is stricter than the portable JSON schemas: it also
requires the full mandatory prohibition set, a SHA-256 link from result to task,
zero changed files for `READ_ONLY`, and zero candidate variants for
`CAPABILITY_READINESS`.

Validate a task before dispatch:

```text
python -m research_pipeline validate-local-research-task <task.json>
```

Validate a returned result:

```text
python -m research_pipeline validate-local-research-result <result.json> --task <task.json>
```

## Execution modes

- `READ_ONLY`: inspect contracts, toolchain, hashes, and sealed summaries. No
  repository or state writes are allowed.
- `WORKTREE_WRITE`: make bounded repository changes only when the task package
  names that mode and gives a non-zero file limit. It still cannot write
  canonical research state.

All assignments are fail-closed. Missing evidence is reported as
`MISSING_PROOF`; it does not justify a permission request, data import, gate
relaxation, candidate proliferation, or OOS access.

## Fixed prohibitions

Every task forbids canonical-state writes, Research MCP write calls, a second
timer or writer, Trading/database/order/fund/SHADOW/PAPER/LIVE action, OOS access
or gate relaxation, external backfill/import, paid API or API-key use, and
Production/database mutation.

V1 uses the local Codex task through the user's ChatGPT sign-in. It does not
configure an OpenAI API key and does not claim that a ChatGPT subscription pays
for API usage. The computer and Codex must be available for local work; cloud
heartbeat and evidence collection continue independently while it is off.

## Pilot acceptance gate

The first vertical slice is complete only when:

1. the capability-readiness task validates before dispatch;
2. the Local Research task returns a result tied to the exact task SHA-256;
3. all safety assertions remain false and no files are changed by that task;
4. the Manager/Coach can interpret the result without changing V6, canonical
   state, or any Trading runtime; and
5. the result identifies a bounded next task or records `MISSING_PROOF`.

Passing this gate proves orchestration and safety only. It is not evidence of
alpha, PnL improvement, drawdown improvement, or candidate readiness.
