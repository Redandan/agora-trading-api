# BTC DRA Independent-Lot Hybrid Profit-Lock Exit V2 Result

Date: 2026-08-02

Research identity:
`BTC_DRA_INDEPENDENT_LOT_HYBRID_PROFIT_LOCK_EXIT_V2_RESEARCH`

Decision:

```text
DATA_QUALITY_PASS
BASELINE_PARITY_PASS_THROUGH_PRIOR_PROFIT_LOCK
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

The hybrid floor corrected the preceding candidate's unrealized-profit and
drawdown problem, but sold strong lots too early and materially underperformed
V1.

Validation total fell to `70.98130173`, which is `15.22168013` below V1 and
`42.26964435` below V2A. Median holding improved from the preceding profit
lock's `210h` to `192h`, but still missed the frozen `182.5h` limit. Annual
median holding improved in only `2/5` folds.

The candidate is `NO_CANDIDATE`; DRA V1 remains the reference.

## Frozen candidate

`ENTRY_ATR_1R_ARM_MAX_HALF_PEAK_OR_PEAK_MINUS_1R_LOCK_EXIT`

Every DRA lot remained independent and there was no runner or entitlement.
After a lot's peak net PnL reached `1R = entry ATR14 * quantity`, its causal
profit floor was:

```text
max(50% * peak net PnL, peak net PnL - 1R)
```

The half-peak branch controlled modest gains. Above `2R`, the second branch
limited maximum giveback to `1R`. A sale queued only when current estimated net
PnL was strictly positive and no greater than the effective floor; next-open
PnL also had to remain strictly positive.

This was the only tested formula. No multiplier, threshold, branch, lookback,
or factor was scanned.

## Data and reproducibility

- source: server-local, read-only `md_kline`, OKX `BTCUSDT`, complete `1h`;
- first open: `2019-01-01T00:00:00`;
- last included close: `2025-01-01T00:00:00`;
- rows: `52,608`;
- all data-quality defect counts: zero;
- input SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- specification SHA-256:
  `755c1b15e36bdd86ab70aa3f456fccafd5a711ff3fdfe34330b50ef509bf27e4`;
- preceding profit-lock specification/runner SHA-256:
  `98b13326ebf22a345162a4046b2c0c64723aaaaf0753fa1ca031bec8b3008277`
  and `739fea38ec89d2ea6dfc9161addc8a69213b13fbb6dd40d103155d49e06aae6c`;
- previous structural specification/runner SHA-256:
  `33ab31ab4b60beef918e4e75a3cd4445d3af9d874e0a2bf969b9c6532cd371be`
  and `41e1150510a1a68bff2d12fe4b3edf7d594623e701ab46f13485039e4bb74fa3`;
- V2C/V2D/V2E dependency SHA-256:
  `7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37`,
  `5443f8efdfdfc0522e867513efb7090d547eada7865f2fde2097309cdf224952`,
  and `0d0f8542d4d0863e7148d4ce69abb7ed41cb1abb053ff382effd9e3ab8d65d9b`;
- hybrid runner SHA-256:
  `6e4e43c29765a5d487214a0981bd802b134d9904f0ac558948552908940d8673`.

Two complete preselection runs produced byte-identical `1,787,444`-byte JSON
outputs with SHA-256:

`94667470b2f6ea7bd0380c5443ab9af1290aad0fb7017896d2300d4b616b939e`.

## Checkpoint acceptance

Before the hybrid result was accepted, the runner exactly reproduced V1,
V2A, every V2B/V2C/V2D checkpoint and annual audit, V2E, the prior structural
study, and the preceding independent-lot `1R`/half-peak profit lock. Artifact,
numeric, and operational parity all passed.

## Validation frontier

Window: `2023-01-01` through `2024-12-31`. Amounts are USDT.

| Exit | Realized | Unrealized | Total | Max DD | Median | P90 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| V1 | `89.41118307` | `-3.20820121` | `86.20298186` | `7.121498%` | `182.5h` | `1,418.3h` |
| V2A `1.50 ATR` | `116.45914729` | `-3.20820121` | `113.25094608` | `8.945793%` | `401.0h` | `1,846.6h` |
| Loose half-peak profit lock | `79.52385626` | `17.20327680` | `96.72713306` | `16.478718%` | `210.0h` | `1,263.0h` |
| **Hybrid max-1R giveback** | **`74.18950294`** | **`-3.20820121`** | **`70.98130173`** | **`6.999583%`** | **`192.0h`** | **`854.4h`** |

The hybrid had `51` buys, `50` sells, one open lot, zero blocked entries, zero
deferred exits, `16.873461%` average utilization, `60%` peak utilization, and
`1,574.18950294` turnover.

Relative to the loose half-peak version, the hybrid changed:

- realized: `-5.33435332`;
- unrealized: `-20.41147801`;
- total: `-25.74583133`;
- drawdown: `16.478718% -> 6.999583%`;
- median: `210h -> 192h`; and
- open lots: `3 -> 1`.

It successfully removed the two large ending mark-to-market winners, but
applying the tighter branch throughout the window surrendered more future
trend value than it locked.

## Formula and path audit

| Audit | Validation result |
| --- | ---: |
| Lots armed | `50` |
| Queues / successful fills | `50 / 50` |
| Half-peak binding exits | `31` |
| Peak-minus-1R binding exits | `19` |
| Exact ties | `0` |
| Half-peak branch realized PnL | `18.70276042` |
| Peak-minus-1R branch realized PnL | `55.48674252` |
| Deferred fills | `0` |
| Missing entry ATR | `0` |
| Trigger/floor/positive-fill violations | `0 / 0 / 0` |

The only ending lot was the same unarmed `2024-12-16` lot with unrealized
`-3.20820121`; it never reached its `1R` maturity threshold. All candidate
logic and causal assertions passed. The failure was economic.

## Design result

Design produced realized `200.85777417`, unrealized `14.89205948`, total
`215.74983365`, drawdown `51.118356%`, median/P90 holding
`233.0h / 1,399.2h`, `99 / 93 / 6` buys/sells/open lots, six blocked entries,
`47.264545%` average utilization, and `96%` peak utilization.

Although Design total exceeded V2A Design, its `51%` drawdown, six ending lots,
and long holding profile were unacceptable regime-instability evidence.

## Fair-reset annual folds

| Year | Hybrid total | V1 total | Hybrid median | V1 median | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| 2020 | `83.81650286` | `56.19904224` | `198.0h` | `113.0h` | total win, hold loss |
| 2021 | `45.69303890` | `31.45159316` | `304.0h` | `103.5h` | total win, hold loss |
| 2022 | `-25.18167253` | `-15.77563722` | `113.0h` | `126.0h` | total loss, hold win |
| 2023 | `27.84196064` | `44.20017391` | `184.5h` | `204.0h` | total loss, hold win |
| 2024 | `41.92376492` | `38.78866787` | `194.0h` | `160.0h` | total win, hold loss |

Annual total wins passed at `3/5`; annual median-hold wins failed at `2/5`.

## Frozen gate decision

Passed:

- ending unrealized PnL;
- maximum drawdown;
- P90 holding;
- annual total wins;
- entry-ATR completeness;
- trigger and hybrid-floor correctness;
- floor monotonicity; and
- strictly net-positive fills.

Failed:

- Validation total `>= V1` by `15.22168013`;
- Validation total `>= 90% V2A` by `30.94454974`;
- Validation realized `>= V1` by `15.22168013`;
- median holding by `9.5h`; and
- annual median-hold wins, `2/5` versus required `3/5`.

No gate was relaxed.

## Terminal interpretation

The two independent-lot profit-lock studies now bracket the observed trade-off:

- a loose half-peak floor retained more total value but left it unrealized and
  produced excessive drawdown/holding; and
- adding a one-entry-ATR maximum giveback fixed risk and ending inventory but
  sold too many future winners early.

Simply changing the peak-profit fraction or ATR giveback amount next would be
post-result parameter tuning. The pre-2025 evidence does not support another
full-lot price-only profit-lock variant under the current gates.

A materially new next study would have to change another contract dimension,
such as entry quality or partial quantity realization, and justify how it can
exceed the `101.92585147` profitability threshold rather than only interpolate
between these failed endpoints.

## OOS and Production boundary

No candidate was frozen. The OOS command returned `OOS_SEAL_REJECT` before
post-2024 data access. A second attempt refused to overwrite the guard output;
its SHA-256 remained:

`a745550eb76b76e168cbe2921afcde11b91fc43fde8f7307b7edb739692a3c72`.

Therefore 2025+ OOS was not opened and the independent `30 USDT` one-slot
overlay was not run. No runtime, configuration, database, DRA V1, position
`263`, owner `509`, Grid/OCO, funds, schedules, Telegram, order, commit, or
deployment changed.

## Reproducible artifacts

- specification:
  `docs/btc-dra-independent-lot-hybrid-profit-lock-exit-v2-research.md`;
- research runner:
  `research/btc_dra_independent_lot_hybrid_profit_lock_exit_v2.py`;
- preselection run 1:
  `C:\Users\Redan\.codex\visualizations\2026\07\31\019fb8c9-167b-7622-9000-c2ea73925092\btc-dra-hybrid-profit-lock-v2-preselection-2026-08-02-run1.json`;
- byte-identical run 2:
  `C:\Users\Redan\.codex\visualizations\2026\07\31\019fb8c9-167b-7622-9000-c2ea73925092\btc-dra-hybrid-profit-lock-v2-preselection-2026-08-02-run2.json`;
- OOS seal guard:
  `C:\Users\Redan\.codex\visualizations\2026\07\31\019fb8c9-167b-7622-9000-c2ea73925092\btc-dra-hybrid-profit-lock-v2-oos-seal-guard-2026-08-02.json`.

This is historical research evidence only and does not authorize a DRA runtime
change.
