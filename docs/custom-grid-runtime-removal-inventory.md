# Custom Grid Runtime Removal Inventory

## Boundary

This is the deletion inventory for `PASS_CUSTOM_GRID_RUNTIME_REMOVED`. It is
not authorization to delete code, deploy, mutate legacy rows, archive data, or
drop tables. Removal begins only after `PASS_OKX_NATIVE_GRID_FUNCTIONAL` and a
fresh proof of zero legacy holding/in-flight state.

## Mandatory preconditions

All conditions must be proven from Production immediately before removal:

1. OKX-native Gate A is `PASS_OKX_NATIVE_GRID_FUNCTIONAL`.
2. Every legacy Grid has `closed_at`.
3. There are zero `HOLDING`, `SELL_FAILED`, `SELL_PARTIAL`, `PENDING_OKX`, and
   `SELLING_OKX` levels.
4. No provider order or attributable BTC remains unresolved.
5. Grid and Grid-level rows, original provider order/fill receipts, signed
   fees, exact-net PnL, and reconciliation metadata have been exported and
   hashed into the immutable archive.

Failure of any condition keeps the retirement-only runtime quarantined and
blocks deletion.

## Delete executable custom-Grid paths

The removal change must delete, rather than disable, these runtime families:

- `GridManagerService` buy/sell state machine and `GridManagerScheduler`;
- `GridAutoRebalanceScheduler`, including close-and-recreate behavior;
- `GridOrphanRecoveryScanner` and `GridRecoveryProperties`;
- custom `createGrid`, `resumeGrid`, `pauseGrid`, `closeGrid`, residual cleanup,
  and `enableGridAutoRebalance` MCP writes;
- all provider BUY/SELL calls reachable only from the custom Grid engine;
- `GridExecutionEventDetector` custom-runtime events;
- `TradingGridProperties` and the custom runtime/recovery/rebalance environment
  keys;
- custom Grid MCP registration and every internal call to its mutation methods.

The entire `GridMcpTools` class should be removed. If historical read-only
reporting is still required, replace it with a narrowly named archive reader
that has no repository `save`, no provider write dependency, and no scheduler
wiring.

## Remove downstream runtime coupling

The deletion must inspect and remove custom Grid assumptions from:

- `WsSubscriptionResolver` dynamic subscriptions;
- `DailyReportScheduler` active Grid and sell-failure reporting;
- `OcoPositionPollerScheduler` expected-inventory reconciliation;
- `SymbolExposureService`, `CapitalAllocationPolicyPreviewService`,
  `TinyLiveMinimumOrderPreviewService`, and `TradingManagerService` exposure or
  capital reservations;
- `OpportunityScannerService`, `PriceScenarioSimulationService`, and
  `TradingAnalysisService` custom Grid recommendations;
- startup smoke required-tool lists and MCP category/auth descriptions.

Removing these references must not weaken OCO/position inventory accounting.
The replacement source for native Bot inventory is provider-native active bot,
detail, and sub-order evidence.

## Remove obsolete operator workflows

Delete custom Grid scripts whose purpose is open, create, resume, activate,
resize/rebuild, auto-rebalance, trend override, capital override, or custom
post-env verification. Update generic verification scripts so they require the
native status/create/stop tools and reject custom mutation tools.

Historical filenames may be listed in the archive manifest, but executable
copies must not remain on the runtime deployment path.

## Preserve until separately authorized DB deletion

`BtGrid`, `BtGridLevel`, and their repositories may temporarily remain only for
read-only archive verification while `bt_grid` and `bt_grid_level` still exist.
During this interval:

- no production bean may call repository `save`/`delete` for these types;
- no scheduler or provider-write service may depend on them;
- no MCP tool may create, resume, mutate, or retire them;
- the tables are historical evidence, not runtime state.

After archive verification, backup, and separate DB migration authorization,
remove the entities, repositories, migrations/schema references, and physical
tables for `PASS_CUSTOM_GRID_FULLY_DELETED`.

## Required verification for runtime removal

Acceptance requires all of the following, not merely green unit tests:

1. repository search finds no custom Grid create/resume/rebuild/scheduler/
   scanner/provider-order path;
2. Spring startup registers OKX-native Grid tools and no custom Grid mutation
   tools;
3. scheduler inventory contains no custom Grid scheduler or scanner;
4. full local verification, startup smoke, and split-boundary checks pass;
5. Production post-deploy MCP and scheduler inventory match items 2 and 3;
6. Production account balance, native Bot state, provider fills, and archived
   legacy exact-net totals reconcile with no new custom order;
7. rollback disables the native adapter and cannot restore the deleted custom
   engine.

The required receipt is `PASS_CUSTOM_GRID_RUNTIME_REMOVED`. Table presence is
reported separately and cannot be misrepresented as
`PASS_CUSTOM_GRID_FULLY_DELETED`.
