# BTC Donchian SHADOW Runtime Rollout

Date: 2026-07-13

## Status

`BTC_DONCHIAN_20D_10D_V1` now has a local-only, evidence-only runtime lane. It
has not been committed, deployed, enabled, or run against production. The
configuration default is:

```bash
TRADING_BTC_DONCHIAN_SHADOW_MODE=OFF
```

No live implementation, live mode, order service, OCO service, Telegram
service, scheduler, or external-backfill path exists in this lane.

The separately authorized production data repair is complete while Donchian
remains `OFF`. Production now has all 66,009 required OKX rows, zero timestamp,
lattice, duplicate, scope, or OHLC errors, and canonical price-bar hash
`361ab6910872079db4e58c45897828b3399c5d9cb8346afcd1970536d1ee6a6d`.
The independent predeploy packet reports
`READY_FOR_OFF_DEPLOY_AUTHORIZATION`. See
`docs/btc-donchian-off-deploy-readiness-2026-07-13.md`. The 2026-07-14 operator
authorization covers OFF code deployment only; later SHADOW activation still
requires another separate authorization.

## Frozen Semantics

- instrument: OKX `BTCUSDT`, source `okx`, confirmed `1h` bars;
- entry signal: daily close above the prior 20 complete UTC-day highs;
- exit signal: daily close below the prior 10 complete UTC-day lows;
- initial stop: current complete UTC-day ATR14 multiplied by 2;
- virtual sizing: 1% of virtual equity, capped at 100% exposure, no leverage;
- execution: next 1h open; the stress scenario delays one additional bar;
- stop fill: stop price or gap open, whichever is worse;
- normal costs: 0.10% fee and 0.05% adverse slippage per side;
- stress costs: 0.20% fee and 0.10% adverse slippage per side.

The same `BtcDonchianShadowEngine` owns research replay, runtime stepping, and
golden verification. Runtime logic does not reimplement the signal formula.

## Golden Contract

The immutable research input is
`okx-btc-usdt-1h-20260713T090000Z`, covering 66,009 contiguous UTC bars from
2019-01-01 00:00 through 2026-07-13 08:00. Its declared dataset SHA-256 is
`74bccfdc621884447e224536cedb7471f8c28bbb612f38e81d8b23e02ff8cfd8`.

Golden verification compares normal and stress signal, virtual-order, and
trade ledgers by exact row count, row content, and SHA-256. Missing DB bars,
non-hourly timestamps, invalid OHLC, unexpected ordering, or one ledger hash
mismatch fail closed. The verifier is read-only and never downloads or
backfills data.

Because the production `volume` column has a narrower decimal scale than the
raw OKX export and volume is not an input to this strategy, runtime parity also
uses a separate canonical signal-relevant bar ledger over UTC time, symbol,
interval, source, and exact OHLC. Its frozen SHA-256 is
`361ab6910872079db4e58c45897828b3399c5d9cb8346afcd1970536d1ee6a6d`.
This detects an OHLC change even when all six derived strategy ledgers happen
to remain unchanged; raw-file provenance remains anchored by the declared
dataset SHA-256 above.

## Runtime Evidence

The lane receives only closed `BTCUSDT/1h/okx` events from the existing K-line
closed-event listener. It has no independent scheduler. `OFF` returns before
any write. `SHADOW` also returns without writing when runtime evidence is
disabled.

For each accepted closed bar, it stores:

- canonical bar identity and close time;
- bootstrap/catch-up state and batch size;
- state before/after continuity through `stateAfterSha256`;
- normal and stress signals, virtual orders, and virtual trade closes;
- fee/slippage model-completeness markers;
- explicit `SHADOW_ONLY`, `orderSent=false`, `ocoModified=false`, and
  `telegramSent=false` evidence.

State is restored only from hash-valid `SHADOW_ONLY` evidence with
`orderSent=false`. A restart catches up a maximum of 720 contiguous hours.
Missing history, invalid OHLC, a timing gap, a state mismatch, an unclosed bar,
or an over-limit catch-up produces `BLOCKED_DATA_QUALITY` and does not advance
state. Canonical observed bars are persisted once; blocked rows use a separate
event type so a repaired source can be retried.

## Forward Gate

`getBtcDonchianShadowReadiness` remains read-only and requires all of the
following:

- exact frozen golden parity;
- mode `SHADOW` and runtime evidence enabled;
- at least 30 non-bootstrap forward days;
- at least five independent normal entries and five completed normal trades;
- positive normal net PnL and non-negative stress net PnL;
- current evidence within 120 minutes;
- no malformed, unresolved-blocked, duplicate, non-causal, lattice-gap, fee-gap,
  slippage-gap, state-hash, non-SHADOW, or order-sent evidence;
- no completed forward trade without its corresponding forward entry.

Blocked rows remain immutable audit evidence. A later hash-valid canonical
observation for the same bar marks that blocker resolved for readiness without
deleting either row; a blocker with no matching canonical bar remains a hard
failure.

Passing returns `READY_FOR_SHADOW_EVIDENCE_REVIEW_NOT_LIVE`. It never grants a
promotion and always reports `liveImplementationPresent=false`,
`liveOrderAllowed=false`, and `promotionAuthorizationGranted=false`.

## Verification

Local source acceptance:

```powershell
.\scripts\test_btc_donchian_shadow_smoke.ps1
.\scripts\verify_local.ps1
.\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180
```

Completed local evidence on 2026-07-13: `verify_local.ps1` passed 383 Java
tests plus all PowerShell/split checks; independent startup smoke reported 326
registered MCP tools, 47 required tools, and `missing_required_tools=[]`.

After an authorized deployment that retains `OFF`:

```powershell
.\scripts\smoke_btc_donchian_shadow_ssh.ps1 -ExpectedMode OFF
```

Before requesting that deployment, the strict production packet must return
`READY_FOR_OFF_DEPLOY_AUTHORIZATION`:

```powershell
.\scripts\prepare_btc_donchian_off_deploy_readiness_ssh.ps1 `
  -Phase PREDEPLOY `
  -RequireReady
```

After a later, separate explicit authorization to collect production SHADOW
evidence:

```bash
TRADING_RUNTIME_EVIDENCE_ENABLED=true
TRADING_BTC_DONCHIAN_SHADOW_MODE=SHADOW
```

Then run:

```powershell
.\scripts\smoke_btc_donchian_shadow_ssh.ps1 `
  -ExpectedMode SHADOW `
  -RequireGoldenParity
```

`-RequireForwardReady` is appropriate only after the 30-day/five-trade sample
period. The smoke is read-only and is not authorization to deploy, change an
environment file, restart production, import data, place an order, modify OCO,
or send Telegram.

## Authorization Boundary

1. Local implementation and tests require no production action.
2. Deploying the code with mode still `OFF` requires separate explicit
   authorization.
3. Changing production to `TRADING_BTC_DONCHIAN_SHADOW_MODE=SHADOW` requires a
   later separate explicit authorization and a passing golden-parity smoke.
4. Live trading is outside this policy. No live implementation or live
   configuration value exists, and forward readiness cannot enable one.
