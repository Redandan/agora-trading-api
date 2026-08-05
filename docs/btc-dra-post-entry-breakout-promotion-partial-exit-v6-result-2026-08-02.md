# BTC DRA Post-Entry Breakout Promotion Partial Exit V6 Result

Date: 2026-08-02

Research identity:
`BTC_DRA_POST_ENTRY_BREAKOUT_PROMOTION_PARTIAL_EXIT_V6_RESEARCH`

Decision:

```text
DATA_QUALITY_PASS
BASELINE_PARITY_PASS_THROUGH_TREND_STAGE_ENTRY_LOCATION_PARTIAL_V5
DESIGN_VALIDATION_COMPLETE
PRE_2025_FAIR_RESET_FOLDS_COMPLETE
NO_CANDIDATE
POST_ENTRY_PROMOTION_BRANCH_STOP
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
RESEARCH_ONLY
NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE
```

## Outcome first

Removing all runner quotas did not solve the profit-retention problem. V6
made holding substantially faster, but fresh daily Donchian20 confirmation
arrived after most lots had already taken their fast partial exit.

Validation realized/unrealized/total were
`63.80546411 / -0.64164024 / 63.16382387`. Total was `23.03915799` below V1
and retained only `55.773330%` of V2A. It missed the frozen `90%` V2A total
threshold by `38.76202760`, and realized PnL was `25.60571896` below V1.

Holding and risk passed strongly: drawdown was `4.273596%`, cost-weighted
median/P90 were `45h / 711h`, and annual median-hold wins were `5/5`.
Profitability did not pass: annual total wins were only `1/5`.

The decision is `NO_CANDIDATE`; DRA V1 remains the reference and 2025+ OOS
remains sealed.

## Frozen candidate

Every DRA V1 entry still bought the unchanged independent `30 USDT` lot. All
normal lots began on the net-positive plus hourly-EMA5 fast path. A lot could
independently promote to full-quantity V2A only when a later complete daily
close freshly crossed above the prior 20 complete daily highs and full
liquidation was already strictly net-positive.

There was no runner quota, epoch, entitlement, target, `1R`, tie-break, or
entry filter. If several lots qualified on one event, all were promoted. The
annual 2020 fold actually promoted two lots on the same event, confirming that
the engine did not retain the old one-runner restriction.

An unpromoted lot sold `24 USDT` only after both a complete hourly close below
causal EMA5 and a strict net-positive estimate. Its exact `6 USDT` remainder
was rebased at the fill open and used unchanged V2A `1.50 ATR`. A promoted lot
used full-quantity V2A from its observable breakout close.

## Validation comparison

| Exit | Realized | Unrealized | Total | DD | Median | P90 | Annual total / hold wins |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| V1 | `89.41118307` | `-3.20820121` | `86.20298186` | `7.121498%` | `182.5h` | `1,418.3h` | reference |
| V2A | `116.45914729` | `-3.20820121` | `113.25094608` | `8.945793%` | `401h` | `1,846.6h` | reference |
| V5 early trend | `89.44425444` | `-0.64164024` | `88.80261420` | `7.076057%` | `190.5h` | `1,422h` | `4/5 / 1/5` |
| **V6 post-entry promotion** | **`63.80546411`** | **`-0.64164024`** | **`63.16382387`** | **`4.273596%`** | **`45h`** | **`711h`** | **`1/5 / 5/5`** |

V6 had `51` buys, `96` exit slices, one open `6 USDT` remainder, zero blocked
entries, zero deferred fills, average utilization `8.821888%`, maximum open
cost `102 USDT`, and turnover `1,587.80546411`.

Median first realization was only `26h`; median final completion remained
`401h` because the `6 USDT` remainders still used V2A. The cost-weighted
holding metric nevertheless passed because `24/30` of most lots was released
quickly.

## Promotion audit

Validation observed `38` fresh Donchian20 events but promoted only `5` lots,
or `9.803922%` of buys. All five promotions were unique, post-entry,
pre-partial, strictly net-positive, and tied to an exact fresh event. All five
event-eligible lots were promoted; quota/tie-break rejections were exactly
zero.

Maximum same-event promotions in aggregate Validation were one. The fair-reset
2020 fold recorded two simultaneous promotions, which directly proves that V6
allowed multiple runners when multiple lots were eligible.

Promotion median/P90 delay after fill were `120h / 172.8h`, while median first
realization was `26h`. This timing mismatch is the failure mechanism: `46`
lots completed the fast `24 USDT` partial path before earning promotion.
There were `88` repeated event-lot observations where a fresh event arrived
after a lot had already partially exited, plus two non-positive event-lot
observations.

All cost, quantity, positive-fill, causal breakout, one-partial, promotion
uniqueness, and no-quota audits passed. The failure was economic rather than
mechanical.

## Annual folds

| Year | V6 total | V1 total | V6 median | V1 median | Promotions | Result |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 2020 | `44.04203758` | `56.19904224` | `76h` | `113h` | `7` | total loss, hold win |
| 2021 | `19.92962124` | `31.45159316` | `21h` | `103.5h` | `4` | total loss, hold win |
| 2022 | `-2.09439995` | `-15.77563722` | `23h` | `126h` | `2` | total win, hold win |
| 2023 | `25.19615514` | `44.20017391` | `28h` | `204h` | `1` | total loss, hold win |
| 2024 | `37.66851425` | `38.78866787` | `55h` | `160h` | `4` | total loss, hold win |

Design realized/unrealized/total were
`100.46804785 / -20.28428829 / 80.18375956`, drawdown was `27.643897%`, and
median/P90 were `42h / 1,353h`. It produced `15` promotions. The same
fast-but-under-monetized behavior was already present in Design.

## Reproducibility and OOS seal

- preselection rows/hash: `52,608` /
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- specification SHA-256:
  `7e2245eb6478a9b3a5708d7b5758ae6e89424c7282ab7836742b40b050a5b130`;
- runner SHA-256:
  `03babd93d146318ec779031bf5b5623655bf62d4430ff0f94a122c8208882697`;
- two byte-identical `4,939,049`-byte preselection JSONs SHA-256:
  `1c0fde46b994aff094b74b4b322f5947fbf516b2b580f5689ccc6f1ba07078dc`.

The OOS command returned `OOS_SEAL_REJECT` before post-2024 data access because
preselection froze no candidate. A second call refused to overwrite the guard;
its SHA-256 stayed
`3947148459f91554cfa1e51d58504db6554b27609b0581196f7ff95b67e3192e`.

The independent one-slot overlay did not run. No runtime, configuration,
database, DRA V1, position `263`, owner `509`, Grid/OCO, funds, schedules,
Telegram, order, commit, or deployment changed.

## Research implication

The confirmed gap is now temporal, not quota-related. A complete daily
Donchian20 breakout arrives around `120h` after entry, while the fast partial
path realizes at `26h`. The next defensible work is therefore a Design-only
timing diagnostic on earlier hourly continuation observations; it should first
test whether a causal signal exists before registering another sell strategy.
Scanning more Donchian periods or merely delaying the partial exit would undo
the purpose of V6 and is not justified by this result.

## Reproducible artifacts

- specification:
  `docs/btc-dra-post-entry-breakout-promotion-partial-exit-v6-research.md`;
- runner:
  `research/btc_dra_post_entry_breakout_promotion_partial_exit_v6.py`;
- accepted preselection runs:
  `btc-dra-post-entry-promotion-v6-preselection-2026-08-02-run1.json` and
  `run2.json` in the task visualization directory;
- OOS seal:
  `btc-dra-post-entry-promotion-v6-oos-seal-guard-2026-08-02.json` in the same
  directory.
