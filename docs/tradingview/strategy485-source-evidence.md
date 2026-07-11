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

## Remaining evidence boundary

The Pine source does not plot `nnOutput`, and none of the 203 report entries is
an AI-buy order. Exact per-intent NN error at `1e-6` therefore still requires a
separately authorized, instrumented Pine copy or another TradingView series
export. The evidence above proves entry parity for the two order paths that
actually fired, but it must not be presented as completed NN parity.

## Free NN export handoff

Read-only Chrome inspection on 2026-07-11 confirmed all of the following:

- The Basic-plan Strategy Report `Download data as XLSX` action opens an upgrade
  prompt, so it is not a free evidence path.
- Data Window and chart Table view expose only plotted Pine series. The original
  `AI` strategy columns are Bollinger bands, long MA, relative-low,
  potential-low, and AI-buy signal. They do not include `nnOutput`.
- Chart Table view offers `Download data` as CSV. TradingView documents that
  chart CSV includes script plots, including plots restricted to
  `display.data_window`: https://www.tradingview.com/pine-script-docs/faq/indicators/#is-it-possible-to-export-indicator-data-to-a-file

Do not edit the original strategy. After separate authorization to create an
instrumented TradingView copy, add the line from
`strategy485-nn-export-snippet.pine` immediately after `nnOutput` is calculated:

```pine
plot(nnOutput, title = "NN Output Export", display = display.data_window, format = format.price, precision = 10)
```

Then open chart `Table view`, choose `Download data`, keep UTC/UNIX time, and
join the exported NN series to the already verified intent report:

```powershell
.\scripts\join_tradingview_nn_chart_export.ps1 `
  -ChartCsvPath 'C:\path\to\BINANCE_BTCUSDT_1D.csv' `
  -IntentCsvPath '.\docs\tradingview\strategy485-report-365.csv' `
  -OutputPath '.\docs\tradingview\strategy485-golden-365.csv'
```

The join fails closed for a missing intent timestamp, duplicate chart timestamp,
invalid NN value, or ambiguous NN column. Its output is canonical
`time,reason,label,qty,nn_output` plus a SHA-256 manifest. It does not authorize
a production import, production env change, or live promotion.
