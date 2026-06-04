package com.agora.service.backtest;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TradeRecord {

    private LocalDateTime entryTime;
    private LocalDateTime exitTime;
    private double entryPrice;
    private double exitPrice;
    private double quantity;
    private double grossPnl;
    private double netPnl;
    private double returnPct;
    private String exitReason;
    private String side;
    private double borrowingCost;
    private double releasedNotional;

    // ─── V047 (2026-04-17) — indicator snapshot at ENTRY time ───────────────
    // Purpose: feed ML signal_scorer with actual market state, not just
    // price+timestamp. Each value is computed at the entry bar from the same
    // kline series the strategy saw. NaN encoded as null (not stored).
    private Double adx14;               // ADX trend strength at entry
    private Double rsi14;               // RSI 14 at entry
    private Double atrPct;              // ATR% of entry_price
    private Double volumeRatioMa20;     // current_vol / 20-bar volume MA
    private Double closeVsEma50Pct;     // (close - EMA50) / EMA50
    private Double ema20SlopePct;       // (EMA20 - EMA20[-5]) / EMA20[-5]
    private Double bbWidthPct;          // Bollinger Band width / middle

    // ─── V049 (2026-04-17) — regime / position-in-trend features ────────────
    // Same-TF rolling derivations, no external API. Phase 2 walk-forward
    // showed V047 features alone produce 0pp edge on regime-shift holdout —
    // these add the "where am I in the broader trend" context that LightGBM
    // and the LLM scorer both need.
    private Double dd20barPct;          // drawdown from 20-bar high (positive = below peak)
    private Double dd50barPct;          // drawdown from 50-bar high
    private Double momentum50barPct;    // (close - close[idx-50]) / close[idx-50]
    private Double realizedVol20bar;    // stdev of 20 log returns
    private Double distFromEma200Pct;   // (close - EMA200) / EMA200, long-term position
    private Double rangePct50bar;       // (high50 - low50) / midpoint, range tightness

    // V050 cross-timeframe features (NULL until BacktestEngine wires HTF kline loading)
    private Double htfMomentum50barPct; // 50 HTF-bar momentum (1h trade → 4h HTF)
    private Integer htfTrendUp;         // 1 if HTF close > HTF EMA50 else 0
    private Double htfDistEma50Pct;     // (HTF close - HTF EMA50) / HTF EMA50
}
