# BTC DRA Independent-Lot Conjunctive Partial De-Risk V3B Result

Date: 2026-08-02

Research identity:
`BTC_DRA_INDEPENDENT_LOT_CONJUNCTIVE_PARTIAL_DE_RISK_V3B_RESEARCH`

Decision:

```text
DATA_QUALITY_PASS
BASELINE_PARITY_PASS_THROUGH_CONDITIONAL_PARTIAL_V3
DESIGN_VALIDATION_COMPLETE
PRE_2025_FAIR_RESET_FOLDS_COMPLETE
NO_CANDIDATE
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
RESEARCH_ONLY
```

## Outcome first

The single `24/6` conjunctive candidate passed every frozen profitability,
unrealized, drawdown, P90, annual-total, and accounting gate. It failed only
aggregate and annual median holding.

Validation realized/total were `108.20391069 / 104.99570948`, safely above V1
and the `101.92585147` 90%-V2A threshold. Drawdown was `6.388173%` and P90
`1,329.1h`. Cost-weighted median remained `317h` and annual median wins were
`0/5`, so the decision is `NO_CANDIDATE` without relaxing the gate.

## Candidate and Validation

Only entries with both positive 7-day momentum acceleration and signal-day
range above prior causal ATR14 kept the full `30 USDT` V2A path. The other
entries armed at `1R`, then sold `24 USDT` after a causal hourly EMA5
deterioration while strictly net-positive; the `6 USDT` remainder used V2A.

Validation had:

- `51` buys, `82` exit slices, one open lot, zero blocked entries and zero
  deferred fills;
- `18` full-V2A and `33` partial-eligible entries;
- `32` partial fills, `18` direct V2A completions, and `32` remainder
  completions;
- realized `108.20391069`, unrealized `-3.20820121`, total `104.99570948`;
- average utilization `20.723803%`, turnover `1,608.20391069`;
- cost-weighted median/P90 `317h / 1,329.1h`; and
- median first realization/final completion `201.5h / 402.5h`.

All cost, quantity, single-partial, trigger, and strict positive-fill audits
passed.

## Annual and Design evidence

| Year | Candidate total | V1 total | Candidate median | V1 median | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| 2020 | `76.85320972` | `56.19904224` | `255h` | `113h` | total win, hold loss |
| 2021 | `33.95322760` | `31.45159316` | `274h` | `103.5h` | total win, hold loss |
| 2022 | `-24.62257080` | `-15.77563722` | `210h` | `126h` | total loss, hold loss |
| 2023 | `49.20979730` | `44.20017391` | `334h` | `204h` | total win, hold loss |
| 2024 | `54.29013982` | `38.78866787` | `212h` | `160h` | total win, hold loss |

Design realized/unrealized/total were
`213.22087501 / -101.42144167 / 111.79943334`, drawdown `29.321733%`,
median/P90 `261h / 1,640h`, and `99 / 161 / 6 / 5`
buys/slices/open/blocked. Design retained the known stale-inventory regime
risk and did not justify gate relaxation.

## Reproducibility and boundary

- input SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- specification SHA-256:
  `882db75ad87bad2ce8111c3ba43c7ad27f3a5244f82853d4e623967c57ea007b`;
- runner SHA-256:
  `9df19359fed929e0c305c46eea94c163753d2d72956719907831c1a96a324d57`;
- two byte-identical `3,321,582`-byte JSONs SHA-256:
  `370156c2e58ed5e603a1aeb87244309f8fb39164ffb3103790263309e8eaa209`;
- OOS seal SHA-256:
  `c3cd654b2f25a92283d68e82144bfea41c5527fea48bb2c9351d0ecd30323634`.

OOS rejected before data access and no one-slot overlay ran. No Production or
trading state changed.

Artifacts are the matching V3B specification and runner plus
`btc-dra-conjunctive-partial-v3b-preselection-2026-08-02-run1.json`, `run2`,
and `btc-dra-conjunctive-partial-v3b-oos-seal-guard-2026-08-02.json` in the
task visualization directory.
