# Forward spot performance evidence V1

Status: deployed in `94fd6713` on 2026-10-02 at 13:49 UTC. The report is live;
new natural observations and genuine future trades are separate evidence gates.
Current runtime is `43355e9b`, the native-Grid diagnostic follow-up at 14:07 UTC.
This document is not a profitability or scaling decision.

## Scope and authority

The change adds evidence for the existing DRA one-lot 30 USDT canary and
owner 509. It preserves strategy conditions, sizing, execution order,
reservations, provider calls, retry decisions, inventory ownership and
notification policy. Grid, legacy/manual BTC and Donchian SHADOW are outside
this live spot ledger. No migration, backfill, timer, MCP tool or configuration
switch is added. Queries remain read-only.

The existing `getOpenSpotPositions` response includes both the realized
provider cash-flow ledger and `FORWARD_SPOT_PERFORMANCE`. Existing catalog
observations remain a view of the latest decision, not a full opportunity count.

## Provider cash-flow reconciliation

DRA uses its existing durable execution attempts. Exact coverage now requires
terminal, correctly owned attempts, provider identity and receipt presence,
matching provider/applied quantity, quote and fee fields, supported signed fees,
and matched bought/sold net base quantity. Rejected attempts with fills,
duplicate provider orders, unsupported rebates/currencies, pending fees and
residual BTC (including dust) remain explicit evidence gaps.

Owner 509 records `SPOT_FILL_V1` in the existing `bt_decision_audit` JSON
column. Each event is bound to its live-signal ID and contains normalized
provider order/client IDs, side, average price, gross/net quantities, signed
fee/currency/USDT equivalent and allocated quantity. Aggregate sell receipts
are allocated per lot. The read path validates all allocations of each
physical order; missing, excessive or contradictory allocations cannot be
labelled exact. Identical repeated audit receipts count once.

Accounting is independent of the stored realized PnL:

- USDT-fee buy cash cost = gross quantity × average price + USDT fee.
- BTC-fee buy cash cost = gross quantity × average price; BTC received is
  reduced by its base fee. Its USDT fee equivalent is reported separately and
  is not deducted a second time.
- Sell cash proceeds = allocated gross quote proceeds − allocated USDT fee.
- Provider net PnL = sell cash proceeds − buy cash cost.

The stored PnL must agree within `0.00000001 USDT × distinct receipt count`,
covering existing per-application 8-decimal accounting. Quantity/application
checks use `0.000000000001 BTC/USDT`; the adapter's existing BTC fee conversion
uses 8-decimal USDT rounding. A mismatch is visible, never repaired by a report.
Provider-derived PnL is the exact-subset sum. Partial coverage never becomes an
all-lot or whole-account net result.

Owner-509 receipt capture is observational and asynchronous. A missing or
delayed fee remains incomplete; this does not introduce a new reconciliation
worker or attempt/retry layer for owner 509. A missing audit write also remains
missing, rather than blocking or resending an order. Position 263's historical
missing BUY receipt and other historical accounting gaps are not synthesized.

## Forward hourly observations

After the existing closed-bar strategy dispatch, the existing audit executor
captures at most one `SPOT_PERFORMANCE_V1` row per owner/OKX BTCUSDT hourly bar
within two minutes of its confirmed close. It does not fetch exchange prices
or evaluate strategies. Old/recovery bars are not reconstructed as historical
equity snapshots. Single-JVM serialization plus a committed existence check
suppresses normal duplicate delivery. This is not a multi-instance DB unique
constraint; duplicate persisted keys make report coverage incomplete.

Each snapshot contains recorded realized PnL (including partial sells), open
cost, marked inventory, unrealized PnL before future exit costs, their total,
configured exposure cap, utilization and oldest open record age. Unresolved
reservations or missing position cost suppress the aggregate. Ownership comes
from persisted strategy namespaces, not account balances. Holding age is based
on record creation, not proven provider fill time.

`referenceEquity = configuredExposureCap + recordedRealizedPnl + unrealizedPnl`
is a recorded-basis reference series, not exchange-account equity or fee-exact
net liquidation value. Position observations are taken after dispatch and use
the last closed OKX price; this is not an intrabar or exactly synchronized
provider-portfolio valuation. Recorded totals can include historical lots and
later accounting corrections. Different strategy caps are not an equal-capital
profitability comparison.

The report queries a bounded trailing 30-day window (2,000 rows per event
query) and displays first/last observations, gaps, duplicate/invalid samples,
current/stale state and truncation. Sampled drawdown and mean utilization require
at least two valid consecutive hourly snapshots with the same capital basis.
Gaps, conflicting duplicates, changed capital or nonpositive reference equity
suppress these metrics. Drawdown is the peak-to-trough decline of observed
reference equity, not a claim about intrahour/account drawdown. Nothing before
the first observation is backfilled or assumed to be zero.

## Forward entry evidence

`SPOT_ENTRY_EVAL_V1` records fresh DRA evaluations and owner-509 daily
evaluations. It distinguishes no candidate, handled buy path, explicit block,
deferred reconciliation/exit handling and an unconfirmed outcome. A handled
path is not itself proof of a fill; provider receipts are the fill evidence.

DRA's candidate basis is its existing `VIRTUAL_ENTRY_QUEUED` event. Owner 509's
basis is its frozen weighted buy intents. These are not all theoretically
profitable market opportunities. Each is counted by distinct strategy/bar;
conflicting repeated evaluations suppress candidate totals, and missing bars
or unconfirmed outcomes make the observed-window coverage incomplete.

Versioned `ENTRY_SKIP` contexts additionally expose known block reasons,
including DRA's previously unreported reconciliation-first early returns.
Repeated delivery/reservation blocks are excluded from new-opportunity counts.
Reason counts are observed lower bounds and may overlap on a bar. There is no
counterfactual foregone-profit estimate.

## Retention and failure behavior

The existing opt-in audit cleanup excludes `SPOT_FILL_V1`,
`SPOT_PERFORMANCE_V1` and `SPOT_ENTRY_EVAL_V1`, preserving evidence through
long-held positions. Cleanup is not enabled or invoked. Approximate additional
steady-state volume is 48 snapshots plus 25 evaluations/day, plus fill events.

Writers use the existing bounded audit executor and cannot create provider
actions. Dispatch/write failure is logged; report coverage must expose missing
evidence. No current state is reconstructed from these new audit types, and
they do not enter the old runtime-evidence sidecar. Concurrent daily/hourly
strategy events can be observed at slightly different instants; snapshots are
explicitly observations rather than an atomic cross-strategy portfolio ledger.

## Acceptance

Local acceptance: Java 21 `mvn test`, `mvn -DskipTests package`, and
`git diff --check` passed on 2026-10-02. The retained suite ran 58 tests with
zero failures/errors/skips; 19 cases are new or added for this change. The
package build completed at 12:28 UTC. Focused fixtures cover quote/base/zero fees, unsupported
fees, repeated/conflicting receipts, multi-lot sell allocation, residual dust,
provider/stored PnL mismatch, partial realized + unrealized accounting,
ownership isolation, duplicate/old bars, unavailable DB/executor, missing
samples and capital changes. They use no Spring application startup, network,
real credentials or database.

The combined release reran 73 tests successfully; the live-inventory diagnostic
follow-up reran 79 tests and Java 21 package successfully at 14:03 UTC. The
retained production verifier confirmed the unchanged 10-tool registry and LIVE
caps. At 13:55 UTC the new report returned zero forward samples and evaluations,
with explicit missing-proof states rather than fabricated zero drawdown.

At the natural 14:00 UTC close, both owners captured one valid snapshot at
14:00:02 (bar open 13:00 UTC, OKX close 86,561). DRA recorded one fresh entry
evaluation with zero candidates. Both snapshot streams have zero duplicates,
invalid rows and internal missing hours. Open owned lots and utilization were
zero; their recorded reference equity was 31.58057695 and 250 USDT respectively.
These are labelled recorded-basis values, not verified account liquidation
equity. Drawdown and mean utilization remain suppressed at sample count one.

Forward evidence acceptance still requires two natural hourly samples plus
the next natural owner-509 daily evaluation. A genuine
future buy-to-sell lifecycle is required to establish live cash-flow coverage;
do not create a test order or replay history to complete acceptance. Initial
zero forward samples is expected, not a success claim. Fee-exact whole-account
drawdown, historical repair, automatic owner-509 delayed-fee recovery and an
equal-capital strategy comparison remain outside V1.
