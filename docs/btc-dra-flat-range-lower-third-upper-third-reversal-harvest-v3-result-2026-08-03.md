# BTC DRA Flat-Range Lower-Third / Upper-Third Reversal Harvest V3 Result

- Research identity:
  `BTC_DRA_FLAT_RANGE_LOWER_THIRD_UPPER_THIRD_REVERSAL_HARVEST_V3_RESEARCH`
- Candidate:
  `FLAT_DONCHIAN20_LOWER_THIRD_RECLAIM_UPPER_THIRD_TOUCH_EMA5_REVERSAL`
- Result date: 2026-08-03
- Status: `NO_CANDIDATE`
- Scope: research only; not `SHADOW`, not `PAPER`, not `LIVE`

## Decision

The lower-range entry is economically coherent and improves drawdown and
ending unrealized PnL. The frozen upper-third qualification does not solve the
holding problem: it makes completed lots wait too long before the profitable
reversal exit becomes eligible.

Validation total PnL is `31.92221317 USDT`, only `2.33357347 USDT` below flat
sleeve V1, while drawdown improves from `5.798793%` to `5.762120%` and ending
unrealized PnL improves from `-1.56726556` to `-0.82980159 USDT`. However,
median holding rises from `245` to `512` hours and P90 rises from `1951.2` to
`2134.6` hours. Both are far above the DRA V1 gates.

The wait decomposition is decisive: median entry-to-upper-touch time is
`503` hours, while median upper-touch-to-exit-queue time is only `10` hours.
The EMA5 reversal reacts quickly after qualification; the frozen upper-third
requirement is the bottleneck.

## Frozen causal contract

- Flat regime:
  `abs(EMA20_now - EMA20_5d_ago) <= 0.25 * ATR14`.
- Range: high and low of the 20 complete UTC days strictly before the current
  day.
- Entry: hourly close crosses upward through the lower third and remains at or
  below the range midpoint.
- Fill: next-hour adverse buy price must remain at or below the signal
  midpoint.
- Per-lot range boundaries are frozen at entry.
- Qualification: a later complete hourly close reaches the frozen upper third.
- Exit: on a strictly later bar, close falls below causal hourly EMA5 while
  estimated net PnL remains positive; next-open fill must also remain positive.
- No fixed-profit target, `1R`, stop loss, time exit, forced loss, or final
  liquidation.

No DRA V1 router or admission limit is present. The full preregistration is in
`docs/btc-dra-flat-range-lower-third-upper-third-reversal-harvest-v3-research.md`.

## Reproducibility

- Selection window: 2019-01-01 through 2024-12-31 UTC.
- Selection rows: `52,608`.
- Selection SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.
- Specification SHA-256:
  `0d5df70d421e50e9946fe5861602ff93602a1c5795cd5c1d1da4246a3099315f`.
- Canonical runner SHA-256:
  `48f06b8d1d79368ad172ce10b13d13426e210e8e3b926e67d76a153808c1e9d2`.
- DRA V1 and flat sleeve V1 Validation checkpoints: exact.
- Canonical machine-readable evidence:
  `btc-dra-flat-range-lower-third-upper-third-reversal-harvest-v3-2026-08-03-evidence-r1.json`.

The first aggregate-only JSON is preserved and was not overwritten. The
canonical `evidence-r1` rerun added the preregistered per-signal records and
entry-to-touch / touch-to-queue decomposition without changing any strategy
condition. Headline metrics are identical.

All selection and July results are labelled
`POST_HOC_HISTORICAL_RESEARCH_ONLY`; no clean OOS claim is available.

## Validation comparison: 2023-2024

| Metric | DRA V1 | Flat sleeve V1 | Range harvest V3 | One-slot V3 |
| --- | ---: | ---: | ---: | ---: |
| Realized PnL (USDT) | 89.41118307 | 35.82305220 | 32.75201476 | 17.05699670 |
| Unrealized PnL (USDT) | -3.20820121 | -1.56726556 | -0.82980159 | -0.82980159 |
| Total PnL (USDT) | 86.20298186 | 34.25578664 | 31.92221317 | 16.22719511 |
| Max drawdown | 7.121498% | 5.798793% | 5.762120% | 19.031304% |
| Median hold | 182.5 h | 245.0 h | 512.0 h | 745.5 h |
| P90 hold | 1418.3 h | 1951.2 h | 2134.6 h | 2667.4 h |
| Average utilization | 21.632695% | 11.017100% | 9.837893% | 49.903101% |
| Turnover (USDT) | 1589.41118307 | 725.82305220 | 542.75201476 | 257.05699670 |
| Buys / sells / open | 51 / 50 / 1 | 24 / 23 / 1 | 18 / 17 / 1 | 9 / 8 / 1 |
| Blocked entries | 0 | 0 | 0 | 43 |

The reference V3 run has 18 fills, 17 upper touches, 17 queues, 17 strictly
positive exits, one open lot, and zero midpoint gap cancellations.

## Holding decomposition

| Stage | Median | P90 |
| --- | ---: | ---: |
| Entry fill to frozen upper-third touch | 503.0 h | 2127.2 h |
| Upper-third touch to EMA5 reversal queue | 10.0 h | 17.4 h |
| Entry fill to next-open exit | 512.0 h | 2134.6 h |

The exit confirmation adds little delay. Most holding time is spent waiting for
the entry-frozen upper range to be reached. The longest Validation lot holds
for `3338` hours before a profitable exit. Four other completed lots exceed
`1000` hours.

## Design robustness

Design is materially weaker than Validation:

- realized: `58.29106771 USDT`;
- unrealized: `-60.13520158 USDT`;
- total: `-1.84413387 USDT`;
- drawdown: `19.402320%`;
- median / P90 holding: `268.5 / 3538.4` hours;
- buys / sells / open: `31 / 28 / 3`;
- median open age: `8833` hours.

This is additional evidence that profitable completed exits can coexist with
economically dominant stale inventory.

## Frozen gate evaluation

Passed:

- positive Validation realized PnL;
- unrealized PnL no worse than DRA V1;
- drawdown no higher than DRA V1;
- 17 Validation sells, above the minimum 10;
- positive total PnL in `4/5` annual folds;
- all range, fill, touch, ordering, reversal, and next-open causal audits.

Failed:

- total `31.92221317 < 34.25578664 USDT` flat V1 floor;
- median hold `512.0 > 182.5` hours;
- P90 hold `2134.6 > 1418.3` hours.

No gate is lowered and 2025+ is not promoted to OOS.

## Annual fair-reset folds

| Year | DRA V1 total | Flat V1 total | V3 total | V3 DD | V3 median | V3 P90 | Buys / sells / open |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2020 | 56.19904224 | 21.27442094 | 8.97658792 | 7.241541% | 452.0 h | 1600.8 h | 7 / 7 / 0 |
| 2021 | 31.45159316 | 18.73132494 | 22.19555976 | 14.956479% | 428.5 h | 3632.1 h | 12 / 10 / 2 |
| 2022 | -15.77563722 | -3.51231158 | -6.61334241 | 8.186509% | 105.0 h | 988.4 h | 8 / 6 / 2 |
| 2023 | 44.20017391 | 15.84163049 | 11.40421226 | 5.762120% | 1017.0 h | 2134.6 h | 8 / 7 / 1 |
| 2024 | 38.78866787 | 18.17903716 | 18.97600676 | 4.076040% | 395.5 h | 1155.5 h | 11 / 10 / 1 |

The strategy is positive in four folds but does not show stable holding-time
improvement. Its strongest total year, 2021, also has `14.956479%` drawdown and
a `3632.1`-hour P90 hold.

## Independent one-slot overlay

Validation one-slot total is `16.22719511 USDT`, with `43` blocked entries,
`19.031304%` drawdown, `745.5`-hour median hold, and `2667.4`-hour P90 hold.
The small slot therefore increases opportunity cost and does not rescue the
candidate.

## July 2026 post-hoc price diagnostic

July produces one V3 entry and no exit before the month cutoff:

- signal: 2026-07-28 05:00 UTC;
- next-open fill: 2026-07-28 06:00 UTC;
- effective buy: `63,485.72700`;
- prior-20-day low / high: `61,548.40 / 66,955.00`;
- lower third / midpoint / frozen upper third:
  `63,350.60 / 64,251.70 / 65,152.80`;
- signal range position: `35.25%`;
- month-end unrealized PnL: `-0.35522747 USDT`.

The buy location matches the intended `63k` area. The lot did not close at or
above `65,152.80` before the cutoff, so the causal sell path correctly remained
inactive. This diagnostic cannot override the historical failure.

## Interpretation

The study separates two components cleanly:

- useful: flat-regime lower-range entries reduce ending unrealized loss and
  keep drawdown low;
- rejected: requiring the frozen upper third before any exit eligibility
  delays recycling for weeks or months.

It would be incorrect to tune `one third` downward inside this study. A future
research phase should first perform a read-only lot-level opportunity study of
time to net-positive, midpoint, and upper-third reach. Only after freezing a
new economic qualification from that diagnostic should another candidate be
run. V3 itself remains `NO_CANDIDATE`.

## Operational boundary

No Production runtime, configuration, database, DRA V1, position `263`, owner
`509`, Grid/OCO, funds, schedules, orders, deployment, or external write was
changed.
