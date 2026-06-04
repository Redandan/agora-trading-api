package com.agora.service.backtest;

import com.agora.model.MdKline;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * 量化版 SOP 短線策略（MVP）：
 * 1) 1D：close 與 MA50 + MACD 零軸判斷方向
 * 2) ADX：僅 ADX > adxEntryThreshold 才可進場
 * 3) 1H：五項打分（EMA20、RSI、MACD 金叉、布林中軌上穿、量能）至少 minSignals
 * 4) R:R：入場前先以 1H 關鍵位估算，需 >= minRR
 */
@Component
public class SopMtfAdxStrategy implements Strategy {

    public static final String TYPE = "SOP_MTF_ADX";

    @Override
    public String getType() {
        return TYPE;
    }

    /**
     * #450 Phase 3 — SOP_MTF_ADX adjustExit: trend-following exit logic.
     *
     * <p>2 條規則:
     * <ol>
     *   <li><b>TREND_REVERSAL</b>:1h EMA20 從 EMA50 上方 cross 到下方 + LONG → forceClose</li>
     *   <li><b>ADX_COLLAPSE</b>:ADX 從進場 ≥25 跌到 &lt; 18 + 浮盈 → tightenTp 鎖小利</li>
     * </ol>
     *
     * <p>Live mode 限制:Phase 2 的 PositionExitManagerScheduler 建 ctx 沒帶 indicator arrays
     * (per-strategy-injected 設計目前沒做),所以此邏輯實際上只在 backtest 真正生效。
     * Live 路徑會走「indicators empty → no-op」graceful degradation。
     */
    @Override
    public java.util.Optional<ExitAdjustment> adjustExit(
            StrategyContext ctx, OpenPositionView pos, Map<String, Object> config) {
        if (!"LONG".equals(pos.side())) return java.util.Optional.empty();
        if (pos.entryPrice() == null || pos.currentPrice() == null) return java.util.Optional.empty();

        // Get base timeframe (1h default)
        String base = (String) config.getOrDefault("baseInterval", "1h");
        double[] ema20 = ctx.getIndicator(base, "ema20");
        double[] ema50 = ctx.getIndicator(base, "ema50");
        double[] adx = ctx.getIndicator(base, "adx");
        Integer iObj = ctx.getIndex(base);
        if (iObj == null) return java.util.Optional.empty();
        int i = iObj;

        // Need at least 2 bars to detect cross
        if (ema20 == null || ema50 == null || i < 1
                || i >= ema20.length || i >= ema50.length) {
            // Indicator arrays unavailable (e.g. live mode minimal ctx) → no-op
            return java.util.Optional.empty();
        }
        if (Double.isNaN(ema20[i]) || Double.isNaN(ema50[i])
                || Double.isNaN(ema20[i - 1]) || Double.isNaN(ema50[i - 1])) {
            return java.util.Optional.empty();
        }

        // Rule 1: TREND_REVERSAL — bearish cross + LONG → forceClose
        boolean bearishCross = ema20[i] < ema50[i] && ema20[i - 1] >= ema50[i - 1];
        if (bearishCross) {
            return java.util.Optional.of(ExitAdjustment.forceClose(
                String.format("EMA20(%.2f) crossed below EMA50(%.2f), trend reversal",
                        ema20[i], ema50[i]),
                "TREND_REVERSAL"
            ));
        }

        // Rule 2: ADX_COLLAPSE — ADX < 18 + profit → tightenTp
        if (adx != null && i < adx.length && !Double.isNaN(adx[i]) && adx[i] < 18.0
                && pos.inProfit()) {
            java.math.BigDecimal newTp = pos.currentPrice()
                    .multiply(java.math.BigDecimal.valueOf(1.005))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            return java.util.Optional.of(ExitAdjustment.tightenTp(
                newTp,
                String.format("ADX collapsed to %.1f (<18), trend dying", adx[i]),
                "ADX_COLLAPSE"
            ));
        }

        return java.util.Optional.empty();
    }

    @Override
    public Map<String, Object> defaultExecutionConfig() {
        return Map.of(
                "enableMtf", true,
                "dynamicLevelsEnabled", true,
                "partialTpEnabled", true,
                "allowShort", true,
                "moveSlToBreakeven", false
        );
    }

    @Override
    public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
        boolean enableMtf = getBoolean(config, "enableMtf", true);
        // 使用實際回測週期（支援 1h / 4h / 15m 等），預設 1h 保持向下相容
        String base = config.get("runIntervalCode") instanceof String s ? s.toLowerCase() : "1h";
        Integer i1h = context.getIndex(base);
        Integer i1d = enableMtf ? context.getIndex("1d") : null;
        if (i1h == null || i1h < 2 || (enableMtf && (i1d == null || i1d < 2))) {
            recordDiagnostic(config, context.getCurrent() == null ? null : context.getCurrent().getOpenTime(),
                    DiagnosticCode.MTF_INDEX_NOT_READY,
                    "baseIndex=" + i1h + " (" + base + "), 1dIndex=" + i1d);
            return StrategySignal.HOLD;
        }

        MdKline current1h = context.getCurrent(base);
        MdKline prev1h = i1h > 0 ? context.getTimeframeKlines().get(base).get(i1h - 1) : null;
        if (current1h == null || prev1h == null) {
            recordDiagnostic(config, context.getCurrent() == null ? null : context.getCurrent().getOpenTime(),
                    DiagnosticCode.MTF_CANDLE_MISSING,
                    "currentBase=" + (current1h != null) + ", prevBase=" + (prev1h != null) + " (" + base + ")");
            return StrategySignal.HOLD;
        }

        double[] adx1h = context.getIndicator(base, "adx");
        double[] ema20_1h = context.getIndicator(base, "ema20");
        double[] rsi1h = context.getIndicator(base, "rsi");
        double[] macd1h = context.getIndicator(base, "macdLine");
        double[] macdSignal1h = context.getIndicator(base, "macdSignal");
        double[] bollMid1h = context.getIndicator(base, "bollMid");
        double[] volumeMa1h = context.getIndicator(base, "volumeMa");
        double[] macd1d = enableMtf ? context.getIndicator("1d", "macdLine") : null;

        if (adx1h == null || ema20_1h == null || rsi1h == null || macd1h == null || macdSignal1h == null
            || bollMid1h == null || volumeMa1h == null || (enableMtf && macd1d == null)) {
            recordDiagnostic(config, current1h.getOpenTime(),
                    DiagnosticCode.INDICATOR_SERIES_MISSING,
                    "adx=" + (adx1h != null) + ", ema20=" + (ema20_1h != null) + ", rsi=" + (rsi1h != null)
                            + ", macd=" + (macd1h != null) + ", macdSignal=" + (macdSignal1h != null)
                            + ", bollMid=" + (bollMid1h != null) + ", volumeMa=" + (volumeMa1h != null)
                            + ", macd1d=" + (macd1d != null));
            return StrategySignal.HOLD;
        }
        if (Double.isNaN(adx1h[i1h]) || Double.isNaN(ema20_1h[i1h]) || Double.isNaN(rsi1h[i1h])
            || Double.isNaN(macd1h[i1h]) || Double.isNaN(macdSignal1h[i1h])
            || Double.isNaN(bollMid1h[i1h]) || Double.isNaN(volumeMa1h[i1h])
            || (enableMtf && Double.isNaN(macd1d[i1d]))) {
            recordDiagnostic(config, current1h.getOpenTime(),
                    DiagnosticCode.INDICATOR_VALUE_NOT_READY,
                    "adx=" + adx1h[i1h] + ", ema20=" + ema20_1h[i1h] + ", rsi=" + rsi1h[i1h]
                            + ", macd=" + macd1h[i1h] + ", macdSignal=" + macdSignal1h[i1h]
                            + ", bollMid=" + bollMid1h[i1h] + ", volumeMa=" + volumeMa1h[i1h]
                            + ", macd1d=" + (enableMtf ? macd1d[i1d] : "N/A"));
            return StrategySignal.HOLD;
        }

        // Publish snapshot so LiveSignalEvaluator / MarketSignalCache / analyzeMarket
        // see real rsi + a synthetic confidence score. Previously only ScoreBuyStrategy
        // set LiveSignalContext, so analyzeMarket displayed "NN=0.00 RSI=0.0" for
        // SOP_MTF_ADX strategies (strategy 315 etc.) even when evaluation ran fine.
        // score = ADX normalised (trend strength proxy) clamped to [0,1]; nnOutput
        // left at 0 because NN is a ScoreBuy-specific concept.
        double adxScore = Math.max(0.0, Math.min(1.0, adx1h[i1h] / 50.0));
        LiveSignalContext.set(adxScore, 0.0, rsi1h[i1h]);

        double adxEntry = getDouble(config, "adxEntryThreshold", 25.0);
        if (adx1h[i1h] <= adxEntry) {
            recordDiagnostic(config, current1h.getOpenTime(),
                    DiagnosticCode.ADX_BELOW_THRESHOLD,
                    "adx=" + adx1h[i1h] + ", threshold=" + adxEntry);
            return StrategySignal.HOLD;
        }

        int maPeriod1d = Math.max(20, getInt(config, "dailyMaPeriod", 50));
        int keyLevelLookback1h = Math.max(5, getInt(config, "keyLevelLookbackBars", 20));
        if ((enableMtf && i1d < maPeriod1d - 1) || i1h < keyLevelLookback1h) {
            recordDiagnostic(config, current1h.getOpenTime(),
                    DiagnosticCode.LOOKBACK_NOT_ENOUGH,
                    "1dIndex=" + (enableMtf ? i1d + "/" + (maPeriod1d - 1) : "SKIPPED")
                            + ", 1hIndex=" + i1h + "/" + keyLevelLookback1h);
            return StrategySignal.HOLD;
        }

        double close1h = current1h.getClosePrice().doubleValue();
        double prevClose1h = prev1h.getClosePrice().doubleValue();
        double volume1h = current1h.getVolume().doubleValue();

        double ma1d = Double.NaN;
        MdKline d1 = null;
        double macd1dValue = Double.NaN;
        boolean trendLong;
        boolean trendShort;
        if (enableMtf) {
            ma1d = sma(context, "1d", i1d - maPeriod1d + 1, i1d);
            d1 = context.getTimeframeKlines().get("1d").get(i1d);
            macd1dValue = macd1d[i1d];
            trendLong = d1.getClosePrice().doubleValue() > ma1d && macd1dValue > 0;
            trendShort = d1.getClosePrice().doubleValue() < ma1d && macd1dValue < 0;
        } else {
            // 關閉 MTF 時，改用 1h EMA20 + MACD 作為趨勢代理
            trendLong  = close1h > ema20_1h[i1h] && macd1h[i1h] > 0;
            trendShort = close1h < ema20_1h[i1h] && macd1h[i1h] < 0;
        }
        double rsiPullback = getDouble(config, "rsiPullbackThreshold", 40.0);
        double rsiReboundConfirm = getDouble(config, "rsiReboundConfirm", 50.0);
        double minRsiDelta = getDouble(config, "minRsiDelta", 3.0);
        int reboundLookbackBars = getInt(config, "reboundLookbackBars", 10);
        boolean requireCandleBreak = getBoolean(config, "requireCandleBreak", false);

        // #398 — publish trigger-condition snapshot (rsi / trend / macd state) for HOLD audit
        LiveSignalContext.putDetail("rsi_value", rsi1h[i1h]);
        LiveSignalContext.putDetail("rsi_pullback_threshold", rsiPullback);
        LiveSignalContext.putDetail("rsi_rebound_threshold", rsiReboundConfirm);
        LiveSignalContext.putDetail("trend_long", trendLong);
        LiveSignalContext.putDetail("trend_short", trendShort);
        LiveSignalContext.putDetail("macd_above_signal", macd1h[i1h] > macdSignal1h[i1h]);
        LiveSignalContext.putDetail("close_above_ema20", close1h > ema20_1h[i1h]);

        List<DiagnosticCode> matched = new ArrayList<DiagnosticCode>();

        // A: close > EMA20
        if (close1h > ema20_1h[i1h]) {
            matched.add(DiagnosticCode.EMA20_ABOVE);
        }
        // B: Pullback→Rebound（已升級為必要前提條件，不再計入打分訊號）
        // C: MACD 金叉
        if (macd1h[i1h - 1] <= macdSignal1h[i1h - 1] && macd1h[i1h] > macdSignal1h[i1h]) {
            matched.add(DiagnosticCode.MACD_GOLDEN_CROSS);
        }
        // D: 價格上穿布林中軌
        if (prevClose1h <= bollMid1h[i1h - 1] && close1h > bollMid1h[i1h]) {
            matched.add(DiagnosticCode.BOLL_MID_CROSS_UP);
        }
        // E: volume > MA(volume, 5)
        if (volume1h > volumeMa1h[i1h]) {
            matched.add(DiagnosticCode.VOLUME_ABOVE_MA);
        }

        int minSignals = getInt(config, "minSignals", 3);
        boolean shortOnly = getBoolean(config, "shortOnly", false);

        LiveSignalContext.putDetail("matched_signals", matched.size());
        LiveSignalContext.putDetail("required_signals", minSignals);

        if (!shortOnly && trendLong && matched.size() >= minSignals) {
            if (!detectReboundReady(rsi1h, i1h, rsiPullback, rsiReboundConfirm, minRsiDelta, reboundLookbackBars)) {
                recordDiagnostic(config, current1h.getOpenTime(),
                        DiagnosticCode.REBOUND_NOT_READY,
                        "pullbackThreshold=" + rsiPullback + ", reboundConfirm=" + rsiReboundConfirm
                                + ", lookbackBars=" + reboundLookbackBars);
                LiveSignalContext.putDetail("hold_reason", "rebound_not_ready");
                return StrategySignal.HOLD;
            }
            if (requireCandleBreak && close1h <= prev1h.getHighPrice().doubleValue()) {
                recordDiagnostic(config, current1h.getOpenTime(),
                        DiagnosticCode.CANDLE_BREAK_NOT_CONFIRMED,
                        "close=" + close1h + ", prevHigh=" + prev1h.getHighPrice().doubleValue());
                LiveSignalContext.putDetail("hold_reason", "candle_break_not_confirmed");
                return StrategySignal.HOLD;
            }
            if (passesRrLong(context, base, i1h, close1h, keyLevelLookback1h, getDouble(config, "minRR", 2.0))) {
                LiveSignalContext.putDetail("trigger_reason", "long_all_gates_passed");
                return StrategySignal.BUY;
            }
            recordDiagnostic(config, current1h.getOpenTime(),
                    DiagnosticCode.RISK_REWARD_NOT_ENOUGH,
                    buildRiskRewardMessage(context, base, i1h, close1h, keyLevelLookback1h, getDouble(config, "minRR", 2.0)));
            LiveSignalContext.putDetail("hold_reason", "long_rr_not_enough");
            return StrategySignal.HOLD;
        }

        if (!shortOnly && trendLong) {
            recordDiagnostic(config, current1h.getOpenTime(),
                    DiagnosticCode.LONG_SIGNALS_NOT_ENOUGH,
                    "matched=" + matched + ", required=" + minSignals);
            LiveSignalContext.putDetail("hold_reason", "long_signals_not_enough");
            return StrategySignal.HOLD;
        }

        if (trendShort && (getBoolean(config, "allowShort", false) || shortOnly)) {
            double rsiSellThreshold = getDouble(config, "rsiSellThreshold", 60.0);
            List<DiagnosticCode> shortMatched = new ArrayList<DiagnosticCode>();

            // A: close < EMA20
            if (close1h < ema20_1h[i1h]) {
                shortMatched.add(DiagnosticCode.EMA20_BELOW);
            }
            // B: RSI > rsiSellThreshold (超漲回落訊號)
            if (rsi1h[i1h] > rsiSellThreshold) {
                shortMatched.add(DiagnosticCode.RSI_OVERBOUGHT);
            }
            // C: MACD 死叉
            if (macd1h[i1h - 1] >= macdSignal1h[i1h - 1] && macd1h[i1h] < macdSignal1h[i1h]) {
                shortMatched.add(DiagnosticCode.MACD_DEATH_CROSS);
            }
            // D: 價格下穿布林中軌
            if (prevClose1h >= bollMid1h[i1h - 1] && close1h < bollMid1h[i1h]) {
                shortMatched.add(DiagnosticCode.BOLL_MID_CROSS_DOWN);
            }
            // E: volume > MA(volume, 5)
            if (volume1h > volumeMa1h[i1h]) {
                shortMatched.add(DiagnosticCode.VOLUME_ABOVE_MA);
            }

            if (shortMatched.size() >= minSignals) {
                double minRr = getDouble(config, "minRR", 2.0);
                boolean rrOk;
                // 若明確設定固定止損/止盈，用百分比計算 R:R（下跌創新低時 key-level reward ≈ 0，會誤判）
                if (config.containsKey("fixedStopLossPct") && config.containsKey("fixedTakeProfitPct")) {
                    double sl = getDouble(config, "fixedStopLossPct", 0.02);
                    double tp = getDouble(config, "fixedTakeProfitPct", 0.05);
                    rrOk = sl > 0 && (tp / sl) >= minRr;
                } else {
                    rrOk = passesRrShort(context, base, i1h, close1h, keyLevelLookback1h, minRr);
                }
                if (rrOk) {
                    LiveSignalContext.putDetail("trigger_reason", "short_all_gates_passed");
                    return StrategySignal.SELL;
                }
                recordDiagnostic(config, current1h.getOpenTime(),
                        DiagnosticCode.RISK_REWARD_NOT_ENOUGH,
                        buildRiskRewardShortMessage(context, base, i1h, close1h, keyLevelLookback1h, minRr));
                LiveSignalContext.putDetail("hold_reason", "short_rr_not_enough");
                return StrategySignal.HOLD;
            }
            recordDiagnostic(config, current1h.getOpenTime(),
                    DiagnosticCode.SHORT_SIGNALS_NOT_ENOUGH,
                    "shortMatched=" + shortMatched + ", required=" + minSignals);
            LiveSignalContext.putDetail("hold_reason", "short_signals_not_enough");
            LiveSignalContext.putDetail("short_matched_signals", shortMatched.size());
            return StrategySignal.HOLD;
        }

        if (trendShort) {
            recordDiagnostic(config, current1h.getOpenTime(),
                    DiagnosticCode.SHORT_TREND_NOT_SUPPORTED,
                    "日線空頭趨勢成立，但策略未啟用 allowShort");
            LiveSignalContext.putDetail("hold_reason", "short_not_allowed");
            return StrategySignal.HOLD;
        }

        recordDiagnostic(config, current1h.getOpenTime(),
                DiagnosticCode.TREND_FILTER_BLOCKED,
                "日線趨勢未通過多頭條件，close1d=" + (d1 != null ? d1.getClosePrice().doubleValue() : "N/A") + ", ma1d=" + ma1d + ", macd1d=" + macd1dValue);

        LiveSignalContext.putDetail("hold_reason", "trend_filter_blocked");
        return StrategySignal.HOLD;
    }

    private boolean passesRrShort(StrategyContext context, String timeframe, int i1h, double price, int lookback, double minRr) {
        double support = minLow(context, timeframe, i1h - lookback, i1h - 1);
        double resistance = maxHigh(context, timeframe, i1h - lookback, i1h - 1);
        double risk = resistance - price;
        double reward = price - support;
        return risk > 0.0 && reward > 0.0 && (reward / risk) >= minRr;
    }

    private String buildRiskRewardShortMessage(StrategyContext context, String timeframe, int i1h, double price, int lookback, double minRr) {
        double support = minLow(context, timeframe, i1h - lookback, i1h - 1);
        double resistance = maxHigh(context, timeframe, i1h - lookback, i1h - 1);
        double risk = resistance - price;
        double reward = price - support;
        double rr = risk > 0.0 ? reward / risk : Double.NaN;
        return "reward=" + reward + ", risk=" + risk + ", rr=" + rr + ", required=" + minRr;
    }

    private boolean passesRrLong(StrategyContext context, String timeframe, int i1h, double price, int lookback, double minRr) {
        double support = minLow(context, timeframe, i1h - lookback, i1h - 1);
        double resistance = maxHigh(context, timeframe, i1h - lookback, i1h - 1);
        double risk = price - support;
        double reward = resistance - price;
        return risk > 0.0 && reward > 0.0 && (reward / risk) >= minRr;
    }

    private String buildRiskRewardMessage(StrategyContext context, String timeframe, int i1h, double price, int lookback, double minRr) {
        double support = minLow(context, timeframe, i1h - lookback, i1h - 1);
        double resistance = maxHigh(context, timeframe, i1h - lookback, i1h - 1);
        double risk = price - support;
        double reward = resistance - price;
        double rr = risk > 0.0 ? reward / risk : Double.NaN;
        return "reward=" + reward + ", risk=" + risk + ", rr=" + rr + ", required=" + minRr;
    }

    private void recordDiagnostic(Map<String, Object> config, LocalDateTime time, DiagnosticCode code, String detail) {
        BacktestDiagnosticCollector collector = BacktestDiagnosticCollector.fromConfig(config);
        if (collector != null) {
            collector.record(code, time, detail);
        }
    }


    private double sma(StrategyContext context, String timeframe, int start, int end) {
        double sum = 0.0;
        int count = 0;
        int from = Math.max(0, start);
        List<MdKline> list = context.getTimeframeKlines().get(timeframe);
        if (list == null || list.isEmpty()) {
            return Double.NaN;
        }
        int to = Math.min(end, list.size() - 1);
        for (int i = from; i <= to; i++) {
            sum += list.get(i).getClosePrice().doubleValue();
            count++;
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private double maxHigh(StrategyContext context, String timeframe, int start, int end) {
        List<MdKline> list = context.getTimeframeKlines().get(timeframe);
        if (list == null || list.isEmpty()) {
            return Double.NaN;
        }
        int from = Math.max(0, start);
        int to = Math.min(end, list.size() - 1);
        double m = Double.NEGATIVE_INFINITY;
        for (int i = from; i <= to; i++) {
            m = Math.max(m, list.get(i).getHighPrice().doubleValue());
        }
        return m;
    }

    private double minLow(StrategyContext context, String timeframe, int start, int end) {
        List<MdKline> list = context.getTimeframeKlines().get(timeframe);
        if (list == null || list.isEmpty()) {
            return Double.NaN;
        }
        int from = Math.max(0, start);
        int to = Math.min(end, list.size() - 1);
        double m = Double.POSITIVE_INFINITY;
        for (int i = from; i <= to; i++) {
            m = Math.min(m, list.get(i).getLowPrice().doubleValue());
        }
        return m;
    }

    /**
     * 以 Window Scan 方式模擬 Pullback→Rebound 狀態機，無需跨 K 線持久化狀態。
     * 向前掃描 lookbackBars 根 K 線，找到完整的 PULLBACK→REBOUND_READY 序列即返回 true。
     *
     * 重置條件（任一成立即清除 sawPullback 和 reboundFound）：
     *   1. RSI < 30（跌太深，趨勢已壞）
     *   2. 找到 rebound 後 RSI 再次跌破 pullbackThreshold（假反彈後繼續跌）
     */
    private boolean detectReboundReady(double[] rsi, int currentIdx,
                                        double pullbackThreshold, double reboundConfirm,
                                        double minRsiDelta, int lookbackBars) {
        int start = Math.max(1, currentIdx - lookbackBars);
        boolean sawPullback = false;
        boolean reboundFound = false;

        for (int i = start; i < currentIdx; i++) {
            if (Double.isNaN(rsi[i])) continue;

            // 重置條件 1：RSI 跌破 30，整體趨勢失效
            if (rsi[i] < 30.0) {
                sawPullback = false;
                reboundFound = false;
                continue;
            }

            // 重置條件 2：找到 rebound 後 RSI 再次跌破 pullbackThreshold
            if (reboundFound && rsi[i] < pullbackThreshold) {
                sawPullback = true;
                reboundFound = false;
            }

            // 進入 PULLBACK 狀態
            if (!sawPullback && rsi[i] < pullbackThreshold) {
                sawPullback = true;
            }

            // PULLBACK → REBOUND_READY 轉換
            if (sawPullback && !reboundFound && rsi[i] > reboundConfirm) {
                double prevRsi = Double.isNaN(rsi[i - 1]) ? rsi[i] : rsi[i - 1];
                if ((rsi[i] - prevRsi) >= minRsiDelta) {
                    reboundFound = true;
                }
                // delta 不足（假反彈）→ 保持 sawPullback=true，繼續等待更大幅度回升
            }
        }

        return reboundFound;
    }

    private int getInt(Map<String, Object> config, String key, int defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private double getDouble(Map<String, Object> config, String key, double defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private boolean getBoolean(Map<String, Object> config, String key, boolean defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(text);
    }
}
