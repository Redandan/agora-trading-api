# BTC VIX risk-state long/cash historical V1

This research-only experiment tests one external global-risk mechanism without
changing Production or an existing DRA parent.  It holds spot BTC when the
latest Cboe VIX close is at or below its midrank percentile against exactly the
prior 252 Cboe business-day closes and otherwise holds cash.

The Cboe close becomes usable only at `00:00 UTC` on the following calendar
day.  The latest target persists across weekends and Cboe holidays.  The
primary percentile is `0.8`; `0.7` and `0.9` are frozen neighbors.  No current
VIX observation enters its own reference window.

Design is 2020 through 2022 and Validation is 2023 through 2024.  Every window
starts with equity `1` in cash.  The comparison is buy-and-hold BTC with the
same initial equity, H1 path, fee and adverse-slippage scenario.  Results keep
realized, unrealized, total return, drawdown, underwater duration, exposure,
turnover, holding, terminal inventory, annual breadth and concentration
together.

A pass requires the primary and both neighbors to pass their frozen gates.
It may end only at `CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED` with independent
sealed OOS still unopened.  Any failed gate permanently closes the exact
source/lookback/relation/threshold family; an absolute VIX threshold, moving
average, slope, BTC interaction, leverage, shorting or another percentile is
not a rescue path.
