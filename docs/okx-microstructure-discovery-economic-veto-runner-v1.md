# OKX microstructure discovery economic-veto runner V1

## Status and boundary

This is an offline, pre-outcome Local Research capability. It is not a strategy,
candidate, V4 source, manifest, adapter, deployment, registration, or
SHADOW/PAPER/LIVE path. The runner may emit only `VETO_BEFORE_V4` or
`PERMIT_LATER_V4`; neither disposition proves alpha or authorizes activation.

The production runner is zero-argument and binds these fixed roots:

- V3 handoff package:
  `C:/Users/Redan/.codex/local-research-node/inbox/local-node-microstructure-v3-evidence-diagnostic-v1`
- positive V1 interpretation:
  `C:/Users/Redan/.codex/local-research-node/outbox/local-node-microstructure-v3-interpretation-runner-v2/interpretation-result.json`
- create-once result:
  `C:/Users/Redan/.codex/local-research-node/outbox/local-node-microstructure-discovery-economic-veto-runner-v1/economic-veto-result.json`

No caller path, environment path, tier, route, threshold, fallback, or variant
is accepted. Repository, handoff, interpretation, and output roots must remain
distinct regular non-link, non-reparse locations. A partial output, extra file,
changed source, or conflicting existing result blocks execution. An identical
sealed result is idempotent.

## Frozen evidence flow

The runner validates the exact create-only handoff manifest, canonical intake
state, fourteen raw V3 day bundles and envelopes, diagnostic result, and one
hash-bound interpretation with disposition
`READY_FOR_ONE_HYPOTHESIS_DESIGN`. It mechanically selects the first passing
tier recorded by that interpretation. Caller overrides, magnitude ranking,
fallback tiers, multiple variants, and tuning are forbidden.

All fourteen UTC days must be contiguous and `CLEAN`, with exactly 1,440 valid
minutes per day. Every raw document hash, payload seal, predecessor binding,
and cumulative chain binding is revalidated before the pure evaluator sees the
minute records. The runner snapshots task, runtime, repository source, handoff,
and interpretation bytes before and after evaluation and publication.

## Causal and economic reconstruction

For a signal bucket `[m,m+1)`, the earliest decision is after the bucket is
complete at `m+1`. Entry is the `trade_open_price` at `m+2`; the position holds
the sixty complete minutes `m+2` through `m+61`; exit is the open at `m+62`.
At an equal timestamp, exit is processed before entry. Signals without a full
`m+62` exit are excluded and reported. Discovery response fill fields are not
used.

Candidate and control ledgers are separate, long-only BTC-USDT spot ledgers:

- gross entry is exactly 30 USDT;
- at most one position is open in each lane;
- per-tier cooldown is exactly sixty minutes;
- overlap, pyramiding, resizing, stops, targets, and scaling are forbidden;
- terminal inventory must be zero.

Controls require positive below-mid sell notional and a midline buy/sell ratio
strictly below 1.50. Each control uses the same UTC minute on the closest
unused strictly earlier day in the same seven-day fold. It uses the identical
clock, notional, and costs. Unmatched candidates are excluded from the paired
economic ledger; no fallback or cross-fold label is permitted.

Costs use high-precision decimal arithmetic. Entry execution is raw entry
multiplied by 1.0005; the buy fee is 0.0010 of gross base. Exit execution is raw
exit multiplied by 0.9995; the sell fee is 0.0010 of gross quote. Net PnL is net
exit quote less 30 USDT. The same calculation and deterministic decimal
serialization apply to both lanes.

## Gates and interpretation

The result recomputes counts, fold breadth, matched-control coverage,
duplicate/cross-fold/anomaly/exclusion counts, terminal inventory, net totals,
median candidate return, positive-trade share, maximum drawdown, both half
deltas, and top-one positive incremental concentration.

`PERMIT_LATER_V4` requires every frozen integrity and economic gate, including
at least thirty selected-tier trades, at least ten in each half, at least 80%
control coverage, zero integrity defects and terminal inventory, positive
candidate and incremental totals, positive median, positive share above 50%,
candidate drawdown no worse than control, positive incremental PnL in both
halves, and top-one concentration at most 40%. Any failed gate produces
`VETO_BEFORE_V4` without tuning.

False-negative rate across regimes, generalization, a future V4 source and
manifest, Design/Validation or OOS value, strategy PnL, drawdown, capital use,
capacity, candidate readiness, and activation all remain `MISSING_PROOF`.

## Validation boundary

Tests use only synthetic in-memory records and temporary directories. They
cover all three frozen tiers, positive and veto outcomes, m+2/m+62 lookahead
boundaries, exit-before-entry ordering, incomplete exits, cooldown and
one-position behavior, control uniqueness and attrition, friction, drawdown,
breadth, concentration, source drift, output conflict, exact inventory, and
idempotent create-once publication. Production fixed roots are never opened by
the test suite.
