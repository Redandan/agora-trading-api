# BTC DRA Independent-Lot Conditional Partial De-Risk V3 Result

Date: 2026-08-02

Research identity:
`BTC_DRA_INDEPENDENT_LOT_CONDITIONAL_PARTIAL_DE_RISK_V3_RESEARCH`

Decision:

```text
DATA_QUALITY_PASS
BASELINE_PARITY_PASS_THROUGH_HYBRID_PROFIT_LOCK_V2
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

Conditional partial realization solved the prior full-lot profit problem, but
not holding time. All three candidates beat V1 Validation realized/total,
retained at least `90%` V2A total, passed drawdown and accounting, and beat
annual V1 total in `4/5` folds. None improved annual median holding in any fold.

The best-profit candidate, 7-day momentum acceleration, produced realized
`117.52474516`, total `114.31654395`, and drawdown `7.506541%`, but its
cost-weighted `338h / 1,608h` median/P90 failed the frozen holding gates.

## Frozen architecture

Every DRA buy remained unchanged and independent. Entry-day continuation
quality selected either a full `30 USDT` V2A path or a partial-eligible path.
After peak full-lot net PnL reached `1R`, a close below causal hourly EMA5 and
strictly positive tranche PnL queued `20 USDT` for next-open sale. The residual
`10 USDT` quantity was rebased and followed V2A `1.50 ATR`.

The three preregistered labels were 7-day momentum acceleration, daily range
expansion, and their union. There was no ratio, ATR, EMA, or threshold scan.

## Validation frontier

Amounts are USDT; holding is allocated-cost weighted.

| Candidate | Realized | Unrealized | Total | DD | Median | P90 | Full V2A / partial fills | Annual total / hold wins |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 7D acceleration | `117.52474516` | `-3.20820121` | `114.31654395` | `7.506541%` | `338h` | `1,608h` | `28 / 22` | `4/5 / 0/5` |
| Range expansion | `109.37022098` | `-3.20820121` | `106.16201977` | `7.734178%` | `338h` | `1,471.2h` | `27 / 24` | `4/5 / 0/5` |
| Acceleration OR range | `116.97808010` | `-3.20820121` | `113.76987889` | `8.392661%` | `363h` | `1,608h` | `37 / 14` | `4/5 / 0/5` |

V1 was `89.41118307 / -3.20820121 / 86.20298186`, DD `7.121498%`,
median/P90 `182.5h / 1,418.3h`. V2A total was `113.25094608`, so the frozen
`90%` threshold was `101.92585147`.

All candidates had `51` buys, one ending lot, zero blocked entries, zero
deferred fills, exact quantity/cost conservation, one partial at most per lot,
and strictly net-positive fills. Their `20 USDT` partial-fill medians were
approximately `143-147h`; the aggregate median remained slow because too much
capital stayed full V2A.

## Design and annual stability

| Candidate | Design realized | Design unrealized | Design total | DD | Median / P90 | Buys / sells / open / blocked |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 7D acceleration | `263.78918296` | `-101.42144167` | `162.36774129` | `28.120920%` | `329h / 1,646h` | `99 / 131 / 6 / 6` |
| Range expansion | `225.11230580` | `-101.42144167` | `123.69086413` | `29.063235%` | `311h / 1,646h` | `99 / 148 / 6 / 5` |
| Acceleration OR range | `263.37040382` | `-101.42144167` | `161.94896215` | `28.141386%` | `340h / 1,646h` | `99 / 118 / 6 / 6` |

The 7-day acceleration annual totals were `93.37060288`, `55.10384644`,
`-25.60145024`, `50.16638378`, and `62.65438781`; annual medians were
`329h`, `346h`, `311h`, `370.5h`, and `286h`. The other two candidates had
the same `4/5` total and `0/5` holding-win pattern. The holding failure was
therefore regime-wide, not an aggregate-window artifact.

## Data, checkpoints, and reproducibility

- rows: `52,608`, first open `2019-01-01T00:00:00`, last close
  `2025-01-01T00:00:00`;
- input SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- specification SHA-256:
  `b5276fc2666a56fb0c26cc79880fa8681684857751c7c3da7977b4f2a32a8c5f`;
- runner SHA-256:
  `2945aa711b2067cc6acff50cc04aa596fb784011b54abdd235c24e0044c19397`.

An initial pre-performance structural run rejected the first 2019 signal
because the dataset had not yet accumulated 14 complete days. Before any
candidate metric existed, the specification froze the conservative treatment
already implicit in prior studies: that single hard-inception lot remains a
full V2A lot and can never partially sell. Validation and all annual folds had
complete inputs.

Two accepted runs produced byte-identical `3,228,907`-byte JSON files with
SHA-256:
`a33662887fe4dec009cf7b454e1640d95641f7be8648e3c16d05df03d948f512`.

## OOS and Production boundary

No candidate passed holding gates, so OOS rejected before any 2025+ data
access. The seal artifact SHA-256 is
`886795eaf55e7ce7daa77ec63fdecec7717b46d8c5a617bb0100a9435f0c5b52`.
No one-slot overlay ran. No runtime, configuration, database, DRA V1,
position, owner, Grid/OCO, funds, schedules, Telegram, order, commit, or
deployment changed.

## Reproducible artifacts

- specification:
  `docs/btc-dra-independent-lot-conditional-partial-de-risk-v3-research.md`;
- runner:
  `research/btc_dra_independent_lot_conditional_partial_de_risk_v3.py`;
- accepted JSON runs:
  `btc-dra-conditional-partial-v3-preselection-2026-08-02-run1b.json` and
  `btc-dra-conditional-partial-v3-preselection-2026-08-02-run2.json` in the
  task visualization directory;
- OOS seal:
  `btc-dra-conditional-partial-v3-oos-seal-guard-2026-08-02.json` in the same
  directory.
