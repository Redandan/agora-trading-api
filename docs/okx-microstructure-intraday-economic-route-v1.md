# OKX Microstructure Intraday Economic Route V1

Status: `PREOUTCOME_TEMPLATE_NOT_INSTANTIATED`

This document explains the frozen research-only template in
`research_pipeline/okx-microstructure-intraday-economic-route-contract.v1.json`.
It does not instantiate a tier or date, implement a collector, source extension,
adapter, runner, ledger, strategy, hypothesis, manifest, candidate, state
transition, or authorize economic execution, OOS access, or Trading.

## Mechanical tier binding

A later contract instance may proceed only from a validated interpretation with
disposition `READY_FOR_ONE_HYPOTHESIS_DESIGN`. It must copy the interpretation's
first passing tier in the frozen simplest-first order:

1. `MIDLINE_RATIO_1_5_ONLY`
2. `MIDLINE_RATIO_1_5_PLUS_NET_TAKER_BUY`
3. `MIDLINE_RATIO_1_5_PLUS_NET_TAKER_BUY_PLUS_BOOK_SUPPORT`

The caller cannot supply a tier, fall back to another tier, rank observed
magnitudes, change a threshold, or run multiple tier variants. This template
contains no selected tier value.

## Strict causal clock

Signal minute `[m,m+1)` is unavailable until `m+1`. The decision occurs only
after completion, entry uses the `m+2` minute's `trade_open_price`, and exit
uses the `m+62` minute's `trade_open_price`. The 60 complete held minutes are
`m+2` through `m+61`, inclusive.

Crossing a UTC-day boundary is allowed only when every required minute remains
inside the same complete contiguous fold. A signal without its full `m+62`
exit is excluded and reported. If an exit and another entry share a timestamp,
the exit is processed first.

Each candidate and matched-control lane is a separate one-position ledger:
long-only OKX `BTC-USDT` spot, `30.00 USDT` gross entry notional, 60-minute
per-tier cooldown, no leverage, overlap, pyramiding, resizing, stop, target,
scale-in, or scale-out. Every fold must end with zero inventory.

## Frozen friction ledger

The exact research accounting order is:

1. entry price = raw `m+2` open multiplied by `1.0005`;
2. gross base = `30.00 / entry price`;
3. buy fee = `0.0010` of gross base, removed from base;
4. exit price = raw `m+62` open multiplied by `0.9995`;
5. gross exit quote = net base multiplied by exit price;
6. exit fee = `0.0010` of gross exit quote;
7. net PnL = net exit quote minus `30.00`.

This is 15 planning basis points per side and 30 basis points round trip before
market movement. Controls use the identical formulas. Costs cannot change after
outcome access.

## Required comparators

Cash at the same `30.00 USDT` capital is the absolute benchmark, but cash alone
cannot establish independent alpha. Every paired economic comparison also needs
a non-event control at the same UTC minute of day, selected from the closest
unused strictly earlier day in the same fold. The non-event condition remains
below-mid sell quote notional greater than zero and the fixed midline ratio less
than `1.50`.

Controls are unique, never cross folds, and use the identical `m+2`/`m+62`
clock, notional, friction, and separate one-position accounting. Unmatched
events are excluded from both sides of the paired ledger and reported, so the
paired candidate and control trade counts are equal. At least 80% of otherwise
eligible selected-tier events must be matched.

## Untouched future stages

A later, separately frozen instance must supply exact dates for three consecutive
untouched stages after positive interpretation, contract and manifest freeze,
and proven source readiness:

| Stage | Complete UTC days | Role |
| --- | ---: | --- |
| Design | 14 | First independent economic test |
| Validation | 14 | Independent confirmation |
| Sealed OOS | 14 | Single consumption after Design and Validation pass |

The total is 42 future complete UTC days. Discovery bytes are excluded from all
three stages. Backfill, boundary extension, cross-fold labels, and cross-fold
controls are forbidden. The template does not open OOS and contains no dates or
outcomes.

## Fail-closed gates

Every fold independently requires 14 contiguous `CLEAN` days, 1,440 valid
minutes per day, zero integrity anomalies, complete `m+62` exits, at least 30
selected-tier trades, at least 10 in each seven-day half, at least 80% matched
coverage, no duplicate controls, no cross-fold label, and zero terminal
inventory. Failure closes without extension or repair.

Design, Validation, and OOS independently apply the same economic gates:

- candidate net total PnL is positive versus cash;
- candidate total PnL exceeds matched control;
- median candidate net return is positive;
- positive candidate net-trade share exceeds 50%;
- candidate maximum drawdown is no worse than control;
- candidate-minus-control PnL is positive in both seven-day halves;
- the largest positive incremental trade contributes no more than 40%; and
- terminal inventory is zero.

Design or Validation failure is `NO_CANDIDATE`. OOS is consumable once only
after both pass and the single route is frozen; OOS failure is `OOS_FAIL` and a
pass ends at `REPORTED_NOT_ACTIVATED`. No threshold, fold, cost, hold, tier, or
comparator may change after outcome access.

## Evidence and opportunity-cost boundary

This contract has zero immediate PnL and drawdown effect. Forty-two future days
may be faster than the sparse DRA conjunction, but future selected-tier cadence,
economic edge after 30 basis points, matched-control attrition, PnL, drawdown,
half stability, concentration, liquidity, capacity, source readiness, adapter
parity, OOS value, and activation remain `MISSING_PROOF`.

The explicit opportunity costs are turnover and friction, adverse selection
from the decision buffer, false-positive trades, short-horizon concentration,
unknown event cadence, control attrition, and the source/adapter infrastructure
needed before any lawful evaluation.
