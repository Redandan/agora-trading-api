# BTC DRA Flat-Regime Stale-Inventory Admission Veto V2 Result

- Research identity:
  `BTC_DRA_FLAT_REGIME_STALE_INVENTORY_ADMISSION_VETO_V2_RESEARCH`
- Candidate: `FLAT_STALE_7D_AND_60USDT_ADMISSION_VETO`
- Result date: 2026-08-02
- Status: `NO_CANDIDATE`
- Scope: research only; not `SHADOW`, not `PAPER`, not `LIVE`

## Decision

The fixed stale-inventory veto affects the intended risk mechanism, but it does
not make the flat sleeve or routed portfolio acceptable.

On Validation, the router's drawdown falls from `12.399848%` to `10.770310%`
and total PnL remains above DRA V1. It still exceeds the frozen drawdown ceiling
of `9.121498%`, while median and P90 holding worsen. Annual total-PnL wins fall
to `2/5`, below the required `3/5`.

The principal failure is a route-timing side effect. Rejecting a flat signal
does not update the last accepted-entry timestamp, so the shared arm/cooldown
can re-open before it would have after an accepted flat lot. That changes which
later non-flat DRA V1 signals are eligible. In 2022, the veto reduces flat lots
but increases non-flat DRA lots, leaving more rather than less stale inventory.

## Frozen candidate

The V1 flat regime, EMA20 reclaim entry, and `1R / EMA5 / 0.5R` profit-only exit
are unchanged. The only candidate rule is:

```text
staleExposureVeto = oldestOpenFlatLotAge >= 168 hours
                    and openFlatLotCost >= 60 USDT
```

The causal snapshot is taken on the complete hourly signal bar after scheduled
fills and exits. A veto rejects only that flat entry. It does not liquidate or
modify existing lots, does not update the last accepted-entry timestamp, and
does not directly reject non-flat DRA V1 entries.

The full preregistration is in
`docs/btc-dra-flat-regime-stale-inventory-admission-veto-v2-research.md`.

## Reproducibility

- Selection window: 2019-01-01 through 2024-12-31 UTC.
- Selection rows: `52,608`.
- Selection SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.
- Specification SHA-256:
  `6c47810fcded8f8792b724ad39d7476d884f40e2e219902fe64d904414dd3030`.
- Runner SHA-256:
  `41ee7d08bb459890b4e1b078fe839f31f6ce32dd4cec02cc473450d01c28dbbc`.
- DRA V1, flat V1, router V1, and 30-USDT one-slot checkpoints: all exact.
- Machine-readable output:
  `btc-dra-flat-regime-stale-inventory-admission-veto-v2-2026-08-02.json`.

The hypothesis was generated from already inspected history. Pre-2025 results
and July 2026 are labelled `POST_HOC_HISTORICAL_RESEARCH_ONLY`; no clean OOS
claim is available.

## Validation comparison: 2023-2024

| Metric | DRA V1 | Flat V1 | Flat V2 veto | Router V1 | Router V2 veto |
| --- | ---: | ---: | ---: | ---: | ---: |
| Realized PnL (USDT) | 89.41118307 | 35.82305220 | 23.66845009 | 102.78528314 | 99.94153541 |
| Unrealized PnL (USDT) | -3.20820121 | -1.56726556 | -1.56726556 | -1.56726556 | -1.56726556 |
| Total PnL (USDT) | 86.20298186 | 34.25578664 | 22.10118453 | 101.21801758 | 98.37426985 |
| Max drawdown | 7.121498% | 5.798793% | 5.867744% | 12.399848% | 10.770310% |
| Median hold | 182.5 h | 245.0 h | 257.0 h | 226.5 h | 245.0 h |
| P90 hold | 1418.3 h | 1951.2 h | 2211.8 h | 2391.2 h | 2435.6 h |
| Average utilization | 21.632695% | 11.017100% | 8.711354% | 28.813953% | 27.852257% |
| Turnover (USDT) | 1589.41118307 | 725.82305220 | 473.66845009 | 1902.78528314 | 1839.94153541 |
| Buys / sells / open | 51 / 50 / 1 | 24 / 23 / 1 | 16 / 15 / 1 | 61 / 60 / 1 | 59 / 58 / 1 |
| Blocked entries | 0 | 0 | 40 | 2 | 19 |

The standalone veto evaluates 56 flat signals, accepts 16, and rejects 40.
The router evaluates 36 flat signals, accepts 17, and rejects 19. Every veto
satisfies both frozen predicates; there are zero cap rejections in those two
Validation runs and zero non-flat admission rejections.

## Gate results

### Standalone flat V2

Passed:

- positive realized and total PnL;
- unrealized PnL no worse than DRA V1;
- drawdown no higher than DRA V1;
- positive total PnL in `4/5` fair-reset folds;
- all admission accounting and causal predicate audits.

Failed:

- median hold `257.0 h > 182.5 h`;
- P90 hold `2211.8 h > 1418.3 h`.

### Router V2

Passed:

- Validation realized and total PnL at least DRA V1;
- unrealized PnL no worse than DRA V1;
- drawdown strictly lower than router V1;
- all admission accounting and route-isolation audits.

Failed:

- drawdown `10.770310% > 9.121498%`;
- median hold `245.0 h > 182.5 h`;
- P90 hold `2435.6 h > 1418.3 h`;
- annual total wins `2/5 < 3/5`;
- annual median-hold wins `2/5 < 3/5`.

The one-slot overlay exactly reproduces its prior checkpoint and has zero
admission-veto rejections, as required. Its `51` blocked signals are ordinary
one-slot capacity rejections, not stale-exposure vetoes.

## Annual fair-reset totals

| Year | DRA V1 | Flat V1 | Flat V2 veto | Router V1 | Router V2 veto | Router V2 vetoes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 2020 | 56.19904224 | 21.27442094 | 13.24731838 | 60.56300091 | 52.81788637 | 17 |
| 2021 | 31.45159316 | 18.73132494 | 16.42006273 | 44.92582504 | 41.82796505 | 3 |
| 2022 | -15.77563722 | -3.51231158 | -6.49392132 | -33.42435093 | -44.78885760 | 8 |
| 2023 | 44.20017391 | 15.84163049 | 9.92683736 | 47.00821510 | 44.16446737 | 19 |
| 2024 | 38.78866787 | 18.17903716 | 11.93922818 | 47.83927944 | 47.83927944 | 0 |

## 2022 route-timing diagnosis

| 2022 metric | Router V1 | Router V2 veto |
| --- | ---: | ---: |
| Flat entries / exits | 9 / 8 | 6 / 5 |
| Non-flat DRA entries / exits | 7 / 4 | 9 / 4 |
| Total entries / exits / open | 16 / 12 / 4 | 15 / 9 / 6 |
| Ending open cost | 120 USDT | 180 USDT |
| Unrealized PnL | -50.50729670 USDT | -58.89019363 USDT |
| Total PnL | -33.42435093 USDT | -44.78885760 USDT |
| Max drawdown | 17.975656% | 21.647525% |

Eight flat signals are correctly vetoed in 2022. However, two additional
non-flat DRA entries become eligible on the shifted shared arm/cooldown
timeline, while non-flat exits remain at four. This direct route accounting
explains why a locally correct flat-entry veto can worsen combined inventory.

## July 2026 post-hoc diagnostic

The veto does not fire in July because the stale-age and 60-USDT exposure
predicates never coincide at a new eligible signal. Flat V2 therefore exactly
matches flat V1:

- buys / sells / open: `3 / 2 / 1`;
- realized: `2.43365606 USDT`;
- unrealized: `-0.80687570 USDT`;
- total: `1.62678036 USDT`;
- drawdown: `0.968989%`.

Router V2 also exactly matches router V1 for July at `-0.11363607 USDT` total.
This is post-hoc behavior only and cannot override the historical gate failure.

## Next defensible hypothesis

Do not scan the `168-hour` or `60-USDT` constants. The observed failure is the
shared timing transition after a rejected flat signal. A separately
preregistered V2B experiment may keep the same entry, exit, and veto formula
but make a veto reserve the same seven-day cooldown timestamp that the rejected
entry would have occupied. This would test whether preventing route
substitution removes the extra non-flat DRA inventory. It must remain
post-hoc, rerun all existing gates, and still fail closed.

## Operational boundary

No Production runtime, configuration, database, DRA V1, position `263`, owner
`509`, Grid/OCO, funds, schedules, orders, deployment, or external write was
changed.
