# Minimal Runtime Cleanup Roadmap

Last verified: 2026-07-24

## Objective

Reduce `agora-trading-api` to a maintainable BTC spot runtime without changing
strategy behavior, provider Grid state, open positions, funds, or production
configuration.

The target product is:

1. versioned strategy contracts;
2. owner alias 508 daily BTC PAPER accounting;
3. Donchian SHADOW evidence;
4. exact catalog-owned market data;
5. mechanical execution safety;
6. read-only OKX Native Spot Grid monitoring;
7. minimal reports, notifications, MCP, health, and deployment support.

This document schedules cleanup work. It does not authorize code deletion,
deployment, strategy activation, orders, OCO/Grid mutation, fund movement,
database migration, or production configuration changes.

## Current verified baseline

Production was reverified after the authorized Batch 2A deployment on
2026-07-24 at 18:43 Asia/Taipei:

- deployed runtime commit: `2f4ab79fcb03`;
- active port: `8085`; inactive port `8084` drained;
- Trading MCP: 10 tools and 11 resources;
- catalog contracts:
  - `TV_BTC_DAILY_ACCUMULATION_V1@v1`, PAPER,
    Binance `BTCUSDT@1d`, exchange orders not allowed;
  - `BTC_DONCHIAN_20D_10D_V1@v1`, SHADOW,
    OKX `BTCUSDT@1h`, exchange orders not allowed;
- owner 508 PAPER evaluation is configured `false`;
- exactly two catalog market streams are connected:
  Binance `BTCUSDT@1d` and OKX `BTCUSDT@1h`;
- one provider-managed OKX BTC-USDT Spot Grid is running; this runtime has no
  create, amend, or stop adapter;
- execution-safety status is `OK`; positions `#260/#261/#262` are intentional
  BTC Base holdings without OCO;
- at the acceptance checkpoint, server worktree, `origin/main`, and deployed
  metadata matched `2f4ab79fcb03`;
- local and public dedicated health/MCP passed, and shared-host MCP remained
  blocked.

Deployed source inventory before the first cleanup batch:

- 427 Java files;
- 247 Spring bean/repository/configuration files;
- 51 repositories;
- 2 controllers;
- 9 scheduler-related files;
- no repository test tree;
- only supported deployment and server-verification scripts remain.

The small entry surface and large internal source inventory show that the next
maintenance gain must come from removing unreachable internal modules, not
from removing more API endpoints.

## Cleanup status

Batch 1 was committed, deployed, and accepted on Production on 2026-07-24.

Accepted result:

- removed 26 unreachable Java files;
- Java files: 427 to 401;
- Spring bean/repository/configuration files: 247 to 232;
- repositories: 51 to 47;
- scheduler-related files: 9 to 8;
- removed the retired Execution Event environment keys and runtime-log
  classifier;
- removed the isolated Attention audit-writer method;
- retained historical database table definitions without a migration or table
  deletion;
- `mvn -DskipTests package`: passed;
- retained shell script syntax: passed with Git Bash;
- PowerShell syntax and environment-template validation: passed;
- direct core assertions: 10 MCP tools, no catalog LIVE registration, protected
  runtime files present, deleted symbols absent, migrations unchanged.
- runtime commit `2b8bff881cc1` deployed by blue/green switch from `8085` to
  `8084`;
- independent server verification and shared-database schema comparison
  passed: 42 source entity tables, 0 missing database tables;
- runtime log smoke passed: 0 errors, 0 unknown warnings, and 0 high-risk
  operation-like lines;
- all 10 MCP tools passed after deployment with the same 11-resource registry
  hash;
- startup resolved exactly Binance `BTCUSDT@1d` and OKX `BTCUSDT@1h`, and both
  streams reached `RUNNING`;
- owner 508 remained disabled PAPER with exchange orders unauthorized;
- Donchian exact golden parity passed and remained SHADOW without order, OCO,
  or Telegram actions;
- positions `#260/#261/#262`, execution-safety `issues=0`, and protected
  `0.00050810202 BTC` remained unchanged;
- OKX native Grid `3767345250394603520` remained `running` with 10 USDT,
  10 grids, 11 provider fills, and 2 completed provider groups. This proves
  continuity, not exact-net or long-term profitability.

Batch 2A was committed, deployed, and accepted on Production on 2026-07-24.

Accepted result:

- Production has no explicit enablement for any removed startup root; all four
  startup backfills and migration drift checking resolve to default `false`;
- removed 11 Java files: four startup backfill runners, their exclusive
  Coinalyze, The Graph/Uniswap, Hyperliquid, and aggregate backfill services,
  two exclusive configuration records, and the superseded migration drift
  checker;
- Java files: 401 to 390;
- startup `ApplicationRunner`/`CommandLineRunner` classes: 5 to 1;
- retained `OkxLiquidationWsService` because old indicator components still
  inject it;
- retained the exact-fill one-shot path for separate review because its
  configuration and append service overlap retained evidence code;
- local-smoke defaults and schema-baseline guidance were updated; Flyway
  migrations and historical database tables remain unchanged;
- `mvn -DskipTests package`: passed, compiling 389 source units;
- direct assertions passed for 10 MCP tools, no catalog LIVE registration,
  protected files present, removed symbols absent, and zero migration diff.
- runtime commit `2f4ab79fcb03` deployed by blue/green switch from `8084` to
  `8085`; the old `8084` listener was fully drained;
- independent server, public route, and shared-database schema verification
  passed with 42 source entity tables and 0 missing tables;
- runtime log smoke passed with 0 errors, 0 unknown warnings, and 0 high-risk
  operation-like lines;
- exactly Binance `BTCUSDT@1d` and OKX `BTCUSDT@1h` reached `RUNNING`;
- all 10 MCP tools passed with the unchanged 11-resource registry hash;
- owner 508 remained disabled PAPER; Donchian remained SHADOW and exact golden
  parity passed without order, OCO, or Telegram actions;
- positions `#260/#261/#262`, execution-safety `issues=0`,
  `473.2783880116848 USDT`, and protected `0.00050810202 BTC` matched the
  pre-deploy baseline;
- OKX native Grid `3767345250394603520` retained the same running state, range,
  10 USDT investment, 10 grids, 11 provider fills, and 2 completed provider
  groups.

Batch 2B is a local-only candidate as of 2026-07-24. It has not been committed,
deployed, or accepted on Production.

Local result:

- Production has no explicit enablement for the OKX evidence collector,
  authenticated ingestion, or exact-fill one-shot runner; all three resolve to
  default `false`;
- removed the exact-fill one-shot runner and its exclusive authenticated read
  client, collection, episode assembly, hashing, append repository, collection
  metadata, and immutable-fill entity closure;
- removed the now-unused `AsyncStartup` marker and exact-fill-only
  configuration fields;
- removed 16 Java files and 1,154 lines; Java files decreased from 390 to 374
  and startup `ApplicationRunner`/`CommandLineRunner` classes from 1 to 0;
- removed three source entity mappings, four repository interfaces, and one
  repository implementation while retaining their historical Flyway
  definitions and database tables;
- retained generic OKX evidence code for a separate dependency review;
- retained `OkxLiquidationWsService` for the later indicator dependency
  review;
- retained the native Grid tool's own provider fill pagination. It directly
  reads OKX Grid evidence and did not depend on the removed offline collector;
- `mvn -DskipTests package` passed, compiling 373 source units;
- environment-template validation and direct assertions passed for 10 MCP
  tools, no catalog LIVE registration, unchanged 508/Donchian contracts,
  protected Grid/OCO files, removed-symbol absence, zero startup runners, and
  zero migration diff.

## Protected keep set

The following areas are protected during cleanup. A cleanup batch must not
change their behavior unless separately authorized.

### Strategy core

- `StrategyRuntimeCatalog`;
- `TradingViewDailyStrategyContract`;
- `LocalTradingViewSignalEvaluator`;
- `TradingViewAccumulationPaperEngine`;
- `TradingViewAccumulationPaperService`;
- the minimum backtest/indicator dependency closure needed to reproduce the
  captured TradingView strategy;
- Donchian policy, engine, SHADOW lane, golden parity, and readiness evidence;
- archived strategy inventory reads needed to explain database mappings.

### Market data

- Binance spot `BTCUSDT@1d` closed-bar ingestion;
- OKX spot `BTCUSDT@1h` closed-bar ingestion;
- catalog subscription resolution;
- K-line persistence, duplicate prevention, gap detection, and gap repair;
- closed-bar event dispatch.

### Execution safety

- OKX account and order-state reads;
- OKX private `orders-algo` WebSocket;
- OCO parent/child reconciliation;
- intentional BTC Base no-OCO classification;
- eligible-position protective OCO retry;
- fill, fee, balance, position-ownership, and ambiguous-result reconciliation;
- outbound critical execution-safety notifications.

Execution safety may correct provider/DB state or place a protective OCO for an
eligible legacy position. It is not a strategy-quality gate and must not veto
owner 508 signals.

### Grid boundary

- read-only provider Grid inventory;
- read-only active/history/detail, sub-order, fill, and economic evidence;
- exact separation between Grid-owned BTC and strategy-owned BTC.

No service-side native Grid create, amend, stop, or migration path may be
reintroduced.

### Operations

- the 10-tool MCP whitelist and fail-closed Bearer authentication;
- read-only internal reports used by the AgoraMarketAPI Telegram gateway;
- outbound Telegram notification port needed by retained safety components;
- health/info endpoints;
- shared-database validation and Flyway ownership;
- blue/green deployment, nginx routing, runtime-log checks, and server
  verification;
- AgoraMarket internal exchange-rate client.

## Removal policy

A Java artifact is removable only when all of the following are true:

1. it is outside the protected keep set;
2. it has no inbound path from a registered MCP provider, controller,
   application/event listener, enabled scheduler, protected service, or
   deployment component;
3. its configuration is absent or default-off in Production;
4. deleting it does not change the 10-tool registry, two catalog streams,
   strategy modes, Grid boundary, OCO safety, internal reports, or health;
5. compilation and the batch-specific source assertions pass.

Removing an entity or repository from source does not authorize dropping its
database table. Historical/shared tables remain untouched unless a separate
database migration is explicitly approved.

## Scheduled cleanup batches

### Batch 1 — Unreachable operator subsystems — Production accepted

Risk: low.

Removed source-only features with no current runtime entry:

- the unregistered Execution Event MCP class;
- the default-off Execution Event scheduler;
- Execution Event detectors, notification service, model, repository,
  configuration, enums, and Telegram buttons;
- `SystemReminderService` and its isolated model/repository;
- `SystemSnapshotCollector`;
- `AutoExplorationRolloutStateService` and its isolated transition
  model/repository;
- `AttentionRuleEvaluator` and its isolated rule model/repository, after
  confirming comments/audit labels are the only remaining references.

The `bt_execution_event`, reminder, transition, and attention tables are not
dropped in this batch.

Verified local result:

- fewer always-created Spring beans and repositories;
- no change to callable tools, strategies, market streams, Grid, OCO, reports,
  or notification delivery.

### Batch 2 — Dormant alternative-data and startup-backfill paths — 2A Production accepted; 2B local candidate

Risk: low to medium.

Start from these default-off roots:

- `CoinalyzeBackfillRunner`;
- `CompositeIndicatorBackfillRunner`;
- `DexFlowBackfillRunner`;
- `HyperliquidFundingBackfillRunner`;
- `OkxLiquidationWsService`;
- migration-drift and one-shot evidence runners not used by the protected
  runtime.

Compute the dependency closure for each root, then remove only connectors,
DTOs, indicator implementations, properties, repositories, and environment
keys that are not referenced by 508, Donchian, OCO safety, Grid evidence, or
deployment verification.

Do not remove `MdKline`, the two active K-line providers, OKX authenticated
account/order reads, or exact fill/fee evidence still used by Grid and
execution safety.

Batch 2A removes the four startup backfill runners, their exclusive external
provider/service closure, and `MigrationDriftChecker`. Batch 2B separately
removes the default-off exact-fill one-shot closure after proving that the
native Grid tool has its own provider-read implementation. Batch 2B removes
source mappings only: historical migration definitions and database tables
remain untouched.

`OkxLiquidationWsService` remains deferred until the indicator dependency
review. Generic OKX evidence models, append/read adapters, repositories, and
coverage logic also remain deferred rather than being mixed into Batch 2B.

### Batch 3 — Backtest and strategy-library reduction

Risk: medium.

Build a source dependency closure from:

- `LocalTradingViewSignalEvaluator`;
- `TradingViewAccumulationPaperService`;
- the captured 508 strategy implementation;
- Donchian engine and golden replay.

Keep that closure and remove generic strategy algorithms, diagnostics,
simulation helpers, DTOs, and backtest persistence paths that are reachable
only from archived strategies.

Archived database rows remain queryable as inventory, but they do not retain
their own executable strategy classes.

### Batch 4 — Repository and entity pruning

Risk: medium.

After Batches 1-3, remove Java entities and repositories that have no protected
runtime consumer. Likely review groups include:

- AI task and review artifacts;
- autonomous exploration transitions;
- Polymarket snapshots and alerts;
- old market-flip decisions;
- funding-arbitrage state;
- obsolete indicator alert and override state;
- historical custom-Grid Java mappings not required by native Grid safety.

This is source pruning only. Do not drop, truncate, rename, or migrate shared
database tables.

### Batch 5 — Telegram implementation reduction

Risk: medium.

Retain:

- `NotificationPort.broadcast`;
- `NotificationPort.alert`;
- the outbound channel queue;
- notification logging, deduplication, and critical delivery behavior used by
  market-data and execution-safety components.

Remove only unreferenced Trading-local callback, approval, pinned-message,
inline-button, vacation-era classification, and legacy AI/market-report
formatting paths. AgoraMarketAPI remains the webhook and command owner.

### Batch 6 — Documentation consolidation

Risk: low.

- keep `README.md`, `SERVICE_BOUNDARY.md`, this roadmap,
  `strategy-driven-minimal-runtime.md`, `split-acceptance-status.md`, and
  `deploy-runbook.md` as current documents;
- keep `SPLIT_PROGRESS.md` as the chronological implementation history;
- mark dated research and rollout documents as historical evidence;
- remove or archive obsolete runnable instructions that name deleted scripts,
  retired tools, or superseded architectures.

## Batch acceptance

Every code-removal batch must be one reviewable commit and must pass:

```powershell
mvn -DskipTests package
git diff --check
```

It must also prove by direct source/config assertions:

- exactly 10 MCP tools remain registered;
- no catalog strategy is `LIVE`;
- owner 508 remains PAPER, `BTCUSDT`, `1d`, Binance, database mapping `485`;
- Donchian remains SHADOW, `BTCUSDT`, `1h`, OKX;
- Grid create/amend/stop paths remain absent;
- database `enabled` flags cannot start market streams;
- the retained deployment scripts are unchanged or parse successfully;
- no database migration or schema deletion is included.

Deployment is a separate authorization. After an authorized runtime deploy,
reverify:

1. server worktree/deployed commit/`origin/main` identity;
2. active and drained blue/green ports;
3. health and MCP routing;
4. 10-tool registry identity;
5. exactly two catalog streams for the current modes;
6. owner 508 configuration;
7. Donchian SHADOW evidence continuity;
8. native Grid provider identity and running state;
9. open positions and execution-safety status;
10. runtime errors, unknown warnings, and operation-like log lines.

## Stop conditions

Stop a cleanup batch and reduce its scope when:

- a candidate is in the protected dependency closure;
- compilation reveals an unexpected runtime dependency;
- MCP tool count or names change;
- a controller, event listener, or scheduler gains a missing dependency;
- 508, Donchian, Grid, OCO, account, report, or notification semantics change;
- the batch would require a database migration;
- Production verification would require a trading, Grid, OCO, fund, Telegram,
  scheduler, or environment mutation.

## Recommended next action

Review, commit, deploy, and independently accept Batch 2B as its own runtime
change. Keep `OkxLiquidationWsService` with the later indicator dependency
review, and review the generic OKX evidence closure separately before starting
the broader backtest/library reduction.
