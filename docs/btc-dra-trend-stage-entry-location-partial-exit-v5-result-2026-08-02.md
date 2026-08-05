# BTC DRA Trend-Stage Entry-Location Partial Exit V5 Result

Date: 2026-08-02

Research identity:
`BTC_DRA_TREND_STAGE_ENTRY_LOCATION_PARTIAL_EXIT_V5_RESEARCH`

Decision:

```text
DATA_QUALITY_PASS
DESIGN_CANDIDATE_FROZEN_BEFORE_VALIDATION
BASELINE_PARITY_PASS_THROUGH_QUALITY_TIERED_PARTIAL_V4
PRE_2025_VALIDATION_AND_FAIR_RESET_FOLDS_COMPLETE
NO_CANDIDATE
TREND_STAGE_ENTRY_LOCATION_BRANCH_STOP
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
RESEARCH_ONLY
NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE
```

## Outcome first

The Design-selected early-trend candidate narrowly beat V1 in Validation, but
did not retain enough V2A profit and still missed the holding gates.

Validation realized/unrealized/total were
`89.44425444 / -0.64164024 / 88.80261420`. Total exceeded V1 by
`2.59963234`, realized exceeded V1 by only `0.03307137`, and ending
unrealized improved by `2.56656097`. However, it retained only `78.412249%` of
V2A and missed the frozen `90%` V2A threshold by `13.12323727`.

Drawdown `7.076057%` passed. Cost-weighted median `190.5h` missed by `8h`,
and P90 `1,422h` missed by `3.7h`. Annual total wins passed at `4/5`, but
annual median-hold wins were only `1/5`.

The decision is `NO_CANDIDATE`; DRA V1 remains the reference and 2025+ OOS
remains sealed.

## Frozen architecture

Every DRA V1 signal continued to buy an independent `30 USDT` lot. No entry
was blocked, resized, entitled, or charged against a runner quota by the
factor.

The only Design-frozen candidate used the complete daily close's consecutive
days above causal EMA20:

- days `1-7`: full `30 USDT` V2A `1.50 ATR` ratchet;
- day `8+`: no-`1R` V3C path, requiring both strict net-positive PnL and a
  complete hourly close below causal EMA5 before selling `24 USDT` next open;
- the exact `6 USDT` remainder was rebased at the partial fill and used
  unchanged V2A `1.50 ATR`.

No fixed profit percentage, loss exit, time exit, final liquidation, extra
EMA/ATR setting, partial ratio, or parameter scan was introduced.

## Design-only firewall and ablation

The first stage physically queried only `35,064` complete rows ending at
`2023-01-01T00:00:00`. Its data SHA-256 was
`c7771c7b3e65f964628208443efe0c931ddfc5b5d1d62f82bdf2afc731805c53`.
Validation and OOS were inaccessible at this stage.

| Design candidate | Total | DD | Median | P90 | Full V2A / partial | Eligible |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| EMA20 streak `<=7` | `146.10426831` | `21.337810%` | `179h` | `1,550h` | `46 / 54` | yes, selected by highest total |
| EMA20 streak `<=20` | `124.84303187` | `25.743473%` | `311h` | `1,571.6h` | `78 / 21` | no; ending unrealized worse than V1 |
| EMA20 streak `<=7` and extension `<=1 ATR` | `135.65322961` | `21.132350%` | `95h` | `1,401h` | `32 / 68` | yes |

The selected `<=7` formula had Design realized/unrealized
`188.45669082 / -42.35242251`. Design V1 total was `90.77797326`; Design V2A
total/median were `176.40466034 / 371h`.

The first Design execution exposed a mechanical audit contradiction: the
specification allowed the single hard-inception ATR-unavailable lot to fall
back to V2A, while the runner also required the inherited zero-missing-route
flag. Only the audit was corrected to validate the explicitly recorded
fallback. No candidate, trading rule, metric calculation, trade, or selection
ordering changed. The obsolete first artifact is not an accepted freeze.

After correction, two accepted Design artifacts were byte-identical at
`524,158` bytes with SHA-256
`7cf4da54983ddc07c91b36eac84daf00f1f7d4250364521c2efd12d067152a5a`.
They froze candidate
`EMA20_ABOVE_STREAK_LE_7_FULL_V2A_ELSE_NET_POSITIVE_PARTIAL_24_6` with freeze
SHA-256
`8deddf5a0774d7eba64ec0269ef29f198dff8961f84d68cada904159b642a1c5`
before Validation was opened.

## Validation result

| Exit | Realized | Unrealized | Total | DD | Median | P90 | Annual total / hold wins |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| V1 | `89.41118307` | `-3.20820121` | `86.20298186` | `7.121498%` | `182.5h` | `1,418.3h` | reference |
| V2A | `116.45914729` | `-3.20820121` | `113.25094608` | `8.945793%` | `401h` | `1,846.6h` | reference |
| V4 quality tier | `99.68390014` | `-3.20820121` | `96.47569893` | `4.938290%` | `210h` | `1,315h` | `2/5 / 0/5` |
| **V5 early trend** | **`89.44425444`** | **`-0.64164024`** | **`88.80261420`** | **`7.076057%`** | **`190.5h`** | **`1,422h`** | **`4/5 / 1/5`** |

V5 had `51` buys, `78` exit slices, one open `6 USDT` remainder, zero blocked
entries, zero deferred exits, average utilization `17.343912%`, maximum open
cost `156 USDT`, and turnover `1,613.44425444`.

There were `23` full V2A entries and `28` partial-path entries. Route total
PnL was `69.14051340` for full V2A and `19.66210080` for the partial path.
Median first realization was `97h`, but cost-weighted median remained
`190.5h`, and median final completion remained `401h`.

## Annual folds

| Year | V5 total | V1 total | V5 median | V1 median | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| 2020 | `70.88881128` | `56.19904224` | `163h` | `113h` | total win, hold loss |
| 2021 | `53.83114964` | `31.45159316` | `169h` | `103.5h` | total win, hold loss |
| 2022 | `-3.29310158` | `-15.77563722` | `280h` | `126h` | total win, hold loss |
| 2023 | `38.66708857` | `44.20017391` | `140h` | `204h` | total loss, hold win |
| 2024 | `49.83637115` | `38.78866787` | `208h` | `160h` | total win, hold loss |

The formula generalized better for annual total direction than V4, but the
holding improvement did not generalize. Its entry-time trend age could not
identify which later winners should retain full size: shortening more routes
gave up V2A upside, while keeping early-trend routes did not make the median
competitive with V1.

## Reproducibility and OOS seal

- preselection rows/hash: `52,608` /
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- specification SHA-256:
  `103fd30dbc9022c77c2a9625ca0cb93dcce8f63d449a3560457bedc9b0c7b3fa`;
- runner SHA-256:
  `f595764db86bbaa40d2ab45d36f3fe707f4ff44f63877cbf5b71fe1c416c9427`;
- two byte-identical `4,415,852`-byte preselection JSONs SHA-256:
  `84537ebca99c1f1bf6cfe5d42f44a9013534db7acfc1ab566476decae6be822f`.

The OOS command returned `OOS_SEAL_REJECT` before any post-2024 query because
preselection froze no candidate. A second call refused to overwrite the guard;
its SHA-256 remained
`dad72da9ea5c5edad2e9e7cd8f2503d9e27eafb8218f4009e3c40ff37a83a227`.

No independent one-slot overlay ran. No runtime, configuration, database, DRA
V1, position `263`, owner `509`, Grid/OCO, funds, schedules, Telegram, order,
commit, or deployment changed.

## Research implication

Static entry-time classification is now the weak link. The next defensible
hypothesis is a per-lot post-entry state transition: every lot begins on the
fast path and may earn full-runner treatment only after observable post-entry
trend continuation, without epoch quotas or blocked buys. That would be a new
research branch and must be preregistered; V5 provides no authority to run or
deploy it.

## Reproducible artifacts

- specification:
  `docs/btc-dra-trend-stage-entry-location-partial-exit-v5-research.md`;
- runner:
  `research/btc_dra_trend_stage_entry_location_partial_exit_v5.py`;
- accepted Design freezes:
  `btc-dra-trend-stage-partial-v5-design-freeze-2026-08-02-run2-fixed.json`
  and `run3-fixed.json` in the task visualization directory;
- accepted preselection runs:
  `btc-dra-trend-stage-partial-v5-preselection-2026-08-02-run1.json` and
  `run2.json` in the same directory;
- OOS seal:
  `btc-dra-trend-stage-partial-v5-oos-seal-guard-2026-08-02.json` in the same
  directory.
