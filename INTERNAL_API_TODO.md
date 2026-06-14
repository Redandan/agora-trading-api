# Internal API TODO

## Exchange Rate

Status: implemented in trading; keep server/provider deployment verification as the remaining acceptance step.

Current trading implementation:

- `com.agora.service.impl.AgoraMarketExchangeRateServiceImpl`
- Uses the `agora-market-internal-client` SDK when `AGORA_MARKET_INTERNAL_API_KEY` is configured.
- Falls back to `com.agora.service.impl.StaticExchangeRateServiceImpl` for local dev, timeout, `401`, or AgoraMarketAPI downtime.

Provider/SDK expectation:

- `AgoraMarketAPI` publishes a thin internal-client SDK.
- `agora-trading-api` depends on the SDK, not the marketplace application jar.
- `StaticExchangeRateServiceImpl` stays fallback-only.

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
  base-url: ${AGORA_MARKET_BASE_URL:http://127.0.0.1:8080}
  internal-api-key: ${AGORA_MARKET_INTERNAL_API_KEY:}
  timeout-ms: ${AGORA_MARKET_INTERNAL_TIMEOUT_MS:3000}
```

Expected local call path:

```text
agora-trading-api -> http://127.0.0.1:8080/api/internal/exchange-rates/usdt
```

Expected server call path:

```text
agora-trading-api -> https://agoramarketapi.purrtechllc.com/api/internal/exchange-rates/usdt
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

Status: not part of the current split.

Decision for first split:

- Prefer independent trading auth or MCP/API-key auth.
- Do not add identity internal API until there is a real shared-login requirement.
- Do not predefine user internal API contracts in trading.

If needed later, add `IdentityInternalClient` with DTOs only. Do not share `User`, `UserOAuthBinding`, or repositories.
