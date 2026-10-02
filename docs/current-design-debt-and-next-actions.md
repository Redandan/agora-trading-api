# Current Design Debt and Next Actions

## 2026-10-03 strategy execution audit repair (Taipei date)

The owner authorized repair and a combined release after the read-only audit.
The scope is mechanical owner-509 execution recovery, truthful pending-state
reports, exact zero-fee handling and immutable running-version identity.
Entry weights, the 10/80/250 USDT owner-509 limits, +5% profit trigger, DRA's
one-lot 30 USDT contract and the provider-managed Grid remain unchanged.

Owner 509 now commits a versioned reservation for the complete sell group
before submission. Reservation and fill allocation are separate short database
transactions, serialized by the existing strategy row. Every affected lot is
committed or rolled back together. A terminal partial fill returns untouched
lots to OPEN and partially consumed lots to OPEN_PARTIAL. The current sell
client id prevents another submission on the same daily bar.

On an existing fresh daily evaluation, V2 pending reservations are queried by
their original OKX client id. Only a matching terminal receipt can release a
reservation or apply a fill. An unfilled cancellation releases it without
inventing a trade; positive fills require complete normalized fee evidence.
Zero fee is valid. Unknown fees, not-found orders, live/partially-filled orders,
identity mismatch and overfills remain pending. A reconciliation pass places
no new order, and no new scheduler or historical-signal replay was added.

The old non-atomic reservation format cannot prove aggregate membership and
is not automatically adopted. The pre-release database audit found no open
509/DRA unresolved rows. There is no migration or historical backfill. Buy
quantity is floored to the existing eight-decimal position-column precision,
so database rounding cannot attribute another owner's BTC to this strategy.
Any sub-satoshi provider residual remains outside the tradable lot; it must
not be described as a fully liquidated, fee-exact lifecycle without evidence.

Performance snapshots now emit MISSING_PROOF and omit equity when an open
owned row has an unresolved execution state. MCP inventory includes pending
buy reservations, flags unresolved execution and omits its market valuation.
Intentional legacy BTC Base holdings remain distinct from unresolved orders.

Deployment passes the built commit into the JVM and MCP freezes that identity
at startup. It no longer reads mutable checkout HEAD. Server verification
requires the runtime identity to match app.commit, while the existing verifier
continues to permit a later docs-only checkout update.

Offline regression covers partial allocation, same-bar replay, receipt replay,
transaction rollback, process restart, canceled and unknown submissions,
provider identity/quantity mismatch, missing/zero/base fees, owner isolation,
pending reporting and immutable version identity. Deployment acceptance must
still confirm health, registry, unchanged limits, position safety and absence
of manual/test orders; offline tests alone are not Production execution proof.

Latest diagnosis: 2026-10-02. Local remediation is based on deployed commit
`9e7f84da54f56bbc983ec08dc7b9083680708b68`. The combined runtime release
`52e47a4a96f92e675cb5dc6251d4cb10188f576a` started on port 8085 at
2026-10-02 11:39 UTC. The Grid/performance integration `94fd6713` subsequently
started at 13:49 UTC on port 8084. Current runtime is diagnostic follow-up
`43355e9b` at 14:07 UTC on port 8085. Acceptance is recorded in
`split-acceptance-status.md`.
The strategy decisions and July acceptance narrative below remain historical
context, not a statement that the first DRA sell is still outstanding.

## 2026-10-02 findings and remediation

The investigation used only the dedicated Trading MCP, application logs and
read-only SQL against Trading tables and MySQL diagnostic metadata. It did
not place orders, alter strategies, change Grid, backfill data, or restart
services. Times in this section are UTC unless explicitly labelled otherwise.

### DRA fee reconciliation blocks new entries — fixed and accepted in Production

Before this release, attempt `1` for closed lot `263` had remained
`RECONCILED_FILLED / PENDING` since 2026-08-19. Its saved provider receipt has `fee=-0.031611633036` and
`feeCcy=USDT`, while `fillFee` and `fillFeeCcy` are absent. The REST order
parser used the latter fields with cumulative `accFillSz`. This left the
persisted fee at zero and currency unknown on each subsequent lookup.

`findOutstandingSell()` treats that fee-pending attempt as outstanding.
`executeEligibleExit()` handles it and returns true, so the LIVE evaluator
does not reach a new buy on that bar. This establishes a real execution
blocker; it does not establish how many profitable opportunities were lost.

The local fix reads the cumulative `fee/feeCcy` pair in both the immediate
fill and later lookup paths, consistent with the
[OKX order-fee contract](https://www.okx.com/docs-v5/log_en/). It never falls
back to a last-fill fee for a cumulative quantity. Missing, unsupported
currency, or rebate evidence stays pending. Fee precision is preserved when
the provider omits trailing zeroes in the gross quantity; exchange lot-size
rounding remains in the order-sizing layer.

The offline saved-receipt case expects a fee-only adjustment of
`-0.03161163 USDT`, no new fill, recorded PnL changing from `1.61218858` to
`1.58057695`, and a second identical reconciliation applying zero delta.
These are test expectations, not mutations of the live ledger. A fresh signed
read-only OKX order-detail request on 2026-10-02 11:51 UTC returned code 0,
state filled, accFillSz 0.00045767, fee -0.031611633036 USDT and rebate 0 for
the original client order ID. The cumulative fee is still available at the
provider; this is not reliance on a stale saved receipt alone. The missing
historical BUY attempt remains `MISSING_BUY_ATTEMPT`, so this does not make
the entire lifecycle fee-exact.

The owner authorized one combined release on 2026-10-02, after all confirmed
code defects in this incident are fixed and verified. After ordinary reconciliation succeeds,
DRA may again reach its existing fresh-signal entry path. Do not manually
clear PENDING, create a replacement order, replay old signals, or backfill the
missing BUY attempt. Acceptance must verify fee-only application, unchanged
provider fill quantity and original exit time, no duplicate submission, and
the unchanged one-lot 30 USDT / +5% strategy contract.

### DB interruption — server lifecycle confirmed, initiator unproven

The application connects from `10.0.0.64` to MySQL at `10.0.0.119:3306`.
Local MySQL socket absence is unrelated. `performance_schema.error_log`
records externally signalled graceful shutdowns on 2026-10-01 at 10:31:23,
10:47:12 and 10:47:33, followed by restarts. The application records
`Connection refused` and unavailable pool connections at 10:34–10:46,
affecting OCO polling. No change to pool sizing or maxLifetime is justified
by this evidence alone. Intermediate DB-ready messages do not prove that the
private endpoint was reachable throughout that interval.

The full day has 24 distinct OKX hourly bars, one Binance daily bar, and 24
each of DRA and Donchian evidence rows. Both lanes also have observations at
09:00, 10:00, 11:00 and 12:00 across the incident. These bounded checks show
no missing bar/evidence count in that window; they are not a proof of zero
order impact. Remaining evidence: cloud maintenance/audit actor and request,
endpoint reachability between restarts, and any historical execution impact.
The configured OCI security-token session was expired when the cloud audit was
attempted; no reauthentication or cloud mutation was performed.

The application now propagates failed K-line inserts instead of treating them
as duplicate rows. Existing OKX gap recovery inserts and publishes bars in
chronological order, publishes each committed insert before attempting the next
one, and accepts only explicit confirmed closed bars. A later DB failure can
no longer swallow events for earlier committed inserts in that batch. No new
scheduler, external source, manual backfill or historical order replay is added.

### WebSocket reliability — deployed heartbeat and diagnostic fixes

The prior OKX candle implementation recursively scheduled a heartbeat on
every successful reconnect without cancelling the old chain. Repeated
reconnections could accumulate ping tasks. A subscription now owns one
cancellable heartbeat, stopped on reconnect/unsubscribe/shutdown and replaced
on a new connection. The interval and retry/backoff limits are unchanged. Pending reconnects are
cancelled on unsubscribe/shutdown; exact subscription and socket identity guards
prevent old callbacks or queued reconnects from reviving a removed stream or
clearing a replacement socket. The same lifecycle guards cover Binance and
OKX private streams; private repeated-failure alerts occur once per outage.

Public and private socket logs now retain exception type, HTTP status,
failure count, outage start and last message time without dumping response
bodies or credentials. Recovery duration ends at subscription acknowledgement.
Binance daily freshness also accepts the provider
end-minus-one-millisecond close timestamp. This fixes diagnostic visibility; the upstream cause of the recent resets is
not established. No new timer or notification path is added.

### Operator evidence — deployed read-only changes

The existing `getStrategyRuntimeCatalog` tool appends a common persisted
observation section for active lanes. It shows source-pinned latest closed
bars, freshness, whether the decision matches that bar, recorded decision
reasons/conditions, explicitly owned inventory, current recorded-cost/cap
utilization, and oldest pending DRA fee attempt. It does not evaluate a
strategy, restore state, call an exchange, or write. Missing evidence and DB
failures remain visible. Historical utilization and complete blocked-entry
counts are still unavailable. The ten-tool MCP allowlist stays unchanged.

The realized ledger now lists incomplete lot IDs and their evidence gaps,
labels exact sums as a verified subset when coverage is partial, and suppresses
the mixed-basis recorded aggregate. Empty inventory is `NO_CLOSED_LOTS`, not
an accounting failure. Missing recorded PnL cannot be labelled reconciled.
39 unattributed historical lots are not reassigned by inference. A full net
equity series and maximum drawdown remain unproven and require a separately
scoped accounting design before strategy ranking or scaling.

### Grid and accepted policy choices

The read-only snapshot around 2026-10-02 10:58 showed native Grid
`3767345250394603520` running with a `63,978–67,259` range, 10 USDT investment,
and provider PnL about `+0.2697 USDT`. BTC around 86,305 was about 28.3% above
the upper bound. Range relevance and opportunity cost warrant an operator
decision, but this snapshot does not justify a replacement range or establish
terminal exact-net profit. No Grid change is included. This is a smaller
capital exposure than the DRA execution blocker.

The follow-up local change adds timestamped native Grid range observations and
bounded filled/live sub-order pagination. A 12:34 UTC OKX ticker of 86,725.3
was 28.9423% above the same upper bound; the bot's ten live orders were buys
in the old range. See [Grid deviation handling](okx-native-grid-range-observation-v1.md)
for the diagnostics contract and operator adjustment/acceptance evidence.
The diagnostic code is deployed in `94fd6713`; the live-inventory count
reconciliation follow-up `43355e9b` passed live acceptance. Separately, the account holder confirmed
the existing bot's edit at 20:59:56 Asia/Taipei: 78,000–90,000, ten grids,
unchanged 10 USDT cumulative investment. Read-only browser acceptance found
eight buys and two sells in the new range, with current price inside it.
That fixed-range edit left Trailing Up/Down off. The account holder subsequently
stopped the old bot at 21:17:28 and created `3974349158101700608` at 21:20:34
with both trailing directions enabled, upper/lower stop-moving prices
102,000/73,200 and actual cumulative investment 13.62 USDT. Browser acceptance
found one running bot, eight buys and two sells. This provider configuration
is independent of the diagnostic release; Trading has no Grid mutation adapter.

Legacy lots 260/261/262 intentionally have no automatic exit. Donchian has
80 observation days, two unique entries and one completed trade against a
five-entry/five-trade gate. Neither is treated as a bug or promotion authority.

### Deployment tooling corrections during acceptance

Only one new application process was started, PID 717216 on port 8085. The
first deployment exited 1 because strict verification raced with graceful
termination of the old PID 2082580 on port 8084. The old process then exited
normally (graceful HTTP shutdown at 11:39:46 and Hikari shutdown at 11:39:48); a fresh process/listener check confirmed the old port was drained.
No forced kill or second deployment was used.

The standalone verifier also initially failed because its wildcard nginx scan
included an unreadable configuration owned by another service. Direct route
inspection and authenticated local/public probes confirmed that Trading's
routes were present. Preflight and verification now default to the exact nginx
configuration used by deployment, while retaining an explicit override.
The deployment script now waits up to 60 seconds after SIGTERM, keeps old PID
metadata on timeout, and fails visibly rather than running the strict check
before shutdown completes. Both exit and timeout paths passed an offline
fixture without signalling any real process. The SSH wrapper now rejects dirty
or untracked server files and preserves local commits through fast-forward-only
updates instead of resetting the server checkout.

All retained shell files passed syntax checks; the changed PowerShell wrapper
passed parser validation. The log smoke checker also recognizes the new
explicit transport exception types while retaining warning thresholds and the
private-stream subscription-recovery requirement. Four offline fixtures proved
recovered transport acceptance, unrecovered private failure, unknown exception
failure, and public transport classification. These script-only changes do not restart the running
Java release. The strict verifier subsequently passed with the default route
configuration. The deployed JAR SHA-256 is
`cfa69f3580c457021fa5753c4148102686506781c3ff2a2a8cde4d7f359e5e1d`.
New-process log smoke found zero errors, zero unknown warnings,
and zero high-risk operation-like lines at 11:42 UTC. Three strategy lanes
returned AVAILABLE with CURRENT market data and matching persisted decisions.
The realized ledger exposed all 40 incomplete historical lots and suppressed
the mixed-basis aggregate as intended. OCO safety and original execution caps
remained unchanged.

### Natural production acceptance at 2026-10-02 12:00 UTC

The first naturally closed OKX hourly bar after deployment triggered the normal
DRA reconciliation at 12:00:01.578945 UTC. Read-only SQL and the catalog MCP
independently confirmed:

| Field | Before release | After natural reconciliation |
| --- | --- | --- |
| Attempt 1 state | RECONCILED_FILLED | RECONCILED_FILLED |
| Fee status | PENDING | RECONCILED |
| Applied USDT fee | 0 | 0.031611633036 |
| Lot 263 recorded realized PnL | 1.61218858 | 1.58057695 |
| Applied BTC fill | 0.000457670000 | unchanged |
| Original exit time | 2026-08-19 21:00:13.303497 | unchanged |
| Total execution attempts | 1 | 1 |
| Pending DRA fee attempts | 1 | 0 |

DRA evidence 32014 and Donchian evidence 32013 each advanced to the 11:00 open /
12:00 close bar; both recorded orderSent=false. Legacy positions 260/261/262
retained their quantities and remained open. Flyway history remained V1–V4,
all successful. No manual order, manual reconciliation, SQL repair, replay,
Grid operation or migration was used for acceptance. The frozen one-lot
30 USDT / +5% execution contract remains in force. A future entry still needs
a genuine eligible signal; this acceptance does not claim a new profitable
cycle or resolve the missing historical BUY receipt.

### Validation


Run the narrow offline receipt/reconciliation, heartbeat, observation, and
existing execution-policy tests; then `mvn -DskipTests package` and
`git diff --check`. Tests must not start Spring, contact exchanges or a
database, or send notifications. The combined deployment and first natural reconciliation/bar provided
the runtime acceptance above. Preserve the original dirty research checkout.

Local validation on 2026-10-02: all 39 tests passed with zero failures,
errors, or skips; Java 21 `mvn -DskipTests package` and `git diff --check`
passed. These are offline source/package results, not deployment acceptance.

This is the current decision document for maintenance and strategy scaling.
Selected strategy research and rollout evidence remains where it is required
to reproduce 509, DRA, Donchian, schema, or retained developer tooling.
Superseded proposals and review packets remain recoverable from Git history;
none overrides this document or the versioned strategy contracts.

## Product decision

The product is a small strategy-driven BTC spot runtime:

1. owner 509 is the TradingView-parity LIVE baseline;
2. DRA V1 is an independent one-lot, 30 USDT LIVE canary;
3. Donchian remains evidence-only SHADOW;
4. OKX Native Spot Grid remains provider-managed and read-only from this
   service;
5. the platform supplies market data, ownership accounting, idempotent order
   submission, reconciliation, observability, and deployment safety.

Do not restore AI/ML ensemble voting, TQS/Autopilot promotion, shared
strategy-quality gates, generic exits, or a second strategy orchestration
framework without a new versioned requirement and causal evidence. Those
systems increased maintenance cost and blocked strategy intent without proving
economic value.

## Economic comparison contract

Every comparison must use the same initial capital, market window, source,
fees, adverse slippage, and final valuation time. Report these fields
separately:

| Field | Required meaning |
| --- | --- |
| Realized PnL | Net PnL from completed lots only |
| Unrealized PnL | Estimated net liquidation value minus remaining cost |
| Total PnL | Realized plus unrealized PnL |
| Maximum drawdown | Drawdown of total marked-to-market equity |
| Capital utilization | Average and maximum open cost divided by the common capital |
| Blocked entries | Signals skipped because the strategy had no available capital or lot capacity |
| Holding age | Oldest and average age of open lots |

Realized PnL alone is never the ranking metric. A profit-only strategy can make
realized PnL look good by leaving losing inventory open. The primary ranking is
fee-adjusted total PnL under equal capital; realized and unrealized PnL remain
visible as separate diagnostic fields.

Provider receipts are the final source of truth for fills and fees. A database
value derived before the provider fee is available is provisional and must not
be described as exact-net performance.

## Current strategy interpretation

### Owner 509

Owner 509 proves that the runtime can reproduce the chosen TradingView entry
logic and execute it with bounded notional. It is a LIVE baseline, not a
profitable benchmark:

- the 1,095-day parity run produced 72 intents, `-8.50%` total return, and
  `23.38%` maximum drawdown;
- the later fair-reset comparison also left materially negative unrealized
  inventory;
- therefore 509 should remain bounded and must not be scaled on parity evidence
  alone.

### DRA V1

DRA V1 has better historical and out-of-sample economics under its 250 USDT
multi-lot reference model. Production does not run that model. Production is
limited to one 30 USDT lot, so its fair LIVE comparison is the one-lot overlay,
not the headline 250 USDT reference result.

The frozen V1 choice is:

- daily reversal/trend confirmation for entry;
- one 30 USDT LIVE lot;
- `+5%` estimated net profit exit;
- no stop-loss, time exit, drawdown gate, or forced end liquidation.

Changing the exit target, adding partial profit-taking, or adding an intrabar
exit creates DRA V2 and requires a new causal/OOS comparison. It must not be
silently inserted into V1.

## Accepted choices, not defects

The following are deliberate boundaries:

- no AI/ML, ensemble, TQS, or shared strategy risk veto;
- DRA limited to a 30 USDT single-lot canary;
- no automatic loss sale or time exit in 509 and DRA V1;
- historical strategy and evidence rows remain in the shared database;
- OKX Native Grid is provider-managed;
- aliases `508` and `509` remain display lineage while canonical strategy keys
  own runtime behavior.

The absence of a stop-loss is high risk, but it is a strategy choice rather
than an execution-platform defect. It is acceptable only while unrealized PnL,
holding age, utilization, and blocked opportunity cost remain visible.

## Actual design debt

### Forward economic evidence V1 — deployed, accumulating future evidence

The deployed implementation adds independent provider cash-flow reconciliation,
owner-509 per-lot normalized fill receipts, forward entry evaluations and
hourly recorded-basis performance observations using existing audit tables.
See [forward-spot-performance-v1.md](forward-spot-performance-v1.md) for formulas,
coverage gates, retention, remaining limitations and acceptance. Initial empty
reports correctly expose missing proof. Future samples and full trade lifecycles
remain necessary; deployment does not authorize scaling or historical repair.

### Completed code reduction

On 2026-07-27, Batches 4A through 4H removed 111 unreferenced legacy classes
and 12,117 source lines. The removed roots covered old counterfactual/adoption
services, standalone simulations, unused provider adapters, inactive
diagnostics, retired risk helpers, evidence utilities, Telegram presentation
helpers, AI DTOs, configuration bindings, and repository interfaces with no
remaining consumer. Compilation and direct source assertions preserved the
three runtime strategy implementations, the fixed 10-tool MCP surface, OCO
safety, read-only Grid monitoring, active runtime environment values, all JPA
entities, tables, historical rows, migrations, and deployment scripts.

A low reference count alone is not sufficient evidence for further deletion.
Spring interface implementations, event listeners, schedulers, configuration
binding, JPA entities/repositories, and provider warning classifications need
their own dependency closure. Batch 4D completed that closure for the unused
Etherscan and Pyth adapters together with their obsolete runtime-log
classifications.

Batch 5A completed a second dependency-closure pass on 2026-07-27.
It removed 27 unreachable Java files, 19 inactive Spring components, and 4,280
Java source lines. The deleted roots were the uncalled Binance/FRED/Polymarket
historical import services, the retired alpha-outcome diagnostic graph, the
standalone coverage-profiler CLI and fixtures, the unused market-diagnostics
facade, and the orphaned meta-control attribution/general backtest service
shell. Historical entities, database rows, migrations, the retained strategy
calculation engine, all three runtime strategies, the 10-tool MCP contract,
Grid/OCO safety, and deployment scripts remain unchanged. A clean package
recompiled all 207 remaining source files successfully. Batch 5A is committed
as `f405ee0`.

Batch 5B removed the uncalled `ExposureOptimizer` and
`DailyLossGuard`, 29 no-caller repository methods from retired score-buy,
Tiny Live, Meta-Control, health-summary, cooldown, and risk-gate paths, and
inactive generic OKX sizing/loss configuration. Historical blocker values
remain readable in stored audit/evidence rows. Batch 5B removes a net 878 Java
source lines, leaving 205 Java source files. A clean package succeeds, and no
owner-509, DRA, Donchian, MCP, Grid/OCO, Production configuration, entity,
migration, or deployment path changed. It is committed as `19f7040`;
the commit is part of the current `main` lineage.

Batch 5C removed the uncalled Binance exchange-order Spring adapter
and its unused configuration binding. Binance daily market data for owner 509
is independent and remains unchanged; OKX remains the only registered order
adapter. The batch removes a net 241 Java source lines, leaving 203 Java source
files, without changing an OCO caller, active strategy, Production setting,
entity, migration, or deployment path. It is committed as `dbf10dc`.

Read-only Production verification on 2026-07-28 confirmed local `main`,
`origin/main`, the server worktree, and deployed `app.commit` all at
`dbf10dc`. The active service was healthy on port `8084`, with port `8085`
drained. Therefore Batches 5A through 5C are pushed and deployed; the earlier
local-only checkpoint is superseded.

### Completed provider-first DRA execution safety

Commit `ae47ef0609b6f86c7cfe2338c6d80a3135dc7e25` deployed the narrow
provider-first DRA execution layer. Flyway V4 added
`bt_spot_execution_attempt` with durable uniqueness for
lot/side/sequence, client order ID, and provider order ID. Only one JVM can
atomically claim a reserved attempt for submission. Before sending, the
runtime queries OKX by deterministic `clOrdId`; an ambiguous result remains
unresolved and is never blindly retried.

Cumulative provider fills and fees are applied only as monotonic deltas.
Overfill and backwards cumulative receipts fail closed. A base-currency buy
fee reduces strategy-owned BTC; a quote-currency fee changes cash cost; a
temporarily unknown fee currency keeps a conservative quantity until a later
provider receipt resolves it. A reconciled partial sell allocates a new
durable attempt sequence instead of reusing the first client order ID.

The focused suite contains 14 deterministic tests covering bootstrap and
execution-attempt policy. A disposable MySQL 8.4 race test proved one
successful competing insert, one unique-key rejection, atomic claim results
`1,0`, and rejection of applied quantity greater than provider gross
quantity.

Production V4 started with zero attempt rows and deliberately did not backfill
pre-V4 position `263`. The first new-JVM natural bar passed without a new
reservation or order. This proves deployment and no-duplicate continuity, not
the still-pending first sell lifecycle.

### P0 — Observe the first complete DRA lifecycle

Do not change DRA V1 entry economics, 30 USDT notional, one-lot ownership, or
the +5% net-profit exit while its first live lot remains open. A complete
provider buy-to-sell cycle is still needed to validate entry ownership, exit
eligibility, actual fee reconciliation, realized PnL, and duplicate prevention.
Deployment and continuity evidence are functional evidence, not profitability
evidence.

The owner authorized one narrow exception on 2026-07-29: bootstrap must replay
historical arm, expiry, last-entry-signal, and cooldown state before the first
genuine closed bar. It must not reconstruct historical lots or submit a
historical, deployment, restart, or test order. This lifecycle correction does
not authorize DRA V2, scaling, a new entry factor, or a changed exit.

### Completed — Reconcile delayed provider fees

OKX can publish the buy fee after the initial fill response. The current
provider-first attempt persists gross fill, observed fee, fee status, applied
deltas, and remaining lot quantity. A later provider lookup can finalize a
base-fee quantity or quote-fee cash cost without submitting another order.

Position `263` predates V4 and was intentionally not backfilled. Its
`0.00000046141 BTC` safety remainder must therefore remain a separately
reported asset during the first sell reconciliation; it is not evidence that
the new attempt flow lost quantity.

### Completed — Make partial sell retries idempotent

DRA now reconciles the existing provider order before any retry and allocates
the next deterministic client order ID only after a reconciled partial. An
unresolved or ambiguous attempt blocks later submission. The first sell for
position `263` remains `DRA1S20260726230000`; later sequences exist only when
the preceding provider receipt proves a partial fill.

### P1 — Maintain a small contract-test boundary

The old broad automated test tree was intentionally removed. The retained
narrow deterministic suite now covers historical entry-state replay plus the
provider-first execution-attempt policy. Before changing order, fill,
ownership, or exit handling, extend that small suite where the change lands,
including:

- DRA entry and exit net-return math;
- delayed buy fee reconciliation;
- partial buy and partial sell handling;
- duplicate closed-bar delivery;
- restart/state restore and corrupt-state fail-closed behavior;
- deterministic buy/sell client-order IDs;
- owner-509 same-bar weight aggregation and caps;
- strategy-owned quantity isolation.

This is a narrow LIVE-contract suite, not a restoration of the deleted generic
backtest and AI test infrastructure.

### P2 — Separate current state from evidence history

DRA currently restores state by scanning recent evidence JSON rows. This is
acceptable for the single-instance canary because it fails closed, but
append-only evidence and mutable current state serve different purposes.
Before adding more LIVE strategies or running multiple instances, introduce
one typed current-state record per canonical strategy and keep evidence
append-only.

### P2 — Enforce strategy-and-bar uniqueness durably

The closed-bar listener is asynchronous and the in-process DRA bar guard is
JVM-local. Order reservation has durable uniqueness, but evidence/state
advancement is not yet protected by a database uniqueness contract for
canonical strategy plus bar. Add that constraint before multi-instance
evaluation.

### Completed in this release — Generic read-only strategy observations

The existing catalog MCP now appends generic persisted observations, as
described in the October findings. It does not add a tool or strategy write
path. Complete performance and historical signal accounting remain separate
evidence requirements, not claims supplied by runtime status.

## Ownership invariant

BTC is isolated by logical ledgers, not by separate exchange wallets. A sell
must reconcile the account balance against all strategy-owned, legacy, manual,
OCO-reserved, and Grid-owned quantities, then sell no more than the current
strategy's provider-verified owned quantity. Existing positions
`260/261/262`, owner-509 lots, DRA lots, manual BTC, and Grid BTC must never be
adopted across ownership boundaries.

## Scaling gate

The 30 USDT DRA canary may continue under the frozen V1 contract. Increasing
notional, allowing multiple live lots, or enabling another LIVE strategy
requires all of the following:

1. at least one complete real DRA buy-to-sell lifecycle with provider receipts;
2. exact fee and net-quantity reconciliation;
3. no duplicate or ambiguous order outcome;
4. the P1 contract tests passing;
5. equal-capital realized, unrealized, total, drawdown, utilization, blocked
   entry, and holding-age comparison against owner 509;
6. a new explicit operator authorization.

## Recommended order of work

1. Continue read-only DRA/509 observation until position `263` performs its
   first genuine profit-qualified sell.
2. Reconcile the provider cash result, database realized PnL, and separate BTC
   safety remainder; publish the first real economic result without
   double-counting.
3. Keep DRA at one 30 USDT lot and the frozen +5% exit until that cycle passes.
4. Re-run the equal-capital comparison after real realized evidence exists.
5. Decide whether DRA V2, larger/multi-lot DRA, or applying the same
   provider-first attempt layer to owner 509 is justified and explicitly
   authorized.
6. Only then consider typed state, database bar uniqueness, or targeted class
   extraction needed by the approved scale.
