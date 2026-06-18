# agora-trading-api

Standalone Trading service extracted from AgoraMarketAPI.

## Local run

Compile/test-only verification:

```powershell
.\scripts\verify_local.ps1
```

HTTP startup smoke test with an in-memory local database:

```powershell
.\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180
```

Run against a real configured database:

```powershell
mvn spring-boot:run
```

Health check:

```powershell
curl http://localhost:8084/api/actuator/health
```

AgoraMarket exchange-rate integration:

- Configure `AGORA_MARKET_BASE_URL=https://agoramarketapi.purrtechllc.com`, `AGORA_MARKET_INTERNAL_TIMEOUT_MS=3000`, and `AGORA_MARKET_INTERNAL_API_KEY` to call AgoraMarket internal API in production.
- Leave `AGORA_MARKET_INTERNAL_API_KEY` blank for local static fallback.
- Install the provider SDK first when building from a fresh machine:

```powershell
mvn -f C:\Users\Redan\IdeaProjects\AgoraMarketAPI\internal-client\pom.xml install
```

AgoraMarketAPI Telegram gateway integration:

- `GET /api/trading/internal/reports/current`
- `GET /api/trading/internal/reports/analysis`
- `GET /api/trading/internal/reports/weekly`
- Header: `X-Internal-Api-Key`
- Configure `TRADING_INTERNAL_API_KEY` for an independent inbound key, or leave it unset to reuse `AGORA_MARKET_INTERNAL_API_KEY` during the split.

## Initial boundaries

- Owns trading strategy, OCO/grid, signal, market data, backtest, trading diagnostics, and trading MCP.
- Does not depend on AgoraMarket commerce users, orders, products, or wallet tables.
- Current baseline keeps the extracted trading/system repositories needed for the Spring context to start.
- Cross-service dependencies must go through an internal-client SDK or HTTP DTOs, not shared entities/repositories.
- Public HTTP surface is intentionally narrow: OpenAPI docs, actuator probes, rate-limit JSON redirect, and API-key guarded internal report reads for the AgoraMarketAPI Telegram gateway. Trading MCP is internal-only through server-local `/api/mcp`; public dedicated-host `/api/mcp` and shared-host `/api/trading/mcp` must be blocked by nginx.
- Schema baseline prep remains read-only against the shared `agora_market` database; marketplace-owned table names are rejected in trading source mappings, while shared DB extra tables are expected.

See:

- [AGENTS.md](AGENTS.md)
- [SERVICE_BOUNDARY.md](SERVICE_BOUNDARY.md)
- [INTERNAL_API_TODO.md](INTERNAL_API_TODO.md)
- [SPLIT_PROGRESS.md](SPLIT_PROGRESS.md)
- [docs/deploy-runbook.md](docs/deploy-runbook.md)
- [docs/schema-baseline.md](docs/schema-baseline.md)
- [docs/legacy-trading-parity-inventory.md](docs/legacy-trading-parity-inventory.md)

Server verification after deploy:

```bash
bash scripts/verify_server.sh
```

From Windows, run the same server-side verifier through SSH so Linux-only
tools such as `lsof` are checked on the server instead of the workstation:

```powershell
.\scripts\verify_server_ssh.ps1 -SchemaCompare
```

Full read-only split acceptance from Windows, including cross-service live MCP
ownership smoke and active runtime log smoke:

```powershell
.\scripts\verify_split_acceptance_ssh.ps1
```

Post-deploy open-issue acceptance wrapper for the current #1/#2/#3 trading
guardrail handoffs:

```powershell
.\scripts\verify_post_deploy_issue_acceptance_ssh.ps1 -RequireTrailingAcceptance
```

This wrapper runs split acceptance, the reusable server-local MCP parity smoke,
the #1/#2 guardrail MCP smoke, the read-only signal-correctness MCP smoke, and
the #3 trailing-stop replay smoke through server-local read-only calls.
Windows SSH wrappers validate `SshHost` locally and reject unsupported SSH
target syntax before invoking `ssh`, so acceptance tooling cannot be redirected
through option-like targets.
If `-EnvFile` is overridden, the same remote env file is passed through server
verification, split acceptance, and every server-local MCP smoke so the closure
command verifies one consistent runtime configuration.
Omit
`-RequireTrailingAcceptance` only when collecting reachability evidence before
the deployed DB sample is expected to satisfy the 30d PnL target. The wrapper
then ends with `REACHABILITY_ONLY OK`, not the normal issue-acceptance OK.
Do not use that output as #1/#2/#3 closure evidence. The wrapper also fails
#1/#2 acceptance if anti-wick coverage returns
`Operator action: REVIEW_POLICY_GAPS`.
`-SkipSplitAcceptance` is diagnostic-only; output collected with that flag is
not #1/#2/#3 closure evidence, and it cannot be combined with
`-RequireTrailingAcceptance`. A diagnostic-only run must end with
`DIAGNOSTIC_ONLY OK`, not the normal issue-acceptance OK.

Local verification does not prove production currentness. Treat production as current only after an explicit deploy and server verification pass.
When nginx is updated, deploy also verifies dedicated Trading host health at
`https://agoratradingapi.purrtechllc.com/api` and verifies public Trading MCP
is blocked.

Server-local MCP parity smoke against a running local or deployed Trading
service:

```powershell
.\scripts\smoke_mcp_parity.ps1 -BaseUrl http://127.0.0.1:18084/api -McpKey local-smoke-mcp
.\scripts\smoke_mcp_parity_ssh.ps1
```

Read-only trailing-stop PnL replay smoke after a deploy that contains the
`analyzeTrailingStopPnlReplay` MCP tool:

```powershell
.\scripts\smoke_trailing_stop_pnl_replay_ssh.ps1
```

The default mode proves server-local `/api/mcp` reachability, the read-only
boundary marker, the `acceptanceTarget: total trailing PnL improvement >= 5%`
marker, and an explicit replay sample status. Add `-RequireAcceptance` only
when the deployed DB sample is expected to prove the 30d PnL target
(`acceptance=PASS`). Ambiguous same-bar replay rows are reported but excluded
from PnL acceptance totals.

Read-only post-deploy guardrail acceptance smoke for the BTC anti-wick and
event-risk-control issue handoffs:

```powershell
.\scripts\smoke_guardrail_acceptance_ssh.ps1
```

The script calls server-local `/api/mcp` only. It verifies
`analyzeSpotAntiWickPolicyCoverage` and `getEventRiskControlStatus` boundary
and operator-control markers without changing order/OCO/strategy/grid/fund/Earn
state. Add `-RequireNoReviewGaps` when this smoke is used as issue-acceptance
evidence instead of diagnostic reachability evidence.

Cross-service live ownership smoke is maintained in the AgoraMarketAPI repo.
Run it when validating that representative legacy Trading tools are absent from
AgoraMarketAPI and present in `agora-trading-api`:

```powershell
powershell -ExecutionPolicy Bypass -File C:\Users\Redan\IdeaProjects\AgoraMarketAPI\tools\codex\check-live-mcp-split-ownership.ps1
```
