# OKX Native Spot Grid Functional Authorization

## Boundary

This document defines the independent Production authorizations and evidence
needed for `PASS_OKX_NATIVE_GRID_FUNCTIONAL`. It is not itself authorization to
change Production environment, restart, create or stop a provider Bot, sell
BTC, mutate the database, retire a legacy Grid, or remove the custom runtime.

Functional acceptance starts only after:

1. the reviewed native adapter commit is deployed and accepted with both native
   write gates effectively false;
2. every legacy Grid is closed;
3. legacy `HOLDING`, `SELL_FAILED`, `SELL_PARTIAL`, `PENDING_OKX`, and
   `SELLING_OKX` counts are all zero;
4. authenticated OKX inventory shows zero active native Grid Bots; and
5. a fresh server-local create dry-run has no blockers.

## One deterministic tiny-live package

Do not scan parameters, compare multiple ranges, or recenter after an
authorization. Generate exactly one package from the current OKX BTC-USDT last
price and tick size:

- product: `BTC-USDT` Spot Grid;
- `algoOrdType=grid`;
- arithmetic spacing: `runType=1`;
- no leverage and no contract direction;
- `quoteSz=10 USDT` exactly;
- `gridNum=10`;
- `minPx=floor(lastPrice * 0.975 / tickSz) * tickSz`;
- `maxPx=ceil(lastPrice * 1.025 / tickSz) * tickSz`;
- one fresh 1-32 character alphanumeric `algoClOrdId` beginning with
  `AGOKXG1`;
- `windowStartUtc` captured before the create dry-run.

The package is invalid if the last price leaves the range, OKX's exact USDT
minimum is missing or exceeds 10 USDT, another Bot appears, a legacy state
reopens, or any exact field changes. Do not substitute a second range under the
same authorization.

## Authorization A: enable briefly and create exactly one Bot

The fresh packet must bind all of these values:

- deployed full commit;
- `windowStartUtc`;
- last price and tick size;
- exact `minPx`, `maxPx`, `gridNum=10`, and `quoteSz=10`;
- exact OKX minimum investment;
- exact `algoClOrdId`;
- zero active native Bots and zero open/unsafe legacy state;
- exact `requiredConfirmText` returned by
  `createOkxNativeSpotGrid(execute=false)`.

The separate authorization may temporarily set
`TRADING_OKX_NATIVE_GRID_ENABLED=true` and
`TRADING_OKX_NATIVE_GRID_LIVE_ACTION_ENABLED=true`, restart once, and issue
exactly one matching `execute=true` create request. It must then reconcile the
returned `algoId`, repeat the identical request as an idempotency check, prove
the repeat sends no provider create, restore the original environment bytes,
restart with both gates false, and retain all receipts.

Exact template; every placeholder must be replaced from the fresh packet:

> I authorize one Production OKX native BTC-USDT Spot Grid create on deployed
> commit `<FULL_COMMIT>` for acceptance window `<WINDOW_START_UTC>`, using only
> `algoOrdType=grid`, arithmetic `runType=1`, `minPx=<MIN_PX>`,
> `maxPx=<MAX_PX>`, `gridNum=10`, `quoteSz=10 USDT`, and
> `algoClOrdId=<ALGO_CL_ORD_ID>`, only if the immediately preceding server-local
> dry-run has zero blockers, zero active native Bots, zero open/unsafe legacy
> Grid state, OKX exact minimum `<=10 USDT`, and returns exact confirmation
> `<EXACT_CREATE_CONFIRMATION>`. I authorize a byte-preserving temporary change
> of both native Grid execution gates to true, the controlled restart needed to
> load them, exactly one matching create execution, and one identical
> idempotency replay that must send no second provider create. I authorize no
> other Bot, provider order, Grid #10/#11 action, BTC sale, DB mutation, custom
> runtime deletion, parameter substitution, or capital above 10 USDT. After
> reconciliation, restore the original environment bytes, restart with both
> native gates false, and prove the same single `algoId` remains active.

## Authorization B: restart rediscovery only

After create reconciliation, capture the active Bot and registry `startedAt`.
A separate restart-only authorization must bind the exact deployed commit,
`algoId`, `algoClOrdId`, configuration hash, and pre-restart `startedAt`. The
restart runs with both native write gates false. Post-restart evidence must show
a different `startedAt`, the same single Bot and configuration, no provider
create/stop attempt, and no custom Grid activity.

Exact template:

> I authorize one controlled Production restart on deployed commit
> `<FULL_COMMIT>` solely to verify rediscovery of OKX native Grid
> `algoId=<ALGO_ID>`, `algoClOrdId=<ALGO_CL_ORD_ID>`, configuration SHA-256
> `<BOT_SHA256>`, from pre-restart runtime `<PRE_RESTART_STARTED_AT>`. Both native
> Grid write gates must remain false before and after restart. I authorize no
> provider request that creates or stops a Bot, no order, no BTC sale, no DB or
> legacy Grid mutation, no deployment, and no custom-runtime deletion.

## Observation: read-only until one completed provider pair

Observation does not manufacture a BUY or stop early to claim a result. Poll
only when provider state changes. Retain filled/live sub-orders, group IDs,
provider order IDs, fills, signed fees, balances, and safety evidence. Continue
to report `INSUFFICIENT_NATIVE_GRID_FORWARD_EVIDENCE` until at least one real
provider buy/sell group pair is complete.

No gross Grid profit or unrealized balance change is exact-net PnL.

## Authorization C: stop exactly the observed Bot

Stop authorization is generated only after one completed pair exists. The
recommended terminal disposition is `SELL_BASE` (`stopType=1`) so the tiny test
does not intentionally leave attributable BTC. The dry-run must bind the exact
active Bot SHA-256 and current `requiredConfirmText`. Any changed Bot state
invalidates the authorization.

Exact template:

> I authorize one Production stop of OKX native BTC-USDT Spot Grid
> `algoId=<ALGO_ID>` on deployed commit `<FULL_COMMIT>`, disposition
> `SELL_BASE`, provider `stopType=1`, only if the immediately preceding
> server-local stop dry-run has zero blockers and returns exact confirmation
> `<EXACT_STOP_CONFIRMATION>` bound to active Bot SHA-256 `<BOT_SHA256>`. I
> authorize a byte-preserving temporary change of both native Grid execution
> gates to true, the controlled restart needed to load them, and exactly one
> matching stop request. I authorize no other Bot stop/create, no standalone
> BUY/SELL, no legacy Grid mutation, no DB mutation, no custom-runtime deletion,
> and no retry after an ambiguous provider response. Restore the original
> environment bytes, restart with both gates false, and reconcile terminal
> history, fills, signed fees, exact-net PnL, and base residual.

## Gate A evidence bundle

`PASS_OKX_NATIVE_GRID_FUNCTIONAL` requires all of the following from the same
window and exact identity:

1. create dry-run, create response, active reconciliation, and idempotent replay
   proving one provider create attempt and one Bot;
2. pre/post restart registry and native status proving rediscovery without
   recreation;
3. at least one completed provider buy/sell `groupId` pair with complete order,
   fill, timestamp, quantity, price, and signed-fee coverage;
4. stop dry-run, stop response, and terminal history reconciliation;
5. `getOkxNativeSpotGridAcceptanceEvidence(algoId)` with
   `exactNetPnlProven=true`, no live sub-orders, terminal provider state, and
   base residual no greater than one lot;
6. `getOkxNativeSpotGridFunctionalSafetyEvidence(algoId, algoClOrdId,
   windowStartUtc)` with `PASS_GATE_A_SAFETY_COMPONENT_ONLY`;
7. both native write gates false at the end; and
8. no missing, conflicting, truncated, stale, or cross-window receipt.

The safety component is not the overall result. The overall result remains
`FAIL_OKX_NATIVE_GRID_FUNCTIONAL` or
`INSUFFICIENT_NATIVE_GRID_FORWARD_EVIDENCE` until every item above is proven.

