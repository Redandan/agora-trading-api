# CFTC TFF DRA Historical Entry-Admission V1

This is one bounded historical-development screen. It asks whether the weekly
change in CME Bitcoin Leveraged Money net percentage of open interest can
improve the existing BTC DRA V1 entry path. It does not create a Trading
strategy, open OOS, or authorize SHADOW, PAPER, or LIVE use.

## Frozen question

For each eligible CFTC report, compute:

`(current long % OI - current short % OI) - (prior long % OI - prior short % OI)`

The unchanged DRA parent signal is admitted only when that delta is strictly
positive. Negative and zero values hold cash. Inversion, thresholds, ranking,
normalization, smoothing, lag variants, and post-result tuning are forbidden.

## Historical availability boundary

- Source data is the official annual CFTC TFF Futures Only archive for
  2019-2024. Each archive and its single text member are bound by byte size and
  SHA-256 before execution.
- A report is considered eligible only at 00:00 UTC fourteen calendar days
  after its report date. This deliberately avoids inventing historical release
  timestamps that CFTC does not publish as a complete list.
- Non-Tuesday rows, missing exact seven-day predecessors, and decisions at or
  after 2025-01-01 are excluded.
- Each eligible factor is valid for exactly 168 hours. If the next weekly
  factor is missing or excluded, the prior value expires and the candidate
  holds cash instead of carrying stale evidence forward.
- Report dates 2023-01-31 through 2023-03-14 are excluded before outcomes are
  read because the CFTC ION incident delayed their publication. A row cannot
  use an excluded row as its predecessor.
- 2019 supplies predecessor and warm-up context only. Design is 2020-2022;
  Validation is 2023-2024. A 168-hour predictive episode crossing a window end
  is excluded.

## Direct economic comparison

Parent and candidate use identical 250 USDT initial equity, 240 USDT whole-lot
capacity, fee, slippage, entry/exit logic, and hourly corpus. The fixed gates
require:

- total PnL improvement in Design and Validation;
- Validation realized and total PnL improvement, unrealized PnL non-worse, and
  at least half of the positive total delta attributable to realized PnL;
- Validation drawdown within +0.25 percentage points, underwater duration,
  terminal inventory count, median hold, and P90 hold non-worse;
- at least 8 Design and 4 Validation interventions;
- total PnL wins in at least 3/5 annual folds, drawdown non-worse in at least
  4/5, and no more than 60% of positive annual delta from one year.

Both Design and Validation must also independently pass the 168-hour
directional gates: at least 26 episodes, at least 8 observations of each sign,
chronological quartile and month breadth, median signed response above zero,
the expected raw-return sign for both factor signs, one-sided sign-test
`p <= 0.10`, and no single positive episode contributing more than 25%.

Every gate must pass. Failure produces
`NO_CANDIDATE_CLOSE_CFTC_TFF_FACTOR_FAMILY`; the family is then tombstoned
without tuning. Passing produces only
`DESIGN_VALIDATION_PASS_READY_FOR_ONE_CANDIDATE`; it permits registration of at
most one independent sealed OOS candidate and has no activation effect.

## Execution order

1. Build and synthetic-test the runner without reading CFTC factor values or
   BTC outcomes.
2. Freeze the canonical manifest, archive seals, exclusions, code bindings,
   economics, windows, and gates in Git.
3. Copy the already downloaded official archives into the manifest-bound
   `.research-state` input paths.
4. Execute the deterministic screen once and seal its output. Never overwrite
   the output or reopen a failed family.
