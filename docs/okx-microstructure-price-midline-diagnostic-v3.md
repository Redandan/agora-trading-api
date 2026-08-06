# OKX Price-Midline Microstructure Forward Diagnostic V3

## Decision

V3 corrects V2 before any market outcome was accessed. The confirmed `1.5x`
definition is not current total volume relative to a rolling volume baseline.
It is the quote-notional ratio of active buys above the contemporaneous price
midline to active sells below that midline.

V2 is retained as superseded research history and must not be used for evidence.
V3 remains forward-only mechanism discovery, not a strategy, candidate, OOS
result, order instruction, or SHADOW/PAPER/LIVE authorization.

## Point-in-time price midline

For each OKX `BTC-USDT` public trade, use the most recent `books5` snapshot whose
exchange timestamp is at or before the trade timestamp:

```text
price_midline = (best_bid_price_1 + best_ask_price_1) / 2
```

The numerator is quote notional from taker-side buys whose trade price is above
that reference midline. The denominator is quote notional from taker-side sells
whose trade price is below it. All other referenced trade notional is retained
as `midline_other_quote_notional`, so the three buckets must reconcile exactly
to total quote notional.

A trade without an earlier books5 reference is an integrity anomaly. A crossed
book is also an integrity anomaly. Either condition rejects the full day. Using
a minute high/low midpoint or a later book update is prohibited lookahead.

## Frozen event tiers

The denominator must be positive. A zero denominator is `NO_EVENT`, not an
infinite ratio.

1. `MIDLINE_RATIO_1_5_ONLY`: above-mid buy quote notional divided by below-mid
   sell quote notional is at least `1.50`.
2. `MIDLINE_RATIO_1_5_PLUS_NET_TAKER_BUY`: tier 1 plus positive total net taker
   quote notional.
3. `MIDLINE_RATIO_1_5_PLUS_NET_TAKER_BUY_PLUS_BOOK_SUPPORT`: tier 2 plus positive
   average top-five book imbalance and positive bid-replenishment proxy.

Every tier keeps an independent 60-minute cooldown. The signal is evaluated
only after the minute closes, and the entry reference remains the next complete
minute's trade open. Responses remain fixed at 5, 15, 60, 240, and 1,440
minutes; fees and slippage remain excluded because this is diagnostic, not PnL.

## Evidence boundary

Exactly fourteen contiguous, sealed, complete UTC days are required. Every
minute must contain trades, books5 observations, complete point-in-time midline
references, and exact notional reconciliation. Interpretation still requires
at least 30 events per tier, at least 10 in each seven-day half, and at least
80% strictly earlier same-minute-of-day matched controls.

Run the offline analyzer only after those fourteen V3 day files exist:

```text
python -m research_pipeline.microstructure_diagnostic \
  --input 2026-08-01.json ... --input 2026-08-14.json \
  --output price-midline-diagnostic-v3.json
```

The analyzer independently verifies the V3 field contract, minute continuity,
notional reconciliation, integrity counters, and day seals. Because the current
day contract contains aggregates rather than replayable raw WebSocket messages,
the producer's per-trade book-reference implementation is still
`MISSING_PROOF` until separately reviewed with deterministic raw-message test
vectors. V3 results remain diagnostic even after that proof exists.

No qualifying continuous V3 producer has been deployed and no V3 day exists.
Therefore recent two-week buy timestamps and predictive value remain
`MISSING_PROOF`. The result cannot be rescued with V1/V2 data, historical
backfill, a zero-denominator event, or a changed threshold.
