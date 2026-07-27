# BTC DRA V1 Performance and Factor Report

Date: 2026-07-26

Contract: `BTC_DAILY_REVERSAL_ACCUMULATION_V1@v1`

## Decision

DRA V1 uses the no-drawdown version. On 2026-07-26 the owner authorized an
independent, single-lot 30 USDT Production LIVE canary:

```text
HISTORICAL_RESEARCH_PASS
OUT_OF_SAMPLE_PASS
BETTER_THAN_509_ON_REALIZED_AND_UNREALIZED
SHADOW_RESEARCH_PASS
LIVE_CANARY_30_USDT_AUTHORIZED
FULL_250_USDT_LIVE_NOT_AUTHORIZED
```

## Data and accounting

Read-only Production history:

- source: OKX;
- symbol: `BTCUSDT`;
- interval: `1h`;
- first bar: `2019-01-01T00:00`;
- last bar: `2026-07-25T23:00`;
- rows: `66,312`;
- non-hourly gaps: `0`;
- research input SHA-256:
  `037afa97a287d04a141903559de02da100c66efb6c91bc47a55f86ba4477c445`.

Common accounting:

- initial reserve and maximum open cost: `250 USDT`;
- lot size: `30 USDT`;
- fee: `0.10%` per side;
- adverse slippage: `0.05%` per side;
- profit target: `+5%` estimated net;
- next-open profit floor: `+1%` net;
- no forced loss or final liquidation.

## Three-year comparison

Period: `2023-07-26` through `2026-07-25`.

| Variant | Realized | Unrealized | Total | Max drawdown | Avg utilization |
| --- | ---: | ---: | ---: | ---: | ---: |
| MEI + DD5 + DRA confirmation | `+86.42663128` | `-0.33176735` | `+86.09486393` | `7.481949%` | `15.369069%` |
| Remove MEI | `+86.42663128` | `-0.33176735` | `+86.09486393` | `7.481949%` | `15.369069%` |
| DRA V1, no drawdown gate | `+107.15130387` | `-6.46487858` | `+100.68642529` | `10.183632%` | `19.203923%` |
| Remove daily EMA confirmation | `+138.09915654` | `-86.95220999` | `+51.14694655` | `25.475003%` | `48.018248%` |
| Remove 24-hour momentum | `+96.33016231` | `-23.77941480` | `+72.55074751` | `17.973695%` | `24.611770%` |
| Remove cooldown | `+153.41607442` | `-44.79346854` | `+108.62260588` | `23.627421%` | `49.872719%` |
| Owner 509 fair-reset baseline | `+36.28661631` | `-67.10733974` | `-30.82072343` | `28.304359%` | `23.186131%` |

DRA V1 produced 67 buys, 64 sells, 3 open lots, 90 USDT ending open
cost, 150 USDT maximum observed open cost, and no cap-blocked entry.

## One-lot capacity overlay

The three-year table above uses the 250 USDT multi-lot reference ledger.
Production instead permits one 30 USDT lot. Applying that capacity limit to
the same DRA signals produces the fairer LIVE-capacity comparison:

| Window | Buys / sells / open | Capacity-blocked entries | Realized | Unrealized | Total |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2023-07-26–2026-07-25 | `25 / 24 / 1` | `43` | `+39.92025564` | `-6.13311123` | `+33.78714441` |
| OOS 2025–2026-07-25 | `9 / 8 / 1` | `22` | `+12.66255279` | `-6.13311123` | `+6.52944156` |

The one-lot overlay still outperformed the fair-reset owner-509 baseline in
this research window, but it gave up most DRA entries while the lot was
occupied. It is capacity evidence, not a forecast of the 30 USDT LIVE result.

## Validation split

| Period | Realized | Unrealized | Total | Max drawdown |
| --- | ---: | ---: | ---: | ---: |
| Design 2019–2022 | `+169.89846767` | `-79.12049441` | `+90.77797326` | `29.530448%` |
| Validation 2023–2024 | `+89.41118307` | `-3.20820121` | `+86.20298186` | `7.121498%` |
| OOS 2025–2026-07-25 | `+44.83826545` | `-6.46487858` | `+38.37338687` | `8.870663%` |
| Full 2019–2026-07-25 | `+314.18565472` | `-6.46487858` | `+307.72077614` | `29.530448%` |

## Factor conclusion

MEI `>=60` passed `99.080324%` of ready full-history hours and
`98.957585%` of hours already at least 5% below the rolling high. Removing MEI
changed no trade or metric in any evaluated period.

The effective DRA factors are:

1. daily EMA20 price and slope confirmation;
2. positive 24-hour momentum;
3. seven-day cooldown;
4. independent profit-only lots;
5. bounded open cost.

The drawdown gate improved ending unrealized PnL but reduced realized and total
three-year PnL. The owner selected the no-drawdown version for the separately
bounded 30 USDT LIVE canary so that forward evidence can determine whether the
higher historical realized return persists.

Future comparisons must keep realized and unrealized PnL separate but rank on
fee-adjusted total PnL under equal capital. They must also report drawdown,
utilization, blocked entries, and holding age; realized-only ranking is invalid
for a strategy with no forced loss exit.
