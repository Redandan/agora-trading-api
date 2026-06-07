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
curl http://localhost:8084/api/trading/actuator/health
```

AgoraMarket exchange-rate integration:

- Configure `AGORA_MARKET_BASE_URL=http://127.0.0.1:8080`, `AGORA_MARKET_INTERNAL_TIMEOUT_MS=3000`, and `AGORA_MARKET_INTERNAL_API_KEY` to call AgoraMarket internal API.
- Leave `AGORA_MARKET_INTERNAL_API_KEY` blank for local static fallback.
- Install the provider SDK first when building from a fresh machine:

```powershell
mvn -f C:\Users\Redan\IdeaProjects\AgoraMarketAPI\internal-client\pom.xml install
```

## Initial boundaries

- Owns trading strategy, OCO/grid, signal, market data, backtest, trading diagnostics, and trading MCP.
- Does not depend on AgoraMarket commerce users, orders, products, or wallet tables.
- Current baseline keeps the extracted trading/system repositories needed for the Spring context to start.
- Cross-service dependencies must go through an internal-client SDK or HTTP DTOs, not shared entities/repositories.
- Public HTTP surface is intentionally narrow: OpenAPI docs, actuator probes, rate-limit JSON redirect, and MCP streamable HTTP at `/api/trading/mcp`.
- Schema baseline prep remains read-only until the real `agora_trading` database has been compared; marketplace-owned table names are rejected by the local/server baseline inventory guards.

See:

- [SERVICE_BOUNDARY.md](SERVICE_BOUNDARY.md)
- [INTERNAL_API_TODO.md](INTERNAL_API_TODO.md)
- [SPLIT_PROGRESS.md](SPLIT_PROGRESS.md)
- [docs/deploy-runbook.md](docs/deploy-runbook.md)
- [docs/schema-baseline.md](docs/schema-baseline.md)

Server verification after deploy:

```bash
bash scripts/verify_server.sh
```

Local verification does not prove production currentness. Treat production as current only after an explicit deploy and server verification pass.
