# Local Codex Research Node V1

Status: `OPERATIONAL_V1`

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

## Operational loop

The local node remains message-dispatched even after operational acceptance.
The user does not operate individual research tasks, and local idle time never
creates work. The fixed loop is:

1. the sole cloud Ops schedule observes canonical state and delivers a sealed
   evidence-ready or integrity event to the Manager/Coach task;
2. the Manager/Coach re-reads canonical status and verifies the event identity,
   artifact hash, authorization, queue state, and single-clock boundary;
3. for a ready microstructure V3 diagnostic, the Manager/Coach invokes the
   fixed create-once Server-to-Local handoff pull once, validates the received
   package, and validates the exact bounded Local task before dispatch;
4. the existing Local Research task executes only that task and returns one
   result bound to the task SHA-256;
5. the Manager/Coach validates the returned result, confirms its safety
   assertions and file boundary, and interprets the evidence for performance,
   drawdown, stability, concentration, and opportunity cost;
6. a non-positive or insufficient result closes without tuning. A positive
   interpretation may proceed only to one separately frozen Coach-authored
   hypothesis-design task; it still does not register a candidate, open OOS,
   or authorize Trading.

If the local computer or Local Research task is unavailable, the canonical
event remains durable for a later Manager/Coach run. No fallback timer, local
poller, retry loop, second writer, or user-operated research step is added.

### Frozen contract precedence

The read-only pre-outcome audit
`local-node-microstructure-v3-preoutcome-screen-audit-v1` (task SHA-256
`ca1955a397a2832753deb3ac4420b879fdafdcf2271f0361e94a47245ea69a24`,
validated result SHA-256
`1a6a9861abedf7ee8ee885b1634f4b496cbd0b4b5ed249fd9a10c08da377495f`)
is historical design input, not executable authority. Its most-specific-first
tier recommendation was superseded by the later frozen interpretation
contract and implementation.

The Manager/Coach must dispatch only the current task- and contract-hash-bound
pipeline. The authoritative tier order is simplest first:
`MIDLINE_RATIO_1_5_ONLY`, then the net-taker-buy tier, then the book-support
tier. The first passing tier is the sole selected tier. No Local Research
result, prompt text, or observed magnitude may reorder that list or revive a
later tier after a simpler tier passes.

## Operational acceptance gate

The first vertical slice is complete only when:

1. the capability-readiness task validates before dispatch;
2. the Local Research task returns a result tied to the exact task SHA-256;
3. all safety assertions remain false and no files are changed by that task;
4. the Manager/Coach can interpret the result without changing V6, canonical
   state, or any Trading runtime; and
5. the result identifies a bounded next task or records `MISSING_PROOF`.

Passing this gate proves orchestration and safety only. It is not evidence of
alpha, PnL improvement, drawdown improvement, or candidate readiness.

### Worktree-write acceptance gate

A `WORKTREE_WRITE` assignment is accepted only when:

1. the committed task validates before dispatch and binds every non-null input
   hash plus an exact source commit;
2. the Local task begins from a clean worktree, changes only the task-listed
   paths, stays within `max_files_changed`, and does not stage, commit, push, or
   create an ignored output;
3. its result is bound to the exact task SHA-256, reports
   `source_git_dirty_after=true`, lists every changed file and artifact hash,
   and keeps every safety assertion false;
4. the Manager/Coach independently validates the returned result, re-hashes
   each artifact, inspects the actual diff, and repeats the task-relevant tests
   before staging; and
5. only the Manager/Coach may commit the reviewed slice. A green Local result
   does not authorize canonical state, a runner, V4, OOS, or Trading.

This separates an expected bounded worktree change from an unauthorized state
write. A dirty worktree outside the exact task paths, an unexpected fifth file,
hash drift, or a failed Manager recheck closes the assignment without commit.

## Acceptance evidence

`OPERATIONAL_V1` was accepted on 2026-08-07 from two independently validated
Local Research results in task `019fd621-68ce-7802-9eed-5ef87c35d677`:

- `local-node-capability-readiness-v1` used task SHA-256
  `8eb112d14413f440d7fbf99779bb27c2b8120b7c3c7f041399b2ab8816eb13d1`.
  Its exact returned UTF-8 JSON has SHA-256
  `51051716ce66fd5c61e8fa1d00c72c1718e2b69d32d630a70e857fa5cb00884c`.
  The current executable result validator accepts it against the unchanged task
  bytes; all safety assertions are false and `files_changed` is empty. It
  verified Python, Maven, explicit `JAVA_HOME` Java 21, the approved adapter
  inventory, pinned Java adapter sources, and the retained selection corpus.
  The unqualified Windows `java` remains Java 8, so approved Java research must
  continue to use explicit `JAVA_HOME`.
- `local-node-historical-mechanism-deduplication-audit-v1` used task SHA-256
  `e834bc966a3d0538e4e5280ff1f3a71ee9067ce1bf29f46ab125dcc63f7d0a1b`
  at source commit `19060a736b7db156c2264d756e812fbe6b70281c`.
  Its exact returned UTF-8 JSON has SHA-256
  `6cc144860f15f66653865896af6c90743d782d76bc2ba5060e14679702e27322`.
  The Manager/Coach revalidated the result against the exact task, rehashed all
  16 sealed inputs, confirmed a clean worktree, all safety assertions false,
  and zero changed files. The result assigned one disposition to each of six
  represented historical branches and established a bounded next action:
  preserve those duplicate guards while the two lawful forward-evidence lanes
  mature.

The second result is the first useful research closure, not only a tooling
check: it reduces repeated computation and prevents aggregate-PnL-only results
from reopening already rejected causal mechanisms. It has zero immediate PnL
or drawdown effect. Real microstructure V3 predictive evidence and any strategy
mechanism remain subject to their separate forward-data and economic gates.

The first accepted `WORKTREE_WRITE` vertical slice used task
`local-node-microstructure-discovery-economic-veto-contract-freeze-v1`, task
SHA-256
`e5c574d5cdfb9603a639f7f0873626a1129192c24a75ff29f17af26000396287`,
and clean source commit `468d8874d2045809facc62db768675f49e11dd86`.
The exact returned UTF-8 JSON has SHA-256
`898321c4497c583e921ce47d5b43b5cbe957c4e28996257b2ef0f6ed5fea4b19`.
It reported exactly four new files, `source_git_dirty_after=true`, and all
safety assertions false. The Manager/Coach revalidated the task/result binding,
all four artifact hashes, the canonical contract payload seal, the Draft
2020-12 result schema with the official validator, contradictory-disposition
rejection, and eight focused tests before committing the four-file slice as
`15f5616acc58c18f8d3cc120dee95706361de18f`.

That slice freezes only a discovery economic veto. It has zero immediate PnL
or drawdown effect and adds no evaluator, source, manifest, candidate, OOS, or
activation. A future Local evaluator assignment remains forbidden until one
validated V3 handoff and positive interpretation exist. A failed gate must end
at `VETO_BEFORE_V4`; `PERMIT_LATER_V4` allows only a separately frozen later
V4 source and economic-manifest slice.

## Current recovery evidence

The canonical heartbeat failure sealed on 2026-08-07 was traced to one migrated
closed-review reference containing Windows path separators while the
hash-identical sealed review existed at the corresponding POSIX path. The
strict portability correction is commit
`261367c52cb1cc2d68610fad816e9f5b2795a842`, which is an ancestor of the
currently installed, source-tree-verified Worker commit
`ed414241c67a253362c3453ec44e3c458fb78828`. The focused storage and evidence
regression suites pass 42 tests on the current branch.

This proves source and deployment readiness, not live recovery. Only the next
normally due cloud heartbeat can prove canonical recovery and accept a verified
Coach delivery receipt. An early heartbeat, local fallback, second timer, or
manual canonical repair remains forbidden.
