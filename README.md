# agora-trading-api

Strategy-driven BTC-USDT spot trading service.

## Current product boundary

- The owner-facing `509` strategy is the daily BTCUSDT score-buy plus
  profit-only auto-exit strategy defined in
  `docs/strategy-driven-minimal-runtime.md`.
- Its temporary database mapping remains strategy `485`; database strategy
  `508` is a different legacy strategy and must not be treated as the owner
  alias.
- Owner 509 is explicitly authorized for bounded LIVE OKX spot execution:
  weights `1/2/5` map to `10/20/50 USDT`, with an `80 USDT` same-bar order cap
  and `250 USDT` open-cost cap.
- Owner 509 is the TradingView-parity LIVE baseline, not a proven-profitable
  strategy. Performance decisions must include both realized and unrealized
  PnL under equal capital.
- Market WebSockets are derived from the versioned runtime catalog, not
  database `enabled` flags: owner 509 requires Binance `BTCUSDT@1d`, and the
  Donchian lane requires OKX `BTCUSDT@1h` only while it is explicitly SHADOW.
- `BTC_DAILY_REVERSAL_ACCUMULATION_V1` (`DRA`) is authorized as a separate
  one-lot `30 USDT` LIVE canary. Its `250 USDT` multi-lot backtest is research
  context and must not be presented as the economics of the LIVE canary.
- Retired MEI rows remain immutable historical evidence and cannot restore into
  DRA state or enter the active runtime.
- OKX Native Spot Grid remains provider-managed and independently running;
  this service exposes read-only status and economic evidence only.
- Mechanical execution safety remains; legacy strategy selection, AI/ML
  promotion, experimental entries, and strategy-imposed exits are outside the
  minimal path.
- Trading MCP uses one fail-closed Bearer API-key boundary for its read-only
  tool whitelist. The retired Guardian and cross-JVM Telegram approval paths
  are not part of this service.

## Build and local run

The automated test tree and non-deployment verification scripts were removed
as part of the strategy-first simplification.

Compile and package:

```powershell
mvn -DskipTests package
```

Run locally against an explicitly configured environment:

```powershell
mvn spring-boot:run
```

Health endpoint:

```text
http://localhost:8084/api/actuator/health
```

## Deployment

The retained deployment entry points are:

- `deploy.sh` — server-side blue/green deployment.
- `scripts/deploy_ssh.ps1` — invoke deployment from Windows.
- `scripts/bootstrap_server.sh` — initialize or refresh the server checkout.
- `scripts/preflight_server.sh` — fail-closed server and environment checks.
- `scripts/install_nginx_path.sh` and
  `scripts/rewrite_nginx_trading_routes.awk` — nginx route installation.
- `scripts/verify_server.sh` and `scripts/verify_server_ssh.ps1` — post-deploy
  server verification.
- `scripts/check_server_runtime_log.sh` — runtime error classification.
- `scripts/schema_baseline_compare_server.sh` and
  `scripts/schema_baseline_entity_table_parser.pl` — read-only schema
  comparison.
- `scripts/validate_env_template.ps1` — deployment environment validation.

From Windows:

```powershell
.\scripts\deploy_ssh.ps1
.\scripts\verify_server_ssh.ps1 -SchemaCompare
```

From the server:

```bash
bash deploy.sh
bash scripts/verify_server.sh
```

See `docs/deploy-runbook.md` for required environment and safety boundaries.

## Safety

- Local compilation does not prove production currentness.
- Do not enable live strategies, place orders, change Grid, move funds, run
  migrations, or mutate production configuration without explicit
  authorization.
- Marketplace access stays behind the AgoraMarket internal client or explicit
  HTTP DTO contracts.
- The shared `agora_market` database may contain marketplace tables; this
  service must not delete them.

## Current documents

- `SERVICE_BOUNDARY.md`
- `docs/strategy-driven-minimal-runtime.md`
- `docs/score-buy-auto-exit-v2.md`
- `docs/btc-dra-runtime-v1.md`
- `docs/current-design-debt-and-next-actions.md`
- `docs/split-acceptance-status.md`
- `docs/deploy-runbook.md`

`docs/split-acceptance-status.md` is the concise current production handoff.
The cleanup chronology remains in Git and `SPLIT_PROGRESS.md`; superseded
AI/ML, TQS/Autopilot, Guardian, live-readiness, MEI, and pre-simplification
proposal documents were removed from the working tree.
