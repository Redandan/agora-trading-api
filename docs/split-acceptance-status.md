# Split Acceptance Status

Last refreshed: 2026-06-15

This file is the current handoff for deciding whether the extracted
`agora-trading-api` service is accepted enough to run as the Trading owner while
AgoraMarketAPI keeps the shared database and internal exchange-rate API.

## Current Architecture

- Code is split into a standalone Trading service.
- DB is not split. Trading and AgoraMarketAPI use the shared `agora_market`
  database.
- Nginx routes Trading through `/api/trading/` on the shared AgoraMarketAPI
  host and through `/api/` on the dedicated
  `https://agoratradingapi.purrtechllc.com` host.
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
  - public health works through the dedicated Trading host
    `https://agoratradingapi.purrtechllc.com/api/actuator/health`
  - AgoraMarket dependency health works at
    `https://agoramarketapi.purrtechllc.com/api/actuator/health`

- Local validation is expected to pass with:

  ```powershell
  .\scripts\verify_local.ps1
  .\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180
  ```

- Full read-only live acceptance from Windows/Codex Desktop is expected to pass
  with:

  ```powershell
  .\scripts\verify_split_acceptance_ssh.ps1
  ```

  This wrapper runs Trading server verification with shared-DB schema compare,
  dedicated-host health/MCP checks, nginx active-port checks, active runtime
  log smoke, and AgoraMarketAPI's cross-service live MCP ownership smoke.

- Shared-DB schema compare is read-only. It proves every trading entity table is
  present in `agora_market`; marketplace/shared extra tables are expected and do
  not block acceptance in `SCHEMA_COMPARE_MODE=shared`.
- Local validation passed on 2026-06-15 after the dedicated-host blue-green
  port-swap fix with:
  - `.\scripts\verify_local.ps1`
  - local Spring context registered 304 MCP tools, matching the deployed
    scheduler-list alias surface
- Production runtime was deployed on 2026-06-15:
  - deployed `app.commit` is runtime commit `31af005`
  - active `app.port` was `8085` and listened with matching per-port
    `app.pid` metadata
  - `AGORA_MARKET_BASE_URL` pointed at the stable AgoraMarketAPI nginx vhost
    `https://agoramarketapi.purrtechllc.com`
  - public health passed through
    `https://agoratradingapi.purrtechllc.com/api/actuator/health`
  - local MCP `getMcpRegistryVersion` passed at `/api/trading/mcp`
  - public dedicated-host MCP `tools/list` passed at
    `https://agoratradingapi.purrtechllc.com/api/mcp` with 304 tools,
    representative Trading tools present, and marketplace `updateCartItem`
    absent
  - post-deploy server verification passed with server worktree, `origin/main`,
    and deployed `app.commit` all at `31af005`
  - post-ready ERROR count was 0 in the active trading run log; WARN lines were
    the known startup baseline classes documented separately in the deploy
    runbook
  - deploy now updates both shared-host `/api/trading/` and dedicated-host
    `/api/*` nginx upstreams during blue-green port swaps
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
  - cross-service live MCP ownership smoke passed from AgoraMarketAPI's
    `tools/codex/check-live-mcp-split-ownership.ps1`: AgoraMarketAPI
    `/api/mcp` exposed 153 marketplace/system/internal tools with
    representative legacy Trading tools absent, while `agora-trading-api`
    `/api/trading/mcp` exposed 304 tools with representative Trading tools
    present
  - 2026-06-15 nginx host alias smoke passed for the dedicated Trading host:
    `https://agoratradingapi.purrtechllc.com/api/actuator/health` returned
    `UP`, and `POST https://agoratradingapi.purrtechllc.com/api/mcp`
    returned 304 Trading tools including `previewPositionSizing` and
    `getTradingManagerDigest` while excluding marketplace `updateCartItem`.
  - 2026-06-15 dedicated-host blue-green regression was fixed: an earlier
    deploy switched the shared `/api/trading/` route to the new port while the
    dedicated host still pointed at the drained old port, briefly causing
    dedicated-host 502. Commit `1cb9e60` updated deploy/nginx tooling so both
    host routes follow the active port; commit `31af005` made the dedicated
    host MCP smoke parse the registry exactly.
  - hardened schema env values were active:
    `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`,
    `SPRING_FLYWAY_ENABLED=true`, and
    `SPRING_FLYWAY_TABLE=trading_flyway_schema_history`
  - `trading_flyway_schema_history` was created and baselined at version `1`
- 2026-06-15 runtime-log smoke deploy advanced production to commit `7e02307`
  on active port `8084`. Post-deploy verification passed with shared-mode
  schema compare, dedicated-host health, dedicated-host MCP `tools/list`
  reporting 304 Trading tools, and nginx shared/dedicated upstreams both
  pointing at active port `8084`. The full
  `.\scripts\verify_split_acceptance_ssh.ps1` pass then confirmed:
  - active run log:
    `/home/ubuntu/agora-trading-api/logs/runs/app-20260615T094927Z-port8084.log`
  - runtime `ERROR` count: 0
  - WARN lines matched the known startup/runtime baseline; the runtime log smoke
    prints category counts so future 15/16-style changes can be traced to a
    known warning class instead of just a total count
  - no high-risk trading/OCO/grid/Earn/fund operation-like lines in the recent
    log tail
  - AgoraMarketAPI live MCP exposed 155 marketplace/system/internal tools with
    representative Trading tools absent
  - `agora-trading-api` live MCP exposed 304 Trading tools with representative
    Trading tools present

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
   health, MCP registry smoke, and cross-service live MCP ownership smoke after
   any deploy-affecting change.

## Cutover Boundary

Shared-DB schema compare and Trading deployment acceptance have passed. Keep
these boundaries:

1. Keep order/OCO/grid/fund/Earn-capable jobs running in exactly one service.
2. Keep AgoraMarketAPI internal exchange-rate APIs available.
3. Use cross-service live MCP ownership smoke when validating the live boundary,
   not Trading parity smoke alone.
4. Monitor logs for duplicate scheduler execution, SQL errors, MCP auth errors,
   and nginx `/api/trading/` or dedicated-host `/api/` routing failures.

## Do Not Do

- Do not run extra-table cleanup in shared DB mode; marketplace/shared tables
  are expected in `agora_market`.
- Do not remove AgoraMarketAPI internal exchange-rate endpoints; trading still
  depends on them.
- Do not treat public health alone as split acceptance.
