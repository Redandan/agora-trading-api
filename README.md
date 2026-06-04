# agora-trading-api

Standalone Trading service extracted from AgoraMarketAPI.

## Local run

```powershell
mvn spring-boot:run
```

Health check:

```powershell
curl http://localhost:8084/api/trading/actuator/health
```

Local verification:

```powershell
.\scripts\verify_local.ps1
```

AgoraMarket exchange-rate integration:

- Configure `AGORA_MARKET_BASE_URL` and `AGORA_MARKET_INTERNAL_API_KEY` to call AgoraMarket internal API.
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

See:

- [SERVICE_BOUNDARY.md](SERVICE_BOUNDARY.md)
- [INTERNAL_API_TODO.md](INTERNAL_API_TODO.md)
- [SPLIT_PROGRESS.md](SPLIT_PROGRESS.md)
- [docs/deploy-runbook.md](docs/deploy-runbook.md)

Server verification after deploy:

```bash
bash scripts/verify_server.sh
```
