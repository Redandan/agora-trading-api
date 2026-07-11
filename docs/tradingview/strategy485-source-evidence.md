# Strategy 485 TradingView Source Evidence

- Captured read-only from the signed-in TradingView chart on 2026-07-11T05:20:01Z.
- Chart: `BINANCE:BTCUSDT`, interval `1D`, strategy `AI`.
- Pine source SHA-256: `e144024f8972b2b624bfc05888cdb0fac52feb17c9376f647aa9517ef6de0715`.
- The raw source is not stored because its alert payload contains a webhook secret.
- Strategy report range: 2017-08-17 through 2026-07-11.
- Full report count: 203 intents: 141 relative-low, 62 potential-low, 0 AI-buy intents.
- Basic-plan Strategy Report XLSX download was unavailable. The visible report table was read without changing the script or chart.

## 365-day intent evidence

`strategy485-report-365.csv` contains the 42 report entries whose fill dates fall
inside the available 365-day window. TradingView market orders fill on the next
daily bar, so `signal_time_utc` is the Binance daily bar immediately preceding
`fill_time_utc`.

- Canonical CSV SHA-256: `e5b64e0eef687cca3b3a2efe1675dd47105ddceb994d7acfad76eb805f81269b`.
- Independent source: Binance Vision `data-api.binance.vision`.
- Binance replay bars: 3,250, from 2017-08-17 through the last closed bar on 2026-07-10.
- Result for relative/potential-low intents: expected 42, actual 42, missing 0, extra 0.

Re-run the read-only comparison with:

```powershell
.\scripts\verify_strategy485_tradingview_report.ps1
```

## Authorized private NN export

On 2026-07-11, the authorized Chrome workflow created the private copy
`AI - Strategy 485 NN Export Audit`. The original `AI` strategy was not edited.
The copy added only this data-window plot immediately after `nnOutput` was
calculated:

```pine
plot(nnOutput, title = "NN Output Export", display = display.data_window, format = format.price, precision = 10)
```

- The copy was saved and applied to `BINANCE:BTCUSDT`, interval `1D`.
- Data Window showed 10-decimal NN values.
- TradingView Basic blocked the official Strategy Report download, so the
  signed-in chart Table view was read directly and normalized to CSV.
- `strategy485-nn-chart-export-365.csv` has 365 continuous closed daily rows,
  from 2025-07-11 through 2026-07-10, with SHA-256
  `35e87a1a773a1d9653fea88fcc7e4e935c348e054020da1a0e7e4d4896d1a225`.
- No alert was created and no order was placed.

The NN series was joined to the already verified intent report with:

```powershell
.\scripts\join_tradingview_nn_chart_export.ps1 `
  -ChartCsvPath '.\docs\tradingview\strategy485-nn-chart-export-365.csv' `
  -IntentCsvPath '.\docs\tradingview\strategy485-report-365.csv' `
  -OutputPath '.\docs\tradingview\strategy485-golden-365.csv'
```

The canonical `strategy485-golden-365.csv` has SHA-256
`cef8fcdb95014998e6ced612f6aa767addeee23584515cd9825d8cd4938a6bc5`.
It contains all 42 TradingView order intents with complete `nn_output` evidence.

## Exact buy-point verification

Run the external-data integration check with:

```powershell
.\scripts\verify_strategy485_tradingview_exact_parity.ps1
```

The check downloads 3,250 Binance Vision daily bars into local `target`, then
executes the production `SCORE_BUY_V2` strategy and online model. The verified
result after fixing the Pine 252-bar year-high warmup is:

- expected intents 42, actual intents 42, missing 0, extra 0;
- intent parity status `PASS_EXACT_PARITY`;
- maximum NN error on the 42 actual buy intents: `2.946044341811671E-08`,
  below the required `1E-06`;
- fitted TradingView and Java NN state at the second export row agree within
  `1.4E-07` for weight and `1.0E-07` for bias.

The stricter all-bars comparison still reports four non-intent rows above
`1E-06`: 2025-10-21, 2025-10-22, 2026-02-19, and 2026-07-10. Its maximum raw
daily error is `2.3942303786439467E-05`. This is retained as
`PASS_EXACT_BUY_POINT_PARITY_WITH_RAW_NN_DRIFT`, not presented as zero-drift
full-series parity. Use `-RequireFullDailyNnParity` to fail closed on those four
rows when a TradingView OHLCV/state export becomes available.

The export, join, and verification do not authorize a production import,
production env change, live promotion, alert, or order.

## Production Binance replay preflight

Before requesting authorization for the missing production replay history, run:

```powershell
.\scripts\prepare_strategy485_binance_replay_backfill_preflight_ssh.ps1
```

The preflight performs production `md_kline` `SELECT` queries and Binance Vision
HTTPS `GET` requests only. It compares exact OHLCV on every overlapping UTC day,
writes canonical SHA-256 evidence, and splits missing contiguous history into
planned `backfillBinanceKlinesRange` calls of at most 730 days. Every planned
call uses `replaceExisting=false`, so the proposal is insert-missing-only.

The 2026-07-11 production run found:

- 3,250 complete Binance Vision bars and 770 production rows marked `binance`;
- 731 exact overlap matches;
- 39 five-field OHLCV mismatches from 2024-06-01 through 2024-07-09;
- all 39 mismatched production rows exactly match the official Binance.US
  endpoint, with zero missing, duplicate, or mismatched rows;
- the `source` column length is 10, `binance_us` fits, and no target rows exist;
- preserving and relabeling those 39 rows would leave 2,519 global Binance bars
  to insert in four bounded `replaceExisting=false` calls.

The disposition is
`READY_FOR_SEPARATE_SOURCE_RELABEL_AND_BACKFILL_AUTHORIZATION_NOT_MUTATION`.
The preflight does not emit update SQL and keeps both source relabel and external
backfill permissions false. Insert-missing-only must not run before the proven
Binance.US rows are separately authorized for preservation under `binance_us`;
otherwise the global Binance gaps for those 39 days remain hidden by the old
source label.

`READY_FOR_SEPARATE_EXTERNAL_BACKFILL_AUTHORIZATION` is an evidence state, not
authorization. Enabling the external-backfill guard, executing any planned
call, relabeling the 39 legacy rows, restarting production, or importing the
normalized golden CSV still requires separate exact authorization. Keep
`BTC_BASE_DRY_RUN` and live-order false throughout that workflow.
