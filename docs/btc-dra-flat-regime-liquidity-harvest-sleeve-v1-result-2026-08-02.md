# BTC DRA Flat-Regime Liquidity-Harvest Sleeve V1 Result

- Research identity: `BTC_DRA_FLAT_REGIME_LIQUIDITY_HARVEST_SLEEVE_V1_RESEARCH`
- Candidate: `EMA20_5D_FLAT_025ATR_RECLAIM_1R_EMA5_HALF_R_FULL_EXIT`
- Result date: 2026-08-02
- Status: `NO_CANDIDATE`
- Scope: research only; not `SHADOW`, not `LIVE`

## Decision

Restricting the liquidity-harvest entry to a causal flat regime corrected the
earlier conceptual error of requiring an upward trend. The standalone flat
sleeve is profitable and has lower Validation drawdown than DRA V1. A
mutually-exclusive router also raises Validation total PnL from `86.20298186`
USDT to `101.21801758` USDT.

The candidate nevertheless fails the frozen acceptance gates. The router's
Validation drawdown rises to `12.399848%`, median holding time rises to `226.5`
hours, P90 holding time rises to `2391.2` hours, and annual median-hold wins are
only `2/5`. The independent one-slot overlay has the same holding-tail problem
and severe capital opportunity cost. No Production, SHADOW, or LIVE promotion
is authorized.

## Frozen causal rules

All signals use only complete OKX `BTCUSDT` 1-hour candles and causal daily
state.

- Flat regime:
  `abs(EMA20_now - EMA20_5d_ago) <= 0.25 * ATR14`.
- No positive EMA slope, rising-trend, or DRA V1 signal is required for a flat
  sleeve entry.
- Entry signal: flat regime, previous hourly close at or below the causal daily
  EMA20, and current hourly close above it; fill at the next hourly open.
- Per-lot exit: peak net PnL has reached `1R`, current hourly close is below the
  causal hourly EMA5, and current net PnL remains at least `0.5R`; fill at the
  next hourly open only if strictly net positive after fees and slippage.
- Router: when flat, only the flat reclaim entry may fire; when non-flat, only
  the unchanged DRA V1 entry may fire. Existing lots retain their route-specific
  exit. Arm, cooldown, and the `250 USDT` reference cap are shared.
- Accounting remains `30 USDT` per lot, `0.10%` fee per side, `0.05%` adverse
  slippage per side, independent lots, next-open fills, no stop, no time exit,
  no forced loss, and no final liquidation.

The complete preregistration and gate definitions are in
`docs/btc-dra-flat-regime-liquidity-harvest-sleeve-v1-research.md`.

## Data and reproducibility

- Selection window: 2019-01-01 through 2024-12-31 UTC.
- Selection rows: `52,608`.
- Selection SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.
- Specification SHA-256:
  `5ebefc259e7d215f433bc3d727abf6c7c2c6adec60de87f5bf32c72170a29ab5`.
- DRA V1 checkpoint reproduction: passed exactly.
- Machine-readable result:
  `btc-dra-flat-regime-liquidity-harvest-sleeve-v1-2026-08-02.json`.

The pre-2025 history and July 2026 behavior had already been inspected in the
iterative conversation before this formal preregistration. They are therefore
labelled `POST_HOC_HISTORICAL_RESEARCH_ONLY`; this study cannot claim clean OOS.
Even a historical gate pass would have been only
`HISTORICAL_GATE_PASS_FORWARD_PENDING`.

## Validation comparison: 2023-2024

| Metric | DRA V1 | Flat sleeve | Mutually-exclusive router | One-slot flat overlay |
| --- | ---: | ---: | ---: | ---: |
| Realized PnL (USDT) | 89.41118307 | 35.82305220 | 102.78528314 | 17.67064201 |
| Unrealized PnL (USDT) | -3.20820121 | -1.56726556 | -1.56726556 | -1.56726556 |
| Total PnL (USDT) | 86.20298186 | 34.25578664 | 101.21801758 | 16.10337645 |
| Max drawdown | 7.121498% | 5.798793% | 12.399848% | 18.752917% |
| Median hold | 182.5 h | 245.0 h | 226.5 h | 251.0 h |
| P90 hold | 1418.3 h | 1951.2 h | 2391.2 h | 2466.4 h |
| Average utilization | 21.632695% | 11.017100% | 28.813953% | 47.560420% |
| Turnover (USDT) | 1589.41118307 | 725.82305220 | 1902.78528314 | 317.67064201 |
| Buys / sells / open | 51 / 50 / 1 | 24 / 23 / 1 | 61 / 60 / 1 | 11 / 10 / 1 |
| Blocked entries | 0 | 0 | 2 | 51 |

The flat sleeve audit passed all entry and fill invariants: all 24 entries were
flat-regime EMA20 reclaims, all 23 exit fills were strictly net positive, and
there were zero deferred exits or ATR inception fallbacks.

## Frozen gate evaluation

### Standalone flat sleeve

Passed:

- positive Validation realized and total PnL;
- unrealized PnL no worse than V1;
- drawdown no higher than V1;
- positive total PnL in `4/5` annual fair-reset folds.

Failed:

- median hold `245.0 h > 182.5 h`;
- P90 hold `1951.2 h > 1418.3 h`.

### Mutually-exclusive router

Passed:

- Validation realized and total PnL at least V1;
- unrealized PnL no worse than V1;
- annual total-PnL wins `4/5`.

Failed:

- drawdown `12.399848% > 9.121498%` (`V1 + 2 percentage points`);
- median hold `226.5 h > 182.5 h`;
- P90 hold `2391.2 h > 1418.3 h`;
- annual median-hold wins `2/5 < 3/5`.

## Annual fair-reset folds

| Year | V1 total | Flat total | Router total | One-slot total | V1 median | Router median |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 2020 | 56.19904224 | 21.27442094 | 60.56300091 | 5.76824730 | 113.0 h | 158.0 h |
| 2021 | 31.45159316 | 18.73132494 | 44.92582504 | 11.81519458 | 103.5 h | 109.5 h |
| 2022 | -15.77563722 | -3.51231158 | -33.42435093 | -12.30791583 | 126.0 h | 81.0 h |
| 2023 | 44.20017391 | 15.84163049 | 47.00821510 | 7.56416171 | 204.0 h | 315.5 h |
| 2024 | 38.78866787 | 18.17903716 | 47.83927944 | 8.30409575 | 160.0 h | 135.5 h |

The main risk concentration is 2022. The router finishes that fold with four
open lots and `-50.50729670` USDT unrealized PnL, producing a
`-33.42435093` USDT total result. The route accounting shows seven non-flat V1
entries and nine flat entries, but only four and eight exits respectively. This
is direct evidence that profitable completed exits did not prevent stale
inventory from dominating total PnL.

## July 2026 post-hoc diagnostic

This section is a diagnostic only, not selection and not OOS.

| Metric | DRA V1 | Flat sleeve | Router | One-slot overlay |
| --- | ---: | ---: | ---: | ---: |
| Realized PnL (USDT) | 0.00000000 | 2.43365606 | 2.43365606 | 1.31957399 |
| Unrealized PnL (USDT) | -2.48724653 | -0.80687570 | -2.54729213 | -0.80687570 |
| Total PnL (USDT) | -2.48724653 | 1.62678036 | -0.11363607 | 0.51269829 |
| Max drawdown | 1.870285% | 0.968989% | 1.189174% | 4.328369% |
| Buys / sells / open | 3 / 0 / 3 | 3 / 2 / 1 | 4 / 2 / 2 | 2 / 1 / 1 |

Flat-sleeve fills and exits:

| Entry fill (UTC) | Effective buy | Exit fill (UTC) | Effective sell | Net PnL | Hold |
| --- | ---: | --- | ---: | ---: | ---: |
| 2026-07-06 16:00 | 63,579.67395 | 2026-07-21 17:00 | 66,509.22875 | 1.31957399 USDT | 361 h |
| 2026-07-14 13:00 | 63,999.58380 | 2026-07-21 17:00 | 66,509.22875 | 1.11408207 USDT | 172 h |
| 2026-07-29 07:00 | 64,467.91785 | still open | n/a | -0.80687570 USDT unrealized | 65 h at cutoff |

These corrected prices match the intended July behavior much more closely than
the earlier mixed-trend experiment: entries occurred near the low-to-mid
`63k-64k` region and the two completed lots exited above `66.5k`. The month is
encouraging but cannot override the pre-2025 gate failure.

## Interpretation and next research direction

The useful part is the flat-regime entry, not the present exit package. It adds
historical PnL in four of five annual folds and the July trade locations are
economically sensible. The unresolved problem is stale inventory: the
profit-only rule protects realized trades but leaves lots that never reach or
retain the exit prerequisites, creating large unrealized losses and long tails.

The next defensible experiment should therefore not loosen the gate or scan
more EMA/ATR parameters. It should hold the flat-entry formula fixed and test
one preregistered inventory-control hypothesis that remains profit-only, such
as route-level admission control based on existing open-lot age/exposure. This
would address capital crowding and blocked entries without pretending a losing
lot can be safely liquidated. Until such a candidate passes the same gates, DRA
V1 remains the usable baseline and this sleeve remains research-only.

## Operational boundary

No Production runtime, configuration, database, DRA V1 rule, position `263`,
owner `509`, Grid/OCO behavior, funds, scheduler, order path, deployment, or
external write was changed. No 2025+ clean OOS claim is made.
