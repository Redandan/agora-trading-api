# Split Acceptance Status

Last refreshed: 2026-06-07

This file is the current handoff for deciding whether the standalone
`agora-trading-api` split is accepted enough to start disabling legacy
AgoraMarketAPI trading entry points.

## Proven

- The standalone trading service is deployed on the production host under
  `/home/ubuntu/agora-trading-api`.
- The server worktree is expected to match `origin/main`; verify with:

  ```bash
  cd /home/ubuntu/agora-trading-api
  git status --short --branch
  git rev-parse --short HEAD
  cat app.commit
  cat app.port
  ```

- `scripts/verify_server.sh` proves:
  - server worktree equals `origin/main`
  - deployed `app.commit` equals the worktree commit
  - active `app.pid` listens on `app.port`
  - local health works at `/api/trading/actuator/health`
  - local MCP works at `/api/trading/mcp`
  - nginx exposes `/api/trading/`
  - public health works through
    `https://agoramarketapi.purrtechllc.com/api/trading/actuator/health`
  - AgoraMarket dependency health works at
    `http://127.0.0.1:8080/api/actuator/health`

- Local validation is expected to pass with:

  ```powershell
  .\scripts\verify_local.ps1
  .\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180
  ```

- The schema cleanup path is guarded:
  - `scripts/schema_extra_tables_cleanup_apply_server.sh` is dry-run by default.
  - it regenerates read-only schema compare outputs when needed.
  - it refuses non-empty extra tables.
  - it writes a database backup before any possible cleanup.
  - it only drops tables when `APPLY_SCHEMA_EXTRA_TABLE_CLEANUP=1` is explicitly
    set for that invocation.

## Not Yet Accepted

Do not mark the split complete while any item in this section remains true.

- `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh` currently fails
  until the standalone `agora_trading` database has no extra tables.
- The known unresolved state is:
  - source entity tables: 39
  - database tables: 56
  - missing database tables: 0
  - extra database tables: 17
  - obvious marketplace-owned database tables: `users`
  - the 17 extra tables have been observed as empty during dry-run cleanup
- Flyway baseline has not been generated.
- Production still uses temporary bootstrap schema mode:
  - `SPRING_JPA_HIBERNATE_DDL_AUTO=update`
  - `SPRING_FLYWAY_ENABLED=false`
- Legacy AgoraMarketAPI trading entry points and schedulers have not been
  disabled.

## Required Next Step

Run a final dry-run, review the printed backup path and row counts, then apply
the empty-table cleanup only when DB schema cleanup is explicitly authorized:

```bash
cd /home/ubuntu/agora-trading-api
bash scripts/schema_extra_tables_cleanup_apply_server.sh
APPLY_SCHEMA_EXTRA_TABLE_CLEANUP=1 bash scripts/schema_extra_tables_cleanup_apply_server.sh
RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh
```

If the compare passes after cleanup, continue with the Flyway baseline:

1. Generate an explicit baseline migration under
   `src/main/resources/db/migration`.
2. Deploy with `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` and
   `SPRING_FLYWAY_ENABLED=true`.
3. Re-run local verify, local smoke, server verify, public health, and MCP
   registry smoke.

## Cutover Boundary

Only after schema compare and Flyway baseline acceptance pass:

1. Review scheduler ownership so order/OCO/grid/fund/Earn-capable jobs run in
   exactly one service.
2. Keep AgoraMarketAPI internal exchange-rate APIs available.
3. Disable only legacy AgoraMarketAPI trading HTTP/MCP/scheduler entry points.
4. Monitor logs for duplicate scheduler execution, SQL errors, MCP auth errors,
   and nginx `/api/trading/` routing failures.

## Do Not Do

- Do not drop tables without a fresh backup and explicit apply flag.
- Do not disable AgoraMarketAPI legacy trading before schema compare passes.
- Do not remove AgoraMarketAPI internal exchange-rate endpoints; trading still
  depends on them.
- Do not treat public health alone as split acceptance.
