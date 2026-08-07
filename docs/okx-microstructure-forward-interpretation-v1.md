# OKX Microstructure Forward Interpretation V1

Status: `FROZEN_PREOUTCOME_RESEARCH_ONLY`

## Purpose

This contract interprets one already validated OKX BTC-USDT microstructure V3
create-only handoff. It is a deterministic discovery screen, not a strategy,
candidate, OOS result, performance result, order instruction, or activation
authorization.

The interpreter accepts only canonical handoff-result bytes plus the caller's
`HandoffContext`. It first calls `validate_handoff_result_bytes`. It accepts no
bare diagnostic object, alternate contract path, tier, horizon, threshold,
clock, environment setting, or filesystem location. It performs no write,
network call, subprocess, clock read, server call, or state mutation.

## Frozen screen

Global eligibility requires all of the following:

- diagnostic status `FORWARD_DIAGNOSTIC_READY_FOR_INTERPRETATION`;
- entry reference `NEXT_COMPLETE_MINUTE_OPEN`;
- fees and slippage label `NOT_APPLIED_DIAGNOSTIC_NOT_PNL`;
- every tier has `gate_status=PASS` and all four readiness booleans true;
- the three screen metrics are non-null for both fixed horizons.

The primary horizon is exactly 60 minutes. The confirmatory horizon is exactly
15 minutes. The 5-, 240-, and 1,440-minute values are descriptive only and
cannot change classification, selection, or disposition.

At each decision horizon:

- `POSITIVE` requires `median_return_bps > 0`,
  `positive_return_share_pct > 50.00`, and
  `matched_median_return_delta_bps > 0`;
- `NEGATIVE` requires all three values to be less than or equal to those
  boundaries;
- every other combination is `MIXED`.

A tier is `PASS` only when both horizons are `POSITIVE`, `REJECT` only when
both are `NEGATIVE`, and otherwise `AMBIGUOUS`.

## One simplest-sufficient mechanism

The fixed evaluation order is:

1. `MIDLINE_RATIO_1_5_ONLY`;
2. `MIDLINE_RATIO_1_5_PLUS_NET_TAKER_BUY`;
3. `MIDLINE_RATIO_1_5_PLUS_NET_TAKER_BUY_PLUS_BOOK_SUPPORT`.

The first passing tier is the only selected tier. Later passing tiers remain
diagnostic detail and never become additional mechanisms or variants. Observed
magnitude cannot reorder the list.

The result has exactly four terminal dispositions:

- `READY_FOR_ONE_HYPOTHESIS_DESIGN` when one tier is selected;
- `NO_CANDIDATE_SHORT_HORIZON_BUY_PRESSURE` when all tiers reject;
- `AMBIGUOUS_NO_HYPOTHESIS` when no tier passes and at least one is ambiguous;
- `INSUFFICIENT_FORWARD_EVIDENCE` when global eligibility is incomplete.

Positive status authorizes only one separately validated and frozen
hypothesis-design task. It does not register a hypothesis or candidate and does
not authorize OOS access.

## Seals and missing proof

The result binds the source handoff document and payload hashes, the handoff
result schema hash, the V3 diagnostic contract and diagnostic payload hashes,
and the interpretation contract hash. Output is compact, sorted-key canonical
UTF-8 JSON with a SHA-256 payload seal.

The following remain `MISSING_PROOF` regardless of disposition:

- statistical significance and dependence-adjusted uncertainty;
- raw-message producer correctness;
- DRA clock and point-in-time feature compatibility;
- fees, slippage, fills, and capacity;
- matched-capital PnL, drawdown, utilization, and holding risk;
- candidate readiness, OOS value, and activation.

The V3 discovery window cannot be relabelled as OOS for a hypothesis derived
from it. `NO_CANDIDATE`, ambiguity, and insufficient evidence are valid
fail-closed research outcomes and cannot be rescued by another tier, horizon,
threshold, backfill, or analyzer change.
