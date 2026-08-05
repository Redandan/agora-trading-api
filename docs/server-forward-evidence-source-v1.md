# Server Forward Evidence Source V1

Status: `ACTIVE_AWAITING_FIRST_FORWARD_DAY`

This design closes the only remaining infrastructure gap in the autonomous
strategy-candidate loop: prospective OKX `BTC-USDT` complete 1-hour bars. The
sponsor authorized this exact read-only public forward-data source on
2026-08-04. Policy V3 now freezes the endpoint and isolation contract; the
authorization does not extend to any other external import or backfill.

## Objective

Deliver exactly one untouched UTC day (24 complete 1-hour bars) to the canonical
research intake between day close and the frozen six-hour deadline, without
giving the Research Worker network, database, Trading runtime, or credential
access and without adding a second timer.

The OKX public market-data contract is:

- fixed host and endpoint: `www.okx.com/api/v5/market/candles`;
- fixed instrument: `BTC-USDT`;
- fixed bar: `1H`;
- maximum one bounded response needed for the preceding UTC day;
- response layout: `[ts,o,h,l,c,vol,volCcy,volCcyQuote,confirm]`;
- every admitted bar must have `confirm=1`.

Official reference:
`https://www.okx.com/docs-v5/en/#rest-api-market-data-get-candlesticks`.

## Isolation

```text
single Codex cloud Ops schedule
  -> existing request_research_heartbeat operation
  -> deterministic CAPTURE_FORWARD_EVIDENCE companion request
  -> network-enabled agora-evidence-source oneshot
  -> atomic, hash-bound day bundle in one-way drop
  -> network-denied agora-research ingest oneshot
  -> research_pipeline ingest-evidence-day
  -> canonical hash chain
  -> on the final day, deterministic dataset / diagnostic / manifest / review
```

The source runs as a separate `agora-evidence-source` Unix identity. It may:

- resolve and connect only to the fixed OKX public HTTPS origin;
- write only the group-confined source-request audit and one-way source-drop
  directories;
- read only a minimal immutable capture contract containing trigger identity,
  source string, expected next UTC day, and capture deadline.

It may not read or write canonical research state, the main Worker request
queue, OAuth state, Trading files, Production secrets, databases, exchange
credentials, or the Spring application. The existing `agora-research` identity remains
network-denied and is the only identity that may extend canonical evidence
state.

## Clock and flow

No systemd timer is added. When the existing cloud Ops heartbeat is due, the
already exposed heartbeat operation reads canonical evidence progress. If
`next_observation_day` has closed and the capture deadline has not passed, it
atomically enqueues one fixed companion request. Repeated calls converge on the
same trigger/day fingerprint. This avoids a sixth MCP operation and keeps one
routine clock.

The source must reject:

- any caller-provided URL, host, symbol, interval, output path, or shell value;
- a day other than canonical `next_observation_day`;
- a request before UTC day close or after the six-hour deadline;
- any HTTP/TLS/API error, nonzero OKX code, incomplete candle, duplicate/gap,
  non-finite or invalid OHLC/volume, or timestamp outside the exact day;
- an existing drop with different bytes.

The normalized `FORWARD_EVIDENCE_DAY` bundle records the SHA-256 of the exact
24 selected raw OKX rows, the fixed producer version, and a deterministic
artifact id. A separate raw artifact records capture time and is file-hashed by
the delivery envelope. The network-denied intake independently reconstructs
the bundle from those rows, revalidates the existing
`evidence-day.schema.json` contract and appends the canonical SHA-256 chain.
When that append completes the frozen window, the same intake seals a
mechanism-neutral market-path diagnostic, typed evidence manifest, and ready
review. It does not map a strategy or evaluate strategy PnL; Codex remains the
hypothesis selector on the next cloud step.

## Activation gate

Before `2026-08-06T00:00:00Z`, all of the following must be true for the current
R1 trigger:

1. The sponsor explicitly authorizes the separate read-only public OKX forward
   source and the fixed outbound HTTPS boundary. **Satisfied 2026-08-04.**
2. The V3 authorized source amendment names the source operation while keeping
   Worker network/database/backfill as `DENY`. **Satisfied 2026-08-04.**
3. The canonical source contract binds producer, transport, trigger fingerprint,
   and authorization before evidence start. **Satisfied 2026-08-04; sealed
   source-contract SHA-256 `5473210ab96b5f102a63c989a8dcab609b70c093e3e16804272b2be3de0a426e`.**
4. Fixture, duplicate, late, gap, TLS/API failure, queue-concurrency, and
   end-to-end first-day tests pass. **Satisfied locally and on the server.**
5. The deployed source identity cannot read Trading secrets or write canonical
   research state, the canonical intake is network-denied, the fixed public
   OKX readiness probe passes, and the old heartbeat timer remains disabled.
   **Satisfied 2026-08-04.**

R1 is now lawfully active. Evidence begins at `2026-08-06T00:00:00Z`. The
single cloud cycle at `2026-08-07T01:00:00Z` should emit deterministic request
`cd3076eebd380bdd59c8be742659797a` for UTC day `2026-08-06`; acceptance must
still be proven from the resulting canonical sealed-day record rather than
assumed from deployment readiness.

If this gate is not complete before evidence start, do not backfill. Close R1 as
an integrity failure and register R2 with a later untouched start only after the
source is lawfully active.
