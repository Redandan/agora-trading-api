# Declarative DRA Entry-Admission Screen V1

This offline runner turns one frozen, decision-time feature into an equal-
capital DRA V1 entry-admission screen. It exists to shorten idea-to-economic-
decision time without creating another timer, writer, production path, or
arbitrary expression language.

The manifest is closed by
`research_pipeline/dra-declarative-entry-admission-manifest.v1.schema.json`.
It binds one primary-source prior, the exact pre-2025 H1 corpus, unchanged DRA
V1 parent economics, one supported feature family, and one to three variants.
The runner supports only:

- lagged complete-day realized volatility versus its prior 20-complete-day
  median, admitted at or below the frozen threshold;
- complete-day volume versus its prior 20-complete-day median, admitted at or
  above the threshold; and
- complete-day high-low range percentage versus its prior 20-complete-day
  median, admitted at or above the threshold; and
- complete-day downside realized-semivariance share versus its prior
  20-complete-day median, admitted at or below the threshold; and
- complete-day Amihud-style illiquidity versus its prior 20-complete-day
  median, admitted at or below the threshold; and
- complete-day realized-variance to bipower-variation ratio versus its prior
  20-complete-day median, admitted at or below the threshold; and
- complete-day intraday return-sign persistence share versus its prior
  20-complete-day median, admitted at or above the threshold; and
- complete-day positive-return estimated quote-volume share versus its prior
  20-complete-day median, admitted at or above the threshold; and
- complete-day estimated quote-volume Herfindahl concentration versus its
  prior 20-complete-day median, admitted at or below the threshold; and
- complete-day close location within the day's high-low range versus its prior
  20-complete-day median, admitted at or above the threshold; and
- final close relative to the complete day's H1 base-volume-weighted mean
  close versus its prior 20-complete-day median, admitted at or above the
  threshold; and
- complete-day Schnytzer-Westreich realized performance, ranked against the
  prior 20 complete days, admitted at or above the frozen percentile.
- complete-day H1 lag-1 open-to-close log-return autocorrelation, ranked
  against the prior 20 complete days, admitted at or above the frozen
  percentile; and
- the latest complete UTC day's weekday index (`Monday=0` through `Sunday=6`),
  admitted at or below the frozen threshold without a rolling lookback.

Rolling features fail closed until their prior window is complete. The direct
calendar feature becomes available only after its 24-hour UTC day is complete.
A signal uses only the latest complete UTC-day feature known before the next-bar fill. The
parent side, entries, exits, lot size, eight-slot capacity, 250 USDT initial
equity, fee, and adverse slippage remain unchanged.

The volatility feature must bind the completed co-equal volatility-management
prior audit. The already-supported volume and range features instead bind the
pre-outcome `forward-diagnostic-contract.v1.json`, which froze their definitions
and 1.25/1.50/1.75 thresholds before any discovery outcome. That binding is a
design prior only: it does not convert historical development evidence into the
90-day discovery result or clean OOS.

The downside-semivariance feature is sign-asymmetric rather than another total-
volatility level. It divides squared negative hourly returns by all squared
hourly returns for the latest complete day, compares that share with the prior
20-complete-day median, and binds a co-equal prior/falsification audit. It does
not reuse the closed lagged-volatility family.

The Amihud-style feature is a price-impact proxy rather than plain volume,
range, trend, or volatility. For each hourly subinterval of the latest complete
UTC day it divides absolute open-to-close return by estimated dollar volume
(`close * base volume`), averages the 24 ratios, and compares that value with
the prior 20-complete-day median. A zero-volume hour fails closed. The feature
binds a co-equal primary-literature prior, while the use of close-price dollar
volume remains an explicit approximation rather than an order-book liquidity
claim.

The realized-to-bipower feature is a sign-neutral price-path jumpiness proxy,
not another total-volatility level and not a formal H1 jump detector. It divides
the latest complete day's sum of squared hourly close-to-close returns by
`pi/2` times the sum of adjacent absolute-return products, then compares that
ratio with the prior 20-complete-day median. Bipower variation is comparatively
robust to rare jumps, while the ratio exposes how much realized variation is
unsupported by adjacent moves. Non-positive realized or bipower variation
fails closed. Hourly sampling is explicitly too coarse to claim a true jump
classification.

The intraday sign-persistence feature is a magnitude-neutral serial-dependence
proxy, not another long-trend or volatility filter and not a formal
autocorrelation test. From the 24 hourly closes wholly contained in the latest
complete UTC day, it forms 23 consecutive close-to-close returns and the 22
adjacent return pairs. It divides the number of strictly same-sign pairs by 22,
then compares that share with the prior 20-complete-day median. Zero returns do
not count as persistence and the denominator remains 22, so the result cannot
be improved by silently deleting flat hours. The feature deliberately excludes
the preceding day's close and binds a co-equal primary-literature prior.

The directional-volume-participation feature is an H1 bar proxy, not true
taker order flow or an order-book imbalance measure. For every hourly bar in
the latest complete UTC day it estimates quote volume as `close * base volume`,
sums that amount for bars whose close is strictly above their open, and divides
it by the day's total estimated quote volume. The share is then divided by the
median of exactly the prior 20 complete-day shares. Individual zero-volume
hours remain valid and contribute zero; non-positive total daily quote volume
fails closed. This keeps the mechanism distinct from total volume magnitude
and unweighted return-sign persistence while binding a co-equal market-impact
and Bitcoin fragmentation prior.

The intraday-volume-concentration feature measures how unevenly the latest
complete UTC day's estimated quote volume is distributed across its 24 hourly
bars. It sums the squared hourly shares of `close * base volume`, producing
`1/24` for perfectly even participation and `1` when all volume is confined to
one hour, then divides that value by the prior 20-complete-day median. It is a
within-day liquidity-path proxy rather than a total-volume, directional-volume,
order-flow or market-wide liquidity claim. Individual zero-volume hours remain
in the fixed 24-hour day; non-positive total daily quote volume fails closed.

The close-location feature measures where the final hourly close sits inside
the latest complete UTC day's high-low range. It is zero at the daily low and
one at the daily high, then is divided by the median of exactly the prior 20
complete-day values. This is a price-path rejection-versus-persistence proxy,
not an order-flow, total-range, volatility or candle-pattern claim. It requires
exactly 24 hourly bars and a positive daily range. The unchanged long DRA entry
is admitted only when the ratio is at or above the frozen threshold.

The H1 volume-weighted close-location feature divides the final hourly close
by `sum(hourly close * base volume) / sum(base volume)` for exactly the 24 bars
inside the latest complete UTC day, then divides that value by the median of
exactly the prior 20 complete-day values. It is an OHLCV approximation of
ending price relative to the day's volume-weighted price center, not trade-level
VWAP, order flow, price impact or fair value. Individual zero-volume hours
remain in the day; non-positive daily base or estimated quote volume fails
closed. The feature is distinct from total volume, green-candle volume share,
volume concentration, daily range and high-low close location.

The realized-performance feature uses all 24 open-to-close H1 log returns
inside the latest complete UTC day. It solves the non-zero
`mean(exp(-lambda * return)) = 1` root, which combines the entire intraday
return distribution rather than selecting variance, skewness or kurtosis in
isolation. The latest value is converted to a midrank percentile against
exactly the prior 20 complete-day values, so the frozen 0.4/0.5/0.6 thresholds
do not depend on the unstable scale of `lambda`. A day without both positive
and negative intraday outcomes fails closed; no epsilon, clipping or alternate
moment approximation is allowed. This is an H1 proxy for published 5-minute
evidence and does not inherit that paper's significance or return claim.

The H1 lag-1 return-autocorrelation feature uses the same 24 open-to-close log
returns wholly inside the latest complete UTC day, forms 23 ordered adjacent
pairs, and computes their Pearson correlation with separately demeaned leading
and lagged sequences. It then converts the coefficient to a midrank percentile
against exactly the prior 20 complete days. A higher percentile means that
hourly returns were less negatively autocorrelated or more persistent than
recent history; it does not by itself prove price direction or profitability.
Zero variation in either sequence fails closed. This sequence-order statistic
is distinct from the closed magnitude-neutral same-sign-pair share, total
volatility, jumpiness and long-trend families.

The weekend-calendar feature maps the latest complete UTC day to an integer
from Monday `0` through Sunday `6`. Its nested thresholds admit Monday through
Thursday (`3`), Monday through Friday (`4`, primary), or Monday through
Saturday (`5`). It tests whether the repeatedly observed reduction in weekend
Bitcoin trading activity is useful as a DRA entry-admission condition; it does
not assume or optimize a weekday return premium. UTC, the weekday subsets and
the three thresholds are frozen, and the family closes on failure without
shifting time zones or selecting individual weekdays.

The fixed UTC traditional-session activity feature sums estimated quote volume
(`H1 close * base volume`) for exactly the six whole bars opened from 15:00
through 20:00 UTC, divides it by estimated quote volume across all 24 bars in
the latest complete UTC day, and then divides that share by the median of the
prior 20 complete-day shares. The six-bar interval is the whole-H1 subset fully
inside the published 14:30-21:00 GMT NYSE window. It is frozen in UTC: no
daylight-saving shift, half-hour reconstruction, venue-local reinterpretation,
or after-outcome session search is allowed. This participation-share feature is
distinct from total daily volume, positive-return volume share, hourly volume
concentration, and the weekend calendar label. It tests matched-capital DRA
economics, not a standalone time-of-day return anomaly.

Each screen reports the parent and candidate Design, Validation, and 2020-2024
annual ledgers, including realized, unrealized, and total PnL, maximum drawdown,
holding distribution, utilization, underwater duration, realized-lot fills,
and terminal inventory. Paired deltas always use equal initial capital.

The frozen gate set requires Design and Validation total-PnL improvement,
Validation realized and unrealized non-inferiority, drawdown within 0.25
percentage points, non-worse median and P90 holding time, minimum intervention
counts, at least three of five annual PnL wins, at least four of five annual
drawdown non-worse years, positive-delta concentration no greater than 60%, and
non-worse neighbor stability. A failure returns
`NO_MECHANISM_CLOSE_FEATURE_FAMILY`; it does not authorize tuning. A pass only
permits one separately frozen hypothesis manifest. OOS remains denied.

New fixed UTC traditional-session activity, close-location, H1 volume-weighted
close-location, realized-performance, H1 lag-1 return-autocorrelation, and
weekend-calendar screens use gate set V2. In addition to every V1 gate, the
primary must strictly improve realized PnL in both Design and Validation, keep
maximum underwater duration non-worse in both windows, and not increase
terminal inventory counts. Both neighbors must also keep Validation realized
PnL, underwater duration, and terminal inventory non-worse. Earlier sealed V1
screens retain their original gate identity and are never reinterpreted.

Run a frozen screen with:

```text
python research/btc_dra_declarative_entry_admission_v1.py \
  --manifest <manifest.json> --input <pre-2025.tsv> --output <new-output.json>
```

Generated outputs belong under `.research-state/` and are create-only. The
runner has no network, database, server, scheduler, Trading, deployment, or
canonical-state integration.
