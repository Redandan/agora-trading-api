# BTC DRA V7 Trend-Quality Promotion Liquidity-Harvest V8 Result

Date: 2026-08-02

Research identity:
`BTC_DRA_V7_TREND_QUALITY_PROMOTION_LIQUIDITY_HARVEST_V8_RESEARCH`

Decision:

```text
DATA_QUALITY_PASS
BASELINE_PARITY_PASS_THROUGH_V7
DESIGN_VALIDATION_COMPLETE
PRE_2025_FAIR_RESET_FOLDS_COMPLETE
NO_CANDIDATE
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
RESEARCH_ONLY
NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE
```

## Outcome first

V8 improved capital-harvest efficiency versus V1 but failed the frozen
realized-PnL protection by `0.84041802 USDT`. Every other performance,
stability, causal, promotion, and accounting gate passed. Thresholds were not
changed after the result, so the decision is `NO_CANDIDATE`.

Validation realized/unrealized/total were
`88.57076505 / -0.64164024 / 87.92912481`. Total beat V1 by `1.72614295`,
drawdown improved from `7.121498%` to `6.283374%`, median/P90 were
`184h / 836h`, and turnover increased by `23.15958198`. Frozen harvest
efficiency was `0.13147773` realized USDT per `1,000 USDT-hours`, `39.521%`
above V1's `0.09423508`, and won in `3/5` annual folds.

The one failed gate matters: realized PnL was below V1's `89.41118307`, so the
candidate cannot replace V1 under the preregistered liquidity-harvest contract.

## Validation comparison

| Exit | Realized | Unrealized | Total | DD | Median | P90 | Turnover | Harvest efficiency |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| V1 | `89.41118307` | `-3.20820121` | `86.20298186` | `7.121498%` | `182.5h` | `1,418.3h` | `1,589.41118307` | `0.09423508` |
| V3C no-1R partial | `90.45986447` | `-0.64164024` | `89.81822423` | `5.905520%` | `184h` | `836h` | `1,614.45986447` | not selected by the frozen V8 gate |
| V7 promote every first 1R | `96.02789691` | `-0.64164024` | `95.38625667` | `6.832349%` | `192h` | `836h` | `1,620.02789691` | `0.13837909` |
| **V8 trend-quality promotion** | **`88.57076505`** | **`-0.64164024`** | **`87.92912481`** | **`6.283374%`** | **`184h`** | **`836h`** | **`1,612.57076505`** | **`0.13147773`** |

V8 had `51` buys, `79` exit slices, one open `6 USDT` remainder, zero blocked
entries, zero deferred exits, average utilization `15.359234%`, and frozen
capital-hours `673,656.003240 USDT-hours`.

## Promotion result and diagnosis

V7 had eight pre-partial first-`1R` promotions. V8 evaluated the same eight
crossings and:

- promoted four that passed seven-day acceleration, daily close above EMA20,
  and rising daily EMA20;
- rejected four and kept them on the immediate no-`1R` V3C harvest path;
- allowed four rejected lots to complete a later partial harvest;
- used no quota, tie-break, entry block, or resize.

All promotion decisions were unique first crossings with complete causal daily
inputs. Every promoted lot passed the four frozen trend-quality conditions,
and no rejected lot promoted later.

The filter selected the wrong economic subset in Validation:

- V8 was `1.88909942` total below V3C, so promoting the four qualified lots
  reduced value versus promoting none;
- V8 was `7.45713186` total below V7, so the four lots rejected by V8 were the
  valuable part of V7's broader promotion set.

This is an observed attribution, not a new tradable rule. Inverting or tuning
the conditions after seeing this result would be data mining and was not run.

## Annual folds

| Year | V8 total | V1 total | V8 median | V1 median | V8 / V1 harvest efficiency | Promoted / rejected | Result |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 2020 | `52.48695114` | `56.19904224` | `92h` | `113h` | `0.13006491 / 0.10324093` | `2 / 1` | efficiency win, total loss |
| 2021 | `16.05712302` | `31.45159316` | `26h` | `103.5h` | `0.10042756 / 0.10731364` | `1 / 1` | efficiency loss, total loss |
| 2022 | `-0.81030310` | `-15.77563722` | `31h` | `126h` | `0.03850488 / 0.04519766` | `0 / 2` | efficiency loss, total win |
| 2023 | `40.77572406` | `44.20017391` | `171h` | `204h` | `0.16229914 / 0.09461107` | `2 / 2` | efficiency win, total loss |
| 2024 | `45.65762839` | `38.78866787` | `187h` | `160h` | `0.11358718 / 0.09031002` | `2 / 2` | efficiency and total win |

Annual totals beat V1 in `2/5`, median holding in `4/5`, and harvest
efficiency in `3/5`, exactly passing the frozen annual requirements.

## Design and audit

Design realized/unrealized/total were
`137.01685913 / -38.12504610 / 98.89181303`, drawdown `16.496399%`,
median/P90 `96h / 1,094h`, and harvest efficiency `0.07339201`. It had
`101` buys, `165` exit slices, six ending lots, zero blocked entries, and
four promotions from eight first-`1R` decisions.

Validation and every annual fold passed route completeness, exact quantity and
cost conservation, strictly net-positive fills, at-most-one partial fill,
promotion uniqueness, trend-quality input completeness, and same-hour ordering.

## Reproducibility and boundary

- preselection rows/hash: `52,608` /
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- specification SHA-256:
  `3f00e56ff19cf4809247cc9fab5bac12f6cacedb9ad8f2da8042875c36f78592`;
- runner SHA-256:
  `f1972d52b8cacf9d6d3801d9c31aa1ffceda5df498634104d33ee52c22fa7db2`;
- two byte-identical `5,784,946`-byte JSON files SHA-256:
  `9a1d9c9a95a8b3eb7b509d9b5cc26e506cc0c1abd311ddbc7cd2bebe8423b284`.

The first structural execution stopped before candidate simulation because the
V2A checkpoint does not expose annual folds. The runner was corrected to add
annual harvest metrics only when a baseline actually contains folds. No V8
metric existed before that correction; the candidate formula and gates did not
change.

OOS was not opened and the one-slot overlay did not run because preselection
froze no candidate. No runtime, configuration, database, DRA V1, position
`263`, owner `509`, Grid/OCO, funds, schedules, Telegram, order, commit, or
deployment changed.

## Reproducible artifacts

- specification:
  `docs/btc-dra-v7-trend-quality-promotion-liquidity-harvest-v8-research.md`;
- runner:
  `research/btc_dra_v7_trend_quality_promotion_liquidity_harvest_v8.py`;
- accepted preselection runs:
  `btc-dra-v7-trend-quality-promotion-liquidity-harvest-v8-preselection-2026-08-02-run1.json`
  and `run2.json` in the task visualization directory.
