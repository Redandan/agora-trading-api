# Split Acceptance Status

Last refreshed: 2026-06-13

This file is the current handoff for deciding whether the extracted
`agora-trading-api` service is accepted enough to run as the Trading owner while
AgoraMarketAPI keeps the shared database and internal exchange-rate API.

## Current Architecture

- Code is split into a standalone Trading service.
- DB is not split. Trading and AgoraMarketAPI use the shared `agora_market`
  database.
- Nginx routes Trading through `/api/trading/`.
- Trading must not import AgoraMarketAPI marketplace entities or repositories.
- Trading may call AgoraMarketAPI through the internal-client SDK or internal
  HTTP DTOs for explicit internal APIs such as exchange rates.

## Proven

- The trading service has a deployment directory on the production host under
  `/home/ubuntu/agora-trading-api`.
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

- Shared-DB schema compare is read-only. It proves every trading entity table is
  present in `agora_market`; marketplace/shared extra tables are expected and do
  not block acceptance in `SCHEMA_COMPARE_MODE=shared`.
- Local validation passed on 2026-06-13 with:
  - `.\scripts\verify_local.ps1`
  - `.\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180`
- Production was deployed and verified on 2026-06-13:
  - deployed `app.commit`, server worktree `HEAD`, and `origin/main` matched
    during the deploy verification run
  - active `app.port` was listening with matching per-port `app.pid` metadata
  - public health passed through
    `https://agoramarketapi.purrtechllc.com/api/trading/actuator/health`
  - production MCP parity passed at local `/api/trading/mcp` with 21
    representative trading tools present from 303 registered tools
  - `SPRING_DATASOURCE_URL` database: `agora_market`
  - `META_CONTROL_ML_SQL_SCHEMA`: `agora_market`
  - `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh` passed in
    shared mode
  - source entity tables: 39
  - missing database tables: 0
  - shared database tables: 175
  - database marketplace tables: 5, expected in shared DB mode
  - extra database tables: 136, expected in shared DB mode

## Remaining Hardening

These items do not block two-repo development or `/api/trading/` ownership, but
they do block treating Trading schema management as production-hardened.

- Flyway baseline has not been generated.
- Production still uses temporary bootstrap schema mode:
  - `SPRING_JPA_HIBERNATE_DDL_AUTO=update`
  - `SPRING_FLYWAY_ENABLED=false`
- The 2026-06-13 startup log showed Hibernate attempting a bootstrap DDL change
  for `market_indicator_history.value` and MySQL rejecting it with data
  truncation. The service stayed healthy, but this confirms the next hardening
  step should be baseline plus validation mode instead of Hibernate schema
  update.

## Required Next Step

The trading service is deployed, verified, and ready for separate Trading-side
development. The next Trading-side hardening step is:

1. Keep AgoraMarketAPI internal exchange-rate APIs available.
2. Generate an explicit baseline migration under
   `src/main/resources/db/migration` with
   `scripts/schema_baseline_generate_server.sh` after shared-mode compare passes.
3. Review the generated baseline and deploy with
   `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` and
   `SPRING_FLYWAY_ENABLED=true`.
4. Re-run local verify, local smoke, server verify, public health, and MCP
   registry smoke.

## Cutover Boundary

Shared-DB schema compare and Trading deployment acceptance have passed. Keep
these boundaries:

1. Keep order/OCO/grid/fund/Earn-capable jobs running in exactly one service.
2. Keep AgoraMarketAPI internal exchange-rate APIs available.
3. Monitor logs for duplicate scheduler execution, SQL errors, MCP auth errors,
   and nginx `/api/trading/` routing failures.

## Do Not Do

- Do not run extra-table cleanup in shared DB mode; marketplace/shared tables
  are expected in `agora_market`.
- Do not remove AgoraMarketAPI internal exchange-rate endpoints; trading still
  depends on them.
- Do not treat public health alone as split acceptance.
