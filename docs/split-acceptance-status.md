# Split Acceptance Status

Last verified: 2026-07-24 23:33 Asia/Taipei

This file is the concise current handoff for the standalone Trading service.
Historical acceptance detail remains in Git and `SPLIT_PROGRESS.md`; it is not
runnable current guidance.

## Current production identity

- repository/server directory: `/home/ubuntu/agora-trading-api`;
- deployed runtime commit: `657f7ae0ed6d`;
- at the acceptance checkpoint, server worktree and `origin/main` matched the
  deployed runtime commit;
- active port: `8084`;
- inactive blue/green port: `8085`, drained;
- local and public dedicated health: passed;
- local and public dedicated MCP: passed with Bearer authentication;
- shared-host `/api/trading/mcp`: blocked with HTTP 404;
- AgoraMarket internal dependency health: passed;
- nginx shared/dedicated upstreams: active port `8084`;
- server worktree: clean.

The current server verification command is:

```powershell
.\scripts\verify_server_ssh.ps1
```

Use `-SchemaCompare` only when entity/schema ownership changed. Verification is
read-only and does not authorize deployment or trading actions.

## Current runtime boundary

Trading owns:

- versioned strategy catalog;
- owner 508 PAPER accounting;
- Donchian SHADOW evidence;
- exact strategy-owned market streams;
- spot OCO reconciliation and account/order safety;
- read-only provider-managed OKX Native Spot Grid monitoring;
- minimal Trading MCP and internal read-only reports;
- Trading schema validation and deployment.

AgoraMarketAPI continues to own:

- marketplace behavior and marketplace user identity;
- Telegram webhook and command dispatch;
- the internal exchange-rate API consumed by Trading.

The shared `agora_market` database remains expected. Extra marketplace tables
are not a Trading cleanup target.

## Strategy state

The runtime catalog contains exactly two executable/evidence contracts:

| Contract | Mode | Market data | Exchange order |
| --- | --- | --- | --- |
| `TV_BTC_DAILY_ACCUMULATION_V1@v1` | PAPER | Binance `BTCUSDT@1d` | not allowed |
| `BTC_DONCHIAN_20D_10D_V1@v1` | SHADOW | OKX `BTCUSDT@1h` | not allowed |

Owner 508 is a display alias for
`TV_BTC_DAILY_ACCUMULATION_V1@v1`. Its temporary database mapping is strategy
`485`. Database strategy `508` is a different archived strategy.

Current owner 508 settings:

```text
configuredEnabled=false
configuredExecutionMode=BTC_BASE_PAPER
configuredStrategyId=485
exchangeOrderAuthorized=false
```

The Binance daily stream remains connected for catalog readiness, but the 508
evaluator does not run while `configuredEnabled=false`.

Donchian runtime evidence is enabled in SHADOW. It has no live implementation,
order, OCO, or Telegram path and cannot block owner 508.

Every unlisted database strategy is `ARCHIVED`; a database `enabled` value
cannot start a stream or enter the runtime evaluation path.

## Market data

Production startup confirmed exactly:

```text
binance SPOT BTCUSDT@1d
okx     SPOT BTCUSDT@1h
```

The catalog owns this inventory. Database strategy flags, environment item
lists, warm-up evaluation, database-change resubscription, and dual-provider
divergence logic do not control subscriptions.

The hourly orchestrator is limited to K-line gap detection/repair. It does not
run AI, ML, meta-control, or position-advice tasks.

## OKX Native Spot Grid

One provider-managed BTC-USDT Spot Grid is running:

```text
algoId=3767345250394603520
algoClOrdId=AGOKXG120260723032002
range=63978..67259
gridNum=10
quoteInvestment=10 USDT
state=running
```

Trading can read active/history/detail, sub-orders, fills, provider grouping,
fees, and economic evidence. It cannot create, amend, or stop a native Grid.
Grid state and floating PnL can change independently of a service deployment.

At the latest acceptance checkpoint the same bot had 11 provider fills and 2
completed provider groups. It remained active, so exact-net functional
acceptance and long-term profitability were not proven.

## Execution safety

The current read-only execution-safety result is:

```text
id=260 BTCUSDT LONG INTENTIONAL_BTC_BASE_NO_OCO
id=261 BTCUSDT LONG INTENTIONAL_BTC_BASE_NO_OCO
id=262 BTCUSDT LONG INTENTIONAL_BTC_BASE_NO_OCO
protected=0
intentionalNoOco=3
issues=0
status=OK
```

The OKX private `orders-algo` WebSocket and OCO polling/reconciliation remain
enabled. These are mechanical execution-safety components, not strategy
quality gates. They may reconcile fills/position state or place a protective
OCO for an eligible legacy position. Intentional BTC Base holdings suppress
generic OCO retry.

## MCP surface

Production exposes exactly 10 tools:

1. `getMcpRegistryVersion`
2. `getStrategyRuntimeCatalog`
3. `getOwner508RuntimeStatus`
4. `analyzeBtcDonchianShadowGoldenParity`
5. `getBtcDonchianShadowReadiness`
6. `getOkxNativeSpotGridStatus`
7. `getOkxNativeSpotGridAcceptanceEvidence`
8. `getExecutionSafetyStatus`
9. `getOpenSpotPositions`
10. `getExchangeAccountSafetySnapshot`

The registry has 11 resources: one version resource and one resource per tool.
Missing, invalid, unknown, and unannotated tool calls fail closed. There is no
Guardian key, External-AI session, Trading-local approval state, or Telegram
approval path.

## Internal reports and notifications

AgoraMarketAPI may call these read-only Trading report routes with the internal
API key:

- `/api/trading/internal/reports/current`;
- `/api/trading/internal/reports/analysis`;
- `/api/trading/internal/reports/weekly`;
- `/api/trading/internal/reports/market-signal-risk/drilldown`.

Trading retains outbound Telegram alerts needed by market-data and
execution-safety components. It does not own the Telegram webhook or callback
state.

## Local acceptance boundary

The repository test tree and non-deployment verification scripts were removed
during strategy-first simplification. For a code-removal batch, local
acceptance is:

```powershell
mvn -DskipTests package
git diff --check
```

Direct source/config assertions must additionally prove:

- exactly 10 MCP tools;
- exactly two catalog contracts and no `LIVE` mode;
- owner 508 and Donchian mappings unchanged;
- no Grid mutation adapter;
- database flags cannot start streams;
- no database migration or table deletion;
- retained deployment scripts still parse.

For docs-only changes, `git diff --check` is sufficient unless the document
claims new runtime or deployment evidence.

## Batch 1 production acceptance

The current source-reduction plan is
`docs/minimal-runtime-cleanup-roadmap.md`.

Batch 1 was committed as `2b8bff881cc1`, deployed, and accepted on Production.
It removes the unreachable Execution Event, SystemReminder, isolated
Meta/Attention, and auto-exploration rollout source while leaving historical
database tables unchanged.

Acceptance evidence:

- local package, retained script syntax, environment-template validation, and
  direct protected-runtime assertions passed;
- blue/green deployment switched `8085` to `8084` and drained `8085`;
- server health, dedicated MCP, nginx routing, and AgoraMarket dependency
  checks passed;
- shared-database comparison found all 42 source entity tables and 0 missing
  tables;
- runtime log smoke found 0 errors, 0 unknown warnings, and 0 high-risk
  operation-like lines;
- all 10 read-only MCP tools passed with 11 resources and unchanged registry
  identity;
- exactly Binance `BTCUSDT@1d` and OKX `BTCUSDT@1h` reached `RUNNING`;
- owner 508 remained disabled PAPER and Donchian remained SHADOW;
- Donchian golden parity passed; neither strategy sent an order, changed OCO,
  or sent Telegram during acceptance;
- positions `#260/#261/#262`, execution-safety `issues=0`, account
  `473.2783880116848 USDT`, and protected `0.00050810202 BTC` matched the
  pre-deploy baseline;
- native Grid `3767345250394603520` remained `running` with unchanged range,
  10 USDT investment, and 10 grids.

The protected keep set is:

- 508 PAPER engine and its minimum captured-strategy dependency closure;
- Donchian SHADOW evidence;
- the two catalog market streams and gap repair;
- OKX account/order reads, private WebSocket, OCO reconciliation, and BTC
  ownership;
- read-only provider Grid monitoring;
- 10-tool MCP, internal reports, critical outbound notifications, health,
  schema validation, and deployment.

Batch 1 remains historical accepted evidence. The current runtime includes the
subsequently accepted Batch 2A reduction described below.

## Batch 2A production acceptance

Batch 2A was committed as `2f4ab79fcb03`, deployed, and accepted on
Production.

It removes four default-off startup backfill runners, their exclusive
Coinalyze, The Graph/Uniswap, Hyperliquid, and aggregate backfill services, two
exclusive configuration records, and the superseded migration drift checker.
It reduces Java files from 401 to 390 and startup runner classes from 5 to 1.

`OkxLiquidationWsService` and the exact-fill one-shot path are intentionally
deferred because they still overlap other source components. No migration,
entity, repository, strategy, market-stream, Grid, OCO, account, report, or
notification component is part of Batch 2A.

Acceptance evidence:

- blue/green deployment switched `8084` to `8085` and drained `8084`;
- server, public route, and shared-database schema verification passed with 42
  source entity tables and 0 missing tables;
- runtime log smoke found 0 errors, 0 unknown warnings, and 0 high-risk
  operation-like lines;
- all 10 MCP tools passed with 11 resources and the unchanged registry hash;
- exactly Binance `BTCUSDT@1d` and OKX `BTCUSDT@1h` reached `RUNNING`;
- owner 508 remained disabled PAPER and Donchian remained SHADOW with exact
  golden parity;
- no strategy order, OCO change, Grid mutation, database mutation, or Telegram
  send occurred during acceptance;
- positions `#260/#261/#262`, execution-safety `issues=0`,
  `473.2783880116848 USDT`, and protected `0.00050810202 BTC` matched the
  pre-deploy baseline;
- native Grid `3767345250394603520` remained `running` with the same range,
  10 USDT investment, 10 grids, 11 provider fills, and 2 completed provider
  groups.

## Batch 2B production acceptance

Batch 2B was committed as `cee8a45d848d`, deployed, and accepted on
Production.

The candidate removes the default-off exact-fill one-shot runner and its
exclusive authenticated provider-read, collection, episode assembly, hashing,
append, collection-metadata, and immutable-fill source closure. It also removes
the now-unused `AsyncStartup` marker and exact-fill-only configuration fields.

Local evidence:

- Production has no explicit collector, authenticated-ingestion, or exact-fill
  one-shot enablement; all three settings resolve to default `false`;
- 16 Java files and 1,154 lines are removed;
- Java files decrease from 390 to 374, compiled source units from 389 to 373,
  and startup runner classes from 1 to 0;
- three source entity mappings, four repository interfaces, and one repository
  implementation are removed, but historical Flyway definitions and database
  tables remain unchanged;
- generic OKX evidence code and `OkxLiquidationWsService` remain deferred;
- native Grid provider-fill reads remain in `OkxNativeGridMcpTools` and do not
  depend on the removed collector;
- `mvn -DskipTests package`, environment-template validation, direct protected
  runtime assertions, removed-symbol checks, and migration-diff checks passed;
- the repository has no test tree, so this is package and direct-contract
  evidence, not automated test-suite evidence.

Production evidence:

- blue/green deployment switched `8085` to `8084` and fully drained `8085`;
- server worktree, `origin/main`, and deployed metadata matched
  `cee8a45d848d`;
- local and public health, dedicated authenticated MCP, nginx routing, and the
  AgoraMarket dependency passed; the shared-host Trading MCP remained blocked;
- shared-database comparison found 39 source entity tables, 209 database
  tables, and 0 missing source tables; no migration or table deletion ran;
- runtime log smoke found 0 errors, 0 unknown warnings, and 0 high-risk
  operation-like lines;
- all 10 MCP tools passed with 11 resources and the unchanged registry hash;
- exactly Binance `BTCUSDT@1d` and OKX `BTCUSDT@1h` reached `RUNNING`;
- owner 508 remained disabled PAPER with exchange orders unauthorized;
- Donchian golden parity and runtime integrity passed while it remained SHADOW
  with no order, OCO, or Telegram action;
- positions `#260/#261/#262`, execution-safety `issues=0`,
  `473.2783880116848 USDT`, and protected `0.00050810202 BTC` matched the
  pre-deploy baseline;
- native Grid `3767345250394603520` remained `running` with 11 provider fills
  and 2 completed provider groups; exact-net acceptance remains
  `NOT_YET_PROVEN` while the bot is active.

## Batch 2C production acceptance

Batch 2C was committed as `505850dda60b`, deployed, and accepted on
Production.

The candidate removes the generic OKX evidence normalize, coverage, append,
and read-summary closure. It does not remove native Grid provider reads or OKX
account/order/OCO safety.

Local evidence:

- the generic collector and authenticated-ingestion Production switches are
  absent and therefore default to `false`;
- `executable_quote_snapshot`, `fill_fee_ledger`,
  `funding_bill_ledger`, and `margin_snapshot` each contain zero rows;
- 19 Java files and 1,395 lines are removed;
- Java files decrease from 374 to 355, compiled source units from 373 to 354,
  repository files from 42 to 35, and source entity tables from 39 to 35;
- four source entity mappings, six repository interfaces, one repository
  implementation, and the isolated Spring service/configuration closure are
  removed;
- the V2 migration and all four historical database tables remain unchanged;
- the pure local `CoverageProfiler` and CLI remain;
- `mvn -DskipTests package`, environment-template validation, direct protected
  runtime assertions, removed-symbol checks, and migration-diff checks passed;
- the repository has no test tree, so this is package and direct-contract
  evidence, not automated test-suite evidence.

Production evidence:

- blue/green deployment switched `8084` to `8085` and fully drained `8084`;
- server worktree, `origin/main`, and deployed metadata matched
  `505850dda60b` at the runtime acceptance checkpoint;
- local and public health, dedicated authenticated MCP, nginx routing, and the
  AgoraMarket dependency passed; the shared-host Trading MCP remained blocked;
- shared-database comparison found 35 source entity tables, 209 database
  tables, and 0 missing source tables;
- the four retained V2 evidence tables still contained zero rows after
  deployment; no migration, table deletion, or database-data mutation ran;
- runtime log smoke found 0 errors, 0 unknown warnings, and 0 high-risk
  operation-like lines;
- all 10 MCP tools passed with 11 resources and the unchanged registry hash;
- exactly Binance `BTCUSDT@1d` and OKX `BTCUSDT@1h` reached `RUNNING`;
- owner 508 remained disabled PAPER with exchange orders unauthorized;
- Donchian golden parity and runtime integrity passed while it remained SHADOW
  with no order, OCO, or Telegram action;
- positions `#260/#261/#262`, execution-safety `issues=0`,
  `473.2783880116848 USDT`, and protected `0.00050810202 BTC` matched the
  pre-deploy baseline;
- native Grid `3767345250394603520` remained `running`. Provider fills advanced
  naturally from 13 before deployment to 14 during acceptance, while completed
  provider groups remained 2. The active bot's exact-net acceptance remains
  `NOT_YET_PROVEN`.

## Batch 2D production acceptance

Batch 2D was committed as `657f7ae0ed6d`, deployed, and accepted on
Production.

The candidate removes the default-off OKX liquidation WebSocket and its two
uncalled SQI consumers. It does not change the two catalog market streams,
owner 508, Donchian, native Grid provider reads, OKX authenticated
account/order reads, private OCO WebSocket, or execution safety.

Local evidence:

- Production has no `MARKET_LIQUIDATION_WS_ENABLED` setting, and the active
  runtime logs the service as disabled;
- only `SqiIndicator` and `SqueezeIndicatorService` injected the liquidation
  WebSocket; neither has a current caller or scheduler entry;
- Production SQI/liquidation indicator rows were last written on 2026-06-13,
  with zero rows in the latest seven-day window and no matching Telegram
  source rows;
- four Java files and 908 Java lines are removed; current main Java files
  decrease from 354 to 350;
- seven obsolete local-smoke settings and stale SQI diagnostic-source metadata
  are removed;
- historical indicator rows, entities, repositories, and migrations remain
  unchanged;
- `mvn -DskipTests package` passed, compiling 350 source files;
- environment-template validation and direct
  10-tool/no-LIVE/508/Donchian/Grid/OCO/removed-file/zero-migration-diff
  assertions passed;
- the repository has no test tree, so this is package and direct-contract
  evidence, not automated test-suite evidence.

Production evidence:

- blue/green deployment switched `8085` to `8084` and fully drained `8085`;
- server worktree, `origin/main`, and deployed metadata matched
  `657f7ae0ed6d` at the runtime acceptance checkpoint;
- local and public health, dedicated authenticated MCP, nginx routing, and the
  AgoraMarket dependency passed; the shared-host Trading MCP remained blocked;
- shared-database comparison found 35 source entity tables, 209 database
  tables, and 0 missing source tables;
- the active startup log contained zero `OkxLiqWS` lines while exactly Binance
  `BTCUSDT@1d` and OKX `BTCUSDT@1h` reached `RUNNING`;
- runtime log smoke found 0 errors, 0 unknown warnings, and 0 high-risk
  operation-like lines;
- all 10 MCP tools passed with 11 resources and the unchanged registry hash;
- owner 508 remained disabled PAPER with exchange orders unauthorized;
- Donchian golden parity and runtime integrity passed while it remained SHADOW
  with no order, OCO, or Telegram action;
- positions `#260/#261/#262`, execution-safety `issues=0`,
  `473.2783880116848 USDT`, and protected `0.00050810202 BTC` matched the
  pre-deploy baseline;
- native Grid `3767345250394603520` remained `running` with 14 provider fills
  and 2 completed provider groups; exact-net acceptance remains
  `NOT_YET_PROVEN`;
- retained SQI/liquidation historical row counts and latest timestamps remained
  unchanged; no migration, schema, or database-data mutation ran.

## Batch 3A committed candidate

Batch 3A is committed and pushed as runtime commit `10e5ee3fd9ec`, but it is
not deployed or accepted on Production. The current Production identity above
remains `657f7ae0ed6d`.

Local evidence:

- Production catalog and read-only database inspection confirmed strategy
  `485` is `SCORE_BUY_V2`; its compatibility adapter and frozen
  `ScoreBuyStrategy` delegate remain protected;
- Production database strategy `508` is the separate archived
  `OI_FUNDING_DIVERGENCE` strategy;
- removed six archived executable strategy implementations and ten uncalled
  backtest validation, replay, simulation, and optimization helpers;
- removed 16 Java files, 4,089 Java lines, 12 Spring beans, and four obsolete
  local-smoke configuration lines; Java files decreased from 350 to 334;
- archived strategy and backtest database rows remain queryable; no entity,
  repository, migration, schema, or database-data change is included;
- `mvn -DskipTests package` passed while compiling 334 source files;
- environment-template validation passed with 43 keys;
- direct assertions passed for exactly 10 MCP tools, exactly two non-LIVE
  catalog contracts, unchanged owner 508 and Donchian mappings, retained
  Grid/OCO files, absent Grid mutation tools, removed source symbols, and zero
  migration/deployment-script diff.

Recommended next action: deploy and Production-accept Batch 3A only with
separate authorization.

## Not proven by acceptance

Deployment and health evidence do not prove:

- owner 508 profitability;
- Donchian forward profitability;
- Grid long-term profitability;
- authorization to enable PAPER or LIVE;
- authorization to place/cancel/modify an order or OCO;
- authorization to create/stop a Grid;
- authorization to move funds, send an operator message, or alter Production;
- authorization to migrate or delete database tables.
