# Split Acceptance Status

Last refreshed: 2026-06-14

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
  - deployed `app.commit` equals the worktree commit, or differs only by
    docs/tooling files that do not require runtime deploy
  - active `app.pid` listens on `app.port`
  - local health works at `/api/trading/actuator/health`
  - local MCP works at `/api/trading/mcp`
  - nginx exposes `/api/trading/`
  - public health works through
    `https://agoramarketapi.purrtechllc.com/api/trading/actuator/health`
  - AgoraMarket dependency health works at
    `https://agoramarketapi.purrtechllc.com/api/actuator/health`

- Local validation is expected to pass with:

  ```powershell
  .\scripts\verify_local.ps1
  .\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180
  ```

- Shared-DB schema compare is read-only. It proves every trading entity table is
  present in `agora_market`; marketplace/shared extra tables are expected and do
  not block acceptance in `SCHEMA_COMPARE_MODE=shared`.
- Local validation passed on 2026-06-14 after the scheduler-alias deploy with:
  - `.\scripts\verify_local.ps1`
  - `.\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180`
  - local Spring context registered 304 MCP tools, matching the deployed
    scheduler-list alias surface
- Production runtime was deployed on 2026-06-14:
  - deployed `app.commit` is runtime commit `6a656fe`
  - active `app.port` was `8085` and listened with matching per-port
    `app.pid` metadata
  - `AGORA_MARKET_BASE_URL` pointed at the stable AgoraMarketAPI nginx vhost
    `https://agoramarketapi.purrtechllc.com`
  - public health passed through
    `https://agoramarketapi.purrtechllc.com/api/trading/actuator/health`
  - local MCP `getMcpRegistryVersion` passed at `/api/trading/mcp`
  - post-deploy server verification passed with server worktree, `origin/main`,
    and deployed `app.commit` all at `6a656fe`; later docs-only handoff commits
    may place the worktree ahead without runtime drift
  - latest maintenance server verification passed with server worktree and
    `origin/main` at `1585942`; deployed `app.commit` remained `6a656fe` and
    differed from worktree `HEAD` only by docs/tooling files
  - post-ready WARN/ERROR counts were 0 in the active trading run log; the
    startup warning baseline remains documented separately in the deploy
    runbook
  - `SPRING_DATASOURCE_URL` database: `agora_market`
  - `META_CONTROL_ML_SQL_SCHEMA`: `agora_market`
  - latest full schema compare was rerun on 2026-06-14 through
    `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh` in
    shared mode after the docs-only handoff refresh at `1585942`
  - source entity tables: 39
  - missing database tables: 0
  - shared database tables: 176
  - database marketplace tables: 5, expected in shared DB mode
  - known system tables: 2, including AgoraMarketAPI's `flyway_schema_history`
    and Trading's `trading_flyway_schema_history`
  - extra database tables: 137, expected in shared DB mode
  - production MCP parity smoke passed against
    `https://agoramarketapi.purrtechllc.com/api/trading/mcp` with 304 tools,
    all 21 representative Trading tools present, and the read-only
    `listSchedulerTasks` compatibility alias smoke returning
    `alias_call_ok=listSchedulerTasks`
  - hardened schema env values were active:
    `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`,
    `SPRING_FLYWAY_ENABLED=true`, and
    `SPRING_FLYWAY_TABLE=trading_flyway_schema_history`
  - `trading_flyway_schema_history` was created and baselined at version `1`

## Schema Hardening

Trading schema management is deployed in hardened mode with:

- `src/main/resources/db/migration/V1__baseline.sql`
- `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`
- `SPRING_FLYWAY_ENABLED=true`
- `SPRING_FLYWAY_TABLE=trading_flyway_schema_history`
- `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`

The Trading-owned Flyway table avoids mixing with AgoraMarketAPI's existing
`flyway_schema_history` rows in the shared database.

## Required Next Step

The trading service is deployed, verified, and ready for separate Trading-side
development. The schema baseline hardening step is complete. The next
Trading-side work is:

1. Keep AgoraMarketAPI internal exchange-rate APIs available.
2. Add future Trading schema changes as `V2__...` Flyway migrations under
   `src/main/resources/db/migration`.
3. Re-run local verify, local smoke, server verify with schema compare, public
   health, and MCP registry smoke after any deploy-affecting change.

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
