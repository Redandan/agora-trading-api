# Strategy 508 Profit Optimization Report - 2026-07-13

## Decision

No strategy is ready for live promotion. One separate price-only candidate is
deployed in evidence-only production `SHADOW`, but has not produced a trade.

- Strategy 508 4H/24H remains `SHADOW`; live order remains disabled.
- Strategy 485 remains `BTC_BASE_DRY_RUN`.
- Existing positions `#260/#261/#262` and their OCO lifecycle remain unchanged.
- `BTC_DONCHIAN_20D_10D_V1` passed its frozen local historical gate and its
  evidence-only runtime is in production `SHADOW`; it has no live implementation.
- This work does not authorize a deploy, environment change, backfill, database write, order, OCO change, Telegram send, Grid, fund, or Earn action.

## Production Baseline

The read-only production snapshot collected on 2026-07-13 showed:

| Evidence | Result |
| --- | --- |
| Strategy 508 finalized historical events | 8 |
| Average modeled net return | `-0.5585%` |
| Median modeled net return | `-0.6147%` |
| Profit factor | `0.477` |
| Positive temporal folds | `2/5` under the old equal-sample split |
| Historical verdict | `REJECTED_NO_LIVE_NO_MORE_PARAMETER_TUNING` |
| Forward shadow | 1 entry, 0 finalized, 1 hard-gate block |
| Versioned live lane | 0 orders, 0 attempts, 0 open positions |
| Strategy 508 exact fee coverage | `0%` |
| Open legacy 508 positions | 3, all with active OCO |

The deployed freshness fix at `6e369d0` is loaded into the current JVM. The
2026-07-13 08:00 UTC natural `BTCUSDT/4h/okx` close completed the query-first
feature-selection audit:

| Natural event evidence | Result |
| --- | --- |
| Decision audit | `#77421`, `STRATEGY_508_TIME_EXIT`, raw BUY candidate |
| Bar / decision reference | 04:00 / 08:00 UTC, `BAR_CLOSE` |
| Funding provenance | OKX public funding, `ROW_METADATA`, age 59m, `FRESH` |
| OI provenance | derived OKX public swap path, `ROW_METADATA`, age 58m, `FRESH` |
| Price/volume provenance | closed OKX 4H bars, `FRESH_CLOSED_BAR` |
| Freshness result | clear, no freshness blockers |
| Shadow result | hard blocked by existing global and same-symbol exposure |
| Side effects | `orderSent=false`; positions stayed `#260/#261/#262`; 3/3 OCO active; no strategy TG |

This proves the deployed runtime used fresh causal provider metadata for the
natural event. It does not prove a profitable edge; the candidate was not
eligible for execution because the global position cap was already full.

## Confirmed Tooling Defects

The former report could become green without proving the same executable policy:

1. Historical analysis did not enforce the fixed one-order-per-UTC-day limit.
2. Entry at the exact prior exit minute could re-enter despite scheduler ordering being unprovable.
3. The 72H benchmark reused the cohort selected by the 24H result, creating outcome-dependent sample selection.
4. Five equal-sized event slices were labelled walk-forward even when all events occurred in the latest 90 days.
5. Ambiguous, missing-entry, missing-exit, and insufficient-coverage events had no aggregate attrition gate.
6. Forward readiness counted rows without a canonical event key, explicit `SHADOW` mode, complete fee evidence, or valid JSON.
7. The generic strategy 508 PnL tool could silently hide legacy positions by
   filtering them out as non-policy rows.
8. Fee presence alone was treated as exact even without proof that all fills were aggregated, fee signs were preserved, and gross minus fees matched net PnL.
9. A missing canonical minute could be replaced by an off-grid timestamp while still reporting 100% coverage; OHLC invariants were not checked.
10. Historical and forward samples were not bound to the same effective strategy configuration.
11. Old timing gaps outside the rolling evaluation window could block readiness for up to 365 days.
12. Missing or stale mark prices could be converted into numeric open PnL estimates.
13. Forward outcome replay silently discarded invalid/off-grid rows, so a complete canonical lattice plus an extra rejected row could still be labelled exact.
14. Pending evidence could claim a finalized context without failing the forward gate.
15. Synthetic all-fill fields could contradict the producer capability flag and still permit an exact PnL claim.

## Local Corrections

The current uncommitted local change makes the analysis fail closed:

- Historical admission is one open position maximum plus one order per UTC day.
- Same-minute exit and re-entry is rejected.
- The 72H benchmark has an independent admission and holding cohort.
- Temporal validation uses five fixed, non-overlapping calendar folds with no refit claim.
- A promotion sample needs at least 99% finalized mature outcomes, 100% canonical UTC minute coverage for every finalized event, valid OHLC invariants, and at least 270 calendar days of span.
- Every outcome is reconciled; unresolved events receive a conservative worst-case loss bound.
- Forward readiness uses unique canonical shadow event keys bound to the same effective policy/config hash and rejects duplicates, config drift, malformed JSON, missing fee/net evidence, and non-shadow execution rows.
- Raw minute input must contain zero rejected rows and zero duplicate canonical timestamps; sanitation can no longer turn contaminated source input into exact evidence.
- Every canonical row used to establish observation age must reconcile its row outcome with context `outcome/finalized` state.
- Timing gaps are evaluated over the rolling 30-day window plus the 24H position-state seed lookback, not unrelated legacy rows.
- A first live probe would need positive exact net PnL and no partial-fill incident before another probe could proceed.
- Generic strategy PnL always lists every matching auto-traded position. Each
  row separately identifies whether it belongs to the exact versioned policy;
  mixed or legacy cohorts cannot claim exact versioned-policy profit.
- Exact PnL requires explicit all-fill aggregation, preserved fee signs, and gross/fee/net parity. Missing evidence remains unknown.
- Missing or stale mark prices keep open gross/net PnL null instead of fabricating a zero-price estimate.
- The current live producer does not yet provide an immutable all-fill signed-fee ledger. Readiness now exposes `LIVE_EXACT_FILL_PROVENANCE_NOT_IMPLEMENTED` and cannot become live-ready until a separately authorized execution-evidence implementation closes that gap.
- Raw strategy 508 signals now mature in `RAW_SIGNAL_COUNTERFACTUAL` even when a
  hard gate blocks execution. Only explicitly configuration-bound
  `EXECUTABLE_SHADOW` rows count toward promotion; legacy unbound clear rows fail
  closed.

Targeted acceptance covers 98 Java tests plus the strategy 508 and price-only
PowerShell smokes.

## Validation

- Targeted strategy 508 acceptance: `98/98` Java tests passed.
- Full repository verification: `391/391` Java tests passed and
  `scripts/verify_local.ps1` completed with `[verify] OK`.
- Startup smoke: local health passed on port 18084; MCP parity reported 326
  tools, 47 required tools, and zero missing tools.
- Price-only fixture, tamper, deterministic replay, and independent report
  verification all passed.
- Production acceptance was read-only. No order, OCO change, position change,
  Telegram notification, environment mutation, backfill, or database write was
  performed.

## Data Boundary

Free candle data is not the main blocker:

- OKX spot 4H candles for 425 days and 1m candles for 365 days can be downloaded locally without writing production DB state.
- A local dataset must freeze `asOfUtc`, source commit, strategy config hash, raw page hashes, timestamp lattice, duplicates, OHLC invariants, and confirmed-bar status.
- Binance Vision can be used only as a separately labelled sensitivity dataset, not as OKX parity evidence.

Canonical feature replay is blocked because old causal funding and OI observations cannot be reconstructed exactly:

- Historical funding settlements are not equivalent to the hourly predicted funding snapshots used live.
- Historical aggregate OI is not equivalent to the provider-specific live OI path and provider-transition rules.
- Rows without provider path, effective capture time, and availability time cannot be promoted to canonical evidence.

Therefore a 365-day candle replay may test price-only alternatives, but it cannot prove exact historical parity for the current OI/funding strategy.

## Alternative Candidates

These candidates were evaluated under the frozen local price-only policy.

| Candidate | Fixed rule | Purpose |
| --- | --- | --- |
| `BTC_WEEKLY_TSMOM_V1` | Weekly BTC/cash allocation from prior weekly momentum; next 1H open | Test a low-turnover regime edge |
| `BTC_DONCHIAN_20D_10D_V1` | Enter above prior 20-day high, exit below prior 10-day low, ATR initial stop, 1% risk | Test breakout persistence without adding dip buys |
| `BTC_VOL_MANAGED_LONG_V1` | Weekly long exposure scaled to 40% annualized realized volatility, capped at 100%, no leverage | Test drawdown reduction while retaining BTC upside |

Final results:

| Candidate | Result |
| --- | --- |
| `BTC_WEEKLY_TSMOM_V1` | Rejected: 10 round trips and 64.60%/67.73% drawdown |
| `BTC_DONCHIAN_20D_10D_V1` | Passed historical gate: +171.89%/+163.25%, PF 4.443/4.170, 15.10% drawdown; SHADOW review only |
| `BTC_VOL_MANAGED_LONG_V1` | Rejected: one continuous round trip and 67.33%/67.65% drawdown |

The Donchian latest isolated fold was negative under both normal and stress
costs. Full evidence and limitations are in
`docs/btc-price-only-profit-research-report-2026-07-13.md`.

Common research rules:

- Immutable signal ledger and next-open execution.
- No deleted signals, hindsight window selection, shorting, or leverage.
- Same-window HODL and unmodified strategy 508 baselines.
- Normal cost: 0.10% fee plus 0.05% adverse slippage per side.
- Stress cost: 0.20% fee plus 0.10% adverse slippage per side and one-bar delay.
- Five isolated fixed-parameter folds with no cross-fold position carry; normal
  at least 4/5 positive and stress at least 3/5 positive.
- Normal maximum drawdown at most 20%, stress maximum drawdown at most 25%, and all-period net return positive.

## Next Gate

1. Design the immutable fill/fee ledger separately; do not infer exact fee provenance from current `TradeResult` fields.
2. Commit only after review; deployment requires a new explicit authorization.
3. Continue collecting Donchian production SHADOW evidence; the current forward
   sample has zero entries and cannot establish an edge. Do not infer strategy
   508 parity from price-only candles.
4. Do not revisit live sizing until forward SHADOW evidence passes.

Current conclusion: Donchian has a reproducible historical price-only candidate
edge and an evidence-only SHADOW runtime, but there is still no proven
production/live edge. The useful progress is bounded forward collection plus
removal of false-positive readiness paths.
