package com.agora.service.backtest;

import com.agora.model.MdKline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ScoreBuyV3 — 恐慌底部確認策略（Panic-Bottom Conviction Signal）
 *
 * <p>設計依據：2026-04-24 七天指標回測（BTCUSDT，164 個小時樣本）。
 * 原始 4/4 舊信號（divergence&gt;-0.2 + fund&lt;0 + ob&gt;-0.2 + ls&lt;1.0）
 * 在 Apr 17-18 $77K 頂部觸發，平均 24h 回報 -1.4%，勝率 15%。
 * 反向設計後（散戶進場 + 空頭回補 + 大戶等待），4/4 命中率 75%，
 * 平均 72h 回報 +4.38%，且全部發生在 $73.8K-$75.9K 真正底部區間。
 *
 * <h3>兩階段決策</h3>
 *
 * <p><b>Phase 1（K 線，回測與直播皆啟用）</b>：
 * <ol>
 *   <li>RSI &lt; rsiOversold（預設 35）— 超賣區</li>
 *   <li>收盤 &lt; 布林下軌 + 30% 帶寬 — 接近超賣下軌</li>
 *   <li>成交量 &gt; MA20 × volumeBreakoutMultiplier（預設 1.3）— 量能確認</li>
 *   <li>收盤 &lt; SMA200（requireBelowSma200=true 時）— 位於長期均線以下，確認為底部恐慌而非高位震盪</li>
 * </ol>
 *
 * <p><b>Phase 2（市場微觀結構，僅直播模式啟用）</b>：
 * <p>查詢 market_indicator_history 最近 2 小時資料，若無資料則自動判斷為回測模式並跳過。
 * <ul>
 *   <li>【必要錨點】ls_ratio &gt; lsThreshold（預設 1.0）— 散戶已開始抄底（多頭超越空頭）</li>
 *   <li>【加分 C1】funding_rate &gt; 0 — 空頭已回補完畢，不再有融資賣壓</li>
 *   <li>【加分 C2】divergence_score &lt; divScoreThreshold（預設 -0.25）
 *       — 大戶尚未確認（whale_buy_ratio 仍低），機構進場前夕</li>
 *   <li>【加分 C3】fear_greed &lt; fearGreedThreshold（預設 35）— 市場仍在恐懼區</li>
 * </ul>
 * 觸發：ls &gt; lsThreshold AND 加分條件 ≥ minAdditionalGates（預設 1）。
 *
 * <h3>回測語義</h3>
 * <p>3 年回測測試 Phase 1（K 線條件）的底部信號品質，是 V3 直播績效的理論上限。
 * Phase 2 在直播模式會進一步過濾，理論上提升精準度但降低頻率。
 * 信號觸發時透過 {@link LiveSignalContext} 暴露 convictionScore 作為 nnOutput 代理。
 *
 * <h3>Config keys</h3>
 * <ul>
 *   <li>{@code rsiOversold}               預設 35.0</li>
 *   <li>{@code rsiOverbought}              預設 75.0（次級 SELL trigger）</li>
 *   <li>{@code volumeBreakoutMultiplier}   預設 1.3</li>
 *   <li>{@code requireBelowSma200}         預設 true</li>
 *   <li>{@code minWarmupBars}              預設 200</li>
 *   <li>{@code lsThreshold}               預設 1.0</li>
 *   <li>{@code divScoreThreshold}          預設 -0.25</li>
 *   <li>{@code fearGreedThreshold}         預設 35.0</li>
 *   <li>{@code minAdditionalGates}         預設 1（需滿足加分條件最低數）</li>
 * </ul>
 */
@Slf4j
@Component
public class ScoreBuyV3Strategy implements Strategy {

    public static final String TYPE = "SCORE_BUY_V3";

    private final JdbcTemplate jdbc;

    @Autowired
    public ScoreBuyV3Strategy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String getType() { return TYPE; }

    @Override
    public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
        int index = context.getIndex();
        List<MdKline> klines = context.getKlines();

        // ── 1. Warmup guard ───────────────────────────────────────────────────
        int minBars = getInt(config, "minWarmupBars", 200);
        if (index < minBars || index < 2 || klines == null || index >= klines.size()) {
            return StrategySignal.HOLD;
        }

        // ── 2. Indicator availability ─────────────────────────────────────────
        double[] rsi    = context.getIndicators().get("rsi");
        double[] bollMid = context.getIndicators().get("bollMid");
        double[] bollLow = context.getIndicators().get("bollLow");
        double[] volMa20 = context.getIndicators().get("volumeMa20");
        double[] adx    = context.getIndicators().get("adx");
        if (!valid(rsi, index) || !valid(bollMid, index)
                || !valid(bollLow, index) || !valid(volMa20, index)) {
            return StrategySignal.HOLD;
        }

        double close  = context.getCurrent().getClosePrice().doubleValue();
        double volume = context.getCurrent().getVolume().doubleValue();

        // ── 3. Phase 1: K-line pre-conditions (always, backtest + live) ───────
        double rsiOversold = getDouble(config, "rsiOversold", 35.0);
        double volMult     = getDouble(config, "volumeBreakoutMultiplier", 1.3);

        boolean rsiOk = rsi[index] < rsiOversold;
        boolean bbOk  = close < bollLow[index] + (bollMid[index] - bollLow[index]) * 0.3;
        boolean volOk = volMa20[index] > 0 && volume > volMa20[index] * volMult;

        if (!(rsiOk && bbOk && volOk)) {
            // Secondary SELL — RSI 超買時發 SELL 結束持倉
            double rsiOverbought = getDouble(config, "rsiOverbought", 75.0);
            if (rsi[index] > rsiOverbought) return StrategySignal.SELL;
            return StrategySignal.HOLD;
        }

        // ── 3b. #265 ADX Regime Gate — skip entries in choppy/sideways markets ─
        // ADX < adxMinTrend AND CI > ciMaxChoppy → too choppy, skip
        if (getBool(config, "adxRegimeGateEnabled", true)) {
            double adxMin = getDouble(config, "adxMinTrend", 20.0);
            if (valid(adx, index) && adx[index] < adxMin) {
                int ciPeriod = getInt(config, "choppinessPeriod", 14);
                double ci = computeChoppinessIndex(klines, index, ciPeriod);
                double ciMax = getDouble(config, "ciMaxChoppy", 61.8);
                if (!Double.isNaN(ci) && ci > ciMax) {
                    log.debug("[ScoreBuyV3] HOLD: ADX={} < {} AND CI={} > {} (choppy market)",
                            String.format("%.1f", adx[index]), adxMin,
                            String.format("%.1f", ci), ciMax);
                    return StrategySignal.HOLD;
                }
            }
        }

        // ── 3c. #266 Williams %R oversold filter ─────────────────────────────
        // Optional: require W%R in oversold zone as pullback entry confirmation
        if (getBool(config, "requireWilliamsROversold", false)) {
            int wrPeriod = getInt(config, "williamsRPeriod", 14);
            double wr = computeWilliamsR(klines, index, wrPeriod);
            double wrThreshold = getDouble(config, "williamsROversoldThreshold", -80.0);
            if (!Double.isNaN(wr) && wr > wrThreshold) {
                log.debug("[ScoreBuyV3] HOLD: W%R={} not oversold (> {})", String.format("%.1f", wr), wrThreshold);
                return StrategySignal.HOLD;
            }
        }

        // ── 4. SMA — value zone filter (close must be below long-term trend) ────
        //     sma200Bars defaults to 200 for daily bars; set to 4800 for 1h bars
        //     (200 days × 24h) or 1200 for 4h bars (200 days × 6h) via config.
        if (getBool(config, "requireBelowSma200", true)) {
            int smaBars = getInt(config, "sma200Bars", 200);
            double sma200 = computeSma(klines, index, smaBars);
            if (close >= sma200) {
                log.debug("[ScoreBuyV3] HOLD: close={} >= SMA({})={} (not in value zone)",
                        (long) close, smaBars, (long) sma200);
                return StrategySignal.HOLD;
            }
        }

        // ── 5. Phase 2: Bottom Conviction Signal (live-mode only) ────────────
        //     Detects live vs backtest by checking if indicator_history has
        //     recent data for this bar's timestamp window.
        String symbol   = context.getCurrent().getSymbol();
        LocalDateTime barTime = context.getCurrent().getOpenTime();
        ConvictionResult conviction = queryBottomConviction(symbol, barTime, config);

        if (conviction.hasLiveData) {
            // Anchor condition: ls_ratio > 1.0 (retail buying the dip)
            if (!conviction.lsBuyersDominate) {
                log.debug("[ScoreBuyV3] HOLD: ls_ratio={} not dominant (retail not buying yet)",
                        conviction.lsRatio);
                return StrategySignal.HOLD;
            }
            // #268: CCI momentum confirmation gate (optional)
            int cciGate = 0;
            if (getBool(config, "cciGateEnabled", false)) {
                int cciPeriod = getInt(config, "cciPeriod", 20);
                double cciThreshold = getDouble(config, "cciOversoldThreshold", -100.0);
                double cci = computeCci(klines, index, cciPeriod);
                if (!Double.isNaN(cci) && cci < cciThreshold) cciGate = 1;
            }

            // Require at least N additional conviction gates
            int addScore = conviction.fundingCovered + conviction.whaleWaiting + conviction.fearZone + cciGate;
            int maxGates = 3 + (getBool(config, "cciGateEnabled", false) ? 1 : 0);
            int minGates = getInt(config, "minAdditionalGates", 1);
            if (addScore < minGates) {
                log.debug("[ScoreBuyV3] HOLD: ls ok but addScore={}/{} < minGates={}",
                        addScore, maxGates, minGates);
                return StrategySignal.HOLD;
            }
            // #269: conviction score denominator matches actual gate count; cap at 1.0
            double convictionScore = Math.min(1.0, (1.0 + addScore) / (1.0 + maxGates));
            LiveSignalContext.set(convictionScore, convictionScore, rsi[index]);
            log.info("[ScoreBuyV3] LIVE BUY {} ls={} funding={} div={} fg={} add={}/3",
                    symbol, conviction.lsRatio, conviction.fundingRate,
                    conviction.divScore, conviction.fearGreed, addScore);
        } else {
            // Backtest mode: Phase 2 not available, expose RSI proxy
            double klineConviction = 1.0 - (rsi[index] / rsiOversold);   // 0..1, higher = more oversold
            LiveSignalContext.set(klineConviction, klineConviction, rsi[index]);
            log.debug("[ScoreBuyV3] Backtest mode — Phase 2 skipped");
        }

        log.info("[ScoreBuyV3] BUY {} bar={} rsi={} close={} sma200check={}",
                symbol, index, String.format("%.1f", rsi[index]), (long) close,
                getBool(config, "requireBelowSma200", true));
        return StrategySignal.BUY;
    }

    // ─── Phase 2: Bottom Conviction from market_indicator_history ─────────────

    /**
     * Queries the most recent indicator snapshot within ±2h of {@code barTime}.
     * Returns {@link ConvictionResult#BACKTEST_MODE} when no data is found
     * (typical for historical backtest bars pre-dating the collector launch).
     */
    private ConvictionResult queryBottomConviction(String symbol, LocalDateTime barTime,
                                                    Map<String, Object> config) {
        LocalDateTime from = barTime.minusHours(2);
        LocalDateTime to   = barTime.plusHours(1);
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT indicator, value FROM market_indicator_history " +
                    "WHERE symbol = ? AND captured_at BETWEEN ? AND ? " +
                    "ORDER BY captured_at DESC LIMIT 20",
                    symbol, from, to);

            if (rows.isEmpty()) return ConvictionResult.BACKTEST_MODE;

            // Build latest-value map (first occurrence per indicator = most recent)
            Map<String, Double> latest = new HashMap<>();
            for (Map<String, Object> row : rows) {
                String ind = (String) row.get("indicator");
                Object val = row.get("value");
                if (val != null) latest.putIfAbsent(ind, ((Number) val).doubleValue());
            }

            Double whale = latest.get("whale_buy_ratio");
            Double fg    = latest.get("fear_greed");
            Double fund  = latest.get("funding_rate");
            Double ls    = latest.get("long_short_ratio");

            // Need at minimum whale + fg + ls to compute a useful signal
            if (whale == null || fg == null || ls == null) return ConvictionResult.BACKTEST_MODE;

            // divergence_score = whale_buy_ratio - (1 - fg_normalized)
            double divScore     = whale - (1.0 - fg / 100.0);
            double lsThreshold  = getDouble(config, "lsThreshold", 1.0);
            double divThreshold = getDouble(config, "divScoreThreshold", -0.25);
            double fgThreshold  = getDouble(config, "fearGreedThreshold", 35.0);

            boolean lsDominates  = ls > lsThreshold;
            boolean fundCovered  = fund != null && fund > 0;
            boolean whaleWaiting = divScore < divThreshold;
            boolean inFear       = fg < fgThreshold;

            return new ConvictionResult(
                    true, ls, fund != null ? fund : 0.0, divScore, fg,
                    lsDominates,
                    fundCovered  ? 1 : 0,
                    whaleWaiting ? 1 : 0,
                    inFear       ? 1 : 0);

        } catch (Exception e) {
            log.debug("[ScoreBuyV3] conviction query failed — treating as backtest: {}", e.getMessage());
            return ConvictionResult.BACKTEST_MODE;
        }
    }

    // ─── Technical indicator helpers ─────────────────────────────────────────

    /**
     * #265 Choppiness Index: 100×log10(ΣTR(n) / (HH(n)-LL(n))) / log10(n)
     * Returns NaN if insufficient data. CI > 61.8 = choppy, < 38.2 = trending.
     */
    private double computeChoppinessIndex(List<MdKline> klines, int index, int period) {
        if (index < period) return Double.NaN;
        double sumTr = 0, highestHigh = Double.MIN_VALUE, lowestLow = Double.MAX_VALUE;
        for (int i = index - period + 1; i <= index; i++) {
            MdKline cur = klines.get(i);
            double h = cur.getHighPrice().doubleValue();
            double l = cur.getLowPrice().doubleValue();
            double c = cur.getClosePrice().doubleValue();
            double prevC = i > 0 ? klines.get(i - 1).getClosePrice().doubleValue() : c;
            double tr = Math.max(h - l, Math.max(Math.abs(h - prevC), Math.abs(l - prevC)));
            sumTr += tr;
            highestHigh = Math.max(highestHigh, h);
            lowestLow   = Math.min(lowestLow, l);
        }
        double range = highestHigh - lowestLow;
        if (range <= 0 || sumTr <= 0) return Double.NaN;
        return 100.0 * Math.log10(sumTr / range) / Math.log10(period);
    }

    /**
     * #266 Williams %R: (HH(n) - Close) / (HH(n) - LL(n)) × (-100)
     * Range -100..0. < -80 = oversold, > -20 = overbought.
     */
    private double computeWilliamsR(List<MdKline> klines, int index, int period) {
        if (index < period - 1) return Double.NaN;
        double highestHigh = Double.MIN_VALUE, lowestLow = Double.MAX_VALUE;
        for (int i = index - period + 1; i <= index; i++) {
            highestHigh = Math.max(highestHigh, klines.get(i).getHighPrice().doubleValue());
            lowestLow   = Math.min(lowestLow,  klines.get(i).getLowPrice().doubleValue());
        }
        double range = highestHigh - lowestLow;
        if (range <= 0) return Double.NaN;
        double close = klines.get(index).getClosePrice().doubleValue();
        return -100.0 * (highestHigh - close) / range;
    }

    /**
     * #268 CCI: (TypicalPrice - SMA(TP, n)) / (0.015 × MeanDeviation)
     * < -100 = oversold momentum reversal signal.
     */
    private double computeCci(List<MdKline> klines, int index, int period) {
        if (index < period - 1) return Double.NaN;
        double[] tp = new double[period];
        for (int i = 0; i < period; i++) {
            MdKline k = klines.get(index - period + 1 + i);
            tp[i] = (k.getHighPrice().doubleValue() + k.getLowPrice().doubleValue()
                    + k.getClosePrice().doubleValue()) / 3.0;
        }
        double sma = 0;
        for (double v : tp) sma += v;
        sma /= period;
        double meanDev = 0;
        for (double v : tp) meanDev += Math.abs(v - sma);
        meanDev /= period;
        if (meanDev == 0) return Double.NaN;
        return (tp[period - 1] - sma) / (0.015 * meanDev);
    }

    // ─── SMA200 helper ────────────────────────────────────────────────────────

    /** Computes simple moving average of close prices over the last {@code period} bars. */
    private double computeSma(List<MdKline> klines, int index, int period) {
        int start = Math.max(0, index - period + 1);
        double sum = 0;
        int cnt = 0;
        for (int i = start; i <= index; i++) {
            sum += klines.get(i).getClosePrice().doubleValue();
            cnt++;
        }
        return cnt > 0 ? sum / cnt : 0;
    }

    // ─── Config helpers ───────────────────────────────────────────────────────

    private boolean valid(double[] arr, int idx) {
        return arr != null && idx < arr.length && !Double.isNaN(arr[idx]);
    }
    private int getInt(Map<String, Object> config, String key, int def) {
        Object v = config.get(key);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }
    private double getDouble(Map<String, Object> config, String key, double def) {
        Object v = config.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }
    private boolean getBool(Map<String, Object> config, String key, boolean def) {
        Object v = config.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number)  return ((Number) v).intValue() != 0;
        return def;
    }

    // ─── ConvictionResult record ──────────────────────────────────────────────

    private record ConvictionResult(
            boolean hasLiveData,
            double lsRatio,
            double fundingRate,
            double divScore,
            double fearGreed,
            boolean lsBuyersDominate,
            int fundingCovered,   // 1 if fund > 0
            int whaleWaiting,     // 1 if divScore < threshold
            int fearZone          // 1 if fg < threshold
    ) {
        static final ConvictionResult BACKTEST_MODE =
                new ConvictionResult(false, 0, 0, 0, 0, false, 0, 0, 0);
    }
}
