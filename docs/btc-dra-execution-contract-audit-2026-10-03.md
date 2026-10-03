# DRA execution-contract and performance-basis repair

The 30 USDT research parent and deployed one-lot canary have different
execution profiles. Using the parent's result as the canary's result is a
measurement error. Changing the virtual cap to 30 changes entry and cooldown
semantics; it is not a mechanical repair of V1.

## Corrected contract

`DRA_V1_VIRTUAL250_SINGLE30_CURRENT_QUOTE` retains the frozen behavior:

- The 250 USDT reference engine emits `VIRTUAL_ENTRY_QUEUED`. Queued virtual
  signals advance cooldown even when LIVE is occupied. LIVE separately applies
  the one-lot 30 USDT gate.
- Bootstrap reconstructs historical arm/expiry/cooldown without positions or
  orders. Bootstrap and catch-up cannot submit orders.
- LIVE evaluates its own lot against the current provider quote on each fresh
  hourly invocation, requires +5% estimated net return, and handles an exit
  before entry. An invocation handling an exit does not also buy.
- The reference queues exits at a closed bar and fills at the next open when
  at least +1% net remains.

The release shares existing entry-event selection and net-return arithmetic
between LIVE and the offline diagnostic. No threshold, capital limit, state
schema, cooldown, ownership, submission, retry or reconciliation rule changes.
Expected direct PnL/drawdown improvement from this release is zero. It fixes
economic evidence and diagnostic ambiguity, not strategy alpha.

## Fixed historical diagnostic

`com.agora.research.BtcDraLiveContractReplayCli` is a plain Java 21 entrypoint,
not a Spring component. It takes a sealed input TSV and a new output JSON.
Invoke it with compiled classes and dependency classpath, never Maven exec or
the application main class. It has no repository/provider/network dependency.

Input: 52,608 OKX BTCUSDT H1 rows, 2019-01-01 through exclusive 2025-01-01,
SHA-256 `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.
Different input hashes and existing output files are rejected. Only the current
contract is replayed, across 2020–2024 annual resets and 2023–2024. There is no
candidate, parameter selection, canonical write or OOS access. A 2019 reset is
unavailable because required bootstrap history precedes the sealed input.

Next hourly open is an explicit proxy for the unavailable historical ticker.
Cost includes a 0.10% buy fee; both sides include 0.05% adverse slippage. BTC
quantity is floored to eight decimals. Each later lot remains 30 USDT, profits
remain cash, and final liquidation is not forced. Actual provider quantity
steps, minimum sizes, fee receipts, latency, partial fills and outages are not
reconstructed. This is not exact LIVE fill parity or a return forecast.

| 2023–2024, initial 30 USDT | Total PnL | Maximum H1 close drawdown | Daily Sharpe, cash 0 |
| --- | ---: | ---: | ---: |
| Sealed single-slot research parent | 39.93354639 | 17.699055% | 1.744063 |
| Current-contract ticker proxy | 38.90252534 | 18.659930% | 1.761126 |
| BTC buy-and-hold, same proxy costs/precision | 139.17147560 | 32.284163% | 2.013627 |
| Cash, zero yield | 0 | 0% | undefined |

The proxy completes 22 buys/exits, has no terminal lot, and is occupied
78.117875% of hours. It has 28 queued signals blocked by the occupied lot,
one deferred during exit handling, and 151 daily confirmations inside virtual
cooldown. These are not counts of missed profitable trades. Completed holding
median/P90 are 188.0/769.3 hours. Annual resets cannot be summed.

The -1.03102105 USDT difference bundles signal, bootstrap, exit and rounding
differences; it cannot be attributed to cooldown alone. Annual changes are
mixed: proxy PnL is lower in 2020–2023 and higher in 2024. Changing the live
signal contract to the research parent is not established as a stable
risk-adjusted improvement.

| Independent year | Proxy PnL | Maximum drawdown |
| --- | ---: | ---: |
| 2020 | 23.99539821 | 50.811998% |
| 2021 | 6.69860864 | 36.559773% |
| 2022 | -13.12316732 | 55.156379% |
| 2023 | 20.12282193 | 18.659930% |
| 2024 | 18.59893736 | 22.002013% |

Fixed-size profit taking and long-lived losing inventory remain economic
limitations. This correction does not justify adding capital, a new stop,
target tuning, or reopening rejected research branches.

## Diagnostic fixes

New DRA observations include a typed entry stage, queued flag, virtual capacity
block, arm state and UTC cooldown boundary. MCP exposes it only with matching
schema, profile and bar. Legacy rows stay missing proof and are not backfilled.
MCP joins actual execution disposition only for the same observed bar.

Known buy preflight blocks now retain `BLOCKED:<reason>`. Provider lookup,
submission and reconciliation uncertainty retain explicit unconfirmed labels.
The forward report no longer counts a known capacity block as ambiguous
execution. Policy/exposure metadata labels 250 USDT drawdown as virtual
reference, separate from the 30 USDT live inventory.

## Verification

- 106 offline tests pass, including fee-adjusted trigger boundaries, cooldown,
  bootstrap/catch-up isolation, queued-event admission, evidence/bar binding,
  and occupied-lot rejection before provider or reservation access.
- Java 21 package passes. The unchanged reference engine matches all frozen
  Design/Validation checkpoints through the existing plain-Java parity CLI.
- All six windows reconcile trade PnL to ending equity and enforce one lot and
  nonnegative cash. Independent processes reproduce R2 JSON byte for byte.
- R2 replay SHA-256:
  `0d9855902eeaba782310f0479f8dee6cacda18e019bee2149f6b56e385530bec`.
- Frozen analysis contract SHA-256:
  `73a51a5591369e9e2fad7115dfa34e54d3f26910927a51e8937de97b458bb529`.
- First diagnostic attempt had equal numeric/ledger content but nondeterministic
  map ordering. It is retained as rejected; sorted-key R2 is a new artifact.
- Local evidence is in the main research checkout under
  `.research-state/reports/dra-current-contract-20261003T0853/r2/`.

Production acceptance is recorded in `split-acceptance-status.md`. Later natural
bars and provider receipts remain necessary for forward performance evidence.
