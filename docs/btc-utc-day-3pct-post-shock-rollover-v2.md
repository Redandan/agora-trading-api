# BTC UTC-day 3% post-shock rollover lineage V2

This offline capability preserves the original R1 diagnostic and post-shock
contracts byte-for-byte while allowing the existing heartbeat children to
follow one deterministic missed-window discovery rollover.

The pure lineage resolver starts at the frozen R1 trigger identity and accepts
only a chain of `CLOSED` predecessor states whose reason is
`MISSED_CAPTURE_WINDOW_NO_BACKFILL`. Each link must bind the successor id and
fingerprint in the predecessor state and bind the predecessor id and
fingerprint in the successor state. Canonical trigger bytes, stored trigger
hashes, research-only authorization, discovery purpose, source, observation
unit and the inherited scientific contract are revalidated. A missing link,
fork, cycle, extra active leaf, candidate binding or drift fails closed.

When R1 is still the active leaf, the V1 schemas, namespaces, builders and
artifact bytes remain unchanged. When a successor is the active leaf:

- shock diagnostics use only consecutive accepted observations from that leaf;
- the first diagnostic requires two successor observations, never one R1 day
  plus one successor day;
- V2 diagnostics are create-only under
  `shock-diagnostics/btc-utc-day-3pct-v2/<leaf-fingerprint>/`;
- post-shock episodes require a V2 diagnostic plus the adjacent accepted
  outcome from the same leaf and an advancing leaf chain;
- V2 snapshots are create-only under
  `post-shock-factor/btc-utc-day-3pct-v2/<leaf-fingerprint>/snapshots/`;
- every V2 diagnostic, episode and snapshot binds the root and leaf ids and
  fingerprints.

The inclusive 3% threshold, H1/H6 path diagnostics, H24 primary response,
minimum-eight-episode gate, directional and chronological breadth, three-month
breadth, concentration gates and terminal dispositions are unchanged. The
existing heartbeat function and both child call signatures are unchanged; no
timer, source, writer, scheduler, hypothesis, candidate, OOS or Trading path is
added.

Immediate fee-adjusted PnL and drawdown effects are zero. Predictive value,
fees, slippage, capacity, matched-capital performance, OOS behavior, deployment
and Trading value remain `MISSING_PROOF`.
