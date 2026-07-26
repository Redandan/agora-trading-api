package com.agora.service.tradingview;

import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties.ExecutionMode;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.BtStrategyService;
import com.agora.service.backtest.BacktestEngine;
import com.agora.service.backtest.LiveSignalContext;
import com.agora.service.backtest.Strategy;
import com.agora.service.backtest.StrategyContext;
import com.agora.service.backtest.StrategyRegistry;
import com.agora.service.backtest.StrategySignal;
import com.agora.service.backtest.TradingViewScoreBuyModel;
import com.agora.service.meta.DecisionAuditWriter;
import com.agora.service.strategy.RuntimeStrategy;
import com.agora.service.strategy.StrategyLifecycleMode;
import com.agora.service.strategy.StrategyRuntimeCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Free TradingView replacement lane.
 *
 * <p>This service evaluates the local TradingView-parity ScoreBuy strategy on
 * closed K-lines and writes decision audit rows for each Pine-equivalent order
 * intent. Owner 509 may pass only the current complete daily bar to the
 * minimal LIVE adapter; catch-up bars remain audit-only.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalTradingViewSignalEvaluator implements RuntimeStrategy {

    private static final String SOURCE = "LOCAL_TRADINGVIEW_PARITY";
    private static final String NO_BUY_REASON = "LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE";
    private static final int EVALUATION_ORDER = 100;

    private final TradingViewLocalSignalProperties props;
    private final BtStrategyService strategyService;
    private final StrategyRegistry strategyRegistry;
    private final BacktestEngine backtestEngine;
    private final MdKlineRepository klineRepository;
    private final DecisionAuditWriter auditWriter;
    private final TradingViewScoreBuyAutoExitPaperService paperService;
    private final TradingViewScoreBuyAutoExitLiveService liveService;
    private final StrategyRuntimeCatalog strategyRuntimeCatalog;
    private final Map<String, Instant> seenKeys = new ConcurrentHashMap<>();

    @Override
    public String key() {
        return TradingViewScoreBuyAutoExitStrategyContract.KEY;
    }

    @Override
    public int evaluationOrder() {
        return EVALUATION_ORDER;
    }

    @Override
    public void onClosedBar(MdKline kline) {
        log.debug("[LocalTradingView] evaluate strategy runtime {}@{} openTime={} "
                        + "source={} enabled={}",
                kline.getSymbol(),
                kline.getIntervalCode(),
                kline.getOpenTime(),
                kline.getSource(),
                isEnabled());
        evaluate(kline);
    }

    public boolean isEnabled() {
        if (!props.enabled() || !props.effectiveExecutionEnabled()) {
            return false;
        }
        StrategyLifecycleMode expectedMode =
                props.executionMode() == ExecutionMode.BTC_BASE_LIVE
                        ? StrategyLifecycleMode.LIVE
                        : StrategyLifecycleMode.PAPER;
        return strategyRuntimeCatalog.isMode(
                TradingViewScoreBuyAutoExitStrategyContract.KEY, expectedMode);
    }

    public void evaluate(MdKline eventKline) {
        if (!isEnabled() || eventKline == null) {
            return;
        }

        String symbol = normalizeSymbol(eventKline.getSymbol());
        String interval = normalizeInterval(eventKline.getIntervalCode());
        String source = normalizeSource(eventKline.getSource());
        if (!allowed(symbol, props.allowedSymbols(), true)
                || !allowed(interval, props.allowedIntervals(), false)
                || !allowed(source, props.allowedSources(), false)) {
            return;
        }
        if (props.strategyId() != TradingViewScoreBuyAutoExitStrategyContract.CURRENT_DATABASE_STRATEGY_ID) {
            log.error("[LocalTradingView] refuse mismatched strategy mapping configuredId={} contract={} expectedDatabaseId={} legacyCollisionId={}",
                    props.strategyId(),
                    TradingViewScoreBuyAutoExitStrategyContract.KEY,
                    TradingViewScoreBuyAutoExitStrategyContract.CURRENT_DATABASE_STRATEGY_ID,
                    TradingViewScoreBuyAutoExitStrategyContract.LEGACY_DATABASE_ID_COLLISION);
            return;
        }

        try {
            BtStrategy strategyEntity = strategyService.getRequired(props.strategyId());
            Strategy strategy = strategyRegistry.getRequiredStrategy(strategyEntity.getStrategyType());
            Map<String, Object> config = new LinkedHashMap<>(strategyService.parseConfig(strategyEntity.getConfigJson()));
            strategy.defaultExecutionConfig().forEach(config::putIfAbsent);
            config.put("runIntervalCode", interval);
            config.put("tradingViewParityMode", true);
            config.put("tradingViewAllowIncompleteHistoryShadowIntents", true);

            List<MdKline> klines = loadKlines(eventKline, symbol, interval, source);
            int index = indexOf(klines, eventKline.getOpenTime());
            if (index < 0) {
                log.debug("[LocalTradingView] skip {}@{} openTime={} source={} because event bar is not in history",
                        symbol, interval, eventKline.getOpenTime(), source);
                return;
            }

            Map<String, double[]> indicators = backtestEngine.buildIndicators(klines, config);
            evaluatePaperLedgerIfEnabled(
                    strategyEntity, strategy, config, klines, indicators, eventKline, interval, source);
            int catchUpBars = Math.max(1, props.catchUpBars());
            int startIndex = Math.max(0, index - catchUpBars + 1);
            List<BarEvaluation> evaluations = new ArrayList<>();
            for (int evalIndex = startIndex; evalIndex <= index; evalIndex++) {
                BarEvaluation evaluation = evaluateBar(strategy, config, klines, indicators, evalIndex,
                        interval, source, eventKline.getOpenTime());
                if (evaluation != null) {
                    evaluations.add(evaluation);
                }
            }

            if (props.executionMode() == ExecutionMode.BTC_BASE_LIVE) {
                evaluations.stream()
                        .filter(evaluation -> Objects.equals(
                                evaluation.kline().getOpenTime(), eventKline.getOpenTime()))
                        .findFirst()
                        .ifPresent(evaluation -> liveService.evaluate(
                                strategyEntity,
                                evaluation.kline(),
                                source,
                                hasCompleteReplayHistory(evaluation.details())
                                        ? evaluation.intents() : List.of(),
                                evaluation.details()));
            }

            for (BarEvaluation evaluation : evaluations) {
                if (evaluation.signal() != StrategySignal.BUY || evaluation.intents().isEmpty()) {
                    auditNoBuy(strategyEntity, evaluation.kline(), interval, source, evaluation.signal(),
                            evaluation.details(), eventKline.getOpenTime(), catchUpBars);
                    continue;
                }
                TradingViewAccumulationOrderPlanner.Plan accumulationPlan =
                        TradingViewAccumulationOrderPlanner.plan(
                                evaluation.intents(),
                                props.defaultNotionalUsdt(),
                                props.maxNotionalUsdt());
                int intentIndex = 0;
                for (LiveSignalContext.OrderIntent intent : evaluation.intents()) {
                    intentIndex++;
                    boolean historyComplete = hasCompleteReplayHistory(evaluation.details());
                    String executionSelection = !historyComplete
                            ? "SHADOW_ONLY_INCOMPLETE_REPLAY_HISTORY"
                            : Objects.equals(evaluation.kline().getOpenTime(), eventKline.getOpenTime())
                            ? props.executionMode() == ExecutionMode.BTC_BASE_LIVE
                            ? "LIVE_CURRENT_BAR_INTENT"
                            : "PAPER_CURRENT_BAR_INTENT"
                            : "CATCH_UP_AUDIT_ONLY";
                    auditIntent(strategyEntity, evaluation.kline(), interval, source, intent,
                            evaluation.details(), intentIndex, eventKline.getOpenTime(), catchUpBars,
                            executionSelection, accumulationPlan);
                }
            }
        } catch (Exception e) {
            log.warn("[LocalTradingView] evaluate failed symbol={} interval={} source={} openTime={} err={}",
                    symbol, interval, source, eventKline.getOpenTime(), e.getMessage());
        }
    }

    private BarEvaluation evaluateBar(Strategy strategy,
                             Map<String, Object> config,
                             List<MdKline> klines,
                             Map<String, double[]> indicators,
                             int index,
                             String interval,
                             String source,
                             LocalDateTime triggerOpenTime) {
        LiveSignalContext.clear();
        try {
            MdKline previous = index > 0 ? klines.get(index - 1) : null;
            StrategyContext context = new StrategyContext(index, klines.get(index), previous, klines, indicators);
            StrategySignal signal = strategy.evaluate(context, config);
            List<LiveSignalContext.OrderIntent> intents = List.copyOf(LiveSignalContext.getOrderIntents());
            Map<String, Object> details = new LinkedHashMap<>(LiveSignalContext.getDetails());
            return new BarEvaluation(klines.get(index), signal, intents, details);
        } catch (Exception e) {
            MdKline kline = klines.get(index);
            log.warn("[LocalTradingView] evaluate bar failed symbol={} interval={} source={} barTime={} triggerBar={} err={}",
                    kline.getSymbol(), interval, source, kline.getOpenTime(), triggerOpenTime, e.toString());
            return null;
        } finally {
            LiveSignalContext.clear();
        }
    }

    private List<MdKline> loadKlines(MdKline eventKline, String symbol, String interval, String source) {
        if ("BTCUSDT".equals(symbol) && "1d".equals(interval) && "binance".equals(source)) {
            List<MdKline> anchored = klineRepository
                    .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                            symbol,
                            interval,
                            source,
                            TradingViewScoreBuyModel.BTCUSDT_1D_REPLAY_START_UTC,
                            eventKline.getOpenTime());
            List<MdKline> klines = anchored == null
                    ? new ArrayList<>()
                    : new ArrayList<>(anchored);
            klines.sort(Comparator.comparing(MdKline::getOpenTime));
            if (indexOf(klines, eventKline.getOpenTime()) < 0) {
                klines.add(eventKline);
                klines.sort(Comparator.comparing(MdKline::getOpenTime));
            }
            return klines;
        }
        int limit = Math.max(10, props.historyBars());
        List<MdKline> descending = hasText(source)
                ? klineRepository.findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(
                        symbol, interval, source, PageRequest.of(0, limit))
                : klineRepository.findBySymbolAndIntervalCodeOrderByOpenTimeDesc(
                        symbol, interval, PageRequest.of(0, limit));
        List<MdKline> klines = new ArrayList<>(descending);
        klines.sort(Comparator.comparing(MdKline::getOpenTime));
        if (indexOf(klines, eventKline.getOpenTime()) < 0) {
            klines.add(eventKline);
            klines.sort(Comparator.comparing(MdKline::getOpenTime));
        }
        return klines;
    }

    private void auditNoBuy(BtStrategy strategy, MdKline kline, String interval, String source,
                            StrategySignal signal, Map<String, Object> details,
                            LocalDateTime triggerOpenTime, int catchUpBars) {
        String signalName = signal == null ? StrategySignal.HOLD.name() : signal.name();
        String auditSide = StrategySignal.HOLD.name();
        String reason = signal == StrategySignal.BUY
                ? "LOCAL_TRADINGVIEW_BUY_WITHOUT_ORDER_INTENT"
                : NO_BUY_REASON;
        String key = String.join("|",
                String.valueOf(strategy.getId()),
                normalizeSymbol(kline.getSymbol()),
                interval,
                String.valueOf(kline.getOpenTime()),
                source == null ? "" : source,
                "NO_BUY",
                signalName);
        if (seenKeys.putIfAbsent(key, Instant.now()) != null) {
            return;
        }
        evictOldKeys();

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("source", SOURCE);
        context.put("signalSource", "LOCAL_TRADINGVIEW");
        context.put("strategyContractKey", TradingViewScoreBuyAutoExitStrategyContract.KEY);
        context.put("entryContractKey", TradingViewScoreBuyAutoExitStrategyContract.ENTRY_CONTRACT_KEY);
        context.put("strategyOwnerAlias", TradingViewScoreBuyAutoExitStrategyContract.OWNER_ALIAS);
        context.put("strategyId", strategy.getId());
        context.put("strategy", strategy.getName());
        context.put("strategyType", strategy.getStrategyType());
        context.put("klineSource", source == null ? "" : source);
        context.put("action", "WAIT");
        context.put("selectedAction", "WAIT");
        context.put("decision", "LOCAL_TRADINGVIEW_NO_BUY");
        context.put("side", auditSide);
        context.put("symbol", normalizeSymbol(kline.getSymbol()));
        context.put("timeframe", interval);
        context.put("barTime", kline.getOpenTime() == null ? "" : kline.getOpenTime().toString());
        context.put("price", kline.getClosePrice());
        context.put("idempotencyKey", key);
        context.put("triggerBarTime", triggerOpenTime == null ? "" : triggerOpenTime.toString());
        context.put("catchUpBars", catchUpBars);
        context.put("catchUpEvaluation", !Objects.equals(kline.getOpenTime(), triggerOpenTime));
        context.put("currentSignalDecision", signalName);
        context.put("currentSignalSource", "LOCAL_TRADINGVIEW");
        context.put("noBuyReason", reason);
        context.put("noCurrentBuyCandidateReason", reason);
        context.put("intentCreated", false);
        context.put("orderSent", false);
        context.put("blockers", reason);
        context.put("executionMode", "LOCAL_TRADINGVIEW_PARITY_EVALUATION");
        context.put("executionModeSetting", props.executionMode().name());
        context.put("executionEnabled", props.effectiveExecutionEnabled());
        context.put("executionDryRun", props.effectiveExecutionDryRun());
        context.put("executionLiveOrderEnabled", props.effectiveExecutionLiveOrderEnabled());
        context.put("suppressionReason", "LOCAL_TRADINGVIEW_NO_BUY");
        if (details != null) {
            details.forEach((name, value) -> context.put("strategyDecision." + name, value));
        }

        auditWriter.logSignalEval(strategy.getId(), normalizeSymbol(kline.getSymbol()), interval,
                kline.getOpenTime(), auditSide, context);
    }

    private void auditIntent(BtStrategy strategy, MdKline kline, String interval, String source,
                             LiveSignalContext.OrderIntent intent, Map<String, Object> details, int intentIndex,
                             LocalDateTime triggerOpenTime, int catchUpBars,
                             String executionSelection,
                             TradingViewAccumulationOrderPlanner.Plan accumulationPlan) {
        String key = String.join("|",
                String.valueOf(strategy.getId()),
                normalizeSymbol(kline.getSymbol()),
                interval,
                String.valueOf(kline.getOpenTime()),
                source == null ? "" : source,
                String.valueOf(intentIndex),
                intent.reason());
        if (seenKeys.putIfAbsent(key, Instant.now()) != null) {
            return;
        }
        evictOldKeys();

        TradingViewAccumulationOrderPlanner.IntentPlan intentPlan =
                accumulationPlan.intents().get(intentIndex - 1);
        BigDecimal requested = intentPlan.requestedNotionalUsdt();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("source", SOURCE);
        context.put("signalSource", "LOCAL_TRADINGVIEW");
        context.put("strategyContractKey", TradingViewScoreBuyAutoExitStrategyContract.KEY);
        context.put("entryContractKey", TradingViewScoreBuyAutoExitStrategyContract.ENTRY_CONTRACT_KEY);
        context.put("strategyOwnerAlias", TradingViewScoreBuyAutoExitStrategyContract.OWNER_ALIAS);
        context.put("strategyId", strategy.getId());
        context.put("strategy", strategy.getName());
        context.put("strategyType", strategy.getStrategyType());
        context.put("klineSource", source == null ? "" : source);
        context.put("action", "BUY");
        context.put("symbol", normalizeSymbol(kline.getSymbol()));
        context.put("timeframe", interval);
        context.put("barTime", kline.getOpenTime() == null ? "" : kline.getOpenTime().toString());
        context.put("price", kline.getClosePrice());
        context.put("orderReason", intent.reason());
        context.put("orderLabel", intent.label());
        context.put("tradingViewQuantity", intent.quantity());
        context.put("orderIntentIndex", intentIndex);
        context.put("idempotencyKey", key);
        context.put("triggerBarTime", triggerOpenTime == null ? "" : triggerOpenTime.toString());
        context.put("catchUpBars", catchUpBars);
        context.put("catchUpEvaluation", !Objects.equals(kline.getOpenTime(), triggerOpenTime));
        context.put("requestedNotionalUsdt", requested);
        context.put("intentWeight", intentPlan.weight());
        context.put("aggregateIntentWeight", accumulationPlan.aggregateWeight());
        context.put("aggregateIntentReasons", accumulationPlan.aggregateReasons());
        context.put("aggregateRequestedNotionalUsdt", accumulationPlan.requestedNotionalUsdt());
        context.put("aggregateMaxOrderNotionalUsdt", accumulationPlan.maxOrderNotionalUsdt());
        context.put("aggregateNotionalWithinCap", accumulationPlan.withinOrderCap());
        context.put("effectiveNotionalUsdt", accumulationPlan.requestedNotionalUsdt());
        boolean liveCurrentBar = props.executionMode() == ExecutionMode.BTC_BASE_LIVE
                && Objects.equals(kline.getOpenTime(), triggerOpenTime)
                && hasCompleteReplayHistory(details);
        context.put("dryRun", !liveCurrentBar);
        context.put("selectedAction", liveCurrentBar
                ? "LIVE_EXECUTION_INTENT"
                : props.executionMode() == ExecutionMode.BTC_BASE_PAPER
                ? "PAPER_EXECUTION_INTENT" : "SHADOW_EXECUTION_INTENT");
        context.put("intentCreated", true);
        context.put("orderSent", false);
        context.put("duplicate", false);
        context.put("blockers", liveCurrentBar
                ? ""
                : props.executionMode() == ExecutionMode.BTC_BASE_PAPER
                ? "PAPER_MODE_NO_EXCHANGE_ORDER"
                : "LOCAL_TRADINGVIEW_DRY_RUN");
        context.put("executionModeSetting", props.executionMode().name());
        context.put("executionEnabled", props.effectiveExecutionEnabled());
        context.put("executionDryRun", props.effectiveExecutionDryRun());
        context.put("executionLiveOrderEnabled", props.effectiveExecutionLiveOrderEnabled());
        context.put("liveExecutionSelected", liveCurrentBar);
        context.put("executionSelection", executionSelection);
        context.put("suppressionReason", liveCurrentBar ? "" : "NON_CURRENT_OR_NON_LIVE");
        if (details != null) {
            details.forEach((name, value) -> context.put("strategyDecision." + name, value));
        }

        auditWriter.logSignalEval(strategy.getId(), normalizeSymbol(kline.getSymbol()), interval,
                kline.getOpenTime(), "BUY", context);
    }

    private void evaluatePaperLedgerIfEnabled(BtStrategy strategyEntity,
                                              Strategy strategy,
                                              Map<String, Object> config,
                                              List<MdKline> klines,
                                              Map<String, double[]> indicators,
                                              MdKline eventKline,
                                              String interval,
                                              String source) {
        if (props.executionMode() != ExecutionMode.BTC_BASE_PAPER) {
            return;
        }
        List<TradingViewScoreBuyAutoExitPaperEngine.PaperBar> paperBars =
                new ArrayList<>(klines.size());
        for (int paperIndex = 0; paperIndex < klines.size(); paperIndex++) {
            BarEvaluation evaluation = evaluateBar(
                    strategy,
                    config,
                    klines,
                    indicators,
                    paperIndex,
                    interval,
                    source,
                    eventKline.getOpenTime());
            if (evaluation == null) {
                paperService.recordBlocked(
                        strategyEntity,
                        eventKline,
                        source,
                        "PAPER_STRATEGY_EVALUATION_FAILED");
                return;
            }
            List<LiveSignalContext.OrderIntent> executableIntents =
                    hasCompleteReplayHistory(evaluation.details())
                            ? evaluation.intents()
                            : List.of();
            paperBars.add(new TradingViewScoreBuyAutoExitPaperEngine.PaperBar(
                    evaluation.kline().getOpenTime(),
                    evaluation.kline().getOpenPrice(),
                    evaluation.kline().getClosePrice(),
                    executableIntents));
        }
        paperService.evaluate(strategyEntity, eventKline, source, paperBars);
    }

    private boolean hasCompleteReplayHistory(Map<String, Object> details) {
        if (details == null || !details.containsKey("tradingview_history_complete")) {
            return true;
        }
        return Boolean.TRUE.equals(details.get("tradingview_history_complete"));
    }

    private record BarEvaluation(MdKline kline,
                                 StrategySignal signal,
                                 List<LiveSignalContext.OrderIntent> intents,
                                 Map<String, Object> details) {
    }

    private void evictOldKeys() {
        Instant cutoff = Instant.now().minusSeconds(24 * 3600L);
        seenKeys.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }

    private int indexOf(List<MdKline> klines, LocalDateTime openTime) {
        if (openTime == null) {
            return -1;
        }
        for (int i = 0; i < klines.size(); i++) {
            if (openTime.equals(klines.get(i).getOpenTime())) {
                return i;
            }
        }
        return -1;
    }

    private boolean allowed(String value, String csv, boolean normalizeAsSymbol) {
        if (!hasText(csv)) {
            return true;
        }
        if (!hasText(value)) {
            return false;
        }
        Set<String> allowed = new HashSet<>();
        for (String token : csv.split(",")) {
            String normalized = normalizeAsSymbol ? normalizeSymbol(token) : token.trim().toLowerCase(Locale.ROOT);
            if (hasText(normalized)) {
                allowed.add(normalized);
            }
        }
        return allowed.contains(normalizeAsSymbol ? normalizeSymbol(value) : value.trim().toLowerCase(Locale.ROOT));
    }

    private String normalizeSymbol(String raw) {
        if (!hasText(raw)) {
            return "";
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) {
            value = value.substring(colon + 1);
        }
        return value.replace("-", "").replace("/", "").replace("_", "");
    }

    private String normalizeInterval(String raw) {
        if (!hasText(raw)) {
            return "";
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if ("D".equals(value)) {
            return "1d";
        }
        if (value.endsWith("D") && value.length() > 1) {
            return value.substring(0, value.length() - 1).toLowerCase(Locale.ROOT) + "d";
        }
        if (value.endsWith("H") && value.length() > 1) {
            return value.substring(0, value.length() - 1).toLowerCase(Locale.ROOT) + "h";
        }
        if (value.matches("\\d+")) {
            long minutes = Long.parseLong(value);
            if (minutes >= 1440 && minutes % 1440 == 0) {
                return (minutes / 1440) + "d";
            }
            if (minutes >= 60 && minutes % 60 == 0) {
                return (minutes / 60) + "h";
            }
            return minutes + "m";
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private String normalizeSource(String raw) {
        return hasText(raw) ? raw.trim().toLowerCase(Locale.ROOT) : "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
