package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import com.agora.config.properties.Strategy508TimeExitProperties;
import com.agora.model.BtDecisionAudit;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.BtStrategyService;
import com.agora.service.TelegramService;
import com.agora.service.backtest.BacktestEngine;
import com.agora.service.backtest.LiveSignalContext;
import com.agora.service.backtest.Strategy;
import com.agora.service.backtest.StrategyContext;
import com.agora.service.backtest.StrategyRegistry;
import com.agora.service.backtest.StrategySignal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.agora.service.trading.Strategy508TimeExitPolicy.HOLD_HOURS;
import static com.agora.service.trading.Strategy508TimeExitPolicy.INTERVAL;
import static com.agora.service.trading.Strategy508TimeExitPolicy.KLINE_SOURCE;
import static com.agora.service.trading.Strategy508TimeExitPolicy.MAX_ORDERS_PER_DAY;
import static com.agora.service.trading.Strategy508TimeExitPolicy.NOTIONAL_USDT;
import static com.agora.service.trading.Strategy508TimeExitPolicy.POLICY_MODE;
import static com.agora.service.trading.Strategy508TimeExitPolicy.STOP_LOSS_PCT;
import static com.agora.service.trading.Strategy508TimeExitPolicy.STRATEGY_ID;
import static com.agora.service.trading.Strategy508TimeExitPolicy.SYMBOL;
import static com.agora.service.trading.Strategy508TimeExitPolicy.TAKE_PROFIT_PCT;

/** Isolated strategy 508 4h shadow/live-micro lane. */
@Service
@RequiredArgsConstructor
@Slf4j
public class Strategy508TimeExitLaneService {

    static final String EVENT_TYPE = "STRATEGY_508_TIME_EXIT";

    private final Set<String> evaluatingBars = ConcurrentHashMap.newKeySet();

    private final Strategy508TimeExitProperties properties;
    private final BtStrategyService strategyService;
    private final StrategyRegistry strategyRegistry;
    private final BacktestEngine backtestEngine;
    private final MdKlineRepository klineRepository;
    private final BtDecisionAuditRepository decisionAuditRepository;
    private final RuntimeDecisionEvidenceRepository evidenceRepository;
    private final RuntimeDecisionEvidenceService runtimeEvidenceService;
    private final BtLiveSignalRepository liveSignalRepository;
    private final OkxTradingService okxTradingService;
    private final OkxTradingProperties okxTradingProperties;
    private final DailyLossGuard dailyLossGuard;
    private final EventRiskLevelEngine eventRiskLevelEngine;
    private final Strategy508TimeExitReadinessService readinessService;
    private final SpotPositionCloseService closeService;
    private final ObjectMapper objectMapper;
    private final TelegramService telegramService;

    public boolean isEnabled() {
        return properties.enabled();
    }

    @Transactional
    public void evaluate(MdKline eventKline) {
        if (!properties.enabled() || !matchesPolicyScope(eventKline)) return;
        String evaluationKey = eventKline.getSource() + "|" + eventKline.getSymbol() + "|"
                + eventKline.getIntervalCode() + "|" + eventKline.getOpenTime();
        if (!evaluatingBars.add(evaluationKey)) {
            log.debug("[508TimeExit] concurrent duplicate bar ignored: {}", evaluationKey);
            return;
        }
        try {
            BtStrategy strategyEntity = strategyService.getRequired(STRATEGY_ID);
            Strategy strategy = strategyRegistry.getRequiredStrategy(strategyEntity.getStrategyType());
            Map<String, Object> config = new LinkedHashMap<>(strategyService.parseConfig(strategyEntity.getConfigJson()));
            strategy.defaultExecutionConfig().forEach(config::putIfAbsent);
            config.put("runIntervalCode", INTERVAL);
            Strategy508TimeExitPolicy.applyMarketFeatureFreshnessPolicy(config);

            List<MdKline> bars = loadBars(eventKline);
            int index = indexOf(bars, eventKline.getOpenTime());
            if (index < 20) {
                auditEvaluation(strategyEntity, eventKline, "HOLD", "INSUFFICIENT_HISTORY", Map.of());
                return;
            }
            Map<String, double[]> indicators = backtestEngine.buildIndicators(bars, config);
            MdKline previous = bars.get(index - 1);
            LiveSignalContext.clear();
            StrategySignal signal;
            LiveSignalContext.Snapshot signalSnapshot;
            Map<String, Object> details;
            try {
                signal = strategy.evaluate(new StrategyContext(index, bars.get(index), previous, bars, indicators), config);
                signalSnapshot = LiveSignalContext.get();
                Map<String, Object> rawDetails = LiveSignalContext.getDetails();
                details = rawDetails == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rawDetails);
            } finally {
                LiveSignalContext.clear();
            }
            if (signal != StrategySignal.BUY) {
                auditEvaluation(strategyEntity, eventKline, "HOLD",
                        String.valueOf(details.getOrDefault("hold_reason", "NO_RAW_BUY")), details);
                return;
            }
            if (decisionAuditRepository.existsByStrategyIdAndSymbolAndIntervalCodeAndBarOpenTimeAndEventType(
                    STRATEGY_ID, SYMBOL, INTERVAL, eventKline.getOpenTime(), EVENT_TYPE)) {
                log.debug("[508TimeExit] duplicate bar ignored: {}", eventKline.getOpenTime());
                return;
            }

            Map<String, Object> context = baseContext(eventKline, details, config, signalSnapshot);
            List<String> hardBlockers = hardBlockers(strategyEntity, config, eventKline, context);
            context.put("hardBlockers", hardBlockers);
            context.put("hardGateClear", hardBlockers.isEmpty());
            addSoftGateObservations(context, hardBlockers);

            BtDecisionAudit audit = saveDecisionAudit(strategyEntity, eventKline, context,
                    hardBlockers.isEmpty() ? "PASS" : "BLOCKED");
            if (!runtimeEvidenceService.isEnabled()) {
                log.warn("[508TimeExit] runtime evidence disabled; shadow/live candidate dropped decisionId={}", audit.getId());
                return;
            }
            RuntimeDecisionEvidence evidence = createEvidence(audit, context, hardBlockers);

            if (!properties.liveMicroArmed()) {
                evidence.setSelectedAction(hardBlockers.isEmpty()
                        ? "STRATEGY_508_TIME_EXIT_SHADOW"
                        : "STRATEGY_508_TIME_EXIT_SHADOW_BLOCKED");
                evidence.setExecutionMode("SHADOW");
                evidence.setOrderSent(false);
                evidence.setSuppressionReason(properties.mode().name());
                if (!hardBlockers.isEmpty()) {
                    evidence.setFinalOutcome("HARD_BLOCKED");
                    evidence.setReason("SHADOW_HARD_GATE_BLOCKED");
                }
                evidenceRepository.save(evidence);
                return;
            }

            Strategy508TimeExitReadinessService.ReadinessSnapshot readiness = readinessService.snapshot(SYMBOL, true);
            if (!readiness.liveEntryReady()) {
                hardBlockers.addAll(readiness.blockers());
            }
            if (!hardBlockers.isEmpty()) {
                context.put("hardBlockers", hardBlockers.stream().distinct().toList());
                context.put("hardGateClear", false);
                evidence.setSelectedAction("STRATEGY_508_TIME_EXIT_LIVE_BLOCKED");
                evidence.setExecutionMode("LIVE_MICRO_BLOCKED");
                evidence.setTerminalBlocker(hardBlockers.get(0));
                evidence.setBlockerReason(String.join(",", hardBlockers));
                evidence.setSuppressionReason(hardBlockers.get(0));
                evidence.setPolicyInputsJson(toJson(context));
                evidence.setFeaturesSnapshotJson(toJson(context));
                evidenceRepository.save(evidence);
                return;
            }

            executeLive(strategyEntity, eventKline, context, evidence);
        } catch (Exception e) {
            log.error("[508TimeExit] evaluation failed bar={} error={}",
                    eventKline == null ? null : eventKline.getOpenTime(), e.getMessage(), e);
        } finally {
            evaluatingBars.remove(evaluationKey);
        }
    }

    private List<String> hardBlockers(BtStrategy strategy,
                                      Map<String, Object> config,
                                      MdKline kline,
                                      Map<String, Object> context) {
        List<String> blockers = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime close = kline.getCloseTime() != null ? kline.getCloseTime() : kline.getOpenTime().plusHours(4);
        long ageMinutes = Math.max(0, Duration.between(close, now).toMinutes());
        context.put("signalAgeMinutes", ageMinutes);
        if (ageMinutes > 15) blockers.add("DATA_FRESHNESS_STALE");

        EventRiskLevelEngine.Snapshot eventRisk = eventRiskLevelEngine.evaluate(SYMBOL);
        context.put("eventRiskLevel", eventRisk.level().name());
        context.put("eventRiskScore", eventRisk.score());
        if (eventRisk.level().atLeast(EventRiskLevelEngine.RiskLevel.R2)) blockers.add("EVENT_RISK_R2_OR_HIGHER");

        DailyLossGuard.GuardResult dailyLoss = dailyLossGuard.check();
        context.put("dailyLossAllowed", dailyLoss.allowed());
        context.put("dailyPnlUsdt", dailyLoss.todayPnl());
        if (!dailyLoss.allowed()) blockers.add("DAILY_LOSS_GUARD");

        long globalOpen = liveSignalRepository.countByAutoTradedIsTrueAndExitTimeIsNull();
        context.put("globalOpenPositions", globalOpen);
        context.put("globalMaxOpenPositions", okxTradingProperties.getMaxOpenPositions());
        if (globalOpen >= okxTradingProperties.getMaxOpenPositions()) blockers.add("GLOBAL_OPEN_POSITION_CAP");
        if (!okxTradingProperties.isAllowConcurrentOnSameSymbol()
                && liveSignalRepository.existsBySymbolAndAutoTradedIsTrueAndExitTimeIsNull(SYMBOL)) {
            blockers.add("GLOBAL_SAME_SYMBOL_EXPOSURE");
        }
        if (liveSignalRepository.findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(STRATEGY_ID).stream()
                .anyMatch(this::isPolicyPosition)) {
            blockers.add("EXPERIMENT_OPEN_POSITION_CAP");
        }
        LocalDateTime dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
        long policyOrdersToday = liveSignalRepository.findByStrategyIdAndCreatedAtAfter(STRATEGY_ID, dayStart).stream()
                .filter(row -> Boolean.TRUE.equals(row.getAutoTraded()))
                .filter(this::isPolicyPosition)
                .count();
        context.put("experimentOrdersToday", policyOrdersToday);
        if (policyOrdersToday >= MAX_ORDERS_PER_DAY) {
            blockers.add("EXPERIMENT_DAILY_ORDER_CAP");
        }
        if (liveSignalRepository.existsByStrategyIdAndSymbolAndIntervalCodeAndBarOpenTimeAndNotifiedAtIsNotNull(
                STRATEGY_ID, SYMBOL, INTERVAL, kline.getOpenTime())) {
            blockers.add("DUPLICATE_LIVE_SIGNAL_BAR");
        }
        if (!ocoHealthOk(context)) blockers.add("EXISTING_OCO_HEALTH_NOT_OK");
        if (!runtimeEvidenceService.isEnabled()) blockers.add("RUNTIME_EVIDENCE_DISABLED");
        if (!okxTradingProperties.isEnabled()) blockers.add("OKX_TRADING_DISABLED");
        if (!okxTradingProperties.hasPrivateCredentials()) blockers.add("OKX_PRIVATE_CREDENTIALS_MISSING");
        return blockers;
    }

    private boolean ocoHealthOk(Map<String, Object> context) {
        for (BtLiveSignal position : liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()) {
            if (position.getFilterReason() != null
                    && position.getFilterReason().startsWith("LOCAL_TRADINGVIEW_BTC_BASE:")) {
                continue;
            }
            if (position.getOcoOrderListId() == null) {
                context.put("ocoHealthReason", "OPEN_POSITION_WITHOUT_OCO:" + position.getId());
                return false;
            }
            try {
                JsonNode algo = okxTradingService.getAlgoOrder(position.getSymbol(), position.getOcoOrderListId());
                String state = algo.path("state").asText("");
                if (!("live".equals(state) || "effective".equals(state) || "pause".equals(state))) {
                    context.put("ocoHealthReason", "OCO_STATE_" + state + ":" + position.getId());
                    return false;
                }
                String childOrderId = algo.path("ordIdList").path(0).asText("");
                if (!childOrderId.isBlank()) {
                    JsonNode child = okxTradingService.querySpotOrderDetail(position.getSymbol(), childOrderId);
                    if ("filled".equalsIgnoreCase(child.path("state").asText())) {
                        context.put("ocoHealthReason", "OCO_CHILD_FILLED_DB_OPEN:" + position.getId());
                        return false;
                    }
                }
            } catch (Exception e) {
                context.put("ocoHealthReason", "OCO_QUERY_FAILED:" + position.getId());
                return false;
            }
        }
        context.put("ocoHealthReason", "OK");
        return true;
    }

    private void executeLive(BtStrategy strategy,
                             MdKline kline,
                             Map<String, Object> context,
                             RuntimeDecisionEvidence evidence) {
        TradeResult buy;
        try {
            buy = okxTradingService.placeMarketBuy(SYMBOL, NOTIONAL_USDT.doubleValue());
        } catch (Exception e) {
            String submittedOrderId = extractOrderId(e == null ? null : e.getMessage());
            if (submittedOrderId != null) {
                context.put("submittedOrderId", submittedOrderId);
                finishFailed(evidence, context, "CRITICAL_ORDER_SENT_FILL_UNCONFIRMED", e);
                sendCritical("Order accepted but fill confirmation failed; new entries are blocked",
                        submittedOrderId, null, e);
            } else {
                finishFailed(evidence, context, "ORDER_FAILED", e);
            }
            return;
        }
        context.put("orderId", buy.getOrderId());
        context.put("entryFeeUsdt", buy.getFeeUsdt());
        context.put("entryFeeCurrency", buy.getFeeCurrency());
        context.put("entryGrossQty", buy.getGrossQty());
        context.put("entryNetQty", buy.getQty());
        context.put("actualEntryPrice", buy.getAvgPrice());
        context.put("actualEntryTime", LocalDateTime.now(ZoneOffset.UTC).toString());
        context.put("scheduledExitAt", LocalDateTime.now(ZoneOffset.UTC).plusHours(HOLD_HOURS).toString());

        BigDecimal actualEntry = buy.getAvgPrice();
        BigDecimal tp = actualEntry.multiply(BigDecimal.ONE.add(TAKE_PROFIT_PCT)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sl = actualEntry.multiply(BigDecimal.ONE.subtract(STOP_LOSS_PCT)).setScale(2, RoundingMode.HALF_UP);
        BtLiveSignal signal = new BtLiveSignal();
        signal.setStrategyId(STRATEGY_ID);
        signal.setSymbol(SYMBOL);
        signal.setIntervalCode(INTERVAL);
        signal.setBarOpenTime(kline.getOpenTime());
        signal.setEntryPrice(kline.getClosePrice());
        signal.setActualEntryPrice(actualEntry);
        signal.setSuggestedTp(tp);
        signal.setSuggestedSl(sl);
        signal.setTradedQty(buy.getQty());
        signal.setOcoQty(buy.getQty());
        signal.setScore(BigDecimal.ZERO.setScale(4));
        signal.setNnOutput(BigDecimal.ZERO.setScale(4));
        signal.setSide("LONG");
        signal.setAutoTraded(true);
        signal.setNotifiedAt(LocalDateTime.now(ZoneOffset.UTC));
        signal.setExchangeOrderId(truncate("508_T24H:" + buy.getOrderId(), 50));
        signal.setFilterReason(POLICY_MODE);
        try {
            signal = liveSignalRepository.save(signal);
        } catch (Exception e) {
            TradeResult unwind = emergencyUnwindUntrackedBuy(buy, context);
            finishFailed(evidence, context, "CRITICAL_ORDER_SENT_SIGNAL_SAVE_FAILED", e);
            sendCritical("Order filled but signal save failed; emergency unwind="
                    + (unwind == null ? "FAILED" : unwind.getOrderId()), buy.getOrderId(), null, e);
            return;
        }

        Long ocoId;
        try {
            ocoId = okxTradingService.placeOco(SYMBOL, buy.getQty(), tp, sl);
            signal.setOcoOrderListId(ocoId);
            liveSignalRepository.save(signal);
        } catch (Exception e) {
            evidence.setLiveSignalId(signal.getId());
            SpotPositionCloseService.CloseResult unwind = closeService.closeAtMarket(
                    signal.getId(), "ENTRY_OCO_ATTACH_FAILED");
            context.put("emergencyUnwindStatus", unwind.status());
            context.put("emergencyUnwindReason", unwind.reason());
            context.put("emergencyUnwindSoldQty", unwind.soldQty());
            finishFailed(evidence, context, "CRITICAL_ORDER_SENT_OCO_ATTACH_FAILED", e);
            sendCritical("Order filled but OCO attach failed; emergency unwind=" + unwind.status(),
                    buy.getOrderId(), signal.getId(), e);
            return;
        }

        context.put("liveSignalId", signal.getId());
        context.put("ocoAlgoId", ocoId);
        context.put("orderSent", true);
        context.put("ocoAttached", true);
        evidence.setLiveSignalId(signal.getId());
        evidence.setSelectedAction("STRATEGY_508_TIME_EXIT_LIVE_EXECUTED");
        evidence.setExecutionMode("LIVE_MICRO");
        evidence.setOrderSent(true);
        evidence.setOcoOrderListId(String.valueOf(ocoId));
        evidence.setFinalOutcome("PENDING_24H");
        evidence.setPolicyInputsJson(toJson(context));
        evidence.setFeaturesSnapshotJson(toJson(context));
        evidence.setExecutionPreviewJson(toJson(Map.of(
                "orderSent", true,
                "ocoAttached", true,
                "liveSignalId", signal.getId(),
                "entryFeeUsdt", buy.getFeeUsdt() == null ? "UNKNOWN" : buy.getFeeUsdt())));
        evidenceRepository.save(evidence);
        sendLiveFill(signal, buy, ocoId);
    }

    private TradeResult emergencyUnwindUntrackedBuy(TradeResult buy, Map<String, Object> context) {
        if (buy == null || buy.getQty() == null || buy.getQty().signum() <= 0) return null;
        try {
            TradeResult unwind = okxTradingService.placeMarketSellWithFill(SYMBOL, buy.getQty());
            context.put("emergencyUnwindOrderId", unwind.getOrderId());
            context.put("emergencyUnwindQty", unwind.getGrossQty());
            context.put("emergencyUnwindFeeUsdt", unwind.getFeeUsdt());
            return unwind;
        } catch (Exception unwindError) {
            context.put("emergencyUnwindFailure", truncate(unwindError.getMessage(), 300));
            return null;
        }
    }

    private void sendLiveFill(BtLiveSignal signal, TradeResult buy, Long ocoId) {
        try {
            telegramService.sendAlert("Strategy 508 4H/24H live fill policy=" + POLICY_MODE
                            + " position=" + signal.getId() + " orderId=" + buy.getOrderId()
                            + " notionalUsdt=" + NOTIONAL_USDT + " entry=" + buy.getAvgPrice()
                            + " ocoAlgoId=" + ocoId,
                    false, "Strategy508TimeExit", "INFO");
        } catch (Exception e) {
            log.warn("[508TimeExit] live fill notification failed position={} error={}",
                    signal.getId(), e.getMessage());
        }
    }

    private RuntimeDecisionEvidence createEvidence(BtDecisionAudit audit,
                                                   Map<String, Object> context,
                                                   List<String> hardBlockers) {
        RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        evidence.setDecisionId(audit.getId());
        evidence.setEvidenceTime(LocalDateTime.now(ZoneOffset.UTC));
        evidence.setSymbol(SYMBOL);
        evidence.setSide("LONG");
        evidence.setStrategyId(STRATEGY_ID);
        evidence.setIntervalCode(INTERVAL);
        evidence.setSignalSource("STRATEGY_508_RAW_BUY_4H");
        evidence.setFeaturesSnapshotJson(toJson(context));
        evidence.setPolicyInputsJson(toJson(context));
        evidence.setFreshnessState(hardBlockers.contains("DATA_FRESHNESS_STALE") ? "STALE" : "CURRENT");
        evidence.setBlockerReason(hardBlockers.isEmpty() ? null : String.join(",", hardBlockers));
        evidence.setTerminalBlocker(hardBlockers.isEmpty() ? null : hardBlockers.get(0));
        evidence.setPolicyMode(POLICY_MODE);
        evidence.setPolicyReason("4H raw BUY, +6%/-12% OCO, 24H time exit");
        evidence.setSelectedAction("STRATEGY_508_TIME_EXIT_SHADOW");
        evidence.setReason("PENDING_24H_OUTCOME");
        evidence.setFinalOutcome("PENDING_24H");
        evidence.setOrderSent(false);
        evidence.setIntentCreated(true);
        evidence.setOcoPlanCreated(false);
        evidence.setExecutionMode("SHADOW");
        evidence.setEvResultJson(toJson(context.get("expectedValueGateObservation")));
        evidence.setTqsResultJson(toJson(context.get("tradeQualityObservation")));
        evidence.setWarningsJson(toJson(context.get("ensembleObservation")));
        return evidenceRepository.save(evidence);
    }

    private Map<String, Object> baseContext(MdKline kline,
                                            Map<String, Object> details,
                                            Map<String, Object> config,
                                            LiveSignalContext.Snapshot signalSnapshot) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("policyMode", POLICY_MODE);
        context.put("strategyId", STRATEGY_ID);
        context.put("symbol", SYMBOL);
        context.put("intervalCode", INTERVAL);
        context.put("source", KLINE_SOURCE);
        context.put("barOpenTime", kline.getOpenTime().toString());
        LocalDateTime decisionTime = kline.getCloseTime() != null
                ? kline.getCloseTime() : kline.getOpenTime().plusHours(4);
        context.put("decisionTime", decisionTime.toString());
        context.put("referenceEntryPrice", kline.getClosePrice());
        context.put("notionalUsdt", NOTIONAL_USDT);
        context.put("takeProfitPct", TAKE_PROFIT_PCT);
        context.put("stopLossPct", STOP_LOSS_PCT);
        context.put("holdHours", HOLD_HOURS);
        context.put("mode", properties.mode().name());
        context.put("liveOrderFlag", properties.liveOrderEnabled());
        context.put("expectedValueGateMode", "OBSERVE_ONLY");
        context.put("tradePlanQualityGateMode", "OBSERVE_ONLY");
        context.put("ensembleMode", "OBSERVE_ONLY");
        double pWin = signalSnapshot != null
                ? signalSnapshot.nnOutput : configDouble(config, "buyThreshold", 0.55);
        pWin = Math.max(0.0, Math.min(1.0, pWin));
        double riskReward = TAKE_PROFIT_PCT.divide(STOP_LOSS_PCT, 8, RoundingMode.HALF_UP).doubleValue();
        double expectedR = pWin * riskReward - (1.0 - pWin);
        double minimumExpectedR = configDouble(config, "preTradeMinExpectedR", 0.20);
        boolean gateEnabled = configBoolean(config, "preTradeExpectedValueGateEnabled", true);
        boolean wouldBlock = gateEnabled && (expectedR <= 0 || expectedR < minimumExpectedR);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("mode", "OBSERVE_ONLY");
        ev.put("gateEnabled", gateEnabled);
        ev.put("pWin", pWin);
        ev.put("riskReward", riskReward);
        ev.put("expectedR", expectedR);
        ev.put("minimumExpectedR", minimumExpectedR);
        ev.put("wouldBlock", wouldBlock);
        ev.put("blocksEntry", false);
        context.put("expectedValueGateObservation", ev);
        context.put("entryParityGap", false);
        context.put("exitParityGap", false);
        if (details != null) details.forEach((key, value) -> context.put("strategyDecision." + key, value));
        return context;
    }

    private void addSoftGateObservations(Map<String, Object> context, List<String> hardBlockers) {
        @SuppressWarnings("unchecked")
        Map<String, Object> ev = (Map<String, Object>) context.get("expectedValueGateObservation");
        Map<String, Object> tqsInputs = new LinkedHashMap<>();
        tqsInputs.put("candidateContinuedToEv", ev != null && !Boolean.TRUE.equals(ev.get("wouldBlock")));
        tqsInputs.put("ocoCapable", true);
        tqsInputs.put("score", context.get("strategyDecision.score"));
        tqsInputs.put("nnOutput", context.get("strategyDecision.nn_output"));
        Map<String, Object> tqs = new LinkedHashMap<>(TradeQualityEngine.scoreJsonV0(
                tqsInputs, hardBlockers.isEmpty() ? "NONE" : hardBlockers.get(0)));
        tqs.put("mode", "OBSERVE_ONLY");
        tqs.put("wouldBlock", "BLOCK".equals(String.valueOf(tqs.get("tqsBand"))));
        tqs.put("blocksEntry", false);
        context.put("tradeQualityObservation", tqs);

        Map<String, Object> ensemble = new LinkedHashMap<>();
        ensemble.put("mode", "OBSERVE_ONLY");
        ensemble.put("status", "NOT_EVALUATED_ISOLATED_RAW_SIGNAL_LANE");
        ensemble.put("wouldBlock", false);
        ensemble.put("blocksEntry", false);
        context.put("ensembleObservation", ensemble);
    }

    private double configDouble(Map<String, Object> config, String key, double fallback) {
        Object value = config == null ? null : config.get(key);
        if (value instanceof Number number) return number.doubleValue();
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private boolean configBoolean(Map<String, Object> config, String key, boolean fallback) {
        Object value = config == null ? null : config.get(key);
        if (value instanceof Boolean bool) return bool;
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private BtDecisionAudit saveDecisionAudit(BtStrategy strategy,
                                              MdKline kline,
                                              Map<String, Object> context,
                                              String outcome) {
        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setEventTime(LocalDateTime.now(ZoneOffset.UTC));
        audit.setStrategyId(strategy.getId());
        audit.setSymbol(SYMBOL);
        audit.setIntervalCode(INTERVAL);
        audit.setBarOpenTime(kline.getOpenTime());
        audit.setEventType(EVENT_TYPE);
        audit.setOutcome(outcome);
        audit.setReason("RAW_BUY_4H_TIME_EXIT_CANDIDATE");
        audit.setContextJson(toJson(context));
        return decisionAuditRepository.save(audit);
    }

    private void auditEvaluation(BtStrategy strategy,
                                 MdKline kline,
                                 String decision,
                                 String reason,
                                 Map<String, Object> details) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("policyMode", POLICY_MODE);
        context.put("decision", decision);
        context.put("reason", reason);
        context.put("symbol", SYMBOL);
        context.put("intervalCode", INTERVAL);
        context.put("source", KLINE_SOURCE);
        context.put("barOpenTime", kline.getOpenTime().toString());
        context.put("decisionTime", (kline.getCloseTime() != null
                ? kline.getCloseTime() : kline.getOpenTime().plusHours(4)).toString());
        context.put("orderSent", false);
        if (details != null) details.forEach((key, value) -> context.put("strategyDecision." + key, value));
        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setEventTime(LocalDateTime.now(ZoneOffset.UTC));
        audit.setStrategyId(strategy.getId());
        audit.setSymbol(SYMBOL);
        audit.setIntervalCode(INTERVAL);
        audit.setBarOpenTime(kline.getOpenTime());
        audit.setEventType(EVENT_TYPE + "_EVAL");
        audit.setOutcome("INFO");
        audit.setReason(reason);
        audit.setContextJson(toJson(context));
        decisionAuditRepository.save(audit);
    }

    private void finishFailed(RuntimeDecisionEvidence evidence,
                              Map<String, Object> context,
                              String outcome,
                              Exception error) {
        context.put("executionFailure", outcome);
        context.put("executionError", truncate(error == null ? null : error.getMessage(), 300));
        evidence.setSelectedAction("STRATEGY_508_TIME_EXIT_LIVE_FAILED");
        evidence.setExecutionMode("LIVE_MICRO_FAILED");
        evidence.setFinalOutcome(outcome);
        evidence.setReason(outcome);
        evidence.setOrderSent(outcome.contains("ORDER_SENT"));
        evidence.setTerminalBlocker(outcome);
        evidence.setPolicyInputsJson(toJson(context));
        evidence.setFeaturesSnapshotJson(toJson(context));
        evidenceRepository.save(evidence);
    }

    private void sendCritical(String status, String orderId, Long liveSignalId, Exception error) {
        try {
            telegramService.sendAlert(status + " policy=" + POLICY_MODE + " orderId=" + orderId
                    + " liveSignalId=" + liveSignalId + " error="
                    + truncate(error == null ? "" : error.getMessage(), 300),
                    false, "Strategy508TimeExit", "CRITICAL");
        } catch (Exception alertError) {
            log.error("[508TimeExit] critical alert failed: {}", alertError.getMessage());
        }
    }

    private List<MdKline> loadBars(MdKline eventKline) {
        List<MdKline> rows = klineRepository.findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(
                SYMBOL, INTERVAL, KLINE_SOURCE, PageRequest.of(0, 320));
        List<MdKline> bars = new ArrayList<>(rows == null ? List.of() : rows);
        if (indexOf(bars, eventKline.getOpenTime()) < 0) bars.add(eventKline);
        bars.sort(Comparator.comparing(MdKline::getOpenTime));
        return bars;
    }

    private boolean matchesPolicyScope(MdKline kline) {
        return kline != null
                && SYMBOL.equalsIgnoreCase(normalizeSymbol(kline.getSymbol()))
                && INTERVAL.equalsIgnoreCase(kline.getIntervalCode())
                && KLINE_SOURCE.equalsIgnoreCase(kline.getSource());
    }

    private boolean isPolicyPosition(BtLiveSignal position) {
        return position.getFilterReason() != null && position.getFilterReason().contains(POLICY_MODE);
    }

    private int indexOf(List<MdKline> bars, LocalDateTime openTime) {
        if (openTime == null) return -1;
        for (int i = 0; i < bars.size(); i++) {
            if (openTime.equals(bars.get(i).getOpenTime())) return i;
        }
        return -1;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) return "";
        return symbol.toUpperCase().replace("-", "").replace("/", "").replace("_", "");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private String extractOrderId(String message) {
        if (message == null) return null;
        int start = message.indexOf("ordId=");
        if (start < 0) return null;
        start += "ordId=".length();
        int end = start;
        while (end < message.length() && !Character.isWhitespace(message.charAt(end))) end++;
        String orderId = message.substring(start, end).replaceAll("[^A-Za-z0-9_-]", "");
        return orderId.isBlank() ? null : truncate(orderId, 80);
    }
}
