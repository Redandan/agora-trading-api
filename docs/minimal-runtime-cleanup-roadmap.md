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

Production was reverified read-only on 2026-07-24:

- deployed commit: `788ad4a8c60c`;
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
- server worktree, `origin/main`, and deployed metadata match;
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

Batch 1 is implemented and verified locally on 2026-07-24. It is not committed
or deployed evidence.

Local candidate result:

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

### Batch 1 — Unreachable operator subsystems — locally verified

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

### Batch 2 — Dormant alternative-data and startup-backfill paths

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

Review and commit the isolated Batch 1 candidate. Deployment remains a separate
authorization and must pass the Production acceptance listed above. Start Batch
2 only after Batch 1 is accepted; do not combine it with backtest cleanup.
