package com.agora.service.backtest;

import com.agora.config.OkxTradingProperties;
import com.agora.metrics.TradingMetrics;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.BtStrategyService;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.market.FearGreedService;
import com.agora.service.market.WhaleFlowService;
import com.agora.service.trading.PostTradeReviewService;
import com.agora.service.ai.AiStrategyDiscoveryService;
import com.agora.service.trading.DailyLossGuard;
import com.agora.service.trading.LongAiFilter;
import com.agora.service.trading.ShortAiFilter;
import com.agora.service.trading.PositionSizingService;
import com.agora.service.trading.TradeQualityEngine;
import com.agora.service.trading.TradeResult;
import com.agora.service.trading.TradingService;
import com.agora.service.trading.ExposureOptimizer;
import com.agora.service.trading.TradingSignalSourcePolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 即時訊號評估服務。
 *
 * <p>由 {@link KlineClosedEventListener}（WS 收盤即時觸發）與
 * {@link LiveSignalScheduler}（每小時 :05 fallback）呼叫。</p>
 *
 * <p>流程：
 * <ol>
 *   <li>讀取所有 enabled 策略（findByEnabled — 不掃全表）</li>
 *   <li>從 DB 載入足夠的 K 線（warmup + yearLookback）</li>
 *   <li>呼叫 BacktestEngine.buildIndicators() 建立技術指標</li>
 *   <li>建立 StrategyContext，評估最後一根 bar</li>
 *   <li>BUY 訊號 → 去重 → 存 BtLiveSignal → 發 TG 通知</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveSignalEvaluator {

    /** SMA200 warmup 需要 200 根，再加 10 根緩衝 */
    private static final int WARMUP_BARS = 210;
    static final String ENTRY_DEDUP_OPEN_EXPOSURE_SCOPE_KEY = "entryDedupOpenExposureScope";
    static final String ENTRY_DEDUP_SCOPE_ALL_OPEN_ROWS = "ALL_OPEN_ROWS";
    static final String ENTRY_DEDUP_SCOPE_AUTO_TRADED_OPEN_ROWS = "AUTO_TRADED_OPEN_ROWS";
    static final String TRADE_PLAN_QUALITY_GATE_ENABLED_KEY = "tradePlanQualityGateEnabled";
    static final String TRADE_PLAN_MIN_RISK_REWARD_KEY = "tradePlanMinRiskReward";
    static final String TRADE_PLAN_MAX_STOP_LOSS_PCT_KEY = "tradePlanMaxStopLossPct";
    static final String ENSEMBLE_SHADOW_PRE_EXECUTION_DISCLAIMER =
            "Phase 1 - Ensemble 不擋；仍需通過下單前風控";

    private final MdKlineRepository klineRepository;
    private final BtLiveSignalRepository liveSignalRepository;
    private final StrategyRegistry strategyRegistry;
    private final BacktestEngine backtestEngine;
    private final NotificationPort notificationPort;
    private final ObjectMapper objectMapper;
    private final FearGreedService fearGreedService;
    private final WhaleFlowService whaleFlowService;
    private final com.agora.service.meta.MarketIndicatorFlipDetector marketIndicatorFlipDetector;
    private final MarketSignalCache marketSignalCache;
    private final OkxTradingProperties tradingProperties;
    private final TradingService tradingService;
    private final com.agora.service.trading.OkxTradingService okxTradingService;
    private final PostTradeReviewService postTradeReviewService;
    private final ShortAiFilter shortAiFilter;
    private final LongAiFilter longAiFilter;
    private final DailyLossGuard dailyLossGuard;
    private final AiStrategyDiscoveryService aiDiscoveryService;
    private final BtStrategyService strategyService;
    private final com.agora.service.meta.DecisionAuditWriter auditWriter;
    private final com.agora.service.meta.StrategyOverrideService strategyOverrideService;
    private final com.agora.service.meta.AttentionRuleEvaluator attentionRuleEvaluator;
    private final TradingMetrics tradingMetrics;
    private final com.agora.repository.trading.SignalOutcomeVerificationRepository signalVerificationRepo;
    /** SHADOW-mode ML inference logger — non-blocking; safe if model not promoted. */
    private final com.agora.service.ml.MlInferenceLogger mlInferenceLogger;
    /** Ensemble scorer gateway — assembles live inputs and delegates to TradeDecisionEngine. */
    private final EnsembleGateway ensembleGateway;
    /** Gemini hint repository — still used when regime-source=gemini (legacy fallback). */
    private final com.agora.repository.trading.GeminiMarketHintRepository geminiHintRepository;
    /** Real-time market snapshot for DeterministicRegimeClassifier (cached 5 min). */
    private final com.agora.service.ai.AiStrategyDiscoveryService discoveryService;
    /** Spot order-book imbalance (cached 5 min) — for ensemble scoring. */
    private final com.agora.service.market.OrderbookImbalanceService orderbookImbalanceService;
    /** OKX Simple Earn — 餘額不足時自動贖回補足（fail-open：若 null 或 redeem 失敗則維持原邏輯）*/
    private final com.agora.service.trading.OkxEarnService okxEarnService;
    /** Position sizing manager — shadow by default; live sizing requires explicit config. */
    private final PositionSizingService positionSizingService;
    private final com.agora.service.trading.OcoAdjustmentAuditWriter ocoAdjustmentAuditWriter;
    private final com.agora.service.trading.EventRiskActionOrchestrator eventRiskActionOrchestrator;
    private final ExposureOptimizer exposureOptimizer;
    private final TradeQualityEngine tradeQualityEngine;
    private final DataFreshnessShadowReplayCollector dataFreshnessShadowReplayCollector;
    private final TradingSignalSourcePolicy signalSourcePolicy;

    /** Self-injection for @Cacheable proxy（必須透過 Spring proxy 才能讓 @Cacheable 生效）*/
    @Autowired @Lazy
    private LiveSignalEvaluator self;

    private record WickAwareSlAdjustment(
            boolean applied,
            BigDecimal selectedSl,
            BigDecimal structuralSl,
            BigDecimal disasterSl,
            BigDecimal swingLow,
            BigDecimal buffer,
            BigDecimal atrAbs,
            String policyMode,
            String reason) {}

    record BottomCatchQualityDecision(
            boolean allowed,
            String reasonCode,
            String reason,
            double riskReward,
            double minRiskReward,
            double stopLossPct,
            double maxStopLossPct,
            boolean wickAwareSlApplied,
            String wickAwareSlMode) {}

    record FearGreedGateDecision(
            boolean active,
            boolean hardBlock,
            boolean warnOnly,
            String condition,
            String reason,
            int value,
            double threshold,
            int tqsPenalty,
            int qualityScore,
            String tqsBand,
            Map<String, Object> context) {

        static FearGreedGateDecision inactive() {
            return new FearGreedGateDecision(false, false, false, null, null,
                    -1, 0.0, 0, 50, "BASELINE", Map.of());
        }
    }

    /**
     * 全域 fallback：實時信號評估讀取的 K 線資料源（{@code okx}/{@code binance}）。
     * V041 起 {@code BtStrategy.klineSource} 成為 per-strategy source of truth；
     * 此欄位只在策略欄位為 null（理論上不會發生，DB DEFAULT 'okx'）時退回使用。
     * 相同 (symbol, interval, openTime) 在 DB 可有兩個 source 的紀錄；正確的源由策略決定。
     */
    @org.springframework.beans.factory.annotation.Value("${market.signal.source:okx}")
    private String fallbackSignalSource;

    /**
     * P0 — Ensemble gate: when true, trades scoring BLOCK (score < threshold) are rejected
     * after all other filters pass. VETO is always active regardless of this flag.
     * Start with false; flip to true after shadow data validates threshold accuracy.
     */
    @org.springframework.beans.factory.annotation.Value("${trade-decision-engine.gate.enabled:false}")
    private boolean ensembleGateEnabled;

    /**
     * P1 — Regime filter: when true, applies regime–aware ADX adjustments before
     * strategy evaluation AND suppresses LONG signals in confirmed TRENDING_DOWN regimes.
     * Safe to enable immediately (conservative in direction, no false-positive risk for shadow).
     */
    @org.springframework.beans.factory.annotation.Value("${trade-decision-engine.regime-filter.enabled:true}")
    private boolean regimeFilterEnabled;

    /**
     * Regime data source:
     * <ul>
     *   <li>{@code deterministic} (default) — {@link com.agora.service.market.DeterministicRegimeClassifier}:
     *       rule-based, real-time, no LLM calls, no 4-hour staleness.</li>
     *   <li>{@code gemini} — reads {@code gemini_market_hint} DB table (updated by
     *       GeminiMarketAdvisor scheduler). Legacy; kept as rollback option.</li>
     * </ul>
     */
    @org.springframework.beans.factory.annotation.Value("${trade-decision-engine.regime-source:deterministic}")
    private String regimeSource;

    private static final DateTimeFormatter FMT_DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};

    /**
     * 針對指定 symbol + intervalCode 評估所有 enabled 策略的最新 bar 訊號。
     */
    public void evaluate(String symbol, String intervalCode) {
        List<BtStrategy> strategies = strategyService.getEnabledStrategies();

        if (strategies.isEmpty()) {
            log.debug("[LiveSignal] No enabled strategies");
            return;
        }

        for (BtStrategy strategy : strategies) {
            if (!signalSourcePolicy.shouldRunLegacyLiveEvaluatorForStrategy(strategy.getId())) {
                log.debug("[LiveSignal] Skip strategyId={} by signal-source policy: {}",
                        strategy.getId(), signalSourcePolicy.status());
                continue;
            }
            try {
                evaluateStrategy(strategy, symbol, intervalCode);
            } catch (Exception e) {
                log.error("[LiveSignal] Error evaluating strategyId={} symbol={} interval={}: {}",
                        strategy.getId(), symbol, intervalCode, e.getMessage(), e);
            }
        }
    }

    private void evaluateStrategy(BtStrategy strategy, String symbol, String intervalCode) {
        // 若策略未設定 symbols，跳過（symbols 為必填，null 視為設定錯誤）
        if (strategy.getSymbols() == null || strategy.getSymbols().trim().isEmpty()) {
            log.warn("[LiveSignal] Skip strategyId={} — symbols 未設定，請修正策略資料", strategy.getId());
            return;
        }
        boolean matched = java.util.Arrays.stream(strategy.getSymbols().split(","))
                .map(String::trim)
                .anyMatch(s -> s.equalsIgnoreCase(symbol));
        if (!matched) {
            log.debug("[LiveSignal] Skip strategyId={} symbols={} for symbol={}",
                    strategy.getId(), strategy.getSymbols(), symbol);
            return;
        }

        // Meta-Control:Claude 下的 PAUSE override(硬性 TTL);第一道檢查。
        // race condition:兩道保險 —— 此處 + autoTrade() 前 re-check(Phase 1 暫以此為主)。
        var pauseOv = strategyOverrideService.findActivePause(strategy.getId(), symbol, intervalCode);
        if (pauseOv.isPresent()) {
            com.agora.model.StrategyOverride ov = pauseOv.get();
            log.info("[LiveSignal] Skip strategyId={} symbol={} interval={} — StrategyOverride.PAUSE (reason={}, expires={})",
                    strategy.getId(), symbol, intervalCode, ov.getReason(), ov.getExpiresAt());
            auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                    "StrategyOverride", ov.getReason(),
                    java.util.Map.of("overrideId", ov.getId(), "expiresAt", ov.getExpiresAt().toString()));
            return;
        }

        Strategy impl;
        try {
            impl = strategyRegistry.getRequiredStrategy(strategy.getStrategyType());
        } catch (IllegalArgumentException e) {
            log.debug("[LiveSignal] No impl for strategyType={}", strategy.getStrategyType());
            return;
        }

        Map<String, Object> config = parseConfig(strategy.getConfigJson());
        if (config == null) return;

        // Interval guard: if the strategy config declares a runIntervalCode, only evaluate on
        // matching bar closures. Prevents 1d strategies (e.g. SCORE_BUY_V2) from triggering on
        // 1h/4h bars and generating false sub-daily shadow signals.
        String configuredInterval = resolveConfiguredRunInterval(config, impl);
        if (!configuredInterval.isEmpty() && !configuredInterval.equalsIgnoreCase(intervalCode)) {
            log.debug("[LiveSignal] Skip strategyId={} type={} — interval mismatch (configured={}, incoming={})",
                    strategy.getId(), strategy.getStrategyType(), configuredInterval, intervalCode);
            return;
        }

        // Regime source — either real-time deterministic classifier (default) or Gemini DB hint.
        // The four effective* variables feed: P1 regime filter, ensemble scoring, attention rules.
        String effectiveStyle = null;
        String effectiveRegime = null;
        Double effectiveConf = null;
        Boolean effectiveShortOk = null;
        try {
            if ("gemini".equalsIgnoreCase(regimeSource)) {
                // Legacy path: read 3-persona vote from DB (bounded by GeminiAdvisor TTL).
                List<com.agora.model.GeminiMarketHint> hints = geminiHintRepository.findActiveHints(
                        symbol, intervalCode, LocalDateTime.now(), PageRequest.of(0, 1));
                if (!hints.isEmpty()) {
                    com.agora.model.GeminiMarketHint h = hints.get(0);
                    effectiveStyle   = h.getStyleHint();
                    effectiveRegime  = h.getRegime();
                    if (h.getConfidence() != null) effectiveConf = h.getConfidence().doubleValue();
                    effectiveShortOk = h.getAllowShort();
                }
            } else {
                // Default: DeterministicRegimeClassifier — real-time, no LLM, reproducible.
                // buildMarketSnapshot() is cached 5 min so no extra DB round-trip per bar.
                String ctxTf = "1h".equals(intervalCode) ? "4h" : "1h";
                com.agora.service.ai.AiStrategyDiscoveryService.MarketSnapshot primary =
                        discoveryService.buildMarketSnapshot(symbol, intervalCode);
                com.agora.service.ai.AiStrategyDiscoveryService.MarketSnapshot context =
                        discoveryService.buildMarketSnapshot(symbol, ctxTf);
                com.agora.service.market.DeterministicRegimeClassifier.Result det =
                        com.agora.service.market.DeterministicRegimeClassifier.classify(primary, context);
                effectiveStyle   = det.styleHint();
                effectiveRegime  = det.regime();
                effectiveConf    = det.confidence();
                effectiveShortOk = det.allowShort();
                log.debug("[Regime] deterministic {}/{}: regime={} style={} allowShort={} conf={} adx={} rsi={} atr={}",
                        symbol, intervalCode, det.regime(), det.styleHint(), det.allowShort(),
                        String.format("%.1f", det.confidence()),
                        String.format("%.1f", primary.adx14()),
                        String.format("%.1f", primary.rsi14()),
                        String.format("%.2f%%", primary.atrPct()));
            }
        } catch (Exception e) {
            log.debug("[LiveSignal] regime source read failed, proceeding without regime: {}", e.getMessage());
        }

        // V041 每策略解析一次 source，之後全部 load 都透過它讀，保證回測/實時一致
        String klineSource = resolveStrategyKlineSource(strategy);

        // ── requiredDailyTrend 門衛：日線趨勢不符時靜默跳過，不影響回測 ──────────────
        String requiredTrend = getString(config, "requiredDailyTrend", "ANY").toUpperCase().trim();
        if (!"ANY".equals(requiredTrend) && !"".equals(requiredTrend)) {
            int maPeriod = Math.max(20, getInt(config, "dailyMaPeriod", 50));
            List<MdKline> dailyKlines = self.loadKlinesCached(symbol, "1d", klineSource, maPeriod + 10);
            if (dailyKlines.size() >= maPeriod) {
                int di = dailyKlines.size() - 1;
                double dailyClose = dailyKlines.get(di).getClosePrice().doubleValue();
                double dailyMa = 0.0;
                for (int k = di - maPeriod + 1; k <= di; k++) {
                    dailyMa += dailyKlines.get(k).getClosePrice().doubleValue();
                }
                dailyMa /= maPeriod;
                boolean dailyBullish = dailyClose > dailyMa;
                if ("BEARISH".equals(requiredTrend) && dailyBullish) {
                    log.info("[LiveSignal] Skip strategyId={} ({}): requiredDailyTrend=BEARISH but close={} > MA{}={}",
                            strategy.getId(), symbol,
                            String.format("%.2f", dailyClose), maPeriod, String.format("%.2f", dailyMa));
                    return;
                }
                if ("BULLISH".equals(requiredTrend) && !dailyBullish) {
                    log.info("[LiveSignal] Skip strategyId={} ({}): requiredDailyTrend=BULLISH but close={} < MA{}={}",
                            strategy.getId(), symbol,
                            String.format("%.2f", dailyClose), maPeriod, String.format("%.2f", dailyMa));
                    return;
                }
            }
        }

        // 計算需要載入的 K 線數量：yearLookback + warmup
        int yearLookback = getInt(config, "yearLookbackBars", 252);
        int totalBars = yearLookback + WARMUP_BARS;

        // 從 DB 取最近 N 根（升序）；透過 self proxy 讓 @Cacheable 生效
        List<MdKline> klines = self.loadKlinesCached(symbol, intervalCode, klineSource, totalBars);
        if (klines.size() < WARMUP_BARS + 3) {
            log.debug("[LiveSignal] Not enough klines: symbol={} interval={} got={} need={}",
                    symbol, intervalCode, klines.size(), WARMUP_BARS + 3);
            return;
        }

        // ── L0: DataFreshness guard ───────────────────────────────────────────────────
        // Skip evaluation when the newest K-line is stale (WS feed may be silent/broken).
        // Threshold: newest bar open must be within 2 × intervalWidth + 15 min of "now".
        // Example: 1h bar → skip if latestBarOpen was > 135 min ago (missed ≥1 bar close).
        {
            MdKline newest = klines.get(klines.size() - 1);
            int intMin = parseIntervalMinutes(intervalCode);
            LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime latestCloseEstimate = newest.getOpenTime().plusMinutes(intMin);
            long minSinceOpen = Duration.between(newest.getOpenTime(), nowUtc).toMinutes();
            long staleThreshold = 2L * intMin + 15;
            if (minSinceOpen > staleThreshold) {
                log.warn("[LiveSignal] DATA_STALE_SKIP: strategyId={} symbol={} interval={} " +
                         "latestBarOpen={} minutesSinceOpen={} threshold={}min",
                         strategy.getId(), symbol, intervalCode,
                         newest.getOpenTime().format(FMT_DISPLAY), minSinceOpen, staleThreshold);
                Map<String, Object> freshnessContext = dataFreshnessContext(strategy.getId(), symbol, intervalCode,
                        nowUtc, newest, latestCloseEstimate, minSinceOpen,
                        staleThreshold, intMin, klineSource, klines.size());
                dataFreshnessShadowReplayCollector.enrichAfterHardBlock(freshnessContext, strategy, symbol,
                        intervalCode, klineSource, newest, nowUtc, latestCloseEstimate, minSinceOpen,
                        staleThreshold, intMin, klines.size());
                auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                         "DataFreshnessGuard",
                         String.format("K-line stale by %dmin (latestOpen=%s, threshold=%dmin)",
                                 minSinceOpen, newest.getOpenTime(), staleThreshold),
                         freshnessContext);
                return;
            }
        }

        config.put("runIntervalCode", intervalCode);

        Map<String, double[]> indicators = backtestEngine.buildIndicators(klines, config);

        int lastIndex = klines.size() - 1;
        MdKline lastBar = klines.get(lastIndex);
        MdKline prevBar = klines.get(lastIndex - 1);

        // ── 多時框 K 線組裝（enableMtf=true 時補載 1D）────────────────────────────
        boolean enableMtf = getBoolean(config, "enableMtf", false);
        Map<String, List<MdKline>> tfKlines = new java.util.LinkedHashMap<>();
        Map<String, Integer> tfIndices = new java.util.LinkedHashMap<>();
        Map<String, Map<String, double[]>> tfInd = new java.util.LinkedHashMap<>();
        tfKlines.put(intervalCode, klines);
        tfIndices.put(intervalCode, lastIndex);
        tfInd.put(intervalCode, indicators);
        if (enableMtf) {
            int maPeriod1d = Math.max(20, getInt(config, "dailyMaPeriod", 50));
            List<MdKline> klines1d = self.loadKlinesCached(symbol, "1d", klineSource, maPeriod1d + 50);
            if (!klines1d.isEmpty()) {
                Map<String, double[]> indicators1d = backtestEngine.buildIndicators(klines1d, config);
                tfKlines.put("1d", klines1d);
                tfIndices.put("1d", klines1d.size() - 1);
                tfInd.put("1d", indicators1d);
            }
        }

        StrategyContext context = new StrategyContext(
                lastIndex, lastBar, prevBar, klines, indicators,
                tfKlines, tfIndices, tfInd);

        // 載入外部 sentiment 資料（失敗時回傳中性值，不影響評估流程）
        SentimentContext.clear();
        try {
            int fgValue     = fearGreedService.getFearGreedValue();
            double whaleRatio = whaleFlowService.getBuyRatio(symbol);
            SentimentContext.set(fgValue, whaleRatio);
            // Event-driven market indicator flip detector — 跨門檻或大變就 TG。
            // Hook 進此處因為 SentimentContext 剛好是所有指標最新 cache 的載入點。
            marketIndicatorFlipDetector.checkAndNotify(symbol, fgValue, whaleRatio);
        } catch (Exception e) {
            log.warn("[LiveSignal] Sentiment load failed: {}", e.getMessage());
        }

        // P1: Regime-aware config override — tightens/relaxes entry parameters based on
        // the Gemini market regime. Applied before impl.evaluate() so the strategy
        // naturally rejects weaker signals in hostile regimes via higher ADX gates.
        // Does NOT affect backtest (regime data is live-only).
        if (regimeFilterEnabled && effectiveRegime != null) {
            applyRegimeConfigOverrides(config, effectiveRegime);
        }

        // Clear before evaluate so we never read stale data from a prior call
        LiveSignalContext.clear();
        StrategySignal signal = impl.evaluate(context, config);
        LiveSignalContext.Snapshot snap = LiveSignalContext.get();
        // #398 — strategy-specific trigger-condition snapshot (mih_value, hold_reason, etc.)
        Map<String, Object> strategyDetails = LiveSignalContext.getDetails();

        log.info("[LiveSignal] strategyId={} symbol={} interval={} bar={} signal={}{}",
                strategy.getId(), symbol, intervalCode,
                lastBar.getOpenTime().format(FMT_DISPLAY), signal,
                snap != null ? String.format(" score=%.3f nn=%.3f rsi=%.1f", snap.score, snap.nnOutput, snap.rsi) : "");

        // 更新最新評估快照供 TradingAnalysisService 讀取
        SentimentContext.Snapshot sent = SentimentContext.get();
        marketSignalCache.update(symbol, intervalCode,
                snap != null ? snap.score    : 0,
                snap != null ? snap.nnOutput : 0,
                snap != null ? snap.rsi      : 0,
                sent != null ? sent.fearGreedValue : 50,
                sent != null ? sent.whaleBuyRatio  : 0.5,
                signal, lastBar.getOpenTime());

        // Meta-Control audit: SIGNAL_EVAL(每根 bar 所有策略一筆,含 HOLD)
        // V2 schema — rich trace for Claude explainPrediction + future ML training.
        com.agora.service.meta.DecisionContextBuilder ctxB =
                com.agora.service.meta.DecisionContextBuilder.v2();
        // Indicators: ADX + ATR% + volume ratio (strategy decision inputs)
        double[] adxValues = indicators.get("adx");
        if (adxValues != null && lastIndex < adxValues.length && !Double.isNaN(adxValues[lastIndex])) {
            ctxB.indicator("adx", adxValues[lastIndex]);
        }
        double[] atrPctValues = indicators.get("atrPct");
        if (atrPctValues != null && lastIndex < atrPctValues.length && !Double.isNaN(atrPctValues[lastIndex])) {
            ctxB.indicator("atr_pct", atrPctValues[lastIndex]);
        }
        double[] vmaValues = indicators.get("volumeMa20");
        if (vmaValues != null && lastIndex < vmaValues.length && vmaValues[lastIndex] > 0) {
            double ratio = lastBar.getVolume().doubleValue() / vmaValues[lastIndex];
            if (!Double.isNaN(ratio) && !Double.isInfinite(ratio)) {
                ctxB.indicator("volume_ratio_ma20", ratio);
            }
        }
        if (snap != null) {
            ctxB.indicator("rsi", snap.rsi)
                .strategy(strategy.getId(), strategy.getStrategyType(),
                        snap.score, snap.nnOutput, null, strategy.getKlineSource());
        } else {
            ctxB.strategy(strategy.getId(), strategy.getStrategyType(),
                    null, null, null, strategy.getKlineSource());
        }
        if (sent != null) {
            ctxB.sentiment("fg", sent.fearGreedValue)
                .sentiment("whale_buy_ratio", sent.whaleBuyRatio);
            // L2 divergence score: whale_buy_ratio - (1 - fg_normalized)
            // Positive = whales accumulating while market fears → classic bottom divergence signal.
            // Negative = alignment (both bullish or both bearish) → no divergence.
            // Useful as an ML feature and for bottom-detection post-analysis.
            double fgNorm = sent.fearGreedValue / 100.0;
            double divergenceScore = Math.round((sent.whaleBuyRatio - (1.0 - fgNorm)) * 10000.0) / 10000.0;
            ctxB.sentiment("divergence_score", divergenceScore);
        }
        // effectiveStyle / effectiveRegime are in broader scope (line ~198-199).
        if (effectiveStyle != null || effectiveRegime != null) {
            ctxB.regime(effectiveStyle, effectiveRegime, null);
        }
        ctxB.execution(signal.name(), null, null, null)
                .executionField("interval", intervalCode);

        // #398 — surface strategy-specific trigger-condition data (mih_value, hold_reason, …)
        // so HOLD audits don't require re-reading the strategy code to debug.
        if (strategyDetails != null && !strategyDetails.isEmpty()) {
            ctxB.extra("strategy_decision", strategyDetails);
        }

        // Phase 1 ensemble shadow scoring — only for actionable signals (BUY/SELL).
        // Result is written to extras.ensemble for later alignment analysis AND attached
        // to the TG notification so humans see the ensemble's conviction at a glance.
        // Observational only: engine outcome does NOT gate trade execution in this phase.
        com.agora.service.meta.TradeDecisionEngine.Decision ensembleDecision = null;
        if (signal == StrategySignal.BUY || signal == StrategySignal.SELL) {
            String sideTag = signal == StrategySignal.BUY ? "LONG" : "SHORT";
            try {
                ensembleDecision = ensembleGateway.compute(
                        strategy.getId(), symbol, intervalCode, sideTag,
                        klines, lastIndex, snap, sent,
                        effectiveStyle, effectiveRegime, effectiveConf, effectiveShortOk,
                        indicators);
                if (ensembleDecision != null) {
                    ctxB.extra("ensemble", ensembleDecisionToMap(ensembleDecision));
                    log.info("[TradeDecisionEngine] strategyId={} {} {} side={} score={} outcome={}",
                            strategy.getId(), symbol, intervalCode, sideTag,
                            String.format("%.1f", ensembleDecision.score()), ensembleDecision.outcome());
                }
            } catch (Exception e) {
                log.warn("[TradeDecisionEngine] shadow compute failed for strategyId={}: {}",
                        strategy.getId(), e.getMessage());
            }
        }

        Map<String, Object> evalCtx = ctxB.build();
        auditWriter.logSignalEval(strategy.getId(), symbol, intervalCode,
                lastBar.getOpenTime(), signal.name(), evalCtx);

        // Meta-Control attention rule(Phase 1 只 LOG_ONLY / NOTIFY,不阻擋流程)
        if (signal == StrategySignal.BUY || signal == StrategySignal.SELL) {
            String sideTag = signal == StrategySignal.BUY ? "LONG" : "SHORT";
            tradingMetrics.signalEmit(symbol, intervalCode, sideTag);
            Map<String, Object> attCtx = new java.util.LinkedHashMap<>();
            attCtx.put("symbol", symbol);
            attCtx.put("interval", intervalCode);
            attCtx.put("side", sideTag);
            attCtx.put("strategy_id", strategy.getId());
            if (snap != null) { attCtx.put("rsi", snap.rsi); attCtx.put("score", snap.score); attCtx.put("nn", snap.nnOutput); }
            if (sent != null) { attCtx.put("fg",  sent.fearGreedValue); attCtx.put("whale", sent.whaleBuyRatio); }
            // ADX from indicators
            double[] adxArr = indicators.get("adx");
            if (adxArr != null && lastIndex < adxArr.length && !Double.isNaN(adxArr[lastIndex])) {
                attCtx.put("adx", adxArr[lastIndex]);
            }
            // Volume ratio (current bar volume / 20-period MA)
            double[] volMa20 = indicators.get("volumeMa20");
            if (volMa20 != null && lastIndex < volMa20.length
                    && !Double.isNaN(volMa20[lastIndex]) && volMa20[lastIndex] > 0) {
                double curVol = klines.get(lastIndex).getVolume().doubleValue();
                attCtx.put("volume_ratio", curVol / volMa20[lastIndex]);
            }
            // Gemini hint context
            if (effectiveStyle != null)  attCtx.put("gemini_style", effectiveStyle);
            if (effectiveRegime != null) attCtx.put("gemini_regime", effectiveRegime);
            attentionRuleEvaluator.evaluate(attCtx);

            // SHADOW-mode ML inference (V052 v9): async, non-blocking, swallows all errors.
            // No PROMOTED model = silent skip. Live trade flow continues regardless.
            // live_signal_id is null at this point (BtLiveSignal not yet created);
            // can be backfilled later by joining (predicted_at ~= now, symbol, side, strategy_id).
            mlInferenceLogger.logShadow(strategy.getId(), symbol, intervalCode, sideTag,
                    klines, lastIndex, null);
        }

        // SELL 訊號：關閉現有多頭；若 allowShort=true 則開新空頭
        if (signal == StrategySignal.SELL) {
            handleExitSignal(strategy, symbol, intervalCode, lastBar, snap, config, ensembleDecision);
            return;
        }

        if (signal != StrategySignal.BUY) return;

        // P1: Regime directional filter — suppress LONG entries in confirmed bear regimes.
        // Regime source (deterministic/gemini) votes TRENDING_DOWN → contradicts LONG entry.
        // allowLongInBearRegime=true in strategy config overrides (e.g. mean-reversion).
        //
        // EXTREME OVERSOLD BYPASS: when RSI < regimeBypassRsiThreshold (default 20),
        // the market has already capitulated — regime filter is counterproductive.
        // Strategies designed for panic-bottoms (OIF, SCORE_BUY) should still fire.
        double currentRsiForRegime = snap != null ? snap.rsi : 50.0;
        // #335: parametrized — allows operators to relax RSI bypass without code changes
        double regimeBypassThreshold = getDouble(config, "regimeBypassRsiThreshold", 20.0);
        // #221: per-strategy opt-in — only crash-bottom strategies (e.g. SCORE_BUY_V2 #485)
        // should bypass RegimeFilter on extreme oversold. Trend-following strategies should not.
        boolean strategyAllowsRsiBypass = getBoolean(config, "allowRsiBypassRegime", false);
        boolean extremeOversoldBypass = strategyAllowsRsiBypass && (currentRsiForRegime < regimeBypassThreshold);
        int currentFgForRegime = sent != null ? sent.fearGreedValue : -1;
        boolean panicBottomBypass = isReversalLikeStrategy(strategy)
                && currentRsiForRegime < getDouble(config, "panicBottomRsiThreshold", 35.0)
                && currentFgForRegime >= 0
                && currentFgForRegime <= getDouble(config, "panicBottomFearGreedMax", 35.0);
        if (extremeOversoldBypass) {
            log.info("[Regime] RSI={} < {} — extreme oversold bypass: TRENDING_DOWN filter skipped for strategyId={} symbol={}",
                    String.format("%.1f", currentRsiForRegime), regimeBypassThreshold,
                    strategy.getId(), symbol);
        }
        if (panicBottomBypass) {
            log.info("[Regime] panic-bottom bypass active: strategyId={} symbol={} rsi={} fg={}",
                    strategy.getId(), symbol, String.format("%.1f", currentRsiForRegime), currentFgForRegime);
        }
        // #335: only hard-block when regime confidence ≥ threshold (default 0 = always block,
        // preserving existing behavior). Strategies can opt-in to a stricter gate (e.g. 0.6)
        // to skip the block when classifier confidence is low — addresses 61.5% mis-block rate.
        //
        // #442 fix: when operator sets a positive threshold (i.e. opted-in to lenient gate),
        // treat null effectiveConf as "below threshold" too. Prod audit (5/6) showed 22/22
        // RegimeFilter blocks had null conf — the original `effectiveConf != null` guard made
        // lowConfidenceBypass dead code in live mode. The classifier returns null conf when
        // some indicator inputs are missing (warmup gap, gemini fallback path), and treating
        // that as "block anyway" defeats operator intent. Backtest path uses primitive double
        // so it's unaffected.
        double regimeMinConf = getDouble(config, "regimeFilterMinConfidence", 0.0);
        boolean lowConfidenceBypass = regimeMinConf > 0
                && (effectiveConf == null || effectiveConf < regimeMinConf);
        if (lowConfidenceBypass) {
            log.info("[Regime] confidence={} < {} — low-conf bypass: filter skipped for strategyId={} symbol={}",
                    effectiveConf != null ? String.format("%.2f", effectiveConf) : "null",
                    regimeMinConf, strategy.getId(), symbol);
        }
        String regimeDowntrendAction = getString(config, "regimeDowntrendAction", "BLOCK")
                .trim().toUpperCase();
        boolean reversalLikeStrategy = isReversalLikeStrategy(strategy);
        boolean softenDowntrendBlock = reversalLikeStrategy && "SOFT_SIZE".equals(regimeDowntrendAction);
        if (regimeFilterEnabled
                && "TRENDING_DOWN".equalsIgnoreCase(effectiveRegime)
                && !getBoolean(config, "allowLongInBearRegime", false)
                && !extremeOversoldBypass
                && !panicBottomBypass
                && !lowConfidenceBypass) {
            if (softenDowntrendBlock) {
                log.info("[Regime] TRENDING_DOWN softened to shadow sizing: strategyId={} symbol={} action={} type={}",
                        strategy.getId(), symbol, regimeDowntrendAction, strategy.getStrategyType());
                auditWriter.logAttentionHit(strategy.getId(), symbol, intervalCode,
                        "RegimeFilterSoftened", "INFO",
                        java.util.Map.of(
                                "regime", effectiveRegime,
                                "action", regimeDowntrendAction,
                                "strategy_type", strategy.getStrategyType() != null ? strategy.getStrategyType() : "n/a",
                                "shadow_only", true));
            } else {
            log.info("[Regime] BUY suppressed — regime=TRENDING_DOWN contradicts LONG: strategyId={} symbol={} style={} conf={}",
                    strategy.getId(), symbol, effectiveStyle,
                    effectiveConf != null ? String.format("%.2f", effectiveConf) : "n/a");
            auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                    "RegimeFilter", "TRENDING_DOWN suppresses LONG",
                    java.util.Map.of(
                            "regime", effectiveRegime,
                            "style", effectiveStyle != null ? effectiveStyle : "n/a",
                            "confidence", effectiveConf != null ? effectiveConf : -1.0));
            return;  // No bt_live_signal record — signal silently suppressed
            }
        }

        // P1 2026-05-21: F&G is no longer an unconditional early hard block by
        // default. It becomes WARN_ONLY + TQS penalty so EV/TQS/evidence layers
        // can sample candidates without enabling live autonomous execution.
        double requireFearGreedBelow = getDouble(config, "requireFearGreedBelow", 0.0);
        double requireFearGreedAbove = getDouble(config, "requireFearGreedAbove", 0.0);
        String fearGreedFilterMode = getString(config, "fearGreedFilterMode", "WARN_ONLY");
        FearGreedGateDecision fearGreedGate = evaluateFearGreedGate(
                "LONG",
                sent != null ? sent.fearGreedValue : null,
                requireFearGreedBelow,
                requireFearGreedAbove,
                fearGreedFilterMode);
        if (fearGreedGate.active()) {
            log.info("[FearGreedFilter] WARN_ONLY/TQS penalty: strategyId={} symbol={} {}",
                    strategy.getId(), symbol, fearGreedGate.reason());
            Map<String, Object> fgContext = new LinkedHashMap<>(fearGreedGate.context());
            fgContext.put("strategyAllowlisted", isTqsStrategyAllowlisted(strategy));
            tradeQualityEngine.applyV0(fgContext, "NONE");
            auditWriter.logAttentionHit(strategy.getId(), symbol, intervalCode,
                    "FearGreedFilterWarnOnly", "WARN", fgContext);
        }

        // BUY 訊號：若有未出場的 SHORT 倉位，先平空再開多
        boolean allowShort = getBoolean(config, "allowShort", false)
                          || getBoolean(config, "shortOnly", false);
        if (allowShort && tradingProperties.isEnabled()) {
            List<BtLiveSignal> openShorts = liveSignalRepository
                    .findByStrategyIdAndSymbolAndIntervalCodeAndExitTimeIsNullAndNotifiedAtIsNotNull(
                            strategy.getId(), symbol, intervalCode)
                    .stream().filter(p -> "SHORT".equals(p.getSide())).toList();
            for (BtLiveSignal shortPos : openShorts) {
                if (Boolean.TRUE.equals(shortPos.getAutoTraded()) && shortPos.getTradedQty() != null) {
                    autoCloseShort(shortPos, symbol, lastBar.getClosePrice());
                }
            }
        }

        // 去重：同一根 bar 且 TG 已成功送出（notifiedAt != null）才算重複
        if (liveSignalRepository.existsByStrategyIdAndSymbolAndIntervalCodeAndBarOpenTimeAndNotifiedAtIsNotNull(
                strategy.getId(), symbol, intervalCode, lastBar.getOpenTime())) {
            log.info("[LiveSignal] Duplicate suppressed: strategyId={} bar={}",
                    strategy.getId(), lastBar.getOpenTime().format(FMT_DISPLAY));
            logEntrySkip(strategy, symbol, intervalCode, lastBar, "DuplicateBar",
                    "same strategy/symbol/interval/bar already notified",
                    Map.of("side", "LONG", "bar_open_time", lastBar.getOpenTime().toString()));
            return;
        }

        // 進場頻率冷卻：防止 SIDEWAYS 環境過度交易
        int cooldownMinutes = (Integer) config.getOrDefault("entryFrequencyCooldownMinutes", 60);
        java.util.Optional<BtLiveSignal> lastClosed = liveSignalRepository.findLastClosedByStrategyIdAndSymbol(
                strategy.getId(), symbol);
        if (lastClosed.isPresent()) {
            BtLiveSignal prev = lastClosed.get();
            if (prev.getExitTime() != null) {
                Duration elapsed = Duration.between(prev.getExitTime(), LocalDateTime.now(ZoneOffset.UTC));
                long remainingMinutes = cooldownMinutes - elapsed.toMinutes();
                if (remainingMinutes > 0) {
                    // #441 — Cooldown is internal speed-limiting, not user-actionable.
                    // TG send removed: log-only suffices. User can grep app.log
                    // "[LiveSignal] Entry blocked by cooldown" if they need detail.
                    // Previously even per-session dedupe still sent ≥1 TG/cooldown ×
                    // (n strategies × n hours) which dominated TG noise during high-fire
                    // periods (#445 SQI POC). Vacation guardian (#448) needs critical-only stream.
                    log.info("[LiveSignal] Entry blocked by cooldown: strategyId={} symbol={} remaining={}min",
                            strategy.getId(), symbol, remainingMinutes);
                    logEntrySkip(strategy, symbol, intervalCode, lastBar, "EntryCooldown",
                            "entry frequency cooldown active",
                            Map.of(
                                    "side", "LONG",
                                    "remaining_minutes", remainingMinutes,
                                    "cooldown_minutes", cooldownMinutes,
                                    "previous_signal_id", String.valueOf(prev.getId())));
                    return;
                }
            }
        }

        // 計算建議止損 / 止盈（atrStopLossEnabled=true 時依 ATR 動態調整）
        double[] slTp = computeSlTpPcts(config, symbol, intervalCode);
        double stopLossPct   = slTp[0];
        double takeProfitPct = slTp[1];
        BigDecimal entry = lastBar.getClosePrice();
        BigDecimal sl = entry.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(stopLossPct)))
                            .setScale(2, RoundingMode.HALF_UP);
        BigDecimal tp = entry.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(takeProfitPct)))
                            .setScale(2, RoundingMode.HALF_UP);
        WickAwareSlAdjustment wickAwareSl = applySpotWickAwareStructuralSl(
                config, symbol, intervalCode, entry, sl, klines, lastIndex);
        if (wickAwareSl.applied()) {
            sl = wickAwareSl.selectedSl();
            stopLossPct = entry.subtract(sl)
                    .divide(entry, 8, RoundingMode.HALF_UP)
                    .doubleValue();
            log.info("[LiveSignal] spotWickAwareExit {}@{}: SL moved to {} {} (structural={} disaster={} swingLow={} buffer={} atrAbs={})",
                    symbol, intervalCode, wickAwareSl.policyMode(), sl, wickAwareSl.structuralSl(),
                    wickAwareSl.disasterSl(), wickAwareSl.swingLow(), wickAwareSl.buffer(), wickAwareSl.atrAbs());
        }

        // 計算年度高點跌幅
        double yearDrop = calcYearDrop(klines, lastIndex, yearLookback, entry.doubleValue());
        ExpectedRDecision expectedRDecision = computeExpectedRDecision(
                strategy.getStrategyType(), snap, stopLossPct, takeProfitPct);
        double expectedR = expectedRDecision.expectedR();
        BottomCatchQualityDecision bottomCatchQuality = evaluateBottomCatchQualityGate(
                strategy.getStrategyType(), config, stopLossPct, takeProfitPct,
                wickAwareSl.applied(), wickAwareSl.policyMode());
        if (!bottomCatchQuality.allowed()) {
            log.info("[LiveSignal] trade plan quality blocked: strategyId={} symbol={} interval={} reason={}",
                    strategy.getId(), symbol, intervalCode, bottomCatchQuality.reason());
            tradingMetrics.signalFiltered("TradePlanQualityGate", bottomCatchQuality.reasonCode());
            Map<String, Object> qualityContext = candidateTradePlanContext(
                    expectedRDecision, getDouble(config, "preTradeMinExpectedR", 0.20),
                    stopLossPct, takeProfitPct, entry, tp, sl, snap, fearGreedGate);
            qualityContext.put("gate_enabled", true);
            qualityContext.put("selectedAction", "BLOCK_LOW_QUALITY_TRADE_PLAN");
            qualityContext.put("riskGateResult", "BLOCKED_TRADE_PLAN_QUALITY");
            qualityContext.put("orderSent", false);
            qualityContext.put("reasonCode", bottomCatchQuality.reasonCode());
            qualityContext.put("reason", bottomCatchQuality.reason());
            qualityContext.put("riskReward", bottomCatchQuality.riskReward());
            qualityContext.put("minRiskReward", bottomCatchQuality.minRiskReward());
            qualityContext.put("maxStopLossPct", bottomCatchQuality.maxStopLossPct());
            qualityContext.put("wickAwareSlApplied", bottomCatchQuality.wickAwareSlApplied());
            qualityContext.put("wickAwareSlMode", bottomCatchQuality.wickAwareSlMode());
            tradeQualityEngine.applyV0(qualityContext, "TradePlanQualityGate");
            logEntrySkip(strategy, symbol, intervalCode, lastBar,
                    "TradePlanQualityGate", bottomCatchQuality.reason(), qualityContext);
            try {
                notificationPort.broadcast(buildTradePlanQualitySkipNotification(
                        symbol, intervalCode, strategy.getId(), entry, sl, tp, bottomCatchQuality), true);
            } catch (Exception e) {
                log.warn("[LiveSignal] trade-plan quality skip notification failed: strategyId={} symbol={} err={}",
                        strategy.getId(), symbol, e.getMessage());
            }
            return;
        }

        // #332 dedup gate. Default remains all open rows, including shadow rows,
        // to preserve legacy behavior. A strategy may explicitly request the
        // auto-traded-only scope after operator review.
        String entryDedupOpenExposureScope = resolveEntryDedupOpenExposureScope(config);
        boolean hasOpenLongExposure = hasOpenLongExposureForEntryDedup(
                strategy.getId(), symbol, "LONG", intervalCode, entryDedupOpenExposureScope);
        boolean dedupShadowOnlyOverride = false;
        boolean stagedMicroAddEntry = false;
        double stagedMicroAddMaxNotionalUsdt = 0.0;
        double preTradeMinExpectedRForSnapshot = getDouble(config, "preTradeMinExpectedR", 0.20);
        ExposureOptimizer.Result exposureDecision = exposureOptimizer.evaluateLongEntry(
                strategy, config, symbol, intervalCode, expectedR, stopLossPct, hasOpenLongExposure,
                entry, tp, sl, lastBar.getOpenTime(), preTradeMinExpectedRForSnapshot,
                expectedRDecision.trusted(), expectedRDecision.provenance());
        Map<String, Object> exposureDecisionContext = exposureDecision.context() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(exposureDecision.context());
        exposureDecisionContext.put("entryDedupOpenExposureScope", entryDedupOpenExposureScope);
        exposureDecisionContext.put("entryDedupOpenExposureScopeKey", ENTRY_DEDUP_OPEN_EXPOSURE_SCOPE_KEY);
        exposureDecisionContext.put("entryDedupOpenExposureScopeDefault", ENTRY_DEDUP_SCOPE_ALL_OPEN_ROWS);
        exposureDecisionContext.put("entryDedupOpenExposureScopeQuery",
                usesAutoTradedOpenRowsForEntryDedup(entryDedupOpenExposureScope)
                        ? "AUTO_TRADED_EXIT_TIME_NULL_ROWS"
                        : "ALL_EXIT_TIME_NULL_ROWS");
        exposureDecisionContext.put("entryDedupOpenExposureScopeBehaviorChange",
                usesAutoTradedOpenRowsForEntryDedup(entryDedupOpenExposureScope));
        exposureDecisionContext.put("entryDedupOpenExposureDetected", hasOpenLongExposure);
        if (exposureDecision.shadowOnly()) {
                dedupShadowOnlyOverride = true;
                log.info("[LiveSignal] ExposureOptimizer shadow-only candidate: strategyId={} symbol={} expectedR={} reason={}",
                        strategy.getId(), symbol, String.format("%.3f", expectedR), exposureDecision.reason());
                auditWriter.logAttentionHit(strategy.getId(), symbol, intervalCode,
                        "EntryDedupSoftened", "INFO",
                        exposureDecisionContext);
        } else if (exposureDecision.stagedMicroAddEntry()) {
                stagedMicroAddEntry = true;
                stagedMicroAddMaxNotionalUsdt = exposureDecision.microAddNotionalCapUsdt();
                log.info("[LiveSignal] ExposureOptimizer staged micro-add allowed: strategyId={} symbol={} expectedR={} maxNotional={} reason={}",
                        strategy.getId(), symbol, String.format("%.3f", expectedR),
                        String.format("%.2f", stagedMicroAddMaxNotionalUsdt), exposureDecision.reason());
                auditWriter.logAttentionHit(strategy.getId(), symbol, intervalCode,
                        "EntryDedupStagedMicroAddAllowed", "INFO",
                        exposureDecisionContext);
        } else if (exposureDecision.blocksEntry()) {
                String blocker = hasOpenLongExposure ? "EntryDedup" : "ExposureOptimizer";
                log.info("[LiveSignal] LONG entry blocked by {}: strategyId={} symbol={} interval={} reason={}",
                        blocker, strategy.getId(), symbol, intervalCode, exposureDecision.reason());
                logEntrySkip(strategy, symbol, intervalCode, lastBar, blocker,
                        exposureDecision.reason(), exposureDecisionContext);
                return;
        }

        boolean configNotifyOnly = getBoolean(config, "notifyOnly", false);
        boolean noLiveExecutionOnly = isNoLiveExecutionOnlyStrategy(strategy, config);
        boolean notifyOnly = configNotifyOnly || dedupShadowOnlyOverride || softenDowntrendBlock || noLiveExecutionOnly;
        if (noLiveExecutionOnly && !configNotifyOnly) {
            log.info("[LiveSignal] strategy forced to notifyOnly by no-live-execution guard: strategyId={} name={}",
                    strategy.getId(), strategy.getName());
        }

        // Phase 1：先存 DB（notifiedAt = null），佔住唯一索引防止並發重複
        BtLiveSignal record = new BtLiveSignal();
        record.setStrategyId(strategy.getId());
        record.setSymbol(symbol);
        record.setIntervalCode(intervalCode);
        record.setBarOpenTime(lastBar.getOpenTime());
        record.setEntryPrice(entry);
        record.setSuggestedSl(sl);
        record.setSuggestedTp(tp);
        record.setScore(snap != null
                ? BigDecimal.valueOf(snap.score).setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        record.setNnOutput(snap != null
                ? BigDecimal.valueOf(snap.nnOutput).setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        record.setSide("LONG");
        record.setNotifiedAt(null);
        liveSignalRepository.save(record);

        // Link this live_signal record to the async ml_inference_log row that was
        // just inserted by logShadow() (called ~50ms earlier). live_signal_id was
        // null at logShadow time because BtLiveSignal hadn't been persisted yet.
        mlInferenceLogger.linkLiveSignal(record.getId(), strategy.getId());

        // Phase 2：發 TG，成功後再更新 notifiedAt
        try {
            String msg = buildTelegramMessage(symbol, intervalCode, lastBar, entry, sl, tp,
                    stopLossPct, takeProfitPct, snap, yearDrop,
                    strategy.getStrategyType(), notifyOnly, ensembleDecision);
            notificationPort.broadcast(msg, true);
            record.setNotifiedAt(LocalDateTime.now(ZoneOffset.UTC));
            liveSignalRepository.save(record);
            createSignalVerification(record, ensembleDecision);
            log.info("[LiveSignal] TG notified: strategyId={} symbol={} bar={} entry={}",
                    strategy.getId(), symbol, lastBar.getOpenTime().format(FMT_DISPLAY), entry);
        } catch (Exception e) {
            log.error("[LiveSignal] TG send failed, will retry: strategyId={} bar={} error={}",
                    strategy.getId(), lastBar.getOpenTime().format(FMT_DISPLAY), e.getMessage());
        }

        // Phase 3：自動下單（enabled=true 且策略未標記 notifyOnly 才執行）
        // notifyOnly=true：只發 TG，不自動下單（適用於預警/備用策略）
        if (notifyOnly || !tradingProperties.isEnabled()) {
            auditExpectedValueGateDryRun(strategy, symbol, intervalCode, expectedRDecision,
                    config, notifyOnly, fearGreedGate, entry, tp, sl,
                    stopLossPct, takeProfitPct, snap, record.getId());
            logEntrySkip(strategy, symbol, intervalCode, lastBar,
                    notifyOnly ? "ShadowExecutionIntent" : "TradingDisabled",
                    notifyOnly ? "shadow candidate suppressed before real order"
                            : "tradingProperties.enabled=false",
                    shadowExecutionIntentContext(strategy, expectedRDecision, stopLossPct, takeProfitPct, entry, tp, sl,
                            notifyOnly, noLiveExecutionOnly, fearGreedGate, snap),
                    record.getId());
            return;
        }
        if (tradingProperties.isEnabled() && !notifyOnly) {
            var eventRisk = eventRiskActionOrchestrator.assessNewEntry(
                    strategy, config, symbol, intervalCode, "LONG", record.getId());
            if (!eventRisk.allowed()) {
                log.info("[EventRiskControl] LONG blocked: strategyId={} symbol={} reason={}",
                        strategy.getId(), symbol, eventRisk.reason());
                tradingMetrics.signalFiltered("EventRiskControl", eventRisk.snapshot().level().name());
                record.setAutoTraded(false);
                record.setFilterReason(eventRisk.reason());
                liveSignalRepository.save(record);
                auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                        "EventRiskControl", eventRisk.reason(),
                        eventRisk.auditContext(), record.getId());
                return;
            }

            // P4: pre-trade expected value gate. Rejects entries with non-positive EV
            // or weak expected R before any auto-trade side effects.
            boolean evGateEnabled = getBoolean(config, "preTradeExpectedValueGateEnabled", true);
            double minExpectedR = getDouble(config, "preTradeMinExpectedR", 0.20);
            if (stagedMicroAddEntry) {
                minExpectedR = Math.min(minExpectedR, getDouble(config, "microAddLiveMinExpectedR", 0.0));
            }
            if (evGateEnabled && !expectedRDecision.trusted()) {
                log.info("[LiveSignal] LONG blocked by preTradeExpectedValueGate (untrusted probability): strategyId={} symbol={} provenance={}",
                        strategy.getId(), symbol, expectedRDecision.provenance());
                tradingMetrics.signalFiltered("ExpectedValueGate", "expected_r_provenance_unavailable");
                record.setAutoTraded(false);
                record.setFilterReason("ExpectedValueGate: calibrated win probability unavailable");
                liveSignalRepository.save(record);
                Map<String, Object> evBlockContext = candidateTradePlanContext(
                        expectedRDecision, minExpectedR, stopLossPct, takeProfitPct, entry, tp, sl, snap, fearGreedGate);
                evBlockContext.put("strategyAllowlisted", isTqsStrategyAllowlisted(strategy));
                evBlockContext.put("ev_reason", "EXPECTED_R_PROVENANCE_UNAVAILABLE");
                evBlockContext.put("abort_reason", "AUTO_TRADE_ABORTED");
                evBlockContext.put("gate_enabled", true);
                tradeQualityEngine.applyV0(evBlockContext, "ExpectedValueGate");
                auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                        "ExpectedValueGate", "calibrated win probability unavailable", evBlockContext, record.getId());
                return;
            }
            if (evGateEnabled && expectedR <= 0) {
                log.info("[LiveSignal] LONG blocked by preTradeExpectedValueGate (EV<=0): strategyId={} symbol={} expectedR={}",
                        strategy.getId(), symbol, String.format("%.4f", expectedR));
                tradingMetrics.signalFiltered("ExpectedValueGate", "expected_r<=0");
                record.setAutoTraded(false);
                record.setFilterReason(String.format("ExpectedValueGate: expectedR=%.4f <= 0", expectedR));
                liveSignalRepository.save(record);
                Map<String, Object> evBlockContext = candidateTradePlanContext(
                        expectedRDecision, minExpectedR, stopLossPct, takeProfitPct, entry, tp, sl, snap, fearGreedGate);
                evBlockContext.put("strategyAllowlisted", isTqsStrategyAllowlisted(strategy));
                evBlockContext.put("ev_reason", "expectedR<=0");
                evBlockContext.put("abort_reason", "AUTO_TRADE_ABORTED");
                evBlockContext.put("gate_enabled", true);
                tradeQualityEngine.applyV0(evBlockContext, "ExpectedValueGate");
                auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                        "ExpectedValueGate", "expectedR<=0", evBlockContext, record.getId());
                return;
            }
            if (evGateEnabled && expectedR < minExpectedR) {
                log.info("[LiveSignal] LONG blocked by preTradeExpectedValueGate (below threshold): strategyId={} symbol={} expectedR={} min={}",
                        strategy.getId(), symbol, String.format("%.4f", expectedR), minExpectedR);
                tradingMetrics.signalFiltered("ExpectedValueGate", "expected_r_below_threshold");
                record.setAutoTraded(false);
                record.setFilterReason(String.format("ExpectedValueGate: expectedR=%.4f < %.4f", expectedR, minExpectedR));
                liveSignalRepository.save(record);
                Map<String, Object> evBlockContext = candidateTradePlanContext(
                        expectedRDecision, minExpectedR, stopLossPct, takeProfitPct, entry, tp, sl, snap, fearGreedGate);
                evBlockContext.put("strategyAllowlisted", isTqsStrategyAllowlisted(strategy));
                evBlockContext.put("ev_reason", "expectedR<minExpectedR");
                evBlockContext.put("abort_reason", "AUTO_TRADE_ABORTED");
                evBlockContext.put("gate_enabled", true);
                tradeQualityEngine.applyV0(evBlockContext, "ExpectedValueGate");
                auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                        "ExpectedValueGate", "expectedR below min threshold", evBlockContext, record.getId());
                return;
            }
            if (evGateEnabled) {
                Map<String, Object> evPassContext = candidateTradePlanContext(
                        expectedRDecision, minExpectedR, stopLossPct, takeProfitPct, entry, tp, sl, snap, fearGreedGate);
                evPassContext.put("strategyAllowlisted", isTqsStrategyAllowlisted(strategy));
                evPassContext.put("ev_reason", "pass");
                evPassContext.put("gate_enabled", true);
                evPassContext.put("notify_only", notifyOnly);
                tradeQualityEngine.applyV0(evPassContext, "NONE");
                auditWriter.logAttentionHit(strategy.getId(), symbol, intervalCode,
                        "ExpectedValueGatePass", "INFO", evPassContext);
            }
            if (fearGreedGate.warnOnly()) {
                record.setAutoTraded(false);
                record.setFilterReason("FearGreedWarnOnlyDryRun: " + fearGreedGate.reason());
                liveSignalRepository.save(record);
                logEntrySkip(strategy, symbol, intervalCode, lastBar,
                        "FearGreedWarnOnlyDryRun",
                        "FearGreed WARN_ONLY/TQS penalty candidate stopped before live execution",
                        fearGreedWarnOnlyDryRunContext(strategy, expectedRDecision, minExpectedR,
                                stopLossPct, takeProfitPct, entry, tp, sl, snap, fearGreedGate),
                        record.getId());
                return;
            }

            // 每日虧損熔斷：當日累計 PnL 低於門檻時拒絕開新倉
            DailyLossGuard.GuardResult guard = dailyLossGuard.check();
            if (!guard.allowed()) {
                log.info("[LiveSignal] LONG blocked by daily loss guard: strategyId={} symbol={} {}",
                        strategy.getId(), symbol, guard.reason());
                tradingMetrics.signalFiltered("DailyLossGuard", guard.reason());
                record.setAutoTraded(false);
                record.setFilterReason("DailyLossGuard: " + guard.reason());
                liveSignalRepository.save(record);
                auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                        "DailyLossGuard", guard.reason(),
                        java.util.Map.of("side", "LONG"), record.getId());
                try {
                    notificationPort.broadcast(String.format(
                            "⛔ <b>做多信號被熔斷</b>\n%s [%s]\n%s",
                            symbol, intervalCode, guard.reason()), true);
                } catch (Exception ignored) {}
                return;
            }

            // LongAiFilter：防止在極度貪婪頂部/超買/多頭擠壓環境中誤做多
            double currentRsi = snap != null ? snap.rsi : 50.0;
            LongAiFilter.FilterResult filter = longAiFilter.check(symbol, intervalCode, currentRsi);
            if (!filter.allowed()) {
                log.info("[LiveSignal] LONG filtered: strategyId={} symbol={} reason={}",
                        strategy.getId(), symbol, filter.reason());
                tradingMetrics.signalFiltered("LongAiFilter", filter.reason());
                record.setAutoTraded(false);
                record.setFilterReason("LongAiFilter: " + filter.reason());
                liveSignalRepository.save(record);
                auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                        "LongAiFilter", filter.reason(),
                        java.util.Map.of("side", "LONG", "rsi", currentRsi), record.getId());
                try {
                    notificationPort.broadcast(String.format(
                            "🚫 <b>做多信號被過濾</b>\n%s [%s]\n%s",
                            symbol, intervalCode, filter.reason()), true);
                } catch (Exception ignored) {}
                return;
            }
            // ── Ensemble gate (P0) ──────────────────────────────────────────────────
            // Two-tier: VETO is always active (Gemini DISABLE kill-switch or short_ok=false).
            // BLOCK is config-gated (ensembleGateEnabled) — requires shadow validation first.
            // Applied after other filters so filter block reasons are surfaced to user first.
            if (ensembleDecision != null) {
                boolean isVeto  = "VETO".equals(ensembleDecision.outcome());
                boolean isBlock = ensembleGateEnabled && "BLOCK".equals(ensembleDecision.outcome());
                if (isVeto || isBlock) {
                    String gateLabel  = isVeto ? "VETO" : "BLOCK";
                    String gateReason = isVeto
                            ? ensembleDecision.vetoReason()
                            : String.format("score=%.1f < threshold=%.1f",
                                    ensembleDecision.score(), ensembleDecision.threshold());
                    log.info("[EnsembleGate] LONG {} strategyId={} symbol={} {}",
                            gateLabel, strategy.getId(), symbol, gateReason);
                    tradingMetrics.signalFiltered("EnsembleGate",
                            gateLabel + ":" + gateReason);
                    record.setAutoTraded(false);
                    record.setFilterReason("EnsembleGate[" + gateLabel + "]: " + gateReason);
                    liveSignalRepository.save(record);
                    auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                            "EnsembleGate", gateReason,
                            java.util.Map.of("side", "LONG",
                                    "ensemble_outcome", gateLabel,
                                    "ensemble_score", ensembleDecision.score()), record.getId());
                    try {
                        notificationPort.broadcast(String.format(
                                "🛡 <b>Ensemble %s</b>\n%s [%s]\n%s",
                                gateLabel, symbol, intervalCode, gateReason), true);
                    } catch (Exception ignored) {}
                    return;
                }
            }

            // ── #437 sub-task 4 — Strategy macro filter (independent of ensemble) ──
            // Per-strategy hard floors on macro signals. 獨立於 ensemble shadow,
            // 這些是 strategy 自己的安全網,即使 ensemble Phase 1 不擋,strategy 仍可硬擋。
            //
            // Config keys (per strategy, default 0/disabled):
            //   macroPolymarketBlockPct  — block LONG when polymarket_risk_pct >= this (0=off)
            //   macroFlipBlockMinutes    — block when last regime flip < N min ago (0=off)
            //   macroMlPwinFloor         — block when ml_p_win < this (0=off, independent of mlGateEnabled)
            //
            // 來源:從 ensembleDecision.inputsEcho 讀,因為 ensembleGateway 已 wire 好所有 macro signal。
            // ensembleDecision=null(compute 失敗) → fail-open 不擋(資料不全寧可放行)。
            if (ensembleDecision != null && ensembleDecision.inputsEcho() != null) {
                java.util.Map<String, Object> echo = ensembleDecision.inputsEcho();
                double pmRiskBlock = getDouble(config, "macroPolymarketBlockPct", 0.0);
                if (pmRiskBlock > 0 && echo.get("polymarket_risk_pct") instanceof Number n) {
                    double pmRisk = n.doubleValue();
                    if (pmRisk >= pmRiskBlock) {
                        String reason = String.format("polymarket_risk_pct=%.1f >= %.1f", pmRisk, pmRiskBlock);
                        log.info("[MacroFilter] LONG blocked: strategyId={} {} {}",
                                strategy.getId(), symbol, reason);
                        tradingMetrics.signalFiltered("MacroFilter", "polymarket:" + reason);
                        record.setAutoTraded(false);
                        record.setFilterReason("MacroFilter[polymarket]: " + reason);
                        liveSignalRepository.save(record);
                        auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                                "MacroFilter", reason,
                                java.util.Map.of("side", "LONG", "polymarket_risk_pct", pmRisk), record.getId());
                        try {
                            notificationPort.broadcast(String.format(
                                    "🛑 <b>Macro 攔截 %s [%s]</b>\nPolymarket risk=%.1f%% &gt;= %.1f%% — 信號已記錄,不下單",
                                    symbol, intervalCode, pmRisk, pmRiskBlock), true);
                        } catch (Exception ignored) {}
                        return;
                    }
                }
                double flipBlockMin = getDouble(config, "macroFlipBlockMinutes", 0.0);
                if (flipBlockMin > 0 && echo.get("market_flip_recent_minutes") instanceof Number n) {
                    int flipMin = n.intValue();
                    if (flipMin < flipBlockMin) {
                        String reason = String.format("regime_flip_recent=%dmin < %dmin", flipMin, (int) flipBlockMin);
                        log.info("[MacroFilter] LONG blocked: strategyId={} {} {}",
                                strategy.getId(), symbol, reason);
                        tradingMetrics.signalFiltered("MacroFilter", "flip:" + reason);
                        record.setAutoTraded(false);
                        record.setFilterReason("MacroFilter[flip]: " + reason);
                        liveSignalRepository.save(record);
                        auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                                "MacroFilter", reason,
                                java.util.Map.of("side", "LONG", "flip_recent_minutes", flipMin), record.getId());
                        try {
                            notificationPort.broadcast(String.format(
                                    "🛑 <b>Macro 攔截 %s [%s]</b>\nRegime flip %dmin 前 &lt; %dmin 冷卻期 — 不下單",
                                    symbol, intervalCode, flipMin, (int) flipBlockMin), true);
                        } catch (Exception ignored) {}
                        return;
                    }
                }
                double pWinFloor = getDouble(config, "macroMlPwinFloor", 0.0);
                if (pWinFloor > 0 && echo.get("ml_p_win") instanceof Number n) {
                    double pWin = n.doubleValue();
                    if (pWin < pWinFloor) {
                        String reason = String.format("ml_p_win=%.3f < %.3f", pWin, pWinFloor);
                        log.info("[MacroFilter] LONG blocked: strategyId={} {} {}",
                                strategy.getId(), symbol, reason);
                        tradingMetrics.signalFiltered("MacroFilter", "pwin:" + reason);
                        record.setAutoTraded(false);
                        record.setFilterReason("MacroFilter[pwin_floor]: " + reason);
                        liveSignalRepository.save(record);
                        auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                                "MacroFilter", reason,
                                java.util.Map.of("side", "LONG", "ml_p_win", pWin), record.getId());
                        try {
                            notificationPort.broadcast(String.format(
                                    "🛑 <b>Macro 攔截 %s [%s]</b>\nML p_win=%.3f &lt; %.3f floor — 不下單",
                                    symbol, intervalCode, pWin, pWinFloor), true);
                        } catch (Exception ignored) {}
                        return;
                    }
                }
            }

            // ML Gate（可選，per-strategy config: mlGateEnabled=true）
            // 同步呼叫 HeatWave ML 取 p_win；低於 threshold 攔截下單。
            // 失敗一律 fail-open，不因 ML infra 問題擋掉真實信號。
            // #249: regime-conditional threshold — SIDEWAYS 用較低門檻避免過度攔截
            if (getBoolean(config, "mlGateEnabled", false)) {
                // Determine effective ML threshold based on regime
                double mlThreshold = 0.0; // 0 = use global config threshold
                double buyThreshold = getDouble(config, "buyThreshold", 0.0);
                String regime = effectiveRegime != null ? effectiveRegime.toUpperCase() : "";
                if ("TRENDING_DOWN".equals(regime)) {
                    // Bear market: use strategy buyThreshold (usually higher selectivity)
                    mlThreshold = buyThreshold > 0 ? buyThreshold : 0.0;
                } else if ("SIDEWAYS".equals(regime) || "VOLATILE".equals(regime)) {
                    // Sideways: use lower of buyThreshold or config, or a relaxed default
                    // mlGateSidewaysThreshold overrides if set; otherwise use buyThreshold
                    double sidewaysOverride = getDouble(config, "mlGateSidewaysThreshold", 0.0);
                    mlThreshold = sidewaysOverride > 0 ? sidewaysOverride
                                  : (buyThreshold > 0 ? buyThreshold * 0.85 : 0.0);
                } else {
                    // TRENDING_UP or UNKNOWN: use buyThreshold from strategy config
                    mlThreshold = buyThreshold > 0 ? buyThreshold : 0.0;
                }
                com.agora.service.ml.MlInferenceLogger.GateResult gate =
                        mlInferenceLogger.gateCheck(record.getId(), symbol, intervalCode,
                                "LONG", strategy.getId(), klines, lastIndex, mlThreshold);
                if (!gate.pass()) {
                    log.info("[MlGate] LONG blocked: strategyId={} symbol={} p_win={}",
                            strategy.getId(), symbol, String.format("%.3f", gate.pWin()));
                    tradingMetrics.signalFiltered("MlGate", gate.reason());
                    record.setAutoTraded(false);
                    record.setFilterReason("MlGate: p_win=" + String.format("%.3f", gate.pWin())
                            + " < " + String.format("%.2f", mlInferenceLogger.getThreshold()));
                    liveSignalRepository.save(record);
                    auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                            "MlGate", gate.reason(),
                            java.util.Map.of("side", "LONG", "p_win", gate.pWin()), record.getId());
                    try {
                        notificationPort.broadcast(String.format(
                                "🤖 <b>ML Gate 攔截 %s [%s]</b>\np_win=<b>%.3f</b> &lt; %.2f — 信號已記錄，不執行下單",
                                symbol, intervalCode, gate.pWin(), mlInferenceLogger.getThreshold()), true);
                    } catch (Exception ignored) {}
                    return;
                }
            }
            double nnOut = snap != null ? snap.nnOutput : 0.50;
            if (!auditWriter.logAutonomousExecutionIntentSync(strategy.getId(), symbol, intervalCode,
                    record.getId(),
                    liveAutonomousExecutionIntentContext(strategy, expectedRDecision, stopLossPct, takeProfitPct, entry, tp, sl,
                            nnOut, fearGreedGate, snap))) {
                markAutoTradeSkipped(record, "RuntimeEvidence: no-evidence-no-trade");
                logEntrySkip(strategy, symbol, intervalCode, lastBar,
                        "RuntimeEvidenceRequired",
                        "runtime evidence could not be written before live autonomous order",
                        Map.of("side", "LONG", "executionMode", "LIVE_AUTONOMOUS",
                                "orderSent", false, "suppressionReason", "NO_RUNTIME_EVIDENCE"),
                        record.getId());
                return;
            }
            autoTrade(record, symbol, tp, sl, nnOut, wickAwareSl.applied(),
                    stagedMicroAddEntry, stagedMicroAddMaxNotionalUsdt);
        } else if (!notifyOnly) {
            record.setAutoTraded(false);
            record.setFilterReason("AutoTrade: trading disabled");
            liveSignalRepository.save(record);
            logEntrySkip(strategy, symbol, intervalCode, lastBar, "TradingDisabled",
                    "tradingProperties.enabled=false",
                    Map.of("side", "LONG"), record.getId());
        }
    }

    private void logEntrySkip(BtStrategy strategy, String symbol, String intervalCode,
                              MdKline lastBar, String blocker, String reason,
                              Map<String, Object> context) {
        logEntrySkip(strategy, symbol, intervalCode, lastBar, blocker, reason, context, null);
    }

    private void auditExpectedValueGateDryRun(BtStrategy strategy,
                                              String symbol,
                                              String intervalCode,
                                              ExpectedRDecision expectedRDecision,
                                              Map<String, Object> config,
                                              boolean notifyOnly,
                                              FearGreedGateDecision fearGreedGate,
                                              BigDecimal entry,
                                              BigDecimal tp,
                                              BigDecimal sl,
                                              double stopLossPct,
                                              double takeProfitPct,
                                              LiveSignalContext.Snapshot snap,
                                              Long liveSignalId) {
        boolean evGateEnabled = getBoolean(config, "preTradeExpectedValueGateEnabled", true);
        if (!evGateEnabled) {
            return;
        }
        double expectedR = expectedRDecision.expectedR();
        double minExpectedR = getDouble(config, "preTradeMinExpectedR", 0.20);
        Map<String, Object> evContext = candidateTradePlanContext(
                expectedRDecision, minExpectedR, stopLossPct, takeProfitPct, entry, tp, sl, snap, fearGreedGate);
        evContext.put("strategyAllowlisted", isTqsStrategyAllowlisted(strategy));
        evContext.put("gate_enabled", true);
        evContext.put("dry_run", true);
        evContext.put("notify_only", notifyOnly);
        evContext.put("abort_reason", "DRY_RUN_ONLY");
        evContext.put("candidateContinuedToEv", true);
        evContext.put("candidateContinuedToTqs", true);
        evContext.put("executionMode", notifyOnly ? "SHADOW" : "DRY_RUN");
        evContext.put("orderSent", false);
        evContext.put("suppressionReason", notifyOnly ? "SHADOW_MODE" : "TRADING_DISABLED");
        evContext.put("intentCreated", true);
        evContext.put("ocoPlanCreated", true);
        evContext.put("riskGateResult", "NOT_EVALUATED_SHADOW");
        evContext.put("selectedAction", "SHADOW_EV_DRY_RUN");
        if (!expectedRDecision.trusted()) {
            evContext.put("ev_reason", "EXPECTED_R_PROVENANCE_UNAVAILABLE");
            tradeQualityEngine.applyV0(evContext, "ExpectedValueGate");
            auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                    "ExpectedValueGate", "calibrated win probability unavailable", evContext, liveSignalId);
            return;
        }
        if (expectedR <= 0) {
            evContext.put("ev_reason", "expectedR<=0");
            tradeQualityEngine.applyV0(evContext, "ExpectedValueGate");
            auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                    "ExpectedValueGate", "expectedR<=0", evContext, liveSignalId);
            return;
        }
        if (expectedR < minExpectedR) {
            evContext.put("ev_reason", "expectedR<minExpectedR");
            tradeQualityEngine.applyV0(evContext, "ExpectedValueGate");
            auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                    "ExpectedValueGate", "expectedR below min threshold", evContext, liveSignalId);
            return;
        }
        evContext.put("ev_reason", "pass");
        tradeQualityEngine.applyV0(evContext, "NONE");
        auditWriter.logAttentionHit(strategy.getId(), symbol, intervalCode,
                "ExpectedValueGatePass", "INFO", evContext);
    }

    private Map<String, Object> shadowExecutionIntentContext(BtStrategy strategy,
                                                             ExpectedRDecision expectedRDecision,
                                                             double stopLossPct,
                                                             double takeProfitPct,
                                                             BigDecimal entry,
                                                             BigDecimal tp,
                                                             BigDecimal sl,
                                                             boolean notifyOnly,
                                                             boolean noLiveExecutionOnly,
                                                             FearGreedGateDecision fearGreedGate,
                                                             LiveSignalContext.Snapshot snap) {
        Map<String, Object> ctx = autonomousIntentBaseContext(expectedRDecision, stopLossPct, takeProfitPct,
                entry, tp, sl, snap, fearGreedGate);
        ctx.put("strategyAllowlisted", isTqsStrategyAllowlisted(strategy));
        ctx.put("executionMode", notifyOnly ? "SHADOW" : "DRY_RUN");
        ctx.put("selectedAction", "SHADOW_EXECUTION_INTENT");
        ctx.put("decision", "SUPPRESS_ORDER");
        ctx.put("intentCreated", true);
        ctx.put("ocoPlanCreated", true);
        ctx.put("orderSent", false);
        ctx.put("suppressionReason", noLiveExecutionOnly
                ? "NO_LIVE_EXECUTION_ONLY_STRATEGY"
                : (notifyOnly ? "SHADOW_MODE" : "TRADING_DISABLED"));
        ctx.put("candidateContinuedToEv", true);
        ctx.put("candidateContinuedToTqs", true);
        ctx.put("riskGateResult", "NOT_EVALUATED_SHADOW");
        ctx.put("terminalBlocker", "NONE");
        tradeQualityEngine.applyV0(ctx, "NONE");
        return ctx;
    }

    private Map<String, Object> fearGreedWarnOnlyDryRunContext(BtStrategy strategy,
                                                               ExpectedRDecision expectedRDecision,
                                                               double minExpectedR,
                                                               double stopLossPct,
                                                               double takeProfitPct,
                                                               BigDecimal entry,
                                                               BigDecimal tp,
                                                               BigDecimal sl,
                                                               LiveSignalContext.Snapshot snap,
                                                               FearGreedGateDecision fearGreedGate) {
        Map<String, Object> ctx = candidateTradePlanContext(
                expectedRDecision, minExpectedR, stopLossPct, takeProfitPct, entry, tp, sl, snap, fearGreedGate);
        ctx.put("strategyAllowlisted", isTqsStrategyAllowlisted(strategy));
        ctx.put("executionMode", "DRY_RUN");
        ctx.put("selectedAction", "FEAR_GREED_WARN_ONLY_SUPPRESS");
        ctx.put("decision", "SUPPRESS_ORDER");
        ctx.put("ev_reason", expectedRDecision.trusted()
                ? (expectedRDecision.expectedR() > 0 ? "pass_or_pending_threshold" : "expectedR<=0")
                : "EXPECTED_R_PROVENANCE_UNAVAILABLE");
        ctx.put("gate_enabled", true);
        ctx.put("dry_run", true);
        ctx.put("candidateContinuedToEv", true);
        ctx.put("candidateContinuedToTqs", true);
        ctx.put("intentCreated", true);
        ctx.put("ocoPlanCreated", true);
        ctx.put("orderSent", false);
        ctx.put("suppressionReason", "FEAR_GREED_WARN_ONLY_DRY_RUN");
        ctx.put("riskGateResult", "NOT_EVALUATED_WARN_ONLY");
        ctx.put("terminalBlocker", "NONE");
        tradeQualityEngine.applyV0(ctx, "NONE");
        return ctx;
    }

    private Map<String, Object> candidateTradePlanContext(ExpectedRDecision expectedRDecision,
                                                          double minExpectedR,
                                                          double stopLossPct,
                                                          double takeProfitPct,
                                                          BigDecimal entry,
                                                          BigDecimal tp,
                                                          BigDecimal sl,
                                                          LiveSignalContext.Snapshot snap,
                                                          FearGreedGateDecision fearGreedGate) {
        Map<String, Object> ctx = autonomousIntentBaseContext(
                expectedRDecision, stopLossPct, takeProfitPct, entry, tp, sl, snap, fearGreedGate);
        ctx.put("min_expected_r", minExpectedR);
        ctx.put("candidateEntry", entry);
        ctx.put("candidateTp", tp);
        ctx.put("candidateSl", sl);
        ctx.put("candidateQty", "NOT_SIZED");
        ctx.put("riskUsdt", "NOT_SIZED");
        return ctx;
    }

    private Map<String, Object> liveAutonomousExecutionIntentContext(BtStrategy strategy,
                                                                     ExpectedRDecision expectedRDecision,
                                                                     double stopLossPct,
                                                                     double takeProfitPct,
                                                                     BigDecimal entry,
                                                                     BigDecimal tp,
                                                                     BigDecimal sl,
                                                                     double nnOut,
                                                                     FearGreedGateDecision fearGreedGate,
                                                                     LiveSignalContext.Snapshot snap) {
        Map<String, Object> ctx = autonomousIntentBaseContext(expectedRDecision, stopLossPct, takeProfitPct,
                entry, tp, sl, snap, fearGreedGate);
        ctx.put("strategyAllowlisted", isTqsStrategyAllowlisted(strategy));
        ctx.put("executionMode", "LIVE_AUTONOMOUS");
        ctx.put("selectedAction", "LIVE_EXECUTION_INTENT");
        ctx.put("decision", "ALLOW_ORDER_AFTER_EVIDENCE");
        ctx.put("intentCreated", true);
        ctx.put("ocoPlanCreated", true);
        ctx.put("orderSent", false);
        ctx.put("suppressionReason", "NONE");
        ctx.put("candidateContinuedToEv", true);
        ctx.put("candidateContinuedToTqs", true);
        ctx.put("riskGateResult", "PASSED_PRE_ORDER_GUARDS");
        ctx.put("nnOutput", nnOut);
        tradeQualityEngine.applyV0(ctx, "NONE");
        return ctx;
    }

    private Map<String, Object> autonomousIntentBaseContext(ExpectedRDecision expectedRDecision,
                                                           double stopLossPct,
                                                           double takeProfitPct,
                                                           BigDecimal entry,
                                                           BigDecimal tp,
                                                           BigDecimal sl,
                                                           LiveSignalContext.Snapshot snap,
                                                           FearGreedGateDecision fearGreedGate) {
        Map<String, Object> ctx = new LinkedHashMap<>(fearGreedGate.context());
        ctx.put("side", "LONG");
        ctx.put("candidate_side", "LONG");
        ctx.put("signalSource", "LiveSignalEvaluator");
        ctx.put("expected_r", expectedRDecision.expectedR());
        ctx.put("expected_r_trusted", expectedRDecision.trusted());
        ctx.put("expected_r_provenance", expectedRDecision.provenance());
        if (expectedRDecision.pWin() == null) ctx.put("p_win", null);
        else ctx.put("p_win", expectedRDecision.pWin());
        ctx.put("p_win_provenance", expectedRDecision.provenance());
        ctx.put("ev_reason", expectedRDecision.trusted()
                ? (expectedRDecision.expectedR() > 0 ? "pass_or_pending_threshold" : "expectedR<=0")
                : "EXPECTED_R_PROVENANCE_UNAVAILABLE");
        ctx.put("score", snap != null ? snap.score : null);
        ctx.put("mlFeatureAvailable", snap != null);
        ctx.put("entry", entry);
        ctx.put("tp", tp);
        ctx.put("sl", sl);
        ctx.put("stop_loss_pct", stopLossPct);
        ctx.put("take_profit_pct", takeProfitPct);
        ctx.put("ocoCapable", entry != null && tp != null && sl != null);
        return ctx;
    }

    private Map<String, Object> dataFreshnessContext(Long strategyId,
                                                     String symbol,
                                                     String intervalCode,
                                                     LocalDateTime nowUtc,
                                                     MdKline newest,
                                                     LocalDateTime latestCloseEstimate,
                                                     long minSinceOpen,
                                                     long staleThreshold,
                                                     int intervalMinutes,
                                                     String klineSource,
                                                     int klinesLoaded) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("replayCandidateId", DataFreshnessReplayCandidateIds.create(
                strategyId, symbol, intervalCode, klineSource, newest.getOpenTime()));
        ctx.put("replayCandidateVersion", "dfsr1");
        ctx.put("replayCandidateStatus", "L0_DATA_FRESHNESS_BLOCKED_NO_CANDIDATE_PLAN");
        ctx.put("liveSignalId", null);
        ctx.put("orderSent", false);
        ctx.put("intentCreated", false);
        ctx.put("ocoPlanCreated", false);
        ctx.put("stale_minutes", minSinceOpen);
        ctx.put("threshold_minutes", staleThreshold);
        ctx.put("latest_bar_open", newest.getOpenTime().toString());
        ctx.put("latest_bar_close_estimate", latestCloseEstimate.toString());
        ctx.put("now_utc", nowUtc.toString());
        ctx.put("interval_minutes", intervalMinutes);
        ctx.put("kline_source", klineSource);
        ctx.put("klines_loaded", klinesLoaded);
        ctx.put("classification_hint", minSinceOpen > staleThreshold ? "TRUE_STALE_KLINE" : "REVIEW");
        return ctx;
    }

    private void logEntrySkip(BtStrategy strategy, String symbol, String intervalCode,
                              MdKline lastBar, String blocker, String reason,
                              Map<String, Object> context, Long liveSignalId) {
        try {
            auditWriter.logEntrySkip(strategy.getId(), symbol, intervalCode,
                    lastBar != null ? lastBar.getOpenTime() : null,
                    blocker, reason, context, liveSignalId);
        } catch (Exception e) {
            log.debug("[LiveSignal] ENTRY_SKIP audit failed strategyId={} blocker={}: {}",
                    strategy.getId(), blocker, e.getMessage());
        }
    }

    /**
     * SELL 訊號觸發時：
     * 1. 關閉所有未出場的 LONG 倉位並發 TG 通知。
     * 2. 若 allowShort=true 且目前無開放的 SHORT 倉位，開新空頭。
     */
    private void handleExitSignal(BtStrategy strategy, String symbol, String intervalCode,
                                   MdKline lastBar, LiveSignalContext.Snapshot snap,
                                   Map<String, Object> config,
                                   com.agora.service.meta.TradeDecisionEngine.Decision ensembleDecision) {
        List<BtLiveSignal> openPositions = liveSignalRepository
                .findByStrategyIdAndSymbolAndIntervalCodeAndExitTimeIsNullAndNotifiedAtIsNotNull(
                        strategy.getId(), symbol, intervalCode);

        // 只處理 LONG（或 side 未設定的舊資料）
        List<BtLiveSignal> openLongs = openPositions.stream()
                .filter(p -> !"SHORT".equals(p.getSide())).toList();
        boolean hasOpenShort = openPositions.stream().anyMatch(p -> "SHORT".equals(p.getSide()));
        boolean allowShort   = getBoolean(config, "allowShort", false)
                            || getBoolean(config, "shortOnly", false);

        if (openLongs.isEmpty() && !allowShort) return;

        BigDecimal exitPrice = lastBar.getClosePrice();
        LocalDateTime exitTime = LocalDateTime.now(ZoneOffset.UTC);

        // ── 1. 關閉所有未出場的 LONG ──────────────────────
        for (BtLiveSignal pos : openLongs) {
            if (tradingProperties.isEnabled() && Boolean.TRUE.equals(pos.getAutoTraded())
                    && pos.getTradedQty() != null) {
                autoSell(pos, symbol, exitPrice);
            } else {
                pos.setExitPrice(exitPrice);
                pos.setExitTime(exitTime);
                pos.setExitReason("SELL_SIGNAL");
                // #420: also compute realizedPnl when qty is available — the audit
                // gap on paper / non-auto-traded SELL_SIGNAL exits has been blocking
                // analyzeStrategyTrades / getMonthlyPnlOverview accuracy.
                // Fallback chain: tradedQty → ocoQty (entry was via OCO).
                // Entry: actualEntryPrice (auto-traded) → entryPrice (paper).
                BigDecimal qtyForPnl = pos.getTradedQty() != null
                        ? pos.getTradedQty() : pos.getOcoQty();
                BigDecimal entryForPnl = pos.getActualEntryPrice() != null
                        ? pos.getActualEntryPrice() : pos.getEntryPrice();
                if (qtyForPnl != null && entryForPnl != null) {
                    pos.setRealizedPnl(calcPnl(entryForPnl, exitPrice, qtyForPnl));
                }
                liveSignalRepository.save(pos);
            }

            BigDecimal refEntry = (Boolean.TRUE.equals(pos.getAutoTraded()) && pos.getActualEntryPrice() != null)
                    ? pos.getActualEntryPrice() : pos.getEntryPrice();
            BigDecimal refExit = pos.getExitPrice() != null ? pos.getExitPrice() : exitPrice;
            double pnlPct = refExit.subtract(refEntry)
                    .divide(refEntry, 6, RoundingMode.HALF_UP).doubleValue();
            log.info("[LiveSignal] Exit LONG: strategyId={} symbol={} pnl={}%",
                    strategy.getId(), symbol, String.format("%.2f", pnlPct * 100));

            // Backfill actual_outcome in ml_inference_log for alignment-rate tracking.
            // outcome 1=win 0=loss; linked via live_signal_id (set by linkLiveSignal at entry).
            mlInferenceLogger.backfillOutcome(pos.getId(), pnlPct > 0 ? 1 : 0, pnlPct);

            try {
                String msg = buildExitMessage(symbol, intervalCode, lastBar,
                        refEntry, refExit, pnlPct, snap, strategy.getStrategyType())
                        + formatEnsembleForTg(ensembleDecision);
                notificationPort.broadcast(msg, true);
            } catch (Exception e) {
                log.error("[LiveSignal] Exit TG failed: strategyId={} error={}", strategy.getId(), e.getMessage());
            }
        }

        // ── 2. 若 allowShort=true 且無現有空頭，開新 SHORT ──
        if (allowShort && !hasOpenShort && tradingProperties.isEnabled()) {
            boolean notifyOnly = getBoolean(config, "notifyOnly", false);
            if (!notifyOnly) {
                // 冷卻期：同策略同 symbol 在 4h 內不重複開空（防止連 K 線連續觸發）
                LocalDateTime cooldownCutoff = LocalDateTime.now(ZoneOffset.UTC).minusHours(4);
                boolean inCooldown = liveSignalRepository
                        .findByStrategyIdAndCreatedAtAfter(strategy.getId(), cooldownCutoff)
                        .stream()
                        .anyMatch(s -> symbol.equals(s.getSymbol()) && "SHORT".equals(s.getSide()));
                if (inCooldown) {
                    log.debug("[LiveSignal] SHORT cooldown active strategyId={} symbol={}", strategy.getId(), symbol);
                    return;
                }

                // SL/TP 先算好（filter-blocked 紀錄也會存這些值，供事後分析）
                double[] shortSlTp = computeSlTpPcts(config, symbol, intervalCode);
                double stopLossPct   = shortSlTp[0];
                double takeProfitPct = shortSlTp[1];
                BigDecimal shortTp = exitPrice
                        .multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(takeProfitPct)))
                        .setScale(2, RoundingMode.HALF_UP);
                BigDecimal shortSl = exitPrice
                        .multiply(BigDecimal.ONE.add(BigDecimal.valueOf(stopLossPct)))
                        .setScale(2, RoundingMode.HALF_UP);

                // 每日虧損熔斷：當日累計 PnL 低於門檻時拒絕開新空倉
                DailyLossGuard.GuardResult guard = dailyLossGuard.check();
                if (!guard.allowed()) {
                    log.info("[LiveSignal] SHORT blocked by daily loss guard: strategyId={} symbol={} {}",
                            strategy.getId(), symbol, guard.reason());
                    tradingMetrics.signalFiltered("DailyLossGuard", guard.reason());
                    saveFilteredShort(strategy.getId(), symbol, intervalCode, lastBar,
                            exitPrice, shortSl, shortTp, snap,
                            "DailyLossGuard: " + guard.reason());
                    auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                            "DailyLossGuard", guard.reason(),
                            java.util.Map.of("side", "SHORT"));
                    try {
                        notificationPort.broadcast(String.format(
                                "⛔ <b>做空信號被熔斷</b>\n%s [%s]\n%s",
                                symbol, intervalCode, guard.reason()), true);
                    } catch (Exception ignored) {}
                    return;
                }

                // 多層 AI 防護過濾：防止在反彈行情或超賣環境中誤做空
                double currentRsi = snap != null ? snap.rsi : 50.0;
                ShortAiFilter.FilterResult filter = shortAiFilter.check(symbol, intervalCode, currentRsi);
                if (!filter.allowed()) {
                    log.info("[LiveSignal] SHORT filtered: strategyId={} symbol={} reason={}",
                            strategy.getId(), symbol, filter.reason());
                    tradingMetrics.signalFiltered("ShortAiFilter", filter.reason());
                    saveFilteredShort(strategy.getId(), symbol, intervalCode, lastBar,
                            exitPrice, shortSl, shortTp, snap,
                            "ShortAiFilter: " + filter.reason());
                    auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                            "ShortAiFilter", filter.reason(),
                            java.util.Map.of("side", "SHORT", "rsi", currentRsi));
                    try {
                        notificationPort.broadcast(String.format(
                                "🚫 <b>做空信號被過濾</b>\n%s [%s]\n%s",
                                symbol, intervalCode, filter.reason()), true);
                    } catch (Exception ignored) {}
                    return;
                }

                double nnOut = snap != null ? snap.nnOutput : 0.80;
                autoOpenShort(strategy, symbol, intervalCode, lastBar, exitPrice, shortTp, shortSl, snap, nnOut, ensembleDecision);
            }
        }
    }

    /**
     * 建立一筆被過濾/熔斷的 SHORT 紀錄（未實際下單），供歷史分析使用。
     * autoTraded=false、filterReason 記錄攔截原因；notifiedAt=now 以避免被重試機制重送 TG。
     */
    private void saveFilteredShort(Long strategyId, String symbol, String intervalCode,
                                    com.agora.model.MdKline lastBar, BigDecimal entry,
                                    BigDecimal sl, BigDecimal tp,
                                    LiveSignalContext.Snapshot snap, String filterReason) {
        try {
            BtLiveSignal rec = new BtLiveSignal();
            rec.setStrategyId(strategyId);
            rec.setSymbol(symbol);
            rec.setIntervalCode(intervalCode);
            rec.setBarOpenTime(lastBar.getOpenTime());
            rec.setEntryPrice(entry);
            rec.setSuggestedSl(sl);
            rec.setSuggestedTp(tp);
            rec.setScore(snap != null
                    ? BigDecimal.valueOf(snap.score).setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO);
            rec.setNnOutput(snap != null
                    ? BigDecimal.valueOf(snap.nnOutput).setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO);
            rec.setSide("SHORT");
            rec.setAutoTraded(false);
            rec.setFilterReason(filterReason);
            rec.setNotifiedAt(LocalDateTime.now(ZoneOffset.UTC));
            liveSignalRepository.save(rec);
        } catch (Exception e) {
            log.warn("[LiveSignal] saveFilteredShort failed for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * 自動買入：下市價單 → 掛 OCO → 更新 DB → 發 TG 通知。
     * 任一步驟失敗都只記錄 log + TG 警告，不影響原有訊號流程。
     */
    // synchronized 防止兩個策略同時觸發時的 race condition（開倉上限檢查到實際下單之間）
    private synchronized void autoTrade(BtLiveSignal record, String symbol,
                                        BigDecimal tp, BigDecimal sl, double nnOutput,
                                        boolean forceRiskSizingForWickAwareSl,
                                        boolean stagedMicroAddEntry,
                                        double stagedMicroAddMaxNotionalUsdt) {
        // 信心度動態倉位：nnOutput >= 0.90 → ×2.0；>= 0.85 → ×1.5；其餘 → ×1.0
        double baseAmount = tradingProperties.getTradeAmountUsdt();
        double legacyTradeAmount;
        if (nnOutput >= 0.90) {
            legacyTradeAmount = baseAmount * 2.0;
        } else if (nnOutput >= 0.85) {
            legacyTradeAmount = baseAmount * 1.5;
        } else {
            legacyTradeAmount = baseAmount;
        }
        double tradeAmount = legacyTradeAmount;
        PositionSizingService.PositionSizingDecision sizingDecision =
                positionSizingService.calculate(symbol, record.getStrategyId(), record.getEntryPrice(),
                        tp, sl, nnOutput, legacyTradeAmount, null);
        if (tradingProperties.isPositionSizingLiveEnabled() || forceRiskSizingForWickAwareSl) {
            tradeAmount = sizingDecision.finalAmountUsdt();
            if (forceRiskSizingForWickAwareSl && !tradingProperties.isPositionSizingLiveEnabled()) {
                tradeAmount = sizingDecision.recommendedAmountUsdt();
                log.info("[AutoTrade] spotWickAware structural SL uses risk-sized notional despite global sizing shadow: legacy={} recommended={}",
                        legacyTradeAmount, tradeAmount);
            }
        }
        if (shouldSkipRiskSizedAutoTrade(sizingDecision, forceRiskSizingForWickAwareSl)) {
            markRiskSizedAutoTradeSkipped(record, symbol, sizingDecision);
            return;
        }
        if (stagedMicroAddEntry && stagedMicroAddMaxNotionalUsdt > 0 && tradeAmount > stagedMicroAddMaxNotionalUsdt) {
            log.info("[AutoTrade] staged micro-add notional cap applied: strategyId={} symbol={} amount={} cap={}",
                    record.getStrategyId(), symbol, tradeAmount, stagedMicroAddMaxNotionalUsdt);
            tradeAmount = stagedMicroAddMaxNotionalUsdt;
        }
        tradeAmount = applyLegacySecondaryNotionalCap(record, symbol, tradeAmount, "initial");

        // 上限檢查（synchronized 確保此處的計數與下方下單是原子的）
        long openCount = liveSignalRepository.countByAutoTradedIsTrueAndExitTimeIsNull();
        if (openCount >= tradingProperties.getMaxOpenPositions()) {
            log.info("[AutoTrade] Skip: maxOpenPositions={} reached (current={})",
                    tradingProperties.getMaxOpenPositions(), openCount);
            markAutoTradeSkipped(record, "AutoTrade: maxOpenPositions reached");
            return;
        }

        // 同 symbol 重複開倉檢查
        if (!tradingProperties.isAllowConcurrentOnSameSymbol()
                && !stagedMicroAddEntry
                && liveSignalRepository.existsBySymbolAndAutoTradedIsTrueAndExitTimeIsNull(symbol)) {
            log.info("[AutoTrade] Skip: already has open position on {}", symbol);
            markAutoTradeSkipped(record, "AutoTrade: existing open position on same symbol");
            return;
        }
        if (!tradingProperties.isAllowConcurrentOnSameSymbol() && stagedMicroAddEntry) {
            log.info("[AutoTrade] same-symbol guard passed by staged micro-add policy: strategyId={} symbol={} cap={}",
                    record.getStrategyId(), symbol, stagedMicroAddMaxNotionalUsdt);
        }

        // 餘額檢查：OcoPositionPollerScheduler 每 10 分鐘主動維護交易池緩衝，此為最後安全網。
        // 若仍不足（剛好在 scheduler 兩次之間觸發信號），嘗試從 Simple Earn 即時補足。
        Double availableUsdt = null;
        try {
            String balStr = okxTradingService.getUsdtBalance();
            if (!"N/A".equals(balStr)) {
                double avail = Double.parseDouble(balStr);
                availableUsdt = avail;
                if (tradingProperties.isPositionSizingShadowEnabled()
                        || tradingProperties.isPositionSizingLiveEnabled()) {
                    sizingDecision = positionSizingService.calculate(symbol, record.getStrategyId(),
                            record.getEntryPrice(), tp, sl, nnOutput, legacyTradeAmount, availableUsdt);
                    if (tradingProperties.isPositionSizingLiveEnabled() || forceRiskSizingForWickAwareSl) {
                        tradeAmount = sizingDecision.finalAmountUsdt();
                        if (forceRiskSizingForWickAwareSl && !tradingProperties.isPositionSizingLiveEnabled()) {
                            tradeAmount = sizingDecision.recommendedAmountUsdt();
                        }
                    }
                    if (shouldSkipRiskSizedAutoTrade(sizingDecision, forceRiskSizingForWickAwareSl)) {
                        markRiskSizedAutoTradeSkipped(record, symbol, sizingDecision);
                        return;
                    }
                }
                if (stagedMicroAddEntry && stagedMicroAddMaxNotionalUsdt > 0 && tradeAmount > stagedMicroAddMaxNotionalUsdt) {
                    log.info("[AutoTrade] staged micro-add notional cap re-applied after balance sizing: strategyId={} symbol={} amount={} cap={}",
                            record.getStrategyId(), symbol, tradeAmount, stagedMicroAddMaxNotionalUsdt);
                    tradeAmount = stagedMicroAddMaxNotionalUsdt;
                }
                tradeAmount = applyLegacySecondaryNotionalCap(record, symbol, tradeAmount, "balance");
                if (avail < tradeAmount) {
                    boolean topped = false;
                    try {
                        topped = okxEarnService.topUpTradingBuffer(BigDecimal.valueOf(avail));
                        if (topped) {
                            log.info("[AutoTrade] Emergency top-up from Simple Earn: avail={} tradeAmount={}",
                                    avail, tradeAmount);
                        }
                    } catch (Exception ex) {
                        log.warn("[AutoTrade] Simple Earn emergency top-up failed: {}", ex.getMessage());
                    }
                    if (!topped) {
                        log.warn("[AutoTrade] Skip: insufficient USDT {} < {} and Earn also insufficient",
                                avail, tradeAmount);
                        markAutoTradeSkipped(record, "AutoTrade: insufficient USDT and Earn top-up unavailable");
                        notificationPort.broadcast(
                                String.format("⚠️ <b>AutoTrade 跳過</b>\nUSDT 餘額不足 (%.2f)，Simple Earn 也不足，無法買入 %s",
                                        avail, symbol), true);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[AutoTrade] Balance check failed, proceeding anyway: {}", e.getMessage());
        }

        // ── L4: Pre-trade friction estimate (shadow mode — log only, never blocks) ──────
        // Estimate market-order slippage by walking the live OKX orderbook.
        // Combined with the fixed 0.1% maker fee, this tells us the real entry cost.
        // Shadow-mode: a WARNING is emitted when friction is high, but the trade still goes through.
        // Future: once threshold is calibrated on live data, gate can be enabled.
        double estimatedSlippagePct = 0;
        try {
            estimatedSlippagePct = orderbookImbalanceService.estimateSlippagePct(symbol, tradeAmount);
            double feeRate = 0.001;  // OKX taker 0.10%
            double totalFriction = estimatedSlippagePct + feeRate;
            log.info("[AutoTrade] FrictionEst: symbol={} size=${} slippage={:.4f}% fee={:.2f}% total={:.4f}%",
                    symbol, tradeAmount,
                    estimatedSlippagePct * 100, feeRate * 100, totalFriction * 100);
            if (estimatedSlippagePct > 0.003) {  // > 0.3% slippage is unusual for BTC/ETH
                log.warn("[AutoTrade] HIGH SLIPPAGE WARNING: symbol={} estimatedSlippage={:.4f}% " +
                         "— orderbook may be thin; consider market conditions before trading",
                         symbol, estimatedSlippagePct * 100);
            }
        } catch (Exception e) {
            log.debug("[AutoTrade] Slippage estimation failed (non-blocking): {}", e.getMessage());
        }

        // buySucceeded 旗標：區分「下單失敗」與「下單成功但後續 DB/OCO 失敗」
        boolean buySucceeded = false;
        try {
            // 市價買入（信心度調整後金額）
            log.info("[AutoTrade] nn={} → tradeAmount={} USDT (base={})", nnOutput, tradeAmount, baseAmount);
            TradeResult result = tradingService.placeMarketBuy(symbol, tradeAmount);
            buySucceeded = true;  // 買入已成交，後續失敗不應報「買入失敗」
            tradingMetrics.orderPlaced(symbol, "LONG_OPEN", "ok");

            record.setAutoTraded(true);
            record.setExchangeOrderId(result.getOrderId());
            record.setActualEntryPrice(result.getAvgPrice());
            record.setTradedQty(result.getQty());
            liveSignalRepository.save(record);

            log.info("[AutoTrade] Buy OK: symbol={} orderId={} qty={} avgPrice={}",
                    symbol, result.getOrderId(), result.getQty(), result.getAvgPrice());

            // 掛 OCO（止盈 + 止損）：用 OKX availBal 取代 DB qty，避免手續費差異導致 51008
            BigDecimal ocoQty = resolveOcoQty(symbol, result.getQty());
            if (ocoQty.compareTo(result.getQty()) != 0) {
                record.setTradedQty(ocoQty);  // 同步修正 DB 記錄（Grid 佔用後剩餘量）
            }
            Long ocoListId = placeOcoWithRetry(symbol, ocoQty, tp, sl);
            // 永遠記錄 ocoQty（即使掛單失敗），讓 checkAndClose PnL 使用正確數量
            record.setOcoQty(ocoQty);
            if (ocoListId != null) {
                record.setOcoOrderListId(ocoListId);
                log.info("[AutoTrade] OCO OK: symbol={} algoId={} tp={} sl={} ocoQty={}", symbol, ocoListId, tp, sl, ocoQty);
                ocoAdjustmentAuditWriter.log(record, "INITIAL_OCO", null, ocoListId,
                        null, tp, null, sl, null, ocoQty, "LiveSignalEvaluator.autoTrade", "initial spot OCO");
            }
            liveSignalRepository.save(record);

            // Meta-Control audit: AUTOTRADE_OK (includes L4 slippage estimate for post-analysis)
            java.util.Map<String, Object> tradeCtx = new java.util.LinkedHashMap<>();
            tradeCtx.put("side",  "LONG");
            tradeCtx.put("entry", result.getAvgPrice());
            tradeCtx.put("qty",   ocoQty);
            tradeCtx.put("tp",    tp);
            tradeCtx.put("sl",    sl);
            tradeCtx.put("ocoOk", ocoListId != null);
            tradeCtx.put("staged_micro_add_entry", stagedMicroAddEntry);
            tradeCtx.put("staged_micro_add_notional_cap_usdt", stagedMicroAddMaxNotionalUsdt);
            tradeCtx.put("legacy_secondary_notional_cap_usdt",
                    signalSourcePolicy.legacySecondaryMaxNotionalUsdtForStrategy(record.getStrategyId()));
            tradeCtx.put("estimated_slippage_pct", estimatedSlippagePct);
            java.util.Map<String, Object> sizingCtx = new java.util.LinkedHashMap<>();
            sizingCtx.put("mode", sizingDecision.liveEnabled() ? "LIVE" : "SHADOW");
            sizingCtx.put("legacyAmountUsdt", sizingDecision.legacyAmountUsdt());
            sizingCtx.put("recommendedAmountUsdt", sizingDecision.recommendedAmountUsdt());
            sizingCtx.put("finalAmountUsdt", sizingDecision.finalAmountUsdt());
            sizingCtx.put("slDistancePct", sizingDecision.slDistancePct());
            sizingCtx.put("riskBudgetUsdt", sizingDecision.riskBudgetUsdt());
            sizingCtx.put("minNotionalUsdt", sizingDecision.minNotionalUsdt());
            sizingCtx.put("belowMinNotional", sizingDecision.belowMinNotional());
            sizingCtx.put("liveEntryAllowed", sizingDecision.liveEntryAllowed());
            sizingCtx.put("availableUsdt", sizingDecision.availableUsdt() != null ? sizingDecision.availableUsdt() : "N/A");
            sizingCtx.put("reason", sizingDecision.reason());
            tradeCtx.put("position_sizing", sizingCtx);
            auditWriter.logAutoTradeOk(record.getStrategyId(), symbol, record.getId(), tradeCtx);

            // TG 通知
            String tgMsg = String.format(
                    "🤖 <b>AutoTrade 已買入 %s</b>\n" +
                    "💰 均價: <b>$%s</b>\n" +
                    "📦 數量: <b>%s</b>\n" +
                    "🎯 止盈: $%s  🛡 止損: $%s\n" +
                    "%s\n" +
                    "OCO: %s",
                    symbol,
                    formatPrice(result.getAvgPrice()),
                    result.getQty().toPlainString(),
                    formatPrice(tp), formatPrice(sl),
                    tradingProperties.isPositionSizingShadowEnabled() || tradingProperties.isPositionSizingLiveEnabled()
                            ? sizingDecision.tgLine()
                            : "📐 Sizing: disabled",
                    record.getOcoOrderListId() != null ? "✅ 已掛單" : "❌ 失敗（請手動設定）");
            notificationPort.broadcast(tgMsg, true);

            // 交易後對帳：驗證 OKX 餘額和 DB 記錄一致
            verifyPostTradeBalance(symbol);

        } catch (Exception e) {
            if (buySucceeded) {
                // 買入已成交，但後續 DB save 或 OCO 失敗
                // reconcileHoldings() 3a 會偵測 SPOT 餘額差異並發警告；此處補一條明確訊息
                log.error("[AutoTrade] Post-buy processing failed (BUY SUCCEEDED): symbol={} error={}",
                        symbol, e.getMessage(), e);
                tradingMetrics.orderPlaced(symbol, "LONG_OPEN", "post_buy_fail");
                try {
                    notificationPort.broadcast(
                            String.format("⚠️ <b>AutoTrade 注意</b>\n%s 買入已成交，但後續記錄/OCO 失敗！\n" +
                                    "幣已在 OKX，請確認並手動設定止損。\n%s", symbol, e.getMessage()), true);
                } catch (Exception ignored) {}
                auditWriter.logAutoTradeFail(record.getStrategyId(), symbol,
                        "POST_BUY_FAIL: " + e.getMessage(),
                        java.util.Map.of("side", "LONG", "buySucceeded", true));
            } else {
                log.error("[AutoTrade] Buy failed: symbol={} error={}", symbol, e.getMessage());
                tradingMetrics.orderPlaced(symbol, "LONG_OPEN", "fail");
                try {
                    notificationPort.broadcast(
                            String.format("❌ <b>AutoTrade 買入失敗</b>\n%s\n%s", symbol, e.getMessage()), true);
                } catch (Exception ignored) {}
                auditWriter.logAutoTradeFail(record.getStrategyId(), symbol,
                        "BUY_FAIL: " + e.getMessage(),
                        java.util.Map.of("side", "LONG", "buySucceeded", false));
            }
        }
    }

    private double applyLegacySecondaryNotionalCap(BtLiveSignal record, String symbol, double tradeAmount, String stage) {
        double cap = signalSourcePolicy.legacySecondaryMaxNotionalUsdtForStrategy(record.getStrategyId());
        if (cap > 0 && tradeAmount > cap) {
            log.info("[AutoTrade] legacy secondary notional cap applied: strategyId={} symbol={} stage={} amount={} cap={}",
                    record.getStrategyId(), symbol, stage, tradeAmount, cap);
            return cap;
        }
        return tradeAmount;
    }

    private boolean shouldSkipRiskSizedAutoTrade(PositionSizingService.PositionSizingDecision sizingDecision,
                                                boolean forceRiskSizingForWickAwareSl) {
        return sizingDecision != null
                && (tradingProperties.isPositionSizingLiveEnabled() || forceRiskSizingForWickAwareSl)
                && !sizingDecision.liveEntryAllowed();
    }

    private String riskSizingSkipReason(PositionSizingService.PositionSizingDecision sizingDecision) {
        String reason = String.format(java.util.Locale.ROOT,
                "AutoTrade: risk-sized notional %.2f below min %.2f; skip live entry",
                sizingDecision.recommendedAmountUsdt(),
                sizingDecision.minNotionalUsdt());
        log.info("[AutoTrade] Skip: {}", reason);
        return reason;
    }

    private void markRiskSizedAutoTradeSkipped(BtLiveSignal record,
                                               String symbol,
                                               PositionSizingService.PositionSizingDecision sizingDecision) {
        String reason = riskSizingSkipReason(sizingDecision);
        markAutoTradeSkipped(record, reason);
        Map<String, Object> context = riskSizingSkipContext(sizingDecision);
        context.put("side", "LONG");
        context.put("orderSent", false);
        context.put("selectedAction", "SKIP_RISK_SIZED_BELOW_MIN_NOTIONAL");
        try {
            auditWriter.logEntrySkip(record.getStrategyId(), symbol, record.getIntervalCode(),
                    record.getBarOpenTime(), "PositionSizing", reason, context, record.getId());
        } catch (Exception e) {
            log.debug("[AutoTrade] risk-sizing ENTRY_SKIP audit failed liveSignalId={}: {}",
                    record.getId(), e.getMessage());
        }
        try {
            notificationPort.broadcast(buildRiskSizingSkipNotification(
                    symbol, record.getIntervalCode(), record.getStrategyId(), record.getId(), sizingDecision), true);
        } catch (Exception e) {
            log.warn("[AutoTrade] risk-sizing skip notification failed liveSignalId={}: {}",
                    record.getId(), e.getMessage());
        }
    }

    private static Map<String, Object> riskSizingSkipContext(PositionSizingService.PositionSizingDecision sizingDecision) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("mode", sizingDecision.liveEnabled() ? "LIVE" : "FORCED_LIVE_RISK_SIZING");
        context.put("legacyAmountUsdt", sizingDecision.legacyAmountUsdt());
        context.put("recommendedAmountUsdt", sizingDecision.recommendedAmountUsdt());
        context.put("finalAmountUsdt", sizingDecision.finalAmountUsdt());
        context.put("slDistancePct", sizingDecision.slDistancePct());
        context.put("tpDistancePct", sizingDecision.tpDistancePct());
        context.put("riskReward", sizingDecision.riskReward());
        context.put("riskBudgetUsdt", sizingDecision.riskBudgetUsdt());
        context.put("minNotionalUsdt", sizingDecision.minNotionalUsdt());
        context.put("belowMinNotional", sizingDecision.belowMinNotional());
        context.put("liveEntryAllowed", sizingDecision.liveEntryAllowed());
        context.put("availableUsdt", sizingDecision.availableUsdt() != null ? sizingDecision.availableUsdt() : "N/A");
        context.put("reason", sizingDecision.reason());
        context.put("explain", sizingDecision.explain());
        return context;
    }

    static String buildRiskSizingSkipNotification(String symbol,
                                                  String intervalCode,
                                                  Long strategyId,
                                                  Long liveSignalId,
                                                  PositionSizingService.PositionSizingDecision sizingDecision) {
        String normalizedInterval = intervalCode == null ? "N/A" : intervalCode.toUpperCase(Locale.ROOT);
        String strategyText = strategyId != null ? String.valueOf(strategyId) : "N/A";
        String signalText = liveSignalId != null ? String.valueOf(liveSignalId) : "N/A";
        return String.format(Locale.ROOT,
                "🟡 <b>AutoTrade 未買入 %s (%s)</b>\n\n" +
                "原因: risk-sized notional <b>$%.2f</b> &lt; min <b>$%.2f</b>\n" +
                "SL 距離: <b>%.2f%%</b> | 風險預算: <b>$%.2f</b>\n" +
                "Legacy 金額: <b>$%.2f</b> | 最終下單: <b>$%.2f</b>\n" +
                "策略: <b>#%s</b> | live_signal_id <b>%s</b>\n\n" +
                "處置: 訊號已記錄但未下單；不是 Ensemble shadow 攔截，也不是漏單。",
                symbol, normalizedInterval,
                sizingDecision.recommendedAmountUsdt(),
                sizingDecision.minNotionalUsdt(),
                sizingDecision.slDistancePct() * 100.0,
                sizingDecision.riskBudgetUsdt(),
                sizingDecision.legacyAmountUsdt(),
                sizingDecision.finalAmountUsdt(),
                strategyText,
                signalText);
    }

    static String buildTradePlanQualitySkipNotification(String symbol,
                                                        String intervalCode,
                                                        Long strategyId,
                                                        BigDecimal entry,
                                                        BigDecimal sl,
                                                        BigDecimal tp,
                                                        BottomCatchQualityDecision decision) {
        String normalizedInterval = intervalCode == null ? "N/A" : intervalCode.toUpperCase(Locale.ROOT);
        String strategyText = strategyId != null ? String.valueOf(strategyId) : "N/A";
        double rr = decision != null ? decision.riskReward() : 0.0;
        double minRr = decision != null ? decision.minRiskReward() : 0.0;
        double slPct = decision != null ? decision.stopLossPct() * 100.0 : 0.0;
        double maxSlPct = decision != null ? decision.maxStopLossPct() * 100.0 : 0.0;
        String reason = decision != null ? decision.reasonCode() : "trade_plan_quality_block";
        return String.format(Locale.ROOT,
                "🟡 <b>AutoTrade 未買入 %s (%s)</b>\n\n" +
                "原因: <b>TradePlanQualityGate</b> %s\n" +
                "RR: <b>%.2f</b> / min <b>%.2f</b> | SL: <b>%.2f%%</b> / max <b>%.2f%%</b>\n" +
                "入場: <b>$%s</b> | TP: <b>$%s</b> | SL: <b>$%s</b>\n" +
                "策略: <b>#%s</b>\n\n" +
                "處置: BUY 訊號已被交易計畫品質閘門攔截；不是 Ensemble shadow 攔截，也不是漏單。",
                symbol, normalizedInterval,
                reason,
                rr, minRr, slPct, maxSlPct,
                formatNotificationPrice(entry),
                formatNotificationPrice(tp),
                formatNotificationPrice(sl),
                strategyText);
    }

    private static String formatNotificationPrice(BigDecimal value) {
        if (value == null) {
            return "N/A";
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void markAutoTradeSkipped(BtLiveSignal record, String reason) {
        record.setAutoTraded(false);
        record.setFilterReason(reason);
        liveSignalRepository.save(record);
    }

    /**
     * 查詢 OKX 實際 availBal，回傳 min(dbQty, availBal)。
     * 解決手續費扣於持倉幣種時 DB qty > OKX 實際餘額導致 OCO 51008 的問題。
     * 若查詢失敗，fallback 回傳原始 dbQty。
     */
    private BigDecimal resolveOcoQty(String symbol, BigDecimal dbQty) {
        try {
            String base = symbol.replace("USDT", "");
            BigDecimal availBal = okxTradingService.getSpotHoldings().stream()
                    .filter(h -> base.equals(h.ccy))
                    .map(h -> h.availBal)
                    .findFirst()
                    .orElse(BigDecimal.ZERO);
            if (availBal.compareTo(BigDecimal.ZERO) > 0 && availBal.compareTo(dbQty) < 0) {
                log.info("[AutoTrade] OCO qty adjusted: dbQty={} → availBal={} (diff={})",
                        dbQty, availBal, dbQty.subtract(availBal));
                return availBal;
            }
        } catch (Exception e) {
            log.warn("[AutoTrade] resolveOcoQty failed, using dbQty={}: {}", dbQty, e.getMessage());
        }
        return dbQty;
    }

    /** OCO 掛單，失敗後立即重試一次（不 sleep 以避免阻塞排程執行緒）。回傳 algoId，失敗回傳 null 並發 TG 警告。 */
    private Long placeOcoWithRetry(String symbol, BigDecimal qty, BigDecimal tp, BigDecimal sl) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return tradingService.placeOco(symbol, qty, tp, sl);
            } catch (Exception e) {
                if (attempt == 1) {
                    log.warn("[AutoTrade] OCO attempt 1 failed, retrying in 300ms: {}", e.getMessage());
                    try { Thread.sleep(300); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                } else {
                    log.error("[AutoTrade] OCO failed after retry — position open WITHOUT SL/TP: symbol={} error={}",
                            symbol, e.getMessage());
                    try {
                        notificationPort.broadcast(
                                String.format("⚠️ <b>AutoTrade 警告</b>\n%s 買入成功但 OCO 掛單失敗（已重試）！\n請手動設定止損止盈。\n%s",
                                        symbol, e.getMessage()), true);
                    } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    /**
     * 自動賣出：先取消 OCO（若存在），再下市價賣單。
     *
     * <p>會直接更新 {@code pos} 的出場欄位並 save，讓呼叫端不需要再次儲存。
     * 若 Binance 回報餘額不足（OCO 已先觸發），以 {@code fallbackExitPrice} 補存出場記錄。</p>
     *
     * @param pos              待出場的訊號記錄
     * @param symbol           交易對
     * @param fallbackExitPrice 當 OCO 已先觸發、市價賣出無法執行時，用來記錄出場價的 fallback
     */
    private synchronized void autoSell(BtLiveSignal pos, String symbol, BigDecimal fallbackExitPrice) {
        LocalDateTime exitTime = LocalDateTime.now(ZoneOffset.UTC);

        // 嘗試取消 OCO
        boolean ocoAlreadyFilled = false;
        if (pos.getOcoOrderListId() != null) {
            try {
                tradingService.cancelOco(symbol, pos.getOcoOrderListId());
            } catch (Exception e) {
                // OKX 51600/51603 表示 OCO 已成交或已不存在
                if (e.getMessage() != null && (
                        e.getMessage().contains("51600") ||    // OKX: Order state not pending
                        e.getMessage().contains("51603"))) {   // OKX: Order does not exist
                    ocoAlreadyFilled = true;
                    log.info("[AutoTrade] OCO already filled/cancelled, skipping market sell: id={} symbol={}",
                            pos.getId(), symbol);
                } else {
                    log.warn("[AutoTrade] OCO cancel failed (proceeding with sell): id={} error={}",
                            pos.getId(), e.getMessage());
                }
            }
        }

        if (ocoAlreadyFilled) {
            // 嘗試從 OKX algo order 取得實際成交價（避免用 bar close 做 fallback）
            BigDecimal actualExitPrice = resolveOcoFillPrice(symbol, pos.getOcoOrderListId(), false);
            boolean usedFallback = actualExitPrice == null;
            BigDecimal resolvedExit = usedFallback ? fallbackExitPrice : actualExitPrice;
            String exitReasonCode = resolveExitReason(resolvedExit, pos, false);

            pos.setExitPrice(resolvedExit);
            pos.setExitTime(exitTime);
            pos.setExitReason(exitReasonCode);
            pos.setRealizedPnl(calcPnl(pos.getActualEntryPrice(), resolvedExit, pos.getTradedQty()));
            liveSignalRepository.save(pos);

            if (usedFallback) {
                try {
                    notificationPort.broadcast(
                            String.format("⚠️ <b>AutoTrade 注意</b>\n%s OCO 已先觸發出場，無法取得實際成交價，請至 OKX 確認。", symbol), true);
                } catch (Exception ignored) {}
            } else if (actualExitPrice != null && pos.getActualEntryPrice() != null) {
                double pnlPct = actualExitPrice.subtract(pos.getActualEntryPrice())
                        .divide(pos.getActualEntryPrice(), 6, RoundingMode.HALF_UP).doubleValue();
                postTradeReviewService.reviewAsync(pos, exitReasonCode, actualExitPrice, pnlPct);
            }
            return;
        }

        // 市價賣出：用 OKX availBal 取代 DB tradedQty，確保不超賣（對稱買入側的 resolveOcoQty）
        BigDecimal sellQty = resolveOcoQty(symbol, pos.getTradedQty());
        try {
            BigDecimal sellAvgPrice = tradingService.placeMarketSell(symbol, sellQty);
            tradingMetrics.orderPlaced(symbol, "LONG_CLOSE", "ok");

            // Bug 1 fix：寫入實際成交價，覆蓋先前的 fallback 值
            pos.setExitPrice(sellAvgPrice);
            pos.setExitTime(exitTime);
            pos.setExitReason("SELL_SIGNAL");
            pos.setRealizedPnl(calcPnl(pos.getActualEntryPrice(), sellAvgPrice, sellQty));
            liveSignalRepository.save(pos);

            log.info("[AutoTrade] Sell OK: symbol={} qty={} avgPrice={}",
                    symbol, sellQty, sellAvgPrice);

            if (pos.getActualEntryPrice() != null) {
                double pnlPct = sellAvgPrice.subtract(pos.getActualEntryPrice())
                        .divide(pos.getActualEntryPrice(), 6, RoundingMode.HALF_UP).doubleValue();
                postTradeReviewService.reviewAsync(pos, "SELL_SIGNAL", sellAvgPrice, pnlPct);
            }

        } catch (Exception e) {
            // OKX 51008 insufficient balance → OCO 可能已觸發但沒被 51600/51603 攔截
            boolean likelyOcoFilled = e.getMessage() != null && e.getMessage().contains("51008");
            tradingMetrics.orderPlaced(symbol, "LONG_CLOSE",
                    likelyOcoFilled ? "oco_already_filled" : "fail");
            log.error("[AutoTrade] Sell failed (likelyOcoFilled={}): id={} symbol={} qty={} error={}",
                    likelyOcoFilled, pos.getId(), symbol, sellQty, e.getMessage());

            BigDecimal actualExitPrice = likelyOcoFilled
                    ? resolveOcoFillPrice(symbol, pos.getOcoOrderListId(), false) : null;
            boolean usedFallback = actualExitPrice == null;
            BigDecimal resolvedExit = usedFallback ? fallbackExitPrice : actualExitPrice;
            String exitReasonCode = likelyOcoFilled
                    ? resolveExitReason(resolvedExit, pos, false) : "SELL_FAILED";

            pos.setExitPrice(resolvedExit);
            pos.setExitTime(exitTime);
            pos.setExitReason(exitReasonCode);
            pos.setRealizedPnl(calcPnl(pos.getActualEntryPrice(), resolvedExit, pos.getTradedQty()));
            liveSignalRepository.save(pos);

            if (likelyOcoFilled && !usedFallback && actualExitPrice != null
                    && pos.getActualEntryPrice() != null) {
                double pnlPct = actualExitPrice.subtract(pos.getActualEntryPrice())
                        .divide(pos.getActualEntryPrice(), 6, RoundingMode.HALF_UP).doubleValue();
                postTradeReviewService.reviewAsync(pos, exitReasonCode, actualExitPrice, pnlPct);
            }

            try {
                String warn = likelyOcoFilled
                        ? String.format("⚠️ <b>AutoTrade 注意</b>\n%s 賣出失敗（餘額不足），OCO 可能已先觸發，請至 OKX 確認。", symbol)
                        : String.format("❌ <b>AutoTrade 賣出失敗！請手動處理！</b>\n%s 數量: %s\n%s",
                                symbol, pos.getTradedQty(), e.getMessage());
                notificationPort.broadcast(warn, true);
            } catch (Exception ignored) {}
        }
    }

    /**
     * 開新 SHORT 倉位：建立 BtLiveSignal(side=SHORT) → 發 TG → 下 SWAP 市價空單 → 掛 SWAP OCO。
     */
    private synchronized void autoOpenShort(BtStrategy strategy, String symbol, String intervalCode,
                                             MdKline lastBar, BigDecimal entry,
                                             BigDecimal tp, BigDecimal sl,
                                             LiveSignalContext.Snapshot snap, double nnOutput,
                                             com.agora.service.meta.TradeDecisionEngine.Decision ensembleDecision) {
        // 上限檢查
        long openCount = liveSignalRepository.countByAutoTradedIsTrueAndExitTimeIsNull();
        if (openCount >= tradingProperties.getMaxOpenPositions()) {
            log.info("[AutoShort] Skip: maxOpenPositions={} reached", tradingProperties.getMaxOpenPositions());
            return;
        }

        // 同 symbol 重複空頭檢查
        if (!tradingProperties.isAllowConcurrentOnSameSymbol()
                && liveSignalRepository.existsBySymbolAndAutoTradedIsTrueAndExitTimeIsNull(symbol)) {
            log.info("[AutoShort] Skip: already has open position on {}", symbol);
            return;
        }

        double baseAmount = tradingProperties.getTradeAmountUsdt();
        double tradeAmount = nnOutput >= 0.90 ? baseAmount * 2.0
                           : nnOutput >= 0.85 ? baseAmount * 1.5 : baseAmount;

        // Phase 1: 存 DB（必須在 OKX 下單前完成，確保孤兒偵測機制有記錄可查）
        BtLiveSignal record = new BtLiveSignal();
        record.setStrategyId(strategy.getId());
        record.setSymbol(symbol);
        record.setIntervalCode(intervalCode);
        record.setBarOpenTime(lastBar.getOpenTime());
        record.setEntryPrice(entry);
        record.setSuggestedSl(sl);
        record.setSuggestedTp(tp);
        record.setScore(snap != null
                ? BigDecimal.valueOf(snap.score).setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        record.setNnOutput(snap != null
                ? BigDecimal.valueOf(snap.nnOutput).setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        record.setSide("SHORT");
        record.setNotifiedAt(null);
        try {
            liveSignalRepository.save(record);
        } catch (Exception e) {
            log.error("[AutoShort] Phase1 DB save failed, aborting to prevent orphan SWAP position: symbol={} error={}",
                    symbol, e.getMessage());
            try {
                notificationPort.broadcast(
                        String.format("❌ <b>AutoShort 中止</b>\n%s DB 寫入失敗，已取消下單防止孤兒倉位。\n%s",
                                symbol, e.getMessage()), true);
            } catch (Exception ignored) {}
            return;  // DB 失敗時中止，不進行 OKX 下單
        }

        // Phase 2: TG 通知
        try {
            String msg = String.format(
                    "%s <b>%s</b> [%s]\n" +
                    "🏷 進場: <b>$%s</b>\n" +
                    "🎯 止盈: $%s  🛡 止損: $%s\n" +
                    "NN=%.3f",
                    resolveShortSignalTelegramHeader(), symbol, intervalCode,
                    formatPrice(entry), formatPrice(tp), formatPrice(sl),
                    snap != null ? snap.nnOutput : 0.0)
                    + formatEnsembleForTg(ensembleDecision);
            notificationPort.broadcast(msg, true);
            record.setNotifiedAt(LocalDateTime.now(ZoneOffset.UTC));
            liveSignalRepository.save(record);
            createSignalVerification(record, ensembleDecision);
        } catch (Exception e) {
            log.error("[AutoShort] TG send failed: {}", e.getMessage());
        }

        // Phase 3: 下單
        try {
            Map<String, Object> config = parseConfig(strategy.getConfigJson());
            var eventRisk = eventRiskActionOrchestrator.assessNewEntry(
                    strategy, config, symbol, intervalCode, "SHORT", record.getId());
            if (!eventRisk.allowed()) {
                log.info("[EventRiskControl] SHORT blocked: strategyId={} symbol={} reason={}",
                        strategy.getId(), symbol, eventRisk.reason());
                tradingMetrics.signalFiltered("EventRiskControl", eventRisk.snapshot().level().name());
                record.setAutoTraded(false);
                record.setFilterReason(eventRisk.reason());
                liveSignalRepository.save(record);
                auditWriter.logFilterBlock(strategy.getId(), symbol, intervalCode,
                        "EventRiskControl", eventRisk.reason(),
                        eventRisk.auditContext(), record.getId());
                return;
            }

            log.info("[AutoShort] Opening SWAP short: symbol={} usdt={}", symbol, tradeAmount);
            TradeResult result = okxTradingService.placeSwapShortEntry(symbol, tradeAmount);
            tradingMetrics.orderPlaced(symbol, "SHORT_OPEN", "ok");

            record.setAutoTraded(true);
            record.setExchangeOrderId(result.getOrderId());
            record.setActualEntryPrice(result.getAvgPrice());
            record.setTradedQty(result.getQty());   // 合約張數
            liveSignalRepository.save(record);

            // 掛 SWAP OCO；SHORT 的 ocoQty 與 tradedQty（合約張數）相同，無 Grid 競爭
            record.setOcoQty(result.getQty());
            try {
                Long algoId = okxTradingService.placeSwapOco(symbol, result.getQty(), tp, sl);
                record.setOcoOrderListId(algoId);
                ocoAdjustmentAuditWriter.log(record, "INITIAL_OCO", null, algoId,
                        null, tp, null, sl, null, result.getQty(),
                        "LiveSignalEvaluator.openShort", "initial swap OCO");
                log.info("[AutoShort] SWAP OCO OK: symbol={} algoId={}", symbol, algoId);
            } catch (Exception e) {
                log.error("[AutoShort] SWAP OCO failed: symbol={} error={}", symbol, e.getMessage());
                notificationPort.broadcast(
                        String.format("⚠️ <b>AutoShort 警告</b>\n%s 空單成功但 OCO 掛單失敗！\n請手動設定止損止盈。\n%s",
                                symbol, e.getMessage()), true);
            }
            liveSignalRepository.save(record);

            notificationPort.broadcast(
                    String.format("🤖 <b>AutoShort 已做空 %s</b>\n💰 均價: <b>$%s</b>\n📦 合約: %s 張\n🎯 tp=$%s  🛡 sl=$%s\nOCO: %s",
                            symbol, formatPrice(result.getAvgPrice()), result.getQty().toPlainString(),
                            formatPrice(tp), formatPrice(sl),
                            record.getOcoOrderListId() != null ? "✅" : "❌"), true);

        } catch (Exception e) {
            log.error("[AutoShort] SWAP order failed: symbol={} error={}", symbol, e.getMessage());
            tradingMetrics.orderPlaced(symbol, "SHORT_OPEN", "fail");
            try {
                notificationPort.broadcast(
                        String.format("❌ <b>AutoShort 做空失敗</b>\n%s\n%s", symbol, e.getMessage()), true);
            } catch (Exception ignored) {}
        }
    }

    /**
     * 平倉 SHORT：取消 SWAP OCO → 市價買回 → 更新 DB → 發 TG 通知。
     */
    private synchronized void autoCloseShort(BtLiveSignal pos, String symbol, BigDecimal fallbackExitPrice) {
        LocalDateTime exitTime = LocalDateTime.now(ZoneOffset.UTC);

        // 嘗試取消 SWAP OCO
        boolean ocoAlreadyFilled = false;
        if (pos.getOcoOrderListId() != null) {
            try {
                okxTradingService.cancelSwapOco(symbol, pos.getOcoOrderListId());
            } catch (Exception e) {
                if (e.getMessage() != null && (e.getMessage().contains("51600") || e.getMessage().contains("51603"))) {
                    ocoAlreadyFilled = true;
                    log.info("[AutoShort] SWAP OCO already filled: id={} symbol={}", pos.getId(), symbol);
                } else {
                    log.warn("[AutoShort] SWAP OCO cancel failed (proceeding with buy-back): id={} error={}",
                            pos.getId(), e.getMessage());
                }
            }
        }

        if (ocoAlreadyFilled) {
            // 嘗試從 OKX SWAP algo order 取得實際成交價
            BigDecimal actualExitPrice = resolveOcoFillPrice(symbol, pos.getOcoOrderListId(), true);
            boolean usedFallback = actualExitPrice == null;
            BigDecimal resolvedExit = usedFallback ? fallbackExitPrice : actualExitPrice;
            String exitReasonCode = resolveExitReason(resolvedExit, pos, true);

            pos.setExitPrice(resolvedExit);
            pos.setExitTime(exitTime);
            pos.setExitReason(exitReasonCode);
            pos.setRealizedPnl(calcShortPnl(pos.getActualEntryPrice(), resolvedExit, pos.getTradedQty(), symbol));
            liveSignalRepository.save(pos);

            if (usedFallback) {
                try {
                    notificationPort.broadcast(
                            String.format("⚠️ <b>AutoShort 注意</b>\n%s SWAP OCO 已先觸發，無法取得實際成交價，請至 OKX 確認。", symbol), true);
                } catch (Exception ignored) {}
            } else if (actualExitPrice != null && pos.getActualEntryPrice() != null) {
                double pnlPct = pos.getActualEntryPrice().subtract(actualExitPrice)
                        .divide(pos.getActualEntryPrice(), 6, RoundingMode.HALF_UP).doubleValue();
                postTradeReviewService.reviewAsync(pos, exitReasonCode, actualExitPrice, pnlPct);
                // Backfill actual_outcome in ml_inference_log (SHORT OCO-already-filled path)
                mlInferenceLogger.backfillOutcome(pos.getId(), pnlPct > 0 ? 1 : 0, pnlPct);
            }
            return;
        }

        // 市價平空（買回）
        try {
            TradeResult result = okxTradingService.placeSwapShortExit(symbol, pos.getTradedQty());
            tradingMetrics.orderPlaced(symbol, "SHORT_CLOSE", "ok");
            pos.setExitPrice(result.getAvgPrice());
            pos.setExitTime(exitTime);
            pos.setExitReason("SELL_SIGNAL");
            pos.setRealizedPnl(calcShortPnl(pos.getActualEntryPrice(), result.getAvgPrice(), pos.getTradedQty(), symbol));
            liveSignalRepository.save(pos);

            log.info("[AutoShort] Close OK: symbol={} contracts={} avgPrice={}",
                    symbol, pos.getTradedQty(), result.getAvgPrice());
            notificationPort.broadcast(
                    String.format("🤖 <b>AutoShort 平倉 %s</b>\n💰 成交價: <b>$%s</b>\n📦 合約: %s 張",
                            symbol, formatPrice(result.getAvgPrice()), pos.getTradedQty().toPlainString()), true);

            if (pos.getActualEntryPrice() != null) {
                double pnlPct = pos.getActualEntryPrice().subtract(result.getAvgPrice())
                        .divide(pos.getActualEntryPrice(), 6, RoundingMode.HALF_UP).doubleValue();
                postTradeReviewService.reviewAsync(pos, "SELL_SIGNAL", result.getAvgPrice(), pnlPct);
                // Backfill actual_outcome in ml_inference_log (SHORT signal-close path)
                mlInferenceLogger.backfillOutcome(pos.getId(), pnlPct > 0 ? 1 : 0, pnlPct);
            }

        } catch (Exception e) {
            log.error("[AutoShort] Close failed: id={} symbol={} error={}", pos.getId(), symbol, e.getMessage());
            tradingMetrics.orderPlaced(symbol, "SHORT_CLOSE", "fail");
            pos.setExitPrice(fallbackExitPrice);
            pos.setExitTime(exitTime);
            pos.setExitReason("SELL_FAILED");
            pos.setRealizedPnl(calcShortPnl(pos.getActualEntryPrice(), fallbackExitPrice, pos.getTradedQty(), symbol));
            liveSignalRepository.save(pos);
            try {
                notificationPort.broadcast(
                        String.format("❌ <b>AutoShort 平倉失敗！請手動處理！</b>\n%s 合約: %s\n%s",
                                symbol, pos.getTradedQty(), e.getMessage()), true);
            } catch (Exception ignored) {}
        }
    }

    /** 嘗試從 OKX algo order 取得實際成交均價（state=filled 且 avgPx 有效）；失敗或未成交回傳 null。 */
    private BigDecimal resolveOcoFillPrice(String symbol, Long algoId, boolean isShort) {
        if (algoId == null) return null;
        try {
            JsonNode algo = isShort
                    ? okxTradingService.getSwapAlgoOrder(symbol, algoId)
                    : okxTradingService.getAlgoOrder(symbol, algoId);
            String avgPxStr = algo.path("avgPx").asText("");
            if (!avgPxStr.isEmpty() && !"0".equals(avgPxStr)) {
                return new BigDecimal(avgPxStr);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 根據出場價與 TP/SL 中點判斷出場原因（TP 或 SL），無法判斷時回傳 "OCO_FILLED"。 */
    private String resolveExitReason(BigDecimal exitPrice, BtLiveSignal pos, boolean isShort) {
        if (pos.getSuggestedTp() != null && pos.getSuggestedSl() != null && exitPrice != null) {
            BigDecimal mid = pos.getSuggestedTp().add(pos.getSuggestedSl())
                    .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
            return isShort ? (exitPrice.compareTo(mid) <= 0 ? "TP" : "SL")
                           : (exitPrice.compareTo(mid) >= 0 ? "TP" : "SL");
        }
        return "OCO_FILLED";
    }

    /** SHORT PnL：(entryPrice - exitPrice) × contracts（空頭獲利來自價格下跌） */
    private BigDecimal calcShortPnl(BigDecimal entryPrice, BigDecimal exitPrice, BigDecimal contracts, String symbol) {
        if (entryPrice == null || exitPrice == null || contracts == null) return null;
        BigDecimal contractSize = BigDecimal.valueOf(okxTradingService.getContractSizeInBase(symbol));
        return entryPrice.subtract(exitPrice).multiply(contracts).multiply(contractSize).setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * 計算已實現損益（USDT）。任一參數為 null 時回傳 null（不強制計算）。
     * 公式：(exitPrice - entryPrice) * qty（未扣手續費，僅供參考）。
     */
    private BigDecimal calcPnl(BigDecimal entryPrice, BigDecimal exitPrice, BigDecimal qty) {
        if (entryPrice == null || exitPrice == null || qty == null) return null;
        return exitPrice.subtract(entryPrice).multiply(qty).setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * 交易後對帳：比對 OKX 實際 cashBal 與 DB 所有未出場倉位的 tradedQty 總和。
     * 僅發警告，不影響主流程。
     */
    private void verifyPostTradeBalance(String symbol) {
        try {
            String base = symbol.replace("USDT", ""); // "ETHUSDT" → "ETH"

            BigDecimal dbTotalQty = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()
                    .stream()
                    .filter(p -> symbol.equals(p.getSymbol()) && p.getTradedQty() != null)
                    .map(BtLiveSignal::getTradedQty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal okxCashBal = okxTradingService.getSpotHoldings().stream()
                    .filter(h -> h.ccy.equals(base))
                    .map(h -> h.cashBal)
                    .findFirst()
                    .orElse(BigDecimal.ZERO);

            BigDecimal tolerance = new BigDecimal("0.0001");
            if (okxCashBal.compareTo(dbTotalQty.subtract(tolerance)) < 0) {
                log.warn("[AutoTrade] 交易後餘額不一致: OKX {} cashBal={} < DB openQty={}",
                        base, okxCashBal, dbTotalQty);
                try {
                    notificationPort.broadcast(String.format(
                            "⚠️ <b>交易後餘額不一致</b>\n%s OKX 實際 cashBal: %s\nDB 開倉記錄合計: %s\n可能有費用計算誤差，請確認。",
                            base, okxCashBal.toPlainString(), dbTotalQty.toPlainString()), true);
                } catch (Exception ignored) {}
            } else {
                log.info("[AutoTrade] 交易後餘額驗證通過: OKX {} cashBal={} >= DB openQty={}",
                        base, okxCashBal, dbTotalQty);
            }
        } catch (Exception e) {
            log.warn("[AutoTrade] 交易後餘額驗證失敗（不影響主流程）: {}", e.getMessage());
        }
    }

    private String buildExitMessage(String symbol, String intervalCode, MdKline bar,
                                     BigDecimal entry, BigDecimal exit, double pnlPct,
                                     LiveSignalContext.Snapshot snap, String strategyType) {
        String barTime = bar.getOpenTime().plusHours(8).format(FMT_DISPLAY);
        String pnlEmoji = pnlPct >= 0 ? "🟢" : "🔴";
        String rsiLine = snap != null
                ? String.format("📊 RSI: <b>%.1f</b>  NN: <b>%.3f</b>\n", snap.rsi, snap.nnOutput)
                : "";
        return String.format(
            "%s <b>出場訊號 %s (%s)</b>\n\n" +
            "📅 K線: %s (UTC+8)\n" +
            "💰 出場價: <b>$%s</b>\n" +
            "📥 入場價: $%s\n" +
            "%s" +
            "損益: <b>%+.2f%%</b>\n\n" +
            "⚡ 策略: %s",
            pnlEmoji, symbol, intervalCode.toUpperCase(),
            barTime,
            formatPrice(exit),
            formatPrice(entry),
            rsiLine,
            pnlPct * 100,
            strategyType
        );
    }

    private String buildTelegramMessage(String symbol, String intervalCode,
                                         MdKline bar, BigDecimal entry,
                                         BigDecimal sl, BigDecimal tp,
                                         double stopLossPct, double takeProfitPct,
                                         LiveSignalContext.Snapshot snap,
                                         double yearDrop,
                                         String strategyType,
                                         boolean notifyOnly,
                                         com.agora.service.meta.TradeDecisionEngine.Decision ensembleDecision) {
        String barTime = bar.getOpenTime().plusHours(8).format(FMT_DISPLAY);

        String scoreLine = snap != null
                ? String.format("📊 Score: <b>%.3f</b>  NN: <b>%.3f</b>  RSI: <b>%.1f</b>  YearDrop: <b>%.1f%%</b>\n",
                        snap.score, snap.nnOutput, snap.rsi, yearDrop * 100)
                : "";

        // Shadow mode footer：明確告知此訊號未自動下單，避免混淆
        String shadowLine = notifyOnly
                ? "\n⚠️ <i>觀察模式 — 未自動下單</i>"
                : "";

        String ensembleSection = formatEnsembleForTg(ensembleDecision);

        return String.format(
            "%s <b>%s (%s)</b>\n\n" +
            "📅 K線: %s (UTC+8)\n" +
            "💰 收盤價: <b>$%s</b>\n\n" +
            "%s" +
            "🛡 建議止損: $%s (-%.1f%%)\n" +
            "🎯 建議止盈: $%s (+%.1f%%)\n\n" +
            "⚡ 策略: %s%s%s",
            resolveLongSignalTelegramHeader(notifyOnly), symbol, intervalCode.toUpperCase(),
            barTime,
            formatPrice(entry),
            scoreLine,
            formatPrice(sl), stopLossPct * 100,
            formatPrice(tp), takeProfitPct * 100,
            strategyType, ensembleSection, shadowLine
        );
    }

    static String resolveLongSignalTelegramHeader(boolean notifyOnly) {
        return notifyOnly ? "👁 <b>觀察候選</b>" : "🟡 <b>買入候選</b>";
    }

    static String resolveShortSignalTelegramHeader() {
        return "📉 <b>做空候選</b>";
    }

    /**
     * Formats ensemble decision as a compact TG section: header + top 3 components
     * (absolute-value sorted). Returns empty string when no decision available so TG
     * messages without ensemble data stay visually identical to pre-Phase-1 output.
     *
     * <p>Shown to humans reviewing the signal — communicates "all signals across ML +
     * Gemini + sentiment + polymarket + flip agree/disagree this is a good entry".
     */
    private String formatEnsembleForTg(com.agora.service.meta.TradeDecisionEngine.Decision dec) {
        if (dec == null) return "";
        StringBuilder sb = new StringBuilder("\n\n");
        // #437 sub-task 1 — shadow mode 顯示 🚫 但實際未擋,造成 UX 矛盾。
        // VETO 永遠是真擋。BLOCK 只在 ensembleGateEnabled=true (Phase 2) 才真擋。
        // Phase 1 shadow:用 🟡 + "WOULD BLOCK" + 下單前風控 disclaimer。
        boolean blockEnforced = ensembleGateEnabled;
        String headerEmoji = switch (dec.outcome()) {
            case "PASS"  -> "🎯";
            case "BLOCK" -> blockEnforced ? "🚫" : "🟡";
            case "VETO"  -> "🛑";
            default      -> "ℹ️";
        };
        if ("VETO".equals(dec.outcome())) {
            sb.append(headerEmoji).append(" <b>Ensemble: VETO</b>");
            if (dec.vetoReason() != null) sb.append("\n   ").append(dec.vetoReason());
            return sb.toString();
        }
        if ("BLOCK".equals(dec.outcome()) && !blockEnforced) {
            sb.append(headerEmoji).append(" <b>Ensemble shadow: WOULD BLOCK</b> ")
              .append(String.format("%.1f/%.0f", dec.score(), dec.threshold()))
              .append("\n   <i>").append(ENSEMBLE_SHADOW_PRE_EXECUTION_DISCLAIMER).append("</i>");
        } else {
            sb.append(headerEmoji).append(" <b>Ensemble: ").append(dec.outcome())
              .append(String.format(" %.1f/%.0f</b>", dec.score(), dec.threshold()));
        }

        // Top 3 components by absolute magnitude
        dec.components().stream()
           .sorted((a, b) -> Double.compare(Math.abs(b.points()), Math.abs(a.points())))
           .limit(3)
           .forEach(c -> sb.append(String.format("\n   %+.1f  %s",
                   c.points(), c.reason())));
        return sb.toString();
    }

    private double calcYearDrop(List<MdKline> klines, int lastIndex, int yearLookback, double close) {
        int start = Math.max(0, lastIndex - yearLookback);
        double yearHigh = 0;
        for (int i = start; i < lastIndex; i++) {
            double h = klines.get(i).getHighPrice().doubleValue();
            if (h > yearHigh) yearHigh = h;
        }
        return yearHigh > 0 ? (yearHigh - close) / yearHigh : 0.0;
    }

    private String formatPrice(BigDecimal price) {
        if (price.compareTo(BigDecimal.valueOf(1000)) >= 0) {
            return String.format("%,.2f", price.doubleValue());
        }
        return price.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 載入最近 totalBars 根 K 線（升序）並快取 60 秒。
     * 必須透過 Spring proxy（self）呼叫，@Cacheable 才會生效。
     * KlineClosedEventListener 收到新 bar 時會 @CacheEvict 使快取失效。
     *
     * <p>V041：cache key 納入 source，避免同 (symbol, interval) 下 okx / binance
     * 兩個來源互相污染（雖然目前 live 只會用其中一個，但 strategy 設定可以切換）。
     */
    @Cacheable(value = "liveSignalKlines", key = "#symbol + ':' + #intervalCode + ':' + #source")
    public List<MdKline> loadKlinesCached(String symbol, String intervalCode, String source, int totalBars) {
        List<MdKline> klines = klineRepository
                .findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(
                        symbol, intervalCode, source, PageRequest.of(0, totalBars));
        Collections.reverse(klines);
        return klines;
    }

    /**
     * 解析策略應使用的 K 線資料源。優先順序：
     * <ol>
     *   <li>{@code strategy.klineSource}（V041 起 NOT NULL DEFAULT 'okx'，per-strategy 設定）</li>
     *   <li>{@code market.signal.source} 全域 fallback（舊資料 / 測試場景）</li>
     * </ol>
     * 回傳值永遠為 lowercase 非 null。
     */
    String resolveStrategyKlineSource(BtStrategy strategy) {
        String src = strategy != null ? strategy.getKlineSource() : null;
        if (src == null || src.isBlank()) {
            src = fallbackSignalSource;
        }
        return src.toLowerCase();
    }

    private Map<String, Object> parseConfig(String configJson) {
        try {
            return objectMapper.readValue(configJson, MAP_TYPE);
        } catch (Exception e) {
            log.error("[LiveSignal] Failed to parse configJson: {}", e.getMessage());
            return null;
        }
    }

    private int getInt(Map<String, Object> config, String key, int def) {
        Object v = config.get(key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
    }

    private double getDouble(Map<String, Object> config, String key, double def) {
        Object v = config.get(key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return def; }
    }

    private boolean getBoolean(Map<String, Object> config, String key, boolean def) {
        Object v = config.get(key);
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private String getString(Map<String, Object> config, String key, String def) {
        Object v = config.get(key);
        if (v == null) return def;
        return String.valueOf(v);
    }

    static String resolveEntryDedupOpenExposureScope(Map<String, Object> config) {
        Object raw = config != null ? config.get(ENTRY_DEDUP_OPEN_EXPOSURE_SCOPE_KEY) : null;
        if (raw == null) {
            return ENTRY_DEDUP_SCOPE_ALL_OPEN_ROWS;
        }
        String normalized = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if (ENTRY_DEDUP_SCOPE_AUTO_TRADED_OPEN_ROWS.equals(normalized)) {
            return ENTRY_DEDUP_SCOPE_AUTO_TRADED_OPEN_ROWS;
        }
        if (ENTRY_DEDUP_SCOPE_ALL_OPEN_ROWS.equals(normalized)) {
            return ENTRY_DEDUP_SCOPE_ALL_OPEN_ROWS;
        }
        return ENTRY_DEDUP_SCOPE_ALL_OPEN_ROWS;
    }

    static boolean usesAutoTradedOpenRowsForEntryDedup(String scope) {
        return ENTRY_DEDUP_SCOPE_AUTO_TRADED_OPEN_ROWS.equals(resolveEntryDedupOpenExposureScope(
                Map.of(ENTRY_DEDUP_OPEN_EXPOSURE_SCOPE_KEY, scope == null ? "" : scope)));
    }

    static BottomCatchQualityDecision evaluateBottomCatchQualityGate(String strategyType,
                                                                     Map<String, Object> config,
                                                                     double stopLossPct,
                                                                     double takeProfitPct,
                                                                     boolean wickAwareSlApplied,
                                                                     String wickAwareSlMode) {
        boolean enabled = configBoolean(config, TRADE_PLAN_QUALITY_GATE_ENABLED_KEY,
                configBoolean(config, "bottomCatchQualityGateEnabled", true));
        double riskReward = stopLossPct > 0 ? takeProfitPct / stopLossPct : 0.0;
        double minRiskReward = Math.max(0.0, configDouble(config, TRADE_PLAN_MIN_RISK_REWARD_KEY,
                configDouble(config, "bottomCatchMinRiskReward", 1.0)));
        double maxStopLossPct = Math.max(0.0, configDouble(config, TRADE_PLAN_MAX_STOP_LOSS_PCT_KEY,
                configDouble(config, "bottomCatchMaxStopLossPct", 0.08)));
        String mode = wickAwareSlMode == null || wickAwareSlMode.isBlank()
                ? "UNKNOWN"
                : wickAwareSlMode.trim().toUpperCase(Locale.ROOT);
        if (!enabled) {
            return new BottomCatchQualityDecision(true, "DISABLED", "trade plan quality gate disabled",
                    riskReward, minRiskReward, stopLossPct, maxStopLossPct, wickAwareSlApplied, mode);
        }

        java.util.List<String> reasons = new java.util.ArrayList<>();
        if (stopLossPct <= 0 || takeProfitPct <= 0) {
            reasons.add("invalid_tp_sl_plan");
        }
        if (riskReward < minRiskReward) {
            reasons.add("risk_reward_below_min");
        }
        if (maxStopLossPct > 0 && stopLossPct > maxStopLossPct) {
            reasons.add("stop_loss_above_max");
        }
        if (reasons.isEmpty()) {
            return new BottomCatchQualityDecision(true, "PASS",
                    String.format(Locale.ROOT,
                            "pass riskReward=%.2f stopLoss=%.2f%% wickAware=%s/%s",
                            riskReward, stopLossPct * 100.0, wickAwareSlApplied, mode),
                    riskReward, minRiskReward, stopLossPct, maxStopLossPct, wickAwareSlApplied, mode);
        }
        String reasonCode = String.join("+", reasons);
        String reason = String.format(Locale.ROOT,
                "%s: riskReward=%.2f < min %.2f or stopLoss=%.2f%% > max %.2f%%; wickAware=%s/%s",
                reasonCode, riskReward, minRiskReward, stopLossPct * 100.0, maxStopLossPct * 100.0,
                wickAwareSlApplied, mode);
        return new BottomCatchQualityDecision(false, reasonCode, reason,
                riskReward, minRiskReward, stopLossPct, maxStopLossPct, wickAwareSlApplied, mode);
    }

    private static double configDouble(Map<String, Object> config, String key, double def) {
        Object v = config != null ? config.get(key) : null;
        if (v == null) return def;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean configBoolean(Map<String, Object> config, String key, boolean def) {
        Object v = config != null ? config.get(key) : null;
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private boolean hasOpenLongExposureForEntryDedup(Long strategyId,
                                                     String symbol,
                                                     String side,
                                                     String intervalCode,
                                                     String scope) {
        if (usesAutoTradedOpenRowsForEntryDedup(scope)) {
            return liveSignalRepository.existsOpenAutoTradedPosition(strategyId, symbol, side, intervalCode);
        }
        return liveSignalRepository.existsByStrategyIdAndSymbolAndSideAndIntervalCodeAndExitTimeIsNull(
                strategyId, symbol, side, intervalCode);
    }

    private boolean isTqsStrategyAllowlisted(BtStrategy strategy) {
        return strategy != null && Long.valueOf(574L).equals(strategy.getId());
    }

    private static boolean isNoLiveExecutionOnlyStrategy(BtStrategy strategy, Map<String, Object> config) {
        return noLiveExecutionOnlyStrategy(strategy, config);
    }

    static boolean noLiveExecutionOnlyStrategy(BtStrategy strategy, Map<String, Object> config) {
        String name = strategy != null && strategy.getName() != null
                ? strategy.getName().toUpperCase(Locale.ROOT)
                : "";
        if (name.contains("NOSL") || name.contains("NO-SL") || name.contains("POC")) {
            return true;
        }
        double fixedStopLossPct = configDouble(config, "fixedStopLossPct", -1.0);
        double fixedTakeProfitPct = configDouble(config, "fixedTakeProfitPct", -1.0);
        return fixedStopLossPct >= 0.50 && fixedTakeProfitPct > 0 && fixedTakeProfitPct <= 0.01;
    }

    static FearGreedGateDecision evaluateFearGreedGate(String side,
                                                       Integer currentFg,
                                                       double requireFearGreedBelow,
                                                       double requireFearGreedAbove,
                                                       String mode) {
        if (currentFg == null) {
            return FearGreedGateDecision.inactive();
        }
        String normalizedMode = mode == null || mode.isBlank()
                ? "WARN_ONLY"
                : mode.trim().toUpperCase();
        if (requireFearGreedBelow > 0 && currentFg >= requireFearGreedBelow) {
            return fearGreedDecision(side, currentFg, requireFearGreedBelow,
                    "F&G at/above panic-bottom threshold",
                    "AT_OR_ABOVE_REQUIRE_BELOW",
                    normalizedMode);
        }
        if (requireFearGreedAbove > 0 && currentFg <= requireFearGreedAbove) {
            return fearGreedDecision(side, currentFg, requireFearGreedAbove,
                    "F&G at/below fade-rally threshold",
                    "AT_OR_BELOW_REQUIRE_ABOVE",
                    normalizedMode);
        }
        return FearGreedGateDecision.inactive();
    }

    private static FearGreedGateDecision fearGreedDecision(String side,
                                                          int currentFg,
                                                          double threshold,
                                                          String reason,
                                                          String condition,
                                                          String mode) {
        int penalty = -10;
        int qualityScore = 40;
        String tqsBand = "PROBE_DRY_RUN";
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("side", side);
        context.put("fearGreedValue", currentFg);
        context.put("threshold", threshold);
        context.put("fearGreedFilterMode", mode);
        context.put("fearGreedRequestedMode", mode);
        context.put("fearGreedFilterState", "WARN_ONLY");
        context.put("fearGreedWarning", true);
        context.put("fearGreedCondition", condition);
        context.put("tqsPenalty", penalty);
        context.put("qualityScore", qualityScore);
        context.put("tqsBand", tqsBand);
        context.put("tqs", Map.of(
                "status", "DRY_RUN_PENALTY",
                "qualityScore", qualityScore,
                "penalty", penalty,
                "penaltyReason", "FearGreedFilter",
                "band", tqsBand));
        context.put("policyMode", "NOTIFY_ONLY");
        context.put("selectedAction", "DRY_RUN_ONLY");
        context.put("signalSource", "LiveSignalEvaluator");
        return new FearGreedGateDecision(true, false, true, condition, reason,
                currentFg, threshold, penalty, qualityScore, tqsBand, context);
    }

    private boolean isReversalLikeStrategy(BtStrategy strategy) {
        String type = strategy.getStrategyType() == null ? "" : strategy.getStrategyType().toUpperCase();
        String name = strategy.getName() == null ? "" : strategy.getName().toUpperCase();
        String hay = type + " " + name;
        return hay.contains("MEI")
                || hay.contains("SQI")
                || hay.contains("REVERSAL")
                || hay.contains("PANIC")
                || hay.contains("BOTTOM")
                || hay.contains("RECOVERY");
    }

    static String resolveConfiguredRunInterval(Map<String, Object> config, Strategy strategy) {
        Object configured = config == null ? null : config.get("runIntervalCode");
        String interval = configured == null ? "" : String.valueOf(configured).trim();
        if (!interval.isBlank() || strategy == null) {
            return interval;
        }
        Map<String, Object> defaults = strategy.defaultExecutionConfig();
        Object defaultInterval = defaults == null ? null : defaults.get("runIntervalCode");
        return defaultInterval == null ? "" : String.valueOf(defaultInterval).trim();
    }

    static ExpectedRDecision computeExpectedRDecision(String strategyType,
                                                      LiveSignalContext.Snapshot snap,
                                                      double stopLossPct,
                                                      double takeProfitPct) {
        if (!Double.isFinite(stopLossPct) || !Double.isFinite(takeProfitPct)
                || stopLossPct <= 0 || takeProfitPct <= 0) {
            return ExpectedRDecision.untrusted("INVALID_TRADE_PLAN");
        }
        boolean calibratedStrategy = ScoreBuyStrategy.TYPE.equalsIgnoreCase(strategyType)
                || ScoreBuyV2Strategy.TYPE.equalsIgnoreCase(strategyType);
        if (!calibratedStrategy) {
            return ExpectedRDecision.untrusted("UNAVAILABLE_STRATEGY_HAS_NO_CALIBRATED_WIN_PROBABILITY");
        }
        if (snap == null || !Double.isFinite(snap.nnOutput)
                || snap.nnOutput < 0.0 || snap.nnOutput > 1.0) {
            return ExpectedRDecision.untrusted("UNAVAILABLE_INVALID_OR_MISSING_STRATEGY_NN_OUTPUT");
        }
        double pWin = snap.nnOutput;
        double expectedR = pWin * (takeProfitPct / stopLossPct) - (1.0 - pWin);
        return new ExpectedRDecision(expectedR, pWin,
                "LIVE_SIGNAL_CONTEXT_NN_OUTPUT:" + strategyType.toUpperCase(Locale.ROOT), true);
    }

    static record ExpectedRDecision(double expectedR,
                                    Double pWin,
                                    String provenance,
                                    boolean trusted) {
        static ExpectedRDecision untrusted(String provenance) {
            return new ExpectedRDecision(-1.0, null, provenance, false);
        }
    }

    /** Serialize engine decision into map form suitable for context_json v2 extras. */
    private Map<String, Object> ensembleDecisionToMap(
            com.agora.service.meta.TradeDecisionEngine.Decision dec) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("score", roundTo(dec.score(), 1));
        m.put("threshold", dec.threshold());
        m.put("outcome", dec.outcome());
        if (dec.vetoReason() != null) m.put("veto_reason", dec.vetoReason());
        List<Map<String, Object>> comps = new java.util.ArrayList<>();
        for (com.agora.service.meta.TradeDecisionEngine.Component c : dec.components()) {
            Map<String, Object> cm = new java.util.LinkedHashMap<>();
            cm.put("layer", c.layer());
            cm.put("points", roundTo(c.points(), 1));
            cm.put("reason", c.reason());
            comps.add(cm);
        }
        m.put("components", comps);
        if (dec.inputsEcho() != null && !dec.inputsEcho().isEmpty()) {
            m.put("inputs", dec.inputsEcho());
        }
        return m;
    }

    private static double roundTo(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    /**
     * BTC spot wick-aware exit mode: keep the exchange OCO stop below the
     * selected anti-wick policy level so a wick alone does not exit the
     * position. Risk is then controlled by position sizing rather than a tight
     * hard SL.
     */
    private WickAwareSlAdjustment applySpotWickAwareStructuralSl(Map<String, Object> config,
                                                                 String symbol,
                                                                 String intervalCode,
                                                                 BigDecimal entry,
                                                                 BigDecimal currentSl,
                                                                 List<MdKline> klines,
                                                                 int lastIndex) {
        boolean defaultEnabled = "BTCUSDT".equalsIgnoreCase(symbol);
        if (!getBoolean(config, "spotWickAwareExitEnabled", defaultEnabled)) {
            return new WickAwareSlAdjustment(false, currentSl, null, null, null, null, null, "DISABLED", "disabled");
        }
        if (entry == null || entry.signum() <= 0 || currentSl == null || currentSl.signum() <= 0
                || klines == null || klines.isEmpty() || lastIndex < 0) {
            return new WickAwareSlAdjustment(false, currentSl, null, null, null, null, null, "UNKNOWN", "insufficient_inputs");
        }
        String defaultMode = "BTCUSDT".equalsIgnoreCase(symbol) ? "ULTRA_LOW_DISASTER" : "STRUCTURAL";
        String policyMode = getString(config, "spotWickAwareSlMode", defaultMode).trim().toUpperCase();
        if (!"ULTRA_LOW_DISASTER".equals(policyMode) && !"STRUCTURAL".equals(policyMode)) {
            policyMode = defaultMode;
        }

        int lookbackBars = Math.max(12, getInt(config, "spotWickAwareLookbackBars", 72));
        int from = Math.max(0, lastIndex - lookbackBars + 1);
        int to = Math.min(lastIndex, klines.size() - 1);
        if (to < from) {
            return new WickAwareSlAdjustment(false, currentSl, null, null, null, null, null, policyMode, "invalid_window");
        }

        BigDecimal swingLow = null;
        List<MdKline> window = new java.util.ArrayList<>();
        for (int i = from; i <= to; i++) {
            MdKline bar = klines.get(i);
            if (bar == null) continue;
            window.add(bar);
            BigDecimal low = bar.getLowPrice();
            if (low != null && (swingLow == null || low.compareTo(swingLow) < 0)) {
                swingLow = low;
            }
        }
        if (swingLow == null || swingLow.signum() <= 0) {
            return new WickAwareSlAdjustment(false, currentSl, null, null, null, null, null, policyMode, "swing_low_unavailable");
        }

        BigDecimal pctBuffer = entry.multiply(BigDecimal.valueOf(
                Math.max(0.0005, getDouble(config, "spotWickAwareBufferPct", 0.0015))));
        BigDecimal atrAbs = computeAtrAbs(window, 14);
        BigDecimal atrBuffer = atrAbs != null
                ? atrAbs.multiply(BigDecimal.valueOf(Math.max(0.0, getDouble(config, "spotWickAwareAtrBufferMul", 0.30))))
                : BigDecimal.ZERO;
        BigDecimal buffer = pctBuffer.max(atrBuffer).setScale(2, RoundingMode.HALF_UP);
        BigDecimal structuralSl = swingLow.subtract(buffer).setScale(2, RoundingMode.HALF_UP);
        if (structuralSl.signum() <= 0 || structuralSl.compareTo(entry) >= 0) {
            return new WickAwareSlAdjustment(false, currentSl, structuralSl, null, swingLow, buffer, atrAbs, policyMode,
                    "invalid_structural_sl");
        }
        double disasterPct = Math.min(0.50, Math.max(0.03,
                getDouble(config, "spotWickAwareDisasterSlPct", 0.12)));
        BigDecimal disasterSl = entry.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(disasterPct)))
                .setScale(2, RoundingMode.HALF_UP);
        double fixedDisasterPrice = getDouble(config, "spotWickAwareDisasterSlPrice", 0.0);
        if (fixedDisasterPrice > 0.0) {
            BigDecimal fixed = BigDecimal.valueOf(fixedDisasterPrice).setScale(2, RoundingMode.HALF_UP);
            if (fixed.signum() > 0 && fixed.compareTo(entry) < 0) {
                disasterSl = disasterSl.min(fixed);
            }
        }
        BigDecimal selectedSl = "ULTRA_LOW_DISASTER".equals(policyMode)
                ? structuralSl.min(disasterSl)
                : structuralSl;

        if (selectedSl.signum() > 0 && selectedSl.compareTo(currentSl) < 0) {
            return new WickAwareSlAdjustment(true, selectedSl, structuralSl, disasterSl,
                    swingLow.setScale(2, RoundingMode.HALF_UP),
                    buffer, atrAbs != null ? atrAbs.setScale(2, RoundingMode.HALF_UP) : null,
                    policyMode,
                    "ULTRA_LOW_DISASTER".equals(policyMode)
                            ? "moved_hard_sl_to_ultra_low_disaster"
                            : "moved_hard_sl_below_swing_low");
        }
        return new WickAwareSlAdjustment(false, currentSl, structuralSl, disasterSl,
                swingLow.setScale(2, RoundingMode.HALF_UP),
                buffer, atrAbs != null ? atrAbs.setScale(2, RoundingMode.HALF_UP) : null,
                policyMode,
                "existing_sl_already_below_structure");
    }

    private BigDecimal computeAtrAbs(List<MdKline> bars, int period) {
        if (bars == null || bars.size() < 2) return null;
        int start = Math.max(1, bars.size() - Math.max(2, period));
        List<BigDecimal> ranges = new java.util.ArrayList<>();
        for (int i = start; i < bars.size(); i++) {
            MdKline cur = bars.get(i);
            MdKline prev = bars.get(i - 1);
            if (cur.getHighPrice() == null || cur.getLowPrice() == null) continue;
            BigDecimal highLow = cur.getHighPrice().subtract(cur.getLowPrice()).abs();
            BigDecimal highClose = prev.getClosePrice() != null
                    ? cur.getHighPrice().subtract(prev.getClosePrice()).abs()
                    : BigDecimal.ZERO;
            BigDecimal lowClose = prev.getClosePrice() != null
                    ? cur.getLowPrice().subtract(prev.getClosePrice()).abs()
                    : BigDecimal.ZERO;
            ranges.add(highLow.max(highClose).max(lowClose));
        }
        if (ranges.isEmpty()) return null;
        BigDecimal sum = ranges.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(ranges.size()), 8, RoundingMode.HALF_UP);
    }

    /**
     * 計算 SL/TP 百分比。
     *
     * <p>若 {@code atrStopLossEnabled=true} 且 ATR 可用，使用 ATR × multiplier：
     * <ul>
     *   <li>slPct = clamp(atrPct × atrStopLossMultiplier / 100, min=1%, max=8%)</li>
     *   <li>tpPct = clamp(atrPct × atrTakeProfitMultiplier / 100, min=2%, max=15%)</li>
     * </ul>
     * 高波動時 SL 自然放寬，避免被雜訊洗掉；低波動時 SL 收窄，改善 R:R。
     *
     * <p>預設 multiplier：SL=2.5、TP=5.0（R:R=2:1）。
     * 關閉或無 ATR 時回退 {@code fixedStopLossPct} / {@code fixedTakeProfitPct}（預設 5%/10%）。
     */
    private double[] computeSlTpPcts(Map<String, Object> config, String symbol, String intervalCode) {
        // 與 BacktestEngine 共用同一組 key：atrSlMultiplier / atrTpMultiplier。
        // multiplier > 0 即視為啟用；預設仍為固定百分比。
        double slMul = getDouble(config, "atrSlMultiplier", 0.0);
        double tpMul = getDouble(config, "atrTpMultiplier", 0.0);
        double fixedSl = getDouble(config, "fixedStopLossPct",   0.05);
        double fixedTp = getDouble(config, "fixedTakeProfitPct", 0.10);
        // #438 Sub-fix C — horizon-aware cap: 短 horizon 的固定/ATR TP 不該超過 24h 預期移動。
        int maxHoldingHours = getInt(config, "maxHoldingHours", 0);

        // #438 Sub-fix A — atrFallback opt-in: strategy 沒明確設 fixed/atr multiplier 時,
        // 用合理 ATR-based 預設 (1.5×ATR / 3.0×ATR, R:R 2:1) 取代僵硬的 5%/10% fixed。
        // Backward compat: 預設 false,strategy 需顯式 enable 或 multiplier > 0 才啟用 ATR path。
        boolean atrFallback = getBoolean(config, "atrFallback", false);
        if (atrFallback && slMul <= 0 && tpMul <= 0) {
            slMul = getDouble(config, "atrFallbackSlMul", 1.5);
            tpMul = getDouble(config, "atrFallbackTpMul", 3.0);
            log.info("[LiveSignal] atrFallback {}@{}: applying default sl×{} tp×{}",
                    symbol, intervalCode, slMul, tpMul);
        }

        // When only higherTfForSl / antiStopHuntOffset is set (no ATR multipliers),
        // apply offset to fixed SL and return early — preserves backtest compatibility.
        if (slMul <= 0 && tpMul <= 0) {
            double sl = fixedSl;
            if (getBoolean(config, "antiStopHuntOffset", true)) {
                sl = Math.min(0.08, sl + 0.0003 + Math.random() * 0.0005);
            }
            return applyHorizonCap(sl, fixedTp, maxHoldingHours, symbol, intervalCode);
        }

        // higherTfForSl: use a higher timeframe ATR for SL to reduce stop-hunt risk.
        // e.g. "4h" on a 1h strategy uses 4h ATR (wider, follows major structure).
        // SL uses higherTf ATR; TP continues to use signal-timeframe ATR.
        String higherTfForSl = getString(config, "higherTfForSl", null);

        try {
            AiStrategyDiscoveryService.MarketSnapshot ms =
                    aiDiscoveryService.buildMarketSnapshot(symbol, intervalCode);
            double atrPct = ms.atrPct();  // 百分比數值，如 1.18 表示 1.18%
            if (atrPct <= 0) return new double[]{fixedSl, fixedTp};

            // Fetch higher-timeframe ATR for SL if configured
            if (higherTfForSl != null && !higherTfForSl.isBlank() && slMul > 0) {
                try {
                    AiStrategyDiscoveryService.MarketSnapshot msHtf =
                            aiDiscoveryService.buildMarketSnapshot(symbol, higherTfForSl);
                    double htfAtrPct = msHtf.atrPct();
                    if (htfAtrPct > atrPct) {
                        log.info("[LiveSignal] higherTfForSl={}: 1h_atr={}% → {}_atr={}% (wider SL)",
                                higherTfForSl, String.format("%.2f", atrPct),
                                higherTfForSl, String.format("%.2f", htfAtrPct));
                        atrPct = htfAtrPct;  // use higher-tf ATR for SL calculation only
                    }
                } catch (Exception e) {
                    log.warn("[LiveSignal] higherTfForSl={} fetch failed, falling back to {}: {}",
                            higherTfForSl, intervalCode, e.getMessage());
                }
            }
            // BacktestEngine 的 atrPct 為 fraction（0.0118），這裡是百分比（1.18）。
            // 為了與 BacktestEngine 行為一致，轉為 fraction 後再乘 multiplier。
            double atrFrac = atrPct / 100.0;

            // ── ATR Spike 收斂：SL 保持 current ATR（完整保護），TP 改用 baseline ATR ──
            // spikeMultiple 預設 2.0：current > baseline × 2 才視為 spike
            // convergenceFactor 預設 1.5：TP 用 baseline × 1.5（給一點額外空間，不過度收緊）
            double tpAtrFrac = atrFrac;  // 預設：TP 用 current ATR
            boolean spikeEnabled = getBoolean(config, "atrSpikeConvergenceEnabled", true);
            if (spikeEnabled && tpMul > 0) {
                double spikeMultiple      = getDouble(config, "atrSpikeMultiple",      2.0);
                double convergenceFactor  = getDouble(config, "atrSpikeTpConvergence", 1.5);
                if (ms.isAtrSpike(spikeMultiple)) {
                    tpAtrFrac = (ms.baselineAtrPct() * convergenceFactor) / 100.0;
                    log.info("[LiveSignal] ATR spike {}@{}: current={}% baseline={}% → TP ATR={}% (spike×{} conv×{})",
                            symbol, intervalCode,
                            String.format("%.2f", atrPct),
                            String.format("%.2f", ms.baselineAtrPct()),
                            String.format("%.2f", tpAtrFrac * 100),
                            spikeMultiple, convergenceFactor);
                }
            }
            // ─────────────────────────────────────────────────────────────────────

            double slPct = slMul > 0 ? Math.max(0.003, Math.min(0.08, atrFrac   * slMul)) : fixedSl;
            double tpPct = tpMul > 0 ? Math.max(0.005, Math.min(0.20, tpAtrFrac * tpMul)) : fixedTp;

            // Anti-stop-hunt offset: add a small random buffer (0.03%–0.08%) so the SL
            // price lands at a non-round number, avoiding predictable liquidity sweeps.
            if (getBoolean(config, "antiStopHuntOffset", true)) {
                double offset = 0.0003 + Math.random() * 0.0005;
                slPct = Math.min(0.08, slPct + offset);
            }

            // ── R:R floor：避免 spike 收斂後 TP 太接近 SL ──
            // 預設 minRiskReward = 1.5（TP ≥ SL × 1.5），可透過 config 覆蓋
            double minRR = getDouble(config, "minRiskReward", 1.5);
            double tpFloor = slPct * minRR;
            if (tpPct < tpFloor) {
                tpPct = Math.min(0.20, tpFloor);
                log.info("[LiveSignal] R:R floor applied {}@{}: tp→{}% (sl={}% rr≥{}x)",
                        symbol, intervalCode,
                        String.format("%.2f", tpPct * 100),
                        String.format("%.2f", slPct * 100),
                        minRR);
            }
            // ──────────────────────────────────────────────

            log.info("[LiveSignal] ATR-SL/TP {}@{}: atr={}% baseline={}% sl={}% tp={}%",
                    symbol, intervalCode,
                    String.format("%.2f", atrPct),
                    String.format("%.2f", ms.baselineAtrPct()),
                    String.format("%.2f", slPct * 100),
                    String.format("%.2f", tpPct * 100));
            return applyHorizonCap(slPct, tpPct, maxHoldingHours, symbol, intervalCode);
        } catch (Exception e) {
            log.warn("[LiveSignal] ATR-SL/TP fallback for {}@{}: {}", symbol, intervalCode, e.getMessage());
            return applyHorizonCap(fixedSl, fixedTp, maxHoldingHours, symbol, intervalCode);
        }
    }

    /**
     * #438 Sub-fix C — horizon-aware TP/SL cap.
     *
     * <p>24h horizon 的 strategy 設 +10% TP 不切實際:預期 24h 移動 (√24 × 1h-ATR ≈ 4-5%) 通常
     * 遠 &lt; 10%,大部分倉位被 max-hold 強制平倉,實際 R:R 比帳面差。短 horizon 必須收緊 TP。
     *
     * <p>Matrix(per #438):
     * <table>
     *   <tr><th>maxHoldingHours</th><th>TP cap</th><th>SL cap</th></tr>
     *   <tr><td>0 (no limit)</td><td>20%</td><td>10%</td></tr>
     *   <tr><td>≤ 12h</td><td>4%</td><td>3%</td></tr>
     *   <tr><td>13-24h</td><td>6%</td><td>4%</td></tr>
     *   <tr><td>25-48h</td><td>10%</td><td>6%</td></tr>
     *   <tr><td>49-72h</td><td>15%</td><td>8%</td></tr>
     *   <tr><td>&gt; 72h</td><td>20%</td><td>10%</td></tr>
     * </table>
     */
    private double[] applyHorizonCap(double slPct, double tpPct, int maxHoldingHours,
                                     String symbol, String intervalCode) {
        double tpCap;
        double slCap;
        if (maxHoldingHours <= 0) {
            tpCap = 0.20;
            slCap = 0.10;
        } else if (maxHoldingHours <= 12) {
            tpCap = 0.04;
            slCap = 0.03;
        } else if (maxHoldingHours <= 24) {
            tpCap = 0.06;
            slCap = 0.04;
        } else if (maxHoldingHours <= 48) {
            tpCap = 0.10;
            slCap = 0.06;
        } else if (maxHoldingHours <= 72) {
            tpCap = 0.15;
            slCap = 0.08;
        } else {
            tpCap = 0.20;
            slCap = 0.10;
        }
        double cappedSl = Math.min(slPct, slCap);
        double cappedTp = Math.min(tpPct, tpCap);
        if (cappedSl < slPct || cappedTp < tpPct) {
            log.info("[LiveSignal] horizon-cap {}@{} maxHold={}h: sl {}%→{}% tp {}%→{}%",
                    symbol, intervalCode, maxHoldingHours,
                    String.format("%.2f", slPct * 100), String.format("%.2f", cappedSl * 100),
                    String.format("%.2f", tpPct * 100), String.format("%.2f", cappedTp * 100));
        }
        return new double[]{cappedSl, cappedTp};
    }

    /**
     * P1: Regime-aware config override — called before {@code impl.evaluate()} to tighten or
     * relax entry parameters based on the Gemini market regime.
     *
     * <p>Only modifies {@code adxEntryThreshold}: higher values in hostile regimes force the
     * strategy to require stronger trend confirmation before signalling. Other parameters (SL/TP,
     * cooldown) are not touched here — regime adjustments to position sizing can be added later
     * once shadow data validates the direction.
     *
     * <p>Adjustments (applied on top of the per-strategy configured value):
     * <ul>
     *   <li>TRENDING_DOWN: +8 — heavy counter-trend risk; require very strong trend reversal signal</li>
     *   <li>SIDEWAYS:      +5 — chop / false breakout risk; require cleaner trend</li>
     *   <li>VOLATILE:      +3 — unpredictable swings; slight tightening</li>
     *   <li>TRENDING_UP:   −2 — aligned regime; allow slightly earlier entry</li>
     *   <li>RECOVERY:      0  — neutral; no adjustment</li>
     * </ul>
     *
     * <p>Strategy configs may override via {@code allowLongInBearRegime=true} (handled separately
     * at the post-signal stage, not here — this method only touches ADX threshold).
     */
    /**
     * Converts an interval code (e.g., "1h", "4h") to its duration in minutes.
     * Used by the DataFreshness guard (L0) to compute the staleness threshold.
     */
    private static int parseIntervalMinutes(String intervalCode) {
        if (intervalCode == null) return 60;
        return switch (intervalCode.toLowerCase()) {
            case "1m"  -> 1;
            case "5m"  -> 5;
            case "15m" -> 15;
            case "30m" -> 30;
            case "1h"  -> 60;
            case "4h"  -> 240;
            case "1d"  -> 1440;
            case "1w"  -> 10080;
            default    -> 60;   // safe fallback
        };
    }

    private void applyRegimeConfigOverrides(Map<String, Object> config, String regime) {
        if (regime == null) return;
        int baseAdx = getInt(config, "adxEntryThreshold", 22);
        int delta = switch (regime.toUpperCase()) {
            case "TRENDING_DOWN" -> 8;
            case "SIDEWAYS"      -> 5;
            case "VOLATILE"      -> 3;
            case "TRENDING_UP"   -> -2;
            default              -> 0;   // RECOVERY and unknown: no change
        };
        if (delta != 0) {
            int adjusted = Math.max(15, baseAdx + delta);  // floor at 15 to avoid nonsensical values
            config.put("adxEntryThreshold", (double) adjusted);
            log.debug("[Regime] adxEntryThreshold adjusted {} → {} (regime={})",
                    baseAdx, adjusted, regime);
        }
    }

    /** #272: 在 TG 通知成功後建立信號結果驗證記錄。失敗不影響主流程。 */
    private void createSignalVerification(
            com.agora.model.BtLiveSignal signal,
            com.agora.service.meta.TradeDecisionEngine.Decision ensembleDecision) {
        try {
            if (signalVerificationRepo.existsByLiveSignalId(signal.getId())) return;
            if (signal.getSuggestedSl() == null || signal.getSuggestedTp() == null) return;

            java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
            String decision = "PASS";
            String layer = "PASS";
            if (ensembleDecision != null) {
                if ("BLOCK".equals(ensembleDecision.outcome())) {
                    decision = "BLOCK";
                    layer = "EnsembleGate";
                } else if ("VETO".equals(ensembleDecision.outcome())) {
                    decision = "BLOCK";
                    layer = "EnsembleGate[VETO]";
                } else {
                    layer = "EnsembleGate";
                }
            }

            boolean duplicateShape = signalVerificationRepo.existsWatchingDuplicateShapeSince(
                    now.minusDays(7),
                    signal.getSymbol(),
                    signal.getIntervalCode(),
                    signal.getSide(),
                    decision,
                    layer,
                    signal.getEntryPrice(),
                    signal.getSuggestedSl(),
                    signal.getSuggestedTp());
            if (duplicateShape) {
                log.info("[SignalVerifier] skip duplicate-like verification shape live_signal_id={} {}@{} decision={} layer={} entry={} sl={} tp={}",
                        signal.getId(), signal.getSymbol(), signal.getIntervalCode(), decision, layer,
                        signal.getEntryPrice(), signal.getSuggestedSl(), signal.getSuggestedTp());
                return;
            }

            com.agora.model.SignalOutcomeVerification v = new com.agora.model.SignalOutcomeVerification();
            v.setLiveSignalId(signal.getId());
            v.setSymbol(signal.getSymbol());
            v.setIntervalCode(signal.getIntervalCode());
            v.setSide(signal.getSide());
            v.setDecision(decision);
            v.setDecisionLayer(layer);
            v.setEntryPrice(signal.getEntryPrice());
            v.setSlPrice(signal.getSuggestedSl());
            v.setTpPrice(signal.getSuggestedTp());
            v.setOutcome("WATCHING");
            v.setCreatedAt(now);
            signalVerificationRepo.save(v);
            log.debug("[SignalVerifier] created for live_signal_id={} decision={} layer={}",
                    signal.getId(), decision, layer);
        } catch (Exception e) {
            log.warn("[SignalVerifier] create failed for live_signal_id={}: {}", signal.getId(), e.getMessage());
        }
    }
}
