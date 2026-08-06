# OKX Microstructure Forward Diagnostic V2

> **Disposition: `SUPERSEDED_BEFORE_EVIDENCE`.** The user clarified that
> `1.5x` means active buy quote notional above the contemporaneous price midline
> divided by active sell quote notional below it. V2 incorrectly defined it as
> total minute volume relative to a rolling baseline. No V2 market outcome was
> accessed. Use the versioned V3 contract; retain this file only as audit history.

## Decision

V2 corrects the daily-candle proxy mismatch by defining a minute-level,
forward-only diagnostic over public OKX `BTC-USDT` `trades` and `books5` data.
It does not modify or supersede the uncommitted V1 bounded-capture slice.

This is mechanism discovery, not a trading strategy. It cannot produce a
candidate, OOS result, order, SHADOW/PAPER/LIVE action, or canonical 90-day
evidence mutation.

## Economic thesis

A raw 1.5x minute-volume impulse may include indiscriminate selling or transient
noise. Requiring positive taker-buy flow should remove sell-driven spikes, and
requiring positive top-five book imbalance plus bid-depth replenishment should
further isolate minutes where aggressive buying is supported by resting demand.

The expected benefit is higher median forward return and smaller adverse
excursion relative to the raw-volume tier. This is unproved. The opportunity
cost is at least fourteen future complete UTC days plus the engineering and
storage needed for uninterrupted collection.

## Frozen tiers

The feature denominator is the median total quote notional of the prior 20
complete minutes. The signal minute itself is never in its lookback.

1. `VOLUME_1_5_ONLY`: ratio at least `1.50`.
2. `VOLUME_1_5_PLUS_TAKER_BUY`: tier 1 and net taker quote notional above zero.
3. `VOLUME_1_5_PLUS_TAKER_BUY_PLUS_BOOK_SUPPORT`: tier 2, average top-five
   book imbalance above zero, and positive bid-replenishment proxy.

Each tier has its own 60-minute signal cooldown. This limits burst duplication;
the output must still disclose overlapping forward-label windows.

## Causal timing and responses

A signal is evaluated only after its UTC minute is complete. The entry
reference is the first trade price of the next complete minute. Using any
signal-minute price as the entry is a leakage rejection.

The diagnostic measures 5-, 15-, 60-, 240-, and 1,440-minute close return,
maximum favorable excursion, and maximum adverse excursion. Fees and slippage
are deliberately absent because this stage tests direction and path, not PnL.

Each event is matched to the closest unused strictly earlier non-event minute
with the same UTC minute-of-day and a complete 1,440-minute response. This is a
time-of-day control, not a complete volatility/regime control; that limitation
must remain visible.

## Readiness and stop rules

Input requires exactly fourteen contiguous, sealed, complete UTC-day bundles
matching `okx-microstructure-forward-day.v2.schema.json`. Every day contains
1,440 minutes, both streams in every minute, a clean integrity summary, and no
historical backfill.

Interpretation readiness requires, for every tier:

- at least 30 labeled events;
- at least 10 events in each seven-day half; and
- at least 80% matched-control coverage.

Failure returns `INSUFFICIENT_FORWARD_EVIDENCE`; it does not authorize a lower
threshold, a different lookback, a longer history import, or a performance
claim. Passing returns only `FORWARD_DIAGNOSTIC_READY_FOR_INTERPRETATION`.

## Offline execution

After fourteen valid day files exist, run the analyzer manually with one
`--input` argument per day in chronological order and a new `--output` path:

```text
python -m research_pipeline.microstructure_diagnostic \
  --input 2026-08-01.json ... --input 2026-08-14.json \
  --output diagnostic-v2.json
```

The analyzer independently validates exact fields, complete minute sequence,
trade-notional identities, price and timestamp bounds, zero anomalies, and each
payload seal. It refuses to overwrite an existing result. Any invalid day
returns `DATA_REJECT`; insufficient sample or control coverage returns
`INSUFFICIENT_FORWARD_EVIDENCE`.

## Single-clock and deployment boundary

The sole V6 cloud Ops schedule and canonical candle evidence chain remain
unchanged. This offline analyzer introduces no timer, writer, source daemon,
deployment, or network collection. A future uninterrupted producer requires a
separate versioned source contract and explicit deployment authorization.

Until that producer is reviewed and operated for fourteen future days, recent
two-week buy timestamps remain `MISSING_PROOF`; the prior daily-candle proxy is
not a substitute for this minute-level test.
