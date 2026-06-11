# Legacy Trading Parity Inventory

Last refreshed: 2026-06-11

This inventory compares the legacy `AgoraMarketAPI` trading ownership surface
with the standalone `agora-trading-api` service. It is source-code evidence only;
it does not prove production cutover and does not disable legacy behavior.

## Scope And Boundary

- Legacy source inspected: `C:\Users\Redan\IdeaProjects\AgoraMarketAPI`
- Standalone source inspected: `C:\Users\Redan\IdeaProjects\agora-trading-api`
- Standalone HTTP base path: `/api/trading`
- Standalone MCP endpoint: `POST /api/trading/mcp`
- Shared DB mode remains `agora_market`.
- Extra marketplace/shared DB tables are expected and must not be cleaned up in
  shared mode.

## HTTP Parity

Standalone Trading intentionally has no legacy marketplace or admin controller
package. Its direct HTTP surface is the narrow split surface:

| Surface | Legacy AgoraMarketAPI | Standalone Trading | Status |
| --- | --- | --- | --- |
| MCP Streamable HTTP | `POST /api/mcp` | `POST /api/trading/mcp` | Carried by standalone path |
| Health | `/api/actuator/health` | `/api/trading/actuator/health` | Carried by standalone path |
| Backtest admin HTTP | `/api/backtests/**` via `BacktestController` | no public HTTP controller | Covered through `BacktestValidationMcpTools`; legacy HTTP still needs disable decision |
| Admin market import/backfill | `/api/admin/market/import`, `/reimport`, `/info`, `/subscribe`, `/backfill-oi` | no public HTTP controller | Covered through guarded MCP/backfill paths where retained; legacy HTTP still needs disable decision |
| Public kline/market HTTP | `/api/market/klines`, `/symbols`, `/intervals`, `/ticker` | no public HTTP controller | Not carried as public HTTP; standalone keeps market-data MCP and internal services |
| Admin OCO HTTP | `/api/admin/oco/**` | no public HTTP controller | Covered through `PositionMcpTools`, `GridMcpTools`, and guarded services; legacy HTTP still needs disable decision |
| Admin scheduler HTTP | `/api/admin/scheduler/**` | no public HTTP controller | Covered through `MetaControlMcpTools.listSchedulers`; legacy HTTP still needs disable decision |

Cutover implication: disable legacy trading HTTP entry points only after the
owner accepts that operator access should be MCP-first under `/api/trading/mcp`.
Do not remove AgoraMarketAPI marketplace HTTP or internal exchange-rate APIs.

## MCP Class Parity

Standalone Trading carries these legacy trading MCP tool classes:

- `AiRouterMcpTools`
- `AiTaskOrchestrationMcpTools`
- `BacktestValidationMcpTools`
- `DiagnosticMcpTools`
- `EarnMcpTools`
- `EnsembleMcpTools`
- `ExecutionEventMcpTools`
- `FundingArbMcpTools`
- `GridMcpTools`
- `GuardianMcpTools`
- `IndicatorMcpTools`
- `MarketDataMcpTools`
- `MetaControlMcpTools`
- `PositionMcpTools`
- `ReportMcpTools`
- `RuntimeEvidenceMcpTools`
- `ScoreBuyMcpTools`
- `SignalCorrectnessMcpTools`
- `StagedAddMcpTools`
- `StrategyManagementMcpTools`
- `TradingManagerMcpTools`
- `TradingMlMcpTools`

Legacy MCP classes intentionally not carried because they are marketplace,
seller, wallet, user connector, support, risk-ops, or knowledge surfaces:

- `CustomerSupportMcpTools`
- `FounderSellerMcpTools`
- `GrowthOpsMcpTools`
- `KnowledgeMcpTools`
- `PredictionMatchV4McpTools`
- `RiskOpsMcpTools`
- `SchedulerMcpTools`
- `SellerFulfillmentMcpTools`
- `SellerOpsMcpTools`
- `SourcingOpsMcpTools`
- `StoreOpsMcpTools`
- `UserMcpTools`
- `WalletOpsMcpTools`

Coverage smoke is provided by `scripts/smoke_mcp_parity.ps1`. It checks the
standalone MCP endpoint with `tools/list` and requires representative tools
from strategy, backtest, grid, market data, diagnostic, ML, reporting, position,
guardian, execution-event, score-buy, runtime-evidence, funding, Earn, ensemble,
AI router, and AI task orchestration surfaces.

## Scheduler Parity

Standalone Trading carries the legacy trading scheduler package and adds explicit
default-off guards for production ownership. Source comparison shows the
standalone scheduler set includes the legacy trading scheduler set plus the
current extracted additions:

- `AlphaPromotionTrackerScheduler`
- `GeminiMarketAdvisorScheduler`
- `GridManagerScheduler`

High-risk ownership groups that must run in exactly one service after cutover:

- order/OCO: `OcoPositionPollerScheduler`, `PositionExitManagerScheduler`,
  `TrailingStopScheduler`, ScoreBuy execution schedulers, `TinyLiveAutoExecutionScheduler`
- grid: `GridManagerScheduler`, `GridAutoRebalanceScheduler`,
  `GridOrphanRecoveryScanner`
- Earn/fund: `EarnTradingBufferTopUpScheduler`, `FundingArbScheduler`
- market-data writes/backfills: `HourlyOrchestrator`,
  `MarketIndicatorHistoryCollector`, `CompositeIndicatorScheduler`,
  `BtcPriceMoveIndicatorCollector`, `WashoutAccumulationIndexScheduler`
- notifications/digests: daily report, autonomous digest, weekly scorecard,
  event scan, execution event, market-signal risk-card, BTC price-move alerts
- ML/strategy automation: ML protection/autoretrain/digest/edge watcher,
  strategy discovery, signal outcome verification, alpha promotion,
  exploration loop/rollout/monitor

Cutover implication: legacy AgoraMarketAPI schedulers must remain enabled until
the owner explicitly starts cutover. When cutover starts, disable only legacy
trading schedulers or their parent feature flags; keep marketplace/system
schedulers and the internal exchange-rate API available.

## Disable Plan Draft

1. Keep `agora-trading-api` on shared `agora_market`; do not split DB.
2. Verify standalone deploy and schema compare in shared mode.
3. Generate and review the Flyway baseline, then switch Trading itself to
   `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` and `SPRING_FLYWAY_ENABLED=true`
   only during an approved deploy.
4. Smoke `/api/trading/mcp` with `getMcpRegistryVersion` and
   `scripts/smoke_mcp_parity.ps1`.
5. Add a low-risk disable switch in AgoraMarketAPI for legacy trading HTTP/MCP
   and `com.agora.scheduler.trading` only.
6. Leave AgoraMarketAPI marketplace, user connector, wallet, seller, support,
   and `/api/internal/exchange-rates/usdt` paths available.
7. Monitor duplicate scheduler execution, SQL errors, MCP auth errors, and
   nginx `/api/trading/` routing failures before deleting legacy code.
