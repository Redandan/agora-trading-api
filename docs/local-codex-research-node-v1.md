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

Before Manager/Coach dispatch, every new assignment must also have one
Manager-owned performance dispatch conforming to
`research_pipeline/local-research-dispatch.schema.json`. This envelope binds
the exact existing task SHA-256 without changing frozen task bytes. It makes
the causal mechanism, performance hypothesis, policy primary metric, expected
direction, drawdown hypothesis, opportunity cost, claim boundary, positive /
negative / insufficient-evidence dispositions, task stop-condition hash, and
candidate-variant limit independently machine-verifiable.
The dispatch document byte contract is compact UTF-8 JSON with lexicographically
sorted object keys and exactly one trailing LF.

Validate that envelope and its exact task before sending either to Local:

```text
python -m research_pipeline.local_dispatch <dispatch.json> --task <task.json>
```

After Local returns, validate the complete transitive closure without changing
the V1 result schema:

```text
python -m research_pipeline.local_dispatch <dispatch.json> --task <task.json> --result <result.json>
```

The closure succeeds only when the dispatch and result independently bind the
same exact task bytes and the result passes the existing file-limit and all-
false safety assertions. Its output records the dispatch, task, and raw result
SHA-256 values so Manager/Coach interpretation cannot silently switch to a
different hypothesis, task, or result.

Legacy accepted V1 task/result evidence remains valid. A task without a valid
performance dispatch may be inspected as historical evidence but must not be
newly dispatched by Manager/Coach. Infrastructure and capability tasks may
declare zero immediate performance effect, but they must still state their
learning-latency or evidence-quality rationale and opportunity cost. Diagnostic
tasks must keep PnL and drawdown `MISSING_PROOF` until a later matched-capital
experiment proves them.

The executable validator is stricter than the portable JSON schemas: it also
requires the full mandatory prohibition set, a SHA-256 link from result to task,
zero changed files for `READ_ONLY`, and zero candidate variants for
`CAPABILITY_READINESS`.

### Runtime-neutral Local preflight

Manager/Coach owns semantic validation. Before dispatch it must run the
authoritative task and performance-dispatch validators and deliver their exact
`VALID` receipt together with the task SHA-256, dispatch SHA-256, stop-condition
count and hash, branch, and clean `HEAD` / local-origin commit. After Local
returns, Manager/Coach must run the authoritative result and transitive-closure
validators. These two Manager gates are never delegated or skipped.

Local does not need a runnable Python validator to execute an otherwise valid
bounded assignment. When Python is unavailable, Local may use native read-only
host capabilities to re-hash the committed task, dispatch, every non-null input,
and the Manager receipt; it must also verify the exact branch, `HEAD`, local
origin, clean worktree, repository containment, regular-file type, non-link /
non-reparse status, and authorized execution scope. Any mismatch remains an
immediate zero-change stop.

This split avoids a second schema implementation and does not weaken the task
contract. Local must not infer a missing receipt, correct frozen bytes, install
a validator, or replace semantic validation with native JSON parsing. The
accepted runtime-neutral proof is
`local-node-local-native-hash-preflight-audit-v1`, whose Manager-validated
result is stored at
`research_pipeline/examples/local-research-result.local-native-hash-preflight-audit.v1.json`.

Validate a task before dispatch:

```text
python -m research_pipeline validate-local-research-task <task.json>
```

Validate a returned result:

```text
python -m research_pipeline validate-local-research-result <result.json> --task <task.json>
```

Manager/Coach may aggregate the mandatory mechanical pre-dispatch checks into
one read-only receipt:

```text
python -m research_pipeline local-research-manager-preflight <dispatch.json> \
  --task <task.json> --intent <pre-dispatch-intent.json>
```

The command reuses the authoritative task and dispatch validator, then requires
a clean branch whose `HEAD` equals the matching local `origin` ref, exact
committed task, dispatch, and prospective classification-intent bytes, regular
non-link input files with all task-bound SHA-256 values intact, and at least one
pre-frozen `COUNT` disposition for a mechanism conclusion or spec/capability
slice. A `NON_COUNTING` task fails before dispatch by default. Manager/Coach may
use `--allow-non-counting-integrity-repair` only for a bounded repair of an
active forward-evidence integrity risk; the exception is explicit in the
receipt and is not a countable research output. The command prints one
deterministic receipt to stdout; it does not write a receipt, fetch Git state,
contact the server, inspect an outcome, or replace Manager/Coach semantic
validation.

For every new countable strategy-path assignment, Manager/Coach must also freeze
a hash-bound admission document conforming to
`research_pipeline/local-research-strategy-path.v1.schema.json` and run:

```text
python -m research_pipeline local-research-strategy-preflight <dispatch.json> \
  --task <task.json> --intent <pre-dispatch-intent.json> \
  --strategy-path <strategy-path.json>
```

This stricter receipt accepts only a diagnostic or registered experiment step.
It proves that the tested feature is available before the decision, binds one
existing parent and matched comparator, requires an existing adapter or direct
runner, and permits at most one additional research step before a frozen
hypothesis or matched-capital experiment. It also freezes fee, adverse-slippage,
total-PnL, drawdown, inventory-path and holding-age visibility. A negative result
closes the family; insufficient evidence stops without a permission request;
independent forward/OOS evidence remains sealed.

The four claimed subjects are not free text alone. `decision_feature`,
`parent_strategy`, `matched_comparator`, and `execution_runner` must each bind
an exact task input by kind, locator, and SHA-256 semantics. Parent and
comparator require hash-verified repository or sealed-artifact inputs; the
runner requires a hash-verified repository input. The acceptance record may
carry `strategy_path_evidence`; the weekly verifier reloads that exact sidecar
from the result source commit and recomputes the complete task/dispatch/intent
and input closure before marking the row strategy-path admitted.

Capability, infrastructure, and repair work may still use the original Manager
preflight when genuinely necessary, but it is support work and must not be
presented as direct candidate delivery. Already sealed historical tasks and the
current active chain retain their original byte contracts and validator command.

For a bounded period of at most seven days, Manager/Coach may measure accepted
Local output against the current throughput targets with an explicit acceptance
allowlist:

```text
python -m research_pipeline local-research-throughput-kpi \
  --period-start <UTC> --period-end <UTC> \
  --acceptance <manager-acceptance.json> [--acceptance <another.json> ...]
```

This command reuses the two-stage classification verifier and reports unique
mechanism families, spec/capability families, excluded work, operational
overhead, and candidate-delivery efficiency. That efficiency is met only when
counted mechanism conclusions with verified strategy-path admission are
strictly more than half of all accepted outputs; support slices, excluded work,
and legacy mechanism labels without admission proof remain in the denominator.
The labelled-mechanism count is retained only as a legacy proxy and cannot make
the KPI green. It is a workflow KPI only. The classification V1 contract cannot prove a rolling
forward terminal, alpha, PnL or drawdown improvement, so those claims remain
explicit `MISSING_PROOF` instead of being inferred from filenames.

When the direct candidate-delivery ratio is below target, the same deterministic
receipt reports a `natural_recovery_forecast`. It projects the first rolling
boundary strictly after which the target would recover if no new accepted
outputs arrive. The forecast is an allocation aid, not permission to manufacture
mechanism tasks or suppress an active evidence-integrity repair. Missing row
completion time remains `MISSING_PROOF`; an empty future window is never treated
as target attainment.

The receipt also exposes a deterministic `next_dispatch_policy`. A direct task
with verified strategy-path admission remains eligible because it improves or
preserves the ratio. Support work is deferred whenever adding one accepted row
would fail the strict-majority target. Support becomes eligible only within the
reported integer headroom, except for a separately proven active evidence-
integrity repair that remains `NON_COUNTING`. The exception preserves evidence;
it does not make the workflow KPI green.

New Local dispatches use the binding allocation preflight rather than reading
that policy by convention:

```text
python -m research_pipeline local-research-allocation-preflight <dispatch.json> \
  --task <task.json> --intent <pre-dispatch-intent.json> \
  --period-start <UTC> --period-end <UTC> \
  --acceptance <manager-acceptance.json> [--acceptance <another.json> ...] \
  [--strategy-path <strategy-path.json>]
```

The command first performs the ordinary or strategy-path Manager preflight,
then revalidates the explicit rolling acceptance allowlist and binds the
proposed output to the resulting allocation policy. Direct strategy-path work
passes; support work without strict-majority headroom fails closed. The active
evidence-integrity exception remains explicit and cannot be combined with a
direct strategy path.

The optimized workflow target cannot pass on ratio alone. Each rolling
seven-day audit must contain at least five accepted outputs, at least three
source-commit-verifiable direct mechanism outputs, at least three distinct
direct mechanism families, and a direct-output ratio strictly above 50%.
Workflow completion requires two daily audits whose rolling windows are exactly
one day apart:

```text
python -m research_pipeline local-research-goal-audit \
  --previous-period-start <UTC> --previous-period-end <UTC> \
  --previous-acceptance <acceptance.json> [...] \
  --current-period-start <UTC> --current-period-end <UTC> \
  --current-acceptance <acceptance.json> [...]
```

This audit proves workflow allocation stability only. It keeps strategy success
`MISSING_PROOF` until a candidate separately passes fees, adverse slippage,
matched-capital total PnL, drawdown, holding-path, breadth, Validation, and OOS
gates.

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
3. the Manager/Coach validates the exact task plus its task-hash-bound
   performance dispatch, classification intent, and—for new countable work—the
   direct strategy-path admission before Local outcome access;
4. for a ready microstructure V3 diagnostic, the Manager/Coach invokes the
   fixed create-once Server-to-Local handoff pull once, validates the received
   package, and validates the exact bounded Local task before dispatch;
5. the existing Local Research task executes only that task and returns one
   result bound to the task SHA-256;
6. the Manager/Coach validates the returned result, confirms its safety
   assertions and file boundary, and interprets the evidence for performance,
   drawdown, stability, concentration, and opportunity cost;
7. a non-positive or insufficient result closes without tuning. A positive
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

### Active-chain health

`scripts/verify_local_research_active_chain.ps1` is the Manager-side read-only
resume gate. It validates only the current three-stage V3R1 terminal execution
chain, its fixed runner bindings, the exact Manager diagnostic dispatch, and
the countable mechanism-conclusion intent required by the Manager research
value gate. It deliberately leaves the slower focused offline test suites to
the separate implementation or release gate. It never treats historical task
snapshots as current execution authority and never writes Local roots,
canonical state, a server, a schedule, OOS, or Trading state.
Its JSON validation path must execute identically under the bundled Windows
PowerShell 5.1 and PowerShell 7; the release check runs the gate through both
shells when both are installed.

Historical task input drift is expected after an accepted versioned change:
those immutable tasks remain evidence of what was authorized at their source
commit and must not be edited to match the current worktree. Goal resumption is
blocked only when this explicit active-chain gate fails or canonical state is
not evidence-ready, not merely because a repository-wide scan finds sealed
historical hashes that no longer match current files.

## Operational acceptance gate

The first vertical slice is complete only when:

1. the capability-readiness task validates before dispatch;
2. the Local Research task returns a result tied to the exact task SHA-256;
3. all safety assertions remain false and no files are changed by that task;
4. the Manager/Coach can interpret the result without changing the sole cloud
   Ops contract, canonical state, or any Trading runtime; and
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

The first bounded public-primary-literature prior audit used task
`local-node-microstructure-order-flow-literature-prior-audit-v1`, task SHA-256
`d7a0057e795b3199df4f834012c9bd90f171257bef84bbdd6fcee9dbe6ba349f`,
Manager dispatch SHA-256
`4ead2455567c920048544d5d5a2b13238264915d19352dcd114448b7ca6404a5`,
and source commit `641a786df08518f5850765c204667f55bdc63e67`. The
Manager independently validated the schema-bound, zero-file-change result as
SHA-256 `4a54b2a1f0c65bd33d1fea2345634a3245d5f7f322d52b748b093b31bd4fbc99`.
Its formal disposition was `PRIMARY_SOURCE_PRIOR_INSUFFICIENT`: readable
primary evidence separated contemporaneous price impact from fragmented
multi-venue sub-second prediction, but did not prove one-venue OKX spot
predictability at the frozen 15- and 60-minute horizons. The result therefore
keeps the permitted label
`PLAUSIBLE_PREOUTCOME_ORDER_FLOW_MECHANISM_NOT_OKX_INTRADAY_ALPHA`, changes no
V3 byte, creates no hypothesis or candidate, and continues only the already
frozen 14-day evidence screen. Immediate PnL and drawdown effect remain zero.

The first post-deployment cloud-prompt consistency audit used task
`local-node-cloud-ops-v7-prompt-consistency-audit-v1`, task SHA-256
`8485d2e8fa7fa12e5adb2ab6efd1dec84ae1917b43271756c9793321051bacb5`,
Manager dispatch SHA-256
`ebb6ca5f8ea9507c06ed4e2417e6b8d94f51299c0ab68d67c52f1caf84fb84af`,
and source commit `ffffe3e9cfcd4a887614be7c81d1c58b1d2560c5`.
The Manager independently validated the schema-bound, zero-file-change result
as SHA-256
`086dfe2aa4e187353257fdd0ee3551ec82ff6c755d6cfbda8bd7c099931d5b61`.
Its disposition was `V7_PROMPT_CONSISTENT_READY_FOR_PLATFORM_BINDING`: every
executable write instruction uses the exact V7 attestation, V6 remains only a
predecessor or cutover stop, same-turn receipt and cross-task operations remain
denied, and the sole 09:05 clock preserves `NOT_DUE` before 09:00. This proves
repository prompt-to-contract consistency only. Canonical V7 and the deployed
Worker were subsequently proven `READY`; platform same-Coach-chat binding,
platform-side exact one-active-recurrence readback, unattended prior-context
visibility, and live two-turn receipt acceptance remain `MISSING_PROOF`.
Immediate PnL and drawdown effect remain zero.

The first restart-tolerant Local V3 pipeline closure used task
`local-node-microstructure-v3-restart-tolerant-local-pipeline-v1`, task
SHA-256
`ae8153da68a68aa5df8af36ce582a879c846af596440937385f7f991edde4a89`,
Manager dispatch SHA-256
`302d002e6558efab8196607ad6248c7f5d3b01feb96f8562ed7f12934a56c945`,
and clean source commit `433df647165e062db2e83db2162cf562aa80ea48`.
The exact returned UTF-8 JSON has SHA-256
`d0ebd63264e83b0f4b36a85c7ef1b72614045702c52e9037c25b5c8ee9d7b3d7`.
It reported exactly eight authorized files, `source_git_dirty_after=true`, and
all safety assertions false. The Manager/Coach independently revalidated the
dispatch/task/result closure, rehashed every artifact, inspected the exact
diff, and repeated all 34 focused deterministic tests before committing and
pushing the accepted slice as
`875da23d4de3c3e0eaecb7da36fd9d75e098f19a`.

That slice removes the obsolete hard-coded 2026-08-08 diagnostic identity from
the Local handoff and interpretation path. Runtime identity and the exact
contiguous 14-day inventory now come only from the fixed-task, canonical,
sealed manifest; the receiver derives the exact allowlist before staging any
byte. Legacy 2026-08-08 and synthetic 2026-08-09-r1 packages both pass without
caller-selected roots, task ids, diagnostic ids, or dates. Real R1 evidence,
predictive value, fees, slippage, capacity, PnL, drawdown, OOS, and activation
remain `MISSING_PROOF`; immediate PnL and drawdown effect are zero.

## Current recovery evidence

The canonical heartbeat failure sealed on 2026-08-07 was traced to one migrated
closed-review reference containing Windows path separators while the
hash-identical sealed review existed at the corresponding POSIX path. The
strict portability correction is commit
`261367c52cb1cc2d68610fad816e9f5b2795a842`, which is an ancestor of the
currently installed, clean, source-tree-verified Worker commit
`c6247e8074803227c8a83b44dcc331aec2956e6d`. Fresh canonical status at
`2026-08-08T09:05:16Z` reports the latest heartbeat `HEARTBEAT_OK`, all queues
idle, and the 2026-08-07 companion evidence day `SEALED` before its deadline.
This proves live heartbeat and evidence-path recovery.

It does not repair the old Coach event's delivery history. That event remains
pending with `BREACH_PENDING_DELIVERY_PROOF`. The active
`CLOUD_OPS_SCHEDULE_V10` contract, exact SHA-256
`90e0de95fa34beff9447640a5dcdbb972278014664806df0a4bf5f36e2598faa`,
allows an initial pending event to be sent and readback-verified before the
single normally due heartbeat, so its verified receipt can close canonical
pending state in that same cycle while preserving the historical `BREACH`.
Events created by the heartbeat wait for the next normal cycle. An early
heartbeat, local fallback, second timer, or manual canonical repair remains
forbidden. Fresh canonical status at `2026-08-14T11:35:33Z` proves V10 Worker
release `20260814T112229Z`, source commit
`ab2528c35e337fbfa47e528ff83d9b829d4806de`, exact V10 attestation, and one
daily recurrence. The first normal post-release heartbeat, live delivery,
readback, receipt acceptance, and terminal SLA disposition remain
`MISSING_PROOF` until observed.
