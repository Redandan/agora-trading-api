# Trading Deployment Runbook

## Scope

This runbook covers only deployment of the standalone Trading service. It does
not authorize strategy activation, orders, OCO/Grid mutations, fund movement,
Earn actions, Telegram sends, database migrations, or production data changes.

The automated test tree and non-deployment scripts were intentionally removed
during the strategy-first simplification. Historical documents may still name
retired scripts; those names are not runnable instructions.

## Required server state

Default application directory:

```bash
/home/ubuntu/agora-trading-api
```

Default secrets file:

```bash
/home/ubuntu/.env.trading.secrets
```

Required deployment values include:

```bash
AGORA_MARKET_BASE_URL=https://agoramarketapi.purrtechllc.com
AGORA_MARKET_INTERNAL_API_KEY=<configured internal key>
AGORA_MARKET_INTERNAL_TIMEOUT_MS=3000
TRADING_MCP_KEY=<configured MCP key>
SPRING_DATASOURCE_URL=jdbc:mysql://<host>:3306/agora_market
SPRING_DATASOURCE_USERNAME=<configured user>
SPRING_DATASOURCE_PASSWORD=<configured password>
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
SPRING_FLYWAY_ENABLED=true
SPRING_FLYWAY_TABLE=trading_flyway_schema_history
SPRING_FLYWAY_BASELINE_ON_MIGRATE=true
SPRING_FLYWAY_BASELINE_VERSION=1
PORT=8084
```

Use `.env.trading.secrets.example` as the complete key inventory. Never commit
the real secrets file.

## Strategy-safe deployment defaults

The daily owner strategy must remain non-live unless separately authorized:

```bash
TRADINGVIEW_LOCAL_ENABLED=false
TRADINGVIEW_LOCAL_STRATEGY_ID=485
TRADINGVIEW_LOCAL_ALLOWED_SYMBOLS=BTCUSDT
TRADINGVIEW_LOCAL_ALLOWED_INTERVALS=1d
TRADINGVIEW_LOCAL_ALLOWED_SOURCES=binance
TRADINGVIEW_LOCAL_EXECUTION_MODE=BTC_BASE_PAPER
```

`TRADINGVIEW_LOCAL_ENABLED=false` is the deploy default. A separately reviewed
PAPER activation may set it to `true`; `BTC_BASE_PAPER` can persist simulated
fills and accounting evidence but cannot send an exchange order. OKX Native
Spot Grid is configured and operated independently; a service deployment must
not create, stop, or replace a Grid. This runtime contains no Grid mutation
adapter or Grid write gate; it only queries provider status/economic evidence.

Market-data startup has one global switch and a provider safety allowlist:

```bash
MARKET_WS_AUTO_SUBSCRIBE_ENABLED=false
MARKET_WS_AUTO_SUBSCRIBE_PROVIDERS=binance,okx
```

When enabled, the runtime catalog—not database `enabled` rows or environment
item lists—selects exact streams. Owner 508 uses Binance `BTCUSDT@1d`;
Donchian uses OKX `BTCUSDT@1h` only in SHADOW. There is no startup warm-up,
database-change resubscription, or dual-provider divergence setting.

## Retained scripts

| File | Purpose |
|---|---|
| `deploy.sh` | Server-side blue/green deployment and health-gated switch |
| `scripts/deploy_ssh.ps1` | Durable Windows-to-server deployment wrapper |
| `scripts/bootstrap_server.sh` | Clone/update the server worktree and inspect prerequisites |
| `scripts/preflight_server.sh` | Validate environment, dependencies, ports, and script syntax |
| `scripts/install_nginx_path.sh` | Install Trading nginx routes |
| `scripts/rewrite_nginx_trading_routes.awk` | Deterministically rewrite Trading nginx locations |
| `scripts/verify_server.sh` | Verify deployed process, health, routes, metadata, and optional schema comparison |
| `scripts/verify_server_ssh.ps1` | Run the server verifier from Windows |
| `scripts/check_server_runtime_log.sh` | Fail on material runtime errors and operation-like logs |
| `scripts/schema_baseline_compare_server.sh` | Read-only source-to-database table comparison |
| `scripts/schema_baseline_entity_table_parser.pl` | Parse entity table ownership for schema comparison |
| `scripts/validate_env_template.ps1` | Validate the checked-in environment template |

No other script is part of the supported deployment workflow.

## Local build

There is no repository test suite. Compile and package only:

```powershell
mvn -DskipTests package
```

Before changing a retained shell script:

```bash
bash -n deploy.sh scripts/*.sh
```

Before changing a retained PowerShell script, parse it without executing it:

```powershell
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path .\scripts\deploy_ssh.ps1),
    [ref]$null,
    [ref]$errors
)
$errors
```

## Bootstrap

On the server:

```bash
bash scripts/bootstrap_server.sh
```

Bootstrap checks the repository, Java, Maven, AgoraMarket health, secrets-file
presence, and nginx route presence. Warnings do not authorize continuing when
the later preflight fails.

## Deploy

From Windows:

```powershell
.\scripts\deploy_ssh.ps1
```

Or from the server checkout:

```bash
bash deploy.sh
```

`deploy.sh` performs a blue/green deployment, waits on the HTTP actuator health
endpoint, updates nginx only after the new instance is healthy, records deploy
metadata, and invokes server verification. Do not bypass a failed preflight or
health gate merely to complete a deployment.

## Verify

From Windows:

```powershell
.\scripts\verify_server_ssh.ps1
```

Include the read-only schema comparison when schema ownership or entity mapping
changed:

```powershell
.\scripts\verify_server_ssh.ps1 -SchemaCompare
```

From the server:

```bash
bash scripts/verify_server.sh
```

Server verification is evidence of deployment and runtime reachability. It is
not evidence that a strategy is profitable and does not authorize live
execution.

## Nginx installation

Only when initially installing or repairing the Trading route:

```bash
sudo bash scripts/install_nginx_path.sh
```

The dedicated Trading MCP route must remain authenticated. The shared-host
legacy Trading MCP route must remain blocked.

## Schema boundary

Normal deploys do not create, drop, or clean tables. The optional compare is
read-only:

```bash
RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh
```

Do not run Flyway baseline regeneration, table cleanup, or a migration merely
because comparison reports shared marketplace tables. Extra marketplace tables
are expected while Trading and AgoraMarket share `agora_market`.

## Failure handling

- If compile fails, do not deploy.
- If preflight fails, correct the environment or dependency; do not bypass it.
- If the new instance fails health, do not switch nginx.
- If server verification fails after a switch, preserve logs and deployment
  metadata before deciding whether to redeploy a known prior revision.
- A running process alone is not proof of correct orders, Grid health, or
  strategy performance.
