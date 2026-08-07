# Trading Deployment Runbook

## Scope

This runbook covers deployment of the standalone Trading service and the
explicitly authorized owner 509 plus DRA LIVE configurations below. It does not authorize
manual/test orders, OCO/Grid mutations, fund movement, Earn actions, database
migrations, or unrelated production data changes.

Use `split-acceptance-status.md` for the current deployed handoff and
`current-design-debt-and-next-actions.md` for current maintenance priorities.
Historical commands in Git history or `SPLIT_PROGRESS.md` are not deployment
instructions.

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

`TRADING_MCP_KEY` supplies the Trading MCP Bearer credential used by both the
DEV and OPS compatibility properties. Trading has no Guardian key,
External-AI session key, or Telegram approval state. Server-local and dedicated
public MCP checks must send this Bearer key; unknown and unannotated tools fail
closed.

## Owner 509 LIVE deployment contract

The owner authorized this bounded LIVE configuration:

```bash
TRADINGVIEW_LOCAL_ENABLED=true
TRADINGVIEW_LOCAL_STRATEGY_ID=485
TRADINGVIEW_LOCAL_ALLOWED_SYMBOLS=BTCUSDT
TRADINGVIEW_LOCAL_ALLOWED_INTERVALS=1d
TRADINGVIEW_LOCAL_ALLOWED_SOURCES=binance
TRADINGVIEW_LOCAL_DEFAULT_NOTIONAL_USDT=10.0
TRADINGVIEW_LOCAL_MAX_NOTIONAL_USDT=80.0
TRADINGVIEW_LOCAL_EXECUTION_MODE=BTC_BASE_LIVE
TRADINGVIEW_LOCAL_BTC_BASE_MAX_EXPOSURE_USDT=250.0
TRADINGVIEW_LOCAL_LIVE_MAX_SIGNAL_AGE_MINUTES=15
TRADING_OKX_ENABLED=true
```

Owner 509 sends at most one aggregated buy for a genuine current Binance daily
bar and never replays historical/catch-up signals. It may aggregate eligible
509 lots into one profit-only sell. Every provider submission has a durable
reservation and OKX client order ID; an ambiguous result is alerted and never
blindly retried. OKX Native Spot Grid remains independently configured and its
BTC is outside owner 509 inventory.

Market-data startup has one global switch and a provider safety allowlist:

```bash
MARKET_WS_AUTO_SUBSCRIBE_ENABLED=true
MARKET_WS_AUTO_SUBSCRIBE_PROVIDERS=binance,okx
```

Donchian remains a research lane:

```bash
TRADING_BTC_DONCHIAN_SHADOW_MODE=OFF
```

DRA defaults to `OFF`. The owner authorized this exact bounded LIVE canary on
2026-07-26:

```bash
TRADING_BTC_DRA_MODE=LIVE
TRADING_BTC_DRA_LIVE_NOTIONAL_USDT=30.00
TRADING_BTC_DRA_MAX_LIVE_EXPOSURE_USDT=30.00
TRADING_BTC_DRA_LIVE_MAX_SIGNAL_AGE_MINUTES=15
```

DRA permits one actual OKX spot lot, uses no leverage, and sells only its own
recorded BTC after the profit-only exit condition. It does not authorize a
second lot, 250 USDT live exposure, test/manual orders, OCO, Grid, fund
movement, or Telegram sends.

When enabled, the runtime catalog—not database `enabled` rows or environment
item lists—selects exact streams. Owner 509 uses Binance `BTCUSDT@1d`;
Donchian and DRA use the same deduplicated OKX `BTCUSDT@1h` requirement only
while their respective switches allow evaluation. There is no startup warm-up,
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

The broad historical test tree remains intentionally removed. The narrow
bootstrap and execution-attempt LIVE-contract suite is retained:

```powershell
mvn test
```

Then compile and package the complete application:

```powershell
mvn -DskipTests package
```

Before the next Java change to order submission, fill/fee reconciliation,
position ownership, client-order idempotency, or strategy state, extend the
narrow LIVE-contract tests listed in
`current-design-debt-and-next-actions.md`. Do not restore the deleted generic
AI/backtest test infrastructure.

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

### Offline microstructure producer distribution

The continuous research source is outside the Trading runtime and is not
installed or activated by the normal Trading deployment. Its inactive Maven
profile builds a narrow direct-Java-21 distribution without changing the
Spring Boot main class:

```powershell
mvn -o "-Dtest=OkxMicrostructureContinuousSourceCliTest,OkxMicrostructureForwardSourceCliTest" test
mvn -o -Pmicrostructure-research-dist -DskipTests package
```

The expected output is one classified jar containing only
`com/agora/research/OkxMicrostructure*.class` and exactly the Jackson
annotations, core, and databind runtime jars under
`target/microstructure-dist/lib`. Do not run the jar or either source main as a
build or deployment test.

`scripts/deploy_research_worker_upgrade_ssh.ps1` is the separate Research
Worker packager. It refuses a dirty worktree, performs the offline profile
build, and obtains committed runtime bytes only from the exact `HEAD` through
the fixed Git allowlist `research_pipeline`, `research_mcp`, `research_source`,
`research`, and `scripts/research-worker`, plus exactly the committed
`docs/autonomous-research-charter.md` file required by the server contract
suite. Git archive runs with `core.autocrlf=false`, preserving committed blob
bytes regardless of Windows checkout conversion. It never recursively packages
the live worktree or broad `docs` tree and excludes `src`, `pom.xml`, and every
unknown root. The `docs` parent must contain only the regular non-symlink
charter. The only generated input is `target/microstructure-dist`, which must
contain exactly the canonical narrow jar and the Jackson annotations, core, and
databind jars with no other file, directory, link, or reparse point.

The packager constructs a private tree, generates `source.sha256` from it,
archives that same tree, extracts the archive into a second private directory,
and requires exact relative-path and SHA-256 equality before any upload. Both
the installer and server verifier independently enforce the same runtime roots,
exact four-file distribution, no-link/no-secret/no-cache boundary, and complete
manifest coverage, including the exact charter and excluding every other docs
path. The installed release may add only
`.release/source.sha256` and `.release/provenance.json`; those metadata files
are not pre-install package inputs.

Immediately after creating the clean reviewed commit, run the same closure
without any network action:

```powershell
.\scripts\deploy_research_worker_upgrade_ssh.ps1 -PackageOnly
```

`PackageOnly` performs the clean-commit gate, offline build, staging, manifest,
archive, extraction, and equality checks, then exits before SSH/SCP. Do not run
it in a dirty worktree; failure there is intentional, and the task that prepares
the commit may use only static parser/closure checks. A successful package-only
result is still not Linux/server/deployment proof.

### Cloud Ops V7 same-chat cutover (separately authorized only)

Repository preparation does not activate `CLOUD_OPS_SCHEDULE_V7`. The Worker
attestation accepts only the exact frozen V7 bytes and SHA-256
`426f4a9d1f252a610a89e30fcd2a7f890b6bc26f2cb9e7fbf003a08839d5f144`;
the preserved V6 bytes remain
`d58468b509ffce9f26af2d631a67c97d97f23c8aee369a1c7a3dafbee7959c85`.
Before packaging a reviewed clean commit, run the focused queue,
server-contract, same-chat contract, and evidence suites offline. Run
`PackageOnly` only as the separate post-commit closure gate described above.

A future authorized cutover must occur after a completed normal heartbeat with
both queues idle and enough time before the next canonical due boundary. Use
this fail-closed zero-overlap order:

1. Pause V6 and prove from canonical and platform readback that it is inactive
   and exactly zero schedules are active.
2. Deploy and verify the V7-only Worker while zero schedules are active. Prove
   its installed release inventory and exact frozen contract hash.
3. Prove canonical `ops_schedule_contract.status=READY`,
   `contract_id=CLOUD_OPS_SCHEDULE_V7`, and the exact V7 SHA-256 above.
4. Bind the same-Coach-chat schedule without activating it; prove its
   destination and that the active schedule count remains zero.
5. Activate exactly one V7 schedule, then re-read both canonical status and
   platform state to prove exactly one active clock and the fixed V7 binding.
6. Wait for the normal Turn N. Render the exact canonical prompt without a
   receipt and without changing `delivery_queued_at` or `delivery_deadline_at`.
7. Wait for normal Turn N+1. Submit a receipt only for the exact prior assistant
   token after fresh canonical pending-id verification, and preserve the honest
   canonical `PASS` or `BREACH` result.

Never accept both attestation hashes, overlap V6 and V7 schedules, add a catch-up
clock, or run a V6 schedule against a V7-only Worker. If rollback is required,
pause V7 and prove it inactive first, restore and verify the V6 Worker and
canonical V6 READY hash, and only then resume exactly one V6 schedule. Atomic
in-place platform retargeting, same-chat binding, one-active-schedule readback,
Turn N rendering, Turn N+1 receipt acceptance, and live learning-latency or
economic value remain `MISSING_PROOF` until separately authorized live
acceptance.

Optional
`MicrostructureForwardStartDay` and `MicrostructureDiagnosticId` parameters
must be supplied together. They prepare a root-owned future binding tied to the
actual installed release manifest; they do not start collection.

The corresponding installer places
`agora-research-microstructure-source.service` as disabled and inactive. The
unit has no `[Install]` section and no timer. It also installs and enables only
the event-driven `agora-research-microstructure-intake.path` and its
network-denied oneshot service. An explicit future binding preparation runs the
fixed `initialize` operation in the privileged installer context, validates the
exact derived state file, and leaves it `agora-research:agora-research` mode
`0600`. Ordinary upgrades validate that same file without overwriting it.

The fixed drop parent is `root:agora-evidence` mode `1770`. The source group can
publish a new reserved day, but the sticky parent prevents rename or deletion
after intake freezes the day directory to `root:agora-research` mode `0550` and
its two files plus matching reservation to mode `0440`. The intake account is
not persistently in `agora-evidence`; only the legacy units that explicitly
declare `SupplementaryGroups=agora-evidence` retain that access. Intake receives
only `CAP_DAC_READ_SEARCH`, `CAP_CHOWN`, and `CAP_FOWNER`, with no content-write
override. The drop mount is metadata-capable for those calls, while DAC denies
intake create/write/unlink and denies the source read/modify/rename/delete after
freeze.

Before any separately authorized source start, server preflight must prove the
active intake path, no microstructure timer, fixed release/binding identity,
same-filesystem staging/drop, at least 2 GiB free, at most 14 exact
day/reservation pairs, clean recovery sidecars, frozen ownership/modes, and the
publisher create-versus-frozen-denial boundary. Do not manually start the source
from this preparation alone. Never reuse the candle `agora-research-source` or
`agora-research-evidence-ingest` paths for this microstructure chain.

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

Normal deploys do not invent, drop, or clean tables. A checked-in forward-only
Flyway migration may run only when that exact migration is explicitly
authorized as part of the release. The optional compare is read-only:

```bash
RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh
```

Do not run Flyway baseline regeneration, table cleanup, or a migration merely
because comparison reports shared marketplace tables. Extra marketplace tables
are expected while Trading and AgoraMarket share `agora_market`.

The explicitly authorized 2026-07-30 release applied
`V4__spot_execution_attempt.sql`. It created
`bt_spot_execution_attempt` with zero initial rows and did not backfill
existing DRA position `263`.

## Failure handling

- If compile fails, do not deploy.
- If preflight fails, correct the environment or dependency; do not bypass it.
- If the new instance fails health, do not switch nginx.
- If server verification fails after a switch, preserve logs and deployment
  metadata before deciding whether to redeploy a known prior revision.
- A running process alone is not proof of correct orders, Grid health, or
  strategy performance.
