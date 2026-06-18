# Split Acceptance Status

Last refreshed: 2026-06-18

This file is the current handoff for deciding whether the extracted
`agora-trading-api` service is accepted enough to run as the Trading owner while
AgoraMarketAPI keeps the shared database and internal exchange-rate API.

## Current Architecture

- Code is split into a standalone Trading service.
- DB is not split. Trading and AgoraMarketAPI use the shared `agora_market`
  database.
- Nginx routes Trading through `/api/trading/` on the shared AgoraMarketAPI
  host for compatibility report/API paths and through `/api/` on the dedicated
  `https://agoratradingapi.purrtechllc.com` host.
- Trading MCP is internal-only through server-local `/api/mcp`. Public
  dedicated-host `/api/mcp` and shared-host `/api/trading/mcp` must be blocked
  by nginx with exact `return 404` blocks and no `proxy_pass`.
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
  - local health works at `/api/actuator/health`
  - local MCP works at `/api/mcp`
  - nginx exposes `/api/trading/`
  - nginx exact MCP blocks return `404` directly with no `proxy_pass`
  - public health works through the dedicated Trading host
    `https://agoratradingapi.purrtechllc.com/api/actuator/health`
  - AgoraMarket dependency health works at
    `https://agoramarketapi.purrtechllc.com/api/actuator/health`

- Local validation is expected to pass with:

  ```powershell
  .\scripts\verify_local.ps1
  .\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180
  ```

- The current local handoff batch is not deployed evidence until it is pushed,
  deployed, and verified on the server. Local verification on 2026-06-18 passed
  through commit `8476f8d` with `.\scripts\verify_local.ps1`: 51 tests, 0 failures,
  305 MCP tools registered during the local-smoke Spring context,
  split-boundary/schema-inventory/script-syntax/post-deploy-guardrail checks
  OK. This includes the reviewed shared-DB baseline guard and
  `schema_baseline_generate_server.sh` header guard so a future baseline dump
  cannot reintroduce pre-review Flyway wording. It also verifies that a custom
  `-EnvFile` is carried through server verification, split acceptance, and the
  server-local MCP acceptance smokes, and that the production
  signal-correctness smoke hard-fails if `verifyStrategyExecution` does not
  provide the expected no-missed-evaluation/no-missed-order marker. Earlier
  local smoke evidence for this handoff batch passed
  `.\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180`, including
  `[mcp-parity] OK http://127.0.0.1:18084/api/mcp toolCount=305 required=35`
  and local `/api/actuator/health` OK. Treat this as local readiness only;
  #1/#2/#3 closure still requires deployed server-local read-only acceptance
  after an explicitly authorized deploy.

- Full read-only live acceptance from Windows/Codex Desktop is expected to pass
  with:

  ```powershell
  .\scripts\verify_split_acceptance_ssh.ps1
  ```

  This wrapper runs Trading server verification with shared-DB schema compare,
  dedicated-host health plus public MCP blocked checks, nginx active-port
  checks, active runtime log smoke, and AgoraMarketAPI's cross-service live MCP
  ownership smoke.

- Current open issue acceptance after an explicitly authorized deploy is
  consolidated by:

  ```powershell
  .\scripts\verify_post_deploy_issue_acceptance_ssh.ps1 -RequireTrailingAcceptance
  ```

  This wrapper runs split acceptance plus the reusable server-local MCP parity
  smoke, the focused #1/#2 guardrail MCP smoke, the read-only
  signal-correctness MCP smoke, and #3 trailing-stop PnL replay smoke through
  server-local read-only calls. If `-EnvFile` is overridden, that same remote
  env file is used for the server verifier, split acceptance, and all
  server-local MCP smokes.
  Use `-RequireTrailingAcceptance` only when the deployed DB sample should prove
  the issue #3 30d PnL target; otherwise the trailing replay is reachability
  evidence only. The guardrail smoke is run in no-review-gaps mode, so
  `Operator action: REVIEW_POLICY_GAPS` fails #1/#2 issue acceptance instead of
  being treated as a closure signal. `-SkipSplitAcceptance` is diagnostic-only;
  output collected with that flag is not #1/#2/#3 closure evidence, and it
  cannot be combined with `-RequireTrailingAcceptance`. A diagnostic-only run
  must end with `DIAGNOSTIC_ONLY OK`, not the normal issue-acceptance OK.

- Current open-issue closure matrix:

  | Issue | Local evidence already covered | Still required before closing |
  | --- | --- | --- |
  | #1 BTC small-TP / wide-disaster-SL guardrail | Risk-sized disaster-SL min-notional behavior, TP/SL asymmetry reporting, same-strategy and same-symbol BTCUSDT LONG exposure blocking, and strict no-review-gaps acceptance scripting are covered by unit/local verification. | A deployed server-local `/api/mcp` guardrail smoke must pass without `REVIEW_POLICY_GAPS`. |
  | #2 event-risk control | R2/R3 new-entry orchestration, MARKET_SIGNAL confluence escalation, config-only operator controls, and read-only MCP status markers are covered locally. | A deployed server-local `/api/mcp` guardrail smoke must prove the read-only event-risk status surface. Phase B OCO/position protection remains future live-promotion scope and is not enabled by this acceptance pass. |
  | #3 trailing stop | The split baseline carries `bt_live_signal` trailing columns; the scheduler is explicit opt-in at 30s, supports dry-run, +0.5 ATR breakeven, +1.0 ATR trailing activation, LONG/SHORT same-bar ambiguity handling, modifyOco retry x3 + alert, and read-only replay/MCP smoke coverage. The split repo uses the reviewed `V1__baseline.sql`; do not generate an extra standalone migration for the current shared-DB gate. ATR is currently a per-position snapshot initialized on first trailing tick; dynamic per-tick ATR recompute is not part of this closure gate unless separately promoted. | A deployed server-local trailing replay must return `sampleStatus=REPLAYED` and `acceptance=PASS` with `-RequireTrailingAcceptance`; no-sample or `NOT_PROVEN` results are reachability only. |

- Shared-DB schema compare is read-only. It proves every trading entity table is
  present in `agora_market`; marketplace/shared extra tables are expected and do
  not block acceptance in `SCHEMA_COMPARE_MODE=shared`.
- Trading MCP DataFreshnessGuard RCA is read-only. After a deploy containing
  the latest diagnostic changes, `diagnoseDataFreshnessGuardBlocks` should show
  current kline snapshot status per symbol/interval/source with
  `READY_NOW`, `STALE_NOW`, `NO_DATA_NOW`, or `QUERY_FAILED_NOW` and a
  `staleNowKeys` summary, so historical stale audit rows can be separated from
  an active collector/source outage.
- Trading MCP trailing-stop PnL replay is read-only. After a deploy containing
  `analyzeTrailingStopPnlReplay`, use
  `.\scripts\smoke_trailing_stop_pnl_replay_ssh.ps1` to call server-local
  `/api/mcp` and prove the boundary marker, the
  `acceptanceTarget: total trailing PnL improvement >= 5%` marker, and sample
  status. The 30d PnL acceptance for issue #3 is only proven when that smoke
  returns `sampleStatus=REPLAYED` and `acceptance=PASS`; local H2 smoke or a
  no-sample production result is only reachability evidence. PnL acceptance
  totals exclude `ambiguousSameBar` rows where trigger/stop ordering cannot be
  proven from OHLC bars.
- Reusable MCP parity now has both local and SSH coverage paths. Local
  `smoke_local_health.ps1` invokes `smoke_mcp_parity.ps1`; deployed issue
  acceptance invokes `smoke_mcp_parity_ssh.ps1` before the guardrail, signal-correctness, and trailing replay smokes.
  `verify_local.ps1` parses and compares all three required-tool lists so local
  smoke, reusable parity smoke, and server-local SSH parity smoke cannot drift
  silently. Both local and SSH parity smokes require the trailing replay
  acceptance-target marker. H2 local smoke executes only H2-compatible
  read-only parity calls; MySQL-backed governance drift/relaxation/tightening
  diagnostics are executed by the server-local SSH parity smoke.
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
  - historical public dedicated-host MCP `tools/list` passed at
    `https://agoratradingapi.purrtechllc.com/api/mcp` with 304 tools,
    representative Trading tools present, and marketplace `updateCartItem`
    absent; this public route is now superseded by the MCP internal-only policy
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
  - historical pre-internal-only production MCP parity smoke passed against
    `https://agoramarketapi.purrtechllc.com/api/trading/mcp` with 304 tools,
    all 21 representative Trading tools present, and the read-only
    `listSchedulerTasks` compatibility alias smoke returning
    `alias_call_ok=listSchedulerTasks`; this public route is now superseded by
    the MCP internal-only policy, and current parity smoke must use
    server-local MCP
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
  schema compare, dedicated-host health, historical dedicated-host MCP
  `tools/list` reporting 304 Trading tools, and nginx shared/dedicated
  upstreams both pointing at active port `8084`. This public MCP route is now
  superseded by the MCP internal-only policy. The full
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
- 2026-06-15 read-only server verification after local diagnostic/smoke
  improvements confirmed the running service was healthy but not current:
  regular `.\scripts\verify_server_ssh.ps1` failed at git currentness because
  the server worktree was `8419bee` while the checked local head was `5c62887`;
  later docs-only handoff commits may advance `origin/main` without changing
  the deploy requirement.
  Re-running with `-SkipGitCurrent` performed no deploy or production mutation
  and passed active port `8084`, local health, local MCP
  `getMcpRegistryVersion`, public dedicated-host health, historical public
  dedicated-host MCP `tools/list` with 304 tools, and nginx shared/dedicated
  upstream checks. This public MCP route is now superseded by the MCP
  internal-only policy.
  A read-only runtime-log smoke against the active run log then passed with
  runtime `ERROR` count 0, known WARN counts
  `flyway_mysql_version=1`, `startup_bean_timing=11`, `cglib_proxy=2`,
  `open_in_view=1`, `thegraph_optional_key=6`,
  `autonomous_digest_severe=1`, `okx_ws_connection_reset=1`, `unknown=0`,
  and no high-risk
  trading/OCO/grid/Earn/fund operation-like lines in the last 3000 lines.
  Deploy is required before the read-only `verifyStrategyExecution` and
  DataFreshnessGuard RCA smoke improvements are production-current.
- 2026-06-15 production deploy advanced Trading runtime to commit `4636a08`
  on active port `8085`. Post-deploy `deploy.sh` verification passed with
  worktree, `origin/main`, and deployed `app.commit` all matching `4636a08`;
  local health, local MCP `getMcpRegistryVersion`, AgoraMarket dependency
  health, public dedicated-host health, historical public dedicated-host MCP
  `tools/list` with 304 tools, nginx shared/dedicated upstreams, and nginx service checks
  all passed. `.\scripts\verify_server_ssh.ps1 -SchemaCompare` passed after
  deploy: 39 source entity tables, 176 shared database tables, 0 missing
  trading tables, 5 marketplace/shared tables, 2 known system tables, and 137
  extra database tables expected in shared mode. Runtime-log smoke passed on
  `/home/ubuntu/agora-trading-api/logs/runs/app-20260615T155705Z-port8085.log`
  with `ERROR` count 0, WARN baseline counts
  `flyway_mysql_version=1`, `startup_bean_timing=16`, `cglib_proxy=2`,
  `open_in_view=1`, `thegraph_optional_key=0`,
  `autonomous_digest_severe=0`, `okx_ws_connection_reset=0`, `unknown=0`,
  and no high-risk trading/OCO/grid/Earn/fund operation-like lines in the last
  3000 lines. Full `.\scripts\verify_split_acceptance_ssh.ps1` passed,
  including cross-service live MCP ownership: AgoraMarketAPI exposed 155
  marketplace/system/internal tools with representative Trading tools absent,
  and `agora-trading-api` exposed 304 Trading tools with representative Trading
  tools present. Additional production MCP smoke confirmed
  `diagnoseDataFreshnessGuardBlocks` returns the read-only boundary and
  acceptance marker, and `verifyStrategyExecution` returns the read-only
  `no external import/backfill` marker without Binance API failure noise.
- After the 2026-06-16 MCP path tightening, the acceptance expectation is that
  public Trading MCP routes are blocked while server-local `/api/mcp` remains
  callable for SSH/operator verification. `/api/trading/mcp` is not a
  standalone MCP endpoint and must stay blocked on the public shared host.
- 2026-06-16 production deploy advanced Trading runtime to commit `5cc6782`
  on active port `8084`. The first public-MCP-block deploy attempt correctly
  rolled back because the dedicated-host `/api/mcp` route still returned HTTP
  200; commit `5cc6782` fixed the nginx rewrite to track nested server-block
  braces before replacing the dedicated Trading MCP location. The successful
  deploy post-verifier then confirmed:
  - local health passed at `http://127.0.0.1:8084/api/trading/actuator/health`
  - server-local MCP `getMcpRegistryVersion` passed at the then-current legacy
    context path `/api/trading/mcp`; current acceptance uses `/api/mcp`
  - public dedicated Trading MCP
    `https://agoratradingapi.purrtechllc.com/api/mcp` returned HTTP 404
  - public shared-host Trading MCP
    `https://agoramarketapi.purrtechllc.com/api/trading/mcp` returned HTTP 404
  - nginx shared/dedicated Trading upstreams pointed at active port `8084`
    while public MCP was blocked
  - only active port `8084` remained listening after deploy drain
- 2026-06-16 `.\scripts\verify_server_ssh.ps1 -SchemaCompare` passed against
  deployed commit `5cc6782`: 39 source entity tables, 176 shared database
  tables, 0 missing trading tables, 5 marketplace/shared tables, 2 known
  system tables, and 137 extra database tables expected in shared mode.
- 2026-06-16 full `.\scripts\verify_split_acceptance_ssh.ps1` passed:
  runtime log smoke on
  `/home/ubuntu/agora-trading-api/logs/runs/app-20260616T013714Z-port8084.log`
  found runtime `ERROR` count 0, WARN lines matching the known baseline, and
  no high-risk trading/OCO/grid/Earn/fund operation-like lines in the last
  3000 lines. Cross-service live MCP ownership smoke reported AgoraMarketAPI
  155 marketplace/system/internal tools with representative Trading tools
  absent, and `agora-trading-api` 304 Trading tools with representative
  Trading tools present.
- 2026-06-16 MCP path deploy advanced Trading runtime to commit `efff0d2` on
  active port `8085`. Deploy first rolled back safely when strict verification
  rejected the still-running old blue-green port before drain; commit `efff0d2`
  split deploy verification into pre-drain and post-drain phases. The
  successful deploy confirmed:
  - local health passed at `http://127.0.0.1:8085/api/actuator/health`
  - server-local MCP `getMcpRegistryVersion` passed at `/api/mcp`
  - public dedicated Trading MCP
    `https://agoratradingapi.purrtechllc.com/api/mcp` returned HTTP 404
  - public shared-host Trading MCP
    `https://agoramarketapi.purrtechllc.com/api/trading/mcp` returned HTTP 404
  - nginx shared/dedicated Trading upstreams pointed at active port `8085`
    while public MCP was blocked
  - non-active blue-green port `8084` had no listener after drain
- 2026-06-16 `.\scripts\verify_server_ssh.ps1 -SchemaCompare` passed against
  deployed commit `efff0d2`: 39 source entity tables, 176 shared database
  tables, 0 missing trading tables, 5 marketplace/shared tables, 2 known
  system tables, and 137 extra database tables expected in shared mode.
- 2026-06-16 full `.\scripts\verify_split_acceptance_ssh.ps1` passed against
  deployed commit `efff0d2`: runtime log smoke on
  `/home/ubuntu/agora-trading-api/logs/runs/app-20260616T025852Z-port8085.log`
  found runtime `ERROR` count 0, WARN lines matching the known baseline, and
  no high-risk trading/OCO/grid/Earn/fund operation-like lines in the last
  3000 lines. Cross-service live MCP ownership smoke reported AgoraMarketAPI
  155 marketplace/system/internal tools with representative Trading tools
  absent, and `agora-trading-api` 304 Trading tools with representative
  Trading tools present through server-local `/api/mcp`.

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
