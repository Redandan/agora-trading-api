# OKX BTC-USDT Microstructure Forward Source V1

## Purpose

This source collects forward-only public `trades` and `books5` messages for
exploratory research into whether 1.5x volume events accompanied by taker-buy
flow and bid-side absorption identify better short-horizon entries.

It is a data source, not a strategy and not a promotion candidate. It must not
place orders, start Trading runtime components, write a database, mutate the
canonical 90-day candle evidence chain, or activate SHADOW, PAPER, or LIVE.

## Frozen source contract

- Venue and instrument: OKX `BTC-USDT` spot.
- Public WebSocket: `wss://ws.okx.com:8443/ws/v5/public`.
- Channels: `trades` and `books5` only.
- Direction: forward-only. No REST history, replay, or historical backfill.
- Runtime: a bounded standalone Java 21 CLI; it never starts Spring.
- Storage: UTC minute aggregates plus integrity metadata. Raw messages are not
  persisted; their exact arrival order is committed by a rolling SHA-256 chain.
- Sealing: the CLI refuses an existing output path and seals the SHA-256 of
  UTF-8 compact JSON excluding `seal`, with object keys sorted
  lexicographically.

The CLI intentionally accepts no endpoint, symbol, channel, credential,
database, scheduler, or trading argument.

## Minute fields and interpretation

`trades.side` is treated as taker side. The bundle records buy and sell base
quantity, buy and sell quote notional, their net difference, the number of
trade records, and OKX's aggregated match count.

Each `books5` snapshot contributes top-five bid and ask quote depth, spread,
mid-price, and quote-depth imbalance. `bid_replenishment_quote_proxy` is the
sum of positive changes in total top-five bid quote depth. It is only a proxy:
price-level movement can change total top-five depth without a resting bid
being replenished at the traded price.

## Integrity and smoke-tooling gate

The output reports subscription acknowledgements, stream observation, malformed
records, exchange errors, crossed books, timestamp regressions, sequence
regressions, non-increasing trade IDs, source counts, and the raw-message arrival
hash chain.

A bounded capture is always diagnostic under this V1 tooling contract. The
collector calculates a structural full-day flag from exactly 1,440 contiguous
UTC minutes, both streams in every minute, both subscriptions, and zero
integrity anomalies. That calculation is not proof of canonical eligibility.
The strict portable schema and offline validator require
`canonical_evidence_eligible=false`; a collector bundle that claims `true`
fails closed even when its payload seal is internally consistent.

Validate a bundle without initializing research state:

```text
python -m research_pipeline validate-okx-microstructure-bundle <bundle.json>
```

Successful validation returns `VALID_SMOKE_TOOLING_ONLY`. The validator:

- rejects unknown or duplicate JSON keys and malformed field types;
- requires the exact frozen public source, channel, metric, and authorization
  values;
- requires ordered contiguous UTC minutes with both streams present;
- derives acknowledgement, stream, full-day, and integrity consistency;
- rejects every integrity anomaly, listener error, or record-count mismatch;
- independently reconstructs compact UTF-8 JSON without `seal`, recursively
  sorts object keys, and verifies the declared SHA-256; and
- never writes `.research-state`, registers an artifact, opens OOS, or changes
  a strategy state.

Strict local validation proves only deterministic bundle shape and seal
integrity. It does not prove who ran the source, that a full UTC day was
continuously observed, or that the bytes reached the canonical server through
a reviewed one-way path.

## Deliberately absent production capabilities

This V1 does not reconnect, resume across disconnects, run continuously,
schedule itself, rotate daily files, monitor liveness externally, or register
artifacts in the research control plane. Those are deployment capabilities and
require separate authorization and review. Until then, captures remain local,
bounded, exploratory artifacts and cannot satisfy a multi-day forward sample.

## Single-clock and canonical-state boundary

The sole `CLOUD_OPS_SCHEDULE_V6` contract is unchanged. This source adds no
timer, scheduler, local canonical writer, heartbeat operation, or server intake.
Any future canonical use requires a separately reviewed prospective source
identity, continuous event-driven UTC-day producer, immutable one-way
transport, network-denied server intake, capture deadline, and predecessor
chain binding before collection begins. Only the server Research Worker may
write canonical state, and only the existing cloud schedule may advance the
research lifecycle.

Until all of those contracts exist and are verified, source identity,
continuous capture, one-way transport, server intake, predecessor binding, and
performance value remain `MISSING_PROOF`. A smoke bundle must never be used as
alpha evidence, discovery evidence, OOS, or a promotion gate.

## Bounded invocation

Compile the project normally, construct the dependency classpath, and invoke
the class directly (not the repository Maven exec target):

```text
java -cp <target-classes-and-dependencies> \
  com.agora.research.OkxMicrostructureForwardSourceCli \
  --duration-seconds 20 \
  --output <new-sealed-json-path>
```

The permitted duration is 5 through 86,400 seconds. The output path must not
already exist.

## Protocol references

- [OKX API V5 documentation](https://www.okx.com/docs-v5/en/)
- [OKX WebSocket order-book guidance](https://www.okx.com/docs-v5/trick_en/)
