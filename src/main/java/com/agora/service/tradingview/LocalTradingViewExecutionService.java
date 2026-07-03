package com.agora.service.tradingview;

import com.agora.config.OkxTradingProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.model.BtDecisionAudit;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.TelegramService;
import com.agora.service.backtest.LiveSignalContext;
import com.agora.service.meta.DecisionAuditWriter;
import com.agora.service.trading.TradeResult;
import com.agora.service.trading.TradingService;
import com.agora.service.trading.TradingSignalSourcePolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Execution lane for LOCAL_TRADINGVIEW parity order intents.
 *
 * <p>Default rollout remains dry-run/fail-closed. Real orders require the local
 * TradingView signal source, live execution mode or equivalent legacy flags,
 * OKX trading enabled, private credentials configured, a scoped BTCUSDT/1d/long
 * opportunity, daily/open-position caps, and immediate OCO attachment after the
 * market buy.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LocalTradingViewExecutionService {

    private static final String EXECUTION_MODE = "LOCAL_TRADINGVIEW_PARITY_EXECUTION";
    private static final String EVIDENCE_EXECUTION_MODE = "LOCAL_TV_PARITY_EXEC";
    private static final String EVENT_TYPE = "LOCAL_TV_EXECUTION";
    private static final String SIDE = "LONG";

    private final TradingViewLocalSignalProperties props;
    private final DecisionAuditWriter auditWriter;
    private final BtLiveSignalRepository liveSignalRepository;
    private final BtDecisionAuditRepository decisionAuditRepository;
    private final RuntimeDecisionEvidenceRepository evidenceRepository;
    private final TradingService tradingService;
    private final OkxTradingProperties okxTradingProperties;
    private final TradingSignalSourcePolicy signalSourcePolicy;
    private final ObjectMapper objectMapper;
    private final TelegramService telegramService;

    @Transactional
    public void preview(BtStrategy strategy,
                        MdKline kline,
                        String interval,
                        String source,
                        LiveSignalContext.OrderIntent intent,
                        Map<String, Object> baseContext,
                        int intentIndex) {
        if (!props.effectiveExecutionEnabled() || strategy == null || kline == null || intent == null) {
            return;
        }

        String symbol = normalizeSymbol(kline.getSymbol());
        String normalizedInterval = normalizeInterval(interval);
        BigDecimal entry = kline.getClosePrice();
        BigDecimal notional = props.defaultNotionalUsdt().min(props.maxNotionalUsdt());
        BigDecimal tp = takeProfit(entry);
        BigDecimal sl = stopLoss(entry);
        Map<String, Object> context = executionContext(
                strategy, kline, normalizedInterval, source, intent, baseContext, intentIndex,
                entry, tp, sl, notional);

        String blocker = preExecutionBlocker(strategy, symbol, normalizedInterval, kline, intentIndex, entry, tp, sl, notional);
        if (blocker != null) {
            logBlocked(strategy, symbol, normalizedInterval, kline.getOpenTime(), blocker, context);
            return;
        }

        placeOrderAndAttachOco(strategy, symbol, normalizedInterval, kline, intent, context, entry, tp, sl, notional);
    }

    private String preExecutionBlocker(BtStrategy strategy,
                                       String symbol,
                                       String interval,
                                       MdKline kline,
                                       int intentIndex,
                                       BigDecimal entry,
                                       BigDecimal tp,
                                       BigDecimal sl,
                                       BigDecimal notional) {
        if (intentIndex > props.executionMaxOrdersPerBar()) {
            return "LocalTradingViewExecutionBarCap";
        }
        if (props.effectiveExecutionDryRun()) {
            return "LocalTradingViewExecutionDryRun";
        }
        if (!props.effectiveExecutionLiveOrderEnabled()) {
            return "LocalTradingViewLiveOrderNotEnabled";
        }
        if (!signalSourcePolicy.shouldRunLocalTradingViewEvaluator()) {
            return "LocalTradingViewPrimaryNotActive";
        }
        if (!allowed(symbol, props.allowedSymbols(), true) || !allowed(interval, props.allowedIntervals(), false)) {
            return "LocalTradingViewScopeNotAllowlisted";
        }
        if (!positive(entry) || !positive(tp) || !positive(sl) || tp.compareTo(entry) <= 0 || sl.compareTo(entry) >= 0) {
            return "LocalTradingViewInvalidOcoPlan";
        }
        if (!positive(notional) || notional.compareTo(props.maxNotionalUsdt()) > 0) {
            return "LocalTradingViewInvalidNotional";
        }
        if (!okxTradingProperties.isEnabled()) {
            return "OkxAutoTradeDisabled";
        }
        if (!okxTradingProperties.hasPrivateCredentials()) {
            return "OkxPrivateCredentialsMissing";
        }
        LocalDateTime dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
        if (liveSignalRepository.countByStrategyIdAndAutoTradedIsTrueAndCreatedAtAfter(strategy.getId(), dayStart)
                >= props.executionMaxOrdersPerDay()) {
            return "LocalTradingViewDailyCapReached";
        }
        long openSameStrategySymbol = liveSignalRepository.findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(strategy.getId())
                .stream()
                .filter(row -> symbol.equalsIgnoreCase(row.getSymbol()))
                .count();
        if (openSameStrategySymbol >= props.executionMaxOpenPositions()) {
            return "LocalTradingViewOpenPositionCapReached";
        }
        if (liveSignalRepository.existsByStrategyIdAndSymbolAndSideAndIntervalCodeAndExitTimeIsNull(
                strategy.getId(), symbol, SIDE, interval)) {
            return "LocalTradingViewOpenPositionExists";
        }
        if (liveSignalRepository.existsByStrategyIdAndSymbolAndIntervalCodeAndBarOpenTimeAndNotifiedAtIsNotNull(
                strategy.getId(), symbol, interval, kline.getOpenTime())) {
            return "LocalTradingViewDuplicateBar";
        }
        return null;
    }

    private void logBlocked(BtStrategy strategy,
                            String symbol,
                            String interval,
                            LocalDateTime barOpenTime,
                            String blocker,
                            Map<String, Object> context) {
        String status = switch (blocker) {
            case "LocalTradingViewExecutionDryRun" -> "WOULD_EXECUTE_DRY_RUN";
            case "LocalTradingViewExecutionBarCap" -> "BLOCKED_BAR_CAP";
            case "LocalTradingViewLiveOrderNotEnabled" -> "BLOCKED_LIVE_ORDER_FLAG";
            default -> "BLOCKED_HARD_GATE";
        };
        boolean wouldExecute = "LocalTradingViewExecutionDryRun".equals(blocker);
        String reason = reason(blocker);
        context.put("executionStatus", status);
        context.put("wouldExecute", wouldExecute);
        context.put("executionBlocker", blocker);
        context.put("executionReason", reason);
        context.put("orderSent", false);
        context.put("ocoAttached", false);

        auditWriter.logEntrySkip(strategy.getId(), symbol, interval, barOpenTime, blocker, reason, context);
    }

    private void placeOrderAndAttachOco(BtStrategy strategy,
                                        String symbol,
                                        String interval,
                                        MdKline kline,
                                        LiveSignalContext.OrderIntent intent,
                                        Map<String, Object> context,
                                        BigDecimal entry,
                                        BigDecimal tp,
                                        BigDecimal sl,
                                        BigDecimal notional) {
        context.put("executionStatus", "ORDER_PLACEMENT_STARTED");
        context.put("orderAttempted", true);
        context.put("wouldExecute", true);
        context.put("orderSent", false);
        context.put("ocoAttached", false);
        saveExecutionAudit(strategy, symbol, interval, kline.getOpenTime(), "INFO",
                "LocalTradingViewOrderPlacementStarted",
                "Local TradingView parity order placement started", context, null);

        TradeResult buy;
        try {
            buy = tradingService.placeMarketBuy(symbol, notional.setScale(2, RoundingMode.HALF_UP).doubleValue());
        } catch (Exception e) {
            context.put("executionStatus", "ORDER_FAILED");
            context.put("executionBlocker", "LocalTradingViewOrderFailed");
            context.put("executionReason", truncate(e.getMessage(), 420));
            BtDecisionAudit audit = saveExecutionAudit(strategy, symbol, interval, kline.getOpenTime(), "ERROR",
                    "LocalTradingViewOrderFailed", "OKX market buy failed: " + truncate(e.getMessage(), 420),
                    context, null);
            writeEvidence(audit, null, context, "ORDER_FAILED", false, false, null);
            return;
        }

        context.put("orderSent", true);
        context.put("orderId", buy.getOrderId());
        context.put("filledQty", buy.getQty());
        context.put("actualEntryPrice", buy.getAvgPrice());

        BtLiveSignal signal;
        try {
            signal = createLiveSignal(strategy, symbol, interval, kline, intent, entry, tp, sl, buy);
        } catch (Exception e) {
            context.put("executionStatus", "CRITICAL_ORDER_SENT_LIVE_SIGNAL_SAVE_FAILED");
            context.put("executionBlocker", "LocalTradingViewLiveSignalSaveFailed");
            context.put("executionReason", truncate(e.getMessage(), 420));
            BtDecisionAudit audit = saveExecutionAudit(strategy, symbol, interval, kline.getOpenTime(), "ERROR",
                    "LocalTradingViewLiveSignalSaveFailed",
                    "Market buy filled but bt_live_signal save failed: " + truncate(e.getMessage(), 420),
                    context, null);
            writeEvidence(audit, null, context, "CRITICAL_ORDER_SENT_LIVE_SIGNAL_SAVE_FAILED", true, false, null);
            sendCriticalAlert("CRITICAL_ORDER_SENT_LIVE_SIGNAL_SAVE_FAILED", symbol, buy, null, e);
            return;
        }

        context.put("liveSignalId", signal.getId());

        Long ocoAlgoId;
        try {
            ocoAlgoId = tradingService.placeOco(symbol, buy.getQty(), tp, sl);
            signal.setOcoOrderListId(ocoAlgoId);
            signal.setOcoQty(buy.getQty());
            liveSignalRepository.save(signal);
        } catch (Exception e) {
            signal.setFilterReason("LOCAL_TRADINGVIEW_OCO_ATTACH_FAILED: " + truncate(e.getMessage(), 420));
            liveSignalRepository.save(signal);
            context.put("executionStatus", "CRITICAL_UNPROTECTED_LOCAL_TRADINGVIEW");
            context.put("executionBlocker", "LocalTradingViewOcoAttachFailed");
            context.put("executionReason", truncate(e.getMessage(), 420));
            context.put("ocoAttached", false);
            BtDecisionAudit audit = saveExecutionAudit(strategy, symbol, interval, kline.getOpenTime(), "ERROR",
                    "LocalTradingViewOcoAttachFailed",
                    "Market buy filled but OCO attach failed: " + truncate(e.getMessage(), 420),
                    context, signal.getId());
            writeEvidence(audit, signal, context, "CRITICAL_UNPROTECTED_LOCAL_TRADINGVIEW", true, false, null);
            sendCriticalAlert("CRITICAL_UNPROTECTED_LOCAL_TRADINGVIEW", symbol, buy, signal.getId(), e);
            return;
        }

        context.put("executionStatus", "EXECUTED_OCO_ATTACHED");
        context.put("executionBlocker", "");
        context.put("executionReason", "Local TradingView parity order executed and OCO attached");
        context.put("ocoAttached", true);
        context.put("ocoAlgoId", ocoAlgoId);
        BtDecisionAudit audit = saveExecutionAudit(strategy, symbol, interval, kline.getOpenTime(), "PASS",
                "LocalTradingViewExecuted",
                "Local TradingView parity order executed and OCO attached",
                context, signal.getId());
        writeEvidence(audit, signal, context, "EXECUTED_OCO_ATTACHED", true, true, ocoAlgoId);
    }

    private void sendCriticalAlert(String status, String symbol, TradeResult buy, Long liveSignalId, Exception error) {
        String message = status + " order placed but LOCAL_TRADINGVIEW protection/audit failed. symbol="
                + symbol
                + " orderId=" + safe(buy == null ? null : buy.getOrderId())
                + " qty=" + safe(buy == null ? null : buy.getQty())
                + " liveSignalId=" + safe(liveSignalId)
                + " error=" + truncate(error == null ? "" : error.getMessage(), 420);
        try {
            telegramService.sendAlert(message, false, "LocalTradingViewExecution", "CRITICAL");
        } catch (Exception alertError) {
            log.error("[LocalTradingView] critical alert failed after order was sent. status={} symbol={} orderId={} err={}",
                    status, symbol, safe(buy == null ? null : buy.getOrderId()), alertError.getMessage(), alertError);
        }
    }

    private BtLiveSignal createLiveSignal(BtStrategy strategy,
                                          String symbol,
                                          String interval,
                                          MdKline kline,
                                          LiveSignalContext.OrderIntent intent,
                                          BigDecimal entry,
                                          BigDecimal tp,
                                          BigDecimal sl,
                                          TradeResult buy) {
        BtLiveSignal signal = new BtLiveSignal();
        signal.setStrategyId(strategy.getId());
        signal.setSymbol(symbol);
        signal.setIntervalCode(interval);
        signal.setBarOpenTime(kline.getOpenTime());
        signal.setEntryPrice(entry);
        signal.setSuggestedTp(tp);
        signal.setSuggestedSl(sl);
        signal.setScore(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        signal.setNnOutput(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        signal.setNotifiedAt(LocalDateTime.now(ZoneOffset.UTC));
        signal.setAutoTraded(true);
        signal.setExchangeOrderId("LOCAL_TV:" + buy.getOrderId());
        signal.setActualEntryPrice(buy.getAvgPrice());
        signal.setTradedQty(buy.getQty());
        signal.setOcoQty(buy.getQty());
        signal.setSide(SIDE);
        signal.setFilterReason("LOCAL_TRADINGVIEW_PARITY:" + truncate(intent.reason(), 220));
        return liveSignalRepository.save(signal);
    }

    private BtDecisionAudit saveExecutionAudit(BtStrategy strategy,
                                               String symbol,
                                               String interval,
                                               LocalDateTime barOpenTime,
                                               String outcome,
                                               String blocker,
                                               String reason,
                                               Map<String, Object> context,
                                               Long liveSignalId) {
        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setEventTime(LocalDateTime.now(ZoneOffset.UTC));
        audit.setStrategyId(strategy.getId());
        audit.setSymbol(symbol);
        audit.setIntervalCode(interval);
        audit.setBarOpenTime(barOpenTime);
        audit.setEventType(EVENT_TYPE);
        audit.setOutcome(outcome);
        audit.setBlocker(blocker);
        audit.setReason(truncate(reason, 500));
        audit.setContextJson(toJson(context));
        audit.setLiveSignalId(liveSignalId);
        return decisionAuditRepository.save(audit);
    }

    private void writeEvidence(BtDecisionAudit audit,
                               BtLiveSignal signal,
                               Map<String, Object> context,
                               String finalOutcome,
                               boolean orderSent,
                               boolean ocoAttached,
                               Long ocoAlgoId) {
        if (audit == null || audit.getId() == null) {
            return;
        }
        RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        evidence.setDecisionId(audit.getId());
        evidence.setEvidenceTime(LocalDateTime.now(ZoneOffset.UTC));
        evidence.setSymbol(audit.getSymbol());
        evidence.setSide(SIDE);
        evidence.setStrategyId(audit.getStrategyId());
        evidence.setIntervalCode(audit.getIntervalCode());
        evidence.setLiveSignalId(signal == null ? null : signal.getId());
        evidence.setSignalSource("LOCAL_TRADINGVIEW");
        evidence.setFeaturesSnapshotJson(toJson(context));
        evidence.setFreshnessState("PASS_LOCAL_TRADINGVIEW_PARITY_RECHECK");
        evidence.setSelectedAction("LOCAL_TRADINGVIEW_EXECUTE");
        evidence.setReason(finalOutcome);
        evidence.setPolicyMode("LOCAL_TRADINGVIEW_PARITY_MICRO_LIVE");
        evidence.setFinalOutcome(finalOutcome);
        evidence.setOrderSent(orderSent);
        evidence.setExecutionMode(EVIDENCE_EXECUTION_MODE);
        evidence.setSuppressionReason(orderSent ? null : String.valueOf(context.get("executionBlocker")));
        evidence.setOcoOrderListId(ocoAlgoId == null ? null : String.valueOf(ocoAlgoId));
        evidence.setExecutionPreviewJson(receipt(context, finalOutcome, orderSent, ocoAttached, ocoAlgoId));
        evidenceRepository.save(evidence);
    }

    private Map<String, Object> executionContext(BtStrategy strategy,
                                                 MdKline kline,
                                                 String interval,
                                                 String source,
                                                 LiveSignalContext.OrderIntent intent,
                                                 Map<String, Object> baseContext,
                                                 int intentIndex,
                                                 BigDecimal entry,
                                                 BigDecimal tp,
                                                 BigDecimal sl,
                                                 BigDecimal notional) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (baseContext != null) {
            context.putAll(baseContext);
        }
        context.put("executionMode", EXECUTION_MODE);
        context.put("executionEnabled", true);
        context.put("executionModeSetting", props.executionMode().name());
        context.put("executionDryRun", props.effectiveExecutionDryRun());
        context.put("executionLiveOrderEnabled", props.effectiveExecutionLiveOrderEnabled());
        context.put("legacyExecutionEnabled", props.executionEnabled());
        context.put("legacyExecutionDryRun", props.executionDryRun());
        context.put("legacyExecutionLiveOrderEnabled", props.executionLiveOrderEnabled());
        context.put("executionIntentIndex", intentIndex);
        context.put("executionMaxOrdersPerBar", props.executionMaxOrdersPerBar());
        context.put("executionMaxOrdersPerDay", props.executionMaxOrdersPerDay());
        context.put("executionMaxOpenPositions", props.executionMaxOpenPositions());
        context.put("executionWouldUseScoreBuySchedulers", false);
        context.put("signalSourcePolicyPrimary", signalSourcePolicy.primary());
        context.put("okxAutoTradeEnabled", okxTradingProperties.isEnabled());
        context.put("okxPrivateCredentialsConfigured", okxTradingProperties.hasPrivateCredentials());
        context.put("strategyId", strategy.getId());
        context.put("symbol", normalizeSymbol(kline.getSymbol()));
        context.put("timeframe", interval);
        context.put("klineSource", source == null ? "" : source);
        context.put("barTime", kline.getOpenTime() == null ? "" : kline.getOpenTime().toString());
        context.put("orderReason", intent.reason());
        context.put("orderLabel", intent.label());
        context.put("tradingViewQuantity", intent.quantity());
        context.put("effectiveNotionalUsdt", notional);
        context.put("executionEntryPrice", entry);
        context.put("executionTpPrice", tp);
        context.put("executionSlPrice", sl);
        context.put("executionTakeProfitPct", props.executionTakeProfitPct());
        context.put("executionStopLossPct", props.executionStopLossPct());
        context.put("orderSent", false);
        context.put("ocoAttached", false);
        return context;
    }

    private BigDecimal takeProfit(BigDecimal entry) {
        if (!positive(entry)) {
            return BigDecimal.ZERO;
        }
        return entry.multiply(BigDecimal.ONE.add(props.executionTakeProfitPct()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal stopLoss(BigDecimal entry) {
        if (!positive(entry)) {
            return BigDecimal.ZERO;
        }
        return entry.multiply(BigDecimal.ONE.subtract(props.executionStopLossPct()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private String receipt(Map<String, Object> context, String status, boolean orderSent, boolean ocoAttached, Long ocoAlgoId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("version", "local-tradingview-execution-v1");
        node.put("status", status);
        node.put("executionMode", EXECUTION_MODE);
        node.put("signalSource", "LOCAL_TRADINGVIEW");
        node.put("strategyId", String.valueOf(context.get("strategyId")));
        node.put("symbol", String.valueOf(context.get("symbol")));
        node.put("timeframe", String.valueOf(context.get("timeframe")));
        node.put("barTime", String.valueOf(context.get("barTime")));
        node.put("orderReason", String.valueOf(context.get("orderReason")));
        node.put("notionalUsdt", String.valueOf(context.get("effectiveNotionalUsdt")));
        node.put("entryPrice", String.valueOf(context.get("executionEntryPrice")));
        node.put("tp", String.valueOf(context.get("executionTpPrice")));
        node.put("sl", String.valueOf(context.get("executionSlPrice")));
        node.put("orderSent", orderSent);
        node.put("ocoAttached", ocoAttached);
        node.put("orderId", String.valueOf(context.getOrDefault("orderId", "")));
        node.put("ocoAlgoId", ocoAlgoId == null ? "" : String.valueOf(ocoAlgoId));
        node.put("scoreBuySchedulersUsed", false);
        node.put("mode", props.executionMode().name());
        node.put("dryRun", props.effectiveExecutionDryRun());
        node.put("liveOrderEnabled", props.effectiveExecutionLiveOrderEnabled());
        return node.toString();
    }

    private String reason(String blocker) {
        return switch (blocker) {
            case "LocalTradingViewExecutionBarCap" ->
                    "Local TradingView parity execution skipped by per-bar intent cap";
            case "LocalTradingViewExecutionDryRun" ->
                    "Local TradingView parity execution dry-run; no order sent";
            case "LocalTradingViewLiveOrderNotEnabled" ->
                    "Local TradingView live order flag is disabled; no order sent";
            case "LocalTradingViewPrimaryNotActive" ->
                    "TRADING_SIGNAL_SOURCE_PRIMARY is not LOCAL_TRADINGVIEW";
            case "LocalTradingViewScopeNotAllowlisted" ->
                    "Local TradingView execution scope is not allowlisted";
            case "LocalTradingViewInvalidOcoPlan" ->
                    "Local TradingView execution has invalid entry/tp/sl plan";
            case "LocalTradingViewInvalidNotional" ->
                    "Local TradingView execution notional is invalid";
            case "OkxAutoTradeDisabled" ->
                    "OKX auto-trade is disabled; no order sent";
            case "OkxPrivateCredentialsMissing" ->
                    "OKX private credentials are missing; no order sent";
            case "LocalTradingViewDailyCapReached" ->
                    "Local TradingView daily order cap reached";
            case "LocalTradingViewOpenPositionCapReached" ->
                    "Local TradingView open-position cap reached";
            case "LocalTradingViewOpenPositionExists" ->
                    "Local TradingView same strategy/symbol/interval position already open";
            case "LocalTradingViewDuplicateBar" ->
                    "Local TradingView duplicate bar already has a live signal";
            default -> blocker;
        };
    }

    private boolean allowed(String value, String csv, boolean normalizeAsSymbol) {
        if (csv == null || csv.isBlank()) {
            return true;
        }
        if (value == null || value.isBlank()) {
            return false;
        }
        Set<String> allowed = new java.util.HashSet<>();
        for (String token : csv.split(",")) {
            String normalized = normalizeAsSymbol ? normalizeSymbol(token) : token.trim().toLowerCase(Locale.ROOT);
            if (normalized != null && !normalized.isBlank()) {
                allowed.add(normalized);
            }
        }
        return allowed.contains(normalizeAsSymbol ? normalizeSymbol(value) : value.trim().toLowerCase(Locale.ROOT));
    }

    private String normalizeSymbol(String raw) {
        if (raw == null || raw.isBlank()) {
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
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private String toJson(Map<String, Object> context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
