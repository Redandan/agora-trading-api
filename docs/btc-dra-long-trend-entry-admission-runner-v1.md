# BTC DRA Long-Trend Entry-Admission Runner V1

Status: `OFFLINE_CAPABILITY_ONLY_NO_OUTCOME`

## Purpose

This runner permits a later frozen study to test one causal entry-admission
mechanism: an otherwise unchanged DRA V1 signal is admitted only when the
current complete UTC-day close is strictly above the simple average of the
prior 200 complete UTC-day closes. It changes no arm, 30-day expiry, seven-day
cooldown, next-open fill, lot size, `+5%` exit, fee, adverse slippage, or
positive next-open exit guard.

The feature is known at the original DRA decision close and never uses the
current incomplete day or a later price. The first 200 days after hard data
inception use an explicit parent-preserving fallback because no earlier corpus
is available. This fallback must be reported and may not be interpreted as
trend evidence.

The runner is a capability, not an experiment. It contains no data locator,
performance gate, threshold sweep, hypothesis, candidate selection, OOS
access, state writer, network call, scheduler, or Trading path. Building and
testing it must not read a real historical or forward corpus.

## Equal-capital ledger

Both parent and candidate ledgers use `250 USDT` initial equity. The effective
whole-lot capacity is `240 USDT`, which is economically identical to the DRA V1
`250 USDT` cap because a ninth `30 USDT` lot exceeds both ceilings. A later
study must first prove exact DRA V1 parent parity.

Every run exposes realized, unrealized and total PnL; drawdown against common
equity; utilization; buys, sells and blocked entries; median and P90 completed
holding time; realized-lot and terminal-inventory attribution; hours by open
lot count; underwater hours, episodes and maximum duration; minimum equity;
the drawdown timestamp; parent signals, admissions, vetoes and hard-inception
fallbacks.

`paired_deltas` only compares same-window, same-equity ledgers and has no pass
or fail rule. Design, historical Validation, annual breadth, concentration,
drawdown, holding, path-risk and disposition gates must be frozen separately
before any real data is loaded.

## Boundaries

- authorization remains `RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE`;
- Server Canonical remains the sole research-state writer;
- the sole Codex Cloud Ops schedule remains the only research clock;
- no Production, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action
  is permitted;
- neither a positive nor negative historical result may alter the DRA runtime;
  a positive result may only retain a prior for a separately frozen hypothesis
  or equal-capital experiment.
