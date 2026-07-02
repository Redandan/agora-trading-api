package com.agora.service.backtest;

import com.agora.model.MdKline;
import com.agora.service.ml.MlTrainingOrchestrator;
import com.agora.service.ml.ScoreBuyMlFeatureSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ScoreBuyV2 — Pine-script SCORE_BUY with its handcrafted sigmoid-NN replaced
 * by the currently PROMOTED HeatWave ML model (e.g. signal_scorer v13 with
 * validated +31.4pp 90-day holdout edge).
 *
 * <p>2026-07 TradingView parity mode: strategy #485 is the production SCORE_BUY
 * path, but TradingView's Pine script does not include the HeatWave ML gate.
 * By default this class now delegates to {@link ScoreBuyStrategy} with the
 * TradingView indicator parameters. Set {@code tradingViewParityMode=false}
 * only for explicit legacy ML-gated experiments.
 *
 * <p>PineScript 原版有 online-learning NN(gradient descent 持續更新權重);
 * V1 Java port 卻用**寫死權重**的 sigmoid,導致 nnOutput 幾乎無法 &gt; 0.7。
 * 5y BTC 1d backtest 0-3 筆且全 SL。 V2 用 MlTrainingOrchestrator.predictOne
 * 直接呼叫 PROMOTED 模型當 scorer,回到「真的 AI 評分」的初衷。
 *
 * <p><b>決策流程</b>:
 * <ol>
 *   <li>Warmup + indicator 可用性</li>
 *   <li>Dip 前置條件(必要但不充分): RSI &lt; rsiOversold(預設 35) + nearLowerBB +
 *       volumeBreakout — 若任一 fail 不花 ML 配額直接 HOLD</li>
 *   <li>Build V047+V049 features(與 vw_signal_training_v5_dedup 訓練欄位對齊)</li>
 *   <li>Call {@code orchestrator.predictOne} 取 p_win</li>
 *   <li>p_win ≥ buyThreshold(預設 0.7) → BUY;否則 HOLD</li>
 * </ol>
 *
 * <p><b>Backtest 相容性</b>: HeatWave ML_PREDICT_ROW 是 stateless,可對任何歷史
 * feature vector 打分。Backtest 使用此策略 = 真實 ML-gated 績效,不會 drift。
 *
 * <p><b>失敗時絕不誤 BUY</b>: 查無 PROMOTED / predict 例外 / 無 p_win 欄位 →
 * 一律回 HOLD。安全第一。
 *
 * <p><b>Config keys</b>:
 * <ul>
 *   <li>{@code mlModelName} 預設 "signal_scorer"</li>
 *   <li>{@code buyThreshold} 預設 0.7 (ML p_win 閾值,非原 PineScript 的 sigmoid 輸出)</li>
 *   <li>{@code rsiOversold} 預設 35</li>
 *   <li>{@code volumeBreakoutMultiplier} 預設 1.3</li>
 *   <li>{@code rsiOverbought} 預設 75 (次級 SELL)</li>
 *   <li>{@code minWarmupBars} 預設 200</li>
 * </ul>
 */
@Slf4j
@Component
public class ScoreBuyV2Strategy implements Strategy {

    public static final String TYPE = "SCORE_BUY_V2";

    private static final long PROMOTED_CACHE_TTL_MS = 60_000;

    private final JdbcTemplate jdbc;
    private final MlTrainingOrchestrator orchestrator;
    private final ObjectMapper objectMapper;
    private final ScoreBuyStrategy tradingViewStrategy;

    private final AtomicReference<PromotedRef> cachedPromoted = new AtomicReference<>(null);

    @Autowired
    public ScoreBuyV2Strategy(JdbcTemplate jdbc,
                               MlTrainingOrchestrator orchestrator,
                               ObjectMapper objectMapper,
                               ScoreBuyStrategy tradingViewStrategy) {
        this.jdbc = jdbc;
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
        this.tradingViewStrategy = tradingViewStrategy;
    }

    @Override
    public String getType() { return TYPE; }

    /**
     * #450 Phase 3 — SCORE_BUY_V2 adjustExit: panic-bottom recovery exit logic.
     *
     * <p>Strategy thesis 是 panic-bottom rally:RSI<35 + F&G<25 + 量爆觸發。
     * Exit 應 mirror entry signal 弱化:當市場從 panic 回歸 normal,thesis 完成。
     *
     * <p>Phase 3 minimal 規則(F&G access 需 ctx 擴充,留 Phase 3.5):
     * <ol>
     *   <li><b>TIME_DECAY_30D_FORCE</b>:1d strategy maxHold 720h(30d),持倉 ≥ 25 天 → tight TP 收尾</li>
     *   <li><b>TIME_DECAY_30D_HARD</b>:持倉 ≥ 30 天 → forceClose</li>
     *   <li><b>TRAIL_SL_BE</b>:TP progress ≥ 50% → trail SL 到 BE+</li>
     * </ol>
     */
    @Override
    public java.util.Optional<ExitAdjustment> adjustExit(
            StrategyContext context, OpenPositionView pos, Map<String, Object> config) {
        if (!"LONG".equals(pos.side())) return java.util.Optional.empty();
        if (pos.entryPrice() == null || pos.currentPrice() == null) return java.util.Optional.empty();

        // ScoreBuyV2 通常 1d strategy with maxHoldingHours 720(30d),用更長 time decay
        long maxHold = getInt(config, "maxHoldingHours", 720);
        long timeDecaySoft = (long) (maxHold * 0.83);  // 25/30 = ~83%
        long timeDecayHard = maxHold;
        double targetTpPct = getDouble(config, "fixedTakeProfitPct", 0.20);

        // Rule 1: TIME_DECAY hard force close at maxHold
        if (pos.ageHours() >= timeDecayHard) {
            return java.util.Optional.of(ExitAdjustment.forceClose(
                "MaxHold reached (" + pos.ageHours() + "h >= " + timeDecayHard + "h)",
                "TIME_DECAY_HARD_FORCE"
            ));
        }

        // Rule 2: TIME_DECAY soft tighten TP
        if (pos.ageHours() >= timeDecaySoft) {
            java.math.BigDecimal newTp = pos.currentPrice()
                    .multiply(java.math.BigDecimal.valueOf(1.005))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            return java.util.Optional.of(ExitAdjustment.tightenTp(
                newTp,
                String.format("Late hold (%dh >= %dh soft cutoff), tighten TP",
                        pos.ageHours(), timeDecaySoft),
                "TIME_DECAY_SOFT"
            ));
        }

        // Rule 3: TRAIL_SL_BE — TP progress ≥ 50%
        double tpProgress = pos.tpProgressPct(targetTpPct);
        if (tpProgress >= 0.5) {
            java.math.BigDecimal newSl = pos.entryPrice()
                    .multiply(java.math.BigDecimal.valueOf(1.005))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            if (pos.currentSl() == null || newSl.compareTo(pos.currentSl()) > 0) {
                return java.util.Optional.of(ExitAdjustment.trailingSl(
                    newSl,
                    String.format("TP progress %.0f%%, trail SL to BE+", tpProgress * 100),
                    "TRAIL_SL_BE"
                ));
            }
        }

        return java.util.Optional.empty();
    }

    @Override
    public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
        if (getBoolean(config, "tradingViewParityMode", true)) {
            return tradingViewStrategy.evaluate(context, tradingViewParityConfig(config));
        }

        int index = context.getIndex();
        List<MdKline> klines = context.getKlines();

        // 1. Warmup
        int minBars = getInt(config, "minWarmupBars", 200);
        if (index < minBars || index < 2 || klines == null || index >= klines.size()) {
            LiveSignalContext.putDetail("hold_reason", "warmup");
            return StrategySignal.HOLD;
        }

        // 2. Indicator availability (BacktestEngine 預先算好 rsi/bollMid/bollLow/volumeMa20)
        double[] rsi = context.getIndicators().get("rsi");
        double[] bollMid = context.getIndicators().get("bollMid");
        double[] bollLow = context.getIndicators().get("bollLow");
        double[] volMa20 = context.getIndicators().get("volumeMa20");
        if (!validIndicator(rsi, index) || !validIndicator(bollMid, index)
                || !validIndicator(bollLow, index) || !validIndicator(volMa20, index)) {
            LiveSignalContext.putDetail("hold_reason", "indicator_unavailable");
            return StrategySignal.HOLD;
        }

        double close = context.getCurrent().getClosePrice().doubleValue();
        double volume = context.getCurrent().getVolume().doubleValue();

        // 3. Dip 前置條件(必要但不充分)— 不滿足就不浪費 ML 配額
        double rsiOversold = getDouble(config, "rsiOversold", 35.0);
        double volMult = getDouble(config, "volumeBreakoutMultiplier", 1.3);
        boolean rsiOk = rsi[index] < rsiOversold;
        boolean bbOk = close < bollLow[index] + (bollMid[index] - bollLow[index]) * 0.3;
        boolean volOk = volMa20[index] > 0 && volume > volMa20[index] * volMult;
        // #398 — surface dip-gate state for SIGNAL_EVAL audit
        LiveSignalContext.putDetail("rsi_value", rsi[index]);
        LiveSignalContext.putDetail("rsi_oversold_threshold", rsiOversold);
        LiveSignalContext.putDetail("gate_rsi", rsiOk);
        LiveSignalContext.putDetail("gate_bb_lower", bbOk);
        LiveSignalContext.putDetail("gate_volume", volOk);
        if (!(rsiOk && bbOk && volOk)) {
            // Secondary SELL — RSI 超買時要結束持倉
            double rsiOverbought = getDouble(config, "rsiOverbought", 75.0);
            if (rsi[index] > rsiOverbought) {
                LiveSignalContext.putDetail("trigger_reason", "rsi_overbought_sell");
                return StrategySignal.SELL;
            }
            LiveSignalContext.putDetail("hold_reason", "dip_gate_failed");
            return StrategySignal.HOLD;
        }

        // 4. Build ML features (與 MlInferenceLogger/vw_signal_training_v5_dedup 對齊)
        // 5. Fetch PROMOTED model
        String modelName = (String) config.getOrDefault("mlModelName", "signal_scorer");
        PromotedRef ref = getPromotedRef(modelName);
        if (ref == null) {
            log.debug("[ScoreBuyV2] no PROMOTED model for '{}' — fallback HOLD", modelName);
            LiveSignalContext.putDetail("hold_reason", "no_promoted_model");
            return StrategySignal.HOLD;
        }

        Map<String, Object> features = ScoreBuyMlFeatureSupport.alignToTrainedFeatures(
                buildFeatures(context, config), ref.trainedFeatures);

        // 6. Predict
        Double pWin;
        try {
            String predJson = orchestrator.predictOne(ref.heatwaveHandle, features);
            pWin = extractPWin(predJson);
        } catch (Exception e) {
            log.warn("[ScoreBuyV2] predict failed v{}: {}", ref.modelVersionId, e.getMessage());
            LiveSignalContext.putDetail("hold_reason", "ml_predict_exception");
            return StrategySignal.HOLD;
        }
        if (pWin == null) {
            log.warn("[ScoreBuyV2] no p_win in predict response — HOLD");
            LiveSignalContext.putDetail("hold_reason", "ml_predict_no_pwin");
            return StrategySignal.HOLD;
        }

        // 7. Decision
        double threshold = getDouble(config, "buyThreshold", 0.7);

        // Publish for LiveSignalEvaluator / ml_inference_log — expose p_win as nnOutput
        LiveSignalContext.set(pWin, pWin, rsi[index]);
        LiveSignalContext.putDetail("p_win", pWin);
        LiveSignalContext.putDetail("buy_threshold", threshold);

        if (pWin >= threshold) {
            log.info("[ScoreBuyV2] BUY {} @ bar {} p_win={} >= threshold={}",
                    context.getCurrent().getSymbol(), index, pWin, threshold);
            LiveSignalContext.putDetail("trigger_reason", "p_win_above_threshold");
            return StrategySignal.BUY;
        }
        LiveSignalContext.putDetail("hold_reason", "p_win_below_threshold");
        return StrategySignal.HOLD;
    }

    // ─── helpers ────────────────────────────────────────────

    private Map<String, Object> tradingViewParityConfig(Map<String, Object> config) {
        Map<String, Object> tv = new HashMap<>(config);
        tv.put("shortLookbackBars", 20);
        tv.put("medLookbackBars", 63);
        tv.put("yearLookbackBars", 252);
        tv.put("rsiOversold", 40.0);
        tv.put("rsiOverbought", 70.0);
        tv.put("buyThreshold", 0.8);
        tv.put("volumeBreakoutMultiplier", 1.5);
        tv.put("scoreScale", 8.0);
        tv.put("scoreShift", 4.0);
        tv.put("allowMacdAsLowProxy", false);
        tv.put("requireAboveSma200", false);
        return tv;
    }

    private Map<String, Object> buildFeatures(StrategyContext context, Map<String, Object> config) {
        Map<String, Object> f = new HashMap<>();
        int index = context.getIndex();
        List<MdKline> klines = context.getKlines();
        MdKline bar = klines.get(index);
        String symbol = bar.getSymbol();
        String intervalCode = (String) config.getOrDefault("runIntervalCode", bar.getIntervalCode());

        // Static / categorical
        long strategyId = longFromConfig(config, "__strategyId", 0L);
        f.put("strategy_id", strategyId);
        f.put("is_short", 0);
        f.put("is_btc", "BTCUSDT".equalsIgnoreCase(symbol) ? 1 : 0);
        f.put("is_1h", "1h".equalsIgnoreCase(intervalCode) ? 1 : 0);
        f.put("entry_price", bar.getClosePrice().doubleValue());

        LocalDateTime t = bar.getOpenTime();
        f.put("hour_of_day", t.getHour());
        int javaDow = t.getDayOfWeek().getValue();
        int mysqlDow = (javaDow == 7) ? 1 : javaDow + 1;
        f.put("day_of_week", mysqlDow);

        // V047 + V049 (HTF V050 stays null — backtest engine doesn't load HTF here)
        Map<String, Double> snap = EntryFeatureSnapshot.compute(klines, index);
        for (Map.Entry<String, Double> e : snap.entrySet()) {
            if (e.getValue() != null) f.put(e.getKey(), e.getValue());
        }
        // HW ML003011: must have all trained columns; backfill missing with null
        for (String k : EntryFeatureSnapshot.ALL_FEATURE_KEYS) f.putIfAbsent(k, null);
        for (String k : EntryFeatureSnapshot.STATIC_FEATURE_KEYS) f.putIfAbsent(k, null);
        ScoreBuyMlFeatureSupport.appendPromotedModelMarketIndicatorAliases(jdbc, f, symbol);
        return f;
    }

    private PromotedRef getPromotedRef(String modelName) {
        PromotedRef cur = cachedPromoted.get();
        long now = System.currentTimeMillis();
        if (cur != null && modelName.equals(cur.modelName)
                && (now - cur.cachedAtMs) < PROMOTED_CACHE_TTL_MS) {
            return cur.modelVersionId == 0 ? null : cur;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, heatwave_handle, feature_importance_json FROM ml_model_registry "
                            + "WHERE model_name = ? AND status = 'PROMOTED' LIMIT 1",
                    modelName);
            if (rows.isEmpty()) {
                cachedPromoted.set(new PromotedRef(modelName, 0, null, List.of(), now));
                return null;
            }
            long id = ((Number) rows.get(0).get("id")).longValue();
            String handle = (String) rows.get(0).get("heatwave_handle");
            if (handle == null || handle.isBlank()) {
                cachedPromoted.set(new PromotedRef(modelName, 0, null, List.of(), now));
                return null;
            }
            try { orchestrator.loadModel(handle); } catch (Exception ignored) {}
            List<String> trainedFeatures = ScoreBuyMlFeatureSupport.parseFeatureImportanceKeys(
                    objectMapper, rows.get(0).get("feature_importance_json"));
            PromotedRef fresh = new PromotedRef(modelName, id, handle, trainedFeatures, now);
            cachedPromoted.set(fresh);
            return fresh;
        } catch (Exception e) {
            log.warn("[ScoreBuyV2] PROMOTED lookup failed: {}", e.getMessage());
            cachedPromoted.set(new PromotedRef(modelName, 0, null, List.of(), now));
            return null;
        }
    }

    private Double extractPWin(String predJson) {
        try {
            JsonNode root = objectMapper.readTree(predJson);
            JsonNode probs = root.path("ml_results").path("probabilities");
            if (probs.isMissingNode() || probs.isNull()) probs = root.path("probabilities");
            if (probs.isMissingNode() || probs.isNull()) return null;
            JsonNode p1 = probs.get("1");
            return p1 == null ? null : p1.asDouble();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean validIndicator(double[] arr, int idx) {
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
    private boolean getBoolean(Map<String, Object> config, String key, boolean def) {
        Object v = config.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        if (v instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s);
        return def;
    }
    private long longFromConfig(Map<String, Object> config, String key, long def) {
        Object v = config.get(key);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    private record PromotedRef(String modelName, long modelVersionId,
                                String heatwaveHandle, List<String> trainedFeatures, long cachedAtMs) {}
}
