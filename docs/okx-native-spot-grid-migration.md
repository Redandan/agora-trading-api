# OKX Native Spot Grid Migration

## Decision

The local `bt_grid` execution engine is deprecated for new Grid creation. The
migration target is OKX-native Spot Grid, while existing local grids remain
queryable and closable until their holdings and residuals are retired.

This document is a migration plan, not authorization to place or stop an OKX
bot, sell a legacy holding, change production environment, deploy, or restart.

## Phase 1: freeze expansion and establish read-only visibility

- Keep `TRADING_GRID_ENABLED=false`.
- Keep `TRADING_GRID_CUSTOM_CREATE_RESUME_ENABLED=false`.
- Block local `createGrid`, `resumeGrid`, and enabling local auto-rebalance.
- Preserve `listGrids`, `gridStats`, `closeGrid`, and residual cleanup so legacy
  inventory can be reviewed and retired through separately authorized actions.
- Use `getOkxNativeSpotGridStatus(includeHistory=true)` for server-local,
  read-only OKX-native Bot inventory.
- Use `previewOkxNativeSpotGridMigration` to produce a read-only BTC-USDT,
  Spot 1x, one-bot, at-most-10-USDT migration package. It reports legacy
  inventory and reads OKX public `minSz`, `lotSz`, `tickSz`, and ticker evidence
  to reject configurations below the public-rule lower bound. This lower bound
  is not the Bot create endpoint's final minimum-investment acceptance, which
  remains an explicit blocker until an exactly authorized provider request.
- Do not add an OKX-native create/stop write tool in this phase.

## Phase 2: retire legacy inventory

1. Refresh `listGrids`, `gridStats`, exposure, spot holdings, and OKX fills.
2. Obtain exact authorization for each legacy Grid holding disposition.
3. Close or retain each holding according to that authorization.
   Retirement quantity must be reconstructed from the original OKX BUY order,
   including signed base-currency fees and lot-size rounding. The legacy DB
   gross `filled_qty` and aggregate account BTC balance are not sell authority.
4. Require zero `HOLDING`, `SELL_FAILED`, `SELL_PARTIAL`, `PENDING_OKX`, and
   `SELLING_OKX` rows before declaring the local engine retired.
5. Keep historical rows for attribution; do not drop tables or migrations.

Grid #10 and Grid #11 are not implicitly authorized by this plan. Grid #10 has
had a holding and Grid #11 has had none; both require fresh production evidence
before any action.

## Phase 3: native Bot write adapter

The local candidate may contain the write adapter before it is deployed, but it
must remain disabled through both `TRADING_OKX_NATIVE_GRID_ENABLED=false` and
`TRADING_OKX_NATIVE_GRID_LIVE_ACTION_ENABLED=false`. Phase 1 Production deploy
authorization remains pinned to its exact earlier commit and does not include
this adapter.

Execution also requires the existing `TRADING_OKX_ENABLED=true` master trading
switch. The native adapter cannot bypass that global provider-order gate.

Enable the separately gated adapter for OKX `Place grid algo order` only after:

- account-level native Grid API availability is proven read-only;
- total quote investment is the single capital authority;
- symbol is fixed to `BTC-USDT`, algo type is Spot Grid, and leverage is absent;
- maximum quote investment is no more than 10 USDT;
- OKX accepts that amount above its current minimum;
- no local or native Grid holding would violate the single-holding intent;
- create is idempotent and binds the returned OKX `algoId` to immutable audit
  evidence;
- stop semantics explicitly distinguish sell-base from keep-base;
- exact confirmation text and separate production authorization are supplied.

The adapter must default to disabled and expose preview/read tools before any
write tool. Trend classification may be advisory, but capital, product type,
idempotency, minimum-order, and single-holding checks remain hard blockers.

The protected create tool fixes `algoOrdType=grid`, `runType=1`, uses only
`quoteSz`, rejects `quoteSz>10`, requires a fresh unique provider
`algoClOrdId`, and re-reads active/history inventory before execution. The
preflight calls OKX's public `POST /api/v5/tradingBot/grid/min-investment` for
the exact range and grid count, requires a positive USDT minimum, and blocks
when that minimum exceeds 10 USDT or the requested `quoteSz`. The
protected stop tool requires an explicit `SELL_BASE` versus `KEEP_BASE`
disposition and binds confirmation to a SHA-256 of the current provider Bot
detail. Neither tool writes the local database.

## Acceptance standard

Migration is accepted only when both Gate A and Gate B pass. Passing tests,
deploying code, or disabling a flag alone is not acceptance.

### Gate A: OKX-native Grid works normally

All items are required:

1. The production runtime is on a reviewed commit and exposes read-only native
   Grid status, preview, create, and stop workflows with distinct authorization
   boundaries.
2. The provider preflight proves that OKX accepts the exact package: `BTC-USDT`,
   Spot Grid, 1x/no leverage, one active Grid bot, and total quote investment
   no greater than 10 USDT. If the OKX minimum exceeds 10 USDT, migration fails
   under the current capital authorization.
3. A separately authorized production create request returns one OKX `algoId`;
   querying active bots returns that same ID and exact configuration. Retries
   cannot create a second bot.
4. At least one real buy fill and its paired sell complete through the native
   bot. Provider order IDs, fill quantities, fill prices, signed fees, and
   timestamps are retained as exact forward evidence.
5. Reporting reconciles OKX bot state, spot balance, open/unpaired inventory,
   completed Grid profit, signed fees, and exact-net PnL. No gross or unrealized
   PnL is presented as exact-net profit.
6. During the acceptance window there are zero duplicate bots, zero duplicate
   orders caused by retries, zero unexplained holdings, zero unprotected filled
   inventory, and zero local custom-Grid orders.
7. Restart and reconnect verification shows the same OKX bot is rediscovered;
   the application neither recreates it nor loses its attribution identity.
8. A separately authorized stop test reaches the requested terminal semantics
   (`keep base` or `sell base`), leaves no unexplained residual, and the bot is
   observable in history rather than active inventory.
9. Functional acceptance requires one completed pair and a clean stop. Profit
   is reported but is not fabricated as a functional requirement: promotion
   beyond the 10 USDT exploration still requires a separate forward performance
   standard for exact-net PnL, drawdown, and stability.

Gate A result is `PASS_OKX_NATIVE_GRID_FUNCTIONAL` only when every item passes;
otherwise it is `FAIL_OKX_NATIVE_GRID_FUNCTIONAL` and the custom engine cannot
be removed yet.

### Gate B: custom Grid system is removed

Gate B starts only after Gate A passes and all legacy inventory is retired.
All items are required:

1. Every legacy Grid has `closed_at`, and there are zero `HOLDING`,
   `SELL_FAILED`, `SELL_PARTIAL`, `PENDING_OKX`, and `SELLING_OKX` rows. Spot
   balances and OKX fills reconcile before removal.
2. Custom `createGrid`, `resumeGrid`, and auto-rebalance tools are deleted, not
   merely disabled. No API, MCP tool, scheduler, CLI, or internal call path can
   create or revive a local Grid.
3. The custom execution engine, scanners, auto-rebalance scheduler, provider
   buy/sell calls, mutation services, configuration flags, and production
   wiring are removed. Repository-wide search finds no executable custom Grid
   order path.
4. Legacy close/recovery code is removed only after item 1 is proven. Until
   then it remains a quarantined retirement path and cannot open new exposure.
5. Historical Grid records needed for signed-fee PnL attribution are exported
   to an immutable archive and reconciled to provider evidence.
6. Dropping `bt_grid` / `bt_grid_level` tables is a final, separately authorized
   database migration after archive verification and backup. Code/runtime
   removal may pass before physical table deletion, but final data-layer
   deletion cannot be claimed until that migration is applied and verified.
7. Full local verification, startup smoke, split-boundary checks, and production
   post-deploy read-only checks pass. The runtime registers only OKX-native Grid
   tools and no custom Grid mutation tools.
8. Rollback disables the OKX-native adapter or stops the native bot according
   to an exact authorization; rollback cannot restore the deleted custom Grid
   engine.

Gate B has two explicit receipts:

- `PASS_CUSTOM_GRID_RUNTIME_REMOVED`: code, scheduler, wiring, and all custom
  order paths are gone; archived tables may remain read-only.
- `PASS_CUSTOM_GRID_FULLY_DELETED`: the separately authorized archive and DB
  migration are verified and legacy tables are absent.

The overall migration status is `MIGRATION_ACCEPTED` only after Gate A and
`PASS_CUSTOM_GRID_RUNTIME_REMOVED`. If physical data deletion is required by
scope, acceptance remains incomplete until `PASS_CUSTOM_GRID_FULLY_DELETED`.
