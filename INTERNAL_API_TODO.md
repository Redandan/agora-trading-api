# Internal API TODO

## Exchange Rate

Status: planned.

Current trading implementation:

- `com.agora.service.impl.StaticExchangeRateServiceImpl`
- Static fallback rates for `USD`, `USDT`, and `TWD`.

Target implementation:

- `AgoraMarketAPI` publishes a thin internal-client SDK.
- `agora-trading-api` depends on the SDK, not the marketplace application jar.
- `StaticExchangeRateServiceImpl` becomes fallback-only or is replaced by an SDK-backed implementation.

SDK contract:

```java
public interface ExchangeRateInternalClient {
    List<ExchangeRateInfo> getUsdtRates();
    ExchangeRateInfo getUsdtRate(String currency);
}
```

Runtime config:

```yaml
agora-market:
  base-url: ${AGORA_MARKET_BASE_URL:http://localhost:8080}
  internal-api-key: ${AGORA_MARKET_INTERNAL_API_KEY:}
  timeout-ms: ${AGORA_MARKET_INTERNAL_TIMEOUT_MS:3000}
```

Expected local call path:

```text
agora-trading-api -> http://localhost:8080/api/internal/exchange-rates/usdt
```

Expected server call path:

```text
agora-trading-api -> http://127.0.0.1:8080/api/internal/exchange-rates/usdt
```

AgoraMarketAPI provider config:

```yaml
internal:
  api-key: ${INTERNAL_API_KEY:}
```

Header:

```text
X-Internal-Api-Key: <same value>
```

## Identity

Status: deferred.

Decision for first split:

- Prefer independent trading auth or MCP/API-key auth.
- Do not add identity internal API until there is a real shared-login requirement.

If needed later, add `IdentityInternalClient` with DTOs only. Do not share `User`, `UserOAuthBinding`, or repositories.
