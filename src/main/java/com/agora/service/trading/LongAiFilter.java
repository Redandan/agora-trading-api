package com.agora.service.trading;

import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.ai.AiStrategyDiscoveryService;
import com.agora.service.market.EventCalendarService;
import com.agora.service.market.FearGreedService;
import com.agora.service.market.OrderbookImbalanceService;
import com.agora.service.market.WhaleFlowService;
import com.agora.service.trading.MdKlineToBarSeriesConverter;
import com.agora.config.properties.OrderbookImbalanceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.volume.ChaikinMoneyFlowIndicator;

/**
 * LONG 進場多層防護過濾器（對稱 {@link ShortAiFilter}）。
 *
 * <p><b>Layer 1（確定性規則）</b>：恐懼貪婪 > 75、4h 趨勢 BEARISH、RSI 超買、鯨魚賣出、
 * 資金費率過高（多頭付費）、多空比過高（多頭擠壓風險）。
 *
 * <p>在 {@code shadow} 模式下僅記錄決定，不攔截交易；{@code active} 模式才實際封鎖。
 * 首次上線建議先跑 shadow 觀察 1-2 週後再切 active。
 *
 * <p>跳過 Polymarket：其「關稅緩解」概率是 SHORT 專用反向信號，LONG 需要不同關鍵字
 * （衰退、升息等），v1 暫不納入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LongAiFilter {

    private final AiStrategyDiscoveryService aiDiscoveryService;
    private final FearGreedService fearGreedService;
    private final WhaleFlowService whaleFlowService;
    private final OkxTradingService okxTradingService;
    private final EventCalendarService eventCalendarService;
    private final OrderbookImbalanceService orderbookImbalanceService;
    private final MdKlineRepository klineRepository;
    private final MdKlineToBarSeriesConverter barSeriesConverter;
    private final com.agora.config.properties.LongAiFilterProperties props;
    private final OrderbookImbalanceProperties orderbookProps;

    public record FilterResult(boolean allowed, String reason) {}

    /**
     * 對指定幣種執行 LONG 過濾檢查。
     *
     * @param symbol       交易對（如 BTCUSDT）
     * @param intervalCode 1h 訊號週期
     * @param currentRsi   當前 RSI 值（來自 LiveSignalContext.Snapshot）
     * @return FilterResult.allowed=false 時應跳過做多
     */
    public FilterResult check(String symbol, String intervalCode, double currentRsi) {
        if (!props.enabled()) return new FilterResult(true, "filter disabled");

        FilterResult rule = checkRules(symbol, currentRsi);

        if ("shadow".equalsIgnoreCase(props.mode())) {
            if (!rule.allowed()) {
                log.info("[LongAiFilter][SHADOW] symbol={} would block: {}", symbol, rule.reason());
            }
            return new FilterResult(true, "shadow: " + rule.reason());
        }

        return rule;
    }

    private FilterResult checkRules(String symbol, double rsi) {
        // 0. 事件日曆封鎖（FOMC / CPI 等高影響事件窗口）
        EventCalendarService.BlockResult evt = eventCalendarService.checkBlock();
        if (evt.blocked()) {
            long h = evt.timeToEvent().toHours();
            String when = h > 0 ? String.format("%d 小時後", h) : String.format("%d 小時前已發生", -h);
            return new FilterResult(false,
                    String.format("事件窗口封鎖：%s（%s），禁止做多", evt.event().name(), when));
        }

        // 先取 4h 快照：F&G 與 4h 趨勢要合併判斷
        AiStrategyDiscoveryService.MarketSnapshot snap4h = null;
        try {
            snap4h = aiDiscoveryService.buildMarketSnapshot(symbol, "4h");
        } catch (Exception e) {
            log.warn("[LongAiFilter] 4h snapshot failed for {}: {}", symbol, e.getMessage());
        }

        // 1. 恐懼貪婪 > 75 + 4h 非多頭延續 → 頂部頂背離情境禁止做多
        // 若 4h 明確 BULLISH 且 MACD 柱狀 ≥ 0，允許順勢 LONG（牛市延續）
        int fg = fearGreedService.getFearGreedValue();
        if (fg > props.fgThreshold()) {
            boolean bullishTrend = snap4h != null
                    && "BULLISH".equals(snap4h.trendDirection())
                    && snap4h.macdHistogram() >= 0;
            if (!bullishTrend) {
                return new FilterResult(false, String.format(
                        "Fear&Greed=%d（>%d）且 4h 非延續多頭（可能為極度貪婪頂部，禁止做多）", fg, props.fgThreshold()));
            }
            log.debug("[LongAiFilter] F&G={} + 4h BULLISH → allow trend-following LONG", fg);
        }

        // 2. 4h 大時框偏空：禁止做多（與 F&G 規則分離）
        if (snap4h != null) {
            if ("BEARISH".equals(snap4h.trendDirection()))
                return new FilterResult(false,
                        "4h 趨勢=BEARISH（大時框偏空，price < EMA20）");
            if (snap4h.macdHistogram() < 0)
                return new FilterResult(false,
                        String.format("4h MACD柱狀=%.4f（<0 動能偏空，禁止做多）", snap4h.macdHistogram()));
        }

        // 3. RSI > 80 = 超買
        if (rsi > props.rsiThreshold())
            return new FilterResult(false,
                    String.format("RSI=%.1f（>%.0f 超買，不適合做多）", rsi, props.rsiThreshold()));

        // 4. 鯨魚賣出 > (1 - 閾值) = 大戶持續賣出
        double whale = whaleFlowService.getBuyRatio(symbol);
        if (whale > 0 && whale < props.whaleBuyRatioThreshold())
            return new FilterResult(false,
                    String.format("鯨魚賣出=%.0f%%（>%.0f%% 大戶持續賣出）",
                            (1 - whale) * 100, (1 - props.whaleBuyRatioThreshold()) * 100));

        // 持續多頭趨勢下，資金費率/多空比的極端是結構性正常 → 同 F&G 規則邏輯
        boolean bullishContinuation = snap4h != null
                && "BULLISH".equals(snap4h.trendDirection())
                && snap4h.macdHistogram() >= 0;

        // 5+6:perp 專用指標,spot-mode=true 時 skip(SPOT 做多無 funding/擠壓風險)
        if (!props.spotMode()) {
            // 5. 資金費率 > 閾值 = 多頭付費過高（僅在非延續多頭時攔）
            try {
                double fundingRate = okxTradingService.getCurrentFundingRate(symbol);
                if (fundingRate > props.fundingRateThreshold() && !bullishContinuation)
                    return new FilterResult(false,
                            String.format("資金費率=%.4f%%（>%.4f%%）且 4h 非延續多頭（擠壓風險，禁止做多）",
                                    fundingRate * 100, props.fundingRateThreshold() * 100));
            } catch (Exception e) {
                log.warn("[LongAiFilter] FundingRate check failed, skipping: {}", e.getMessage());
            }

            // 6. 多空比 > 閾值 = 多頭過多（僅在非延續多頭時攔）
            try {
                double lsRatio = okxTradingService.getLongShortRatio(symbol);
                if (lsRatio > 0 && lsRatio > props.longShortRatioThreshold() && !bullishContinuation)
                    return new FilterResult(false,
                            String.format("多空帳戶比率=%.2f（>%.2f）且 4h 非延續多頭（擠壓風險，禁止做多）",
                                    lsRatio, props.longShortRatioThreshold()));
            } catch (Exception e) {
                log.warn("[LongAiFilter] LongShortRatio check failed, skipping: {}", e.getMessage());
            }
        } else {
            log.debug("[LongAiFilter] spot-mode=true, skip funding-rate + long-short-ratio (perp-only metrics)");
        }

        // 7. Orderbook imbalance < -閾值 = 賣牆堆積（可能即將下跌）→ 禁止做多
        // 瞬時訂單流信號，無趨勢 gate（同 whale / ShortAiFilter orderbook 設計）
        try {
            double imbalance = orderbookImbalanceService.getImbalance(symbol);
            if (imbalance < -orderbookProps.threshold())
                return new FilterResult(false,
                        String.format("Orderbook imbalance=%+.2f（<%.2f 賣牆堆積，即將下跌風險）",
                                imbalance, -orderbookProps.threshold()));
        } catch (Exception e) {
            log.warn("[LongAiFilter] Orderbook imbalance check failed, skipping: {}", e.getMessage());
        }

        // 9. EMA9: close < EMA9 = 短期下跌趨勢 → 封鎖做多（可選，需啟用 ema9FilterEnabled）
        if (props.ema9Filter()) {
            try {
                java.time.LocalDateTime since9 = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(20);
                java.util.List<com.agora.model.MdKline> klines9 =
                        klineRepository.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                                symbol, "1h", since9,
                                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
                if (klines9.size() >= 10) {
                    BarSeries series9 = barSeriesConverter.convert(klines9, symbol + "-ema9");
                    org.ta4j.core.indicators.EMAIndicator ema9 =
                            new org.ta4j.core.indicators.EMAIndicator(
                                    new org.ta4j.core.indicators.helpers.ClosePriceIndicator(series9), 9);
                    int last = series9.getEndIndex();
                    double closeVal = series9.getBar(last).getClosePrice().doubleValue();
                    double ema9Val  = ema9.getValue(last).doubleValue();
                    if (closeVal < ema9Val) {
                        return new FilterResult(false,
                                String.format("close=%.1f < EMA9=%.1f（短期下跌趨勢，封鎖做多）", closeVal, ema9Val));
                    }
                    log.debug("[LongAiFilter] EMA9={} close={} → pass", String.format("%.1f", ema9Val), String.format("%.1f", closeVal));
                }
            } catch (Exception e) {
                log.warn("[LongAiFilter] EMA9 check failed, skipping: {}", e.getMessage());
            }
        }

        // 8. CMF(20) < 0 = 淨資金流出（賣壓主導）→ 封鎖做多（可選，需啟用 cmfFilterEnabled）
        if (props.cmfFilter()) {
            try {
                java.time.LocalDateTime since = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(35);
                java.util.List<com.agora.model.MdKline> klines =
                        klineRepository.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                                symbol, "1h", since,
                                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
                if (klines.size() >= 22) {
                    BarSeries series = barSeriesConverter.convert(klines, symbol + "-cmf");
                    ChaikinMoneyFlowIndicator cmf = new ChaikinMoneyFlowIndicator(series, 20);
                    double cmfValue = cmf.getValue(series.getEndIndex()).doubleValue();
                    if (cmfValue < 0) {
                        return new FilterResult(false,
                                String.format("CMF(20)=%.4f（<0 淨資金流出，做多確認不足）", cmfValue));
                    }
                    log.debug("[LongAiFilter] CMF(20)={} → pass", String.format("%.4f", cmfValue));
                }
            } catch (Exception e) {
                log.warn("[LongAiFilter] CMF check failed, skipping: {}", e.getMessage());
            }
        }

        // 10-12. #432 — three optional false-positive reducer rules. Default OFF;
        // each owns its feature flag. Enable individually after backtest validation
        // shows ≥ 5pp winrate lift without dropping trade count below sustenance.
        FilterResult layer2 = checkLayer2Rules(symbol);
        if (!layer2.allowed()) return layer2;

        return new FilterResult(true, "Layer1 rules passed");
    }

    /**
     * #432 — Layer 2 false-positive reducers (CMO overbought / EMA cross / RSI divergence).
     * All three rules share the same kline window read; broken into a helper to keep
     * {@link #checkRules} flat. Each rule is independently flag-gated and try-catched.
     */
    private FilterResult checkLayer2Rules(String symbol) {
        if (!props.cmoFilter() && !props.emaCrossFilter() && !props.rsiDivergenceFilter()) {
            return new FilterResult(true, "layer2 disabled");
        }
        java.util.List<com.agora.model.MdKline> klines;
        try {
            java.time.LocalDateTime since = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(40);
            klines = klineRepository.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                    symbol, "1h", since, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        } catch (Exception e) {
            log.warn("[LongAiFilter] layer2 klines fetch failed, skip: {}", e.getMessage());
            return new FilterResult(true, "layer2 skipped (klines fetch err)");
        }
        if (klines.size() < 25) {
            log.debug("[LongAiFilter] layer2 insufficient klines ({}<25)", klines.size());
            return new FilterResult(true, "layer2 skipped (insufficient klines)");
        }
        BarSeries series = barSeriesConverter.convert(klines, symbol + "-l2");
        org.ta4j.core.indicators.helpers.ClosePriceIndicator close =
                new org.ta4j.core.indicators.helpers.ClosePriceIndicator(series);
        int last = series.getEndIndex();

        // Rule 10 — CMO(14) > threshold = overbought
        if (props.cmoFilter()) {
            try {
                org.ta4j.core.indicators.CMOIndicator cmo =
                        new org.ta4j.core.indicators.CMOIndicator(close, 14);
                double cmoVal = cmo.getValue(last).doubleValue();
                if (cmoVal > props.cmoThreshold()) {
                    return new FilterResult(false, String.format(
                            "CMO(14)=%.1f（>%.0f 超買，封鎖做多）", cmoVal, props.cmoThreshold()));
                }
                log.debug("[LongAiFilter] CMO(14)={} → pass", String.format("%.1f", cmoVal));
            } catch (Exception e) {
                log.warn("[LongAiFilter] CMO check failed, skipping: {}", e.getMessage());
            }
        }

        // Rule 11 — EMA(9) < EMA(21) = short-term bearish cross
        if (props.emaCrossFilter()) {
            try {
                org.ta4j.core.indicators.EMAIndicator ema9 =
                        new org.ta4j.core.indicators.EMAIndicator(close, 9);
                org.ta4j.core.indicators.EMAIndicator ema21 =
                        new org.ta4j.core.indicators.EMAIndicator(close, 21);
                double e9  = ema9.getValue(last).doubleValue();
                double e21 = ema21.getValue(last).doubleValue();
                if (e9 < e21) {
                    return new FilterResult(false, String.format(
                            "EMA(9)=%.1f < EMA(21)=%.1f（短期均線轉空，封鎖做多）", e9, e21));
                }
                log.debug("[LongAiFilter] EMA9={} EMA21={} → pass",
                        String.format("%.1f", e9), String.format("%.1f", e21));
            } catch (Exception e) {
                log.warn("[LongAiFilter] EMA cross check failed, skipping: {}", e.getMessage());
            }
        }

        // Rule 12 — bearish RSI divergence: price HH + RSI LH within last ~30 bars.
        // Simplification: compare two halves of the lookback window.
        // Recent half = last N/2 bars; prev half = bars before that. If max close in
        // recent half > max close in prev half (price HH) AND RSI at recent-peak <
        // RSI at prev-peak (RSI LH) → divergence.
        if (props.rsiDivergenceFilter()) {
            try {
                org.ta4j.core.indicators.RSIIndicator rsi =
                        new org.ta4j.core.indicators.RSIIndicator(close, props.rsiDivergencePeriod());
                int totalBars = series.getBarCount();
                int lookback = Math.min(30, totalBars);
                int halfPoint = totalBars - (lookback / 2);
                int startIdx = totalBars - lookback;

                int recentPeakIdx = -1, prevPeakIdx = -1;
                double recentPeakClose = -1, prevPeakClose = -1;
                for (int i = halfPoint; i < totalBars; i++) {
                    double c = series.getBar(i).getClosePrice().doubleValue();
                    if (c > recentPeakClose) { recentPeakClose = c; recentPeakIdx = i; }
                }
                for (int i = startIdx; i < halfPoint; i++) {
                    double c = series.getBar(i).getClosePrice().doubleValue();
                    if (c > prevPeakClose) { prevPeakClose = c; prevPeakIdx = i; }
                }
                if (recentPeakIdx >= 0 && prevPeakIdx >= 0
                        && recentPeakClose > prevPeakClose) {
                    double recentRsi = rsi.getValue(recentPeakIdx).doubleValue();
                    double prevRsi = rsi.getValue(prevPeakIdx).doubleValue();
                    if (recentRsi < prevRsi) {
                        return new FilterResult(false, String.format(
                                "RSI 背離（價 %.1f→%.1f HH，RSI %.1f→%.1f LH，封鎖做多）",
                                prevPeakClose, recentPeakClose, prevRsi, recentRsi));
                    }
                    log.debug("[LongAiFilter] RSI div check: price HH but RSI {} → {} no LH → pass",
                            String.format("%.1f", prevRsi), String.format("%.1f", recentRsi));
                }
            } catch (Exception e) {
                log.warn("[LongAiFilter] RSI divergence check failed, skipping: {}", e.getMessage());
            }
        }

        return new FilterResult(true, "layer2 rules passed");
    }
}
