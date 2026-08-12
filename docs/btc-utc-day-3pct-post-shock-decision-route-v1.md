# BTC UTC-day 3% post-shock decision route V1

## Decision

This pre-outcome contract prevents result-driven selection between the frozen
post-shock direction and volatility-persistence diagnostics. It is a pure
offline research-question router. It does not run from the heartbeat, inspect
canonical state, create a hypothesis or candidate, open OOS, or map a result to
a Trading strategy.

The fixed priority is:

1. if the terminal signed-H24 diagnostic retains either continuation or
   reversal, route exactly one parent-neutral directional research question;
2. otherwise, if the terminal volatility diagnostic retains persistence,
   route exactly one parent-neutral volatility-risk research question; and
3. if both diagnostics close, close the post-shock family without rescue
   tuning, inverse-rule mining, or combining failed branches.

If both diagnostics retain, the directional route still has priority. The
volatility result may remain context, but the shared discovery evidence does
not test a joint interaction and cannot support a joint-alpha claim. A later
Manager review may freeze at most one bounded research question. It may not
register a candidate or reinterpret the discovery window as OOS.

## Integrity boundary

The router accepts only canonical UTF-8 JSON bytes from terminal directional
V1 or rollover V2 results and one terminal volatility-persistence V1 snapshot.
It reuses both deterministic source validators, requires the same active leaf,
binds exact artifact hashes and terminal clocks, and makes the decision
available only after both inputs are sealed.

Immediate PnL and drawdown effects are `ZERO`. Joint predictive value,
strategy compatibility, fees, slippage, capacity, economic value, candidate
readiness and OOS remain `MISSING_PROOF`.
