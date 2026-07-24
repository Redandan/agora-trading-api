# Split Progress

- 2026-07-24: committed, deployed, and accepted minimal-runtime cleanup Batch
  2B as runtime commit `cee8a45d848d`. The blue/green deployment switched
  Production from `8085` to `8084` and fully drained `8085`. Server worktree,
  `origin/main`, deployed metadata, local/public health, dedicated authenticated
  MCP, nginx routing, and the AgoraMarket dependency passed. Shared-database
  comparison found 39 source entity tables, 209 database tables, and 0 missing
  source tables; no migration or table deletion ran. Runtime log smoke found 0
  errors, 0 unknown warnings, and 0 high-risk operation-like lines. All 10 MCP
  tools passed with 11 resources and the unchanged registry hash. Exactly
  Binance `BTCUSDT@1d` and OKX `BTCUSDT@1h` reached `RUNNING`. Owner 508
  remained disabled PAPER; Donchian remained SHADOW with exact golden parity
  and runtime integrity. Positions `#260/#261/#262`, execution-safety
  `issues=0`, `473.2783880116848 USDT`, and protected `0.00050810202 BTC`
  matched the pre-deploy baseline. OKX native Grid
  `3767345250394603520` remained `running` with 11 provider fills and 2
  completed provider groups; exact-net acceptance remains unproven while the
  bot is active. No strategy activation, order, OCO/Grid mutation, fund
  movement, Telegram send, environment change, migration, or database mutation
  was performed.
- 2026-07-24: implemented local-only minimal-runtime cleanup Batch 2B. Read-only
  Production inspection confirmed that the OKX evidence collector,
  authenticated ingestion, and exact-fill one-shot switches were absent and
  therefore defaulted to `false`. Removed the exact-fill one-shot runner and
  its exclusive provider-read, collection, episode assembly, hashing, append,
  collection-metadata, and immutable-fill closure, plus the now-unused
  `AsyncStartup` marker and exact-fill-only settings. This deletes 16 Java
  files and 1,154 lines, reducing Java files from 390 to 374, compiled source
  units from 389 to 373, and startup runner classes from 1 to 0. The native
  Grid tool retains its independent OKX fill pagination; generic OKX evidence
  and `OkxLiquidationWsService` remain deferred. Three source entity mappings,
  four repository interfaces, and one repository implementation are removed,
  while historical migrations and database tables remain untouched.
  `mvn -DskipTests package`,
  environment-template validation, 10-tool/no-LIVE/508/Donchian/protected-file
  assertions, removed-symbol checks, zero-runner checks, and migration-diff
  checks passed. This candidate is not committed, deployed, or accepted on
  Production and made no strategy, market-stream, order, OCO/Grid, position,
  fund, Telegram, scheduler, environment, migration, or database mutation.
- 2026-07-24: committed, deployed, and accepted minimal-runtime cleanup Batch
  2A as runtime commit `2f4ab79fcb03`. The blue/green deployment switched
  Production from `8084` to `8085` and fully drained `8084`. Independent
  server, public-route, and shared-database schema verification passed with 42
  source entity tables and 0 missing tables. Runtime log smoke found 0 errors,
  0 unknown warnings, and 0 high-risk operation-like lines. All 10 MCP tools
  passed with 11 resources and the unchanged registry hash. Exactly Binance
  `BTCUSDT@1d` and OKX `BTCUSDT@1h` reached `RUNNING`. Owner 508 remained
  disabled PAPER; Donchian remained SHADOW with exact golden parity and no
  order, OCO, or Telegram action. Positions `#260/#261/#262`,
  execution-safety `issues=0`, `473.2783880116848 USDT`, and protected
  `0.00050810202 BTC` matched the pre-deploy baseline. OKX native Grid
  `3767345250394603520` retained the same running state, range, 10 USDT
  investment, 10 grids, 11 provider fills, and 2 completed provider groups.
  No database migration, strategy activation, order, OCO/Grid mutation, fund
  movement, or Telegram send was performed.
- 2026-07-24: implemented local-only minimal-runtime cleanup Batch 2A. Removed
  four default-off startup backfill runners, their exclusive Coinalyze,
  The Graph/Uniswap, Hyperliquid, and aggregate indicator-history backfill
  services, two exclusive configuration records, and the superseded
  `MigrationDriftChecker`. Production inspection confirmed all removed startup
  switches were absent and therefore defaulted to `false`; Coinalyze and The
  Graph keys were also absent. Java files decreased from 401 to 390 and startup
  runner classes from 5 to 1. `OkxLiquidationWsService` and the exact-fill
  one-shot path were explicitly deferred because they overlap other retained
  source components. `mvn -DskipTests package`, deleted-symbol checks,
  10-tool/no-LIVE/protected-file assertions, migration-diff check, and
  `git diff --check` passed. No entity, repository, migration, database,
  Production, strategy, market-stream, order, OCO/Grid, position, fund,
  Telegram, report, or notification state was changed. This candidate was
  subsequently committed, deployed, and accepted in the entry above.
- 2026-07-24: committed, deployed, and accepted the first minimal-runtime
  cleanup batch as runtime commit `2b8bff881cc1`. The blue/green deployment
  switched Production from `8085` to `8084` and fully drained `8085`.
  Independent server verification passed health, authenticated dedicated MCP,
  nginx routing, currentness at the acceptance checkpoint, and the AgoraMarket
  dependency. Shared-database comparison found all 42 source entity tables
  with 0 missing tables; no migration or table deletion was performed. Runtime
  log smoke found 0 errors, 0 unknown warnings, and 0 high-risk operation-like
  lines. All 10 read-only MCP tools passed with 11 resources and the unchanged
  registry hash. Owner 508 remained disabled PAPER, Donchian remained SHADOW
  with exact golden parity, and exactly Binance `BTCUSDT@1d` plus OKX
  `BTCUSDT@1h` reached `RUNNING`. Positions `#260/#261/#262`,
  execution-safety `issues=0`, `473.2783880116848 USDT`, and protected
  `0.00050810202 BTC` matched the pre-deploy baseline. OKX native Grid
  `3767345250394603520` remained `running` with its 10 USDT investment,
  10-grid range, 11 provider fills, and 2 completed provider groups. No order,
  OCO/Grid mutation, fund movement, database mutation, strategy activation, or
  Telegram send was performed. Active Grid continuity is not exact-net or
  long-term profitability proof.
- 2026-07-24: implemented the first minimal-runtime cleanup batch locally.
  Removed 26 Java files belonging to the unregistered/default-off Execution
  Event subsystem and the isolated SystemReminder, SystemSnapshot,
  AttentionRule, and AutoExploration rollout paths. Removed two retired
  Execution Event template settings, their validator expectations, the old
  runtime-log error classifier, and the now-unreferenced Attention audit
  method. Java inventory decreased from 427 to 401, repositories from 51 to
  47, and scheduler-related files from 9 to 8. Historical database table
  definitions remain unchanged; no migration or table deletion was added.
  `mvn -DskipTests package`, retained shell/PowerShell syntax, environment
  template validation, `git diff --check`, and direct 10-tool/catalog/protected
  runtime assertions passed. This implementation was subsequently deployed and
  accepted in the entry above.
- 2026-07-24: refreshed the current documentation boundary after read-only
  Production verification at deployed commit `788ad4a8c60c`. Added a staged
  minimal-runtime cleanup roadmap with an explicit protected keep set,
  source-only database policy, six cleanup batches, per-batch acceptance, and
  stop conditions. Replaced the stale current acceptance handoff that still
  described the July 14 330-tool runtime and deleted verification scripts.
  Corrected the minimal-runtime document so the retained Donchian SHADOW lane
  is no longer described as removed. This docs-only change does not modify
  Production, environment, database, strategy, order, OCO, Grid, position,
  fund, scheduler, Telegram, MCP registration, or runtime behavior.
- 2026-07-24: reduced Trading MCP authentication to the active fail-closed
  Bearer API-key boundary. Removed 853-line legacy Guardian/External-AI logic,
  the 390-line Trading-local session approval store, and the Telegram approval
  prompt path that could not share state with AgoraMarketAPI's callback JVM.
  DEV/OPS/LOCAL_ONLY levels, annotation discovery, category metadata, request
  size protection, authenticated protocol methods, and exact tool authorization
  remain. This source change does not modify keys, routes, Telegram, Production
  environment, database, trading, OCO, Grid, positions, funds, or strategy
  modes.
- 2026-07-24: replaced database-enabled and dual-provider market-data startup
  with exact `StrategyRuntimeCatalog` requirements. Owner 508 PAPER readiness
  owns Binance `BTCUSDT@1d`; Donchian owns OKX `BTCUSDT@1h` only while SHADOW.
  Removed the legacy enabled-strategy validator, database-change resubscription
  listener, dual-provider divergence monitor/alerter, and their dead repository
  queries/settings. This source change does not alter Production environment,
  Grid, BTC, positions, funds, database rows, orders, or strategy modes.
- 2026-07-24: reduced OKX Native Spot Grid to a read-only provider-monitoring
  lane. Removed Grid create/stop and migration/Gate-A MCP tools, their execution
  service, provider write methods, disabled write-gate configuration, and
  obsolete authorization documents. The MCP whitelist is now 10 tools. The
  existing provider bot is not stopped, recreated, or reconfigured; no order,
  position, fund, database, or schema mutation is part of this cleanup.
- 2026-07-23: removed the retired custom-Grid archive, retirement, and
  verification documents/scripts from the local candidate. OKX Native Spot Grid
  remains unchanged. Historical `bt_grid` / `bt_grid_level` schema and the
  read-only interfaces required by current native safety checks remain; no
  database migration, table deletion, production mutation, or deploy occurred.
- 2026-07-23: removed the executable custom Grid runtime from a local candidate:
  MCP mutation tools, manager/service, schedulers, recovery/event detectors,
  custom configuration flags, downstream exposure/report/simulation wiring,
  and legacy operator scripts are gone. OKX Native Spot Grid remains the only
  executable Grid path. Historical `bt_grid` / `bt_grid_level` entities,
  repositories, and schema are retained read-only for native safety checks and
  attribution; no DB migration or table deletion occurred. This is local-only
  work and does not claim Production deployment, Gate A completion, archive
  completion, `PASS_CUSTOM_GRID_FULLY_DELETED`, or `MIGRATION_ACCEPTED`.
- 2026-07-14: completed the explicitly authorized production adoption of
  strategy 508 positions `#260/#261/#262` into intentional BTC Base
  management. The exact cohort quantity was `0.00047090 BTC`; all three rows
  remain open with their original quantities and now report
  `ADOPTED_FROM_OCO`. Exchange OCO algo IDs `3727763466544136192`,
  `3730179375279816704`, and `3730420950782099456` were independently
  confirmed canceled and unfilled. No market sell, position close, Telegram
  send, fund transfer, Grid, or Earn action occurred. The trading-wallet BTC
  cash balance was `0.00058897202`, which covers the managed cohort but is not
  used as proof of row-level ownership. The operator restored the original
  production environment byte-for-byte, restarted the single active runtime,
  and left both adoption gates plus `executionArmed` false. Independent MCP,
  idempotency, OCO-health, open-position, wallet, fill-history, process, and
  runtime-log checks passed; OCO health remained `3 OK / 0 SYNC_ERROR / 0
  anomaly`, now intentionally classified as managed no-OCO holdings. Runtime
  commit `1136379` remains active on port `8085`, port `8084` has no listener,
  and post-action split acceptance passed with 330 Trading MCP tools, zero
  runtime errors, zero unknown warnings, and no high-risk operation lines.
  Sanitized evidence is stored at
  `/home/ubuntu/agora-trading-api/target/btc-base-adoption/adoption-20260714T142520Z.json`;
  the dynamic MCP confirmation was not persisted.
- 2026-07-14: deployed `BTC_BASE_ADOPTION_V1` runtime commit `1136379` with an
  explicitly authorized blue-green rollout from port `8084` to `8085`. The
  guarded saga requires exact recorded ownership and exchange OCO quantity, two
  default-OFF runtime gates, exact aggregate quantity, and a dynamic
  confirmation string. It persists a recoverable `ADOPTION_PENDING` marker,
  cancels and freshly confirms the OCO, then records `ADOPTED_FROM_OCO` while
  keeping the position open and retaining its BTC. It is idempotent,
  fail-closed on fill/query/cancel races, and never submits a sell, closes the
  position, sends Telegram, moves funds, or changes Grid/Earn. Shared BTC_BASE
  state suppresses generic OCO retry/modify, trailing-stop, legacy SELL,
  time-exit market close, missing-OCO noise, and generic DB force-close paths;
  adopted holdings count toward cross-strategy BTC_BASE exposure. Full local
  verification passed 441 Java tests and all PowerShell/split checks. Startup
  smoke passed with 330 MCP tools, 50 required tools / zero missing, and
  explicit default-OFF write-gate assertions. Full post-deploy shared-schema
  split acceptance passed with a clean deployed worktree, zero runtime errors,
  zero unknown warnings, no high-risk operation lines, correct dedicated-host
  routing, and the shared-host MCP block intact. Server-local manager status,
  both exact previews, and the protected dry-run for `#260/#261/#262` proved
  strategy 508 ownership, intervals `4h/4h/1h`, exact per-row
  `tradedQty == ocoQty`, aggregate quantity `0.00047090 BTC`, zero blockers,
  and three eligible live OCOs. The dry-run returned
  `READY_FOR_EXPLICIT_EXECUTION_NOT_AUTHORIZED`; both adoption gates and
  `executionArmed` remained false. OCO health before and after stayed
  `3 OK / 0 SYNC_ERROR / 0 anomaly`. Every safety marker remained false: no
  production env, DB/runtime-evidence write, order, close, OCO change,
  Telegram, scheduler, Grid, fund, Earn, backfill, or exchange mutation was
  performed. At that acceptance checkpoint, live adoption still required
  separate exact authorization and a fully drained predecessor runtime.
- 2026-07-14: deployed OCO all-child state hardening runtime commit `4f11774`
  with an explicitly authorized blue-green rollout from port `8085` to
  `8084`. A shared `OcoOrderStateInspector` now checks the OCO parent and every
  visible child for spot and swap paths; a confirmed child fill takes
  precedence over a stale active parent, while incomplete child lookup fails
  closed. Polling, market-close protection, ScoreBuy/strategy 508 preflight,
  BTC Base previews, position reports, fee attribution, swap reconciliation,
  and backtest fill-price resolution use the same state semantics. Full
  shared-schema split acceptance passed with 329 Trading MCP tools, 50 required
  tools / zero missing, zero runtime errors, zero unknown warnings, and no
  high-risk operation log lines. Post-deploy assertions for `#260/#261/#262`
  confirmed strategy 508 ownership, exact `tradedQty == ocoQty == ownedQty`,
  three live OCOs, `3 OK / 0 SYNC_ERROR / 0 anomaly`, zero preview blockers,
  aggregate quantity `0.00047090 BTC`, and cost `29.99925310 USDT`. Both BTC
  Base previews remained read-only with `RETIRE_CLOSE_REVIEW`; every safety
  marker was false. No production env, DB, order, position, OCO, Telegram,
  scheduler, Grid, fund, Earn, backfill, or exchange mutation was performed.
- 2026-07-14: deployed `BTC_BASE_POSITION_MANAGER_V1` runtime commit
  `c75814f` with an explicitly authorized blue-green rollout from port `8084`
  to `8085`. Full shared-schema split acceptance and post-call runtime smoke
  passed with 329 Trading MCP tools, 50 required tools / zero missing, zero
  runtime errors, zero unknown warnings, and no high-risk operation log lines.
  Deployed previews for `#260/#261/#262` proved exact
  `tradedQty == ocoQty == ownedQty`, strategy 508 ownership, intervals
  `4h/4h/1h`, three live healthy OCOs, zero blockers, aggregate quantity
  `0.00047090 BTC`, and cost `29.99925310 USDT`. Both previews returned
  `RETIRE_CLOSE_REVIEW`; this remained a recommendation only. OCO sync health
  remained at zero errors and the production env SHA-256 was unchanged. All
  safety markers remained false: no adoption persistence, DB/runtime-evidence
  write, order, close, OCO change, Telegram send, fund move, Grid/Earn action,
  scheduler change, backfill, or exchange mutation occurred.
- 2026-07-14: implemented local-only `BTC_BASE_POSITION_MANAGER_V1` as a
  fail-closed read-only/shadow review surface for explicit open BTCUSDT OCO
  position IDs. It requires exact `tradedQty == ocoQty` ownership, confirms the
  live OCO parent and visible child state, excludes wallet/Grid/manual BTC, and
  returns bounded keep/recovery/retirement review dispositions without
  persistence or execution. Production predeploy read-only evidence for
  `#260/#261/#262` confirmed strategy 508 ownership, intervals `4h/4h/1h`,
  three active OCOs with `0 SYNC_ERROR / 0 anomaly`, total displayed quantity
  `0.00047090 BTC`, cost `29.999253104 USDT`, weighted entry `63706.20748`, and
  negative heuristic EV on all three. The simulated disposition was
  `RETIRE_CLOSE_REVIEW`, but adoption remains blocked until the new deployed
  tool proves exact quantity parity. Local acceptance passed 398 Java tests,
  all PowerShell/split checks, and startup smoke with 329 MCP tools / 50
  required / zero missing. At that local-acceptance checkpoint the batch had
  not yet been committed or deployed; it did not
  change production env, DB, orders, positions, OCO, Telegram, scheduler, Grid,
  fund, Earn, or exchange state.
- 2026-07-14: deployed strategy 508 entry-diagnostic hardening commit
  `502ff4d` with an explicitly authorized blue-green rollout from port `8085`
  to `8084`. The production env SHA-256 was unchanged across deployment.
  Pre-drain and strict post-drain server verification, shared-mode schema
  comparison, split acceptance, MCP parity (`326` tools / `47` required),
  signal correctness, strategy 508 hold/time-exit, EntryDedup/exposure,
  first-entry, Donchian SHADOW isolation, OCO health, and final runtime-log
  smokes passed. Strategy 508 remains `SHADOW` with live-order false and
  `orderAllowed=false`; its historical sample remains fail-closed at seven
  finalized events. Generic PnL attribution now reports all four matching
  positions, including three open legacy positions, without claiming exact net
  PnL when fee evidence is incomplete. OCO remained `SYNC_ERROR=0`; runtime
  logs had zero errors, only the known WARN baseline, and no high-risk operation
  lines. No production env, order, OCO, position, strategy flag, Telegram,
  grid, fund, Earn, external backfill, or database mutation was performed.
- 2026-07-14: a read-only runtime audit confirmed
  `BTC_DONCHIAN_20D_10D_V1` is now deployed in production `SHADOW` at
  `cb2c31c` with exact golden parity. Evidence contained two runtime rows and
  one non-bootstrap forward bar, with zero entries and zero completed trades.
  The lane still has no live implementation, order, OCO, or Telegram path.
- 2026-07-14: the operator explicitly authorized
  `BTC_DONCHIAN_20D_10D_V1` OFF-code staging, commit, push, and deployment only.
  Production environment changes and SHADOW activation remain outside this
  authorization and require a later separate decision.
- 2026-07-13: completed the separately authorized BTC Donchian production data
  repair while effective mode remained `OFF`. The transaction-gated importer
  verified immutable local CSV SHA-256, exact production pre-state, inserted
  55,405 missing OKX `BTCUSDT/1h` rows, and corrected one close time. It
  committed only after all 66,009 rows produced canonical price-bar hash
  `361ab6910872079db4e58c45897828b3399c5d9cb8346afcd1970536d1ee6a6d`.
  An independent post-commit predeploy packet reproduced the hash with zero
  duplicates, lattice gaps, close-time errors, OHLC failures, or blockers and
  returned `READY_FOR_OFF_DEPLOY_AUTHORIZATION`. No commit, push, deploy,
  restart, env change, order, OCO, Telegram, external download/backfill,
  strategy, grid, fund, or Earn mutation was performed. That data-repair
  authorization did not include OFF code deployment or SHADOW activation.
- 2026-07-13: implemented the local-only `BTC_DONCHIAN_20D_10D_V1`
  SHADOW evidence runtime. One deterministic engine now owns historical replay,
  closed-bar stepping, restart/catch-up state, and exact golden verification.
  The full 66,009-row official OKX dataset matches every normal/stress
  signal/order/trade row and all six frozen ledger hashes. Runtime mode is
  limited to `OFF|SHADOW` and defaults `OFF`; there is no live implementation,
  scheduler, order/OCO/Telegram dependency, or external-backfill path. This
  work is local only: no deploy, production env/restart, order, OCO, Telegram,
  DB, backfill, strategy, grid, fund, or Earn mutation was performed. Full
  local verification passed 383 Java tests and all PowerShell/split checks;
  startup smoke passed with 326 MCP tools, 47 required, and zero missing.
- 2026-07-13: local-only BTC price research now has a tamper-evident official
  OKX `BTC-USDT/1H` dataset (66,009 confirmed contiguous UTC rows), frozen cost
  policy, isolated five-fold simulation, per-signal/order/trade ledgers, and an
  independent report verifier. `BTC_DONCHIAN_20D_10D_V1` is the only historical
  pass and is routed to SHADOW design review only; no deploy, environment,
  order, OCO, Telegram, backfill, or production DB mutation was performed.
- 2026-07-13: strategy 508 forward evidence now separates
  `RAW_SIGNAL_COUNTERFACTUAL` from configuration-bound `EXECUTABLE_SHADOW`.
  Hard-gate-blocked raw events mature for signal-quality analysis but never
  count toward promotion; legacy unbound clear rows fail closed.

- 2026-07-13: deployed strategy 508 market-feature freshness/provenance commit
  `cf1f4df` with an explicitly authorized blue-green rollout. Production moved
  from port `8084` to `8085`, the old listener drained, and strict server verify
  passed with server worktree/app metadata at `cf1f4df`, local/public health,
  dedicated-host MCP, nginx routing, and shared-host MCP `404` protection all
  healthy. Server-local MCP parity passed with 324 tools / 45 required tools;
  signal correctness, strategy 508 hold-counterfactual, and strategy 508
  time-exit SHADOW smokes also passed. Runtime remains `SHADOW` with
  `TRADING_508_TIME_EXIT_LIVE_ORDER_ENABLED=false`, `orderAllowed=false`, and
  historical status `INSUFFICIENT_EXACT_1M_SAMPLE`; the stricter causal replay
  reduced finalized events from 8 to 7 rather than retaining an untrusted row.
  A separate read-only side-effect audit confirmed the unchanged open positions
  `#260/#261/#262`, OCO health `3 OK / 0 SYNC_ERROR / 0 anomaly`, no runtime
  evidence in the first 60 minutes, and no TG notification in the preceding two
  hours. No live order, OCO mutation, position change, Telegram send, env change,
  strategy flag change, grid/fund/Earn action, backfill, or DB migration was
  performed by post-deploy acceptance. Exact new provenance fields still require
  the next naturally closed 4H event before they can be claimed as production
  runtime evidence.
- 2026-07-13: strategy 508 4H/24H market-feature freshness provenance is
  implemented locally after the first production close audit exposed a real
  timing defect. Audit `#77413` decided at `2026-07-12T12:00Z` but selected
  funding/OI/spread rows captured at `08:01Z` because the strategy keyed hourly
  features by the 4H bar open. The versioned lane now uses the latest clean
  observation at or before bar close, never a future row, with a fixed 90-minute
  maximum age. Funding and OI are always required; DEX flow and CEX/DEX spread
  are required only when their filters are enabled. Missing/stale required
  inputs fail closed to HOLD, while closed-bar volume and SMA200 provenance is
  emitted alongside provider, captured-at, age, and freshness fields. New
  collector rows preserve funding/OI/DEX/spread provider metadata through an
  atomic `INSERT IGNORE`; OI delta collection skips unknown or cross-provider
  transitions instead of subtracting incomparable snapshots. A production read-only reconstruction showed the
  latest causal `11:01Z` funding/OI values still produce the same BUY for
  `#77413`; no production deploy, env change, live enablement, order, OCO,
  Telegram, position, DB, scheduler, grid, fund, Earn, or exchange mutation was
  performed. Local acceptance passed `scripts/verify_local.ps1` with 332 Java
  tests and the independent startup smoke with 324 MCP tools / 45 required tools.
- 2026-07-12: evaluated the final predeclared strategy 485 exit candidate with
  unchanged 42/42 TradingView intents and fixed 10 USDT one-order-per-bar buys.
  The candidate reduces 25% when inventory net return first reaches `-12%` and
  rearms only after a later BUY. The risk report now includes realized losses
  through `maxCapitalLossPct`; the 15% gate conservatively uses the maximum of
  inventory drawdown and capital loss on cumulative gross buys. The candidate
  improved 365-day return from `-26.85%` to `-13.43%` and PnL from `-67.13` to
  `-37.60 USDT`, but remained negative at 180 days (`-11.74%`) and 365 days,
  retained `24.10%` risk drawdown, produced only `2/5` positive walk-forward
  folds, and remained `-13.11%` under doubled-fee stress. It was rejected and
  the report now emits `NONE_NO_PROVEN_EDGE_STOP_TUNING`. This was local
  read-only evidence; no production env, deploy, DB, strategy, live order, OCO,
  grid, fund, Earn, Telegram, scheduler, or exchange mutation was made.
- 2026-07-11: evaluated the next predeclared strategy 485 sizing candidate
  without changing any buy point. `SHADOW_252D_DRAWDOWN_TIERED_PER_BAR` uses
  only the maximum close from the previous 252 closed bars, excludes the
  current bar, and maps drawdown `<20%`, `20%-<40%`, and `>=40%` to one
  `10/20/30 USDT` shadow order per bar. Every horizon and walk-forward fold now
  retains prior-price warmup instead of truncating the 252-bar reference at the
  measurement boundary. The signed 3,250-bar replay retained 42/42 intents with
  zero missing/extra. The candidate was rejected: 180-day return `-14.28%`,
  365-day return `-32.27%`, 365-day maximum drawdown `38.11%`, walk-forward
  `2/5` positive folds, and doubled-fee stress return `-32.40%`. This was local
  read-only evidence; no production env, deploy, DB, strategy, live order, OCO,
  grid, fund, Earn, Telegram, scheduler, or exchange mutation was made.
- 2026-07-11: advanced the read-only strategy 485 profit experiment from the
  rejected same-bar aggregate candidate to one predeclared Pine quantity-tier
  candidate. `SHADOW_PINE_QUANTITY_TIERED_PER_BAR` maps the source's
  `1000/2000/5000` quantities to `1x/2x/5x` of the 10 USDT base slice while
  retaining one shadow order per bar, every original intent, the 250 USDT cap,
  no automatic sell, and no lookahead. The signed 3,250-bar Binance replay
  retained 42/42 buy-point parity with zero missing/extra. The candidate was
  rejected: 180-day return `-11.60%`, 365-day return `-32.86%`, 365-day maximum
  drawdown `38.65%`, walk-forward `2/5` positive folds, and doubled-fee stress
  return `-32.99%`. This is local read-only evidence only; no production env,
  deploy, DB, strategy, live order, OCO, grid, fund, Earn, Telegram, scheduler,
  or exchange mutation was made.
- 2026-07-11: the read-only strategy 485 production replay preflight proved
  that the 39 `BTCUSDT` `1d` rows from 2024-06-01 through 2024-07-09 currently
  labeled `binance` are exact Binance.US candles, not corrupted global Binance
  rows. All five OHLCV fields match Binance.US for 39/39 rows; 731 later rows
  match Binance Vision, and no `binance_us` target rows exist. The safe
  disposition preserves and relabels the 39 legacy rows, then inserts 2,519
  missing global Binance bars in four bounded `replaceExisting=false` chunks.
  This is only
  `READY_FOR_SEPARATE_SOURCE_RELABEL_AND_BACKFILL_AUTHORIZATION_NOT_MUTATION`;
  no SQL, backfill, env change, restart, or live mutation was performed.
- 2026-07-11: created the authorized private TradingView copy
  `AI - Strategy 485 NN Export Audit`, added a data-window-only 10-decimal
  `NN Output Export` plot, applied it to `BINANCE:BTCUSDT` `1D`, and extracted
  365 continuous closed-bar NN rows without creating an alert or order. The
  production Java replay exposed and fixed one real mismatch: parity mode had
  started online training before Pine's 252-bar year-high warmup completed.
  After the fix, the canonical 365-day golden set passed 42/42 intent parity
  with zero missing/extra and maximum per-intent NN error
  `2.946044341811671E-08` (required `<=1E-06`). A stricter all-365-bar check
  retains four non-intent raw NN drift rows, maximum `2.3942303786439467E-05`,
  so the result is explicitly
  `PASS_EXACT_BUY_POINT_PARITY_WITH_RAW_NN_DRIFT`, not full-series zero drift.
  No production import/env/live/order/OCO/grid/fund/Earn mutation was made.
- 2026-07-11: recovered strategy 485's signed-in TradingView Pine source and
  replaced the fixed handcrafted ScoreBuy sigmoid with the source's exact
  eight-input online-learning replay. Pine indicator parameters, learning rate,
  2017-08-17 Binance replay anchor, relative/potential/AI statement order, and
  closed-bar semantics are now explicit. Incomplete anchored history cannot
  reach a non-LocalTradingView execution path; the local lane may retain shadow
  intents but cannot select them for execution. Read-only Chrome evidence found
  203 historical intents (141 relative-low, 62 potential-low, 0 AI-buy). The
  committed 365-day report has 42 intents and a free Binance Vision replay over
  3,250 daily bars produced 42 actual intents with zero missing and zero extra.
  The raw Pine source is not stored because its alert payload contains a secret;
  only its SHA-256 and redacted semantics are recorded. The later authorized
  private-copy export above closes exact per-intent NN verification while
  preserving the separate full-daily-series drift boundary. Neither evidence
  promotes live trading or relaxes dry-run.
- 2026-07-11: tightened TradingView golden-truth parity so matching buy-point
  keys cannot pass without complete per-intent NN evidence. Added
  `scripts/normalize_tradingview_golden_truth.ps1` plus a local test to map
  TradingView export columns, normalize timestamps to UTC, require a declared
  365-day window, preserve same-bar intent multiplicity, and emit source/golden
  SHA-256 evidence. The tool is local-only and explicitly denies production
  import, env change, and live promotion authorization.

## Current Baseline

- `agora-trading-api` is extracted and compiles as a standalone Spring Boot app.
- Current test baseline: `mvn test` should load the full Spring context with `com.agora` component scanning.
- The repo keeps trading/system runtime code needed for the Spring context. Marketplace auth/frontend remnants are treated as forbidden cleanup regressions by `scripts/verify_local.ps1`.
- Current reusable MCP parity contract requires 47 representative tools,
  including BTC_BASE semantics/profit, Binance backfill, golden-truth
  verification, timeframe-aware strategy validation, and BTC Donchian golden
  parity/readiness surfaces.

## Completed

- 2026-07-11 missed-opportunity diagnostics now correlate terminal execution
  evidence by resolved `live_signal_id`, then by the exact
  strategy/symbol/interval/bar key. This fixes false `MISSED_CANDIDATE` rows
  when `AUTOTRADE_OK` intentionally has no interval, and separates
  `ENTRY_SKIP_REVIEW` / `EXECUTION_FAILURE_REVIEW` from genuinely uncorrelated
  missed BUY evaluations. Trading Manager summaries consume the separated
  counts. The reports remain read-only and do not change signals, orders, OCO,
  strategies, grids, funds, Earn, Telegram, or exchange state.
- Trading app entry point uses full `com.agora` component scan.
- JPA repository scan is limited to trading/system repositories.
- Obvious marketplace product/order/cart/delivery/game/webpush/notification code was removed from trading.
- `AgoraMarketExchangeRateServiceImpl` uses the `agora-market-internal-client` SDK when configured.
- `StaticExchangeRateServiceImpl` exists as the local/downstream-failure fallback.
- Trading exposes API-key guarded, read-only internal report endpoints for the AgoraMarketAPI Telegram gateway:
  - `GET /api/trading/internal/reports/current`
  - `GET /api/trading/internal/reports/analysis`
  - `GET /api/trading/internal/reports/weekly`
- 2026-06-30 A0 trailing-stop dry-run env/deploy was applied on the
  production host and remains active after the later `8fcf3c0` deploy:
  - `TRAILING_STOP_ENABLED=true`
  - `TRAILING_STOP_DRY_RUN=true`
  - active port `8085`
  - split acceptance passed
  - `getTrailingStopStatus` reported `global.enabled=true`,
  `global.dryRun=true`, and `open_oco_positions=0`
  - 30d BTCUSDT trailing replay remained `acceptance=PASS` with
    `improvementPct=56.299%`
  - profit blocker advanced to
    `NO_OPEN_OCO_POSITIONS` once the active dry-run observation packet is
    consumed by `prepare_profit_next_execution_blocker_packet.ps1`
  This is dry-run observation only, not live OCO mutation approval.
- 2026-07-01 aggressive profit activation review now has a full evidence-only
  post-env verification packet. `prepare_profit_evidence_only_accelerator_env_deploy_handoff.ps1`
  still emits the exact operator authorization text for
  `TRADING_RUNTIME_EVIDENCE_ENABLED=true` and
  `TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true` without
  authorizing deploy by itself. After a separately authorized env diff and
  deploy/restart,
  `prepare_profit_evidence_only_accelerator_post_env_read_only_bundle_ssh.ps1`
  emits `PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_READ_ONLY_BUNDLE`,
  `profit_evidence_only_post_env_bundle_status`,
  `runtime_shadow_intent_count`, and `runtime_order_sent_evidence`; its ready
  state is
  `READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_REVIEW_NOT_LIVE`. The
  bundle keeps `live_policy_change_allowed=false`, `order_allowed=false`,
  `deploy_allowed=false`, and `grid_mutation_allowed=false`, so it is evidence
  review only and not live relaxation or order approval.
- 2026-07-01 aggressive profit activation review also has a standalone
  high-risk micro live probe handoff packet.
  `prepare_profit_high_risk_micro_live_probe_handoff.ps1` extracts the
  `HIGH_RISK_MICRO_LIVE_PROBE` lane from the aggressive packet and emits
  `PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_HANDOFF_PACKET`,
  `micro_probe_exact_authorization_text`, the max one-order / 10 USDT probe
  cap, required env diff, hard-gate checklist, post-env read-only
  verification, kill-switch env diff, and rollback commands. The packet keeps
  `micro_probe_env_deploy_request_allowed=false`, `deploy_allowed=false`,
  `order_allowed=false`, and `live_policy_change_allowed=false`; it is review
  material only until current BUY/scout, OCO/EV, event-risk, runtime evidence,
  kill-switch, and exact operator authorization evidence are refreshed in the
  same session.
- 2026-07-01 high-risk micro live probe now has a read-only hard-gate
  preflight review packet. `prepare_profit_high_risk_micro_live_probe_preflight_review_packet.ps1`
  aggregates the micro handoff, strategy574/TinyLive preflight, TP/SL/OCO
  preflight, live-review packet, and runtime-evidence RCA into
  `PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_PREFLIGHT_REVIEW_PACKET`. The ready state
  is
  `READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_EXACT_AUTHORIZATION_REVIEW_NOT_MUTATION`
  and exposes `micro_probe_hard_gate_clear`,
  `micro_probe_exact_authorization_review_allowed`, and
  `runtime_order_sent_evidence`. It still keeps
  `micro_probe_env_deploy_request_allowed=false`, `deploy_allowed=false`,
  `order_allowed=false`, and `live_policy_change_allowed=false`; it is exact
  authorization review evidence only, not deployment or order approval.
- 2026-07-01 high-risk micro live probe also has a read-only activation
  authorization bundle. `prepare_profit_high_risk_micro_live_probe_activation_authorization_bundle.ps1`
  consumes the saved handoff and preflight logs and emits
  `PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_BUNDLE` with
  `micro_probe_activation_authorization_review_ready`,
  `micro_probe_activation_authorization_text`, the selected env diff, post-env
  read-only verification commands, kill-switch env diff, and rollback
  commands. The ready state is
  `READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION`.
  It still keeps `micro_probe_activation_execution_allowed=false`,
  `micro_probe_env_deploy_request_allowed=false`, `deploy_allowed=false`,
  `order_allowed=false`, and `live_policy_change_allowed=false`; it is an
  operator prompt packet only, not production env/deploy or order approval.
- 2026-07-01 added a read-only source refresh wrapper for that lane:
  `prepare_profit_high_risk_micro_live_probe_activation_source_refresh.ps1`.
  It saves the current aggressive activation, handoff, preflight, and
  activation bundle logs as
  `profit-aggressive-activation-operator-packet-latest.log`,
  `profit-high-risk-micro-live-probe-handoff-latest.log`,
  `profit-high-risk-micro-live-probe-preflight-review-latest.log`, and
  `profit-high-risk-micro-live-probe-activation-authorization-bundle-latest.log`
  under `target/profit-review`, then emits
  `PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_SOURCE_REFRESH_PACKET` and
  `profit_micro_probe_activation_source_refresh_status`. Optional
  `-RefreshLiveReviewFromSsh` and `-RefreshRuntimeEvidenceFromSsh` modes only
  collect read-only evidence. Replay mode now prefers
  `runtime-evidence-rca-latest.log` before
  `runtime-evidence-rca-post-deploy-current.log`, so a fresh source refresh
  does not mix stale runtime RCA into the micro-probe preflight. The wrapper
  keeps `deploy_allowed=false`,
  `order_allowed=false`, and `live_policy_change_allowed=false`.
- 2026-07-01 added a read-only execution-order preflight for the same lane:
  `prepare_profit_high_risk_micro_live_probe_execution_preflight_packet.ps1`.
  It consumes the source-refresh and activation-authorization bundle logs and
  emits `PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_EXECUTION_PREFLIGHT_PACKET` with
  `micro_probe_execution_preflight_ready`,
  `micro_probe_execution_exact_authorization_text`, the same-session
  env/deploy/post-env/order review sequence, pre-deploy local review commands,
  post-env read-only verification, kill-switch env diff, and rollback commands.
  The ready state is
  `READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_EXECUTION_PREFLIGHT_NOT_MUTATION`.
  It still keeps `micro_probe_env_deploy_execution_allowed=false`,
  `micro_probe_order_execution_allowed=false`, `deploy_allowed=false`,
  `order_allowed=false`, and `live_policy_change_allowed=false`; it is the
  final execution-prep checklist only, not production env/deploy or order
  approval.
- 2026-07-01 live-readiness currentness routing now separates origin
  docs/tooling-only drift from runtime drift. `smoke_live_readiness_bundle_ssh.ps1`
  appends a read-only local origin-delta classifier and emits
  `origin_delta_status`, `origin_runtime_delta_files`, and
  `origin_docs_tooling_delta_files`. `prepare_live_review_packet_ssh.ps1`
  accepts `WORKTREE_NOT_ORIGIN_MAIN` only when the classifier proves
  `DOCS_TOOLING_ONLY_DRIFT`; `RUNTIME_DRIFT`, `NO_LOCAL_EVIDENCE`, bundle
  blockers, and non-ready live-review evidence still block. This avoids a
  false deploy requirement for scripts/docs-only changes without enabling live
  trading, scheduler mutation, orders, OCO/grid/fund/Earn/Telegram/exchange
  mutation, or policy relaxation.
- 2026-07-01 aggressive live/order-capable review now has a local read-only
  scope packet. `prepare_live_order_capable_scope_review_packet.ps1` consumes
  saved live-readiness audit, runtime-log smoke, grid post-env, and trailing
  post-opt-in logs, then emits `LIVE_ORDER_CAPABLE_SCOPE_REVIEW_PACKET`,
  `live_order_capable_scope_review_status`, per-flag coverage, current grid /
  trailing risk items, exact accept/rollback authorization text, risk
  acceptance conditions, kill-switch plan, rollback env diff, and hard
  non-authorization markers including `order_allowed=false`,
  `grid_mutation_allowed=false`, and `exchange_mutation_allowed=false`. This is
  for deciding whether already-true `TRADING_OKX_ENABLED`,
  `TRADING_GRID_ENABLED`, or `TRAILING_STOP_ENABLED` should be accepted in a
  named scope or rolled back; it does not deploy,
  change production env, enable live policy, place orders, mutate OCO/grid,
  enable schedulers, send Telegram, or mutate DB/fund/Earn/exchange state.
- 2026-07-01 split/grid post-env verification now uses the same
  docs/tooling-only currentness rule as live-readiness routing. `verify_server.sh`
  still fails when the server worktree differs from `origin/main` by runtime
  files, but a docs/tooling-only delta logs
  `worktree commit differs from origin/main only by docs/tooling files` and can
  pass server verification without a runtime deploy. `verify_server_ssh.ps1`
  streams the local verifier to the server, and `verify_split_acceptance_ssh.ps1`
  streams the local runtime-log checker, so stale server tooling does not
  create false blockers while the deployed app commit remains runtime-current.
  This is verification tooling only; it does not deploy, restart, change
  production env, enable live/grid/OCO/scheduler behavior, place orders, or
  mutate DB/fund/Earn/exchange state.
- 2026-06-30 A2 background automation safety diff was applied and deployed
  from `origin/main` commit `8fcf3c0` on active port `8085`. The reviewed
  background flags are now all false:
  `TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED`,
  `TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED`,
  `MARKET_WS_AUTO_SUBSCRIBE_ENABLED`, `EVENT_SCAN_NOTIFICATION_ENABLED`,
  `EXECUTION_EVENT_ENABLED`, `TRADING_DAILY_TG_REPORT_ENABLED`,
  `TRADING_AUTONOMOUS_DIGEST_ENABLED`,
  `TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED`, and
  `TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED`. Read-only verification
  returned `backgroundAutomationClear=true`, `background_automation_true=[]`,
  `background_automation_blockers=[]`, split acceptance `OK`, runtime log
  `PASS`, and MCP parity `toolCount=308 required=35`. The full live-readiness
  bundle remains `NOT_READY` because there is no current BUY/add candidate,
  runtime evidence still lacks shadow intent, TinyLive rollout gates are not
  ready, signal-policy review gaps remain, and trailing dry-run still needs a
  real open-OCO observation sample.
- Unused public exchange-rate provider chain leftovers were removed; exchange rates now use AgoraMarket internal SDK or static fallback only.
- Flutter/AppVersion deployment leftovers were removed from trading.
- UserSearchLog/SearchLogAspect leftovers were removed from trading.
- CustomerIssue/support-ticket leftovers were removed from trading.
- Product classification and image audit leftovers were removed from trading.
- UserAddress, postal-area, and delivery-country leftovers were removed from trading.
- Unused OAuth2 service interfaces and OAuth2 DTO leftovers were removed from trading.
- WalletConnect/Web3 login leftovers and the unused OAuth2 client dependency were removed from trading.
- Unused AuthService/AuthCode/2FA and marketplace account-login DTO leftovers were removed from trading.
- WebPush, product-report, product-validator, cart-summary, and marketplace order-event leftovers were removed from trading.
- Unused marketplace delivery/order/wallet enums, notification enums, logistics utilities, and delivery/digital-order properties were removed from trading.
- Empty legacy marketplace MCP tool placeholders were removed from trading.
- Unreferenced betting and marketplace status/type enums were removed from trading.
- Unused PWA log, traffic analytics, slot analytics DTOs, slot symbol enum, slot cache, and stale product/PWA/slot security rules were removed from trading.
- Unreferenced chat, staking, transaction DTOs and unused marketplace/betting enums were removed from trading.
- Unused referrer DTO and marketplace logistics enum translation leftovers were removed from trading.
- Unused Telegram login/OAuth binding service chain was removed from trading while keeping Telegram notifications and MCP auth intact.
- Unused member CRUD service and member admin DTO leftovers were removed from trading.
- Unused marketplace user fields, user repository member queries, and post service/DTO leftovers were removed from trading.
- Unused web JWT filter, CurrentUser resolver, UserDetailsService, and `/auth/**` route leftover were removed while keeping MCP API-key auth intact.
- Trading withdrawal risk state no longer reads the marketplace `users` table; unused User entity/repository, AutoReply service, and WebRTC signaling service leftovers were removed.
- Trading no longer accepts login/member JWTs; MCP protected tools use service-level API-key authorization only.
- Unused KB daily-export and post-deploy audit config residue was removed, including stale default-on file/git export and listener properties.
- Unused one-shot OKX OI backfill service residue was removed; OI history backfill remains only through the guarded market-data MCP external-backfill path.
- `AgoraMarketAPI` now has the first internal exchange-rate endpoint:
  - `GET /api/internal/exchange-rates/usdt`
  - `GET /api/internal/exchange-rates/usdt/{currency}`
  - Header: `X-Internal-Api-Key`
  - Provider property: `internal.api-key=${INTERNAL_API_KEY:}`
- `AgoraMarketAPI/internal-client` now contains an independently buildable thin SDK:
  - `ExchangeRateInternalClient`
  - `HttpExchangeRateInternalClient`
  - `ExchangeRateInfo`
  - `AgoraMarketInternalClientProperties`
- Local split verification now guards the remaining handoff assumptions:
  - `scripts/smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180` starts the service under `local-smoke`, proves `/api/actuator/health`, calls `/api/mcp` with `getMcpRegistryVersion`, and checks logs for disabled external side effects.
  - Local smoke also calls representative MCP tools and verifies disabled guard responses for live sentiment reads, external health probes, and external backfill/import reads.
  - `scripts/verify_local.ps1` runs compile/tests, split boundary scanners, env-template checks, shell syntax checks, schema source inventory, and documentation drift guards.
  - `scripts/schema_baseline_inventory.ps1` writes `target/schema-baseline/entity-tables.txt`, `implicit-entities.txt`, `forbidden-marketplace-tables.txt`, and `unsafe-table-names.txt`; the latest local guard run found no implicit entity tables, no obvious marketplace-owned table mappings, and no unsafe table names.
  - Runtime side effects that could surprise a split deployment now default off in code and/or the tracked env template, including scheduled market-data writes, startup backfills, attribution startup work, Telegram digests/alerts, market-flip detector/analyzer escalation, Polymarket/WAI, market WebSockets, K-line divergence Telegram alerting, OCO/grid/Earn/trailing-stop automation, ScoreBuy execution/notification paths, short-squeeze/taker-buy alerting, ShortAiFilter external AI/MCP shadow checks, ML shadow inference logging, ML materialized startup refresh, ML protection/autoretrain/digest automation, Gemini advisor, Tiny Live auto-execution, guardian live actions, runtime evidence, and discovery AI suggestions.
  - `scripts/smoke_local_health.ps1` explicitly clears high-risk host env values and passes matching boot args so local smoke cannot inherit accidental trading/deletion/AI automation from the developer or CI environment.
  - `scripts/verify_local.ps1` dynamically scans every `ApplicationRunner`/`CommandLineRunner` and requires async, explicit opt-in startup behavior.
  - Remaining `enabled:true` fallbacks are enforced by `scripts/verify_local.ps1` as a four-item protective/internal allowlist: MCP master-approval probe wait, Telegram noise reduction, enabled-strategy kline data validation, and deterministic regime filtering.
  - Remaining `@DefaultValue("true")` properties are enforced by `scripts/verify_local.ps1` as protective, dry-run, internal diagnostic, or subordinate options behind disabled parent switches.
  - Remaining `@Value` `:true` fallbacks are deliberately limited to protective/internal checks and dry-run flags; `scripts/verify_local.ps1` rejects new default-on `@Value` fallbacks until they are classified or changed to explicit opt-in.
  - Remaining `Environment.getProperty` default-`true` fallbacks are deliberately limited to MCP master-approval protection, ScoreBuy/TinyLive dry-run flags, and post-scout add sub-options behind disabled execution; `scripts/verify_local.ps1` rejects new default-on environment property fallbacks until they are classified or changed to explicit opt-in.
  - Remaining direct `System.getenv().getOrDefault(..., "true")` fallbacks are deliberately limited to `STARTUP_BEAN_TIMING_ENABLED`, an internal startup diagnostic logger with no network, DB, order, or notification side effects.
  - The exact public HTTP allowlist is enforced by `scripts/verify_local.ps1`; retained public paths are limited to OpenAPI docs, actuator probes/metrics with filter gates, rate-limit JSON redirect, favicon, authenticated dedicated-host Trading MCP, the API-key internal report gateway, and the TradingView webhook ingress that fails closed unless its feature flag and payload secret are configured. Trading MCP is available through server-local `/api/mcp` and public dedicated-host `/api/mcp` with bearer auth; shared-host `/api/trading/mcp` must be blocked by nginx.
- Deploy/server scripts now reject stale AgoraMarket dependency routing unless `AGORA_MARKET_BASE_URL` points at `https://agoramarketapi.purrtechllc.com`.
- `deploy.sh` now checks AgoraMarket exchange-rate dependency health before starting the blue-green switch.
- Server preflight now requires AgoraMarket exchange-rate dependency health by default, with `REQUIRE_AGORA_MARKET_HEALTH=0` reserved for diagnostic-only checks.
- Server verification uses the same AgoraMarket exchange-rate dependency health rule: required by default, warning-only only when `REQUIRE_AGORA_MARKET_HEALTH=0` is explicitly set for diagnostics.
- Server verification now requires deploy metadata (`app.commit`, `app.pid`, `app.port`) by default, with `REQUIRE_DEPLOY_METADATA=0` reserved for non-deploy diagnostics.
- Server verification now also requires active per-port pid metadata (`app.pid.<app.port>`) by default and rejects values that do not match `app.pid`.
- Server verification now requires nginx service active by default, with `REQUIRE_NGINX_SERVICE=0` reserved for non-nginx diagnostics.
- Deploy, server preflight, and verification now require hardened schema env values (`SPRING_JPA_HIBERNATE_DDL_AUTO=validate`, `SPRING_FLYWAY_ENABLED=true`, `SPRING_FLYWAY_TABLE=trading_flyway_schema_history`) after the Flyway baseline is added.
- Deploy, server preflight, and verification now also require `AGORA_MARKET_INTERNAL_TIMEOUT_MS=3000`, so AgoraMarket internal API failure stays bounded during split deploys.
- Env-template verification now discovers both required server env keys and fixed-value env guards from deploy/server scripts.
- Env-template verification now also pins the AgoraMarket internal API timeout and local-only default CORS, so deploy prep cannot silently widen browser access or slow exchange-rate dependency failure.
- Deploy keeps the previous blue-green instance and nginx backup when `RUN_POST_DEPLOY_VERIFY=0`, so skipped verification does not drain the last proven instance.
- Deploy now passes its actual app/env/port/AgoraMarket/nginx context into post-deploy server verification instead of letting the verifier fall back to default paths.
- Deploy now also preserves an explicit `RUN_SCHEMA_BASELINE_COMPARE=1` request into post-deploy server verification, so the Flyway-baseline DB compare cannot be silently skipped during acceptance.
- Direct schema-baseline DB compare now rejects missing or empty datasource env keys before querying MySQL.
- Direct schema-baseline DB compare now rejects datasource targets outside the expected shared trading database before querying MySQL.
- Server schema-baseline DB compare now classifies obvious marketplace-owned database tables separately and allows them in `SCHEMA_COMPARE_MODE=shared`.
- Server schema-baseline source inventory now rejects unsafe table names before
  baseline generation can pass them to `mysqldump`.
- Server schema-baseline DB compare now classifies known system tables such as `flyway_schema_history` and `trading_flyway_schema_history` separately; in shared mode, extra database tables are reported for visibility and do not block acceptance.
- Server schema-baseline source and database marketplace-table checks now share one shell pattern to avoid future drift.
- Server schema-baseline DB compare now fails fast when required inventory and comparison tools are unavailable.
- Deploy/preflight now fail fast when `seq` or `tail` is unavailable before blue-green readiness loops or failure-log diagnostics need them.
- Deploy/preflight now also fail fast for core process-launch and post-verify tools (`bash`, `date`, `env`, `grep`, `nohup`, `sleep`).
- Deploy now fail fast checks metadata, log-directory, and cleanup tools (`cat`, `kill`, `mkdir`, `rm`) before blue-green work begins.
- Deploy/nginx installation now fail fast checks nginx file swap and validation tools (`cp`, `mv`, `nginx`) before changing nginx config.
- Server verification now also fails fast when env/metadata parsing tools (`grep`, `tail`, `tr`) are unavailable.
- Bootstrap and nginx path installation now fail fast when their repo/nginx inspection and file-update tools are unavailable.
- Local verification now checks every local-smoke external key and boot-argument clear marker, including exchange secrets, external data-provider keys, warm-up disables, and dry-run guards.
- Package boundary verification now rejects residual marketplace package segments for account/member, PWA, support-ticket, referrer, recharge/withdraw, transaction, Web3, and WalletConnect code.
- Short-squeeze alerting and Binance taker-buy collection now default off in code as well as in the tracked env template and local smoke.
- Market-flip detector now defaults off in code and the tracked env template, so flip event writes and related notifications are production opt-in.
- ML shadow inference logging now defaults off in code and the tracked env template, so live-signal HeatWave prediction lookups and `ml_inference_log` writes are production opt-in.
- ML protection auto-kill now defaults off in code and the tracked env template, so killing stuck HeatWave connections requires `META_CONTROL_ML_PROTECTION_AUTO_KILL_SECONDARY_LOAD=true`.
- DB slow-query monitoring is read-only; unused safe-kill query termination code was removed and is blocked by local verification.
- Disabled legacy SQI startup backfill runner residue was removed; SQI and ShortBuild history remain covered by the gated composite indicator backfill path.
- K-line divergence alerting now defaults off in code and the tracked env template; both manual scan and alert paths require `TRADING_KLINE_DIVERGENCE_ENABLED=true`.
- LongAiFilter now defaults off in code and the tracked env template, so LONG entry guard reads to Fear&Greed/OKX public market endpoints require `TRADING_LONG_AI_FILTER_ENABLED=true`.
- ETF pressure calculation no longer fetches Yahoo Finance data unless `META_CONTROL_ETF_PRESSURE_REFRESH_ENABLED=true`; without a refreshed snapshot it returns no-data output instead of issuing an implicit external read.
- ShortAiFilter now defaults off in code and the tracked env template, so short-signal external AI/MCP shadow checks require `TRADING_SHORT_AI_FILTER_ENABLED=true`.
- Ensemble MCP preview no longer reads live Fear&Greed/OKX/whale/Polymarket inputs unless `TRADING_ENSEMBLE_PREVIEW_LIVE_MARKET_READS_ENABLED=true`.
- Market-data MCP sentiment dashboard, Polymarket risk, Fear&Greed history/backfill, and F&G trade-analysis tools no longer read live Fear&Greed/OKX/whale/Polymarket/orderbook inputs unless `TRADING_MARKET_DATA_MCP_LIVE_SENTIMENT_ENABLED=true`.
- Market-data MCP system health no longer actively pings OKX/Fear&Greed/whale/Polymarket/orderbook endpoints unless `TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED=true`.
- Market-data MCP external backfill/import tools no longer read OKX/The Graph/FRED/Hyperliquid/Polymarket/Coinalyze or write indicator/import rows unless `TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=true`.
- EventRiskControl keeps protective new-entry blocking default-on, while state-change Telegram notifications now default off and require `EVENT_RISK_CONTROL_STATUS_NOTIFY_ENABLED=true`.
- Deploy/nginx scripts fail fast when `systemctl` is unavailable before attempting nginx reloads.
- Local and server verification now prove the trading MCP context path through `/api/mcp`; `/api/trading/mcp` is not a standalone MCP endpoint and remains only a public shared-host block target.
- Runtime-log smoke now classifies the narrow `ScoreBuyV2Strategy` HeatWave
  `ML003011` feature-schema mismatch warning as
  `scorebuy_ml_schema_mismatch`. The strategy catches the prediction failure
  and returns `HOLD`, so this warning is not treated as a grid/order/OCO deploy
  failure, but the category count remains visible for ScoreBuy model/schema
  follow-up before any ScoreBuy live rollout.
- Legacy AgoraMarketAPI trading HTTP/MCP/scheduler parity inventory is documented
  in `docs/legacy-trading-parity-inventory.md`; standalone carries the trading
  MCP/scheduler classes through `/api/mcp` while intentionally not
  carrying legacy trading admin HTTP controllers as public HTTP.
- Server-local MCP parity smoke coverage now checks representative standalone
  trading tools through `tools/list` in `scripts/smoke_local_health.ps1` and
  the reusable `scripts/smoke_mcp_parity.ps1`.
- Local smoke now also calls the #1 read-only
  `analyzeSpotAntiWickPolicyCoverage` MCP surface and checks its boundary,
  disaster-SL policy, and summary markers before deployed guardrail acceptance.
- The reusable MCP parity smoke now also invokes representative #1/#2/#3
  read-only acceptance surfaces, not just `tools/list`, while keeping calls
  server-local and non-mutating.
- `scripts/smoke_mcp_parity_ssh.ps1` provides the same representative
  read-only MCP parity surface over SSH for deployed server-local `/api/mcp`,
  and the post-deploy issue acceptance wrapper runs it before the focused
  guardrail, signal-correctness, and trailing replay smokes.
- Trailing-stop parity now includes a read-only `analyzeTrailingStopPnlReplay`
  MCP diagnostic, local-smoke registration/call coverage, and
  `scripts/smoke_trailing_stop_pnl_replay_ssh.ps1` for post-deploy 30d replay
  evidence through server-local `/api/mcp`. The script does not modify
  order/OCO/strategy/grid/fund/Earn/Telegram/DB state; `-RequireAcceptance`
  should be used only when the deployed runtime has enough normalized recent
  non-ambiguous backtest rows to prove `acceptance=PASS`. The issue-closure
  default is a 30d/500 sample; smaller limits are diagnostic only. Same-bar
  trigger/stop rows are reported for diagnostics but excluded from PnL
  acceptance totals.
- `scripts/prepare_trailing_stop_operator_review_packet_ssh.ps1` wraps the
  hard trailing-stop replay smoke with `-RequireAcceptance` and emits
  `trailing_stop_operator_review_packet`,
  `trailing_stop_operator_packet_status`, `sampleStatus`, `acceptanceRows`,
  `acceptanceDeltaPnl`, `improvementPct`, `acceptance=PASS`,
  `acceptanceBlocker=NONE`, and ambiguous same-bar exclusion evidence.
  `READY_FOR_OPERATOR_PACKET_NOT_LIVE` means the exit-side evidence can be
  attached to a separate operator review, but the packet does not deploy,
  restart, reload nginx, change production env, enable live trading, enable the
  trailing scheduler, change strategy opt-in, place orders, modify OCO, close
  positions, mutate DB/grid/fund/Earn/Telegram/exchange state, run external
  backfill/import, or authorize exit policy changes.
- `scripts/prepare_exit_side_profit_review_packet_ssh.ps1` combines the
  trailing-stop operator packet and strategy 485 aged negative-EV operator
  packet into `exit_side_profit_review_packet` and
  `exit_side_profit_review_packet_status`. It can return
  `READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION` only when both exit-side
  read-only packets are ready. The combined packet keeps
  `trailing_stop_acceptance`, `trailing_stop_improvement_pct`,
  `strategy485_operator_packet_status`, and
  `strategy485_negative_ev_position_count` visible in one place, but it does
  not authorize live trailing, scheduler enablement, strategy opt-in changes,
  orders, OCO modification, close-position, DB/grid/fund/Earn/Telegram/
  exchange mutation, production env changes, deploy, restart, nginx reload, or
  external backfill/import.
- `scripts/prepare_exit_side_operator_decision_brief_ssh.ps1` converts the
  exit-side packet into `exit_side_operator_review_recommendations`,
  `exit_side_operator_decision_brief_packet`, and
  `exit_side_operator_decision_brief_status`. It can return
  `READY_FOR_OPERATOR_DECISION_NOT_MUTATION` with
  `PREPARE_SEPARATE_EXIT_SIDE_OPERATOR_REVIEW`, while keeping trailing-stop
  policy review separate from strategy 485 aged negative-EV position review and
  listing separate authorizations required for trailing enablement,
  close-position, OCO modification, deploy, or production env changes. The
  brief emits `exit_side_operator_decision_lanes` / `decisionLanes` to separate
  `trailing-stop-rollout`, `strategy485-risk-reduction`, and
  `entry-filter-datafreshness-policy`; the entry/filter lane is explicitly
  `NOT_DECIDED_BY_EXIT_SIDE_BRIEF` and remains routed through the profit
  operator action brief. It also emits
  `exit_side_operator_decision_checklist` / `decisionChecklist` to keep
  trailing rollout authorization, strategy 485 risk-reduction authorization,
  and entry/DataFreshness out-of-scope policy decisions separated for operator
  review. The decision brief now carries proposal ids matching the profit
  operator action brief, including `trailing-stop-rollout-review` and
  `strategy485-risk-reduction-review`, plus the
  `docs/exit-side-operator-review-plan.md` review contract. It also emits
  top-level `strategy485_position_summaries` and carries
  trailing acceptance sample counts in `evidenceSummary` so the key exit-side
  evidence is visible without digging through nested source packets. It does
  not authorize live trailing, scheduler enablement, strategy opt-in changes,
  orders, OCO modification, close-position, DB/grid/fund/Earn/Telegram/exchange
  mutation, production env changes, deploy, restart, nginx reload, or external
  backfill/import.
- `docs/exit-side-operator-review-plan.md` is the read-only review contract for
  the combined exit-side lane. It records the current trailing-stop and strategy
  485 decision markers, required fresh inputs, separate authorization
  checklists, stop conditions, and the rule that entry/filter and DataFreshness
  policy remain out of scope for this review.
- `diagnoseDataFreshnessGuardBlocks` now distinguishes historical
  DataFreshnessGuard blocks from current source freshness: the read-only MCP
  RCA prints `READY_NOW`, `STALE_NOW`, `NO_DATA_NOW`, or `QUERY_FAILED_NOW`
  per symbol/interval/source and summarizes `staleNowKeys` so operators do not
  mistake recovered historical stale rows for an active collector failure.
- `scripts/smoke_signal_correctness_ssh.ps1` provides a repeatable read-only
  production signal-correctness smoke. It checks strategy execution parity,
  blocked-signal outcome false-kill rates, PASS/BLOCK finalized sample counts,
  DataFreshnessGuard current-source recovery, and the 24h signal-correctness
  dashboard without changing order/OCO/strategy/grid/fund/Earn state. The
  smoke also cross-checks EntryDedup governance with missed-opportunity
  regression so operators can distinguish statistical false-block pressure from
  staged-add live-readiness; relaxation is not considered live-ready when
  staged-add would allow no groups or dedup-too-coarse suspects are absent. It
  now also prints row-level no-buy classifications, blocker-family breakdowns,
  high-return no-buy strategy distribution, and a `getNoBuyReasonTruthTable`
  summary for safer next-action triage. `scripts/test_signal_policy_review_plan.ps1`
  now guards the signal-policy review contract, including read-only tool calls,
  `SIGNAL_POLICY_REVIEW_GAPS` blocker mapping, governance drift documentation,
  missed-opportunity evidence, the no-buy reason truth table, and
  `signal_policy_review_plan` markers (`riskCategory`, `evidenceMarkers`,
  `requiredEvidence`, `notAuthorization`) so signal-policy review contract drift
  fails locally before a server smoke is trusted. It also emits
  `dataFreshnessCurrentStatus` so `NO_CURRENT_SAMPLE` is treated as blocked
  clearance evidence rather than a stale-source finding or permission to relax
  DataFreshnessGuard.
- `scripts/prepare_entry_filter_operator_review_packet_ssh.ps1` wraps the
  signal-correctness smoke into `entry_filter_operator_review_packet` and
  `entry_filter_operator_packet_status`. It carries `signalPolicyClear`,
  `governanceMode`, `missedOpportunityStatus`, no-buy classifications,
  EntryDedup staged-add evidence, and `signal_policy_review_plan`.
  `REVIEW_REQUIRED_NOT_POLICY_CHANGE` means governance drift or
  missed-opportunity rows are reviewable but still not approval to relax
  EntryDedup/DataFreshness/live policy. The packet does not deploy, restart,
  reload nginx, change production env, enable live trading, place orders,
  modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange
  state, run external backfill/import, or authorize strategy/filter changes.
- `scripts/prepare_no_buy_row_review_packet_ssh.ps1` converts the
  signal-correctness `rowActions`, high-return no-buy breakdown, and no-buy
  truth table into `no_buy_row_review_packet`, `rowActionFamilyCounts`, and
  `no_buy_row_review_packet_status`. `REVIEW_REQUIRED_NOT_EXPERIMENT` means
  no-buy rows are reviewable but still blocked by governance,
  missed-opportunity, or signal-policy evidence. `READY_FOR_SHADOW_DESIGN_NOT_LIVE`
  means the rows can support a bounded shadow design only. The packet does not
  deploy, restart, reload nginx, change production env, enable live trading,
  place orders, modify OCO, close positions, mutate
  DB/grid/fund/Earn/Telegram/exchange state, run external backfill/import, or
  authorize strategy/filter changes.
- `scripts/prepare_missed_opportunity_shadow_design_packet_ssh.ps1` wraps the
  no-buy row packet and extracts only `MISSED_OPPORTUNITY_REVIEW` rows into
  `missed_opportunity_shadow_design_packet`. It emits
  `shadow_design_review_allowed`, `tiny_live_order_allowed=false`,
  `live_policy_change_allowed=false`, candidate row evidence, and
  `missed_opportunity_shadow_design_packet_status`.
  `BLOCKED_SIGNAL_POLICY_REVIEW_REQUIRED` means rows are reviewable but blocked
  by governance, missed-opportunity, or signal-policy evidence.
  `READY_FOR_MISSED_OPPORTUNITY_SHADOW_DESIGN_NOT_LIVE` means a separate
  shadow-only design can be drafted; it is not live/tiny-live approval. The
  packet does not deploy, restart, reload nginx, change production env, enable
  live trading, execute tiny-live orders, place orders, modify OCO, close
  positions, mutate DB/grid/fund/Earn/Telegram/exchange state, run external
  backfill/import, or authorize strategy/filter changes.
- `scripts/prepare_governance_relaxation_review_packet_ssh.ps1` wraps
  `smoke_signal_correctness_ssh.ps1` and its
  `findGovernanceRelaxationCandidates` evidence into
  `governance_relaxation_review_packet`. It emits relaxation candidates,
  `shadow_governance_review_allowed`, `tiny_live_order_allowed=false`,
  `live_policy_change_allowed=false`, and
  `governance_relaxation_review_packet_status`.
  `REVIEW_REQUIRED_NOT_POLICY_CHANGE` means candidates are reviewable but still
  blocked by signal-policy, governance-drift, missed-opportunity, or no-buy
  evidence. `READY_FOR_GOVERNANCE_SHADOW_REVIEW_NOT_LIVE` means a separate
  shadow-only governance review can be drafted; it is not live policy approval.
  `NO_EVIDENCE` with `NO_CURRENT_SAMPLE` and zero relaxation candidates routes
  its `nextAction` to the DataFreshness profit blocker brief and no-buy
  attention flow review before any DataFreshness/governance policy review.
  The packet does not deploy, restart, reload nginx, change production env,
  enable live trading, execute tiny-live orders, place orders, modify OCO,
  close positions, mutate DB/grid/fund/Earn/Telegram/exchange state, run
  external backfill/import, or authorize strategy/filter changes.
- `scripts/prepare_governance_relaxation_preflight_review_packet.ps1` reads an
  existing governance relaxation review log plus the latest no-buy attention
  packet when present, then emits
  `governance_relaxation_preflight_review_packet`,
  `governance_relaxation_preflight_status`, and
  `governance_relaxation_preflight_decision`. It accepts
  `REVIEW_REQUIRED_NOT_POLICY_CHANGE` as a blocked review packet and
  `READY_FOR_GOVERNANCE_SHADOW_REVIEW_NOT_LIVE` as shadow-only review evidence.
  Valid source `NO_EVIDENCE` packets normally route to
  `BLOCKED_SOURCE_GOVERNANCE_RELAXATION_EVIDENCE`; when
  `no-buy-attention-flow-review-packet-latest.log` is ready, the preflight emits
  `no_buy_attention_next_action`,
  `no_buy_signal_eval_near_threshold_gap_count`, and closest threshold-gap
  evidence so governance blockers point at the completed no-buy/threshold
  review instead of forcing a blind source refresh. If the parsed source packet
  has zero relaxation candidates, no missed eval/order bug,
  `missedOpportunityStatus=PASS`, and zero suspicious/false-block/high-return
  no-buy counts, the preflight emits
  `NO_GOVERNANCE_RELAXATION_CANDIDATES_NOT_LIVE`; that is neutral no-action
  evidence for governance relaxation only, not review readiness or policy
  approval. The packet keeps
  `live_policy_change_allowed=false`,
  `tiny_live_order_allowed=false`, `entry_dedup_policy_change_allowed=false`,
  `data_freshness_policy_change_allowed=false`, `order_allowed=false`, and
  `telegram_send_allowed=false`. It is a local operator-review preflight only
  and does not rerun SSH, deploy, change production env, relax governance or
  EntryDedup/DataFreshness/live policy, enable staged-add/live execution, place
  orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange
  state.
- `scripts/smoke_strategy574_signal_governance_ssh.ps1` provides a focused
  read-only production RCA for the TinyLive strategy 574 near-BUY path. It
  compares 1d/3d/7d/14d governance drift, extracts strategy 574 rows from
  `getMissedOpportunityRegressionReport` and `getNoBuyReasonTruthTable`, checks
  current DataFreshness, TinyLive trigger, and autonomous readiness markers, and
  prints a bounded `policy_change_recommendation` such as
  `DO_NOT_RELAX_ENTRY_DEDUP_OR_DATAFRESHNESS_LIVE` or
  `KEEP_HARD_GATES_AND_OBSERVE_TINY_LIVE_THRESHOLD_CROSS`. The smoke is
  server-local `/api/mcp` only and does not change production env, DB, order,
  OCO, grid, Earn, fund, Telegram, scheduler, exchange, or external
  backfill/import state. `scripts/test_strategy574_signal_governance_smoke.ps1`
  guards the read-only tool calls, no-order markers, window comparison, strategy
  574 row extraction, and non-authorization wording.
- `scripts/prepare_strategy574_signal_review_gate_ssh.ps1` wraps origin-delta
  plus the strategy 574 signal/governance smoke into a read-only gate. It emits
  `deploy_required_before_strategy574_review`,
  `shadow_observation_review_allowed`, `tiny_live_order_allowed=false`,
  `live_policy_change_allowed=false`, `strategy574_review_missing_requirements`,
  and `strategy574_signal_review_gate_status`. The gate can route continued
  read-only observation only; it never authorizes pre-buying, TinyLive order
  execution, EntryDedup/DataFreshness relaxation, deploy, restart, or live
  policy changes.
- `scripts/prepare_strategy574_tiny_live_governance_operator_packet.ps1` wraps
  refreshed strategy 574 signal gate, TinyLive loss RCA, and optional
  strategy574 near-threshold shadow observation logs into a reusable
  `STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_PACKET`. It emits
  `strategy574_tiny_live_governance_operator_packet`,
  `strategy574_tiny_live_governance_status`,
  `strategy574_tiny_live_risk_posture`,
  `strategy574_near_threshold_shadow_recommendation`,
  `strategy574_near_threshold_false_positive_rate_pct`,
  `strategy574_near_threshold_avg_net_return_pct`,
  `strategy574_near_threshold_threshold_relaxation_allowed=false`, and hard false markers for
  `tiny_live_order_allowed`, `live_policy_change_allowed`,
  `scheduler_enablement_allowed`, `deploy_or_env_change_allowed`,
  `order_allowed`, `telegram_send_allowed`, and
  `position_or_oco_mutation_allowed`. A
  `READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_REVIEW_NOT_LIVE` packet
  means the evidence can be reviewed; it is not live/TinyLive approval.
  When the near-threshold observation returns
  `STRATEGY574_NEAR_THRESHOLD_FALSE_POSITIVE_RISK_HIGH`, the packet classifies
  `strategy574_tiny_live_risk_posture=BLOCKED_NEAR_THRESHOLD_FALSE_POSITIVE_RISK_HIGH`
  so operator routing does not treat threshold relaxation as a ready lane.
- `scripts/prepare_strategy574_tiny_live_governance_preflight_review_packet.ps1`
  wraps the local strategy574/TinyLive governance operator packet into
  `STRATEGY574_TINY_LIVE_GOVERNANCE_PREFLIGHT_REVIEW_PACKET`. It emits
  `strategy574_tiny_live_governance_preflight_review_packet`,
  `strategy574_tiny_live_governance_preflight_status`, and
  `strategy574_tiny_live_preflight_decision`. It keeps
  `strategy574_threshold_relaxation_allowed=false`,
  `tiny_live_order_allowed=false`, `live_policy_change_allowed=false`,
  `scheduler_enablement_allowed=false`, `deploy_or_env_change_allowed=false`,
  `order_allowed=false`, `telegram_send_allowed=false`,
  `entry_dedup_policy_change_allowed=false`, and
  `data_freshness_policy_change_allowed=false`. It is a local review-only
  preflight and does not rerun SSH, deploy, change production env, execute
  TinyLive, enable scheduler mutation, place orders, modify OCO, send Telegram,
  or relax EntryDedup/DataFreshness/live policy.
- `scripts/prepare_profit_operator_next_action_board.ps1` combines the profit
  operator priority decision brief, the strategy574/TinyLive governance packet,
  and, with `-RequireAudit`, the latest live-blocker audit packet into a
  `PROFIT_OPERATOR_NEXT_ACTION_BOARD`. It emits
  `profit_operator_next_action_board_packet`,
  `profit_operator_next_action_board_status`,
  `profit_operator_next_action_audit_counts`,
  `profit_operator_next_action_audit_review_queue`, and
  `strategy574_tiny_live_risk_posture`, ranking trailing-stop dry-run,
  strategy485 risk-reduction shadow, EntryDedup semantics shadow,
  DataFreshness collector/blocker review, TP/SL/OCO feasibility,
  strategy574/TinyLive governance blocker review, and aggregate profit priority
  context in one read-only board. `-PriorityDecisionLogPath` lets the board
  reuse the saved priority decision packet from source refresh instead of
  rebuilding the heavy matrix chain. Reused priority packets are now also
  checked against the wrapped source matrix age with the board's
  `-MaxAgeMinutes`; `source_priority_matrix_fresh_for_board_max_age=false`
  keeps the board `NOT_READY` even when the saved priority log itself is fresh.
  It keeps `tiny_live_order_allowed=false`,
  `live_policy_change_allowed=false`, `scheduler_enablement_allowed=false`,
  `deploy_or_env_change_allowed=false`, `order_allowed=false`, and
  `telegram_send_allowed=false`; it is not live approval.
- `scripts/prepare_profit_operator_authorization_request_packet.ps1` consumes
  the saved next-action board and live-blocker audit logs and emits a
  `PROFIT_OPERATOR_AUTHORIZATION_REQUEST_PACKET`,
  `profit_operator_authorization_request_status`,
  `profit_operator_authorization_request_review_queue`,
  `profit_operator_authorization_request_authorization_sequence`, and
  `profit_operator_authorization_request_next_authorization_required`. It is
  fail-closed on missing/stale logs, invalid packet JSON, non-ready board
  status, live-blocker audit evidence gaps, and any live readiness conclusion
  other than `NOT_READY_FOR_LIVE_ENABLEMENT`. It converts the current read-only
  review queue into exact per-lane operator authorization text while keeping
  `live_policy_change_allowed=false`, `scheduler_enablement_allowed=false`,
  `order_allowed=false`, `position_or_oco_mutation_allowed=false`,
  `deploy_or_env_change_allowed=false`, `telegram_send_allowed=false`, and
  `db_grid_fund_earn_exchange_mutation_allowed=false`; it is not live approval.
- `scripts/prepare_profit_live_blocker_audit_packet.ps1` reads existing local
  profit-review logs and emits `PROFIT_LIVE_BLOCKER_AUDIT_PACKET`,
  `profit_live_blocker_audit_packet`, `profit_live_blocker_audit_status`, and
  `liveReadinessConclusion=NOT_READY_FOR_LIVE_ENABLEMENT`. It audits
  profit-priority, trailing dry-run, strategy485 risk reduction, strategy485
  risk escalation, EntryDedup semantics, DataFreshness replay blocker,
  DataFreshness collector activation, TP/SL/OCO feasibility,
  strategy574/TinyLive governance, and governance relaxation lanes. Missing or
  stale logs are blockers, not passes. If the
  governance relaxation preflight packet is absent, it falls back to the source
  governance relaxation review packet and keeps `NO_EVIDENCE` as blocker
  evidence. When preflight emits
  `NO_GOVERNANCE_RELAXATION_CANDIDATES_NOT_LIVE`, the audit counts it as
  no-action evidence, not a primary blocker or live approval. The audit
  keeps `tiny_live_order_allowed=false`, `live_policy_change_allowed=false`,
  `scheduler_enablement_allowed=false`, `deploy_or_env_change_allowed=false`,
  `order_allowed=false`, and `telegram_send_allowed=false`; it does not rerun
  SSH, call MCP, deploy, change production env, enable live/TinyLive/scheduler,
  place orders, send Telegram, modify OCO, or relax EntryDedup/DataFreshness/
  live policy.
- `scripts/prepare_profit_live_blocker_source_refresh.ps1` orchestrates the
  read-only source refresh for the live blocker audit. `-PlanOnly` prints the
  28-step plan without invoking SSH or child refreshes; normal execution runs
  the existing read-only SSH/MCP/SELECT evidence scripts plus local packet
  assembly for every audit lane, reruns the final audit, and writes the
  refreshed trailing dry-run preflight, activation review, post-opt-in
  readiness, observation status, and
  `profit-next-execution-blocker-packet-latest.log` chain, the audit-backed
  `profit-operator-next-action-board-latest.log`, and
  `profit-operator-authorization-request-latest.log`, then finishes with
  `profit-operator-quick-status-latest.log`, so the exact next operator review
  authorization, trailing preflight, and current next-execution blocker stay in
  the same refreshed evidence set without rerunning SSH for the final quick
  blocker summary. It
  refreshes no-buy attention-flow evidence before governance preflight so governance
  `NO_EVIDENCE` inherits the latest no-buy/threshold-gap routing or becomes
  `NO_GOVERNANCE_RELAXATION_CANDIDATES_NOT_LIVE` when there are no relaxation
  candidates and no false-block/high-return no-buy pressure. It preserves
  governance relaxation `NO_EVIDENCE` or `NOT_READY` as blocker evidence instead
  of failing the source-refresh step early, except for that explicit no-action
  status; the final `-RequireAuditReady`
  audit remains the readiness gate. The wrapper keeps
  deploy, production env, live/TinyLive/scheduler, orders, OCO, close-position,
  Telegram, policy relaxation, and DB/grid/fund/Earn/exchange mutation out of
  scope.
- A later source-refresh tooling pass added
  `-ReuseLatestProfitOperatorMatrix` and `-AllowBlockedStepFailures` for the
  profit live blocker pipeline. When the latest profit operator matrix is still
  fresh, source refresh can reuse it through `-MatrixOutputPath` instead of
  rerunning the heavy SSH matrix chain. Source refresh now auto-reuses that
  fresh matrix by default and reserves `-ForceFreshProfitOperatorMatrix` for an
  explicitly requested fresh matrix rebuild. The wrapper emits
  source-refresh-level `step_heartbeat`, `step_timeout`, and `step_complete`
  markers around each child script so long matrix refreshes are observable.
  With `-ContinueOnStepFailure` plus
  `-AllowBlockedStepFailures`, a successful final audit with
  `profit_live_blocker_audit_status=BLOCKED_NOT_READY_FOR_LIVE_ENABLEMENT`
  returns
  `COMPLETE_REFRESHED_SOURCES_WITH_BLOCKED_LANES_NOT_LIVE_READY` rather than
  treating expected NOT_READY lane packets as a failed refresh. The refreshed
  audit remained live-blocked with 10 lanes, 1 review-ready non-live lane
  (`strategy574-tiny-live-governance`), and 9 blocked lanes; it did not
  authorize live/TinyLive/scheduler, orders, OCO, deploy/env changes,
  Telegram, policy relaxation, or DB/grid/fund/Earn/exchange mutation.
- 2026-06-30 follow-up: the same source refresh now uses the fresh production
  `prepare_entry_dedup_operator_decision_brief_ssh.ps1` for the EntryDedup lane
  instead of the local static
  `prepare_entry_dedup_semantics_operator_decision_packet.ps1`. The audit
  accepts the fresh `entry_dedup_operator_decision_brief_status` packet and
  keeps a legacy fallback for older logs, so live-readiness can no longer be
  advanced by canned local EntryDedup shadow rows when production has no recent
  EntryDedup skip sample.
- 2026-06-30 follow-up: `prepare_profit_operator_review_summary.ps1` now keeps
  the latest action brief in non-`RequireReady` mode and emits its own summary
  packet before enforcing its `-RequireReady` failure. This preserves
  `source_matrix_freshness_status=FRESH` and the blocked-lane details for
  downstream exit-side/trailing packets when the reused matrix is fresh but
  `NO_REVIEW_READY_ITEMS`. The refreshed live blocker audit remains
  `NOT_READY_FOR_LIVE_ENABLEMENT`: the matrix has no review-ready items,
  EntryDedup fresh SSH evidence is `NO_EVIDENCE`, DataFreshness replay rows are
  still missing, and Strategy574/TinyLive governance is review-only with
  `tiny_live_order_allowed=false`.
- 2026-06-30 follow-up: the profit operator review chain now treats nonzero
  child `-RequireReady` exits as evidence blockers only when the expected JSON
  packet is missing. Parseable `NOT_READY` packets are preserved as readiness
  evidence instead of being mislabeled as upstream `completed` failures. In the
  same pass, `prepare_profit_verified_recommendations.ps1` now only promotes
  exit-side proposals when the source exit-side packet is
  `READY_FOR_EXIT_SIDE_EXPERIMENT_REVIEW_NOT_LIVE` and each proposal status is
  ready. `scripts/test_profit_review_chain_blocked_packet_preservation.ps1`
  covers the blocked matrix chain through exit-side, verified recommendations,
  consolidated/priority, trailing dry-run, and strategy 485 risk packets. This
  improves blocker classification only; it does not authorize live trading,
  policy relaxation, scheduler, orders, OCO, deploy/env changes, Telegram, or
  DB/grid/fund/Earn/exchange mutation.
- 2026-06-30 follow-up: `prepare_exit_side_profit_review_packet_ssh.ps1` now
  treats Strategy485 `NO_POSITION_RISK_ACTION` and `WATCH_ONLY` as acceptable
  no-action evidence for the exit-side packet. A no-open-position Strategy485
  state no longer blocks a trailing-stop exit-side review by pretending an aged
  negative-EV packet is missing. Direct read-only
  `prepare_exit_side_operator_decision_brief_ssh.ps1 -RequireDecisionReady`
  returned `READY_FOR_OPERATOR_DECISION_NOT_MUTATION` with trailing acceptance
  `PASS`, `trailing_stop_improvement_pct=56.299`, and zero Strategy485
  negative-EV positions. `prepare_profit_live_blocker_source_refresh.ps1` also
  re-creates each step log parent directory before writing output so read-only
  source refresh evidence is not lost if an upstream step refreshes the review
  directory. These are evidence/readiness fixes only; live/order/OCO/scheduler
  permissions remain false until a separate fresh ready packet and explicit
  authorization.
- 2026-06-30 latest read-only source refresh completed with
  `profit_live_readiness_conclusion=NOT_READY_FOR_LIVE_ENABLEMENT`, 10 lanes,
  8 review-ready non-live lanes, 1 no-action lane, and 1 blocked lane.
  Ready-for-review lanes are `profit-priority`, `trailing-stop-dry-run`,
  `strategy485-risk-reduction`, `entry-dedup-semantics`,
  `data-freshness-replay-blocker`,
  `data-freshness-collector-activation`, `tp-sl-oco-feasibility`, and
  `strategy574-tiny-live-governance`.
  `strategy485-risk-escalation` is now `NO_POSITION_RISK_ACTION` when no
  negative-EV position or close/modify suggestion exists, so it is no longer a
  blocker. The EntryDedup lane now uses the fresh 720h/50-row production packet
  and returned `READY_FOR_ENTRY_DEDUP_OPERATOR_DECISION_NOT_LIVE` with
  `entry_dedup_skip_rows=11`, `positive_24h_rows=10`, `tp_hit_rows=11`,
  `sl_hit_rows=0`, and `avg_net_return_pct=0.8`; it is still shadow-review
  evidence only. The sole blocked lane is `governance-relaxation` (no
  reviewable relaxation candidates). `NO_DATAFRESHNESS_SAMPLE` with 1d/3d rows
  at zero is now treated as reviewable evidence-only collector activation
  routing, not as collector activation or DataFreshness policy approval. Final
  audit keeps
  `order_allowed=false`,
  `live_policy_change_allowed=false`, `scheduler_enablement_allowed=false`,
  `position_or_oco_mutation_allowed=false`, `deploy_or_env_change_allowed=false`,
  and `telegram_send_allowed=false`.
- 2026-07-01 follow-up: `prepare_profit_operator_next_action_board.ps1` now
  consumes the latest live-blocker audit with `-RequireAudit` and can reuse the
  saved `profit-operator-priority-decision-brief-latest.log` through
  `-PriorityDecisionLogPath` instead of rebuilding the matrix chain. The
  current saved board returned
  `READY_FOR_PROFIT_OPERATOR_NEXT_ACTION_REVIEW_NOT_LIVE` with
  `source_priority_mode=REUSED_PRIORITY_DECISION_LOG`,
  `source_audit_log_freshness_status=FRESH`,
  `auditCounts.readyReviewCount=8`, `auditCounts.noActionCount=2`, and
  `auditCounts.blockedCount=0`. The audit-backed decision order is
  trailing-stop dry-run, strategy485 risk-reduction shadow, EntryDedup
  semantics shadow, DataFreshness collector activation, DataFreshness replay
  blocker, TP/SL/OCO feasibility, strategy574/TinyLive governance, and
  aggregate profit-priority context. This is operator-review routing only:
  `live_policy_change_allowed=false`, `scheduler_enablement_allowed=false`,
  `position_or_oco_mutation_allowed=false`, `order_allowed=false`, and
  `telegram_send_allowed=false`.
- A follow-up 2026-06-30 governance preflight routing fix keeps the same
  readiness conclusion while removing a no-buy attention status false blocker:
  `prepare_governance_relaxation_preflight_review_packet.ps1` now treats both
  the legacy `READY_FOR_ATTENTION_NO_BUY_FLOW_REVIEW_NOT_LIVE` status and the
  current `READY_FOR_ATTENTION_FLOW_REVIEW_NOT_LIVE` status as ready
  no-buy-attention routing evidence. This can set `no_buy_attention_ready=true`
  for the current packet. A follow-up preflight classification maps the
  no-candidate/no-false-block case to
  `NO_GOVERNANCE_RELAXATION_CANDIDATES_NOT_LIVE` instead of keeping it as the
  sole governance-relaxation blocker. It does not authorize live policy
  relaxation, orders,
  scheduler enablement, OCO/grid/fund/Earn/Telegram/exchange mutation, deploy,
  or production env changes.
- `scripts/smoke_entry_dedup_blocker_decomposition_ssh.ps1` adds a read-only
  production decomposition for recent `ENTRY_SKIP/EntryDedup` rows. It emits
  `ENTRY_DEDUP_BLOCKER_DECOMPOSITION_PACKET`,
  `entry_dedup_blocker_decomposition_status=READY_FOR_ENTRY_DEDUP_BLOCKER_DECOMPOSITION_REVIEW_NOT_LIVE`,
  `classification_ranking`, `reason_ranking`,
  `possible_coarse_semantics_rows`, `protective_rows`, and
  `order_allowed=false`, separating likely coarse non-auto/open-signal
  semantics from true auto-traded exposure, duplicate/bar, and cap/budget
  protection. It is evidence-only and does not deploy, change production env,
  enable live trading/staged-add/scheduler, place orders, modify OCO, send
  Telegram, mutate DB/grid/fund/Earn/exchange state, or relax
  EntryDedup/DataFreshness/live policy.
- `scripts/smoke_entry_dedup_coarse_semantics_shadow_review_ssh.ps1` extends
  that decomposition into a read-only shadow replay for the reviewable
  `same strategy/symbol/interval LONG exposure already exists` family across
  strategies. It emits
  `ENTRY_DEDUP_COARSE_SEMANTICS_SHADOW_REVIEW_PACKET`,
  `entry_dedup_coarse_semantics_shadow_review_status=READY_FOR_ENTRY_DEDUP_COARSE_SEMANTICS_SHADOW_REVIEW_NOT_LIVE`,
  `coarse_reviewable_forward_rows`, `coarse_positive_24h_rows`,
  `coarse_avg_24h_return_pct`, `classification_summary`,
  `strategy_interval_summary`, and `order_allowed=false`. It computes forward
  returns from OKX `md_kline` only and remains evidence-only: no deploy,
  production env change, live/staged-add/scheduler enablement, order/OCO/grid/
  fund/Earn/Telegram/exchange mutation, or EntryDedup/DataFreshness/live policy
  relaxation is authorized.
- `scripts/prepare_entry_dedup_runtime_proof_gap_packet.ps1` now separates
  EntryDedup proof gaps into review and mutation lanes with
  `REVIEW_AND_MUTATION_SPLIT_V1`. The packet emits `reviewGapRanking`,
  `mutationBlockerRanking`,
  `topReviewEvidenceGap=CANDIDATE_RUNTIME_EV_OCO_SNAPSHOTS_MISSING`, and
  `topMutationBlocker=OCO_ROUTE_NOT_PROVEN_OR_MISSING`, so OCO route proof
  remains a hard blocker for orders, staged-add execution, OCO mutation, and
  live policy relaxation without stopping read-only shadow evidence review.
  It keeps all policy, order, scheduler, OCO, grid, Telegram, deploy/env, DB,
  fund, Earn, exchange, and external-backfill mutation flags false.
- 2026-06-23 local read-only profit operator next-action board refresh ran
  `scripts/prepare_profit_operator_next_action_board.ps1 -RequireReady` against
  the latest local priority packet and saved strategy574/TinyLive logs. It
  returned
  `READY_FOR_PROFIT_OPERATOR_NEXT_ACTION_REVIEW_NOT_LIVE`,
  `profit_operator_next_action_primary_focus=trailing-stop-dry-run-operator-review`,
  and `strategy574_tiny_live_risk_posture=BLOCKED_FIX_CURRENT_DATA_FRESHNESS`.
  The board ranks trailing-stop dry-run, strategy485 risk-reduction shadow,
  EntryDedup semantics shadow, and strategy574/TinyLive governance blocker
  review, while preserving false markers for TinyLive orders, live policy,
  scheduler enablement, deploy/env change, orders, Telegram send, and
  position/OCO mutation.
- 2026-06-24 read-only governance/DataFreshness/no-buy refresh tightened the
  live blocker route without changing production state. Governance relaxation
  remained `NO_EVIDENCE`, but
  `prepare_governance_relaxation_preflight_review_packet.ps1` now emits
  `BLOCKED_SOURCE_GOVERNANCE_RELAXATION_EVIDENCE` for valid source
  `NO_EVIDENCE` packets and propagates the source `nextAction` instead of
  forcing a blind refresh. The refreshed DataFreshness blocker brief showed
  `data_freshness_current_status=NO_CURRENT_SAMPLE`,
  `data_freshness_sample_gap_rca_recommendation=NO_RECENT_BUY_STYLE_CANDIDATES`,
  `sample_gap_buy_like_rows_7d_review=0`,
  `sample_gap_attention_hit_rows_7d_review=206`, and
  `sample_gap_data_freshness_rows_7d_review=0`. The no-buy attention packet
  returned `READY_FOR_ATTENTION_NO_BUY_FLOW_REVIEW_NOT_LIVE`, with
  `NO_BUY_LIKE_CANDIDATES_IN_REVIEW_WINDOW` and
  `NO_RECENT_DATAFRESHNESS_ROWS` as blockers. Strategy 574 near-threshold
  refresh found 206 `market_entropy_index` rows averaging 69 versus threshold
  70, but shadow observation remained negative:
  `false_positive_rate_pct=100.00`, `avg_net_return_pct=-0.4000`, and
  `STRATEGY574_NEAR_THRESHOLD_FALSE_POSITIVE_RISK_HIGH`. The refreshed final
  live blocker audit stayed `BLOCKED_NOT_READY_FOR_LIVE_ENABLEMENT` with 10
  lanes, 9 review-ready lanes, zero missing/stale/incomplete evidence, and
  governance relaxation as the only not-ready lane at that snapshot. Later
  no-action classification keeps the same not-live conclusion while preventing
  a zero-candidate governance-relaxation scan from being shown as the sole
  blocker.
- `scripts/prepare_no_buy_attention_flow_review_packet_ssh.ps1` now carries
  attention strategy distribution and SIGNAL_EVAL threshold-gap distribution
  into the consolidated packet. It emits `attention_macro_watch_only_rows`,
  `attention_candidate_interpretation`, `attention_strategy_distribution`,
  `signal_eval_threshold_gap_distribution`,
  `signal_eval_near_threshold_gap_count`, and
  `signal_eval_closest_threshold_gap_*`; near-threshold gaps add
  `SIGNAL_EVAL_NEAR_THRESHOLD_GAP_REVIEW` to route operator review before any
  threshold-change discussion. When all attention rows are
  `strategy=-1 interval=N/A`, it classifies them as
  `ATTENTION_HITS_ARE_MACRO_WATCH_ONLY_NOT_TRADING_CANDIDATES` so operators do
  not treat macro/watch-only warnings as trading candidates with missing
  terminal follow-up. The 2026-06-24 refresh reported
  `attention_macro_watch_only_rows=206` out of 206 attention rows, with
  `attention_strategy_distribution=[{"strategyId":"-1","intervalCode":"N/A","count":206}]`,
  `signal_eval_rows=2792`, `signal_eval_buy_like_rows=0`,
  `signal_eval_threshold_gap_count=10`,
  `signal_eval_near_threshold_gap_count=1`, and closest threshold gap strategy
  574 / 1h / `market_entropy_index` at `minBuyGap=1.0000`.
- `scripts/smoke_strategy485_position_risk_ssh.ps1` provides a focused
  read-only production RCA for SCORE_BUY strategy 485 open-position risk. It
  calls server-local `/api/mcp` to summarize open positions, OCO health,
  position-defense status, active-position EV, TP stretch/aging, stop-sweep
  policy, recent closed trades, execution events, and 3-month PnL, then prints
  `strategy485_position_risk_recommendation` such as
  `REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY` and
  `strategy485_position_review_decision`, a machine-readable routing object
  with OCO health, position EV counts, close/modify suggestions, timeout/TP
  stretch counts, per-position EV summaries, required evidence, next action,
  and non-authorization text. This smoke is review routing only; it does not
  close positions, modify OCO, change production env, DB, order, grid, Earn,
  fund, Telegram, scheduler, exchange, or external backfill/import state.
  `scripts/test_strategy485_position_risk_smoke.ps1` guards the read-only tool
  calls, no-order/no-OCO markers, risk recommendation markers, decision
  markers, and non-authorization wording.
- `docs/strategy485-aged-position-review-plan.md` defines the operator packet
  contract for `REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY`: fresh OCO,
  active-position EV, TP stretch, stop-sweep, timeout, recent-closed, and
  monthly PnL evidence are required before review, while close-position and
  OCO-modification actions remain separate explicit-authorized operations.
  `scripts/test_strategy485_aged_position_review_plan.ps1` keeps this plan
  linked to the smoke, profit bundle, and operator docs.
- `scripts/prepare_strategy485_position_review_gate_ssh.ps1` wraps origin-delta
  plus the strategy 485 position-risk smoke into a read-only gate. It emits
  `deploy_required_before_strategy485_review`, `operator_review_packet_allowed`,
  `position_or_oco_mutation_allowed=false`,
  `strategy485_position_review_decision`,
  `strategy485_review_missing_requirements`, and
  `strategy485_position_review_gate_status`. The gate can route a separate
  operator review packet only after runtime and evidence stop conditions are
  clear; it never authorizes close-position or OCO modification.
- `scripts/prepare_strategy485_operator_review_packet_ssh.ps1` wraps that gate
  into a machine-readable `strategy485_operator_review_packet` and
  `strategy485_operator_packet_status`. It can become
  `READY_FOR_OPERATOR_PACKET_NOT_MUTATION` only when the gate is ready and the
  decision JSON still proves `positionOrOcoMutationAllowed=false`; it never
  authorizes close-position or OCO modification.
- `scripts/smoke_auto_trading_review_bundle_ssh.ps1` wraps the read-only
  origin-delta classifier, live-authorized audit, strategy 485 position-risk
  smoke, strategy 574 signal/governance smoke, and TinyLive post-trade smoke
  into one review command. It prints `review_items` plus
  `auto_trading_review_recommendation` such as
  `OPERATOR_REVIEW_STRATEGY485_POSITION_RISK` or
  `CONTINUE_TINYLIVE_MONITORING`. It also carries
  `strategy485_position_review_decision` plus parsed strategy 485 negative-EV,
  close/modify, and timeout counts into the bundle summary. The wrapper invokes
  existing read-only smokes only and does not change production env, DB, order,
  OCO, grid, Earn, fund, Telegram, scheduler, exchange, external
  backfill/import, deploy, restart, or nginx state.
  `scripts/test_auto_trading_review_bundle.ps1` guards the child smoke list,
  output markers, strategy 485 decision propagation, and non-authorization
  wording.
- `scripts/prepare_auto_trading_review_gate_ssh.ps1` converts the read-only
  auto-trading review bundle into a packet gate with
  `deploy_required_before_auto_trading_review`,
  `operator_review_packet_allowed`, `position_or_oco_mutation_allowed=false`,
  `tiny_live_order_allowed=false`, `live_policy_change_allowed=false`,
  `auto_trading_review_missing_requirements`, and
  `auto_trading_review_gate_status`. The gate can route a separate operator
  packet for read-only position review after runtime currentness is proven, but
  it never authorizes close-position, OCO modification, pre-buying, TinyLive
  order execution, deploy, restart, or live policy changes.
- `scripts/smoke_profit_candidate_review_ssh.ps1` provides a read-only
  production review for risk-adjusted profit-improvement candidates. It calls
  server-local `/api/mcp` for monthly PnL, enabled strategy scorecard,
  ExpectedValueGate stats, signal accuracy, blocked-signal outcomes,
  missed-opportunity/no-buy truth-table evidence, shadow readiness, shadow
  activation candidates, and trailing-stop PnL replay, then prints
  `profit_candidate_items` and `profit_candidate_review_recommendation` such as
  `REVIEW_DATAFRESHNESS_FALSE_KILL_WITH_SHADOW_REPLAY`. The smoke is evidence
  only and does not run origin-delta/currentness checks; use
  `smoke_profit_improvement_review_bundle_ssh.ps1` or
  `prepare_profit_experiment_gate_ssh.ps1` before treating it as
  current-post-deploy profit review input. It does not authorize live trading,
  policy relaxation, strategy
  activation, closing positions, OCO modification, production env changes, DB,
  order, grid, Earn, fund, Telegram, scheduler, exchange, external
  backfill/import, deploy, restart, or nginx state.
  `scripts/test_profit_candidate_review_smoke.ps1` guards the read-only MCP
  calls, hard-fail boundary checks, output markers, docs coverage, and
  non-authorization wording.
- `scripts/prepare_profit_loss_review_gate_ssh.ps1` converts origin-delta and
  profit-candidate evidence into a loss-source packet gate with
  `deploy_required_before_profit_loss_review`, `loss_source_review_allowed`,
  `live_policy_change_allowed=false`, `position_or_oco_mutation_allowed=false`,
  `tiny_live_order_allowed=false`, `profit_loss_review_missing_requirements`,
  and `profit_loss_review_gate_status`. It can route a separate read-only
  loss-source review packet after runtime currentness is proven, but it never
  authorizes DataFreshness relaxation, close-position, OCO modification,
  pre-buying, TinyLive order execution, deploy, restart, or live policy
  changes. The profit-candidate smoke and loss gate now default
  `TrailingLimit=500`, matching issue acceptance and trailing dry-run
  activation review so loss-source routing does not depend on a narrow
  diagnostic trailing replay sample.
- `scripts/smoke_post_deploy_profit_validation_ssh.ps1` aggregates the
  auto-trading review gate, profit loss review gate, and profit experiment gate
  after a separately authorized deploy. It emits
  `deploy_required_before_post_deploy_profit_validation`,
  `server_worktree_commit`, `origin_main_commit`,
  `origin_runtime_delta_files`, `origin_runtime_delta_paths`,
  `origin_runtime_delta_impact`,
  `post_deploy_profit_validation_status`,
  `post_deploy_profit_validation_missing_requirements`,
  `data_freshness_counterfactual_gate_missing_requirements`,
  `strategy485_position_review_decision`,
  `post_deploy_profit_validation_review_plan`,
  `post_deploy_profit_validation_blocker_summary`,
  `post_deploy_profit_validation_review_decision`,
  `live_policy_change_allowed=false`, `position_or_oco_mutation_allowed=false`,
  and `tiny_live_order_allowed=false` so profit review readiness can be checked
  from one read-only command. The blocker summary preserves
  `requiredEvidenceCount`, `requiredEvidence`, `nextAction`, `runtimeDrift`, and no-live
  authorization text for each blocked child gate, while runtime-drift output
  shows the concrete runtime files behind deploy-first blockers. The review
  decision is the top-level routing object with `canPrepareReviewPacket`,
  `deployRequired`, `allowedReviewTypes`, `runtimeDrift`, `blockedGates`, and
  blocker/missing counts. DataFreshness false-kill profit review stays blocked
  when complete replayable candidate rows or any counterfactual field is still
  missing. The 2026-06-30 refresh also keeps
  `COUNTERFACTUAL_NOT_REPLAYABLE_CANDIDATE_SNAPSHOT_MISSING` as a ranked
  DataFreshness scorecard lane instead of an empty-scorecard `NO_EVIDENCE`
  result, so downstream gates can preserve the replayable-candidate blockers.
  `scripts/test_post_deploy_profit_validation.ps1` guards the child
  gate list, blocker summary shape, safety markers, docs coverage, and local
  input validation.
- `scripts/prepare_profit_runtime_deploy_review_packet_ssh.ps1` packages the
  read-only profit runtime deploy review packet before a separate deploy
  decision. It combines `smoke_live_origin_delta_local.ps1` with
  `smoke_post_deploy_profit_validation_ssh.ps1`, emits
  `profit_runtime_deploy_review_packet`,
  `profit_runtime_deploy_packet_status`,
  `origin_runtime_delta_paths`, and `origin_runtime_delta_impact`, and can
  return `READY_FOR_DEPLOY_REVIEW_NOT_DEPLOYED` when runtime drift is proven
  and profit validation remains blocked until deployment. The packet does not
  deploy, restart, reload nginx, change production env, enable live trading,
  place orders, modify OCO/grid/fund/Earn state, send Telegram, mutate DB
  state, touch exchange state, or run external backfill/import.
- `scripts/prepare_profit_blocker_ledger_ssh.ps1` merges the read-only profit
  runtime deploy packet, profit shadow experiment packet, strategy 485 operator
  packet, and DataFreshness replay observation bundle into
  `profit_blocker_ledger_packet`, `profit_blocker_ledger_items`, and
  `profit_blocker_ledger_status`. It prioritizes blockers such as
  `deployed runtime current`, `complete DataFreshness replayable candidate
  rows`, and `current strategy 485 OCO health`, and can return
  `BLOCKED_DEPLOY_CURRENT_RUNTIME` when currentness remains the first blocker.
  The ledger does not deploy, restart, reload nginx, change production env,
  enable live trading, relax policy, place orders, modify OCO, close positions,
  mutate DB/grid/fund/Earn/Telegram/exchange state, run external
  backfill/import, or authorize strategy/DataFreshness policy changes.
- `scripts/prepare_profit_readiness_brief_ssh.ps1` combines
  `smoke_signal_correctness_ssh.ps1`,
  `smoke_trailing_stop_pnl_replay_ssh.ps1`, and
  `prepare_profit_blocker_ledger_ssh.ps1`, plus the
  `prepare_entry_dedup_operator_decision_brief_ssh.ps1` EntryDedup shadow
  decision brief, into
  `profit_readiness_brief_packet` and `profit_readiness_brief_status`. It emits
  `entry_filter_lane_status`, `entry_dedup_shadow_lane_status`,
  `entry_dedup_operator_decision_brief_status`, `exit_lane_status`, and
  `trailing_stop_acceptance` so entry/filter governance/missed-opportunity
  blockers, EntryDedup shadow review evidence, and exit-side trailing/TP-stop
  evidence stay separate in operator review. It now prints `child_start`,
  periodic `child_heartbeat`, and
  `child_complete` markers for long child smokes, with
  `-ChildTimeoutSeconds` bounded to 60..3600 seconds so a stuck local child can
  fail closed with `timedOut=true`. The brief does not deploy, restart, reload
  nginx, change production env, enable live trading, relax
  EntryDedup/DataFreshness/live policy, place orders, modify OCO, close
  positions, mutate DB/grid/fund/Earn/Telegram/exchange state, run external
  backfill/import, or authorize strategy changes.
- `scripts/prepare_entry_filter_blocker_decision_brief_ssh.ps1` now provides a
  focused read-only entry/filter blocker surface for the current
  `BLOCKED_ENTRY_FILTER_REVIEW` state. It invokes
  `smoke_signal_correctness_ssh.ps1`,
  `prepare_data_freshness_replay_evidence_readiness_ssh.ps1`, and
  `prepare_entry_dedup_operator_decision_brief_ssh.ps1`, then emits
  `entry_filter_blocker_decision_brief_packet`,
  `entry_filter_blocker_decision_brief_status`,
  `entry_filter_policy_lane_status`, `data_freshness_replay_lane_status`,
  `entry_dedup_shadow_lane_status`, and
  `entry_filter_blocker_missing_requirements`. The brief separates signal
  policy/missed-opportunity blockers, DataFreshness replay evidence blockers,
  and EntryDedup shadow-review readiness. It does not deploy, restart, reload
  nginx, change production env, enable live trading, relax
  EntryDedup/DataFreshness/live policy, place orders, modify OCO, close
  positions, mutate DB/grid/fund/Earn/Telegram/exchange state, run external
  backfill/import, or authorize strategy changes.
- `scripts/prepare_signal_missed_blocker_decision_brief_ssh.ps1` now provides
  the focused follow-up for
  `entry_filter_blocker_decision_brief_status=BLOCKED_SIGNAL_POLICY_OR_MISSED_OPPORTUNITY_REVIEW`.
  It invokes `prepare_entry_filter_operator_review_packet_ssh.ps1`,
  `prepare_no_buy_row_review_packet_ssh.ps1`,
  `prepare_missed_opportunity_shadow_design_packet_ssh.ps1`, and
  `prepare_governance_relaxation_review_packet_ssh.ps1`, then emits
  `signal_missed_blocker_decision_brief_packet`,
  `signal_missed_blocker_decision_brief_status`,
  `entry_filter_operator_lane_status`, `no_buy_row_review_lane_status`,
  `missed_opportunity_shadow_lane_status`,
  `governance_relaxation_lane_status`, and
  `signal_missed_blocker_missing_requirements`. It keeps signal policy,
  no-buy row, missed-opportunity shadow design, and governance relaxation lanes
  separate before any shadow-only review. It does not deploy, restart, reload
  nginx, change production env, enable live trading, execute tiny-live orders,
  relax EntryDedup/DataFreshness/live policy, place orders, modify OCO, close
  positions, mutate DB/grid/fund/Earn/Telegram/exchange state, run external
  backfill/import, or authorize strategy changes.
- `scripts/watch_profit_evidence_readiness_ssh.ps1` is a bounded read-only
  watcher for the current profit evidence bottleneck. It reruns
  `prepare_profit_readiness_brief_ssh.ps1` and
  `smoke_data_freshness_replay_observation_bundle_ssh.ps1` for a configured
  number of attempts, then emits `profit_evidence_watch_status`,
  `attempt_data_freshness_current_status`,
  `attempt_replay_candidate_id_recommendation`, and
  `attempt_replay_observation_bundle_recommendation`. It can return
  `PENDING_DATAFRESHNESS_CURRENT_SAMPLE`,
  `PENDING_REPLAY_CANDIDATE_ID_EVIDENCE`,
  `PENDING_COUNTERFACTUAL_REPLAY_EVIDENCE`, or
  `EVIDENCE_READY_FOR_REVIEW_NOT_LIVE`; the last status only routes a
  separate read-only review. Long child smokes emit watcher-level
  `child_start`, `child_heartbeat`, and `child_complete` markers. The watcher
  does not authorize live trading, policy
  relaxation, deploy, production env changes, orders, OCO, position closes,
  DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import.
- `scripts/watch_profit_next_execution_readiness_ssh.ps1` is the bounded
  read-only watcher for the current make-money next-execution lane. It reruns
  `prepare_data_freshness_replay_evidence_readiness_ssh.ps1`, saves the attempt
  log, feeds that log into `prepare_profit_next_execution_blocker_packet.ps1`,
  and emits `profit_next_execution_watch_status`,
  `profit_next_execution_watch_unique_blocker`,
  `profit_next_execution_watch_observation_sample_ready`,
  `profit_next_execution_watch_sample_collection_blocked_by`, and
  `profit_next_execution_watch_data_freshness_replay_candidate_id_rows`.
  `PENDING_OPEN_OCO_SAMPLE` means trailing dry-run is active but needs a
  natural open OCO sample; `PENDING_TRAILING_OPT_IN_EVIDENCE` means the
  non-mutating trailing opt-in dry-run evidence must be refreshed before any
  execution request; `PENDING_DATAFRESHNESS_REPLAY_EVIDENCE` means replay
  candidate rows are still missing.
  `EVIDENCE_READY_FOR_OPERATOR_REVIEW_NOT_LIVE` only starts a separate
  read-only review. The watcher does not deploy, change production env, enable
  live trading/scheduler, relax policy, place orders, modify OCO, close
  positions, send Telegram, or mutate DB/grid/fund/Earn/exchange state.
- `scripts/prepare_remaining_open_issues_status.ps1` is a local-only
  consolidated packet for the active remaining open profit issues #6/#7/#8. It
  reads the saved #6 profit-improvement bundle, #7 post-deploy bundle, #8
  BUY-like loss packet, and bounded profit evidence watcher logs, then emits
  `REMAINING_OPEN_ISSUES_STATUS_PACKET`, `remaining_open_issues_status`,
  `remaining_open_issues_global_blocker`, `issue6_status`,
  `issue7_remaining_blocker`, `issue8_status`, `active_open_issue_numbers`,
  `active_remaining_issue_count`, and `profit_evidence_watch_status`. Closed
  #9/#10/#11/#12 lanes are preserved only as completed context and do not affect
  `remainingIssueCount`, the global blocker, or the active next action. If the
  full #7 bundle stops before its summary marker because split-acceptance
  currentness fails on docs/tooling drift, it can fall back to a fresh #7
  collector post-activation status log for blocker classification. It does not
  run SSH or GitHub calls. When it reports `BLOCKED_NOT_CLOSEABLE` with
  `NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS`, #6/#7 remain open for
  replayable DataFreshness evidence and #8 remains open for the upstream
  BUY-like/DataFreshness terminal-flow gap; the next safe action is to wait for
  fresh post-collector DataFreshnessGuard terminal rows before rerunning
  read-only evidence. It does not authorize live
  trading, policy relaxation, deploy, production env changes, orders, OCO,
  position closes, DB/grid/fund/Earn/Telegram/exchange mutation, issue closure,
  or external backfill/import.
- `scripts/prepare_data_freshness_profit_blocker_brief_ssh.ps1` is a narrower
  read-only DataFreshness profit blocker brief. It combines
  `smoke_signal_correctness_ssh.ps1` current-source evidence with
  `smoke_data_freshness_replay_observation_bundle_ssh.ps1` and
  `smoke_data_freshness_sample_gap_rca_ssh.ps1`, then emits
  `data_freshness_profit_blocker_brief_packet`,
  `data_freshness_profit_blockers`, and
  `data_freshness_profit_blocker_status`. `PENDING_DATAFRESHNESS_CURRENT_SAMPLE`
  means current-source evidence is still missing, while
  `READY_FOR_DATAFRESHNESS_REPLAY_REVIEW_NOT_LIVE` only routes a separate
  replay review. The brief now also carries DataFreshness sample recency from
  the replay bundle plus `data_freshness_sample_gap_rca_recommendation`,
  sample-gap row counts, and latest DataFreshness row age, so a pending current
  sample can be separated from a no-BUY-style-candidate window before any
  DataFreshness policy review. The
  brief does not authorize live trading, policy relaxation, deploy, production
  env changes, orders, OCO, position closes,
  DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import.
- `scripts/prepare_profit_operator_review_matrix_ssh.ps1` combines
  `prepare_profit_readiness_brief_ssh.ps1`,
  `watch_profit_evidence_readiness_ssh.ps1`,
  `prepare_exit_side_profit_review_packet_ssh.ps1`, and
  `prepare_data_freshness_shadow_candidate_packet_ssh.ps1` into
  `profit_operator_review_items`, `profit_operator_review_matrix_packet`, and
  `profit_operator_review_matrix_status`. It can return
  `HAS_REVIEW_READY_ITEMS_NOT_LIVE` when a lane such as `exit-side` has enough
  read-only evidence for a separate operator review while other lanes such as
  `entry-filter` or `data-freshness-replay` remain blocked. `REVIEW_SIGNAL_POLICY`
  is not treated as an operator-ready entry-filter lane; entry-filter readiness
  requires the readiness brief to report `CLEAR`. The DataFreshness replay lane
  now carries `data_freshness_shadow_candidate_packet_status`, including
  `BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`, so historical proxy
  blockers are visible in the operator overview. The matrix does not
  authorize live trading, policy relaxation, deploy, production env changes,
  orders, OCO, position closes, DB/grid/fund/Earn/Telegram/exchange mutation,
  or external backfill/import.
- `scripts/prepare_profit_operator_action_brief_ssh.ps1` converts the profit
  operator review matrix into `profit_operator_action_items`,
  `profit_operator_action_brief_packet`, and
  `profit_operator_action_brief_status`. When exit-side evidence is ready it
  returns `READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE` with
  `REVIEW_EXIT_SIDE_TRAILING_AND_STRATEGY485_NOT_MUTATION`, while preserving
  blocked `entry-filter` and `data-freshness-replay` actions. It also emits
  `exit_side_operator_action_proposals`, splitting the ready exit-side lane
  into trailing-stop rollout and strategy 485 risk-reduction proposal skeletons
  with ids `trailing-stop-rollout-review` and
  `strategy485-risk-reduction-review`. They reference
  `docs/exit-side-operator-review-plan.md` and remain non-executable. It also emits
  `profit_operator_decision_lanes` / `decisionLanes` with machine-readable lane
  classes such as `EXIT_SIDE_REVIEW_READY_NOT_LIVE`,
  `ENTRY_FILTER_POLICY_BLOCKED`, and `DATAFRESHNESS_REPLAY_BLOCKED`, so ready
  and blocked profit lanes stay visible together. Long child matrix runs emit
  `child_start`, periodic `child_heartbeat`, and `child_complete` markers, with
  `-ChildTimeoutSeconds` bounding stuck local children. Fresh action-brief runs
  now apply a separate `-MatrixTimeoutSeconds` outer timeout to the nested
  matrix child; the default `0` derives a larger timeout from
  `-ChildTimeoutSeconds` and prints `source_matrix_timeout_seconds` plus
  `child_timeout_seconds` so the matrix is not killed at the first inner-child
  limit. Fresh runs can save raw matrix output with `-SaveMatrixOutputPath`, and follow-up briefs can reuse it
  with `-MatrixOutputPath` and `source_matrix_mode=REUSED_OUTPUT_FILE` instead
  of rerunning the long SSH matrix. Fresh SSH runs now also invoke
  `prepare_signal_missed_blocker_decision_brief_ssh.ps1` and emit
  `profit_operator_signal_missed_blocker_decision` plus
  `signal_missed_blocker_decision_brief_status`, attaching signal/missed and
  governance blocker detail to the entry-filter lane. If matrix collection does
  not produce a usable packet but the signal/missed blocker child succeeds, the
  action brief remains fail-closed with
  `MATRIX_COLLECTION_INCOMPLETE_SIGNAL_MISSED_BLOCKER_COLLECTED`. Matrix reuse mode emits
  `signal_missed_blocker_decision_brief_status=NOT_COLLECTED_REUSED_MATRIX`
  because it deliberately avoids fresh SSH evidence collection. Fresh runs default the saved matrix log to
  `target/profit-review/`, but only successful matrix output with a parsable
  review packet updates `target/profit-review/latest-profit-operator-matrix.path`
  for the next operator review. Failed or timed-out matrix output is saved for
  diagnosis without becoming the latest reusable evidence pointer.
  `scripts/prepare_profit_operator_latest_action_brief.ps1`
  reads that pointer and rebuilds the action brief through the same
  `-MatrixOutputPath` freshness guard without rerunning the long SSH matrix.
  `scripts/prepare_profit_operator_compact_status.ps1` reads the same pointer
  directly and emits `PROFIT_OPERATOR_COMPACT_STATUS`,
  `profit_operator_compact_ready_lanes`,
  `profit_operator_compact_blocked_lanes`, and
  `profit_operator_compact_status` without rerunning SSH or replaying the full
  action brief, so the operator can quickly see whether the latest saved matrix
  still routes to `READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE`.
  `scripts/prepare_profit_operator_quick_status.ps1` is the fastest first
  check: it reads the latest matrix pointer, emits
  `PROFIT_OPERATOR_QUICK_STATUS`, `profit_operator_quick_status_packet`,
  `profit_operator_quick_status`, and
  `profit_operator_quick_refresh_required`, and now also includes the saved
  profit-next-execution blocker fields
  `profit_operator_quick_next_execution_unique_blocker`,
  `profit_operator_quick_next_execution_open_oco_positions`, and
  `profit_operator_quick_next_execution_data_freshness_replay_candidate_id_rows`.
  It returns `REFRESH_REQUIRED_NO_MATRIX`,
  `REFRESH_REQUIRED_STALE_MATRIX`, or
  `REFRESH_REQUIRED_INVALID_MATRIX_PACKET` when the operator should refresh the
  read-only matrix before using the status. It does not rerun SSH, refresh the
  blocker log, or authorize live trading, policy relaxation, deploy, production
  env changes, orders, OCO, position closes,
  DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import.
  `scripts/prepare_profit_aggressive_activation_operator_packet.ps1` packages a
  more aggressive operator review path on top of the saved authorization request
  and quick next-execution blocker. It emits
  `PROFIT_AGGRESSIVE_ACTIVATION_OPERATOR_PACKET` with three separated options:
  `HIGH_RISK_MICRO_LIVE_PROBE`,
  `GRID10_EXISTING_ACTIVE_GRID_ORDER_PATH`, and `EVIDENCE_ONLY_ACCELERATOR`.
  The packet carries exact authorization text, rollback criteria, and current
  blockers such as `NO_OPEN_OCO_POSITIONS_FOR_TRAILING_DRY_RUN_SAMPLE` and
  `DATAFRESHNESS_REPLAY_ROWS_MISSING`, while keeping `order_allowed=false`,
  `deploy_or_env_change_allowed=false`, and `live_policy_change_allowed=false`.
  Each option now also carries machine-readable `proposedEnvDiff`,
  `riskAcceptanceConditions`, `postEnvReadOnlyVerificationCommands`,
  `killSwitchEnvDiff`, and `rollbackCommands`, plus top-level proposed env
  diff, risk-acceptance, post-env verification, kill-switch, and rollback plans
  for a separate operator env/deploy decision. It also emits
  `profit_aggressive_activation_selected_path`,
  `profit_aggressive_activation_order_capable_candidate`,
  `profit_aggressive_activation_order_capable_execution_now_allowed`,
  `profit_aggressive_activation_order_capable_blockers`, and
  `profit_aggressive_activation_execution_queue`; the current ready packet
  selects `EVIDENCE_ONLY_ACCELERATOR` first and keeps
  `order_capable_execution_now_allowed=false`, with
  `GRID10_EXISTING_ACTIVE_GRID_ORDER_PATH` as the next order-capable candidate
  only after fresh grid authorization and exact operator approval. The packet
  now also accepts a fresh `-GridBlockerPriorityBoardLogPath` and surfaces
  `profit_aggressive_activation_grid10_evidence_status`,
  `profit_aggressive_activation_grid10_openable_now`,
  `profit_aggressive_activation_grid10_readiness_score_pct`,
  `profit_aggressive_activation_grid10_top_blocker`, and
  `profit_aggressive_activation_grid10_ranked_blockers`, so the grid10 lane can
  carry current trend/capital blocker evidence instead of generic post-env
  placeholders.
  `scripts/prepare_profit_grid10_order_path_handoff.ps1` now packages the
  `GRID10_EXISTING_ACTIVE_GRID_ORDER_PATH` lane after a fresh 10 USDT
  micro-grid authorization bundle is saved locally. It emits
  `PROFIT_GRID10_ORDER_PATH_HANDOFF_PACKET` with
  `READY_FOR_PROFIT_GRID10_ORDER_PATH_OPERATOR_REVIEW_NOT_MUTATION`,
  `grid10_exact_authorization_texts`,
  `grid10_post_env_read_only_verification`, `grid10_kill_switch_env_diff`, and
  reviewed 2 x 5 USDT inputs, while keeping
  `grid10_execution_now_allowed=false`,
  `grid10_env_deploy_request_allowed=false`, `order_allowed=false`, and
  `create_grid_allowed=false`.
  `scripts/prepare_profit_grid10_activation_authorization_bundle.ps1` then
  turns a ready grid10 handoff log into
  `PROFIT_GRID10_ACTIVATION_AUTHORIZATION_BUNDLE` with
  `READY_FOR_PROFIT_GRID10_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION`,
  `grid10_activation_authorization_review_ready`, and the exact
  `grid10_activation_authorization_text` for the reviewed 2 x 5 USDT
  env/deploy/createGrid prompt. It remains non-executing with
  `grid10_activation_execution_allowed=false`,
  `grid10_env_deploy_request_allowed=false`, `deploy_allowed=false`,
  `order_allowed=false`, and `create_grid_allowed=false`.
  `scripts/prepare_profit_grid10_same_session_activation_review_packet.ps1`
  now consumes the saved activation bundle and emits
  `PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_PACKET` with
  `READY_FOR_PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_NOT_MUTATION`,
  `grid10_same_session_operator_checklist_ready`, the exact
  `grid10_same_session_exact_authorization_text`, env diff, post-env
  read-only verification, and kill-switch env diff. It is the final
  same-session operator checklist before any later env/deploy/createGrid
  action and still keeps `grid10_same_session_execution_allowed=false`,
  `grid10_same_session_env_deploy_allowed=false`, `deploy_allowed=false`,
  `order_allowed=false`, and `create_grid_allowed=false`.
  `scripts/prepare_profit_grid10_activation_source_refresh.ps1` now refreshes
  the local grid10 chain in one read-only command, saving
  `profit-grid10-order-path-handoff-latest.log`,
  `profit-grid10-activation-authorization-bundle-latest.log`, and
  `profit-grid10-same-session-activation-review-latest.log` before emitting
  `PROFIT_GRID10_ACTIVATION_SOURCE_REFRESH_PACKET` and
  `profit_grid10_activation_source_refresh_status`. A ready refresh only means
  the local same-session checklist is replayable; it still keeps
  `deploy_allowed=false`, `order_allowed=false`, and
  `create_grid_allowed=false`.
  `scripts/prepare_profit_grid10_execution_preflight_packet.ps1` consumes the
  refreshed source packet plus the same-session review log and emits
  `PROFIT_GRID10_EXECUTION_PREFLIGHT_PACKET` with
  `READY_FOR_PROFIT_GRID10_ENV_DEPLOY_CREATEGRID_EXECUTION_PREFLIGHT_NOT_MUTATION`,
  `grid10_execution_preflight_ready`, the exact
  `grid10_execution_exact_authorization_text`, ordered env/deploy/post-env/
  createGrid review sequence, post-env verification commands, and kill-switch
  plan. It remains a read-only preflight with
  `grid10_env_deploy_execution_allowed=false`,
  `grid10_create_grid_execution_allowed=false`, `deploy_allowed=false`,
  `order_allowed=false`, and `create_grid_allowed=false`.
  `scripts/prepare_profit_evidence_only_accelerator_env_deploy_handoff.ps1`
  selects the recommended non-order `EVIDENCE_ONLY_ACCELERATOR` lane and emits
  `PROFIT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_HANDOFF_PACKET` with the exact
  operator authorization text, evidence-only env diff
  `TRADING_RUNTIME_EVIDENCE_ENABLED=true` plus
  `TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true`, post-env
  read-only verification, kill-switch env diff, and rollback commands. It keeps
  `production_env_change_allowed=false`, `deploy_allowed=false`, and
  `order_allowed=false`.
  `scripts/prepare_profit_operator_review_summary.ps1` converts that latest
  action brief into `profit_operator_review_summary_packet`, ready lanes,
  exit-side proposals, blocked lanes, and required evidence for operator review
  while preserving the same non-authorization boundary.
  `scripts/prepare_exit_side_operator_experiment_packet.ps1` then reuses that
  fresh latest summary without rerunning SSH and emits
  `EXIT_SIDE_OPERATOR_EXPERIMENT_REVIEW` with
  `READY_FOR_EXIT_SIDE_EXPERIMENT_REVIEW_NOT_LIVE`, carrying
  `trailing-stop-dry-run-experiment-review` and
  `strategy485-risk-reduction-shadow-review` as review-only proposals.
  `scripts/prepare_profit_verified_recommendations.ps1` wraps that exit-side
  experiment packet and the recorded EntryDedup semantics shadow experiment
  packet into `PROFIT_VERIFIED_RECOMMENDATIONS` with
  `READY_WITH_REVIEW_ONLY_RECOMMENDATIONS`, listing the review-only exit-side
  recommendations, `entry-dedup-semantics-shadow-experiment-review`, and the
  still-blocked entry-filter/DataFreshness policy lanes in one machine-readable
  packet.
  `scripts/prepare_exit_side_verified_experiment_readiness.ps1` then converts
  those verified recommendations into `EXIT_SIDE_VERIFIED_EXPERIMENT_READINESS`
  with `READY_FOR_EXIT_SIDE_DRY_RUN_AND_SHADOW_REVIEW_NOT_LIVE`, carrying
  `trailing-stop-dry-run-readiness` and
  `strategy485-risk-reduction-shadow-readiness` plans with minimum evidence,
  success evidence, stop criteria, `live_policy_change_allowed=false`, and
  `position_or_oco_mutation_allowed=false`.
  `scripts/prepare_exit_side_experiment_operator_review_packet.ps1` attaches
  that readiness evidence to `EXIT_SIDE_EXPERIMENT_OPERATOR_REVIEW_PACKET` with
  `READY_FOR_OPERATOR_REVIEW_PACKET_NOT_LIVE`, carrying
  `trailing-stop-dry-run-operator-review` and
  `strategy485-risk-reduction-shadow-operator-review` review items,
  `small_experiment_review_cap_usdt`, an observation window, operator choices,
  and `order_allowed=false`.
  `scripts/prepare_profit_operator_consolidated_review_packet.ps1` wraps that
  exit-side operator packet plus the EntryDedup shadow review packet into
  `PROFIT_OPERATOR_CONSOLIDATED_REVIEW_PACKET` with
  `READY_FOR_OPERATOR_REVIEW_NOT_LIVE`, keeping the ready exit-side review
  items, `entry-dedup-semantics-shadow-operator-review`, and blocked
  entry-filter/DataFreshness policy lanes together in one operator packet while
  preserving `order_allowed=false`, `live_policy_change_allowed=false`,
  `entry_dedup_policy_change_allowed=false`, and
  `position_or_oco_mutation_allowed=false`.
  `scripts/prepare_profit_operator_priority_decision_brief.ps1` then converts
  the consolidated packet into `PROFIT_OPERATOR_PRIORITY_DECISION_BRIEF`,
  `profit_operator_priority_ranked_items`,
  `profit_operator_priority_primary_focus`, and
  `profit_operator_priority_decision_brief_status`. It ranks the current
  review-only work as trailing-stop dry-run first, strategy485 risk-reduction
  shadow second, and EntryDedup semantics shadow third, while keeping
  entry-filter/DataFreshness policy lanes blocked and preserving the same
  no-live/no-order/no-OCO/no-deploy/no-policy-relaxation boundary.
  `scripts/prepare_trailing_stop_dry_run_operator_decision_packet.ps1` then
  narrows the first-ranked item into
  `TRAILING_STOP_DRY_RUN_OPERATOR_DECISION_PACKET`,
  `trailing_stop_dry_run_operator_decision_packet`,
  `trailing_stop_dry_run_primary_focus`, and
  `trailing_stop_dry_run_operator_decision_status`. It requires the priority
  focus to remain `trailing-stop-dry-run-operator-review`, requires the
  exit-side operator packet to keep that item ready, and preserves
  `scheduler_enablement_allowed=false`, `order_allowed=false`,
  `position_or_oco_mutation_allowed=false`, and the same no-live/no-deploy/no
  policy-relaxation boundary.
  `scripts/prepare_trailing_stop_dry_run_preflight_review_packet.ps1` then
  wraps that decision packet into
  `TRAILING_STOP_DRY_RUN_PREFLIGHT_REVIEW_PACKET`,
  `trailing_stop_dry_run_preflight_review_packet`, and
  `trailing_stop_dry_run_preflight_status`. It clarifies dry-run-only operator
  inputs and future prerequisites while preserving
  `scheduler_enablement_allowed=false`, `order_allowed=false`,
  `telegram_send_allowed=false`, `position_or_oco_mutation_allowed=false`, and
  `deploy_or_env_change_allowed=false`.
  `scripts/prepare_strategy485_risk_reduction_operator_decision_packet.ps1`
  narrows the second-ranked item into
  `STRATEGY485_RISK_REDUCTION_OPERATOR_DECISION_PACKET`,
  `strategy485_risk_reduction_operator_decision_packet`,
  `strategy485_risk_reduction_priority_rank`, and
  `strategy485_risk_reduction_operator_decision_status`. It requires the
  strategy485 item to remain rank `2`, requires the exit-side operator packet
  to keep that item ready, and preserves `close_position_allowed=false`,
  `position_or_oco_mutation_allowed=false`, `order_allowed=false`, and the same
  no-live/no-OCO/no-deploy/no-policy-relaxation boundary.
  `scripts/prepare_strategy485_risk_reduction_preflight_review_packet.ps1`
  then wraps that decision packet into
  `STRATEGY485_RISK_REDUCTION_PREFLIGHT_REVIEW_PACKET`,
  `strategy485_risk_reduction_preflight_review_packet`, and
  `strategy485_risk_reduction_preflight_status`. It clarifies non-mutating
  operator inputs and future close/OCO prerequisites while preserving
  `close_position_allowed=false`, `position_or_oco_mutation_allowed=false`,
  `order_allowed=false`, `telegram_send_allowed=false`, and
  `deploy_or_env_change_allowed=false`.
  `scripts/prepare_entry_dedup_semantics_operator_decision_packet.ps1` narrows
  the third-ranked item into `ENTRY_DEDUP_SEMANTICS_OPERATOR_DECISION_PACKET`,
  `entry_dedup_semantics_operator_decision_packet`,
  `entry_dedup_semantics_priority_rank`, and
  `entry_dedup_semantics_operator_decision_status`. It requires the EntryDedup
  item to remain rank `3`, requires the EntryDedup semantics shadow packet to
  stay ready, and preserves `entry_dedup_policy_change_allowed=false`,
  `data_freshness_policy_change_allowed=false`, `order_allowed=false`,
  `position_or_oco_mutation_allowed=false`, and the same no-live/no-OCO/no-
  deploy/no-policy-relaxation boundary.
  `scripts/prepare_entry_dedup_semantics_direct_operator_packet.ps1` now also
  provides an EntryDedup-only read-only review packet for cases where the full
  profit-priority matrix is blocked by unrelated lanes. It emits
  `ENTRY_DEDUP_SEMANTICS_DIRECT_OPERATOR_PACKET`,
  `entry_dedup_semantics_direct_operator_packet`, and
  `sourcePriorityDependency=NOT_REQUIRED_ENTRY_DEDUP_DIRECT_REVIEW`, carries
  the exact-opportunity fields from the shadow packet, treats
  `NON_AUTO_ZERO_QTY_OPEN_SIGNAL_PRESENT` as a live-preflight warning, keeps
  `OCO_ROUTE_NOT_PROVEN_OR_MISSING` as a hard mutation blocker, and preserves
  `entry_dedup_policy_change_allowed=false`,
  `data_freshness_policy_change_allowed=false`,
  `staged_add_execution_allowed=false`, `order_allowed=false`,
  `grid_mutation_allowed=false`, and `telegram_send_allowed=false`.
  The SSH shadow packet now also invokes the exact-opportunity staged-add review
  smoke, so fresh production reruns carry `exactOpportunityCount`,
  `exactDuplicateSuppressedRows`, `tpHitOpportunities`, and
  `stagedAddReviewCandidateOpportunities` into the direct operator packet.
  `scripts/prepare_entry_dedup_runtime_proof_gap_packet.ps1` then combines the
  direct packet, gate preflight, and synthetic EV/OCO preview into
  `ENTRY_DEDUP_RUNTIME_PROOF_GAP_PACKET`, ranking the remaining blockers as
  exact OCO route proof, candidate-level runtime EV/OCO snapshots, exact
  duplicate replay protection, and daily cap/max-loss snapshots while keeping
  all mutation flags false.
- 2026-06-23 read-only production profit evidence refresh ran
  `scripts/prepare_profit_operator_action_brief_ssh.ps1 -RequireReady` through
  SSH/server-local MCP and saved
  `target\profit-review\profit-operator-matrix-20260623T020707Z-BTCUSDT-strategy485.log`.
  The fresh action brief proved the matrix timeout boundary fix in production
  read-only mode: the nested matrix child used
  `source_matrix_timeout_seconds=3900`, completed with exit code `0`,
  `timedOut=false`, and elapsed `882` seconds instead of being killed at the
  inner-child timeout. The fresh matrix returned
  `profit_operator_review_matrix_status=HAS_REVIEW_READY_ITEMS_NOT_LIVE` and
  `profit_operator_action_brief_status=READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE`.
  The next review is the exit-side lane only:
  `REVIEW_EXIT_SIDE_TRAILING_AND_STRATEGY485_NOT_MUTATION`, with review-only
  proposals `trailing-stop-rollout-review` and
  `strategy485-risk-reduction-review`. `entry-filter` remains blocked by
  governance/missed-opportunity review (`signal_policy_clear=false` and
  `data_freshness_current_status=NO_CURRENT_SAMPLE`), and
  `data-freshness-replay` remains blocked by pre-replay-collector historical
  proxy evidence (`BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`,
  `counterfactual_evidence_class=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`,
  `complete_replayable_candidate_rows=0`, and
  `profit_evidence_watch_reason=NO_CURRENT_SAMPLE`). The fresh signal/missed
  blocker child exited `0` with
  `signal_missed_blocker_decision_brief_status=BLOCKED_SIGNAL_MISSED_GOVERNANCE_REVIEW`.
  This evidence is read-only routing only and does not authorize live trading,
  trailing scheduler enablement, position/OCO changes, EntryDedup/DataFreshness
  relaxation, production env changes, deploy, Telegram sends, or exchange
  mutation.
  Reused matrix output is freshness-guarded by
  `-MatrixMaxAgeMinutes` (default `180`) and fails closed with
  `matrix_freshness_status=STALE` when stale. The brief does
  not authorize live trading, policy relaxation, deploy, production env
  changes, trailing scheduler enablement, orders, OCO, position closes,
  DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import.
- 2026-06-23T23:20+08:00 read-only production exit-side operator decision refresh ran
  `scripts/prepare_exit_side_operator_decision_brief_ssh.ps1 -RequireDecisionReady`
  through SSH/server-local MCP. The brief returned
  `exit_side_operator_decision_brief_status=READY_FOR_OPERATOR_DECISION_NOT_MUTATION`
  with `exit_side_profit_review_packet_status=READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION`.
  The trailing-stop rollout review lane is ready for operator review only:
  `trailing_stop_acceptance=PASS`, `trailing_stop_improvement_pct=54.044%`,
  `trailing_stop_delta_pnl=12860.69161894`, `acceptanceRows=327`,
  `improved=170`, `worsened=198`, and `ambiguousSameBar=113` excluded from
  acceptance. The strategy 485 risk-reduction lane is ready for a separate
  non-mutating operator review with `strategy485_oco_health_ok=True`,
  `strategy485_negative_ev_position_count=3`, and
  `strategy485_close_or_modify_suggestion_count=3`; the read-only current
  position summaries are `#148 WATCH/CLOSE evUsdt=-0.54 paperPct=-6.85`,
  `#149 WATCH/CLOSE evUsdt=-0.53 paperPct=-6.79`, and
  `#150 WATCH/CLOSE evUsdt=-0.39 paperPct=-6.43`. The brief keeps
  entry-filter/DataFreshness policy explicitly out of scope and routes those
  blockers back to the profit operator action brief. This evidence does not
  authorize live trading, trailing scheduler enablement, strategy opt-in
  changes, position close, OCO modification/cancel, production env changes,
  deploy, orders, DB/grid/fund/Earn/Telegram/exchange mutation, or external
  backfill/import.
- `scripts/prepare_strategy485_risk_escalation_brief.ps1` packages the latest
  saved exit-side decision log into a local
  `STRATEGY485_RISK_ESCALATION_BRIEF`. It emits
  `strategy485_risk_escalation_brief_packet`,
  `strategy485_risk_escalation_brief_status`,
  `strategy485_severe_paper_loss_count`, `strategy485_total_ev_usdt`,
  `strategy485_worst_paper_pct`, `strategy485_avg_paper_pct`, and
  per-position `strategy485_position_risk_rows`, while keeping
  `close_position_allowed=false`, `position_or_oco_mutation_allowed=false`,
  `order_allowed=false`, `telegram_send_allowed=false`, and all live/deploy/env
  permissions false. This is an operator escalation brief only and does not
  authorize close-position, OCO modification/cancel, live trading, scheduler
  enablement, deploy, production env changes, Telegram sends, or
  DB/grid/fund/Earn/exchange mutation.
- 2026-06-23 local read-only consolidated profit operator packet refresh ran
  `scripts/prepare_profit_operator_consolidated_review_packet.ps1 -RequireReady`
  and saved the full output to
  `target\profit-review\profit-operator-consolidated-review-latest.log`. The
  packet returned `READY_FOR_OPERATOR_REVIEW_NOT_LIVE` with
  `sourcePacketStatus=READY_FOR_OPERATOR_REVIEW_PACKET_NOT_LIVE`,
  `sourceEntryDedupPacketStatus=READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE`,
  `sourceMatrixFreshnessStatus=FRESH`, `readyReviewItemCount=3`,
  `blockedPolicyLaneCount=2`, and no missing requirements. The three ready
  review-only items are `trailing-stop-dry-run-operator-review`,
  `strategy485-risk-reduction-shadow-operator-review`, and
  `entry-dedup-semantics-shadow-operator-review`; the blocked policy lanes
  remain `entry-filter` and `data-freshness-replay`. The packet keeps
  `live_policy_change_allowed=false`,
  `position_or_oco_mutation_allowed=false`, `deploy_or_env_change_allowed=false`,
  and `order_allowed=false`, so this is operator-review routing only, not
  permission to enable live trading or scheduler paths, close positions, modify
  OCO, place orders, relax EntryDedup/DataFreshness/live policy, deploy, change
  production env, mutate DB/grid/fund/Earn/Telegram/exchange state, or run
  external backfill/import.
- 2026-06-23 local read-only profit operator priority decision brief refresh
  ran `scripts/prepare_profit_operator_priority_decision_brief.ps1 -RequireReady`
  and saved the full output to
  `target\profit-review\profit-operator-priority-decision-brief-latest.log`.
  The brief returned `READY_FOR_OPERATOR_DECISION_NOT_LIVE` with
  `sourcePacketStatus=READY_FOR_OPERATOR_REVIEW_NOT_LIVE`,
  `matrixFreshness=FRESH`, `missingRequirements=[]`, and primary focus
  `trailing-stop-dry-run-operator-review`. The ranked operator review order is
  `1:trailing-stop-dry-run-operator-review`,
  `2:strategy485-risk-reduction-shadow-operator-review`, and
  `3:entry-dedup-semantics-shadow-operator-review`, while blocked lanes remain
  `entry-filter` and `data-freshness-replay`. The brief keeps
  `live_policy_change_allowed=false`,
  `position_or_oco_mutation_allowed=false`, `deploy_or_env_change_allowed=false`,
  and `order_allowed=false`, so it is operator-review ordering only, not
  permission to enable live trading or scheduler paths, close positions, modify
  OCO, place orders, relax EntryDedup/DataFreshness/live policy, deploy, change
  production env, mutate DB/grid/fund/Earn/Telegram/exchange state, or run
  external backfill/import.
- 2026-06-23 local read-only trailing-stop dry-run operator decision packet
  refresh ran
  `scripts/prepare_trailing_stop_dry_run_operator_decision_packet.ps1 -RequireReady`
  and saved the full output to
  `target\profit-review\trailing-stop-dry-run-operator-decision-packet-latest.log`.
  The packet returned
  `READY_FOR_TRAILING_DRY_RUN_OPERATOR_DECISION_NOT_LIVE` with
  `sourcePriorityPacketStatus=READY_FOR_OPERATOR_DECISION_NOT_LIVE`,
  `sourceExitSidePacketStatus=READY_FOR_OPERATOR_REVIEW_PACKET_NOT_LIVE`,
  `matrixFreshness=FRESH`, `missingRequirements=[]`, and primary focus
  `trailing-stop-dry-run-operator-review`. Blocked lanes remain
  `entry-filter` and `data-freshness-replay`. The packet keeps
  `scheduler_enablement_allowed=false`,
  `live_policy_change_allowed=false`,
  `position_or_oco_mutation_allowed=false`, `deploy_or_env_change_allowed=false`,
  and `order_allowed=false`, so it is a dry-run design review only, not
  permission to enable trailing, live trading, scheduler paths, close
  positions, modify OCO, place orders, relax EntryDedup/DataFreshness/live
  policy, deploy, change production env, mutate DB/grid/fund/Earn/Telegram/
  exchange state, or run external backfill/import.
- `scripts/prepare_trailing_stop_dry_run_activation_review_packet_ssh.ps1`
  packages the current production replay and runtime state needed before a
  separate trailing dry-run activation decision. It refreshes the hard trailing
  replay packet, live-readiness audit, and server-local `/api/mcp`
  `getTrailingStopStatus` / `getStrategyConfig` evidence, then emits
  `TRAILING_STOP_DRY_RUN_ACTIVATION_REVIEW_PACKET`,
  `trailing_stop_dry_run_activation_review_packet`, and
  `trailing_stop_dry_run_activation_status`. A status of
  `READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_REVIEW_NOT_APPLIED` means the only
  next executable step is a separate operator authorization for
  `TRAILING_STOP_ENABLED=true` and `TRAILING_STOP_DRY_RUN=true`, followed by
  deploy/restart and read-only verification only. The packet keeps
  `BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED` separate from env-diff review:
  if none of the reviewed strategies has `trailingStopEnabled=true`, the next
  step is a separate strategy opt-in authorization before requesting the global
  dry-run env diff. The packet keeps
  `trailing_stop_activation_allowed=false`,
  `scheduler_enablement_allowed=false`, `deploy_or_env_change_allowed=false`,
  `order_allowed=false`, `telegram_send_allowed=false`, and
  `position_or_oco_mutation_allowed=false`; it does not change production env,
  deploy, enable scheduler/live trading, place orders, modify OCO, close
  positions, relax policy, or mutate DB/grid/fund/Earn/Telegram/exchange state.
- `scripts/prepare_trailing_stop_strategy_opt_in_review_packet_ssh.ps1`
  packages that blocker into a separate read-only operator review packet before
  any strategy config write. It consumes the activation packet output, or a
  saved activation `-SourceLog`, and emits
  `TRAILING_STOP_STRATEGY_OPT_IN_REVIEW_PACKET`,
  `trailing_stop_strategy_opt_in_review_packet`, and
  `trailing_stop_strategy_opt_in_review_status`. A status of
  `READY_FOR_STRATEGY_TRAILING_OPT_IN_OPERATOR_REVIEW_NOT_MUTATION` means the
  current activation blocker is exactly missing `trailingStopEnabled` opt-in,
  the trailing replay acceptance is still `PASS`, global trailing remains
  disabled while dry-run remains true, and the packet can propose a separate
  `setTrailingStopOptIn(...)` write plus rollback write for operator approval.
  The packet keeps `trailing_stop_strategy_opt_in_change_allowed=false`,
  `production_env_change_allowed=false`, `deploy_allowed=false`,
  `scheduler_enablement_allowed=false`, `order_allowed=false`,
  `telegram_send_allowed=false`, and `position_or_oco_mutation_allowed=false`;
  it does not call `setTrailingStopOptIn`, change production env, deploy,
  restart, enable scheduler/live trading, place orders, modify OCO, close
  positions, relax policy, or mutate DB/grid/fund/Earn/Telegram/exchange state.
- `scripts/prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1`
  packages the next gate after a separately authorized strategy opt-in write.
  It reruns or consumes the activation packet and emits
  `TRAILING_STOP_POST_OPT_IN_READINESS_PACKET`,
  `trailing_stop_post_opt_in_readiness_packet`, and
  `trailing_stop_post_opt_in_readiness_status`. A status of
  `READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION` means
  the expected strategy, default `574`, has `trailingStopEnabled=true`, the
  trailing replay acceptance still passes, global trailing remains disabled,
  and dry-run remains true. The only next step is a separate operator decision
  for `TRAILING_STOP_ENABLED=true` and `TRAILING_STOP_DRY_RUN=true`, followed by
  deploy/restart and read-only verification. The packet keeps
  `production_env_change_allowed=false`, `deploy_allowed=false`,
  `scheduler_enablement_allowed=false`, `order_allowed=false`,
  `telegram_send_allowed=false`, and `position_or_oco_mutation_allowed=false`;
  it does not call `setTrailingStopOptIn`, change production env, deploy,
  restart, enable scheduler/live trading, place orders, modify OCO, close
  positions, relax policy, or mutate DB/grid/fund/Earn/Telegram/exchange state.
- `scripts/prepare_trailing_stop_dry_run_env_deploy_handoff_ssh.ps1` packages
  the exact dry-run env/deploy authorization request after post-opt-in
  readiness. It consumes the post-opt-in readiness packet and local git metadata
  and emits `TRAILING_STOP_DRY_RUN_ENV_DEPLOY_HANDOFF_PACKET`,
  `trailing_stop_dry_run_env_deploy_handoff_packet`, and
  `trailing_stop_dry_run_env_deploy_handoff_status`. A status of
  `READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DEPLOY_HANDOFF_NOT_MUTATION` means the
  operator can be asked for the exact env diff `TRAILING_STOP_ENABLED=true` and
  `TRAILING_STOP_DRY_RUN=true`, deploy/restart of current `origin/main`, and
  post-env read-only verification only. The packet prints the exact operator
  authorization text, deploy command, post-deploy verification list, and
  rollback plan (`TRAILING_STOP_ENABLED=false` while keeping
  `TRAILING_STOP_DRY_RUN=true`). It keeps `production_env_change_allowed=false`,
  `deploy_allowed=false`, `position_or_oco_mutation_allowed=false`,
  `order_allowed=false`, and `telegram_send_allowed=false`; it does not change
  production env, deploy, restart, set dry-run false, enable live OCO mutation,
  place orders, close positions, send Telegram, relax policy, or mutate
  DB/grid/fund/Earn/Telegram/exchange state.
- `scripts/prepare_local_tradingview_dry_run_receipt_env_handoff.ps1` packages
  the exact LOCAL_TRADINGVIEW dry-run receipt env/deploy authorization request.
  It consumes `smoke_local_tradingview_candidate_ssh.ps1` plus local git
  metadata and emits `LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_HANDOFF_PACKET`,
  `local_tradingview_dry_run_receipt_env_handoff_packet`, and
  `local_tradingview_dry_run_receipt_env_handoff_status`. A status of
  `READY_FOR_LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_HANDOFF_NOT_MUTATION` means
  the operator can be asked for the exact env state
  `TRADING_SIGNAL_SOURCE_PRIMARY=LOCAL_TRADINGVIEW`,
  `TRADINGVIEW_LOCAL_ENABLED=true`, and
  `TRADINGVIEW_LOCAL_EXECUTION_MODE=DRY_RUN` while keeping dry-run true and
  live-order false, followed by deploy/restart of current `origin/main` and
  post-env read-only verification. A current BUY candidate is not required for
  the env handoff; the post-env verifier is
  `smoke_local_tradingview_candidate_ssh.ps1 -RequireDryRunArmed`, with
  `-RequireCurrentCandidate -RequireDryRunArmed` reserved for a latest closed
  bar that actually has a parity BUY. The packet prints the exact operator
  authorization text, deploy command, verification list, and rollback plan. It
  keeps `production_env_change_allowed=false`, `deploy_allowed=false`,
  `live_order_mutation_allowed=false`, `oco_mutation_allowed=false`,
  `grid_mutation_allowed=false`, `db_mutation_allowed=false`,
  `exchange_mutation_allowed=false`, `order_allowed=false`, and
  `telegram_send_allowed=false`; it does not change production env, deploy,
  restart, switch to `LIVE_MICRO`, place orders, modify OCO, send Telegram, or
  mutate DB/grid/fund/Earn/exchange state.
- `scripts/prepare_local_tradingview_oco_lifecycle_env_handoff.ps1` packages
  the exact LOCAL_TRADINGVIEW LIVE_MICRO OCO lifecycle env/deploy authorization
  request. It consumes `smoke_local_tradingview_candidate_ssh.ps1` plus local
  git metadata and emits `LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_PACKET`,
  `local_tradingview_oco_lifecycle_env_handoff_packet`, and
  `local_tradingview_oco_lifecycle_env_handoff_status`. A status of
  `READY_FOR_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_NOT_MUTATION` means
  the operator can be asked for the exact OCO lifecycle authorization to set
  `TRADING_OCO_POLLER_ENABLED=true` while keeping
  `POSITION_EXIT_MANAGER_ENABLED=false`, followed by deploy/restart of current
  `origin/main` and post-env read-only verification. A current BUY candidate is
  not required for the env handoff; the post-env verifier is
  `smoke_local_tradingview_candidate_ssh.ps1 -RequireLiveMicroArmed -RequireOcoLifecycleTracked`,
  plus strategy485 position-risk, live-readiness audit, and live-readiness
  bundle read-only checks. If OCO health reports `SYNC_ERROR`, use
  `prepare_oco_sync_reconciliation_packet_ssh.ps1` before any separate
  reconciliation write request. The packet prints the exact OCO lifecycle
  authorization text, deploy command, verification list, and rollback plan. It
  keeps `local_tradingview_oco_lifecycle_env_request_allowed=false`,
  `production_env_change_allowed=false`, `deploy_allowed=false`,
  `live_order_mutation_allowed=false`, `oco_mutation_allowed=false`,
  `position_mutation_allowed=false`, `grid_mutation_allowed=false`,
  `db_mutation_allowed=false`, `exchange_mutation_allowed=false`,
  `order_allowed=false`, and `telegram_send_allowed=false`; it does not change
  production env, deploy, restart, enable position-exit manager, place orders,
  create/rebalance grid, send Telegram, or mutate DB/grid/fund/Earn/exchange
  state.
- `scripts/execute_trailing_stop_strategy_opt_in_ssh.ps1` provides the
  controlled execution path for that blocker. Its default mode is non-mutating
  and emits `TRAILING_STOP_STRATEGY_OPT_IN_EXECUTION_PACKET`,
  `trailing_stop_strategy_opt_in_execution_packet`, and
  `trailing_stop_strategy_opt_in_execution_status=DRY_RUN_READY_FOR_SEPARATE_EXECUTION_AUTHORIZATION_NOT_MUTATION`.
  The only write path requires `-Execute -ConfirmText
  EXECUTE_TRAILING_STOP_OPT_IN_574`, calls server-local `/api/mcp`
  `setTrailingStopOptIn(strategyId=574, enabled=true, ...)`, then reruns the
  post-opt-in readiness packet. A successful execution reaches
  `EXECUTED_POST_OPT_IN_READY_FOR_ENV_DIFF_REVIEW`, which still only permits a
  separate review for `TRAILING_STOP_ENABLED=true` and
  `TRAILING_STOP_DRY_RUN=true`. The wrapper does not change production env,
  deploy, restart, enable scheduler/live trading, place orders, modify OCO,
  close positions, send Telegram, relax policy, or mutate grid/fund/Earn/
  exchange state. The same wrapper also provides controlled rollback with
  `-Rollback -Execute -ConfirmText ROLLBACK_TRAILING_STOP_OPT_IN_574`, which
  only sets `trailingStopEnabled=false` after a dry-run check reaches
  `ROLLBACK_DRY_RUN_READY_FOR_SEPARATE_EXECUTION_AUTHORIZATION_NOT_MUTATION`.
- `scripts/prepare_profit_next_execution_blocker_packet.ps1` converts the
  current make-money goal into a replayable next-execution blocker packet. It
  emits `PROFIT_NEXT_EXECUTION_BLOCKER_PACKET`,
  `profit_next_execution_blocker_packet`,
  `profit_next_execution_goal_satisfied=false`,
  `profit_next_execution_route=TRAILING_STOP_STRATEGY574_OPT_IN`,
  `profit_next_execution_unique_blocker`, and
  `profit_next_execution_exact_unlock_command`. A
  `BLOCKED_AWAIT_EXPLICIT_EXECUTE_CONFIRMATION` status means the quantified
  trailing-stop opt-in route is still the nearest profit-improvement path, but
  the controlled `setTrailingStopOptIn` write still requires a separate explicit
  confirmation. Once the strategy opt-in is applied, the packet advances to
  `BLOCKED_AWAIT_SEPARATE_TRAILING_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION`,
  preserving that the next step is only the separate
  `TRAILING_STOP_ENABLED=true` plus `TRAILING_STOP_DRY_RUN=true` env/deploy
  review with post-env read-only verification. Its
  `profit_next_execution_exact_unlock_command` points to the read-only
  `prepare_trailing_stop_dry_run_env_deploy_handoff_ssh.ps1 -RequireReady`
  command so the exact operator authorization text, rollback plan, and
  verification list are generated before any env/deploy action. The packet also
  handles the post-deploy active dry-run state: once
  `TRAILING_STOP_ENABLED=true` and `TRAILING_STOP_DRY_RUN=true` are deployed,
  it reports `profit_next_execution_route=TRAILING_STOP_DRY_RUN_OBSERVATION`
  and
  `profit_next_execution_blocker_status=TRAILING_DRY_RUN_ACTIVE_READ_ONLY_OBSERVATION`.
  When the observation-status packet is available, it also surfaces
  `profit_next_execution_observation_status` and can refine
  `profit_next_execution_unique_blocker` to `NO_OPEN_OCO_POSITIONS`. That is the
  observation phase, not live OCO mutation approval. The packet also
  records why Strategy574/TinyLive relaxation, DataFreshness entry-policy
  relaxation, Strategy485 position mutation, and general live-policy relaxation
  are not the next recommended route. It is read-only and does not execute the
  opt-in write, deploy, change production env, enable scheduler/live trading,
  place orders, modify OCO, close positions, send Telegram, relax policy, or mutate
  DB/grid/fund/Earn/exchange state.
- `scripts/watch_profit_next_execution_readiness_ssh.ps1` automates the
  read-only replay loop for the packet above. It records per-attempt
  DataFreshness readiness and next-execution blocker logs under
  `target/profit-review`, refreshes
  `profit-next-execution-blocker-packet-latest.log`, then reports whether the
  lane is
  `PENDING_OPEN_OCO_SAMPLE`, `PENDING_TRAILING_OPT_IN_EVIDENCE`,
  `PENDING_DATAFRESHNESS_REPLAY_EVIDENCE`, or
  `EVIDENCE_READY_FOR_OPERATOR_REVIEW_NOT_LIVE`.
- `scripts/prepare_trailing_stop_dry_run_observation_status_ssh.ps1` turns the
  active A0 dry-run state into a replayable read-only observation packet. It
  emits `TRAILING_STOP_DRY_RUN_OBSERVATION_STATUS_PACKET`,
  `trailing_stop_dry_run_observation_status`, and explicit sample readiness
  fields. `ACTIVE_WAITING_FOR_OPEN_OCO_SAMPLE` with
  `NO_OPEN_OCO_POSITIONS` means A0 is active and safe to observe, but no real
  dry-run sample exists yet; it is not approval for live OCO mutation. A
  2026-06-30 production read-only run reported
  `trailing_stop_dry_run_observation_status=ACTIVE_WAITING_FOR_OPEN_OCO_SAMPLE`,
  `trailing_stop_improvement_pct=56.299%`, and
  `trailing_stop_dry_run_observation_current_open_oco_positions=0`.
- 2026-06-23 local read-only strategy485 risk-reduction operator decision
  packet refresh ran
  `scripts/prepare_strategy485_risk_reduction_operator_decision_packet.ps1 -RequireReady`
  and saved the full output to
  `target\profit-review\strategy485-risk-reduction-operator-decision-packet-latest.log`.
  The packet returned
  `READY_FOR_STRATEGY485_RISK_REDUCTION_OPERATOR_DECISION_NOT_MUTATION` with
  `sourcePriorityPacketStatus=READY_FOR_OPERATOR_DECISION_NOT_LIVE`,
  `sourceExitSidePacketStatus=READY_FOR_OPERATOR_REVIEW_PACKET_NOT_LIVE`,
  `matrixFreshness=FRESH`, `priorityRank=2`, and no missing requirements.
  Blocked lanes remain `entry-filter` and `data-freshness-replay`. The packet
  keeps `close_position_allowed=false`,
  `position_or_oco_mutation_allowed=false`,
  `live_policy_change_allowed=false`, `scheduler_enablement_allowed=false`,
  `deploy_or_env_change_allowed=false`, and `order_allowed=false`, so it is a
  shadow risk-reduction review only, not permission to close positions,
  modify/cancel OCO, place orders, enable live trading, scheduler paths, relax
  EntryDedup/DataFreshness/live policy, deploy, change production env, mutate
  DB/grid/fund/Earn/Telegram/exchange state, or run external backfill/import.
- 2026-06-23T23:22+08:00 read-only TP/SL/OCO feasibility operator packet refreshed
  exit-side evidence with
  `scripts/prepare_exit_side_operator_decision_brief_ssh.ps1 -RequireDecisionReady`
  and then ran
  `scripts/prepare_tp_sl_oco_feasibility_operator_packet.ps1 -RequireReady`.
  The SSH refresh wrote
  `target\profit-review\exit-side-operator-decision-brief-refresh.log` and made
  no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler,
  exchange, deploy, restart, or nginx change. It returned
  `exit_side_operator_decision_brief_status=READY_FOR_OPERATOR_DECISION_NOT_MUTATION`,
  `exit_side_profit_review_packet_status=READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION`,
  `trailing_stop_acceptance=PASS`,
  `trailing_stop_improvement_pct=54.044%`,
  `strategy485_oco_health_ok=True`, and
  `strategy485_negative_ev_position_count=3`; the current position summaries
  are `#148 WATCH/CLOSE evUsdt=-0.54 paperPct=-6.85`,
  `#149 WATCH/CLOSE evUsdt=-0.53 paperPct=-6.79`, and
  `#150 WATCH/CLOSE evUsdt=-0.39 paperPct=-6.43`. The local packet saved
  `target\profit-review\tp-sl-oco-feasibility-operator-packet-latest.log` and
  returned
  `READY_FOR_TP_SL_OCO_FEASIBILITY_OPERATOR_REVIEW_NOT_MUTATION` with
  `tp_sl_oco_feasibility_primary_decision=PREPARE_SEPARATE_TP_SL_OCO_FEASIBILITY_REVIEW`.
  It packages trailing TP/SL risk-reduction evidence and strategy 485
  OCO-protected negative-EV position evidence into one operator packet while
  keeping `close_position_allowed=false`,
  `position_or_oco_mutation_allowed=false`,
  `deploy_or_env_change_allowed=false`, and `order_allowed=false`; it is not
  permission to enable live trading, enable scheduler mutation, place orders,
  close positions, modify/cancel OCO, deploy, change production env, relax
  EntryDedup/DataFreshness/live policy, or mutate DB/grid/fund/Earn/Telegram/
  exchange/external backfill state.
- `scripts/prepare_tp_sl_oco_feasibility_preflight_review_packet.ps1` wraps the
  local TP/SL/OCO feasibility operator packet into
  `TP_SL_OCO_FEASIBILITY_PREFLIGHT_REVIEW_PACKET`. It emits
  `tp_sl_oco_feasibility_preflight_review_packet`,
  `tp_sl_oco_feasibility_preflight_status`, and
  `tp_sl_oco_feasibility_preflight_decision`. It keeps
  `close_position_allowed=false`, `position_or_oco_mutation_allowed=false`,
  `scheduler_enablement_allowed=false`, `deploy_or_env_change_allowed=false`,
  `order_allowed=false`, and `telegram_send_allowed=false`; it is a
  review-only preflight and does not authorize orders, close-position, OCO
  modification/cancelation, live trading, scheduler enablement, deploy,
  production env changes, EntryDedup/DataFreshness/live policy relaxation, or
  DB/grid/fund/Earn/Telegram/exchange/external backfill mutation.
- 2026-06-23T13:11+08:00 local read-only TP/SL/OCO feasibility preflight ran
  `scripts/prepare_tp_sl_oco_feasibility_preflight_review_packet.ps1 -RequireReady`
  and saved
  `target\profit-review\tp-sl-oco-feasibility-preflight-review-packet-latest.log`.
  It returned
  `READY_FOR_TP_SL_OCO_FEASIBILITY_PREFLIGHT_REVIEW_NOT_MUTATION` with
  source packet status
  `READY_FOR_TP_SL_OCO_FEASIBILITY_OPERATOR_REVIEW_NOT_MUTATION`, source
  freshness `FRESH`, `trailing_stop_acceptance=PASS`,
  `strategy485_oco_health_ok=True`, and
  `strategy485_negative_ev_position_count=3`. The packet kept
  `close_position_allowed=false`, `position_or_oco_mutation_allowed=false`,
  `scheduler_enablement_allowed=false`, `deploy_or_env_change_allowed=false`,
  `order_allowed=false`, and `telegram_send_allowed=false`; it is review-only
  and not live approval or permission to place orders, close positions,
  modify/cancel OCO, send Telegram, deploy, change production env, enable
  scheduler/live paths, or relax trading policy.
- 2026-06-23 local read-only EntryDedup semantics operator decision packet
  refresh ran
  `scripts/prepare_entry_dedup_semantics_operator_decision_packet.ps1 -RequireReady`
  and saved the full output to
  `target\profit-review\entry-dedup-semantics-operator-decision-packet-latest.log`.
  The packet returned
  `READY_FOR_ENTRY_DEDUP_SEMANTICS_OPERATOR_DECISION_NOT_LIVE` with
  `sourcePriorityPacketStatus=READY_FOR_OPERATOR_DECISION_NOT_LIVE`,
  `sourceEntryDedupPacketStatus=READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE`,
  `matrixFreshness=FRESH`, `priorityRank=3`, and no missing requirements.
  Blocked lanes remain `entry-filter` and `data-freshness-replay`. The packet
  keeps `entry_dedup_policy_change_allowed=false`,
  `data_freshness_policy_change_allowed=false`,
  `live_policy_change_allowed=false`, `scheduler_enablement_allowed=false`,
  `position_or_oco_mutation_allowed=false`, `deploy_or_env_change_allowed=false`,
  and `order_allowed=false`, so it is shadow-only EntryDedup semantics review,
  not permission to relax EntryDedup/DataFreshness/live policy, enable
  staged-add/live execution, place orders, modify OCO, deploy, change
  production env, mutate DB/grid/fund/Earn/Telegram/exchange state, or run
  external backfill/import.
- `scripts/prepare_entry_dedup_semantics_preflight_review_packet.ps1` wraps the
  local EntryDedup semantics operator decision packet into
  `ENTRY_DEDUP_SEMANTICS_PREFLIGHT_REVIEW_PACKET`. It emits
  `entry_dedup_semantics_preflight_review_packet`,
  `entry_dedup_semantics_preflight_status`, and
  `entry_dedup_semantics_preflight_decision`. It keeps
  `entry_dedup_policy_change_allowed=false`,
  `data_freshness_policy_change_allowed=false`,
  `staged_add_execution_allowed=false`,
  `scheduler_enablement_allowed=false`,
  `deploy_or_env_change_allowed=false`, `order_allowed=false`, and
  `telegram_send_allowed=false`; it is a review-only preflight and does not
  authorize EntryDedup/DataFreshness/live policy relaxation, staged-add/live
  execution, orders, OCO modification/cancelation, deploy, production env
  changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external
  backfill/import.
- 2026-06-23 local read-only DataFreshness replay blocker operator decision
  packet refresh ran
  `scripts/prepare_data_freshness_replay_blocker_decision_packet.ps1 -RequireBlocked`
  and saved the full output to
  `target\profit-review\data-freshness-replay-blocker-decision-packet-latest.log`.
  It reused the latest freshness-guarded profit operator matrix and returned
  `READY_FOR_DATAFRESHNESS_REPLAY_BLOCKER_OPERATOR_DECISION_NOT_LIVE` with
  `sourceMatrixFreshnessStatus=FRESH`,
  `data_freshness_replay_lane_status=BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`,
  `counterfactual_evidence_class=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`,
  `replay_input_stage=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`,
  `complete_replayable_candidate_rows=0`,
  `shadow_candidate_review_allowed=false`, and no missing blocker-packet
  requirements. The packet is a wait/refresh decision for the blocked
  DataFreshness replay lane, not shadow-review readiness. It keeps
  `data_freshness_policy_relaxation_allowed=false`,
  `live_policy_change_allowed=false`, `scheduler_enablement_allowed=false`,
  `position_or_oco_mutation_allowed=false`, `deploy_or_env_change_allowed=false`,
  and `order_allowed=false`, so it does not authorize DataFreshnessGuard
  relaxation, live/staged-add/tiny-live execution, orders, OCO changes, deploy,
  production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, or
  external backfill/import.
- `scripts/prepare_data_freshness_replay_blocker_preflight_review_packet.ps1`
  wraps the DataFreshness replay blocker decision packet into a local
  review-only preflight gate. It emits
  `data_freshness_replay_blocker_preflight_review_packet`,
  `data_freshness_replay_blocker_preflight_status`, and
  `data_freshness_replay_blocker_preflight_decision`. It invokes only the local
  blocker decision packet, requires the source lane to remain blocked, requires
  `complete_replayable_candidate_rows=0` and
  `shadow_candidate_review_allowed=false`, and keeps
  `data_freshness_policy_relaxation_allowed=false`,
  `data_freshness_shadow_review_allowed=false`,
  `collector_activation_allowed=false`, `staged_add_execution_allowed=false`,
  `tiny_live_execution_allowed=false`, `order_allowed=false`, and
  `telegram_send_allowed=false`. It is a wait/refresh blocker preflight only;
  it does not authorize DataFreshnessGuard relaxation, DataFreshness shadow
  review, collector activation, live/staged-add/tiny-live execution, scheduler
  enablement, orders, OCO mutation, deploy, production env changes,
  DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import.
- 2026-06-23 read-only DataFreshness replay evidence refresh plus local
  collector activation decision packet ran
  `scripts/prepare_data_freshness_replay_evidence_readiness_ssh.ps1 -RequireActionable`
  and then
  `scripts/prepare_data_freshness_replay_collector_activation_packet.ps1 -RequireDecisionReady`.
  The SSH refresh wrote
  `target\profit-review\data-freshness-replay-evidence-readiness-refresh.log`
  and remained read-only: no production env, DB, order, OCO, grid, fund, Earn,
  Telegram, scheduler, exchange, deploy, restart, or nginx change. It returned
  `data_freshness_replay_evidence_readiness_status=PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS`,
  `data_freshness_replay_candidate_id_recommendation=PENDING_NO_NEW_DATAFRESHNESS_ROWS`,
  `latest_data_freshness_row_time=2026-06-14T15:38:16`,
  `data_freshness_rows_1d=0`, `data_freshness_rows_3d=0`,
  `replay_input_stage=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`, and
  `complete_replayable_candidate_rows=0`. The local packet saved
  `target\profit-review\data-freshness-replay-collector-activation-packet-latest.log`
  and returned
  `READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_OPERATOR_DECISION_NOT_LIVE`
  with
  `collector_activation_operator_decision=PREPARE_EVIDENCE_ONLY_COLLECTOR_ACTIVATION_REVIEW`.
  This turns the repeated replay blocker into a concrete next operator
  question: whether to separately authorize an evidence-only collector
  activation such as
  `TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true`. The packet keeps
  `collector_activation_allowed=false`,
  `deploy_or_env_change_allowed=false`,
  `data_freshness_policy_relaxation_allowed=false`, and `order_allowed=false`;
  it is not permission to deploy, change production env, relax
  DataFreshnessGuard, enable live/staged-add/tiny-live execution, enable
  scheduler mutation, send Telegram, place orders, modify OCO, or mutate
  DB/grid/fund/Earn/exchange/external backfill state.
- `scripts/prepare_data_freshness_collector_activation_preflight_review_packet.ps1`
  wraps the collector activation decision packet into a local review-only
  preflight gate. It emits
  `data_freshness_collector_activation_preflight_review_packet`,
  `data_freshness_collector_activation_preflight_status`, and
  `data_freshness_collector_activation_preflight_decision`. It invokes only the
  local collector activation decision packet and keeps
  `evidence_only_collector_review_allowed=true`,
  `collector_activation_allowed=false`,
  `deploy_or_env_change_allowed=false`,
  `data_freshness_policy_relaxation_allowed=false`,
  `data_freshness_shadow_review_allowed=false`, `order_allowed=false`, and
  `telegram_send_allowed=false`. It is evidence-only activation preflight, not
  authorization to activate the collector, deploy, change production env, relax
  DataFreshnessGuard, allow DataFreshness shadow review, enable live/staged-add/
  tiny-live execution, enable scheduler mutation, send Telegram, place orders,
  modify OCO, or mutate DB/grid/fund/Earn/exchange/external backfill state.
- 2026-06-22 read-only production profit operator matrix refresh reran
  `scripts/prepare_profit_operator_review_matrix_ssh.ps1 -ReplayDays 30
  -ReplayLimit 200` through SSH. All four child scripts exited `0`, including
  the newly attached `prepare_data_freshness_shadow_candidate_packet_ssh.ps1`.
  The fresh matrix returned `profit_operator_review_matrix_status=NO_REVIEW_READY_ITEMS`:
  `exit-side` was `NOT_READY`, `entry-filter` was
  `BLOCKED_GOVERNANCE_MISSED_OPPORTUNITY_REVIEW`, and
  `data-freshness-replay` now surfaced
  `data_freshness_shadow_candidate_packet_status=BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`
  with `counterfactual_evidence_class=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`,
  `shadow_candidate_review_allowed=false`, `complete_replayable_candidate_rows=0`,
  and `replay_input_next_action=wait_for_new_replay_id_rows_before_shadow_review`.
  This is read-only evidence only: the historical proxy blocker is visible in
  the operator overview, but it still does not authorize DataFreshnessGuard
  relaxation, live trading, deploy, production env changes, orders, OCO,
  scheduler changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external
  backfill/import.
- `scripts/smoke_data_freshness_false_kill_review_ssh.ps1` provides a focused
  read-only production review for the DataFreshnessGuard false-kill profit
  candidate. It calls server-local `/api/mcp` for short/review/long
  DataFreshnessGuard RCA windows, blocked-signal outcomes, governance drift,
  relaxation candidates, missed-opportunity regression, and no-buy truth-table
  evidence. It prints `currentDataFreshnessClean`, `historicalStaleOnly`,
  `data_freshness_shadow_replay_plan`, and
  `data_freshness_false_kill_recommendation` such as
  `REVIEW_COLLECTOR_CADENCE_SHADOW_REPLAY_KEEP_HARD_GATE`. The smoke is
  evidence only and does not authorize DataFreshnessGuard relaxation, live
  trading, policy changes, strategy activation, closing positions, OCO
  modification, production env changes, DB, order, grid, Earn, fund, Telegram,
  scheduler, exchange, external backfill/import, deploy, restart, or nginx
  state. `scripts/test_data_freshness_false_kill_review_smoke.ps1` guards the
  read-only MCP calls, hard-fail boundary checks, output markers, docs
  coverage, and non-authorization wording.
- `scripts/smoke_data_freshness_executability_review_ssh.ps1` provides a
  focused read-only production review for whether the DataFreshness false-kill
  alpha evidence is also executable evidence. It calls server-local `/api/mcp`
  for the historical DataFreshness decision window, current autonomous
  readiness, runtime evidence rows, TinyLive current preview, and autonomous
  opportunity readiness. It prints `missing_executability_evidence`,
  `counterfactual_required_evidence`, and
  `data_freshness_executability_recommendation` such as
  `ALPHA_NOT_EXECUTABILITY_PROVEN_COLLECT_SHADOW_REPLAY`, so +24h false-kill
  return cannot be mistaken for live-tradable profit without EV/OCO/daily-cap/
  duplicate/exposure/event-risk counterfactual proof. The smoke is evidence
  only and does not authorize DataFreshnessGuard relaxation, live trading,
  policy changes, strategy activation, closing positions, OCO modification,
  production env changes, DB, order, grid, Earn, fund, Telegram, scheduler,
  exchange, external backfill/import, deploy, restart, or nginx state.
  `scripts/test_data_freshness_executability_review_smoke.ps1` guards the
  read-only MCP calls, hard-fail boundary checks, output markers, docs
  coverage, and non-authorization wording.
- `scripts/smoke_data_freshness_counterfactual_review_ssh.ps1` provides the
  next read-only evidence layer after executability review: it uses production
  MySQL `SELECT` queries to inspect DataFreshnessGuard `bt_decision_audit`
  rows, linked `bt_runtime_decision_evidence`, and OKX `md_kline` forward
  windows. It prints replay-input coverage markers including
  `complete_replayable_candidate_rows`, `missing_counterfactual_fields`,
  `preview_only_input_rows`, `preview_only_missing_counterfactual_fields`,
  `replay_input_stage`, `collector_status_counts`,
  `hard_gate_preview_status_counts`, `replay_input_next_action`,
  `preview_only_note`, `positive_forward_24h_rows`, `avg_forward_24h_pct`, and
  `data_freshness_counterfactual_recommendation`. A result such as
  `COUNTERFACTUAL_NOT_REPLAYABLE_CANDIDATE_SNAPSHOT_MISSING` means the
  historical alpha proxy still cannot justify DataFreshness policy relaxation
  because liveSignal/candidate plan/EV/OCO/hard-gate snapshots are missing.
  `PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`, `COLLECTOR_DISABLED_TRACE_ONLY`,
  and `PREVIEW_ONLY_NOT_REPLAYABLE` keep the replay-input stage explicit before
  any shadow review is drafted.
  Preview-only rows prove placeholder field presence and terminal-block
  traceability only; they do not count as evaluated EV/OCO/risk pass evidence
  or `complete_replayable_candidate_rows`.
  The smoke is evidence only and does not change production env, DB, order,
  OCO, grid, Earn, fund, Telegram, scheduler, exchange, external
  backfill/import, deploy, restart, or nginx state.
  `scripts/test_data_freshness_counterfactual_review_smoke.ps1` guards the
  direct-SELECT boundary, marker contract, docs coverage, and
  non-authorization wording.
- `scripts/smoke_filter_block_false_kill_issue7_ssh.ps1` provides the issue #7
  focused read-only production review for `BTCUSDT` `1h` `FILTER_BLOCK`
  false-kill pressure. It uses direct MySQL `SELECT` queries only, ranks blocker
  families by false-kill rows and average 24h forward return, then emits an
  `Actionable False-Kill Summary` that excludes DataFreshness severe-stale/
  outage rows from the policy-error denominator, reports
  `severe_stale_outage_rows_excluded`, `severe_stale_outage_incidents`,
  `actionable_filter_block_false_kill_pct`, and an
  `Actionable False-Kill Source Ranking`, then surfaces a DataFreshnessGuard RCA
  with stale-context class counts, runtime evidence
  coverage, liveSignal/replayCandidateId coverage, entry/TP/SL, EV, OCO, and
  hard-gate snapshot coverage, `data_freshness_preview_only_input_rows`,
  `data_freshness_trace_only_rows`, `replay_input_stage`,
  `collector_status_counts`, stale-severity markers
  `data_freshness_stale_minutes_min`, `data_freshness_stale_minutes_avg`,
  `data_freshness_stale_minutes_max`, `data_freshness_near_miss_rows`,
  `data_freshness_recoverable_grace_rows`, and
  `data_freshness_severe_stale_rows`, plus candidate examples containing entry,
  block reason, forward return, `shouldHavePassedProxy`, collector stage, and
  missing replay fields. It also emits a `DataFreshness Guard Optimization
  Counterfactual` section that reports candidate stale-threshold grace
  `releaseRows`, `falseKillReleased`, `correctBlockReleased`, and
  `data_freshness_guard_optimization_verdict`, so severe stale/outage rows are
  not mistaken for safely relaxable near-miss rows. The conclusion also prints
  `issue7_actionable_next_blocker`, so after outage pressure is separated, the
  next non-outage blocker family can be optimized without treating repeated
  stale-source audits as policy false-kill rows. When that blocker is
  `ExpectedValueGate`, the smoke emits `ExpectedValueGate Optimization
  Counterfactual`, `expected_value_gate_optimization_verdict`, and
  `issue7_expected_value_gate_verdict`, comparing candidate `minExpectedR`
  thresholds by released false-kill rows and leaked correct-block rows before
  any EV-gate policy review. It also emits a `TP/SL Proxy Actionable Summary`
  with `tp_sl_proxy_clean_tp_false_kill_pct`, `tp_sl_proxy_verdict`, and
  `issue7_tp_sl_proxy_verdict`, so 24h close-positive proxy rows are separated
  from rows that actually hit TP without touching SL in the available OHLC
  window. The EV packet fields include
  `expected_value_projected_actionable_false_kill_pct_after_review`, which
  estimates the remaining non-EV blocker error rate after a shadow-reviewed EV
  threshold candidate is removed from the actionable queue, and
  `expected_value_projected_next_blocker_after_review`, which points the next
  lane to optimize.
  `shouldHavePassedProxy` is explicitly a historical forward-return proxy, not
  a live pass verdict. A status such as
  `DATAFRESHNESS_FALSE_KILL_PROXY_HIGH_BUT_REPLAY_SNAPSHOTS_MISSING` keeps live
  DataFreshness relaxation blocked until complete replayable rows exist.
  The smoke is evidence only and does not change production env, DB, order,
  OCO, grid, Earn, fund, Telegram, scheduler, exchange, external
  backfill/import, deploy, restart, or nginx state.
  `scripts/test_filter_block_false_kill_issue7_smoke.ps1` guards the marker
  contract, docs coverage, SSH/remote input safety, database allowlist, and
  non-authorization wording.
- `scripts/prepare_filter_block_false_kill_issue7_packet.ps1` converts the
  saved issue #7 smoke log into `issue7_filter_block_false_kill_packet`,
  `issue7_filter_block_false_kill_status`, missing requirements,
  `issue7_filter_block_false_kill_review_allowed`, and
  `issue7_live_relaxation_allowed=false`. `-RequireBlocked` fails closed if the
  packet unexpectedly becomes review-ready while complete replayable
  DataFreshness rows are still the expected blocker. It now separates
  `BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`,
  `BLOCKED_COLLECTOR_TRACE_ONLY_REPLAY_SNAPSHOTS_MISSING`,
  `BLOCKED_PREVIEW_ONLY_REPLAY_SNAPSHOTS_NOT_EVALUATED`, and
  `BLOCKED_DATAFRESHNESS_REPLAY_SNAPSHOTS_MISSING`. It is local packet
  generation only and does not authorize DataFreshnessGuard relaxation, live
  trading, scheduler enablement, orders, OCO modification, deploy, production
  env changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external
  backfill/import. `scripts/test_filter_block_false_kill_issue7_packet.ps1`
  guards the marker contract, stale/missing-log handling, docs coverage, and
  blocked-status classification.
- `scripts/prepare_filter_block_false_kill_issue7_close_readiness.ps1` combines
  the issue #7 packet with the DataFreshness replay observation bundle into
  `ISSUE7_CLOSE_READINESS_PACKET`, `issue7_close_readiness_status`,
  `issue7_close_allowed`, and `issue7_close_missing_requirements`. It allows
  issue closure only when stable `replayCandidateId` rows, complete replayable
  candidate snapshots, and `missing_counterfactual_fields=[]` are present;
  otherwise it emits `BLOCKED_NOT_CLOSABLE_REPLAY_EVIDENCE_MISSING` and keeps
  `issue7_live_relaxation_allowed=false`. `scripts/test_filter_block_false_kill_issue7_close_readiness.ps1`
  covers both blocked and ready synthetic samples and is included in
  `scripts/verify_local.ps1`.
- `scripts/prepare_filter_block_false_kill_issue7_operator_handoff.ps1`
  combines issue #7 close-readiness with the DataFreshness collector activation
  preflight packet into `ISSUE7_OPERATOR_HANDOFF_PACKET`,
  `issue7_operator_handoff_status`, and `issue7_operator_handoff_decision`.
  It distinguishes `READY_TO_CLOSE_NOT_LIVE_RELAXATION` from
  `READY_FOR_EVIDENCE_COLLECTOR_REVIEW_NOT_CLOSEABLE`, so #7 can stay open
  while the next separately authorized evidence-only collector activation
  review is prepared. It keeps `collector_activation_allowed=false`,
  `deploy_or_env_change_allowed=false`, `order_allowed=false`, and
  `issue7_live_relaxation_allowed=false`. `scripts/test_filter_block_false_kill_issue7_operator_handoff.ps1`
  is included in `scripts/verify_local.ps1`.
- `scripts/prepare_filter_block_false_kill_issue7_collector_activation_review_packet.ps1`
  converts that handoff into
  `ISSUE7_EVIDENCE_COLLECTOR_ACTIVATION_REVIEW_PACKET`,
  `issue7_evidence_collector_activation_review_status`, and
  `issue7_evidence_collector_activation_review_decision`. It records the only
  separately reviewable env diff
  `TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true`, required
  disabled trading/execution flags, post-change read-only verification, issue
  close evidence requirements, and stop conditions. It keeps
  `collector_activation_allowed=false`, `deploy_or_env_change_allowed=false`,
  `order_allowed=false`, `telegram_send_allowed=false`, and
  `issue7_live_relaxation_allowed=false`. `scripts/test_filter_block_false_kill_issue7_collector_activation_review_packet.ps1`
  is included in `scripts/verify_local.ps1`.
- `scripts/prepare_filter_block_false_kill_issue7_collector_post_activation_status.ps1`
  is the read-only post-rollout gate for issue #7 after an evidence-only
  collector activation has already been separately authorized. It reuses the
  close-readiness packet plus the replay evidence readiness log and optional
  runtime env/log summary, then emits
  `ISSUE7_COLLECTOR_POST_ACTIVATION_STATUS_PACKET`,
  `issue7_collector_post_activation_status`, and `issue7_remaining_blocker`.
  If local HEAD or the replay evidence runtime is ahead of production it emits
  `BLOCKED_DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE` with
  `DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE`, routing the next step to a
  separately authorized push/deploy plus read-only replay observation refresh.
  The expected blocked state while no fresh post-collector DataFreshnessGuard
  rows exist is `BLOCKED_WAITING_FOR_FRESH_DATAFRESHNESS_ROWS` with
  `NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS`; it keeps
  `issue7_close_allowed=false` and `issue7_live_relaxation_allowed=false`.
  `scripts/test_filter_block_false_kill_issue7_collector_post_activation_status.ps1`
  is included in `scripts/verify_local.ps1`.
- `scripts/prepare_filter_block_false_kill_issue7_push_deploy_handoff.ps1`
  packages the issue #7 deploy-currentness blocker into a read-only operator
  handoff when the post-activation packet reports
  `DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE`. It emits
  `ISSUE7_PUSH_DEPLOY_HANDOFF_PACKET`,
  `issue7_push_deploy_handoff_status`, local/origin commit state, and the
  required read-only post-deploy verification list. A
  `READY_FOR_PUSH_DEPLOY_AUTHORIZATION_NOT_DEPLOYED` status is only a request
  for separate push/deploy authorization; the packet does not push, deploy,
  restart, change production env, close #7, relax DataFreshnessGuard, enable
  live/staged-add/TinyLive execution, enable scheduler mutation, place orders,
  modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange state.
  `scripts/test_filter_block_false_kill_issue7_push_deploy_handoff.ps1` is
  included in `scripts/verify_local.ps1`.
- `scripts/smoke_filter_block_false_kill_issue7_post_deploy_read_only_bundle_ssh.ps1`
  is the replayable issue #7 post-deploy verification bundle to run only after
  a separately authorized push/deploy. It emits
  `issue7_post_deploy_read_only_bundle_plan` and
  `issue7_post_deploy_read_only_bundle_status`, refreshes split acceptance, the
  issue #7 filter-block source log, DataFreshness replay-id evidence, replay
  observation, replay evidence readiness, the runtime evidence-only env smoke
  `scripts/smoke_filter_block_false_kill_issue7_runtime_evidence_only_env_ssh.ps1`
  into `issue7-runtime-evidence-only-env-current.log`, and the post-activation
  gate with `-RuntimeEvidenceLog`, then writes child logs under
  `target/profit-review`. `-PlanOnly` emits
  `PLAN_READY_NOT_EXECUTED` without SSH. The bundle is read-only and does not
  push, deploy, restart, change production env, close #7, relax
  DataFreshnessGuard, enable live/staged-add/TinyLive execution, enable
  scheduler mutation, place orders, modify OCO, send Telegram, or mutate
  DB/grid/fund/Earn/exchange state.
  `scripts/test_filter_block_false_kill_issue7_post_deploy_read_only_bundle.ps1`
  is included in `scripts/verify_local.ps1`.
- `scripts/prepare_data_freshness_shadow_candidate_packet_ssh.ps1` combines the
  read-only governance relaxation packet with the DataFreshness counterfactual
  replay-input smoke into `data_freshness_shadow_candidate_packet` and
  `data_freshness_shadow_candidate_packet_status`. It can return
  `BLOCKED_COUNTERFACTUAL_REPLAY_INPUT_MISSING` when complete replayable rows
  or counterfactual fields are still missing,
  `BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE` when only historical proxy
  rows predate replay-id/collector evidence, or
  `READY_FOR_DATAFRESHNESS_SHADOW_CANDIDATE_NOT_LIVE` only for a separate
  shadow-candidate review. It now carries `replay_input_stage`,
  `collector_status_counts`, `hard_gate_preview_status_counts`, and
  `replay_input_next_action` from the counterfactual smoke, classifies the
  input as `counterfactualEvidenceClass`, carries
  `replayInputEvidenceMarkers`, then emits
  `shadow_candidate_review_allowed`,
  `data_freshness_policy_relaxation_allowed=false`,
  `tiny_live_order_allowed=false`, and `live_policy_change_allowed=false`; it
  does not deploy, restart, change production env, relax DataFreshnessGuard,
  execute TinyLive, place orders, modify OCO, or mutate DB/grid/fund/Earn/
  Telegram/exchange state.
  `scripts/test_data_freshness_shadow_candidate_packet.ps1` guards the child
  read-only scripts, packet markers, docs coverage, local input validation,
  and non-authorization wording.
- `docs/data-freshness-shadow-replay-input-plan.md` defines the follow-up
  replay-input contract for DataFreshness false-kill evidence: stable replay
  candidate id, DataFreshness snapshot, candidate entry/TP/SL plan, EV/TQS,
  OCO preflight, duplicate/daily-cap/exposure/event-risk/open-position/loss
  hard-gate snapshots, `orderSent=false`, and stop conditions. It makes clear
  that the current 74-row positive forward-return proxy is not executable
  evidence until replayable candidate snapshots exist, and it blocks any
  DataFreshnessGuard relaxation or live mutation without fresh read-only
  counterfactual evidence. `scripts/test_data_freshness_shadow_replay_input_plan.ps1`
  guards the plan markers and links it to the existing read-only smokes.
- `docs/data-freshness-shadow-replay-collector-design.md` records the current
  code inventory and future collector boundary: L0 returns before candidate,
  EV, OCO, and hard-gate snapshots, so a future collector must be disabled by
  default, evidence-only, keep DataFreshnessGuard terminal, use a stable
  `replayCandidateId`, avoid live signal creation, and never send Telegram,
  place orders, modify OCO, mutate positions, or change scheduler/live policy.
  The tracked template and runtime config now keep
  `TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=false` /
  `trading.data-freshness.shadow-replay.collector.enabled=false` as the
  explicit default.
  `scripts/test_data_freshness_shadow_replay_collector_design.ps1` is wired
  into local verification to keep that boundary explicit.
- `DataFreshnessShadowReplayCollector` is now wired at the L0
  DataFreshnessGuard audit path as a disabled-by-default evidence skeleton.
  It keeps the hard block terminal and emits only safety markers while
  disabled; if separately enabled later, it marks scalar K-line/strategy
  context and fixed-config entry/TP/SL candidate snapshots as not replayable
  until EV, TQS, OCO, and hard-gate fields are evaluated. It now also emits
  explicit `NOT_EVALUATED_REPLAY_INPUT_ONLY` preview fields for EV, TQS, OCO,
  duplicate, daily-cap, exposure, event-risk, open-position, and loss-budget
  so downstream review can distinguish missing fields from unevaluated gates.
  Dynamic ATR candidate plans are not guessed. It still does not create live
  signals, Telegram sends, orders, OCO, runtime policy changes, DB schema
  changes, or complete replayable evidence.
- DataFreshness L0 audit context now writes deterministic `replayCandidateId`
  values (`dfsr1_...`) plus explicit `orderSent=false`, `intentCreated=false`,
  and `ocoPlanCreated=false` markers. This improves future replay traceability
  only; entry/TP/SL/EV/OCO snapshots are still required before any policy review.
- `scripts/smoke_data_freshness_replay_candidate_id_ssh.ps1` is the read-only
  post-deploy verifier for those ids. It reports
  `PENDING_NO_NEW_DATAFRESHNESS_ROWS`, `REPLAY_CANDIDATE_ID_EVIDENCE_OK`, or
  `REPLAY_CANDIDATE_ID_EVIDENCE_INCOMPLETE`, and `-RequireObserved` should only
  be used when a fresh DataFreshnessGuard row is expected. It also reports
  `DEPLOYED_RUNTIME_NOT_CURRENT` when deployed `app.commit` has not reached the
  replay-id runtime. It now also prints latest DataFreshness row time, latest
  row age, 1d/3d/7d/14d/30d row counts, and
  `data_freshness_sample_gap_status`, so a pending replay-id result can be
  classified as an all-time sample absence or a review-window gap.
- `scripts/smoke_data_freshness_replay_observation_bundle_ssh.ps1` combines
  origin-delta, replay-id, and counterfactual smokes into a read-only
  post-deploy observation chain. It routes stale runtime to
  `DEPLOY_CURRENT_RUNTIME_THEN_OBSERVE_REPLAY_ID` and only treats replay-id
  rows as useful after deployed runtime is current. Its summary now promotes
  latest DataFreshness row time, row age, 1d/3d/7d/14d/30d row counts, and
  `data_freshness_sample_gap_status`, so downstream blocker briefs can parse
  all-time absence versus review-window gaps without relying on child-output
  visibility.
- `scripts/prepare_data_freshness_replay_evidence_readiness_ssh.ps1` converts
  the replay observation chain into
  `DATAFRESHNESS_REPLAY_EVIDENCE_READINESS_PACKET`, emitting
  `data_freshness_replay_evidence_readiness_packet`,
  `data_freshness_replay_evidence_readiness_status`,
  `data_freshness_replay_evidence_blockers`, and
  `data_freshness_replay_evidence_required`. It surfaces
  `PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS`,
  `BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`, or
  `PENDING_COUNTERFACTUAL_REPLAY_SNAPSHOTS` as read-only routing states and
  conditionally attaches sample-gap RCA evidence. It does not deploy, change
  production env, relax DataFreshnessGuard, enable live trading, place orders,
  modify OCO, or mutate DB/grid/fund/Earn/Telegram/exchange state.
- `scripts/prepare_profit_candidate_flow_review_packet_ssh.ps1` combines the
  DataFreshness replay evidence readiness packet and BUY-like candidate
  progression smoke into `PROFIT_CANDIDATE_FLOW_REVIEW_PACKET`, emitting
  `profit_candidate_flow_review_packet`, `profit_candidate_flow_review_status`,
  `profit_candidate_flow_review_items`, `profit_candidate_flow_blockers`, and
  `profit_candidate_flow_required_evidence`. It routes
  `READY_FOR_ENTRY_SKIP_CANDIDATE_FLOW_REVIEW_NOT_LIVE`,
  `READY_FOR_NO_TERMINAL_FOLLOWUP_REVIEW_NOT_LIVE`, or
  `READY_FOR_FILTER_BLOCK_CANDIDATE_FLOW_REVIEW_NOT_LIVE` as read-only
  operator-review states. It does not deploy, change production env, relax
  EntryDedup/DataFreshness/live policy, enable live trading, place orders,
  modify OCO, or mutate DB/grid/fund/Earn/Telegram/exchange state.
- `scripts/smoke_data_freshness_sample_gap_rca_ssh.ps1` is the follow-up
  read-only RCA for a recent-window DataFreshness sample gap. It uses
  production MySQL `SELECT` queries against `bt_decision_audit` to report
  event-type counts, top `FILTER_BLOCK` blockers, BUY-style trading candidate
  counts, separate `ATTENTION_HIT` counts, DataFreshness recency, and
  `data_freshness_sample_gap_rca_recommendation` values such as
  `NO_RECENT_BUY_STYLE_CANDIDATES`, `OTHER_BLOCKERS_DOMINATE_RECENT_WINDOW`,
  `CANDIDATES_EXIST_BUT_NOT_DF_BLOCKED`, or `DATAFRESHNESS_SAMPLE_PRESENT`.
  The smoke is evidence only and does not deploy, change production env, relax
  DataFreshnessGuard, enable live trading, place orders, modify OCO, or mutate
  DB/grid/fund/Earn/Telegram/exchange state.
- 2026-06-22 read-only production replay observation for `BTCUSDT` showed
  `deployment_runtime_current_for_replay_id=true`,
  `data_freshness_replay_candidate_id_recommendation=PENDING_NO_NEW_DATAFRESHNESS_ROWS`,
  `latest_data_freshness_row_time=2026-06-14T15:38:16`,
  `latest_data_freshness_row_age_hours=181`,
  `data_freshness_rows_1d=0`, `data_freshness_rows_3d=0`,
  `data_freshness_rows_7d=0`, `data_freshness_rows_14d=74`,
  `data_freshness_rows_30d=110`, and
  `data_freshness_sample_gap_status=NO_ROWS_IN_REVIEW_WINDOW`.
  Counterfactual evidence still had
  `complete_replayable_candidate_rows=0` and missing
  `liveSignalId`, `replayCandidateId`, explicit entry/TP/SL plan, EV snapshot,
  OCO plan, and complete replayable candidate rows.
- 2026-06-22 read-only production
  `scripts/prepare_data_freshness_replay_evidence_readiness_ssh.ps1 -ReviewDays 14 -ReplayIdDays 3 -Limit 200`
  completed with both child scripts exiting `0`. The packet returned
  `data_freshness_replay_evidence_readiness_status=PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS`,
  `origin_delta_status=DOCS_TOOLING_ONLY_DRIFT`,
  `deployment_runtime_current_for_replay_id=true`,
  `data_freshness_replay_candidate_id_recommendation=PENDING_NO_NEW_DATAFRESHNESS_ROWS`,
  `replay_candidate_id_rows=0`,
  `data_freshness_sample_gap_status=NO_ROWS_IN_REVIEW_WINDOW`, and
  `data_freshness_sample_gap_rca_recommendation=NO_RECENT_BUY_STYLE_CANDIDATES`.
  It also carried `replay_input_stage=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`,
  `collector_status_counts=N/A:74`,
  `complete_replayable_candidate_rows=0`, and required evidence for fresh
  post-runtime DataFreshnessGuard terminal rows, replay-id rows, complete
  replayable rows, and `missing_counterfactual_fields=[]`. This confirms the
  next read-only step is to wait for new BUY-style/DataFreshness terminal
  samples or continue candidate-flow RCA; it is not permission to deploy,
  enable live trading, relax DataFreshnessGuard, place orders, or mutate OCO.
- 2026-06-22 read-only production
  `scripts/smoke_buy_like_candidate_progression_ssh.ps1 -ReviewDays 14
  -FollowupHours 6 -Limit 20 -MaxCandidateRows 1000` completed with exit `0`.
  The smoke found `buy_like_candidate_rows=320`,
  `sampled_buy_like_candidate_rows=320`, `followup_terminal_event_rows=395`,
  `entry_skip_followup_rows=265`, `no_terminal_followup_rows=39`,
  `filter_block_followup_rows=16`, `signal_buy_rows=0`, and
  `autotrade_followup_rows=0`. The top classifications were
  `ENTRY_SKIP:EntryDedup=226`, `NO_TERMINAL_FOLLOWUP=39`,
  `ENTRY_SKIP:DuplicateBar=22`, `ENTRY_SKIP:ShadowExecutionIntent=17`,
  `FILTER_BLOCK:RegimeFilter=9`, and `FILTER_BLOCK:ExpectedValueGate=7`, with
  recommendation `BUY_LIKE_TO_ENTRY_SKIP_REVIEW`. This moves the next
  read-only profit-improvement lane from DataFreshness relaxation toward
  EntryDedup/ShadowExecutionIntent candidate-flow RCA and TP/SL/OCO shadow
  feasibility; it is not permission to relax EntryDedup/DataFreshness/live
  policy, deploy, place orders, or mutate OCO.
- 2026-06-22 read-only production
  `scripts/prepare_profit_candidate_flow_review_packet_ssh.ps1 -ReviewDays 14
  -ReplayIdDays 3 -FollowupHours 6 -Limit 20 -MaxCandidateRows 1000
  -RequireActionable` completed with both child scripts exiting `0`. It
  returned `profit_candidate_flow_review_status=READY_FOR_ENTRY_SKIP_CANDIDATE_FLOW_REVIEW_NOT_LIVE`,
  `data_freshness_replay_evidence_readiness_status=PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS`,
  `data_freshness_replay_candidate_id_recommendation=PENDING_NO_NEW_DATAFRESHNESS_ROWS`,
  `complete_replayable_candidate_rows=0`,
  `buy_like_candidate_progression_recommendation=BUY_LIKE_TO_ENTRY_SKIP_REVIEW`,
  `buy_like_candidate_rows=320`, `entry_skip_followup_rows=265`,
  `no_terminal_followup_rows=39`, `filter_block_followup_rows=16`,
  `signal_buy_rows=0`, and `autotrade_followup_rows=0`. Review items were
  `ENTRY_SKIP_DOMINATES_BUY_LIKE_CANDIDATE_FLOW` and
  `NO_BUY_LIKE_ROWS_REACHED_SIGNAL_BUY_OR_AUTOTRADE`; blockers remained the
  DataFreshness replay-row/counterfactual evidence gaps. The next read-only
  step is EntryDedup/ShadowExecutionIntent row-level RCA and TP/SL/OCO shadow
  feasibility, not DataFreshness relaxation or live mutation.
- 2026-06-22 read-only production EntryDedup RCA for BTCUSDT strategy 508 /
  1h ran `scripts/smoke_entry_dedup_exposure_consistency_ssh.ps1 -StrategyId
  508 -IntervalCode 1h -Hours 336 -Limit 20` and
  `scripts/smoke_entry_dedup_semantics_feasibility_review_ssh.ps1 -StrategyId
  508 -IntervalCode 1h -Hours 336 -ForwardHours 24 -TakeProfitPct 1.00
  -StopLossPct 1.00 -RoundTripFeePct 0.20 -Limit 30`, both read-only. The
  consistency smoke returned
  `entry_dedup_exposure_consistency_recommendation=ENTRY_DEDUP_EXPOSURE_SEMANTICS_MISMATCH_REVIEW`:
  11 recent `EntryDedup` skips pointed at one open same-strategy signal with
  `auto_traded=0`, zero traded/OCO qty, missing OCO, and
  `filterReason=EventRiskControl: R2 score=60 blocks new LONG entries`. The
  feasibility replay returned
  `entry_dedup_semantics_feasibility_recommendation=ENTRY_DEDUP_FEASIBILITY_SHADOW_EXPERIMENT_READY_NOT_LIVE`,
  `entry_dedup_skip_rows=11`, `replay_reviewed_rows=11`, `tp_hit_rows=11`,
  `sl_hit_rows=0`, `ambiguous_same_bar_rows=0`, `net_positive_rows=11`,
  `net_win_rate_pct=100.00`, and `avg_net_return_pct=0.8000` under explicit
  LONG TP 1.00%, SL 1.00%, round-trip fee 0.20%, 24h max-hold assumptions.
  This is review-only shadow feasibility evidence; it does not authorize
  EntryDedup relaxation, staged-add execution, live trading, orders, OCO
  modification, deploy, or production env changes.
- `scripts/smoke_entry_dedup_exact_opportunity_staged_add_review_ssh.ps1`
  adds a narrower read-only EntryDedup opportunity review. It groups repeated
  same-bar `ENTRY_SKIP/EntryDedup` audit rows into synthetic exact opportunity
  keys, emits `ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_PACKET`, reports
  `exact_opportunity_count`,
  `exact_duplicate_suppressed_rows`,
  `staged_add_budget_proxy_allowed_opportunities`, and
  `staged_add_review_candidate_opportunities`, then keeps
  `staged_add_execution_allowed=false`, `order_allowed=false`, and
  `entry_dedup_policy_change_allowed=false`. The packet is not runtime EV/OCO
  evidence and does not authorize EntryDedup relaxation, staged-add/live
  execution, deploy, or production mutation.
- `scripts/prepare_entry_dedup_semantics_shadow_experiment_packet.ps1` now
  carries the exact-opportunity grouping summary alongside the older raw
  `entryDedupSkipRows` summary. The review surface should size the EntryDedup
  alpha opportunity from `exactOpportunityCount` and
  `exactDuplicateSuppressedRows`, not from repeated same-bar audit rows. This
  keeps the blocker analysis quantitative without authorizing EntryDedup
  relaxation, staged-add/live execution, orders, OCO changes, deploy, or
  production mutation.
- 2026-06-22 read-only production sample-gap RCA for `BTCUSDT`, after
  excluding watch-only `ATTENTION_HIT` and terminal `ENTRY_SKIP` rows from the
  pre-terminal BUY-like candidate definition, showed
  `audit_rows_7d_review=3323`, `buy_like_rows_7d_review=11`,
  `attention_hit_rows_7d_review=123`, `filter_block_rows_7d_review=0`,
  `entry_skip_rows_7d_review=11`, `autotrade_rows_7d_review=0`, and
  `data_freshness_rows_7d_review=0`.
  DataFreshness history is still present in the older window
  (`data_freshness_rows_14d=74`, `data_freshness_rows_30d=110`), but the
  7-day gap is classified as
  `data_freshness_sample_gap_rca_recommendation=CANDIDATES_EXIST_BUT_NOT_DF_BLOCKED`.
  Treat this as evidence to inspect BUY-like candidate progression before
  terminal skip/filter/order paths; it is not permission to relax
  DataFreshnessGuard, EntryDedup, or live entry policy.
- `scripts/smoke_attention_hit_progression_ssh.ps1` is the follow-up read-only
  RCA for that candidate progression question. It uses production MySQL
  `SELECT` queries against `bt_decision_audit` to follow recent
  `ATTENTION_HIT` rows to the next same strategy/interval terminal event
  (`SIGNAL_BUY`, `FILTER_BLOCK`, `ENTRY_SKIP`, or `AUTOTRADE_*`) within a
  bounded follow-up window. It emits
  `attention_hit_progression_recommendation`,
  `attention_followup_classification`, terminal event counts, strategy
  distribution, strategy-scoped follow-up counts, macro/watch-only attention
  counts, and examples. The strategy-scoped fields keep macro/watch-only
  background alerts from dominating the real trading-candidate follow-up view.
  The no-buy attention operator packet also invokes the no-terminal continuity
  RCA and carries `no_terminal_continuity_primary_classification` so
  primary-window matcher gaps are not mistaken for a broken trading pipeline.
  The smoke is evidence only and does not deploy, change production env, relax
  EntryDedup/DataFreshness/live policy, enable live trading, place orders,
  modify OCO, or mutate DB/grid/fund/Earn/Telegram/exchange state.
- 2026-06-22 read-only production attention progression for `BTCUSDT` showed
  `attention_hit_rows=122`, all sampled rows under `strategy=-1 interval=N/A`,
  `no_terminal_followup_rows=122`, and
  `attention_hit_progression_recommendation=ATTENTION_HIT_NO_TERMINAL_FOLLOWUP_DOMINATES`.
  The examples were put/call ratio bearish WARN rows, so these attention hits
  are macro/watch-only warnings, not trading entry candidates.
- `scripts/smoke_buy_like_candidate_progression_ssh.ps1` performs the same
  read-only candidate-progression check for true BUY-like pre-terminal trading
  candidates, excluding watch-only `ATTENTION_HIT` rows and terminal
  `ENTRY_SKIP` rows. It emits
  `buy_like_candidate_progression_recommendation`,
  `buy_like_followup_classification`, terminal event counts, candidate type
  distribution, and examples so trading-candidate loss can be separated from
  macro/attention warning flow before any entry-filter, strategy, or live
  execution review.
- `scripts/smoke_signal_eval_no_buy_generation_ssh.ps1` is the read-only
  generation-side RCA for windows where `SIGNAL_EVAL` exists but no recent
  BUY-like candidates are found. It uses production MySQL `SELECT` queries
  against `bt_decision_audit` to emit
  `signal_eval_no_buy_generation_recommendation`, `signal_eval_rows`,
  `buy_like_signal_eval_rows`, `no_buy_signal_eval_rows`, v2 context coverage,
  hold-reason distribution, strategy/interval distribution, threshold-gap
  distribution, context decision distribution, and examples. Recommendations
  such as `NO_BUY_LIKE_SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT` route review
  toward strategy threshold/no-condition evidence; they do not authorize
  DataFreshnessGuard or EntryDedup relaxation, strategy activation, live
  execution, orders, scheduler enablement, deploy, production env changes, or
  DB/OCO/grid/fund/Earn/Telegram/exchange mutation.
- 2026-06-22 read-only production BUY-like candidate progression for `BTCUSDT`
  showed `buy_like_candidate_rows=11`, all as `event=SIGNAL_EVAL strategy=508
  interval=1h`, with `entry_skip_followup_rows=10`,
  `filter_block_followup_rows=0`, `signal_buy_rows=0`,
  `autotrade_followup_rows=0`, and
  `buy_like_candidate_progression_recommendation=BUY_LIKE_TO_ENTRY_SKIP_REVIEW`.
  The dominant follow-up was `ENTRY_SKIP:EntryDedup` with reason
  `same strategy/symbol/interval LONG exposure already exists`. This routes the
  next profit review toward EntryDedup/existing-position exposure evidence for
  strategy 508 rather than DataFreshness relaxation or live execution changes.
- `scripts/prepare_no_buy_attention_flow_review_packet_ssh.ps1` combines the
  DataFreshness profit blocker brief, ATTENTION_HIT progression, SIGNAL_EVAL
  no-buy generation, and BUY-like progression into
  `NO_BUY_ATTENTION_FLOW_REVIEW_PACKET`. A
  `READY_FOR_ATTENTION_NO_BUY_FLOW_REVIEW_NOT_LIVE` packet is the operator
  review route when recent BUY-like candidates are absent but attention rows
  exist without terminal follow-up. It includes
  `SIGNAL_EVAL_NO_BUY_GENERATION_REVIEW` when recent `SIGNAL_EVAL` rows exist
  but none are BUY-like, plus `SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT` when
  v2 context shows strategy threshold misses dominate. It keeps
  DataFreshnessGuard/EntryDedup/live
  policy, scheduler, orders, OCO, deploy, production env, and DB/grid/fund/
  Earn/Telegram/exchange mutation unauthorized.
- `scripts/prepare_strategy574_near_threshold_decision_packet_ssh.ps1` is the
  read-only follow-up for
  `SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT` when strategy 574 / 1h appears near
  its buy threshold. It invokes or reuses
  `smoke_signal_eval_no_buy_generation_ssh.ps1`, extracts the strategy 574
  threshold-gap row, and emits `STRATEGY574_NEAR_THRESHOLD_DECISION_PACKET`,
  `strategy574_near_threshold_decision_packet`,
  `strategy574_near_threshold_decision_status`, `strategy574_min_buy_gap`, and
  `READY_FOR_STRATEGY574_NEAR_THRESHOLD_SHADOW_REVIEW_NOT_LIVE` only when the
  gap is within the configured near-threshold bound. It keeps
  `strategy_threshold_change_allowed=false`,
  `strategy_activation_allowed=false`, `tiny_live_order_allowed=false`,
  `live_policy_change_allowed=false`, `scheduler_enablement_allowed=false`,
  `deploy_or_env_change_allowed=false`, `order_allowed=false`,
  `telegram_send_allowed=false`, `entry_dedup_policy_change_allowed=false`, and
  `data_freshness_policy_change_allowed=false`; it is not approval for
  threshold changes, strategy activation, TinyLive/live execution, deploy,
  production env changes, Telegram sends, EntryDedup/DataFreshness relaxation,
  or DB/OCO/grid/fund/Earn/exchange mutation.
- `scripts/smoke_strategy574_near_threshold_shadow_observation_ssh.ps1` scores
  those strategy574 near-threshold rows with read-only OKX `md_kline` forward
  windows plus a TP/SL/fee proxy. It emits `near_threshold_rows`,
  `reviewable_forward_rows`, `false_positive_rows`,
  `false_positive_rate_pct`, `avg_24h_return_pct`, `avg_mfe_24h_pct`,
  `avg_mae_24h_pct`, `tp_hit_rows`, `sl_hit_rows`,
  `ambiguous_same_bar_rows`, `avg_net_return_pct`, `oco_preflight_status`, and
  `strategy574_near_threshold_shadow_recommendation`. The smoke treats OCO
  preflight as required review evidence, not proven live readiness, and it
  never authorizes threshold changes, strategy activation, TinyLive/live
  execution, deploy, production env changes, Telegram sends,
  EntryDedup/DataFreshness relaxation, or DB/OCO/grid/fund/Earn/exchange
  mutation.
- 2026-06-23 follow-up read-only production strategy574 near-threshold shadow
  observation ran
  `.\scripts\smoke_strategy574_near_threshold_shadow_observation_ssh.ps1` and
  wrote
  `target\profit-review\strategy574-near-threshold-shadow-observation-latest.log`.
  It made no production env, DB, order, OCO, grid, fund, Earn, Telegram,
  scheduler, exchange, external backfill/import, deploy, restart, or nginx
  changes. The 7-day sample returned `near_threshold_rows=30`,
  `reviewable_forward_rows=30`, `false_positive_rows=28`,
  `false_positive_rate_pct=93.33`, `avg_forward_return_pct=-2.0671`,
  `tp_hit_rows=8`, `sl_hit_rows=22`, `ambiguous_same_bar_rows=0`,
  `avg_net_return_pct=-0.6667`,
  `oco_preflight_status=REVIEW_REQUIRED_TP_SL_PROXY_AVAILABLE`, and
  `strategy574_near_threshold_shadow_recommendation=STRATEGY574_NEAR_THRESHOLD_FALSE_POSITIVE_RISK_HIGH`.
  This is negative evidence for relaxing the strategy574 threshold from 70 to
  69 in the current window; the next action is to keep the threshold/live path
  unchanged and require a separate design review before any alternative
  strategy574 shadow idea.
- 2026-06-23 read-only production no-buy/attention refresh for `BTCUSDT`
  showed the current 7-day window shifted from the 2026-06-22 EntryDedup lane
  to a signal-generation/attention lane: `buy_like_candidate_rows=0`,
  `attention_hit_rows=173`, `no_terminal_followup_rows=173`,
  `filter_block_followup_rows=0`, `entry_skip_followup_rows=0`,
  `signal_buy_followup_rows=0`, and `autotrade_followup_rows=0`.
  The follow-up `scripts/smoke_signal_eval_no_buy_generation_ssh.ps1` evidence
  showed `signal_eval_rows=2797`, `buy_like_signal_eval_rows=0`,
  `no_buy_signal_eval_rows=2797`, `hold_reason_rows=2797`,
  `macro_or_unknown_strategy_rows=0`, `v2_context_rows=2797`,
  `strategy_decision_context_rows=2268`, `execution_hold_rows=2797`, and
  `signal_eval_no_buy_generation_recommendation=NO_BUY_LIKE_SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT`.
  The top threshold gaps showed strategy 574 / 1h
  `market_entropy_index` at 69 versus buy threshold 70 (gap 1), ETF pressure
  strategies at 51 versus threshold 60 (gap 9), and SQI strategies at 0 versus
  thresholds 30 or 40.
  `attention_hit_progression_recommendation=ATTENTION_HIT_NO_TERMINAL_FOLLOWUP_DOMINATES`,
  while the BUY-like progression returned
  `buy_like_candidate_progression_recommendation=NO_BUY_LIKE_CANDIDATES_IN_REVIEW_WINDOW`.
  The consolidated no-buy attention-flow packet returned
  `no_buy_attention_flow_review_status=READY_FOR_ATTENTION_NO_BUY_FLOW_REVIEW_NOT_LIVE`,
  review items
  `SIGNAL_EVAL_NO_BUY_GENERATION_REVIEW`,
  `SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT`,
  `ATTENTION_HIT_NO_TERMINAL_FOLLOWUP_DOMINATES`,
  `SIGNAL_GENERATION_OR_ATTENTION_PIPELINE_REVIEW`, and
  `NO_ATTENTION_ROWS_REACHED_SIGNAL_BUY_OR_AUTOTRADE`, with blockers
  `NO_BUY_LIKE_CANDIDATES_IN_REVIEW_WINDOW` and
  `NO_RECENT_DATAFRESHNESS_ROWS`.
  The examples were `strategy=-1 interval=N/A` put/call-ratio WARN rows, so
  they are macro/watch-only attention warnings rather than strategy entry
  candidates. This routes the next read-only profit review toward signal
  generation threshold-gap review, especially near-threshold strategy 574,
  and attention-to-terminal mapping before any DataFreshness, EntryDedup,
  strategy activation, threshold change, or live policy experiment.
- 2026-06-23 follow-up read-only production strategy574 near-threshold decision
  packet ran
  `.\scripts\prepare_strategy574_near_threshold_decision_packet_ssh.ps1 -RequireReady`
  and wrote
  `target\profit-review\strategy574-near-threshold-decision-packet-latest.log`.
  It made no production env, DB, order, OCO, grid, fund, Earn, Telegram,
  scheduler, exchange, external backfill/import, deploy, restart, or nginx
  changes. The packet returned
  `strategy574_near_threshold_decision_status=READY_FOR_STRATEGY574_NEAR_THRESHOLD_SHADOW_REVIEW_NOT_LIVE`,
  `signal_eval_rows=2792`, `signal_eval_buy_like_rows=0`,
  `signalEvalStrategyDecisionContextRows=2264`,
  `strategy574_threshold_gap_indicator=market_entropy_index`,
  `strategy574_min_buy_gap=1.0000`, and
  `strategy574_shadow_observation_review_allowed=true`. This is a
  shadow-observation review route only; it keeps
  `strategy_threshold_change_allowed=false`,
  `strategy_activation_allowed=false`, `tiny_live_order_allowed=false`,
  `live_policy_change_allowed=false`, `scheduler_enablement_allowed=false`,
  `deploy_or_env_change_allowed=false`, `order_allowed=false`,
  `telegram_send_allowed=false`, `entry_dedup_policy_change_allowed=false`, and
  `data_freshness_policy_change_allowed=false`. It does not authorize strategy
  threshold changes, strategy activation, TinyLive/live execution, deploy,
  production env changes, Telegram sends, EntryDedup/DataFreshness relaxation,
  or DB/OCO/grid/fund/Earn/exchange mutation.
- `scripts/smoke_strategy508_entry_dedup_exposure_ssh.ps1` is the follow-up
  read-only RCA for that strategy 508 / 1h EntryDedup lane. It combines
  server-local MCP `getEntryDedupGovernanceDashboard` and
  `getStagedAddReadiness` with direct production DB `SELECT` evidence for
  strategy 508 ENTRY_SKIP rows, open same-strategy exposure, OCO coverage, and
  staged-add blockers. It emits
  `strategy508_entry_dedup_exposure_recommendation`,
  `wouldAllowStagedAdd`, `remainingAddBudget`, `open_same_strategy_positions`,
  `target_group_blockers`, and open-position examples. The smoke is evidence
  only and does not relax EntryDedup/DataFreshness/live policy, execute
  staged-add/live orders, modify OCO, close positions, deploy, or mutate
  production state.
- `scripts/smoke_strategy508_first_entry_readiness_ssh.ps1` is the companion
  read-only first-entry path smoke for Strategy 508 / BTCUSDT / 1h. Use it
  when staged-add preview emits `NO_EXISTING_POSITION_FOR_STAGED_ADD`, because
  staged-add readiness is not first-entry evidence. It checks signal-source
  policy, strategy config, EntryDedup first-entry semantics, AutoTrade
  open-position guards, recent live_signal/audit rows, ExpectedValueGate
  context, and `previewPositionSizing`. It emits
  `strategy508_signal_source_gate`, `entry_dedup_first_entry_pass`,
  `auto_trade_open_position_gate`, `latest_ev_gate_status`,
  `first_entry_position_sizing_status`, `strategy508_first_entry_blockers`, and
  `strategy508_first_entry_conclusion`. It is evidence only and does not
  deploy, change production env, place orders, send Telegram, relax
  EntryDedup/DataFreshness/live policy, or mutate DB/OCO/grid/fund/Earn/exchange
  state.
- Strategy 508 first-entry sizing now has an explicit min-notional floor policy
  path. `TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_ENABLED=false` remains
  the safe default. When enabled with
  `TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_MAX_RISK_USDT`, the sizing
  engine may bridge a raw risk-sized notional below the minimum up to the min
  notional only when available USDT can fund the floor and the floor-sized SL
  loss stays within the configured cap. This is intended for cases like the
  July 2026 strategy 508 sample (`20.83` raw, `50.00` floor, ~`6.00` USDT SL
  risk), and remains a live sizing policy change requiring explicit deploy/env
  authorization.
- `scripts/prepare_strategy508_min_notional_floor_activation_packet.ps1` is the
  read-only activation authorization packet for that Strategy 508 floor path.
  It consumes the saved first-entry readiness log, requires signal-source,
  EntryDedup, open-position, and active EV gates to be clear, verifies that the
  latest blocker is only min-notional sizing, estimates the floor-sized SL risk
  against `TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_MAX_RISK_USDT`, and
  emits exact env diff, rollback env diff, and
  `strategy508_min_notional_floor_activation_authorization_text`. The packet
  keeps deploy/env/restart/order/Telegram/DB/exchange mutation flags false; it
  is review material only until a separate production authorization and
  post-deploy read-only verification are completed.
- Strategy 508 now also has a read-only TradePlanQualityGate review packet for
  the post-min-notional blocker. `scripts/prepare_strategy508_trade_plan_quality_gate_review_packet.ps1`
  consumes saved forward TSV evidence for `ENTRY_SKIP/TradePlanQualityGate`
  rows and emits the exact strategy-scoped `setStrategyFlags(...)` call plus
  rollback call for a narrow +6% TP / -12% disaster-SL allowance
  (`tradePlanQualityGateEnabled=true`, `tradePlanMinRiskReward=0.49`,
  `tradePlanMaxStopLossPct=0.121`). It deliberately keeps
  `strategy_config_mutation_allowed=false`, `mcp_write_allowed=false`,
  `live_policy_change_allowed=false`, `entry_dedup_policy_change_allowed=false`,
  `ev_policy_change_allowed=false`, `order_allowed=false`,
  `telegram_send_allowed=false`, and DB/exchange mutation flags false. The
  runtime now also sends an explicit AutoTrade-not-bought notification when
  `TradePlanQualityGate` blocks a BUY, so those skips are no longer silent.
- 2026-06-22 read-only production strategy 508 EntryDedup/exposure RCA for
  `BTCUSDT` / `1h` showed `buy_eval_rows=11`, `entry_dedup_skip_rows=11`,
  `filter_block_rows=0`, `autotrade_rows=0`, and all skip reasons as
  `same strategy/symbol/interval LONG exposure already exists`. The only open
  same-strategy row was `id=240` with `autoTraded=0`, no OCO, zero traded
  quantity/notional, and `filterReason=EventRiskControl: R2 score=60 blocks new
  LONG entries`. MCP staged-add readiness returned
  `staged_add_decision=BLOCK_HARD_SAFETY`, `wouldAllowStagedAdd=false`,
  `sameStrategyExposureUsed=0`, `remainingAddBudget=10`, and blockers
  `NO_EXISTING_POSITION_FOR_STAGED_ADD` plus `EV_UNKNOWN`. This means the
  recent strategy 508 EntryDedup skips are not live-addable staged-add
  candidates; the next review should inspect whether EntryDedup is too broad
  because it treats a non-auto-traded EventRisk-blocked signal as existing LONG
  exposure. It is still not permission to relax EntryDedup or execute live
  staged adds.
- `scripts/smoke_entry_dedup_exposure_consistency_ssh.ps1` now captures that
  follow-up as a focused read-only DB smoke. It compares the EntryDedup
  open-signal exposure definition against the staged-add auto-traded open
  position definition and emits
  `entry_dedup_exposure_consistency_recommendation`, `open_signal_rows`,
  `auto_traded_open_rows`, `non_auto_open_rows`, `non_auto_zero_qty_rows`, and
  `non_auto_eventrisk_rows`. The mismatch classification is
  `ENTRY_DEDUP_EXPOSURE_SEMANTICS_MISMATCH_REVIEW`; it is a review route only,
  not approval to relax EntryDedup/DataFreshness/live policy or mutate
  production.
- 2026-06-22 production run of that consistency smoke for `BTCUSDT` / strategy
  508 / `1h` returned `entry_dedup_skip_rows=11`,
  `same_exposure_reason_rows=11`, `open_signal_rows=1`,
  `auto_traded_open_rows=0`, `non_auto_open_rows=1`,
  `non_auto_zero_qty_rows=1`, `non_auto_eventrisk_rows=1`,
  `missing_oco_rows=1`, `open_notional=0`, and
  `entry_dedup_exposure_consistency_recommendation=ENTRY_DEDUP_EXPOSURE_SEMANTICS_MISMATCH_REVIEW`.
  The example row remained `id=240`, `autoTraded=0`, zero traded/OCO quantity,
  and `filterReason=EventRiskControl: R2 score=60 blocks new LONG entries`.
  This is evidence of a definition mismatch between EntryDedup open-signal
  exposure and staged-add auto-traded-position exposure; it still does not make
  the row live-addable.
- `scripts/smoke_entry_dedup_semantics_shadow_review_ssh.ps1` is the next
  read-only review step for that mismatch. It scores the skipped EntryDedup
  candidates against later OKX `md_kline` rows, emitting 4h/24h forward
  returns, 24h MFE/MAE, positive-row counts, and
  `entry_dedup_semantics_shadow_recommendation`. The strongest classification,
  `ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_CANDIDATE_NOT_LIVE`, is still only
  input to a separate shadow experiment review; it does not relax EntryDedup,
  approve staged-add execution, or mutate production.
- 2026-06-22 production run of that semantics shadow review for `BTCUSDT` /
  strategy 508 / `1h` kept the same mismatch evidence (`open_signal_rows=1`,
  `auto_traded_open_rows=0`, `non_auto_zero_qty_rows=1`,
  `non_auto_eventrisk_rows=1`, `open_notional=0`) and found
  `entry_dedup_skip_rows=11`, `reviewable_forward_rows=11`,
  `missing_kline_rows=0`, `positive_24h_rows=10`,
  `negative_24h_rows=1`, `positive_24h_rate_pct=90.91`,
  `avg_4h_return_pct=0.8950`, `avg_24h_return_pct=1.0173`,
  `median_24h_return_pct=1.0572`, `avg_mfe_24h_pct=2.3067`, and
  `avg_mae_24h_pct=-0.3580`. The recommendation was
  `ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_CANDIDATE_NOT_LIVE`. This is enough
  to route a separate shadow-only semantics experiment review, but it still
  requires fees, TP/SL/OCO feasibility, ExpectedValueGate, EventRiskControl,
  duplicate-hash, daily-cap, and max-loss evidence before any policy change can
  be considered.
- `scripts/smoke_entry_dedup_semantics_feasibility_review_ssh.ps1` adds the
  next review layer for that candidate. It applies explicit TP/SL/fee
  assumptions to the skipped EntryDedup rows, reports TP hits, SL hits,
  timeouts, same-bar ambiguous rows, net win rate, and average net return, and
  emits `entry_dedup_semantics_feasibility_recommendation`. Same-bar TP/SL
  ambiguity is not treated as pass evidence. A positive classification such as
  `ENTRY_DEDUP_FEASIBILITY_SHADOW_EXPERIMENT_READY_NOT_LIVE` is still only
  input to a separate shadow experiment review with live mutation disabled.
- `scripts/smoke_entry_dedup_semantics_gate_preflight_ssh.ps1` adds the
  read-only blocker review layer for the strategy 508 / `BTCUSDT` / `1h`
  EntryDedup shadow lane. It combines direct MySQL SELECTs with server-local
  read-only MCP calls for EventRiskControl and ExpectedValueGate, then
  classifies EV, EventRisk, duplicate protection, daily cap/max-loss, and OCO
  feasibility plus candidate-level RuntimeDecisionEvidence EV/OCO coverage
  without relaxing EntryDedup, placing orders, changing OCO, or mutating
  production state.
- `scripts/smoke_entry_dedup_semantics_synthetic_ev_oco_preview_ssh.ps1` adds
  the next read-only fallback when candidate-level RuntimeDecisionEvidence is
  absent. It builds a synthetic EV/OCO preview from existing K-lines for the
  strategy 508 / `BTCUSDT` / `1h` EntryDedup candidates, including
  entry/TP/SL, fee-adjusted replay result, `expectedRProxy`, OCO plan-shape
  validity, and OCO route-not-proven status. The packet is explicitly
  `READ_ONLY_SYNTHETIC_REPLAY_PROXY_NOT_RUNTIME_EV` and does not write runtime
  evidence or authorize live/OCO/policy mutation.
- 2026-06-22 production run of that feasibility review for `BTCUSDT` /
  strategy 508 / `1h`, using explicit assumptions `takeProfitPct=1.00`,
  `stopLossPct=1.00`, `roundTripFeePct=0.20`, and `forwardHours=24`, returned
  `entry_dedup_skip_rows=11`, `replay_reviewed_rows=11`, `tp_hit_rows=11`,
  `sl_hit_rows=0`, `timeout_rows=0`, `ambiguous_same_bar_rows=0`,
  `missing_kline_rows=0`, `net_positive_rows=11`,
  `net_win_rate_pct=100.00`, `avg_net_return_pct=0.8000`, and
  `entry_dedup_semantics_feasibility_recommendation=ENTRY_DEDUP_FEASIBILITY_SHADOW_EXPERIMENT_READY_NOT_LIVE`.
  This supports drafting a separate shadow-only EntryDedup semantics experiment
  review packet. It still does not authorize EntryDedup relaxation, live
  staged-add execution, OCO modification, or any production mutation.
- `scripts/prepare_entry_dedup_semantics_shadow_experiment_packet.ps1` now
  packages the three recorded read-only production evidence layers for the
  strategy 508 / `BTCUSDT` / `1h` EntryDedup semantics lane: exposure
  consistency mismatch, forward-return shadow review, and TP/SL/fee
  feasibility. It emits
  `entry_dedup_semantics_shadow_experiment_packet`,
  `entry_dedup_shadow_packet_missing_requirements`, and
  `entry_dedup_semantics_shadow_packet_status`; the ready status is
  `READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE`. The packet is a
  local review attachment only and does not rerun SSH, deploy, change
  production env, relax EntryDedup/DataFreshness/live policy, place orders,
  modify OCO, close positions, or mutate DB/grid/fund/Earn/Telegram/exchange
  state. Ready output must keep `order_allowed=false`.
- `scripts/prepare_entry_dedup_semantics_shadow_experiment_packet_ssh.ps1`
  performs the fresh production rerun version of that packet. It invokes
  `smoke_entry_dedup_exposure_consistency_ssh.ps1`,
  `smoke_entry_dedup_semantics_shadow_review_ssh.ps1`, and
  `smoke_entry_dedup_semantics_feasibility_review_ssh.ps1` directly, then
  emits `ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_REVIEW_PACKET` with
  `freshProductionRerun=true`, child exit codes, parsed evidence,
  `entry_dedup_shadow_packet_missing_requirements`, and
  `entry_dedup_semantics_shadow_packet_status`. Ready output still requires
  `order_allowed=false`, `live_policy_change_allowed=false`, and
  `entry_dedup_policy_change_allowed=false`; it does not authorize EntryDedup
  relaxation, live trading, staged-add execution, orders, OCO modification,
  deploy, production env changes, or DB/grid/fund/Earn/Telegram/exchange
  mutation.
- 2026-06-22 fresh read-only production run of
  `scripts/prepare_entry_dedup_semantics_shadow_experiment_packet_ssh.ps1
  -StrategyId 508 -IntervalCode 1h -Hours 336 -ForwardHours 24
  -ShortForwardHours 4 -TakeProfitPct 1.00 -StopLossPct 1.00
  -RoundTripFeePct 0.20 -ReviewNotionalCapUsdt 10 -ObservationHours 72
  -Limit 30 -RequireReady` completed with all three child scripts exiting `0`.
  It returned
  `entry_dedup_semantics_shadow_packet_status=READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE`,
  `entry_dedup_shadow_packet_missing_requirements=[]`,
  `entry_dedup_exposure_consistency_recommendation=ENTRY_DEDUP_EXPOSURE_SEMANTICS_MISMATCH_REVIEW`,
  `entry_dedup_semantics_shadow_recommendation=ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_CANDIDATE_NOT_LIVE`,
  and
  `entry_dedup_semantics_feasibility_recommendation=ENTRY_DEDUP_FEASIBILITY_SHADOW_EXPERIMENT_READY_NOT_LIVE`.
  Parsed evidence remained `entry_dedup_skip_rows=11`, `open_signal_rows=1`,
  `auto_traded_open_rows=0`, `non_auto_zero_qty_rows=1`,
  `non_auto_eventrisk_rows=1`, `reviewable_forward_rows=11`,
  `positive_24h_rows=10`, `negative_24h_rows=1`,
  `avg_24h_return_pct=1.0173`, `replay_reviewed_rows=11`,
  `tp_hit_rows=11`, `sl_hit_rows=0`, `ambiguous_same_bar_rows=0`, and
  `avg_net_return_pct=0.8000`. The proposed envelope kept
  `ReviewNotionalCapUsdt=10`, `ObservationHours=72`, `order_allowed=false`,
  `live_policy_change_allowed=false`,
  `entry_dedup_policy_change_allowed=false`,
  `position_or_oco_mutation_allowed=false`, and
  `deploy_or_env_change_allowed=false`. This is a review-only shadow experiment
  packet; it still does not authorize EntryDedup relaxation, live trading,
  staged-add execution, orders, OCO modification, deploy, or production env
  changes.
- `scripts/prepare_entry_dedup_operator_decision_brief_ssh.ps1` now converts
  the fresh EntryDedup semantics shadow packet into
  `ENTRY_DEDUP_OPERATOR_DECISION_BRIEF`. Ready output emits
  `entry_dedup_operator_decision_brief_packet`,
  `entry_dedup_operator_decision_lanes`,
  `entry_dedup_operator_decision_checklist`, and
  `entry_dedup_operator_decision_brief_status=READY_FOR_ENTRY_DEDUP_OPERATOR_DECISION_NOT_LIVE`.
  The brief routes `entry-dedup-semantics-shadow-operator-review` as a
  review-only shadow experiment lane and keeps
  `entry-filter-datafreshness-policy` blocked outside that decision. Its
  primary recommendation is `PREPARE_SEPARATE_ENTRY_DEDUP_SHADOW_REVIEW`. It
  must preserve `order_allowed=false`, `live_policy_change_allowed=false`,
  `entry_dedup_policy_change_allowed=false`,
  `position_or_oco_mutation_allowed=false`, and
  `deploy_or_env_change_allowed=false`; it does not authorize EntryDedup
  relaxation, DataFreshness/live policy relaxation, live or staged-add
  execution, orders, OCO modification, deploy, production env changes, or
  DB/grid/fund/Earn/Telegram/exchange mutation.
- `scripts/smoke_profit_improvement_review_bundle_ssh.ps1` wraps the read-only
  origin-delta classifier, profit-candidate review, DataFreshness false-kill
  review, DataFreshness executability review, DataFreshness counterfactual
  replay-input review, strategy 485 position-risk smoke, strategy 574
  signal/governance smoke, and TinyLive post-trade smoke into one
  profit-improvement routing command. It prints
  `profit_improvement_review_items`, `profit_improvement_candidate_scorecard`,
  `profit_improvement_review_decision`,
  `deploy_required_before_profit_improvement_review`,
  `profit_improvement_missing_requirement_count`,
  `profit_improvement_missing_requirements`, `top_profit_improvement_candidate`, and
  `profit_improvement_bundle_recommendation` such as
  `COLLECT_DATAFRESHNESS_REPLAYABLE_CANDIDATE_SNAPSHOTS`, so DataFreshness
  alpha pressure cannot be reviewed without replay-input coverage and
  executable snapshot gaps, and strategy 485 risk plus strategy 574/TinyLive
  context stay visible. The scorecard ranks read-only candidates and required
  evidence only, the DataFreshness candidate carries
  `data_freshness_counterfactual_recommendation`,
  `complete_replayable_candidate_rows`, and `missing_counterfactual_fields`, and
  the strategy 485
  candidate carries `strategy485_position_review_decision` so EV/OCO/timeout
  counts remain attached to the ranked profit candidate; it does not authorize
  live mutations. If the top-ranked candidate is still blocked by deploy or
  replay evidence, the decision stays
  `BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE` even when a secondary read-only lane
  is reviewable. The review decision adds top-level `canDraftShadowExperimentReview`,
  `deployRequired`, `allowedReviewTypes`, `rankedEvidenceRefs`,
  `strategy485ReviewDecision`, missing-requirement counts, and no-live
  authorization text for downstream gates. The wrapper invokes existing read-only smokes only and does not
  change production env, DB, order, OCO, grid, Earn, fund, Telegram, scheduler,
  exchange, external backfill/import, deploy, restart, or nginx state.
  `scripts/test_profit_improvement_review_bundle.ps1` guards the child smoke
  list, summary markers, docs coverage, and non-authorization wording.
- `scripts/prepare_profit_experiment_gate_ssh.ps1` wraps the profit-improvement
  bundle into a read-only experiment gate. It emits
  `deploy_required_before_profit_experiment`,
  `shadow_experiment_review_allowed`, `live_policy_change_allowed=false`,
  `strategy485_position_review_decision`,
  `profit_experiment_blocker_items`,
  `profit_experiment_missing_requirements`, and
  `profit_experiment_gate_status`. The gate can route a candidate toward a
  separate shadow-only proposal only after required replay/counterfactual
  evidence is present; it does not authorize deploy, live policy changes,
  position/OCO changes, or order-capable actions.
  `profit_experiment_blocker_items` keeps DataFreshness replay/candidate
  snapshot gaps separate from strategy 485 risk-reduction operator approval
  gaps, with per-lane required evidence, next action, and non-authorization
  text for dashboards and operator review.
- `scripts/prepare_profit_shadow_experiment_packet_ssh.ps1` wraps that gate
  into a machine-readable `profit_shadow_experiment_packet` and
  `profit_shadow_packet_status`. It can become
  `READY_FOR_SHADOW_EXPERIMENT_PACKET_NOT_LIVE` only when the gate is ready,
  runtime is current, and missing requirements are empty; it keeps
  `live_policy_change_allowed=false` and never authorizes
  DataFreshnessGuard relaxation or live trading.
- `scripts/audit_live_readiness_ssh.ps1` provides a read-only live-readiness
  audit before any explicitly authorized live enablement. It masks secrets,
  reports order-capable flags, dry-run flags, background automation warnings,
  server-local MCP readiness surfaces, runtime-log smoke, machine-readable
  `readiness_details`, `blocker_classification`, `next_actions`, blockers, and
  a final verdict without changing production env, DB, order, OCO, grid, Earn,
  fund, or Telegram state.
- `scripts/smoke_tiny_live_loss_rca_ssh.ps1` provides a read-only RCA smoke for
  the live-readiness `risk_hard_stop` / consecutive tiny-live loss blocker. It
  calls server-local `/api/mcp` to summarize tiny-live execution readiness,
  auto-approval blockers, recent tiny-live audit rows, autonomous execution
  attribution, missed-opportunity context, and monitor/rollout state over the
  policy-aligned default 30-day window. It prints `hardStopClearCriteria` and
  rollout gates such as `completedTinyLiveSamples`, `falsePositiveCount`,
  `canEnableProduction`, and `canIncreaseDailyCap` without changing production
  env, DB, order, OCO, grid, Earn, fund, Telegram, or live scheduler state.
- `scripts/smoke_runtime_evidence_rca_ssh.ps1` provides a read-only RCA smoke
  for the live-readiness `runtime_evidence_gap`. It calls server-local
  `/api/mcp` to report runtime-evidence env/dashboard state, preview
  `runtimeEvidenceStatus`, recent evidence rows, shadow-intent counts, candidate
  context, and no-buy context, then classifies the gap as `CONFIG_DISABLED`,
  `NO_CANONICAL_ROWS`, `CANONICAL_ROWS_NO_SHADOW_INTENT`,
  `CANONICAL_SHADOW_READY`, or `REVIEW_RUNTIME_EVIDENCE_STATUS` without writing
  RuntimeDecisionEvidence or changing production env, DB, order, OCO, grid,
  Earn, fund, Telegram, or live scheduler state.
- `docs/live-dry-run-evidence-plan.md` records the evidence-only path toward a
  later live proposal. It treats `TRADING_RUNTIME_EVIDENCE_ENABLED=true` as a
  separately authorized candidate only, keeps order-capable, Telegram,
  scheduler, OCO, grid, Earn, fund, guardian live-action, and
  external-backfill/import flags disabled, and requires read-only smokes before
  any future live approval discussion.
- `scripts/smoke_live_background_automation_ssh.ps1` provides a read-only
  server env smoke for already-enabled background automation before live review.
  It reports `background_automation_true`,
  `high_risk_background_automation_true`, classification, recommendation, and
  verdict plus review-plan markers (`riskCategory`, `requiredReview`,
  `requiredEvidence`, `nextAction`, `notAuthorization`) without changing
  production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, or
  external backfill/import state.
- `docs/live-production-env-review-proposal.md` turns the current read-only
  live blockers into an operator review checklist for a future production env
  proposal. It names the evidence-only runtime evidence candidate, the
  background automation flags that should be disabled or separately justified,
  the order-capable flags that must stay disabled until live approval, and the
  required post-authorization smokes without authorizing env mutation.
- `scripts/smoke_live_readiness_bundle_ssh.ps1` wraps the live-readiness audit,
  background automation smoke, runtime-evidence RCA, tiny-live loss RCA,
  signal-correctness smoke, and MCP parity smoke into one read-only command that
  prints `bundle_blockers` and `bundle_verdict` without changing production env,
  DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, or external
  backfill/import state.
- `docs/live-readiness-blocker-remediation.md` maps each bundle blocker to the
  required read-only evidence, clear condition, and allowed next action, while
  keeping live/order/OCO/grid/Earn/fund/Telegram/exchange/DB/scheduler mutation
  forbidden without a separate live proposal.
- `docs/live-background-automation-env-diff-proposal.md` isolates the env diff
  that would clear `BACKGROUND_AUTOMATION_REVIEW` after separate authorization:
  all currently true background automation flags are proposed false, with
  read-only verification and rollback criteria, without authorizing production
  env mutation.
- `docs/live-runtime-evidence-env-proposal.md` isolates the evidence-only env
  diff for clearing `RUNTIME_EVIDENCE_CONFIG_DISABLED` after separate
  authorization: only `TRADING_RUNTIME_EVIDENCE_ENABLED=true` is proposed, all
  execution/Telegram/scheduler/external-backfill/exchange/OCO/grid/fund/Earn
  mutation paths remain disabled, and `shadowIntentCount > 0` plus
  `orderSentEvidence=0` are required before live review.
- `scripts/verify_local.ps1` now executable-negative-tests the newer live
  readiness SSH wrappers (`smoke_live_background_automation_ssh.ps1`,
  `smoke_runtime_evidence_rca_ssh.ps1`, `smoke_tiny_live_loss_rca_ssh.ps1`,
  `smoke_signal_correctness_ssh.ps1`, and `smoke_live_readiness_bundle_ssh.ps1`)
  so unsafe SSH targets, invalid read-only query windows, or signal-policy
  review contract drift fail locally before any SSH call.
- Latest recorded current-at-observation read-only live-readiness bundle on
  2026-06-20T20:28+08:00 after the explicitly authorized deploy of
  `ef6253a4ecff7c27a2e709f226e166389700a82d`: server worktree,
  `origin/main`, and deployed `app.commit` all matched that commit, active port
  switched to `8084`, `deployment_metadata_status=CURRENT`,
  `origin_metadata_status=CURRENT_ORIGIN_MAIN`, `metadata_blockers=[]`, and
  `deploy_required_before_live_review=false`. Split/server verification passed
  in shared-DB mode with 39 source entity tables, 176 DB tables, 0 missing
  tables, and 137 expected extra shared tables. Local server MCP
  `/api/mcp` passed, while public dedicated `/api/mcp` and shared-host
  `/api/trading/mcp` remained blocked with 404. The full read-only bundle
  reported runtime log `PASS` with ERROR count 0 and WARN baseline total 13,
  MCP parity `required_tools=[...]`, `missing_required_tools=[]`,
  `toolCount=305 required=35`, `missing_readiness_detail_fields=[]`,
  and `autonomousOpportunity.eligible=false` in `readiness_details`.
  Background automation evidence printed
  `backgroundAutomationClear=false` and
  `background_automation_blockers=["HIGH_RISK_BACKGROUND_AUTOMATION_TRUE", "BACKGROUND_AUTOMATION_TRUE"]`.
  Do not chase docs-only deploy commits by rewriting this attached snapshot
  after every documentation refresh; the currentness source of truth is a
  freshly rerun deployment metadata smoke plus the full live-readiness bundle,
  not the SHA embedded in this progress note.
  The bundle also printed machine-readable `bundle_blocker_summary` entries
  mapping each blocker to a category, required read-only evidence, and next
  action.
  `MCP_AUDIT_TOOL_ERROR`, `DEPLOYED_RUNTIME_NOT_CURRENT`, and
  `RUNTIME_HEALTH_OR_LOG_NOT_CLEAN` are no longer current blockers. The bundle
  still printed `live_review_packet_allowed=false` and `bundle_verdict=NOT_READY`
  with blockers `LIVE_READINESS_NOT_READY`,
  `EXECUTION_ELIGIBILITY_NOT_READY`, `BACKGROUND_AUTOMATION_REVIEW`,
  `RUNTIME_EVIDENCE_CONFIG_DISABLED`, `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`,
  `TINY_LIVE_LOSS_HARD_STOP`, `TINY_LIVE_ROLLOUT_NOT_READY`, and
  `SIGNAL_POLICY_REVIEW_GAPS`. Treat this as the latest recorded blocker set
  for traceability, not as a substitute for rerunning the full read-only bundle
  before any future live-review packet; it is not permission to enable live
  trading.
- 2026-06-21T00:04+08:00 read-only metadata and diagnostic refresh followed
  docs/tooling commit `76b5f00db9e93a249a55f93ff0f87b921ec262bb`. The server
  worktree and deployed `app.commit` still matched
  `ef6253a4ecff7c27a2e709f226e166389700a82d`, while `origin/main` had advanced
  to `76b5f00db9e93a249a55f93ff0f87b921ec262bb`; metadata-only output printed
  `deployment_metadata_status=CURRENT`,
  `origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN`,
  `metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `deploy_required_before_live_review=true`,
  `live_review_packet_allowed=false`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`. The local
  origin-delta classifier still reported docs/tooling-only drift with
  `origin_delta_files=23`, `origin_docs_tooling_delta_files=23`,
  `origin_runtime_delta_files=0`, and `origin_runtime_delta_paths=[]`.
  Diagnostic stale-runtime bundle output still showed the active service
  healthy on port `8084`, local health and server-local `/api/mcp` passing,
  MCP parity `required_tools=[...]`, `missing_required_tools=[]`,
  `[mcp-parity-ssh] OK`, `toolCount=305`, `required=35`, and runtime log
  `PASS` with ERROR count 0 and WARN baseline total 18. The diagnostic bundle
  remained `bundle_verdict=NOT_READY` with blockers
  `LIVE_READINESS_NOT_READY`, `EXECUTION_ELIGIBILITY_NOT_READY`,
  `BACKGROUND_AUTOMATION_REVIEW`, `RUNTIME_EVIDENCE_CONFIG_DISABLED`,
  `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`, `TINY_LIVE_LOSS_HARD_STOP`,
  `TINY_LIVE_ROLLOUT_NOT_READY`, `SIGNAL_POLICY_REVIEW_GAPS`, and
  `DEPLOYED_RUNTIME_NOT_CURRENT`. Order-capable flags were still all false,
  reviewed dry-run flags true, `riskLevel=R0`,
  `missing_readiness_detail_fields=[]`, all nine reviewed background
  automation flags true, runtime evidence disabled with `shadowIntentCount=0`
  and `orderSentEvidence=0`, tiny-live still blocked by the consecutive-loss
  hard stop and rollout gates, `suspiciousNoBuyCount=10`,
  `falseBlockRiskCount=10`, and signal policy remained blocked by
  `TOO_STRICT` governance drift plus `WARN` missed-opportunity regression.
  `scoreBuyPostScoutAdd` was now `executionEligible=true` with state
  `ADD_ON_PULLBACK_READY`, but `scoreBuyPrePosition`,
  `scoreBuyConfirmedDeploy`, and tiny-live remained blocked. This
  is stale-runtime diagnostic/read-only RCA evidence only, not live-readiness
  evidence or live approval.
- 2026-06-20T22:03+08:00 read-only metadata and diagnostic refresh followed
  docs/tooling commit `f84ab1440cc7cc574ca8969203a2ade015dcfce8`. The server
  worktree and deployed `app.commit` still matched
  `ef6253a4ecff7c27a2e709f226e166389700a82d`, while `origin/main` had advanced
  to `f84ab1440cc7cc574ca8969203a2ade015dcfce8`; metadata-only output printed
  `deployment_metadata_status=CURRENT`,
  `origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN`,
  `metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `deploy_required_before_live_review=true`,
  `live_review_packet_allowed=false`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`. The local
  origin-delta classifier still reported docs/tooling-only drift with
  `origin_delta_files=18`, `origin_docs_tooling_delta_files=18`,
  `origin_runtime_delta_files=0`, and `origin_runtime_delta_paths=[]`.
  Diagnostic stale-runtime bundle output still showed the active service
  healthy on port `8084`, local health and server-local `/api/mcp` passing,
  MCP parity `required_tools=[...]`, `missing_required_tools=[]`,
  `toolCount=305`, `required=35`, and runtime log `PASS` with ERROR count 0
  and WARN baseline total 16. The
  diagnostic bundle remained `bundle_verdict=NOT_READY` with blockers
  `LIVE_READINESS_NOT_READY`, `EXECUTION_ELIGIBILITY_NOT_READY`,
  `BACKGROUND_AUTOMATION_REVIEW`, `RUNTIME_EVIDENCE_CONFIG_DISABLED`,
  `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`, `TINY_LIVE_LOSS_HARD_STOP`,
  `TINY_LIVE_ROLLOUT_NOT_READY`, `SIGNAL_POLICY_REVIEW_GAPS`, and
  `DEPLOYED_RUNTIME_NOT_CURRENT`. Order-capable flags were still all false,
  reviewed dry-run flags true, `riskLevel=R0`,
  `missing_readiness_detail_fields=[]`, all nine reviewed background
  automation flags true, runtime evidence disabled with `shadowIntentCount=0`
  and `orderSentEvidence=0`, tiny-live still blocked by the consecutive-loss
  hard stop and rollout gates, and signal policy remained blocked by
  `TOO_STRICT` governance drift plus `WARN` missed-opportunity regression. This
  is stale-runtime diagnostic/RCA evidence only, not live-readiness evidence or
  live approval.
- 2026-06-20T20:53+08:00 read-only metadata and diagnostic refresh followed
  docs/tooling commit `0c033972b4bd39531d0e617d0f2702926108686f`. The server
  worktree and deployed `app.commit` still matched
  `ef6253a4ecff7c27a2e709f226e166389700a82d`, while `origin/main` had advanced
  to `0c033972b4bd39531d0e617d0f2702926108686f`; metadata-only output printed
  `deployment_metadata_status=CURRENT`,
  `origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN`,
  `metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `deploy_required_before_live_review=true`,
  `live_review_packet_allowed=false`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`.
  Read-only stale-runtime diagnostics still showed the service healthy on
  active port `8084`, local health and server-local `/api/mcp` passing, public
  dedicated `/api/mcp` and shared-host `/api/trading/mcp` blocked with 404,
  nginx exact MCP blocks without `proxy_pass`, server-local MCP parity
  `required_tools=[...]`, `missing_required_tools=[]`, `toolCount=305`,
  `required=35`, and runtime log `PASS` with ERROR count 0 and
  WARN baseline total 14. The diagnostic bundle
  remained `bundle_verdict=NOT_READY` with blockers
  `LIVE_READINESS_NOT_READY`, `EXECUTION_ELIGIBILITY_NOT_READY`,
  `BACKGROUND_AUTOMATION_REVIEW`, `RUNTIME_EVIDENCE_CONFIG_DISABLED`,
  `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`, `TINY_LIVE_LOSS_HARD_STOP`,
  `TINY_LIVE_ROLLOUT_NOT_READY`, `SIGNAL_POLICY_REVIEW_GAPS`, and
  `DEPLOYED_RUNTIME_NOT_CURRENT`. Signal correctness remained executable but
  `signalPolicyClear=false` because 7d governance drift was `TOO_STRICT` and
  missed-opportunity regression was `WARN`. This is current stale-runtime
  diagnostic evidence only, not live-readiness evidence or live approval.
- 2026-06-20T21:40+08:00 read-only local origin-delta classifier observed the
  same server worktree commit
  `ef6253a4ecff7c27a2e709f226e166389700a82d` while local `origin/main` was
  `20425dd94eb04edddb5f60fc5eba5facb3c8e456`. It printed
  `origin_delta_local_evidence=true`,
  `origin_delta_status=DOCS_TOOLING_ONLY_DRIFT`, `origin_delta_files=16`,
  `origin_docs_tooling_delta_files=16`, `origin_runtime_delta_files=0`,
  `origin_runtime_delta_paths=[]`, and `live_review_packet_allowed=false`.
  This is routing evidence only: it explains that the current local delta is
  docs/tooling-only, but it does not replace a fresh full read-only
  live-readiness bundle and does not authorize live trading.
- 2026-06-20T21:40+08:00 read-only blocker RCA refresh confirmed the active
  runtime is healthy but still not live-review ready. `audit_live_readiness_ssh`
  reported health `UP`, `runtime_log_status=PASS`, runtime ERROR count 0,
  WARN baseline total 15, `order_capable_flags_true=[]`, all reviewed dry-run
  flags true, `riskLevel=R0`, and `missing_readiness_detail_fields=[]`.
  Background automation stayed blocked with all nine reviewed flags true,
  including high-risk
  `TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED`,
  `EVENT_SCAN_NOTIFICATION_ENABLED`, `EXECUTION_EVENT_ENABLED`,
  `TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED`, and
  `TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED`;
  `backgroundAutomationClear=false` and
  `background_automation_blockers=["HIGH_RISK_BACKGROUND_AUTOMATION_TRUE","BACKGROUND_AUTOMATION_TRUE"]`.
  Runtime evidence remained `diagnosis=CONFIG_DISABLED` with
  `runtimeEvidenceStatus=NOT_READY_ENABLED_FALSE`, `shadowIntentCount=0`, and
  `orderSentEvidence=0`. Tiny-live stayed blocked with
  `hardStopDetected=true`, `autoApprovalMode=BLOCKED`,
  `completedTinyLiveSamples=2`, `falsePositiveCount=2`, and
  `canEnableProduction=false`. Signal correctness found no missed
  evaluation/order bug and current DataFreshnessGuard snapshot was clean, but
  `signalPolicyClear=false` because 7d `governanceMode=TOO_STRICT` and
  missed-opportunity `overallStatus=WARN`. This is read-only RCA evidence only,
  not permission to change env or enable live trading.
- Latest recorded read-only live-readiness bundle observed on
  2026-06-19T12:15+08:00 against server commit
  `224f550478b20a329775f503b3eaa70ba6a2f6a8` while `origin/main` was
  `0eef3ce5c3964e2520c1c5aa16a57e87f0ba26a0`: health UP, deployed metadata
  CURRENT, but the server worktree was not at `origin/main`. The origin hash is
  historical evidence captured at bundle time; later docs or guardrail commits
  can advance `origin/main` without making this recorded snapshot current.
  The audit now explicitly prints `riskLevel=R0`, so event-risk baseline
  evidence is present and `EVENT_RISK_NOT_BASELINE` is no longer part of the
  latest bundle blockers.
  Runtime log smoke failed on two Telegram-send related ERROR lines from
  `TelegramServiceImpl` and `ExecutionEventScheduler`; the deployed runtime
  predates the classified log smoke, so `ERROR category ...` and
  `ERROR rca=TELEGRAM_EXECUTION_EVENT_NOTIFICATION_PATH` must be refreshed
  after the next authorized deploy. MCP parity passed with
  `required_tools=[...]`, `missing_required_tools=[]`, and `toolCount=305
  required=35`. All order-capable
  flags were false and dry-run flags were true, but high-risk background
  automation was already true for external backfills, event/execution
  notification scanning, autonomous digest Telegram, and live-signal retry
  notification. Runtime evidence remained disabled
  (`TRADING_RUNTIME_EVIDENCE_ENABLED=EMPTY`,
  `runtimeEvidenceStatus=NOT_READY_ENABLED_FALSE`, `shadowIntentCount=0`,
  `orderSentEvidence=0`). Tiny-live still had the consecutive-loss hard stop
  (`hardStopDetected=true`, `completedTinyLiveSamples=2`,
  `falsePositiveCount=2`, `canEnableProduction=false`). Signal correctness
  found no missed-evaluation/order bug and current DataFreshnessGuard snapshot
  was clean, but 7d governance drift remained TOO_STRICT and missed-opportunity
  regression was WARN. The bundle printed `live_review_packet_allowed=false`,
  `deploy_required_before_live_review=true`, and verdict NOT_READY with blockers
  `LIVE_READINESS_NOT_READY`, `RUNTIME_HEALTH_OR_LOG_NOT_CLEAN`,
  `EXECUTION_ELIGIBILITY_NOT_READY`, `BACKGROUND_AUTOMATION_REVIEW`,
  `RUNTIME_EVIDENCE_CONFIG_DISABLED`, `RUNTIME_EVIDENCE_NO_SHADOW_INTENT`,
  `TINY_LIVE_LOSS_HARD_STOP`, `TINY_LIVE_ROLLOUT_NOT_READY`,
  `SIGNAL_POLICY_REVIEW_GAPS`, and `DEPLOYED_RUNTIME_NOT_CURRENT`. Treat this
  as stale live-review evidence until the server is refreshed to `origin/main`
  by a separately authorized deploy and the bundle is rerun.
- A recorded read-only deployment metadata refresh on 2026-06-20T09:53+08:00
  observed the server worktree and deployed runtime still at
  `224f550478b20a329775f503b3eaa70ba6a2f6a8`, while `origin/main` had advanced
  to observed origin `4ee52d860fb18f79bd989801c471cd71be5c63d1`. This was
  metadata-only, not a full live-readiness bundle, and only confirms
  `DEPLOYED_RUNTIME_NOT_CURRENT` remains until a separately authorized deploy
  and fresh read-only bundle. The output preserved
  `originMainCommit=<observed origin commit at refresh time>`,
  `metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `deploy_required_before_live_review=true`,
  `live_review_packet_allowed=false`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`. Rerun
  `scripts/smoke_live_deployment_metadata_ssh.ps1` for a current metadata-only
  refresh.
- A historical read-only deployment metadata refresh on 2026-06-20T13:34+08:00
  still observed server worktree and deployed runtime at
  `224f550478b20a329775f503b3eaa70ba6a2f6a8`, while `origin/main` had advanced
  to `873b219171755401c40f3a676fb3c7c9477471ec`. The metadata-only check
  reported `liveBundleOriginStatus=WORKTREE_NOT_ORIGIN_MAIN`,
  `liveBundleDeployStatus=CURRENT`,
  `metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `deploy_required_before_live_review=true`, and
  `bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY`. The default
  `scripts/smoke_live_readiness_bundle_ssh.ps1` fail-fast path then stopped on
  stale metadata before child smokes with
  `bundle_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE","DEPLOYED_RUNTIME_NOT_CURRENT"]`,
  `live_review_packet_allowed=false`, and `bundle_verdict=NO_EVIDENCE`. This is
  currentness/blocker evidence only, not live-readiness evidence.
- A read-only server runtime sanity check on 2026-06-20T10:04+08:00 passed with
  `scripts/verify_server_ssh.ps1 -SkipGitCurrent`: server preflight, active
  port `8084`, non-active port `8085` drained, local health, server-local
  `/api/mcp`, public dedicated health, public dedicated/shared MCP 404 blocks,
  nginx exact MCP blocks, nginx upstreams, and nginx service were healthy for
  the deployed `224f550478b20a329775f503b3eaa70ba6a2f6a8` runtime. Because git
  currentness was skipped and the server worktree/deployed runtime remains
  behind `origin/main`, this is service-health evidence only, not live-readiness evidence,
  and not a substitute for a separately authorized deploy plus the full read-only
  bundle.
- A read-only server-local MCP parity sanity check on 2026-06-20T10:11+08:00
  passed with `scripts/smoke_mcp_parity_ssh.ps1` on the deployed
  `224f550478b20a329775f503b3eaa70ba6a2f6a8` runtime:
  `required_tools=[...]`, `missing_required_tools=[]`, `toolCount=305`, and
  `required=35` through server-local `/api/mcp`. Because deployment metadata
  remains stale relative to `origin/main`, this is MCP reachability evidence
  only; it does not clear
  `DEPLOYED_RUNTIME_NOT_CURRENT` and is not live-readiness evidence.
- A strict read-only runtime-log smoke on 2026-06-20T10:16+08:00 failed against
  active run log
  `/home/ubuntu/agora-trading-api/logs/runs/app-20260618T070102Z-port8084.log`
  with `runtime ERROR lines present: count=2`: one `TelegramServiceImpl` send
  failure and one `ExecutionEventScheduler` scheduled scan failure. This keeps
  `RUNTIME_HEALTH_OR_LOG_NOT_CLEAN` active for the stale deployed runtime.
  `ALLOW_RUNTIME_ERROR=1` remains diagnostic-only and must not be used as
  live-readiness evidence.
- `scripts/smoke_live_deployment_metadata_ssh.ps1` now provides a reusable
  read-only `DEPLOYMENT_METADATA_ONLY` check for that server-currentness
  question. It is faster than the full bundle but deliberately prints
  `live_review_packet_allowed=false`; metadata-only output is not
  live-readiness evidence.
- `scripts/smoke_live_readiness_bundle_ssh.ps1` now prints
  `deployment_metadata_status` and adds `DEPLOYED_RUNTIME_NOT_CURRENT` when
  deployed runtime metadata is missing/unknown or runtime files differ from the
  server worktree, so stale runtime evidence cannot be mistaken for current
  live-readiness. It also classifies SSH/read-only command failures such as
  `SSH_AUTH_FAILED`, `SSH_CONNECT_FAILED`, and `SSH_COMMAND_FAILED` before the
  full bundle completes and
  emits `bundle_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE"]` plus
  `bundle_verdict=NO_EVIDENCE`, so rejected keys, connection problems, or a
  failed child smoke cannot be mistaken for a live blocker result.
- `scripts/smoke_guardrail_acceptance_ssh.ps1` provides a focused read-only
  post-deploy acceptance smoke for the BTC spot anti-wick and event-risk
  guardrail handoffs. It calls server-local `/api/mcp` to verify
  `analyzeSpotAntiWickPolicyCoverage` and `getEventRiskControlStatus` boundary,
  policy, and operator-control markers without changing
  order/OCO/strategy/grid/fund/Earn/Telegram/DB state.
- Shared-DB Flyway baseline prep now includes
  `scripts/schema_baseline_generate_server.sh`, which re-runs shared-mode
  compare and dumps reviewable trading entity DDL for `V1__baseline.sql` without
  enabling Flyway or cleaning extra shared tables.

## Exchange Rate Runtime

Keep static fallback behavior for:

- Local dev without `AGORA_MARKET_INTERNAL_API_KEY`.
- AgoraMarketAPI downtime.
- Timeout or `401` during transition.

Fresh-machine build prerequisite:

```powershell
mvn -f C:\Users\Redan\IdeaProjects\AgoraMarketAPI\internal-client\pom.xml install
```

## Acceptance And Deploy

AgoraMarketAPI deployment/acceptance runbook:

- `C:\Users\Redan\IdeaProjects\AgoraMarketAPI\docs\split-service-acceptance-deploy.md`

Current standalone trading acceptance handoff:

- `docs/split-acceptance-status.md`

Trading deployment prep:

- Trading has a deploy skeleton in `deploy.sh`.
- `scripts/bootstrap_server.sh` can clone/fetch the repo on the server and write a non-secret env template.
- 2026-06-05 server preflight confirmed AgoraMarketAPI is healthy on local port `8080`.
- 2026-06-05 server bootstrap installed `/home/ubuntu/agora-trading-api` and verified fast-forward from `origin/main`.
- 2026-06-05 server bootstrap created `/home/ubuntu/agora-trading-api/.env.trading.secrets.example`.
- 2026-06-05 server configuration created `/home/ubuntu/.env.trading.secrets` without printing secret values.
- 2026-06-05 server configuration created an independent MySQL database for an earlier standalone-DB path. The current target is code split only with shared `agora_market` DB.
- 2026-06-05 server configuration installed nginx `/api/trading/` routing.
- 2026-06-05 observed deployment snapshot used `origin/main` commit `11612b9`; this is historical evidence, not a current-deployment claim.
- 2026-06-05 trading service started on active port `8084`.
- 2026-06-05 `scripts/verify_server.sh` passed with public health check:
  - `https://agoramarketapi.purrtechllc.com/api/trading/actuator/health`
- Production defines `AGORA_MARKET_INTERNAL_API_KEY` in `/home/ubuntu/.env.trading.secrets`, so trading can call AgoraMarket exchange rates and still fall back on timeout or failure.
- 2026-06-13 production deploy advanced `/home/ubuntu/agora-trading-api` to
  the then-current `origin/main` and switched the active blue-green port
  recorded in `app.port`.
- 2026-06-13 post-deploy verification passed: server worktree matched
  `origin/main`, deployed `app.commit` matched `HEAD`, local health passed,
  MCP registry check passed through the then-current legacy context path
  `/api/trading/mcp`, AgoraMarket dependency
  health passed through the stable AgoraMarketAPI nginx vhost, public trading health passed through nginx,
  and nginx service was active.
- 2026-06-13 shared-mode schema compare passed through
  `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh`: 39 source
  entity tables, 0 implicit entity names, 0 forbidden marketplace source
  mappings, 175 database tables, 0 missing trading tables, 136 extra
  marketplace/shared tables expected in shared DB mode.
- 2026-06-13 production MCP parity passed on the active service:
  the then-current legacy context path `/api/trading/mcp` registered 303 tools
  and included the 21 representative trading tools checked by the parity smoke.
- 2026-06-13 read-only validate smoke showed schema validation can start after
  aligning `market_indicator_history.error_flag` mapping with MySQL
  `tinyint(1)`/Boolean semantics.
- 2026-06-13 hardened schema deploy moved production to
  `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`,
  `SPRING_FLYWAY_ENABLED=true`, and
  `SPRING_FLYWAY_TABLE=trading_flyway_schema_history`; Flyway successfully
  created the Trading-owned history table and baselined version `1`.
- 2026-06-13 post-hardening schema compare passed through
  `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh`: 39 source
  entity tables, 0 implicit entity names, 0 forbidden marketplace source
  mappings, 176 database tables, 2 known system tables, 0 missing trading
  tables, and 137 extra marketplace/shared tables expected in shared DB mode.
- 2026-06-14 production deploy advanced `/home/ubuntu/agora-trading-api` to
  `02fd886`, switched the active blue-green port to `8084`, and updated
  `AGORA_MARKET_BASE_URL` to the stable AgoraMarketAPI nginx vhost
  `https://agoramarketapi.purrtechllc.com`.
- 2026-06-14 post-deploy verification passed: server worktree matched
  `origin/main`, deployed `app.commit` matched `HEAD`, local health passed,
  MCP registry check passed through the then-current legacy context path
  `/api/trading/mcp`, AgoraMarket dependency
  health passed through the stable AgoraMarketAPI nginx vhost, public trading
  health passed through nginx, and nginx service was active.
- 2026-06-14 verifier/docs updates advanced the server worktree beyond the
  deployed runtime commit without a runtime deploy. `scripts/verify_server.sh`
  passed because deployed `app.commit` `02fd886` differed from worktree `HEAD`
  only by docs/tooling files; runtime drift still classified as
  `deploy_needed=no`. Treat the latest verifier output, not a stale handoff
  SHA, as the current worktree evidence. The same maintenance pass confirmed
  post-startup WARN/ERROR counts are 0 for the running trading service.
- 2026-06-14 shared-mode schema compare was rerun through
  `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh`: 39 source
  entity tables, 0 implicit entity names, 0 forbidden marketplace source
  mappings, 176 database tables, 2 known system tables, 0 missing trading
  tables, and 137 extra marketplace/shared tables expected in shared DB mode.
- 2026-06-14 historical pre-internal-only production MCP parity smoke passed
  through `/api/trading/mcp` with 303 registered tools and all 21
  representative Trading tools present; this public route is now superseded by the MCP
  internal-only policy.
- 2026-06-14 scheduler-alias deploy advanced the runtime to `6a656fe`,
  switched the active blue-green port to `8085`, and kept
  `AGORA_MARKET_BASE_URL` on the stable AgoraMarketAPI nginx vhost.
- 2026-06-14 post-alias verification passed: server worktree matched
  `origin/main`, deployed `app.commit` matched `HEAD`, local health passed,
  MCP registry check passed through the then-current legacy context path
  `/api/trading/mcp`, AgoraMarket dependency
  health passed, public trading health passed through nginx, schema compare
  passed in shared mode, post-ready WARN/ERROR counts were 0, and direct
  production smoke confirmed the read-only `listSchedulerTasks` compatibility
  alias.
- 2026-06-14 production MCP parity now includes 304 registered tools: the 21
  representative Trading tools plus the read-only `listSchedulerTasks`
  scheduler-list alias.
- 2026-06-14 post-alias local validation passed with
  `.\scripts\verify_local.ps1` and
  `.\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180`; the
  local Spring context registered 304 MCP tools.
- 2026-06-14 maintenance server verification passed after the docs-only
  handoff refresh at `1585942`: deployed `app.commit` remained `6a656fe`,
  the delta was docs/tooling-only, local/public health and local MCP passed,
  AgoraMarket exchange-rate dependency health passed, nginx was active, and
  shared-mode schema compare still found 39 source entity tables, 0 missing
  trading tables, and 137 expected extra marketplace/shared tables.
- 2026-06-14 cross-service live MCP ownership smoke passed from
  `AgoraMarketAPI/tools/codex/check-live-mcp-split-ownership.ps1`:
  AgoraMarketAPI `/api/mcp` exposed 153 marketplace/system/internal tools with
  representative legacy Trading tools absent, while `agora-trading-api`
  exposed 304 tools with representative Trading tools present through the
  then-current legacy context path `/api/trading/mcp`.
- 2026-06-15 nginx added the dedicated Trading API host
  `https://agoratradingapi.purrtechllc.com/api`, mapping public `/api/*` to the
  standalone service's `/api/trading/*` paths on the active port. Smoke showed
  `/api/actuator/health` returned `UP`, `POST /api/mcp` returned 304 Trading
  tools including `previewPositionSizing` and `getTradingManagerDigest`, and
  marketplace `updateCartItem` remained absent from the dedicated Trading host.
- 2026-06-15 production deploy advanced runtime to `1cb9e60` on active port
  `8085`. Post-deploy `scripts/verify_server.sh` passed with server worktree,
  `origin/main`, and deployed `app.commit` all matching `1cb9e60`; dedicated
  host health passed at `https://agoratradingapi.purrtechllc.com/api/actuator/health`.
  During the first deploy, the dedicated host briefly returned 502 because its
  upstream still pointed at the drained old port after the shared
  `/api/trading/` route switched. Commit `1cb9e60` fixed `deploy.sh` and
  `scripts/install_nginx_path.sh` so both shared-host and dedicated-host
  upstreams follow the active blue-green port.
- Server verification now supports `PUBLIC_TRADING_MCP_URL` and
  `PUBLIC_TRADING_CONTEXT_MCP_BLOCKED_URL`, and deploy passes the dedicated
  `https://agoratradingapi.purrtechllc.com/api/mcp` plus shared
  `https://agoramarketapi.purrtechllc.com/api/trading/mcp` URLs by default
  when nginx is updated. Dedicated Trading MCP must be reachable with bearer
  auth; server-local `/api/mcp` remains the operator/verification path; shared
  `/api/trading/mcp` remains a public block target.
- 2026-06-15 production deploy advanced runtime to `31af005` on active port
  `8085`. Post-deploy verification and full read-only schema compare passed:
  39 source entity tables, 0 missing DB tables, 176 DB tables, 2 known system
  tables, and 137 extra marketplace/shared tables expected in shared DB mode.
  Historical dedicated-host MCP smoke reported exactly 304 Trading tools before
  Trading MCP was made internal-only.
- 2026-06-15 runtime-log smoke deploy advanced runtime to `7e02307` on active
  port `8084`. The deploy post-verifier passed with shared-mode schema compare,
  dedicated-host health, historical dedicated-host MCP `tools/list` with 304
  Trading tools, and nginx shared/dedicated upstreams on active port `8084`.
  This public MCP route is now superseded by the MCP internal-only policy. The full
  `scripts/verify_split_acceptance_ssh.ps1` pass also checked the active run log:
  0 runtime ERROR lines, WARN lines matching the known baseline, and no
  high-risk trading/OCO/grid/Earn/fund operation-like lines in the recent log
  tail. Runtime log smoke now prints known WARN category counts, so future total
  count movement can be explained by warning class. Cross-service MCP ownership
  smoke reported AgoraMarketAPI 155 tools with representative Trading tools
  absent and `agora-trading-api` 304 Trading tools present.
- 2026-06-16 public Trading MCP was made internal-only in production. Runtime
  commit `5cc6782` is active on port `8084`; deploy post-verification,
  `scripts/verify_server_ssh.ps1 -SchemaCompare`, and
  `scripts/verify_split_acceptance_ssh.ps1` all passed. Public dedicated
  `https://agoratradingapi.purrtechllc.com/api/mcp` and public shared-host
  `https://agoramarketapi.purrtechllc.com/api/trading/mcp` both returned HTTP
  404, while the then-current server-local legacy context path
  `/api/trading/mcp` `getMcpRegistryVersion` check passed before the later
  `/api/mcp` canonical-path deploy.
  Schema compare remained in shared mode with 39 source entity tables, 0
  missing DB tables, and 137 expected extra shared/marketplace tables. Runtime
  log smoke found 0 ERROR lines and no high-risk trading/OCO/grid/Earn/fund
  operation-like lines in the recent tail; cross-service MCP ownership smoke
  reported AgoraMarketAPI 155 tools with representative Trading tools absent
  and `agora-trading-api` 304 Trading tools present.
- 2026-06-16 MCP path deploy advanced production runtime to commit `efff0d2`
  on active port `8085`. Server-local MCP now verifies through `/api/mcp`;
  `/api/trading/mcp` is not a standalone MCP endpoint and remains only a public
  shared-host block target. Public dedicated
  `https://agoratradingapi.purrtechllc.com/api/mcp` and public shared-host
  `https://agoramarketapi.purrtechllc.com/api/trading/mcp` both returned HTTP
  404. Dedicated public health returned `UP` at
  `https://agoratradingapi.purrtechllc.com/api/actuator/health`; strict
  post-drain verification confirmed non-active port `8084` had no listener.
  Shared-mode schema compare found 39 source entity tables, 0 missing DB
  tables, and 137 expected extra shared/marketplace tables. Full split
  acceptance passed with runtime `ERROR` count 0, no high-risk
  trading/OCO/grid/Earn/fund operation-like lines, AgoraMarketAPI 155 tools
  with representative Trading tools absent, and `agora-trading-api` 304 Trading
  tools present through server-local `/api/mcp`.
- Deploy hardening now keeps the nginx Trading route rewrite in
  `scripts/rewrite_nginx_trading_routes.awk` with a local regression fixture
  in `scripts/test_nginx_route_rewrite.ps1`, so nested nginx `location {}`
  blocks cannot cause public dedicated-host MCP to remain proxied. Windows
  deploys should use `scripts/deploy_ssh.ps1` for durable remote
  `logs/deploy` output, and server verification fails if the non-active
  blue-green port still has a listener.
- 2026-06-17 local handoff batch is ahead of the deployed runtime until it is
  explicitly pushed, deployed, and verified. Local `scripts/verify_local.ps1`
  passed with 51 tests, 305 MCP tools registered in the local-smoke Spring
  context,
  split-boundary/schema-inventory/script-syntax/post-deploy-guardrail checks
  OK, and the stale Flyway wording guard now rejects pre-baseline, legacy V10x
  migration, and generic follow-up migration wording in docs/source comments.
  After the signal-correctness parity-list expansion, local
  `scripts/smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180` also passed
  with MCP parity `required_tools=[...]`, `missing_required_tools=[]`, and
  `toolCount=305 required=35` on local `/api/mcp`; local health was OK.
  The reusable MCP parity smoke has matching local and SSH required-tool lists:
  local smoke invokes `scripts/smoke_mcp_parity.ps1`, the post-deploy issue
  wrapper invokes `scripts/smoke_mcp_parity_ssh.ps1` before the guardrail,
  signal-correctness, and trailing replay smokes, and `scripts/verify_local.ps1`
  fails on any required-tool divergence across the three scripts. H2 local smoke
  executes the H2-compatible read-only parity calls; MySQL-backed governance
  drift/relaxation/tightening diagnostics are executable in the server-local SSH
  parity smoke. This is local readiness only; #1/#2/#3 closure still requires
  deployed server-local read-only acceptance after an explicitly authorized
  deploy.
- 2026-06-18 local verification passed again through the current local handoff
  branch tip; GitHub issues #1/#2/#3 record the exact latest evidence commit.
  `scripts/verify_local.ps1` passed with 51 tests, 305 MCP tools registered in the
  local-smoke Spring context, split-boundary/schema-inventory/script-syntax and
  post-deploy guardrail checks OK. The reviewed shared-DB baseline guard now
  covers both the committed `V1__baseline.sql` and
  `scripts/schema_baseline_generate_server.sh`, so a future guarded baseline
  dump cannot reintroduce pre-review Flyway wording or imply extra-table cleanup.
  The post-deploy issue acceptance wrapper also carries any custom `-EnvFile`
  through split acceptance, server verification, and server-local MCP smokes so
  issue-closure evidence is collected against one consistent runtime
  configuration. Windows SSH wrappers validate `SshHost` locally before
  invoking `ssh`, so deploy and acceptance tooling rejects option-like targets
  before remote execution. The signal-correctness SSH smoke now hard-fails when
  `verifyStrategyExecution` does not provide the expected
  no-missed-evaluation/no-missed-order marker, so issue acceptance cannot pass
  on a missing strategy-execution parity result. A follow-up boundary-only
  check passed with `scripts/verify_split_boundaries.ps1`: 39 explicit entity
  tables, 0 implicit entity names, 0 forbidden marketplace mappings, 0 unsafe
  table names, and env-template coverage for 12 required server keys. Local
  HTTP/MCP smoke also passed with
  `scripts/smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180`, including
  `[mcp-parity] OK http://127.0.0.1:18084/api/mcp toolCount=305 required=35`
  plus `required_tools=[...]` and `missing_required_tools=[]`; local
  `/api/actuator/health` was OK.
  The post-deploy issue acceptance wrapper now reserves `CLOSURE_READY OK` for
  the full closure mode only: split acceptance, no-review-gaps guardrail smoke,
  signal-correctness smoke, and hard trailing replay acceptance must all pass.
  This remains local readiness only until pushed, deployed, and verified on the
  server.
- 2026-07-07 trailing-stop PnL review now has a read-only parameter sweep MCP
  and SSH smoke: `analyzeTrailingStopParameterSweep` plus
  `scripts/smoke_trailing_stop_parameter_sweep_ssh.ps1`. The sweep compares
  the current +0.5/+1.0/+1.0 ATR policy against a bounded grid and emits
  `currentPolicySummary`, `bestPolicySummary`, `bestVsCurrentDeltaPnl`,
  `topCandidates`, and `REVIEW_PARAMETER_CANDIDATE_NOT_LIVE` when a better
  candidate exists. This is evidence for separate design review only; it does
  not change scheduler constants, deploy, change production env, enable
  trailing/live trading, change strategy opt-in, place orders, modify OCO, send
  Telegram, or mutate DB/grid/fund/Earn/exchange state.
- 2026-06-25 Grid trend adjustment review is covered by a reusable read-only
  SSH smoke: `scripts/smoke_grid_trend_adjustment_review_ssh.ps1`. The smoke
  calls server-local `/api/mcp` only, invokes `getGridTrendAdjustmentReview`,
  and requires `boundary=READ_ONLY`, `mutationAllowed=false`,
  `orderAllowed=false`, `gridMutationAllowed=false`,
  `schedulerChangeAllowed=false`, `telegramSendAllowed=false`, `trend1h=`,
  `trend4h=`, `trendAlignment=`,
  `decisionSet=KEEP,PAUSE,WATCH,REBUILD_REVIEW,RESIZE_REVIEW`,
  `automationAllowed=false`, and `recommendation=` markers. It emits
  `grid_trend_adjustment_review_packet`,
  `grid_trend_adjustment_review_status`, and
  `grid_trend_adjustment_recommendation` for operator review, but it does not
  create, pause, resume, close, or rebuild grids; place orders; send Telegram;
  deploy; change production env; or mutate scheduler, DB, OCO, fund, Earn, or
  exchange state. Scheduler integration and any grid execution action remain a
  separate explicitly authorized phase. Smoke framework v1 keeps the SSH/env
  wrapper in PowerShell while moving JSON-RPC calling, marker validation, and
  packet rendering for this grid-trend case into the Java runner
  `com.agora.trading.smoke.McpSmokeCli`.
- 2026-06-29 Grid trend review now has a read-only resize/rebuild operator
  packet wrapper: `scripts/prepare_grid_resize_rebuild_operator_packet_ssh.ps1`.
  It calls server-local `/api/mcp` only, combines
  `getGridTrendAdjustmentReview`, `listGrids`, `getGridPriceAlignment`,
  `getCurrentExposure`, and `getEventRiskControlStatus`, and emits
  `GRID_RESIZE_REBUILD_OPERATOR_PACKET`,
  `grid_resize_rebuild_operator_status`,
  `grid_resize_rebuild_operator_review_ready`,
  `grid_resize_rebuild_candidate_grid_ids`, capital/range deltas, event-risk
  gate evidence, and per-grid `PREVIEW_ONLY` candidate plans. The packet can
  return `READY_FOR_GRID_RESIZE_REBUILD_OPERATOR_REVIEW_NOT_MUTATION`, but it
  keeps `production_env_change_allowed=false`, `deploy_allowed=false`,
  `close_grid_allowed=false`, `create_grid_allowed=false`,
  `grid_mutation_allowed=false`, `scheduler_enablement_allowed=false`,
  `order_allowed=false`, `oco_mutation_allowed=false`, and
  `telegram_send_allowed=false`; any actual close/recreate/resize/rebalance
  remains a separate explicitly authorized mutation phase.
  The first production read-only run after the wrapper was added returned
  `READY_FOR_GRID_RESIZE_REBUILD_OPERATOR_REVIEW_NOT_MUTATION`: active grids
  `#10` and `#11` both had `RESIZE_REVIEW`, `eventRiskGate=CLEAR_EVENT_RISK_R0`,
  `missingEvidence=[]`, `remainingExecutionBlockersBeforeMutation=[]`, and
  aggregate candidate capital `30.0` USDT under the reviewed 30 USDT cap. This
  is resize/rebuild review evidence only and still does not authorize
  closeGrid/createGrid/rebalance/order/OCO/env/deploy actions.
- 2026-06-25 Grid opening now has a repeatable read-only readiness packet:
  `scripts/prepare_grid_open_readiness_packet_ssh.ps1`. It calls server-local
  `/api/mcp` only, combines `getGridTrendAdjustmentReview`, `listGrids`,
  `getGridPriceAlignment`, `getCurrentExposure`, and
  `getEventRiskControlStatus`, and emits `GRID_OPEN_READINESS_PACKET`,
  `grid_open_readiness_status`, `grid_open_readiness_blockers`, and
  `grid_open_readiness_required_evidence`. The packet ranks blockers such as
  missing replayable grid candidate plan, unfavorable trend regime, non-R0
  event risk, historical `SELL_FAILED` reconciliation, and grid/OKX/Earn env
  flag state before any separate operator review. It does not deploy, restart,
  change production env, enable grid, create/pause/resume/close/rebalance
  grids, place orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/
  exchange state. `scripts/test_grid_open_readiness_packet.ps1` and
  `scripts/verify_local.ps1` guard the read-only route, markers, docs coverage,
  and local unsafe-argument failures.
- 2026-06-25 Grid open readiness now includes `grid_candidate_plan` from a
  read-only `md_kline` replay. The candidate fields include entry reference
  price, candidate lower/upper range, grid count, per-level capital, total
  candidate capital, stop-out bounds, replay row count/window, inside-range
  rate, stop-break count, and replay score. This can satisfy the explicit
  range/capital/stop plan evidence requirement when complete, but it remains
  review evidence only: it is not `createGrid` input authorization and it does
  not override trend-regime, event-risk, historical `SELL_FAILED`, OKX
  enablement, scheduler, or live-trading blockers.
  `scripts/test_grid_candidate_plan_packet.ps1` guards those markers and local
  unsafe-argument failures.
- Grid open readiness now separates historical dust-only `SELL_FAILED` rows
  from material sell failures. Dust-only stale rows are emitted as
  `historical_dust_sell_failed_count` and the warning
  `HISTORICAL_GRID_DUST_SELL_FAILED_REVIEW_NOT_BLOCKING`; material rows still
  keep `HISTORICAL_GRID_SELL_FAILED_RECONCILIATION_REQUIRED` until reconciled.
- Grid open readiness now also emits `grid_open_gate_review` and
  `grid_open_operator_authorization_required`, so trend-regime, event-risk, and
  OKX-env blockers have explicit clear conditions before any separate grid-open
  operator packet. The fields remain read-only review evidence and do not
  authorize production env changes, `createGrid`, scheduler enablement, orders,
  Telegram sends, or DB/grid/fund/Earn/exchange mutation.
- Grid opening now also has a read-only operator packet wrapper:
  `scripts/prepare_grid_open_operator_packet_ssh.ps1`. It consumes the
  readiness packet, emits `GRID_OPEN_OPERATOR_PACKET`,
  `grid_open_operator_status`, gate statuses, missing requirements, proposed
  separate env diff, reviewed `createGrid` inputs from the candidate plan,
  `trendOverrideRiskEnvelope`, `eventRiskOverrideRiskEnvelope`,
  `combinedOverrideRiskEnvelope`, `okxGridEnvPreflightEnvelope`, and
  post-authorization verification steps. The
  override envelopes report risk grade, risk points, replay score, stop-break
  rows, recommended capital cap, effective review capital cap, event
  `riskLevel`, and required override documents/conditions as decision support
  only; `R3` is explicitly not recommended for override. The OKX/grid env
  preflight envelope reports masked credential readiness plus
  `TRADING_OKX_ENABLED`, `TRADING_GRID_ENABLED`, scheduler, recovery, and Earn
  flag state before any separate env-diff authorization. A blocked status keeps grid opening
  closed until trend/event-risk/OKX gates clear or receive separate written
  override/authorization; a ready status is still review-only and does not
  authorize production env changes, `createGrid`, scheduler/recovery
  enablement, orders, OCO, Telegram, deploy, restart, or DB/grid/fund/Earn/
  exchange mutation.
- Grid candidate selection now has a bounded read-only parameter sweep:
  `scripts/prepare_grid_candidate_parameter_sweep_ssh.ps1`. It invokes only
  `prepare_grid_open_operator_packet_ssh.ps1` across reviewed `GridCount`,
  `PerLevelUsdt`, `StopOutPct`, and `CandidateHalfWidthPct` combinations, then emits
  `GRID_CANDIDATE_PARAMETER_SWEEP_PACKET`,
  `grid_candidate_parameter_sweep_rows`,
  `grid_candidate_parameter_sweep_best_candidate`,
  `grid_candidate_parameter_sweep_best_quality_candidate`,
  `grid_candidate_parameter_sweep_remaining_blockers`, and
  `grid_candidate_parameter_sweep_status`. A reviewable candidate requires a
  complete plan, replay score >= 70, zero replay stop-break rows, and capital
  within the effective review cap. `CandidateHalfWidthPct=0` keeps the existing
  ATR/trend-derived range; explicit values are candidate range evidence only.
  `gridCount=2` is accepted only as a micro-grid review lane because runtime
  `createGrid` supports `gridCount(2-50)`; it can reduce reviewed candidate
  capital but remains evidence only. A quality candidate can clear replay
  quality while still leaving trend, capital, env, or createGrid authorization
  blocked.
  The sweep is evidence only: it does not
  change env, deploy, restart, call `createGrid`, enable grid/scheduler or
  recovery, place orders, modify OCO, send Telegram, or mutate
  DB/grid/fund/Earn/exchange state.
- Grid trend clearance now has a read-only watch packet:
  `scripts/prepare_grid_trend_clearance_watch_packet_ssh.ps1`. It consumes the
  grid open operator packet and emits `GRID_TREND_CLEARANCE_WATCH_PACKET`,
  `grid_trend_clearance_watch_status`, `trendDistanceToSidewaysPct`,
  direction-to-clear, override capital caps, clearance criteria, abort
  criteria, and next verification steps. This turns the trend blocker into
  replayable watch evidence while keeping the same non-mutation boundary: it
  does not clear the trend gate, change production env, call `createGrid`,
  enable grid/scheduler/recovery, place orders, modify OCO, send Telegram,
  deploy, restart, or mutate DB/grid/fund/Earn/exchange state.
- Grid MCP/tool coverage now has a read-only coverage packet:
  `scripts/prepare_grid_mcp_tool_coverage_packet_ssh.ps1`. It calls
  server-local `/api/mcp` `tools/list` only and emits
  `GRID_MCP_TOOL_COVERAGE_PACKET`, `grid_mcp_tool_coverage_status`,
  `readOnlyReviewTools`, `futureActionToolsPresentButNotInvoked`,
  `boundaryContextTools`, and `missingRequiredTools`. This proves grid review,
  future grid action, OCO, Earn, fund, and scheduler boundary tool registration
  before separate operator review while explicitly not invoking `createGrid`,
  `pauseGrid`, `resumeGrid`, `closeGrid`, `enableGridAutoRebalance`, OCO,
  Earn, fund, scheduler, order, Telegram, deploy, env, or DB/exchange mutation
  paths.
- Grid open review now has a consolidated read-only decision snapshot:
  `scripts/prepare_grid_open_decision_snapshot_ssh.ps1`. It combines the
  trend-clearance watch and MCP tool coverage packets into
  `GRID_OPEN_DECISION_SNAPSHOT`, emitting
  `grid_open_decision_snapshot_status`, `grid_open_ready_for_authorization`,
  `grid_open_decision_snapshot_remaining_blockers`,
  `grid_open_decision_snapshot_operator_authorization_required`, and
  `grid_open_allowed=false`. This gives the operator one quantitative handoff
  for trend distance, replay score, stop-break rows, effective capital cap,
  MCP coverage, gate statuses, remaining blockers, and required separate
  env/createGrid authorization while preserving the same non-mutation boundary:
  it does not deploy, restart, change production env, call `createGrid`, enable
  grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate
  DB/grid/fund/Earn/exchange state.
- Grid trend-regime override now has a separate read-only review packet:
  `scripts/prepare_grid_trend_override_review_packet_ssh.ps1`. It consumes the
  consolidated decision snapshot and emits
  `GRID_TREND_OVERRIDE_REVIEW_PACKET`,
  `grid_trend_override_review_status`,
  `grid_trend_override_review_ready`, `trend_override_allowed=false`, and
  `grid_open_allowed=false`. The packet packages trend percentage, distance to
  sideways, replay score, stop-break rows, effective capital cap, event-risk
  gate, MCP coverage, canonical risk grade source, direct-threshold mismatch
  diagnostics, hard blockers, abort criteria, and required separate
  trend/env/createGrid authorization for operator review. It is review evidence
  only and does not approve the override, deploy, restart, change production
  env, call `createGrid`, enable grid/scheduler/recovery, place orders, modify
  OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange state.
- Grid OKX/env authorization now has a separate read-only preflight packet:
  `scripts/prepare_grid_env_diff_preflight_packet_ssh.ps1`. It consumes the
  grid open operator packet and trend-override review packet, then emits
  `GRID_ENV_DIFF_PREFLIGHT_PACKET`, `grid_env_diff_preflight_status`,
  `grid_env_diff_review_ready`,
  `grid_env_diff_preflight_proposed_env_diff`,
  `production_env_change_allowed=false`, and `grid_open_allowed=false`. The
  packet isolates masked OKX credential readiness, `TRADING_OKX_ENABLED`,
  `TRADING_GRID_ENABLED`, scheduler/recovery/Earn flag state, event-risk gate,
  trend-override readiness, already-applied target flags, pending env diff
  flags, pre-apply requirements, and post-apply read-only verification. Already
  applied target flags are evidence, not blockers; the pre-apply path blocks
  only when the whole env diff is already applied and post-env verification
  should be used. It is not authorization to change env, deploy, restart, call
  `createGrid`, enable grid/scheduler/recovery, place orders, modify OCO, send
  Telegram, or mutate DB/grid/fund/Earn/exchange state.
- Grid createGrid authorization now has a separate read-only preflight packet:
  `scripts/prepare_grid_create_authorization_preflight_packet_ssh.ps1`. It
  consumes the grid open operator packet and env-diff preflight packet, then
  emits `GRID_CREATE_AUTHORIZATION_PREFLIGHT_PACKET`,
  `grid_create_authorization_preflight_status`,
  `grid_create_authorization_review_ready`, `create_grid_allowed=false`, and
  `grid_open_allowed=false`. The packet packages reviewed createGrid inputs,
  replay score, trend/event/OKX gates, capital cap checks against
  `effectiveReviewCapitalCapUsdt`, blockers, missing evidence, required
  pre-create authorizations, and post-create read-only verification. It is not
  authorization to change env, deploy, restart, call `createGrid`, enable
  grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate
  DB/grid/fund/Earn/exchange state.
- Grid capital-cap override review now has a separate read-only packet:
  `scripts/prepare_grid_capital_override_review_packet_ssh.ps1`. It consumes
  the create authorization preflight packet when the blocker is
  `CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP`, then emits
  `GRID_CAPITAL_OVERRIDE_REVIEW_PACKET`,
  `grid_capital_override_review_status`,
  `grid_capital_override_review_ready`, `capital_override_allowed=false`,
  `create_grid_allowed=false`, and `grid_open_allowed=false`. The packet
  quantifies the requested cap raise, required multiplier, reviewed createGrid
  inputs, replay score, stop-break rows, trend risk grade, event-risk gate,
  hard blockers, approval conditions, abort criteria, and post-approval
  read-only verification. It is not authorization to approve a capital
  override, change env, deploy, restart, call `createGrid`, enable
  grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate
  DB/grid/fund/Earn/exchange state.
- Grid open authorization now has a consolidated read-only bundle:
  `scripts/prepare_grid_open_authorization_bundle_ssh.ps1`. It consumes the
  capital-cap override review packet and emits
  `GRID_OPEN_AUTHORIZATION_BUNDLE_PACKET`,
  `grid_open_authorization_bundle_status`,
  `grid_open_authorization_bundle_ready`, `grid_open_allowed=false`, and
  `create_grid_allowed=false`. The bundle consolidates trend-regime override,
  capital-cap override, production env diff, post-env read-only verification,
  and createGrid authorization lanes into one operator-review handoff with
  remaining execution blockers and required authorization order. It is not
  authorization to approve any override, change env, deploy, restart, call
  `createGrid`, enable grid/scheduler/recovery, place orders, modify OCO, send
  Telegram, or mutate DB/grid/fund/Earn/exchange state.
- The grid open authorization bundle accepts a fresh `trendGate` clearance as
  `CLEAR_BY_FRESH_TREND_GATE_NOT_MUTATION`. This prevents a
  `NO_TREND_OVERRIDE_NEEDED...` trend packet from incorrectly blocking the
  consolidated operator request while keeping trend override approval, env
  changes, deploy, and `createGrid` separate.
- Grid open operator authorization now has a read-only request packet:
  `scripts/prepare_grid_open_operator_authorization_request_ssh.ps1`. It
  consumes the authorization bundle and emits
  `GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_PACKET`,
  `grid_open_operator_authorization_request_status`,
  `grid_open_operator_authorization_request_ready`,
  `grid_open_allowed=false`, and `create_grid_allowed=false`. The packet
  renders separate copyable request lines for trend-regime override only when
  the trend gate remains blocked; a fresh `trendGate` clearance is recorded as
  no separate trend-regime override required unless the gate becomes blocked
  again. Capital-cap override, production env diff, deploy/restart plus
  post-env verification, and createGrid review remain separate approvals. The
  request packet now separates `coveredCreateReviewBlockers` from
  `uncoveredCreateReviewBlockers`, so a `CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP`
  create preflight blocker can remain visible while being covered by a ready
  capital-cap override authorization line. Every approval and mutation flag
  remains false. It is not authorization to approve any request, change env,
  deploy, restart, call `createGrid`, enable grid/scheduler/recovery, place
  orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange
  state.
- Grid post-env verification now has a read-only plan packet:
  `scripts/prepare_grid_post_env_verification_plan_ssh.ps1`. It consumes the
  operator authorization request and emits
  `GRID_POST_ENV_VERIFICATION_PLAN_PACKET`,
  `grid_post_env_verification_plan_status`,
  `grid_post_env_verification_plan_ready`, `deploy_allowed=false`,
  `grid_open_allowed=false`, and `create_grid_allowed=false`. The packet
  packages the required post-env read-only commands, pass criteria, abort
  criteria, and refreshed createGrid inputs that must still match after any
  separately authorized env/deploy/restart. It is not authorization to change
  env, deploy, restart, call `createGrid`, enable grid/scheduler/recovery,
  place orders, modify OCO, send Telegram, or mutate
  DB/grid/fund/Earn/exchange state.
- Grid post-env verification now also has a read-only execution bundle:
  `scripts/prepare_grid_post_env_read_only_verification_bundle_ssh.ps1`. It
  invokes split acceptance plus fresh grid plan, decision snapshot, trend
  override, env diff, createGrid preflight, authorization bundle, and operator
  authorization request packets with child heartbeat/timeout markers, then emits
  `GRID_POST_ENV_READ_ONLY_VERIFICATION_BUNDLE`,
  `grid_post_env_read_only_verification_status`,
  `grid_post_env_read_only_verification_ready`, `deploy_allowed=false`,
  `grid_open_allowed=false`, and `create_grid_allowed=false`. Current env-not-
  applied states are explicit blockers such as
  `POST_ENV_TRADING_OKX_ENABLED_NOT_TRUE` and
  `POST_ENV_TRADING_GRID_ENABLED_NOT_TRUE`; those keep createGrid review
  blocked until a separately authorized env diff/deploy is completed and this
  bundle is rerun. It is not authorization to change env, deploy, restart, call
  `createGrid`, enable grid/scheduler/recovery, place orders, modify OCO, send
  Telegram, or mutate DB/grid/fund/Earn/exchange state.
- Grid open blockers now have a read-only priority board:
  `scripts/prepare_grid_open_blocker_priority_board_ssh.ps1`. It consumes the
  pre-env operator authorization request and the post-env verification bundle,
  through bounded child execution with `ChildTimeoutSeconds` and
  `ChildHeartbeatSeconds`, emits `child_start`, `child_heartbeat`, and
  `child_complete` lines for each child packet, then emits
  `GRID_OPEN_BLOCKER_PRIORITY_BOARD`,
  `grid_open_blocker_priority_board_status`,
  `grid_open_readiness_score_pct`,
  `grid_authorization_readiness_phase`,
  `grid_pre_env_authorization_request_ready`,
  `grid_post_env_authorization_request_ready`,
  `grid_split_runtime_current_for_grid_open`,
  `grid_split_tooling_only_currentness_follow_up`,
  `grid_trend_gate`, `grid_trend_gate_clearance_accepted`,
  `grid_trend_override_required`,
  `grid_open_blocker_priority_ranked_blockers`, `grid_open_allowed=false`,
  and `create_grid_allowed=false`. The board ranks split/deploy, event-risk,
  trend-regime, env, replay-score, capital-cap, scheduler/recovery/Earn, and
  operator-authorization-chain blockers into the next safest read-only action.
  It uses pre-env authorization readiness before the env diff is applied and
  post-env authorization readiness after the env diff is live, so a ready
  pre-env operator request is not hidden by a still-blocked post-env lane.
  `SPLIT_ACCEPTANCE_NOT_PASSING` remains a hard blocker only when origin-delta
  evidence shows runtime drift or unknown runtime currentness. If
  `origin_runtime_delta_files=0` and deployment metadata is current, the board
  treats split acceptance as a server tooling-sync follow-up and ranks the next
  runtime blocker instead. `EVENT_RISK_NOT_R0` and
  `OPERATOR_TREND_REGIME_OVERRIDE_REQUIRED_OR_TREND_GATE_CLEARANCE` are ranked
  before `GRID_ENV_DIFF_NOT_APPLIED`, so event/trend risk cannot be hidden by
  a pending env diff. If a child packet times out or cannot produce valid JSON,
  the board returns `REFRESH_GRID_OPEN_BLOCKER_PRIORITY_BOARD_EVIDENCE` and
  ranks `GRID_PRIORITY_BOARD_EVIDENCE_INCOMPLETE` first, so UNKNOWN market/env
  fields from incomplete evidence are not misclassified as live blockers. It is not
  authorization to change env, deploy, restart, call `createGrid`, enable
  grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate
  DB/grid/fund/Earn/exchange state.
- A fresh full micro-grid blocker board run after commit `e8e14df` reported
  `grid_open_blocker_priority_board_status=READY_FOR_GRID_OPEN_BLOCKER_PRIORITY_REVIEW_NOT_MUTATION`,
  `grid_open_blocker_priority_board_decision=WAIT_TREND_CLEARANCE_OR_PREPARE_SEPARATE_TREND_OVERRIDE`,
  `grid_open_readiness_score_pct=91.67`, and passed gates `11/12`. Evidence
  was complete: `source_pre_env_authorization_request exitCode=0`,
  `source_bundle exitCode=0`, `source_origin_delta exitCode=0`,
  `origin_delta_status=CURRENT_ORIGIN_MAIN`,
  `deployment_metadata_status=DOCS_TOOLING_ONLY_DRIFT`,
  `origin_runtime_delta_files=0`,
  `grid_split_runtime_current_for_grid_open=true`, and
  `grid_open_blocker_priority_missing_evidence=[]`. Post-env readiness is
  active (`grid_authorization_readiness_phase=POST_ENV`,
  `grid_post_env_authorization_request_ready=true`), and the reviewed
  candidate remains the 2-level micro-grid:
  `gridCount=2`, `perLevelUsdt=5`, `candidateCapitalUsdt=10`,
  `stopOutPct=5`, `candidateHalfWidthPct=10`, and `replayScore=80`.
  CreateGrid is still blocked: top blocker
  `OPERATOR_TREND_REGIME_OVERRIDE_REQUIRED_OR_TREND_GATE_CLEARANCE`
  (`trendGate=BLOCKED_WAIT_SIDEWAYS_OR_OPERATOR_TREND_OVERRIDE`), followed by
  `CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP`
  (`candidateCapitalUsdt=10; effectiveReviewCapitalCapUsdt=5`). The packet
  kept `create_grid_allowed=false`, `grid_open_allowed=false`,
  `grid_mutation_allowed=false`, `order_allowed=false`, and
  `telegram_send_allowed=false`.
- A fresh micro-grid board run after commit `283b94e` reported
  `grid_open_blocker_priority_board_decision=PREPARE_SEPARATE_GRID_ENV_DIFF_AUTHORIZATION`,
  `origin_delta_status=DOCS_TOOLING_ONLY_DRIFT`,
  `deployment_metadata_status=CURRENT`,
  `origin_runtime_delta_files=0`,
  `grid_split_runtime_current_for_grid_open=true`, and
  `grid_split_tooling_only_currentness_follow_up=true`. It also reported
  `grid_authorization_readiness_phase=PRE_ENV`,
  `grid_pre_env_authorization_request_ready=true`,
  `grid_post_env_authorization_request_ready=false`,
  `grid_trend_gate=CLEAR_TREND_REGIME`,
  `grid_trend_gate_clearance_accepted=true`,
  `grid_trend_override_required=false`,
  `grid_open_readiness_score_pct=66.67`, and passed gates `8/12`. The remaining
  ranked blockers are `GRID_ENV_DIFF_NOT_APPLIED`
  (`TRADING_OKX_ENABLED=false; TRADING_GRID_ENABLED=true`) and
  `CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP`
  (`candidateCapitalUsdt=10.0; effectiveReviewCapitalCapUsdt=5.0`). Split
  strict acceptance remains false due to server worktree tooling drift, but
  zero runtime delta keeps `SPLIT_ACCEPTANCE_NOT_PASSING` out of current grid
  execution blockers; the former operator authorization chain blocker is still
  correctly cleared for the pre-env request phase.
- A follow-up read-only micro-grid review aligned the packet validators with
  runtime `createGrid` support for `gridCount(2-50)` while keeping every
  mutation flag false. The `gridCount=2`, `perLevelUsdt=5`, `stopOutPct=5`,
  `candidateHalfWidthPct=10` candidate replayed with `replayScore=80.0`,
  `stopBreakRows=0`, and `candidateCapitalUsdt=10.0`. Because the current
  trend-override risk remains `MEDIUM`, the effective review cap is
  `5.0`, so capital is still blocked by `CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP`;
  however the required capital-cap override amount drops from `10` USDT on the
  4-level candidate to `5` USDT on the 2-level micro-grid candidate.
- The grid post-env bundle and blocker board preserve candidate grid inputs
  from the freshest available read-only packet, falling back from operator
  authorization request/bundle to create preflight and then the plan packet.
  This keeps `replayScore`, `candidateCapitalUsdt`, and reviewed
  `refreshedCreateGridInputsMustMatch` visible even while env or operator
  authorization gates remain blocked.
- Grid open readiness now has a bounded read-only watch wrapper:
  `scripts/watch_grid_open_readiness_ssh.ps1`. It invokes only the blocker
  priority board, adds a bounded watch loop around the board's own child
  heartbeat/timeout handling, and emits
  `grid_open_readiness_watch_status`, `grid_open_readiness_watch_score_pct`,
  `grid_open_readiness_watch_score_delta_pct`,
  `grid_open_readiness_watch_top_blocker`, and
  `grid_open_readiness_watch_next_action`. Pending states such as
  `PENDING_GRID_DEPLOY_OR_SPLIT_ACCEPTANCE`, `PENDING_GRID_ENV_DIFF`,
  `PENDING_GRID_EVENT_RISK_R0`, and `PENDING_GRID_OPEN_BLOCKERS` keep grid
  opening blocked; `GRID_OPEN_READINESS_READY_FOR_SEPARATE_CREATEGRID_AUTHORIZATION_NOT_MUTATION`
  only means a separate createGrid authorization review may be prepared. The
  watcher does not deploy, restart, change production env, call `createGrid`,
  enable grid/scheduler/recovery, place orders, modify OCO, send Telegram, or
  mutate DB/grid/fund/Earn/exchange state.
- Grid split-acceptance deploy currentness now has a read-only operator
  handoff packet: `scripts/prepare_grid_split_acceptance_deploy_handoff_ssh.ps1`.
  It consumes the bounded grid readiness watch plus the metadata-only
  origin-delta classifier, or replay logs passed with `-GridReadinessWatchLog`
  and `-OriginDeltaLog`, then emits
  `GRID_SPLIT_ACCEPTANCE_DEPLOY_HANDOFF_PACKET`,
  `grid_split_acceptance_deploy_handoff_status`, grid score/top blocker,
  deployment metadata, runtime delta evidence, `reviewedGridCandidateParameters`,
  and the required post-deploy read-only verification commands. The handoff also emits
  `grid_expected_post_deploy_next_blockers`,
  `grid_split_runtime_current_for_grid_open`, and
  `grid_split_tooling_only_currentness_follow_up`, so split/currentness does not
  hide the likely next env/event/capital/operator blocker lanes. If origin
  runtime delta is zero and deployment metadata is current, a server worktree
  mismatch is treated as a tooling-sync follow-up for grid review rather than a
  runtime deploy blocker. The handoff now emits child
  `child_start`, `child_heartbeat`, and `child_complete` markers with timeout
  handling while refreshing nested read-only evidence. A status of
  `READY_FOR_SEPARATE_GRID_SPLIT_ACCEPTANCE_DEPLOY_AUTHORIZATION_NOT_MUTATION`
  means the deploy-currentness blocker is evidence-ready for a separate
  operator decision only; it is not authorization to deploy, change production
  env, call `createGrid`, enable grid/scheduler/recovery, place orders, modify
  OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange state.
- A 2026-06-28 follow-up tightened the grid split/currentness handoff and
  post-env verification plan replay commands. Required post-deploy commands now
  carry the reviewed `Symbol`, `LookbackHours`, `CandidateLookbackHours`,
  `GridCount`, `PerLevelUsdt`, `StopOutPct`, and `CandidateHalfWidthPct`
  values into the blocker board, readiness watch, and post-env bundle. Required
  post-env commands also preserve those values and pass
  `AcceptAlreadyAppliedEnvDiff` only to post-env-capable child packets. The
  latest micro-grid handoff after `2d02712` remained read-only with readiness
  `66.67`, passed gates `8/12`, server worktree `3937f5d` behind `origin/main`
  `2d02712`, and ranked blockers `SPLIT_ACCEPTANCE_NOT_PASSING`,
  `GRID_ENV_DIFF_NOT_APPLIED`, and `CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP`.
- The same 2026-06-28 refresh produced ready read-only micro-grid authorization
  packets. `prepare_grid_open_operator_authorization_request_ssh.ps1` returned
  `READY_FOR_GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_NOT_MUTATION` with
  `requestBlockers=[]`, `gridCount=2`, `perLevelUsdt=5`,
  `candidateCapitalUsdt=10`, `replayScore=80`, and
  `coveredCreateReviewBlockers=["CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP"]`.
  `prepare_grid_post_env_verification_plan_ssh.ps1` returned
  `READY_FOR_GRID_POST_ENV_VERIFICATION_PLAN_NOT_MUTATION` with
  `planBlockers=[]` and replayable post-env commands carrying the same
  micro-grid parameters plus `AcceptAlreadyAppliedEnvDiff` for post-env-capable
  child packets. These packets are still evidence only: trend override,
  capital-cap override, production env diff, deploy/restart, post-env
  verification, and createGrid each remain separate authorization steps.
- Grid opening now has a read-only complete operator packet wrapper:
  `scripts/prepare_grid_open_complete_operator_packet_ssh.ps1`. It aggregates
  the split/currentness deploy handoff, pre-env operator authorization request,
  and post-env verification plan into `GRID_OPEN_COMPLETE_OPERATOR_PACKET`, or
  consumes those saved logs for deterministic replay. It emits the currentness
  authorization line, operator authorization lines, post-deploy commands,
  post-env commands, reviewed createGrid inputs, remaining execution blockers,
  and all mutation flags as false. A ready complete packet is still evidence
  only and does not approve trend/capital/env/deploy/createGrid or open a grid.
- Replaying the complete packet from
  `target/grid-open/grid-split-acceptance-deploy-handoff-microgrid-after-1054bc8.log`,
  `target/grid-open/grid-open-operator-authorization-request-microgrid-after-15bc041.log`,
  and
  `target/grid-open/grid-post-env-verification-plan-microgrid-after-15bc041.log`
  produced `READY_FOR_GRID_OPEN_COMPLETE_OPERATOR_PACKET_NOT_MUTATION` with
  `grid_open_complete_operator_packet_ready=true`, `missingEvidence=[]`,
  currentness `3937f5d` versus `1054bc8`, readiness `66.67`, passed gates
  `8/12`, and reviewed micro-grid inputs `gridCount=2`, `perLevelUsdt=5`,
  `candidateCapitalUsdt=10`, and `replayScore=80`. Remaining execution blockers
  still require separate split/currentness deploy, trend override or clearance,
  capital-cap override, OKX/grid env authorization, deploy/restart,
  post-env read-only verification, and createGrid authorization.
- A later complete-packet refinement distinguishes split runtime currentness
  from server tooling currentness. When the split handoff proves
  `runtimeCurrentForGridOpen=true` from `origin_runtime_delta_files=0`, the
  complete packet filters `SPLIT_ACCEPTANCE_NOT_PASSING` out of runtime
  execution blockers and records that no split runtime deploy/restart is needed
  for grid review. Its decision becomes
  `AWAIT_SEPARATE_ENV_CAPITAL_POST_ENV_AND_CREATEGRID_AUTHORIZATIONS` instead
  of asking for split currentness deploy. Server worktree/tooling sync remains
  a separate follow-up; env diff, capital-cap override, post-env read-only
  verification, and createGrid authorization remain separate blockers.
- The complete packet now also separates filtered execution blockers from raw
  review evidence. `CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP` remains in
  `rawExecutionBlockers` and `coveredExecutionReviewBlockers` when a ready
  capital-cap override request covers it, while `remainingExecutionBlockers`
  keeps the actionable `OPERATOR_CAPITAL_CAP_OVERRIDE_REQUIRED` step instead of
  duplicating the raw review blocker.
- Runtime log smoke now classifies the narrow `OkxTradingService` startup echo
  `[OKX] Auto-trade enabled : true` as OKX auto-trade configuration evidence
  instead of a high-risk operation line. This keeps post-env split acceptance
  from failing on a config echo after an explicitly authorized OKX/grid env
  diff, while actual order placement, OKX submit/fill/execute, `createGrid`,
  OCO, Earn, and fund operation-like lines remain strict blockers unless a
  diagnostic-only high-risk allow flag is used outside acceptance.
- Grid post-env verification now uses explicit `AcceptAlreadyAppliedEnvDiff`
  propagation through the plan/request/bundle/capital/create/env-diff packet
  chain. This separates pre-apply env-diff review from post-env evidence:
  `TRADING_OKX_ENABLED=true` and `TRADING_GRID_ENABLED=true` remain blockers
  in the standalone pre-apply env-diff packet, but are accepted in the post-env
  bundle only when scheduler/recovery/Earn remain disabled and split acceptance
  has passed.
- Grid #10 post-open observation now has a read-only smoke:
  `scripts/smoke_grid_post_open_ssh.ps1`. It uses server-local OPS MCP read
  tools plus the local `scripts/check_server_runtime_log.sh` streamed to a
  remote temporary file to emit
  `grid_post_open_smoke_packet`, verify Grid #10 remains ACTIVE, confirm no
  failed/partial levels, confirm price is in range, and confirm
  `TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false`,
  `GRID_RECOVERY_ENABLED=false`, and `OKX_EARN_TOPUP_ENABLED=false`. It is
  first-day observation evidence only and does not authorize auto-rebalance,
  recovery, close/pause/resume, order/OCO, Earn/fund, Telegram, env, deploy,
  scheduler, or exchange mutations.
- 2026-06-28 read-only grid-open refresh repaired the tooling used to keep the
  operator packet replayable before any opening decision. The readiness script
  now keeps its embedded Python source ASCII-safe while still matching localized
  no-grid markers, the post-open smoke pipes the remote script through
  `bash -s`, streams the current local runtime-log classifier instead of using
  stale server worktree tooling, and fails closed on SSH/runtime-log failures.
  The runtime-log
  smoke classifies bounded `PythNetworkService` feed/network WARN lines under
  `MAX_PYTH_NETWORK_WARN` instead of treating a single timeout as an unknown
  warning. The runtime-log smoke also classifies bounded `OkxWsKlineService`
  public WS transient WARN lines such as `: null` under
  `MAX_OKX_WS_TRANSIENT_WARN`; exceeding the threshold still fails closed as
  collector/network instability evidence. Runtime-log smoke now also
  classifies bounded `McpApiKeyFilter` auth-denied WARN lines from
  unauthenticated MCP probes under `MAX_MCP_AUTH_DENIED_WARN`; exceeding the
  threshold still fails closed as MCP auth abuse, route drift, or verifier auth
  evidence. The operator authorization request
  packet now flattens nested bundle, capital, env-diff, create-preflight, and
  trend blockers into `grid_open_operator_authorization_request_blockers`.
- The refreshed read-only production evidence still does not authorize opening
  another grid. Grid #10 was ACTIVE, in range, with four pending levels, zero
  holding exposure, scheduler registered, and scheduler/recovery/Earn flags
  disabled. A parameter sweep found a best quality BTCUSDT candidate
  `gridCount=4`, `perLevelUsdt=5`, `candidateHalfWidthPct=10`,
  `stopOutPct=5`, `replayScore=80.0`, `stopBreakRows=0`, and
  `candidateCapitalUsdt=20.0`, so the replay-quality blocker can clear with
  that candidate. A follow-up tooling refresh made partially already-applied
  target env flags non-blocking evidence in pre-env mode and aligned trend
  override review to the operator-envelope risk grade while preserving the
  direct threshold grade as diagnostics. The best-candidate pre-env operator
  authorization request now reaches
  `READY_FOR_GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_NOT_MUTATION` with
  `requestBlockers=[]` and
  `coveredCreateReviewBlockers=["CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP"]`. It is
  still only a request packet: the remaining execution steps are separate
  trend/capital/env/deploy authorization, split acceptance, post-env read-only
  verification, and separate createGrid authorization. No deploy, env change,
  scheduler change, `createGrid`, order, OCO, grid, fund, Earn, Telegram, DB,
  or exchange mutation was performed.
- The env-diff preflight now emits `postEnvDiffBlockers` when
  `AcceptAlreadyAppliedEnvDiff` is used before the complete env diff is
  actually live. The refreshed read-only accept-applied evidence showed
  `TRADING_GRID_ENABLED=true` was already present, while
  `TRADING_OKX_ENABLED=false` produced
  `TRADING_OKX_ENABLED_NOT_TRUE_FOR_POST_ENV_REVIEW`; the operator
  authorization request surfaced that value through `envReviewBlockers`. This
  makes the post-env/currentness lane replayable instead of reporting a blocked
  env review with an empty blocker list. The post-env verification plan now
  also flattens source request blockers into `authorizationRequestBlockers` and
  top-level `planBlockers`, so the remaining env/trend/capital/createGrid lanes
  are visible without manually drilling into nested packet JSON.
- 2026-06-29 read-only grid-open refresh after commit `7c1db16` confirmed the
  micro-grid evidence packet is complete but still not executable. Origin delta
  remained `DOCS_TOOLING_ONLY_DRIFT` with server worktree `3937f5d`,
  origin/main `7c1db16`, deployment metadata current, and
  `origin_runtime_delta_files=0`. The blocker priority board returned
  `READY_FOR_GRID_OPEN_BLOCKER_PRIORITY_REVIEW_NOT_MUTATION`, readiness
  `66.67`, passed gates `8/12`, `grid_split_runtime_current_for_grid_open=true`,
  `grid_trend_gate=CLEAR_TREND_REGIME`, and
  `grid_pre_env_authorization_request_ready=true`; current blockers were
  `GRID_ENV_DIFF_NOT_APPLIED` (`TRADING_OKX_ENABLED=false`;
  `TRADING_GRID_ENABLED=true`) and `CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP`
  (`candidateCapitalUsdt=10.0`; `effectiveReviewCapitalCapUsdt=5.0`). The
  independent env preflight was ready for separate env review, the createGrid
  preflight stayed blocked by capital cap, and the capital-cap override review
  was ready for a separate operator decision. The post-env bundle remained
  blocked until a separately authorized env diff/deploy makes
  `TRADING_OKX_ENABLED=true` and split acceptance passes. A follow-up Grid #10
  post-open smoke passed after streaming the local runtime-log checker: Grid
  #10 was ACTIVE, IN_RANGE, had four pending levels, zero holding exposure,
  scheduler registered, scheduler/recovery/Earn flags disabled, runtime
  `ERROR` count 0, `okx_ws_transient=9`, `pyth_network_transient=1`, and
  `unknown=0`. No deploy, env change, `createGrid`, scheduler/recovery/Earn
  enablement, order, OCO, grid, fund, Telegram, DB, or exchange mutation was
  performed.
- A follow-up read-only minimum-capital sweep after commit `c59e26a` proved the
  remaining capital blocker cannot be cleared by lowering candidate parameters
  further. Runtime and review tooling both enforce `gridCount >= 2` and
  `perLevelUsdt >= 5`, so the smallest reviewable grid candidate is 10 USDT.
  The one-combination sweep (`gridCount=2`, `perLevelUsdt=5`, `stopOutPct=5`,
  `candidateHalfWidthPct=10`) produced `qualityCandidateCount=1`,
  `reviewCandidateCount=0`, `replayScore=80.0`, `stopBreakRows=0`,
  `candidateCapitalUsdt=10.0`, `effectiveReviewCapitalCapUsdt=5.0`, and
  `capitalWithinCap=false`, with
  `grid_candidate_parameter_sweep_remaining_blockers=["NO_PARAMETER_CAPITAL_WITHIN_EFFECTIVE_REVIEW_CAP"]`.
  The independent capital override review returned
  `READY_FOR_GRID_CAPITAL_OVERRIDE_OPERATOR_REVIEW_NOT_MUTATION`, requesting a
  separate review-cap decision from 5 USDT to 10 USDT. The env preflight
  remained ready for separate review with only
  `grid_env_diff_preflight_pending_env_diff=["TRADING_OKX_ENABLED=true"]`.
  Grid opening still requires separate capital-cap override, production env
  diff, deploy/restart plus post-env read-only verification, and final
  createGrid authorization; no mutation was performed. Follow-up packet tooling
  now surfaces `existingActiveGridActivationReview` because Grid #10 is already
  ACTIVE while `TRADING_GRID_ENABLED=true`: applying `TRADING_OKX_ENABLED=true`
  can activate the existing active grid order path for market buy/sell
  price-cross events. That requires explicit existing-grid activation wording
  and fresh post-open smoke/runtime-log evidence, not just generic createGrid
  preparation.
- BTC panic-bottom context now has a read-only ScoreBuy companion MCP:
  `previewPanicBottomContext(symbol=BTCUSDT)`. It reads `md_kline`,
  `market_indicator_history` `fear_greed`, a 200WMA reference, OCO health text,
  and 1h/4h trend guards to emit down-wave/retest context,
  `panicBottomScore`, `phase`, and `suggestedAction` with
  `orderAllowed=false` and `gridMutationAllowed=false`. OCO abnormal evidence
  or 1h/4h `TRENDING_BEARISH` downgrades suggestions to
  `SCOUT_PRE_POSITION` or `WATCH`; `previewScoreBuyConviction` displays the
  context for operator review only and does not change live execution.
- Issue #17 panic-bottom missed rebound RCA now has a replayable read-only
  packet path. `scripts/smoke_panic_bottom_context_ssh.ps1` calls server-local
  `/api/mcp` `previewPanicBottomContext` only, and
  `scripts/prepare_panic_bottom_missed_rebound_rca_packet.ps1` combines saved
  profit-candidate, SIGNAL_EVAL no-buy, BUY-like progression, panic-bottom
  context, and optional strategy574 near-threshold shadow logs into
  `PANIC_BOTTOM_MISSED_REBOUND_RCA_PACKET`. The packet emits
  `panic_bottom_missed_rebound_rca_status`, `primaryRootCause`, and
  blocker-layer classification across signal/threshold, BUY-like continuity,
  EntryDedup/DataFreshness/filter, OCO preflight or trend guard, and
  execution/live boundary layers. It keeps strategy threshold relaxation,
  EntryDedup/DataFreshness/live policy changes, pre-position execution, orders,
  OCO/grid/fund/Earn/Telegram/exchange mutations, scheduler enablement, deploy,
  production env changes, DB mutation, and external backfill/import disabled.
- Issue #15 OCO sync errors now have a read-only reconciliation packet:
  `scripts/prepare_oco_sync_reconciliation_packet_ssh.ps1`. It either parses an
  existing source log or calls server-local `/api/mcp` read tools
  (`getOcoHealth`, `listOpenPositions`, `getExecutionRiskSnapshot`) and emits
  `OCO_SYNC_RECONCILIATION_PACKET`, `positionsRequiringWrite`,
  `requiredAuthorization`, `complete_reconciliation_rows`, and
  `oco_sync_reconciliation_status`. The packet is intended to turn OKX
  child-filled/DB-still-open evidence into an operator review artifact only; it
  keeps force-close, position/OCO mutation, orders, scheduler, Telegram,
  deploy/env changes, and DB/grid/fund/Earn/exchange mutation disabled until a
  separate explicit authorization and post-change read-only verification plan
  exist.
- 2026-06-29 read-only grid-open tooling refresh after commits `f09bdce` and
  `ef753ac` clarified the operator evidence without relaxing any runtime gate.
  Candidate replay trend context now uses
  `GRID_CANDIDATE_PLAN_TREND_REVIEW_REQUIRED_NOT_OPEN_APPROVAL` plus
  `candidateTrendRegimeReviewRequired`,
  `candidateTrendRegimeSource=md_kline_replay`, and
  `candidateTrendRegimeNote` to keep md_kline replay risk separate from MCP
  `trendGate` clearance. Capital override wording is now conditional: a fresh
  `trendGate=CLEAR_TREND_REGIME` records that no separate trend-regime override
  is required unless the gate becomes blocked again. The latest complete
  operator packet saved at
  `target/grid-open/grid-open-complete-operator-packet-microgrid-after-ef753ac-current-read-only.log`
  returned `READY_FOR_GRID_OPEN_COMPLETE_OPERATOR_PACKET_NOT_MUTATION`,
  origin/main `ef753ac`, server worktree `3937f5d`,
  `origin_runtime_delta_files=0`, readiness `66.67`, passed gates `8/12`,
  and `missingEvidence=[]`. Remaining execution blockers are still the
  separate env diff, capital-cap override, existing active Grid #10 OKX
  order-path activation authorization, deploy/restart plus post-env read-only
  verification, and separate createGrid authorization. No deploy, production
  env change, scheduler/recovery/Earn enablement, `createGrid`, order, OCO,
  grid, fund, Telegram, DB, or exchange mutation was performed.
- A follow-up read-only refresh after docs commit `ea1844d` kept the runtime
  currentness lane accepted for grid review while preserving execution blockers.
  `target/grid-open/grid-open-blocker-priority-board-microgrid-after-compact-brief-read-only.log`
  returned `READY_FOR_GRID_OPEN_BLOCKER_PRIORITY_REVIEW_NOT_MUTATION`,
  `origin_delta_status=DOCS_TOOLING_ONLY_DRIFT`,
  `deployment_metadata_status=CURRENT`, `origin_runtime_delta_files=0`,
  `grid_split_runtime_current_for_grid_open=true`, readiness `66.67`, passed
  gates `8/12`, top blocker `GRID_ENV_DIFF_NOT_APPLIED`
  (`TRADING_OKX_ENABLED=false; TRADING_GRID_ENABLED=true`), and second blocker
  `CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP` (`candidateCapitalUsdt=10`;
  `effectiveReviewCapitalCapUsdt=5`). Grid #10 post-open smoke saved at
  `target/grid-open/grid-post-open-smoke-grid10-after-ea1844d-continuation-read-only.log`
  passed with Grid #10 ACTIVE, IN_RANGE, four pending levels, zero holding or
  failed/partial levels, scheduler registered, scheduler/recovery/Earn disabled,
  runtime `ERROR` count 0, `unknown=0`, and no high-risk operation-like log
  lines. The blocker priority board now emits a compact authorization brief
  (`grid_open_blocker_priority_next_authorization_required`,
  `grid_open_blocker_priority_existing_active_grid_order_path_activation_risk`,
  `grid_open_blocker_priority_authorization_sequence`, and
  `grid_open_blocker_priority_compact_authorization_brief`) so the next
  operator step is visible without parsing the full nested board packet. No
  deploy, production env change, scheduler/recovery/Earn enablement,
  `createGrid`, order, OCO, grid, fund, Telegram, DB, or exchange mutation was
  performed.
- 2026-06-30 read-only profit-readiness refresh fixed a stale-runtime false
  blocker in the DataFreshness replay evidence path. `smoke_live_origin_delta_local.ps1`
  now accepts the existing bundle `-EnvFile` argument and emits
  `deployed_app_commit` plus `deployment_runtime_delta_files`; the replay
  observation bundle now uses the deployed app commit when deployment metadata
  is `CURRENT` or `DOCS_TOOLING_ONLY_DRIFT` with zero runtime delta. The latest
  production read-only bundle reported
  `deployment_runtime_current_for_replay_id=true`,
  `data_freshness_replay_candidate_id_recommendation=PENDING_NO_NEW_DATAFRESHNESS_ROWS`,
  `replay_candidate_id_rows=0`, latest DataFreshness row
  `2026-06-14T15:38:16`, `data_freshness_rows_14d=0`, and
  `data_freshness_replay_evidence_readiness_status=PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS`.
  The blocker is now correctly classified as missing fresh DataFreshness
  terminal replay rows, not a required runtime deploy.
- The same 2026-06-30 read-only refresh produced a fresh BUY-like candidate
  loss packet at `target/profit-review/buy-like-candidate-loss-review-packet-latest.log`.
  It returned `READY_FOR_BUY_LIKE_CANDIDATE_LOSS_OPERATOR_REVIEW_NOT_LIVE`,
  dominant blocker `ENTRY_SKIP:EntryDedup`, 14d rows `3` (`NO_TERMINAL_FOLLOWUP=2`,
  `ENTRY_SKIP:EntryDedup=1`), and 30d rows `703`
  (`ENTRY_SKIP:EntryDedup=418`, `NO_TERMINAL_FOLLOWUP=110`,
  `ENTRY_SKIP:DuplicateBar=98`, `ENTRY_SKIP:ShadowExecutionIntent=35`,
  filter-block rows `37`, DataFreshnessGuard rows `5`). No-terminal continuity
  was review-ready with 110 rows, mostly terminal-after-primary-window evidence.
  The fresh progression cross-tab ranked the top EntryDedup sources as
  strategy574/1h `161`, strategy566/1h `50`, strategy579/1h `40`, and
  strategy485/1d `18`.
  The packet keeps live policy change, EntryDedup/DataFreshness relaxation,
  scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutation,
  deploy, and production env changes disabled.
- `target/profit-review/buy-like-continuity-matcher-review-packet-latest.log`
  then showed the apparent no-terminal gap is mostly a matcher-window artifact:
  `buy_like_continuity_matcher_review_status=READY_FOR_BUY_LIKE_CONTINUITY_MATCHER_REVIEW_NOT_LIVE`,
  `no_terminal_followup_rows=110`, `matcher_artifact_explained_rows=108`
  (`98.18%`), `residual_potential_true_gap_rows=2` (`1.82%`), with
  `matcher_review_recommendation=EXTEND_PRIMARY_WINDOW_THEN_RECHECK_INTERVAL_LINKING`.
  This keeps the highest-ROI profit review pointed at EntryDedup/exposure
  semantics, not broad DataFreshness or no-terminal policy relaxation.
- 2026-06-30 added a local read-only candidate runtime snapshot collector
  review packet:
  `scripts/prepare_entry_dedup_candidate_runtime_snapshot_collector_review_packet.ps1`.
  It packages the EntryDedup runtime proof gap, BUY-like candidate loss,
  continuity matcher, runtime-evidence RCA, and panic-bottom RCA into
  `ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_PACKET` and emits
  `entry_dedup_candidate_runtime_snapshot_collector_review_status`. The packet
  proposes the candidate context keys needed to close the runtime EV/OCO
  snapshot gap, but keeps `runtime_evidence_write_allowed=false`,
  `collector_activation_allowed=false`, `order_allowed=false`, and
  `strategy_threshold_change_allowed=false`. It is review tooling only and does
  not authorize collector activation, runtime evidence writes, deploy, env
  changes, EntryDedup/DataFreshness or threshold relaxation, staged-add/live
  execution, OCO/order/grid/fund/Earn/Telegram/exchange mutation, or DB changes.
- 2026-06-30 follow-up: the EntryDedup candidate runtime snapshot gap now has
  local shadow context support in `ExposureOptimizer` and `LiveSignalEvaluator`.
  EntryDedup/exposure duplicate blocks keep the original block decision but add
  replayable context fields (`entryPrice`, `tpPrice`, `slPrice`,
  `duplicateCandidateHash`, `replayCandidateId`, EV/TQS continuation,
  OCO plan-shape, `orderSent=false`, and mutation flags false) to the decision
  audit context when runtime evidence sidecar writes are otherwise enabled. The
  review packet now emits
  `entry_dedup_candidate_runtime_snapshot_collector_local_implementation_status=LOCAL_IMPLEMENTED_NOT_DEPLOYED_NOT_ACTIVE`.
  This is evidence-only instrumentation; it does not deploy, activate a
  collector, relax EntryDedup/DataFreshness/live policy, enable staged-add/live
  execution, place orders, modify OCO, send Telegram, or mutate
  DB/grid/fund/Earn/exchange state.
- 2026-07-01 deployed `origin/main` `38b6480` to production without production
  env changes. The deploy advanced the active blue-green port to `8085`, strict
  post-drain server verification passed, dedicated public `/api/mcp` passed,
  shared-host `/api/trading/mcp` stayed blocked with 404, split acceptance
  passed with shared-mode schema compare, runtime log smoke had zero ERRORs and
  only known WARN categories, and MCP split ownership remained valid
  (`trading_tools=308`). Post-deploy origin/currentness is clean:
  `server_worktree_commit=38b6480`, `origin_main_commit=38b6480`,
  `deployed_app_commit=38b6480`, `origin_delta_status=CURRENT_ORIGIN_MAIN`, and
  `origin_runtime_delta_files=0`.
- The post-deploy profit evidence remains blocked, not live-ready:
  `post_deploy_profit_validation_status=BLOCKED_COLLECT_READ_ONLY_EVIDENCE`,
  `profit_loss_review_gate_status=READY_FOR_LOSS_SOURCE_REVIEW_NOT_LIVE`,
  `profit_experiment_gate_status=BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE`,
  DataFreshness `replay_candidate_id_rows=0`,
  `complete_replayable_candidate_rows=0`, and
  `data_freshness_replay_evidence_readiness_status=PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS`.
  Runtime evidence RCA is `CANONICAL_ROWS_NO_SHADOW_INTENT` with
  `shadowIntentCount=0`, `orderSentEvidence=0`, and
  `missing_runtime_evidence_fields=[]`. The next-execution blocker packet keeps
  the active ROI lane at trailing dry-run observation:
  `profit_next_execution_blocker_status=TRAILING_DRY_RUN_ACTIVE_READ_ONLY_OBSERVATION`
  and `profit_next_execution_unique_blocker=NO_OPEN_OCO_POSITIONS`.
  All live policy, scheduler, order, OCO/grid/fund/Earn/Telegram/exchange, DB,
  and production env mutation flags remain false in these packets.
- TradingView-primary signal-source policy is now the local default:
  `TRADING_SIGNAL_SOURCE_PRIMARY=TRADINGVIEW` and
  `TRADING_LEGACY_LIVE_EVALUATOR_ENABLED=false`. K-line close events keep market
  data collection intact but do not invoke the legacy `LiveSignalEvaluator`
  unless a deliberate rollback sets primary `LEGACY` and explicitly enables the
  legacy live evaluator.
- `verifyStrategyExecution` is now signal-source-policy aware. When
  `TRADINGVIEW` or `LOCAL_TRADINGVIEW` primary disables the legacy
  `LiveSignalEvaluator`, backtest BUY rows from non-active legacy strategies are
  reported as `POLICY_SUPPRESSED_NOT_MISSED_EVALUATION` instead of a missed
  evaluation bug. The configured `LOCAL_TRADINGVIEW` strategy still remains
  expected to emit live/audit evidence when its evaluator is active.
- 2026-07-08 BTC_BASE follow-up: `LOCAL_TRADINGVIEW` now has a
  `BTC_BASE_LIVE_MICRO` execution mode in addition to `BTC_BASE_DRY_RUN`.
  It uses the same TradingView parity order intents, places the configured
  small BTC spot market buy, writes live-signal/audit/runtime-evidence rows,
  deliberately skips OCO, and enforces
  `TRADINGVIEW_LOCAL_BTC_BASE_MAX_EXPOSURE_USDT` plus signal-age, scope,
  per-bar, daily, duplicate-bar, notional, OKX credential, and data gates. The
  OCO poller, OCO-missing detector, and open-position/OCO health output now
  treat `LOCAL_TRADINGVIEW_BTC_BASE:*` open rows as intentional BTC_BASE no-OCO
  positions rather than unprotected OCO failures. The existing `LIVE_MICRO`
  mode remains the OCO-attached live path and rollback fallback.
- 2026-07-10 TradingView parity/profit hardening is implemented locally:
  open-position diagnostics now expose strategy id, interval, and audited
  signal source; strategy485 fails closed when strategy ownership is missing;
  autonomous readiness separates target from other-strategy order evidence.
  BTC_BASE backtests now support `SHADOW_ALL_INTENTS`,
  `LIVE_ONE_ORDER_PER_BAR`, and `SHADOW_AGGREGATE_PER_BAR`; catch-up audits all
  bounded bars but only sends the newest eligible first intent to execution.
  Guarded Binance range backfill supports Binance Vision UTC daily data, while
  golden CSV parity remains fail closed without external Pine/Strategy Tester
  truth. The fixed 90/180/270/365-day profit report compares the production
  one-order baseline with one aggregate shadow candidate and keeps candidate
  promotion false pending all long-window gates and new explicit authorization.
  Local acceptance passed `verify_local.ps1` with 236 tests and startup smoke
  with 319 registered tools, 40 required tools, zero missing tools, and health OK.
- 2026-07-12 strategy 508 fixed 4H/24H experiment is implemented locally:
  `STRATEGY_508_4H_24H_V1` adds exact next-1m entry, +6%/-12% OCO and 24H
  time-exit analysis, 99% coverage and ambiguous-minute fail-closed rules,
  fixed multi-window/walk-forward promotion gates, an isolated OFF/SHADOW/
  LIVE_MICRO lane, fee-aware PnL attribution, and a per-position locked spot
  close service. Existing strategy 508 positions are isolated by policy tag;
  strategy 485 remains BTC_BASE_DRY_RUN. The reviewed rollout target is SHADOW
  with live-order false; no live probe is authorized. Production trailing is
  rejected after the latest replay worsened 19/19 positions. Final safety
  review added incremental indicator-cache refresh, paired 24H/72H comparison,
  a one-probe-before-pilot gate, fresh post-cancel balance reads, OCO
  reprotection on failed market close, and fail-closed partial-fee attribution.
  Final local acceptance passed 316 Java tests and startup smoke with 324 MCP
  tools, 45 required tools, zero missing tools, and health OK.

## Cleanup Priority

1. Do not ship extra-table cleanup or drop tooling while the database remains
   shared; extra marketplace/shared tables are expected.
2. Keep the reviewed Flyway baseline under `src/main/resources/db/migration` and add future Trading schema changes as `V2__...` migrations.
3. Keep production on schema validation plus Flyway with the Trading-owned
   `trading_flyway_schema_history` table.
4. Re-run local verify, local smoke, deploy, server verify with schema compare,
   public health, authenticated public Trading MCP checks, server-local MCP registry
   smoke, and cross-service live MCP ownership smoke after deploy-affecting
   changes.

## Do Not Do Yet

- Do not share marketplace JPA entities with trading.
- Do not map marketplace entities or repositories in trading code; the DB is shared, but commerce access must still go through explicit internal APIs or SDK contracts.
- Do not add or predefine identity internal API until shared login is required.
- Do not convert every leftover marketplace service into an internal API.
