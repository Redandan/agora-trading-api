# BTC MEI Directional V1 Performance Report

> Historical only. The MEI runtime candidate was retired on 2026-07-26 after
> factor ablation showed that its threshold changed no trade. The successor is
> `BTC_DAILY_REVERSAL_ACCUMULATION_V1@v1`.

Date: 2026-07-25

Contract: `BTC_MEI_DIRECTIONAL_ACCUMULATION_V1@v1`

## Verdict

The implementation is accepted for commit as a default-`OFF`, SHADOW-only
research candidate.

It is not accepted for LIVE:

- historical realized and total PnL are positive after the frozen cost model;
- 25 losing lots remain open at the end and occupy the full 250 USDT cap;
- 2022, 2023, and 2026 YTD produced no realized exits;
- the strategy materially underperformed fee/slippage-adjusted BTC buy and
  hold;
- the new contract has zero Production forward observations because it has not
  been deployed or enabled.

The correct status is:

```text
LOCAL_IMPLEMENTATION_ACCEPTED
HISTORICAL_SCREEN_POSITIVE_BUT_CAPITAL_LOCKED
FORWARD_SAMPLE_MISSING
LIVE_REJECTED
```

## Evidence and method

The exact local runtime engine was replayed against a read-only Production
query of:

- source: OKX;
- symbol: `BTCUSDT`;
- interval: `1h`;
- first bar: `2019-01-01T00:00`;
- last bar: `2026-07-25T07:00`;
- rows: `66,296`;
- unique rows: `66,296`;
- non-hourly gaps: `0`;
- invalid one-hour close times: `0`;
- source-row SHA-256:
  `97d44154f8a58378a90f20ea61f8948cda2b001888c84272688248ce9de6d181`.

The replay used the frozen candidate contract:

- MEI `>=60`;
- positive 24-hour momentum;
- close above EMA20;
- false-to-true entry edge only;
- 10 USDT per virtual lot;
- 250 USDT maximum open cost;
- 0.10% fee per side;
- 0.05% adverse slippage per side;
- +5% estimated net exit trigger;
- next-open sell deferred unless at least +1% net remains;
- no stop loss, time exit, or end-of-period liquidation.

The virtual equity curve starts at 250 USDT. Realized profit remains as cash
and position size is not scaled up.

Final engine state SHA-256:
`60d29ad16bf948c88faadc825512d93a2f9380adb4e1424e72eda1cef944a3b5`.

## Headline performance

| Metric | Result |
| --- | ---: |
| Initial reserved capital | `250.00000000 USDT` |
| Final virtual equity | `700.74422537 USDT` |
| Realized net PnL | `+563.43540170 USDT` |
| Open unrealized PnL | `-112.69117633 USDT` |
| Total PnL | `+450.74422537 USDT` |
| Realized return / initial reserve | `+225.374161%` |
| Total return / initial reserve | `+180.297690%` |
| CAGR | `+14.600194%` |
| Maximum virtual-equity drawdown | `38.555655%` |
| Maximum open-inventory capital loss | `74.452853%` |
| Total entry fills | `998` |
| Completed exits | `973` |
| Positive completed exits | `973` |
| Open lots | `25` |
| Current open cost | `250.00000000 USDT` |
| Oldest open lot | `2025-07-17T22:00` |
| Entry edges blocked by full capital | `2,930` |
| Total modeled fees | `20.28373923 USDT` |
| Total cumulative buy notional | `9,980.00000000 USDT` |
| Average realized PnL per closed lot | `0.57907030 USDT` |
| Median realized PnL per closed lot | `0.54514238 USDT` |
| Average closed-lot holding time | `1,307.98 hours` |
| Median closed-lot holding time | `149 hours` |

The `100%` realized win rate is mechanical: the strategy refuses to realize a
loss. It is not evidence that every entry is profitable. The 25 open lots and
the unrealized loss are the required counter-evidence.

## Continuous calendar-year decomposition

Positions and realized cash carry continuously across year boundaries. Returns
below are changes in the full virtual equity curve during each calendar year,
not independently reset backtests.

| Period | Realized delta | Equity delta | Period return | Buys | Exits | Blocked entries | Max drawdown | End open lots | End unrealized |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2019 | `+159.91668618` | `+67.17312150` | `+26.869249%` | 282 | 257 | 257 | `25.571552%` | 25 | `-92.74356468` |
| 2020 | `+92.78901952` | `+185.53907804` | `+58.497731%` | 141 | 164 | 393 | `35.621127%` | 2 | `+0.00649384` |
| 2021 | `+153.88499765` | `+92.46279636` | `+18.392789%` | 291 | 268 | 216 | `18.871735%` | 25 | `-61.41570745` |
| 2022 | `0` | `-121.07855884` | `-20.343354%` | 0 | 0 | 504 | `21.885344%` | 25 | `-182.49426629` |
| 2023 | `0` | `+105.01540206` | `+22.150641%` | 0 | 0 | 560 | `5.027160%` | 25 | `-77.47886423` |
| 2024 | `+87.54131242` | `+159.92847907` | `+27.616165%` | 144 | 158 | 386 | `11.338390%` | 11 | `-5.09169758` |
| 2025 | `+69.30338593` | `+12.38944482` | `+1.676423%` | 140 | 126 | 358 | `9.676452%` | 25 | `-62.00563869` |
| 2026 YTD | `0` | `-50.68553764` | `-6.745213%` | 0 | 0 | 256 | `10.923197%` | 25 | `-112.69117633` |

The strategy can stop realizing profit for an entire year or longer while its
capital remains fully occupied. This is the main economic weakness.

## BTC buy-and-hold benchmark

For a like-sized 250 USDT allocation on the same first and last OKX bars, with
the same 0.10% fee and 0.05% adverse slippage on entry and final liquidation:

| Metric | MEI directional | BTC buy and hold |
| --- | ---: | ---: |
| Final net value | `700.74422537` | `4,310.43609799` |
| Net PnL | `+450.74422537` | `+4,060.43609799` |
| Total return | `+180.297690%` | `+1,624.174439%` |
| CAGR | `+14.600194%` | `+45.714847%` |
| Maximum hourly-close drawdown | `38.555655%` | `77.189559%` |

The candidate cut historical drawdown substantially, but its return and CAGR
were much lower than simply holding BTC. It therefore does not yet establish
that the entry logic adds alpha after opportunity cost.

## Acceptance

| Gate | Result |
| --- | --- |
| Java/config package | PASS |
| Environment-template validation | PASS |
| Source-pinned OKX data | PASS |
| Complete one-hour lattice | PASS |
| Fee/slippage-aware accounting | PASS |
| No forced loss/END exit | PASS |
| 250 USDT open-cost cap | PASS |
| No exchange/OCO/Grid/Telegram dependency | PASS |
| No database migration | PASS |
| No test-tree restoration | PASS |
| Historical realized net positive | PASS |
| Historical total PnL positive | PASS |
| Beats BTC buy and hold | FAIL |
| Production forward sample | MISSING |
| LIVE readiness | FAIL |

Production remained unchanged during this acceptance:

- deployed commit: `6dae3fb`;
- deployed catalog: owner 509 LIVE, owner 508/V1 ARCHIVED, Donchian SHADOW;
- the new MEI directional contract is absent from the deployed catalog;
- owner 509 remains armed;
- Donchian remains pending at 11 observation days, 1 entry, and 0 completed
  forward trades.

## Recommendation

Commit the code because the lane is isolated and defaults to `OFF`.

Do not claim proven alpha and do not promote it to LIVE. If deployed later,
deploy it first with `TRADING_BTC_MEI_DIRECTIONAL_SHADOW_MODE=OFF`. Enabling
SHADOW requires separate authorization and should collect forward evidence
specifically for:

- duration and depth of capital lock;
- blocked entry opportunity cost;
- realized net PnL;
- open unrealized loss;
- drawdown versus BTC buy and hold.
