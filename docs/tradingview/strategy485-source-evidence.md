# Strategy 485 TradingView Source Evidence

- Captured read-only from the signed-in TradingView chart on 2026-07-11T05:20:01Z.
- Chart: `BINANCE:BTCUSDT`, interval `1D`, strategy `AI`.
- Pine source SHA-256: `e144024f8972b2b624bfc05888cdb0fac52feb17c9376f647aa9517ef6de0715`.
- The raw source is not stored because its alert payload contains a webhook secret.
- Strategy report range: 2017-08-17 through 2026-07-11.
- Full report count: 203 intents: 141 relative-low, 62 potential-low, 0 AI-buy intents.
- Basic-plan CSV download was unavailable. The visible report table was read without changing the script or chart.

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
