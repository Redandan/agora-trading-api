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
  20-complete-day median, admitted at or below the threshold.

All features fail closed until their prior window is complete. A signal uses
only the latest complete UTC-day feature known before the next-bar fill. The
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

Run a frozen screen with:

```text
python research/btc_dra_declarative_entry_admission_v1.py \
  --manifest <manifest.json> --input <pre-2025.tsv> --output <new-output.json>
```

Generated outputs belong under `.research-state/` and are create-only. The
runner has no network, database, server, scheduler, Trading, deployment, or
canonical-state integration.
