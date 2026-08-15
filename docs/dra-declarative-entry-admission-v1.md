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
  median, admitted at or above the threshold.

All features fail closed until their prior window is complete. A signal uses
only the latest complete UTC-day feature known before the next-bar fill. The
parent side, entries, exits, lot size, eight-slot capacity, 250 USDT initial
equity, fee, and adverse slippage remain unchanged.

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
