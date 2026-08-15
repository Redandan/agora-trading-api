# BTC DRA Equal-Capital Capacity Runner V1

Status: `OFFLINE_CAPABILITY_ONLY_NO_OUTCOME`

## Purpose

This runner separates DRA V1 slot capacity from initial cash equity so a later
frozen study can compare one `30 USDT` slot with two `30 USDT` slots while both
ledgers begin with the same `60 USDT` capital. The existing DRA V1 entry,
seven-day cooldown, next-open fill, `+5%` queue, positive next-open fill guard,
`0.10%` fee per side, and `0.05%` adverse slippage remain unchanged.

The runner is a capability, not an experiment. It contains no data locator,
performance gate, hypothesis, candidate selection, OOS access, state writer,
network call, scheduler, or Trading path. Building and testing it must not read
the sealed pre-2025 corpus or any forward outcome.

## Economic ledger

Every run exposes:

- initial equity and separately bounded slot capacity;
- realized, unrealized, and total PnL;
- maximum drawdown against the common initial equity;
- slot-capacity and whole-equity utilization;
- buys, sells, blocked entries, turnover, and open cost;
- median and P90 completed holding time plus terminal open-lot ages;
- realized-lot and terminal-inventory attribution;
- hours by open-lot count, underwater hours and episodes, maximum underwater
  duration, minimum equity, and the maximum-drawdown timestamp.

`equal_capital_deltas` only pairs two same-window, same-equity ledgers. It has no
pass/fail threshold. A later study must freeze its Design, Validation, annual,
concentration, drawdown, holding-time, and terminal-inventory gates before it
loads any real corpus.

## Boundaries

- authorization remains `RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE`;
- Server Canonical remains the sole research-state writer;
- the sole Codex Cloud Ops schedule remains the only clock;
- no Production, database, order, fund, OCO, Grid, SHADOW, PAPER, or LIVE action
  is permitted;
- a positive later result cannot change the current one-lot DRA runtime and can
  end only at a separately frozen research hypothesis or matched-capital
  experiment.
