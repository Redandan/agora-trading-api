package com.agora.service.tradingview;

import com.agora.config.properties.TradingViewLocalSignalProperties;
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
import com.agora.service.meta.DecisionAuditWriter;
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
 * intent. Exchange orders are delegated to {@link LocalTradingViewExecutionService}
 * and remain disabled unless that service's execution mode and hard gates allow
 * the local TradingView lane to trade.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalTradingViewSignalEvaluator {

    private static final String SOURCE = "LOCAL_TRADINGVIEW_PARITY";
    private static final String BLOCKER = "LocalTradingViewDryRun";

    private final TradingViewLocalSignalProperties props;
    private final BtStrategyService strategyService;
    private final StrategyRegistry strategyRegistry;
    private final BacktestEngine backtestEngine;
    private final MdKlineRepository klineRepository;
    private final DecisionAuditWriter auditWriter;
    private final LocalTradingViewExecutionService executionService;
    private final Map<String, Instant> seenKeys = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return props.enabled();
    }

    public void evaluate(MdKline eventKline) {
        if (!props.enabled() || eventKline == null) {
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

        try {
            BtStrategy strategyEntity = strategyService.getRequired(props.strategyId());
            Strategy strategy = strategyRegistry.getRequiredStrategy(strategyEntity.getStrategyType());
            Map<String, Object> config = new LinkedHashMap<>(strategyService.parseConfig(strategyEntity.getConfigJson()));
            strategy.defaultExecutionConfig().forEach(config::putIfAbsent);
            config.put("runIntervalCode", interval);
            config.put("tradingViewParityMode", true);

            List<MdKline> klines = loadKlines(eventKline, symbol, interval, source);
            int index = indexOf(klines, eventKline.getOpenTime());
            if (index < 0) {
                log.debug("[LocalTradingView] skip {}@{} openTime={} source={} because event bar is not in history",
                        symbol, interval, eventKline.getOpenTime(), source);
                return;
            }

            Map<String, double[]> indicators = backtestEngine.buildIndicators(klines, config);
            int catchUpBars = Math.max(1, props.catchUpBars());
            int startIndex = Math.max(0, index - catchUpBars + 1);
            for (int evalIndex = startIndex; evalIndex <= index; evalIndex++) {
                evaluateBar(strategyEntity, strategy, config, klines, indicators, evalIndex,
                        interval, source, eventKline.getOpenTime(), catchUpBars);
            }
        } catch (Exception e) {
            log.warn("[LocalTradingView] evaluate failed symbol={} interval={} source={} openTime={} err={}",
                    symbol, interval, source, eventKline.getOpenTime(), e.getMessage());
        }
    }

    private void evaluateBar(BtStrategy strategyEntity,
                             Strategy strategy,
                             Map<String, Object> config,
                             List<MdKline> klines,
                             Map<String, double[]> indicators,
                             int index,
                             String interval,
                             String source,
                             LocalDateTime triggerOpenTime,
                             int catchUpBars) {
        LiveSignalContext.clear();
        try {
            MdKline previous = index > 0 ? klines.get(index - 1) : null;
            StrategyContext context = new StrategyContext(index, klines.get(index), previous, klines, indicators);
            StrategySignal signal = strategy.evaluate(context, config);
            List<LiveSignalContext.OrderIntent> intents = LiveSignalContext.getOrderIntents();
            if (signal != StrategySignal.BUY || intents.isEmpty()) {
                return;
            }
            Map<String, Object> details = LiveSignalContext.getDetails();
            int intentIndex = 0;
            for (LiveSignalContext.OrderIntent intent : intents) {
                intentIndex++;
                auditIntent(strategyEntity, klines.get(index), interval, source, intent, details, intentIndex,
                        triggerOpenTime, catchUpBars);
            }
        } catch (Exception e) {
            MdKline kline = klines.get(index);
            log.warn("[LocalTradingView] evaluate bar failed symbol={} interval={} source={} barTime={} triggerBar={} err={}",
                    kline.getSymbol(), interval, source, kline.getOpenTime(), triggerOpenTime, e.toString());
        } finally {
            LiveSignalContext.clear();
        }
    }

    private List<MdKline> loadKlines(MdKline eventKline, String symbol, String interval, String source) {
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

    private void auditIntent(BtStrategy strategy, MdKline kline, String interval, String source,
                             LiveSignalContext.OrderIntent intent, Map<String, Object> details, int intentIndex,
                             LocalDateTime triggerOpenTime, int catchUpBars) {
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

        BigDecimal requested = props.defaultNotionalUsdt();
        BigDecimal effective = requested.min(props.maxNotionalUsdt());
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("source", SOURCE);
        context.put("signalSource", "LOCAL_TRADINGVIEW");
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
        context.put("effectiveNotionalUsdt", effective);
        context.put("dryRun", true);
        context.put("orderSent", false);
        context.put("duplicate", false);
        context.put("blockers", "LOCAL_TRADINGVIEW_DRY_RUN");
        context.put("executionModeSetting", props.executionMode().name());
        context.put("executionEnabled", props.effectiveExecutionEnabled());
        context.put("executionDryRun", props.effectiveExecutionDryRun());
        context.put("executionLiveOrderEnabled", props.effectiveExecutionLiveOrderEnabled());
        if (details != null) {
            details.forEach((name, value) -> context.put("strategyDecision." + name, value));
        }

        auditWriter.logSignalEval(strategy.getId(), normalizeSymbol(kline.getSymbol()), interval,
                kline.getOpenTime(), "BUY", context);
        auditWriter.logEntrySkip(strategy.getId(), normalizeSymbol(kline.getSymbol()), interval,
                kline.getOpenTime(), BLOCKER, "Local TradingView parity dry-run; no order sent", context);
        executionService.preview(strategy, kline, interval, source, intent, context, intentIndex);
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
