# BTC Donchian OFF Deployment Readiness

Date: 2026-07-13

## Verdict

`READY_FOR_OFF_DEPLOY_AUTHORIZATION`

The separately authorized bounded production data repair is complete. The
fixed 66,009-row golden contract now passes exactly while the effective
Donchian mode remains `OFF`. This authorization changed only `md_kline`: it
inserted the 55,405 missing rows and corrected the one identified close time.
No commit, push, deploy, restart, environment change, order, OCO change,
Telegram send, or external download/backfill was performed.

## Confirmed Production Baseline

Evidence from the transaction-gated
`repair_btc_donchian_golden_data_ssh.ps1`, the independent post-commit
`prepare_btc_donchian_off_deploy_readiness_ssh.ps1`, and the earlier
`verify_server_ssh.ps1 -SchemaCompare` confirms:

- server `HEAD`, `origin/main`, and deployed `app.commit` are all
  `6e369d07c88c5fc641f495fdad7a5ee499cb49b3`;
- active port `8084` is healthy, `8085` is not listening, and the tracked
  server worktree is clean; untracked `logs/` is an expected runtime artifact;
- current production exposes 324 MCP tools and neither Donchian tool, which is
  the expected predeploy state;
- `TRADING_BTC_DONCHIAN_SHADOW_MODE` is absent, so its effective value is the
  application default `OFF`;
- `TRADING_RUNTIME_EVIDENCE_ENABLED=true`, but OFF returns before any evidence
  write;
- JPA is `validate`, Flyway is enabled, and the dedicated history table is
  `trading_flyway_schema_history`;
- all required columns already exist in `md_kline`, `bt_decision_audit`, and
  `bt_runtime_decision_evidence`;
- the required K-line scope index and runtime-evidence decision index exist;
- all 39 Trading entity tables are present in the shared database, so no
  migration or schema cleanup is required;
- public/dedicated health, authenticated MCP, shared-host MCP blocking, nginx
  routing, and the AgoraMarket dependency are healthy.

## Golden Data Repair Acceptance

The required window is OKX `BTCUSDT/1h`, inclusive from
`2019-01-01T00:00:00Z` through `2026-07-13T08:00:00Z`.

| Check | Required | Production |
| --- | ---: | ---: |
| Rows | 66,009 | 66,009 |
| First open | 2019-01-01 00:00 UTC | 2019-01-01 00:00 UTC |
| Last open | 2026-07-13 08:00 UTC | 2026-07-13 08:00 UTC |
| Missing historical rows | 0 | 0 |
| Duplicate rows | 0 | 0 |
| Hourly lattice gaps | 0 | 0 |
| OHLC invariant failures | 0 | 0 |
| Close-time mismatches | 0 | 0 |

The repaired close-time row was:

- open: `2026-05-01T17:00:00Z`;
- stored close: `2026-05-02T02:00:00Z`;
- required close: `2026-05-01T18:00:00Z`.

Before repair, the production-window canonical hash was
`78f1e59dc1a2a80072134bbd501c8f974d6915060b192927bcb1561502f0072f`;
normalizing only that row's close time produced
`96b08185fa83574705e5ddbf1149407dc8a169ad7fb4098b4cf1c009f45558fa`.
An independent local calculation over the immutable OKX CSV for the same
10,604-row overlap produced that exact normalized hash. This proves the stored
overlap OHLC values were canonical and bounded the repair to 55,405 missing
rows and one close-time value.

The repair tool validated the local CSV SHA-256
`74bccfdc621884447e224536cedb7471f8c28bbb612f38e81d8b23e02ff8cfd8`,
the production pre-state, all inserts, and the timestamp update inside one
database transaction. It committed only after the full in-transaction
canonical price-bar hash equaled
`361ab6910872079db4e58c45897828b3399c5d9cb8346afcd1970536d1ee6a6d`.
An independent post-commit connection reproduced that hash and returned
`READY_FOR_OFF_DEPLOY_AUTHORIZATION` with no blockers or warnings.

## Patch Scope

The future OFF deployment commit must include only the Donchian research,
runtime, tests, MCP wiring, parity-list updates, and associated documentation.
The shared tracked files have Donchian-specific hunks and can be staged after a
final diff review.

Include:

- `BtcDonchianShadowProperties`, policy, engine, golden verifier, lane,
  readiness service, MCP tools, and their tests;
- the Donchian listener, repository, MCP callback, application config, local
  smoke, MCP parity, verifier, environment-template, and rollout-doc changes;
- the frozen BTC price-only research policy, analyzer, verifier, downloader,
  tests, and report that establish the candidate provenance;
- `scripts/prepare_btc_donchian_off_deploy_readiness_ssh.ps1`,
  `scripts/repair_btc_donchian_golden_data_ssh.ps1`, and this handoff.

Exclude from this scoped commit unless separately reviewed and authorized:

- all dirty `Strategy508TimeExit*` and `StrategyNetPnlAttribution*` runtime and
  test changes;
- `scripts/smoke_strategy508_time_exit_ssh.ps1` and
  `scripts/test_strategy508_time_exit_smoke.ps1` changes;
- `docs/profit-execution-plan.md` and
  `docs/strategy508-profit-optimization-report-2026-07-13.md` changes.

No file has been staged or committed by this readiness pass.

## Required Authorization Sequence

1. Completed: the separately authorized data repair inserted 55,405 unique
   rows and corrected one close time while Donchian remained `OFF`.
2. Completed: the strict independent predeploy packet was rerun:

   ```powershell
   .\scripts\prepare_btc_donchian_off_deploy_readiness_ssh.ps1 `
     -Phase PREDEPLOY `
     -ExpectedBaseCommit 6e369d07c88c5fc641f495fdad7a5ee499cb49b3 `
     -RequireReady
   ```

3. Accepted: it reported exactly 66,009 rows, zero data-quality counters, hash
   `361ab691...e6a6d`, and `READY_FOR_OFF_DEPLOY_AUTHORIZATION`.
4. The 2026-07-14 operator authorization covers staging the scoped files,
   committing, pushing, and deploying with effective mode still `OFF`. It does
   not authorize a production environment change or SHADOW activation.
5. Run the postdeploy read-only acceptance bundle below.
6. Obtain another explicit authorization before changing the mode to `SHADOW`.
   No live mode exists.

## OFF Postdeploy Acceptance

After a separately authorized deployment, run:

```powershell
.\scripts\verify_server_ssh.ps1 -SchemaCompare
.\scripts\verify_split_acceptance_ssh.ps1
.\scripts\smoke_mcp_parity_ssh.ps1
.\scripts\smoke_signal_correctness_ssh.ps1
.\scripts\smoke_strategy508_time_exit_ssh.ps1
.\scripts\smoke_btc_donchian_shadow_ssh.ps1 `
  -ExpectedMode OFF `
  -RequireGoldenParity
.\scripts\prepare_btc_donchian_off_deploy_readiness_ssh.ps1 `
  -Phase POSTDEPLOY_OFF `
  -RequireReady
```

Acceptance requires both new Donchian MCP tools, exact golden parity, readiness
status `OFF_NOT_COLLECTING`, zero order/OCO/Telegram evidence, healthy existing
strategy 508/OCO behavior, current deployment metadata, and no runtime-log or
split-boundary regression.

## Rollback

The OFF deployment changes no environment value and adds no schema object, so
rollback is code-only:

- previous proven commit:
  `6e369d07c88c5fc641f495fdad7a5ee499cb49b3`;
- retain the existing environment file unchanged;
- use the blue-green deployment runbook to restore the previous artifact or
  redeploy the previous commit;
- rerun server verify, split acceptance, MCP parity, signal correctness,
  strategy 508 smoke, and OCO health;
- do not delete imported market data during code rollback. Any later data
  rollback requires a separate database disposition review.

`deploy.sh` must retain its normal automatic rollback behavior when startup or
postdeploy verification fails. This readiness pass did not execute rollback or
any deployment command.

## Later SHADOW Boundary

Only after OFF deployment acceptance and a new explicit authorization may the
operator set:

```bash
TRADING_BTC_DONCHIAN_SHADOW_MODE=SHADOW
```

`TRADING_RUNTIME_EVIDENCE_ENABLED` is already true in the current production
snapshot. After that separately authorized restart, run
`smoke_btc_donchian_shadow_ssh.ps1 -ExpectedMode SHADOW -RequireGoldenParity`.
Do not use `-RequireForwardReady` until at least 30 days and five independent
entries plus five completed trades have accumulated. SHADOW readiness never
authorizes an order, OCO mutation, Telegram send, or live implementation.
