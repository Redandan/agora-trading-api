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

## Initial boundaries

- Owns trading strategy, OCO/grid, signal, market data, backtest, trading diagnostics, and trading MCP.
- Does not depend on AgoraMarket commerce users, orders, products, or wallet tables.
- Current baseline keeps the extracted trading/system repositories needed for the Spring context to start.
- Cross-service dependencies must go through an internal-client SDK or HTTP DTOs, not shared entities/repositories.

See:

- [SERVICE_BOUNDARY.md](SERVICE_BOUNDARY.md)
- [INTERNAL_API_TODO.md](INTERNAL_API_TODO.md)
- [SPLIT_PROGRESS.md](SPLIT_PROGRESS.md)
