# BTC DRA V7 Monotonic-Promotion Post-Peak Trend Exit V9 Result

Research identity:
`BTC_DRA_V7_MONOTONIC_PROMOTION_POST_PEAK_TREND_EXIT_V9_RESEARCH`

Final status: `NO_CANDIDATE_KEEP_V7_AND_DRA_V1`.

## Executive conclusion

The V9 architecture was implemented as preregistered and preserved V7's
monotonic first-`1R` promotion exactly. All three causal post-peak managers
substantially reduced holding time and drawdown, but none beat the frozen V7
parent economically. They interpreted ordinary BTC trend corrections as
terminal deterioration and exited runners that later resumed their advance.

The best V9 Validation result was
`POST_1R_PEAK_GIVEBACK_1R`, with `59.17439047 USDT` total PnL versus
`95.38625667 USDT` for V7. Its median holding time improved from `192h` to
`140h`, but its realized PnL fell from `96.02789691 USDT` to
`59.81603071 USDT`. This is not a competitive replacement for V7.

No candidate was frozen. The `2025+` OOS segment and independent one-slot
overlay remain sealed. No threshold was relaxed and no post-result parameter
scan was performed.

## Frozen research boundary

- Server-local OKX `BTCUSDT` complete causal `1h` bars only.
- Selection cutoff: `2025-01-01T00:00:00Z`.
- Design: 2019-2022; Validation: 2023-2024.
- Annual fair-reset folds: 2020-2024.
- DRA V1 entry, arm, expiry, cooldown, independent-lot accounting, fees,
  slippage, next-open fills, profit-only rule, and no-final-liquidation rule
  remained unchanged.
- V7 routing remained unchanged: every first-`1R` promotion was unconditional;
  trend logic could manage only an already-confirmed full runner.
- The research did not authorize or modify `SHADOW`, `PAPER`, `LIVE`, runtime,
  configuration, DRA V1, position `263`, owner `509`, Grid/OCO, funds,
  schedules, database state, Telegram, or orders.

## Exact baseline reproduction

The research runner reproduced the frozen V1, V2A, and V7 checkpoints before
evaluating V9 candidates.

| Validation strategy | Realized USDT | Unrealized USDT | Total USDT | Max DD % | Median hold h | P90 hold h |
|---|---:|---:|---:|---:|---:|---:|
| DRA V1 | 89.41118307 | -3.20820121 | 86.20298186 | 7.121498 | 182.5 | 1418.3 |
| V2A 1.50 ATR | 116.45914729 | -3.20820121 | 113.25094608 | 8.945793 | 401.0 | 1846.6 |
| V7 frozen parent | 96.02789691 | -0.64164024 | 95.38625667 | 6.832349 | 192.0 | 836.0 |

V7 Design total was `94.90277533 USDT`.

## Candidate results

| Candidate | Design total USDT | Validation realized USDT | Validation total USDT | Max DD % | Median h | P90 h | Manager fills Design / Validation | Annual total wins | Annual median wins | Result |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| `POST_1R_PEAK_GIVEBACK_1R` | 95.51426910 | 59.81603071 | 59.17439047 | 5.189161 | 140 | 643 | 27 / 23 | 2/5 | 5/5 | Reject |
| `POST_1R_EMA5_DOWNTURN` | 92.39842674 | 46.61005729 | 45.96841705 | 4.969448 | 77 | 642 | 32 / 25 | 2/5 | 5/5 | Reject |
| `POST_1R_PEAK_GIVEBACK_1R_AND_EMA5_DOWNTURN` | 95.51426910 | 59.75938875 | 59.11774851 | 5.190149 | 140 | 643 | 27 / 23 | 2/5 | 5/5 | Reject |

All candidates retained V7's `-0.64164024 USDT` Validation ending
unrealized PnL. They passed the mechanical holding-time and drawdown goals,
but failed the more important realized-PnL, total-PnL, harvest-efficiency, and
annual-total stability requirements. The EMA5 candidate also failed the
Design-total requirement.

## Annual fair-reset evidence

| Year | V7 total / median h | Giveback total / median h | EMA5 downturn total / median h | Conjunction total / median h |
|---|---:|---:|---:|---:|
| 2020 | 51.28045134 / 103.5 | 41.03699200 / 76 | 36.12318745 / 53 | 41.03699200 / 76 |
| 2021 | 14.60479908 / 26 | 24.14720617 / 25 | 28.61871785 / 25 | 24.14720617 / 25 |
| 2022 | -2.14051706 / 63 | -1.74198954 / 50 | 2.00046815 / 23 | -1.74198954 / 50 |
| 2023 | 39.87706746 / 302.5 | 26.96416631 / 140 | 19.61930363 / 68 | 26.96416631 / 140 |
| 2024 | 54.01341685 / 190 | 30.99464799 / 140 | 24.19165065 / 92 | 30.99464799 / 140 |

Every candidate reduced the annual median in all five folds, but each beat V7
total PnL in only 2021 and 2022. The losses in the stronger 2020, 2023, and
2024 runner years demonstrate the opportunity cost of treating a local
pullback as a completed trend.

## Lot-level causal diagnosis

Against the same V7 completed lots, the manager-affected realized-PnL deltas
were:

- Giveback: `-36.21186620 USDT`.
- EMA5 downturn: `-49.41783962 USDT`.
- Giveback plus EMA5 downturn: `-36.26850816 USDT`.

Representative giveback exits show where convexity was lost:

| Manager fill date | V9 realized USDT | V7 realized USDT | Delta USDT |
|---|---:|---:|---:|
| 2024-02-10 | 0.90109663 | 10.61314545 | -9.71204882 |
| 2024-01-11 | 2.54165602 | 11.03655183 | -8.49489581 |
| 2023-11-10 | 0.12932388 | 4.19652834 | -4.06720446 |
| 2023-01-07 | 6.22590536 | 10.19610930 | -3.97020394 |
| 2023-10-17 | 5.33709669 | 7.93028309 | -2.59318640 |

The largest EMA5-downturn deltas were `-9.67529879`, `-9.00658631`,
`-8.91365828`, `-6.95948184`, and `-6.28565428 USDT`. A one-hour EMA5
downturn is therefore too sensitive for a post-`1R` BTC runner: it mostly
detects local noise, not a durable terminal reversal.

## Path-invariance and causality audits

All V9 Design, Validation, and annual-fold audits passed:

- buy counts, blocked entries, entry routes, promotion records, and partial
  queue records exactly matched V7;
- all eight V7 promotions remained unconditional;
- no trend condition changed entry, cooldown, position size, route, or quota;
- manager arm occurred only on the first causal full-lot `1R` observation;
- manager queues occurred strictly after the arm bar and only when the frozen
  factor was true;
- unchanged V2A ratchet exits had same-hour precedence;
- every V9 fill remained net positive after frozen fees and slippage;
- quantity, cost basis, and partial-lot accounting passed.

V7 Design itself contains one frozen hard-dataset-inception fallback at
`2019-01-07T00:00:00Z`, because 14 complete prior days do not exist at the
start of the dataset. The first V9 artifact incorrectly classified that
inherited and exactly matched parent condition as a candidate audit failure.
The audit was corrected to require exact parity with V7. Candidate formulas,
trades, fills, and metrics were not changed, and the original artifact was
preserved instead of overwritten.

## Decision

`NO_CANDIDATE_KEEP_V7_AND_DRA_V1`.

This experiment confirms that a trend-plus-strategy design can be made fair
and causal, but these three post-peak factors do not add value. Their apparent
risk improvement comes from surrendering profitable runner exposure. V7
remains the best studied hybrid parent; DRA V1 remains the accepted reference.
The simple post-`1R` giveback/EMA5 branch is closed without loosening the gate.

## Reproducibility

- Data rows: `52,608`.
- Data SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.
- Spec SHA-256:
  `048399d142804a0c52a2d2e3bbe21dfc28938d4bafdd225fae76128dc2f36449`.
- Research runner SHA-256:
  `4d94a636fde25f489407ca31d45fc2ee6553bf9e0610c4888fa8f4d4d95bff9f`.
- Canonical JSON SHA-256:
  `812a631863a1f5ef310361dd672f1bcf0e2a704eb9808ea3ff496564bc32df98`.
- Canonical preselection artifact:
  `btc-dra-v7-monotonic-promotion-post-peak-trend-exit-v9-preselection-2026-08-03-run2-fixed.json`.
- `2025+` OOS opened: `false`.
- One-slot overlay opened: `false`.
