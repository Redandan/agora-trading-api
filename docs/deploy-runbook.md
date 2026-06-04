# Trading Deploy Runbook

## Scope

This repo deploys the standalone trading service. It should not deploy marketplace frontend assets or AgoraMarket commerce APIs.

## Required Environment

Server secrets file:

```bash
/home/ubuntu/.env.trading.secrets
```

Required before enabling AgoraMarket-backed exchange rates:

```bash
AGORA_MARKET_BASE_URL=http://127.0.0.1:8082
AGORA_MARKET_INTERNAL_API_KEY=<same internal key configured in AgoraMarketAPI>
AGORA_MARKET_INTERNAL_TIMEOUT_MS=3000
```

Trading service runtime:

```bash
TRADING_ADMIN_KEY=<set for admin endpoints>
TRADING_MCP_KEY=<set for MCP endpoints>
PORT=8084
```

## Local Acceptance

```powershell
.\scripts\verify_local.ps1
```

Expected:

- `mvn test` passes.
- SDK-backed exchange-rate unit tests pass.
- Spring context test starts with fallback if `AGORA_MARKET_INTERNAL_API_KEY` is not configured.
- No Flutter/AppVersion deployment residue remains.
- No marketplace search logging residue remains.
- No marketplace support-ticket, image-audit, or product-classification residue remains.
- No marketplace address/postal delivery residue remains.
- No unused OAuth2 service/DTO residue remains.
- No WalletConnect/Web3 login residue or OAuth2 client dependency remains.
- No unused AuthService/AuthCode/2FA or marketplace account-login DTO residue remains.
- No WebPush, product-report, product-validator, cart-summary, or marketplace order-event residue remains.
- No unused marketplace delivery/order/wallet enum, notification enum, logistics utility, or delivery/digital-order property residue remains.
- No empty legacy marketplace MCP tool placeholder residue remains.
- No unreferenced betting or marketplace status/type enum residue remains.
- No unused PWA log, traffic analytics, slot analytics, slot cache, or stale product/PWA/slot security-route residue remains.
- No unreferenced chat, staking, transaction DTO or unused marketplace/betting enum residue remains.

## Server Deploy Template

`deploy.sh` is a blue-green skeleton for one host:

```bash
cd /home/ubuntu/agora-trading-api
bash deploy.sh
```

Defaults:

- ports: `8084` and `8085`
- health: `http://127.0.0.1:<port>/api/trading/actuator/health`
- jar: `target/agora-trading-api-1.0-SNAPSHOT.jar`
- env file: `/home/ubuntu/.env.trading.secrets`

## Nginx Path Split

Suggested routing:

```nginx
location /api/trading/ {
    proxy_pass http://127.0.0.1:8084;
}
```

If blue-green is used, deploy should update nginx to the active `app.port`, matching the existing AgoraMarketAPI style.

## Post-Deploy Smoke

```bash
PORT=$(cat /home/ubuntu/agora-trading-api/app.port)
curl -fsS "http://127.0.0.1:${PORT}/api/trading/actuator/health"
curl -fsS "https://agoramarketapi.purrtechllc.com/api/actuator/health"
```

Or run the server verifier:

```bash
cd /home/ubuntu/agora-trading-api
bash scripts/verify_server.sh
```

Optional public path check:

```bash
PUBLIC_TRADING_HEALTH_URL="https://agoramarketapi.purrtechllc.com/api/trading/actuator/health" \
  bash scripts/verify_server.sh
```

Exchange-rate behavior:

- with `AGORA_MARKET_INTERNAL_API_KEY`: trading calls AgoraMarket internal API.
- without key, timeout, or 401: trading falls back to static rates.

`scripts/verify_server.sh` checks:

- required local tools: `curl`, `git`, `java`, `mvn`.
- required server env keys exist in `/home/ubuntu/.env.trading.secrets` without printing secret values.
- active local trading health via `app.port` or default `8084`.
- AgoraMarket production health.
- optional public trading health URL.
- nginx `/api/trading/` path split presence.

## Rollback

Use the previously active port if it is still running:

```bash
echo 8084 > /home/ubuntu/agora-trading-api/app.port
sudo nginx -t && sudo systemctl reload nginx
```

If the old process was already stopped, redeploy the prior git commit:

```bash
git reset --hard <previous-good-commit>
bash deploy.sh
```
