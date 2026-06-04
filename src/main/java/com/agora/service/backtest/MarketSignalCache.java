package com.agora.service.backtest;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 記憶體快取：每個 symbol+intervalCode 的最新一次策略評估結果。
 * 由 LiveSignalEvaluator 在每次 evaluate 後更新，供 TradingAnalysisService 讀取。
 */
@Component
public class MarketSignalCache {

    private final ConcurrentHashMap<String, EvalSnapshot> cache = new ConcurrentHashMap<>();

    public void update(String symbol, String intervalCode,
                       double score, double nnOutput, double rsi,
                       int fearGreedValue, double whaleBuyRatio,
                       StrategySignal signal, LocalDateTime barTime) {
        cache.put(symbol + ":" + intervalCode,
                new EvalSnapshot(symbol, intervalCode, score, nnOutput, rsi,
                        fearGreedValue, whaleBuyRatio, signal, barTime));
    }

    public EvalSnapshot get(String symbol, String intervalCode) {
        return cache.get(symbol + ":" + intervalCode);
    }

    public Collection<EvalSnapshot> getAll() {
        return cache.values();
    }

    public static class EvalSnapshot {
        public final String symbol;
        public final String intervalCode;
        public final double score;
        public final double nnOutput;
        public final double rsi;
        public final int fearGreedValue;
        public final double whaleBuyRatio;
        public final StrategySignal signal;
        public final LocalDateTime barTime;

        EvalSnapshot(String symbol, String intervalCode,
                     double score, double nnOutput, double rsi,
                     int fearGreedValue, double whaleBuyRatio,
                     StrategySignal signal, LocalDateTime barTime) {
            this.symbol        = symbol;
            this.intervalCode  = intervalCode;
            this.score         = score;
            this.nnOutput      = nnOutput;
            this.rsi           = rsi;
            this.fearGreedValue = fearGreedValue;
            this.whaleBuyRatio = whaleBuyRatio;
            this.signal        = signal;
            this.barTime       = barTime;
        }
    }
}
