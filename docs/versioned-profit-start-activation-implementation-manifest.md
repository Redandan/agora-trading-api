# Versioned Profit Start activation implementation manifest

Contract: `VERSIONED_PROFIT_START_ACTIVATION_IMPLEMENTATION_MANIFEST_V1`

Acceptance contract: `VERSIONED_PROFIT_START_ACCEPTANCE_V1`

Deployed base: `748a69ea5b9254e9bd79099e460cefc2ab9297dd`

The machine-readable contract is
[`docs/contracts/versioned-profit-start-activation-implementation-v1.json`](contracts/versioned-profit-start-activation-implementation-v1.json).
This document now records a safe local integration contract. The hard-gate
snapshot, canonical metric reader, and immutable all-fill V3 path are locally
implemented and consumable; that does not prove a fresh runtime snapshot,
runtime activation, or COMPLETE_STABLE exact-net evidence. It does not authorize a database, provider, collector,
environment, live, scheduler, order, OCO, Grid, fund, Earn, or Telegram
mutation.

## Current state

- Acceptance state: `DEPLOYED_CODE_VERIFIED_ACTIVATION_BLOCKED`
- Cohort: `NOT_STARTED`
- Counts: canonical closed `NOT_MEASURABLE`, exact-fee `0`, positive exact-net `0`
- The session remains open.

The local integration deliberately keeps activation blocked. The historical
hard-gate and canonical-reader blocker identifiers remain in the contract for
traceability, with explicit local implementation status; only their runtime
evidence can establish readiness. Existing V2
`fill_fee_ledger` evidence is not an all-fill ledger: its immutable payload does
not preserve fill side, price, or quantity. Existing strategy attribution may
use estimated fees for non-exact reporting. Neither source may support an
exact-net acceptance claim.

## 1. Tiny-live hard-gate snapshot — locally implemented, runtime proof required

### Acceptance

The implementation must create a deterministic, immutable snapshot immediately
before the order boundary and bind its identifier and SHA-256 to runtime
decision evidence. It must fail closed on missing, stale, unknown, malformed,
or identity-mismatched inputs.

The snapshot covers cohort identity and execution mode, candidate presence,
primary 1h and 4h trend, freshness, event risk, exchange minimums, daily and
open-position limits, cross-strategy exposure, stable same-thesis opportunity
identity, OCO feasibility and health where required, runtime evidence-writer
health, and the current-cohort sequential loss budget. Candidate-horizon trend
cannot stand in for either primary trend gate. Zero closed episodes may be
ready; thirty closed episodes are not a tiny-live prerequisite.

### STOP

Any failed or indeterminate gate stops before submission. No candidate returns
`WAIT_MARKET`; it does not create a BUY. Snapshot expiry or hash drift at the
order boundary, inability to bind runtime evidence, duplicate exposure, OCO
mismatch, or a breached loss/exposure/order limit also stops.

### File and schema boundary

New code is limited to a hard-gate snapshot model, service, and focused unit
test. Integration changes are limited to
`VersionedProfitStartCohortService` and
`LocalTradingViewExecutionService`. Existing loss, exposure, and trend services
are read-only inputs, not new mutation surfaces. No migration is required.

## 2. Current-cohort canonical metric reader — locally implemented, exact net blocked

### Acceptance

Every read requires the complete cohort identity and `effectiveFrom`.
Canonical entry/exit episodes use explicit runtime-decision, live-signal,
provider order, trade, and (when applicable) OCO child bindings. Symbol plus a
time window is never sufficient. Legacy, pre-effectiveFrom, other-strategy,
other-symbol, and other-Grid rows stay outside current-cohort metrics.

All current-cohort losses remain in sequential counts and risk metrics. A
closed episode becomes exact only when every entry and exit fill and signed fee
has an unambiguous immutable binding and supported currency treatment. Otherwise
it is `GROSS_ONLY` or `EXACT_NET_NOT_MEASURABLE`, and cannot increment either
the exact-fee or positive-exact-net count.

### STOP

Stop exact classification on an incomplete entry/exit, partial evidence,
conflicting duplicate, missing fee, unsupported fee currency, identity mismatch,
OCO child mismatch, or time-window-only join. `NOT_STARTED` reports canonical
closed `NOT_MEASURABLE`, exact-fee `0`, and positive exact-net `0` without
consulting legacy performance.

### File and schema boundary

New code is limited to a canonical episode model, read-only reader, and focused
test. Existing decision and live-signal repositories may receive narrow
read-only queries. The V2 fee repository is not an exact source and remains
unchanged. The reader does not need its own migration, but exact classification
depends on the additive immutable trade-fill schema below.

## 3. Exact immutable all-fill signed-fee binding

### Acceptance

The evidence row must preserve provider, account hash, instrument, provider
order and trade identifiers, fill timestamp, side, fill price, fill quantity,
signed fee and currency, optional liquidity role, source-page identity,
collection time, and an immutable raw-payload SHA-256. Complete pagination must
be provable. Identical repeats are idempotent; a non-identical repeat is a
permanent conflict and never overwrites evidence.

Exact net uses all entry and exit fills plus every provider-signed fee under an
explicit supported currency policy. Estimated, modeled, schedule-derived, or
aggregate-order fees are forbidden. Missing fields, pagination gaps,
unsupported conversion, or an ambiguous episode link remain gross-only or not
measurable.

### STOP

Stop on missing provider/account/order/trade identity, invalid side/price/size,
missing signed fee or currency, incomplete pagination, hash conflict,
time-window binding, or any request to backfill legacy rows. Collection remains
default-off, fail-closed, forward-only, symbol-scoped, read-only at the provider,
and append-only in the database.

### File and schema boundary

This item requires a future additive
`V3__immutable_trade_fill_evidence.sql`. It may only create the new immutable
table and indexes; `ALTER`, `UPDATE`, `DELETE`, `DROP`, legacy import, and
historical backfill are forbidden. Local implementation may add the model,
repository, guarded evidence adapter/client fields, append service, and focused
schema/evidence tests. Applying V3 or enabling authenticated collection is not
part of local implementation authority.

## Test contract

Focused tests must prove zero-sample readiness, every hard-gate fail-closed
case, cohort isolation, mandatory inclusion of cohort losses, rejection of
time-window joins, multi-fill arithmetic, signed fee costs and rebates,
pagination completeness, immutable duplicate handling, migration additivity,
and default-off/no-side-effect behavior. The local manifest verifier is:

```powershell
.\scripts\test_versioned_profit_start_activation_manifest.ps1
```

The eventual Java/schema implementation must also pass
`.\scripts\verify_local.ps1`; startup/routing or MCP changes additionally
require the local health smoke prescribed by `AGENTS.md`.

## Authorization sequence after a future implementation commit

No package below is issuable now: the exact integration commit is local only,
and the migration checksum,
configuration checksum, and/or effective time do not yet exist.

1. `PUSH_EXACT_ACTIVATION_IMPLEMENTATION_CANDIDATE_<FULL_COMMIT>_TO_ORIGIN_MAIN`
   permits only the expected non-force fast-forward.
2. `BLUE_GREEN_CODE_DEPLOY_WITH_EXACT_ADDITIVE_V3_MIGRATION_AND_RESTART_ONLY_<FULL_COMMIT>_<MIGRATION_SHA256>`
   binds the exact code and additive migration. It excludes environment,
   collection, live, scheduler, order, OCO, and other trading mutations.
3. `ENABLE_FORWARD_ONLY_OKX_SPOT_BTC_USDT_FILL_READ_AND_APPEND_ONLY_EVIDENCE_<CONFIG_SHA256>_FROM_<UTC_EFFECTIVE_FROM>`
   separately permits only provider read and append-only evidence from the
   bound time; no historical import or backfill.
4. `SET_VERSIONED_PROFIT_START_COHORT_IDENTITY_AND_TINY_LIVE_ENV_<CONFIG_SHA256>_FROM_<UTC_EFFECTIVE_FROM>`
   is deferred until the local contracts are deployed, a fresh hard-gate
   snapshot is proven, and exact evidence binding is implemented. It does
   not itself authorize an order or any scheduler/OCO/Grid mutation.

Until those later stages are separately authorized and verified, the deployed
state remains activation-blocked and the cohort remains `NOT_STARTED`.
