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
SPRING_DATASOURCE_URL=jdbc:mysql://10.0.0.119:3306/agora_trading?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=<set for trading DB>
SPRING_DATASOURCE_PASSWORD=<set for trading DB>
# temporary bootstrap-only schema mode; replace after Flyway baseline is added.
SPRING_JPA_HIBERNATE_DDL_AUTO=update
SPRING_FLYWAY_ENABLED=false
PORT=8084
```

`SPRING_JPA_HIBERNATE_DDL_AUTO=update` is a temporary bootstrap-only schema mode.
It is not production hardening. Keep `SPRING_FLYWAY_ENABLED=false` until a
Flyway baseline exists under `src/main/resources/db/migration`; then replace
Hibernate schema update with schema validation and enable Flyway.
Use `scripts/schema_baseline_inventory.ps1` and `docs/schema-baseline.md` as the
read-only inventory step before generating the baseline.
Use `scripts/schema_baseline_compare_server.sh` on the server for the read-only
database table comparison; it must not deploy or mutate schema.
`scripts/verify_server.sh` can run that comparison with
`RUN_SCHEMA_BASELINE_COMPARE=1`; the default remains skipped for normal deploy
health checks.

## Local Acceptance

Split-boundary expectations are documented in `docs/split-audit.md`.

```powershell
.\scripts\verify_local.ps1
```

Expected:

- `mvn test` passes.
- SDK-backed exchange-rate unit tests pass.
- Spring context test starts with profile `local-smoke` and exchange-rate fallback if `AGORA_MARKET_INTERNAL_API_KEY` is not configured.
- Split deploy guardrails stay documented: blue-green cleanup, strict server env checks, `8084/8085` port validation, internal-client SDK install, temporary schema bootstrap mode, Flyway baseline prerequisite, and `/api/internal/...` contract paths.
- Schema baseline prep stays read-only until the real trading database schema has been compared with `target/schema-baseline/entity-tables.txt`.
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
- No unused object storage, login/Tron, security audit, group AI, common utility, OCI maintenance, or AI group config residue remains.

HTTP startup smoke with an in-memory local database:

```powershell
.\scripts\smoke_local_health.ps1
```

Expected:

- Spring Boot starts with profile `local-smoke`.
- `local-smoke` does not register scheduled tasks; smoke logs include `Scheduling disabled for local-smoke profile`.
- Health passes at `http://127.0.0.1:18084/api/trading/actuator/health`.
- Smoke command-line overrides clear local external keys for AgoraMarket, OKX, Binance, and Telegram even if host environment variables are set.
- Smoke logs prove H2 local DB, exchange-rate fallback, cleared OKX API key, disabled OKX auto-trade, skipped private WS, and disabled startup refresh.
- Smoke logs must not show order placement, Telegram sends, private trading WS connection, or auto-execution enabled.
- The script stops the temporary Maven/Java process tree after the check.

## Server Deploy Template

`deploy.sh` is a blue-green skeleton for one host:

First-time server bootstrap/preflight:

```bash
cd /home/ubuntu/agora-trading-api
bash scripts/bootstrap_server.sh
```

If the repo has not been cloned yet:

```bash
bash -c "$(curl -fsSL https://raw.githubusercontent.com/Redandan/agora-trading-api/main/scripts/bootstrap_server.sh)"
```

The bootstrap script checks server tools, clones or fetches this repo, confirms
`.env.trading.secrets.example` is present, checks the AgoraMarket exchange-rate dependency
health endpoint, and reports whether nginx already contains `/api/trading/`. It
does not create or print the real secret file.

Pre-deploy check that does not deploy, start, stop, or switch traffic:

```bash
cd /home/ubuntu/agora-trading-api
bash scripts/preflight_server.sh
```

Expected:

- shell syntax passes for `deploy.sh` and `scripts/*.sh`.
- required server tools exist.
- schema baseline compare tooling is syntax-checked but is not run automatically by preflight.
- required secret keys are present and non-empty without printing values.
- `AgoraMarketAPI/internal-client` exists for local SDK install during deploy.
- AgoraMarket exchange-rate dependency health is checked.
- nginx `/api/trading/` path split is reported.

Last observed server state from 2026-06-05 Asia/Taipei:

- AgoraMarketAPI exists at `/home/ubuntu/AgoraMarketAPI`.
- AgoraMarketAPI active port file reports `8082`.
- Local AgoraMarketAPI health is `UP`.
- `git`, `mvn`, `java`, and `curl` are installed.
- `/home/ubuntu/agora-trading-api` has been bootstrapped and can fast-forward from `origin/main`.
- `/home/ubuntu/agora-trading-api/.env.trading.secrets.example` has been created.
- `/home/ubuntu/.env.trading.secrets` has been created without printing secret values.
- independent trading database `agora_trading` has been created.
- nginx `/api/trading/` location has been installed and reloaded.
- trading was deployed from `origin/main` commit `11612b9`, active port `8084`.
- This is an observed deployment snapshot, not proof that the current `origin/main`
  commit is deployed. Re-run deploy and `scripts/verify_server.sh` before treating
  production as current.
- `scripts/verify_server.sh` passed with:
  - local trading health: `http://127.0.0.1:8084/api/trading/actuator/health`
  - AgoraMarket exchange-rate dependency health: `https://agoramarketapi.purrtechllc.com/api/actuator/health`
  - public trading health: `https://agoramarketapi.purrtechllc.com/api/trading/actuator/health`

Deploy after secrets and nginx path are ready:

```bash
cd /home/ubuntu/agora-trading-api
bash scripts/install_nginx_path.sh
bash deploy.sh
```

Defaults:

- ports: `8084` and `8085`
- health: `http://127.0.0.1:<port>/api/trading/actuator/health`
- jar: `target/agora-trading-api-1.0-SNAPSHOT.jar`
- env file: `/home/ubuntu/.env.trading.secrets`

`deploy.sh`, `scripts/install_nginx_path.sh`, and `scripts/verify_server.sh`
all treat `8084/8085` as the blue-green port set. Unknown active port state is
an error, not a fallback target.

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

Optional schema baseline table comparison before generating Flyway baseline:

```bash
RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh
```

Exchange-rate behavior:

- with `AGORA_MARKET_INTERNAL_API_KEY`: trading calls AgoraMarket internal API.
- without key, timeout, or 401: trading falls back to static rates.

AgoraMarket checks in these scripts are dependency checks for the trading
exchange-rate client. They do not deploy, configure, or mutate AgoraMarketAPI.

`scripts/verify_server.sh` checks:

- required local tools: `bash`, `curl`, `git`, `java`, `mvn`.
- shell syntax passes for `deploy.sh` and `scripts/*.sh` via `scripts/preflight_server.sh`.
- required server env keys exist and are non-empty in `/home/ubuntu/.env.trading.secrets` without printing secret values.
- deploy fails fast if `AgoraMarketAPI/internal-client` is missing, then installs it into the server Maven local repo before building trading.
- trading uses an independent MySQL database, currently `agora_trading`.
- `SPRING_JPA_HIBERNATE_DDL_AUTO=update` remains temporary bootstrap-only schema mode and `SPRING_FLYWAY_ENABLED=false` remains required until a Flyway baseline exists.
- schema baseline database comparison is available through `scripts/schema_baseline_compare_server.sh`; run it through `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh` before generating `V1__baseline.sql`.
- active local trading health via `app.port` or default `8084`, limited to the `8084/8085` blue-green port set.
- AgoraMarket exchange-rate dependency health.
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
