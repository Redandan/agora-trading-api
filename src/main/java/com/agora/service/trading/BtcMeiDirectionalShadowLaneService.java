package com.agora.service.trading;

import com.agora.config.properties.BtcMeiDirectionalShadowProperties;
import com.agora.model.BtDecisionAudit;
import com.agora.model.MdKline;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.strategy.StrategyLifecycleMode;
import com.agora.service.strategy.StrategyRuntimeCatalog;
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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ADVERSE_SLIPPAGE_RATE_PER_SIDE;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.BASE_NOTIONAL_USDT;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.EMA_PERIOD_HOURS;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ENTROPY_24H;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ENTROPY_24H_WEIGHT;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ENTROPY_48H;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ENTROPY_48H_WEIGHT;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ENTROPY_72H;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ENTROPY_72H_WEIGHT;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ENTROPY_BINS;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ENTRY_ENTROPY_THRESHOLD;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.EVIDENCE_SCHEMA_VERSION;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.FEE_RATE_PER_SIDE;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.INTERVAL;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.MAX_CATCH_UP_BARS;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.MAX_OPEN_COST_USDT;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.MIN_REALIZED_NET_PROFIT;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.MOMENTUM_LOOKBACK_HOURS;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.NET_PROFIT_TRIGGER;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.POLICY_MODE;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.REQUIRED_CLOSE_POINTS;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.SOURCE;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.SYMBOL;

/**
 * Source-pinned, evidence-only runtime lane for the MEI directional candidate.
 *
 * <p>This class deliberately has no exchange, OCO, Grid, fund, or notification
 * dependency. Its only writes are decision audit and runtime evidence rows.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BtcMeiDirectionalShadowLaneService {

    static final String EVENT_TYPE = "BTC_MEI_DIRECTIONAL_SHADOW";
    static final String BLOCK_EVENT_TYPE = "BTC_MEI_DIRECTIONAL_BLOCKED";
    private static final String SIGNAL_SOURCE = "OKX_CLOSED_1H_MEI_DIRECTIONAL";
    private static final int STATE_RESTORE_SCAN_LIMIT = 50;

    private final Set<String> evaluatingBars = ConcurrentHashMap.newKeySet();

    private final BtcMeiDirectionalShadowProperties properties;
    private final MdKlineRepository klineRepository;
    private final BtDecisionAuditRepository decisionAuditRepository;
    private final RuntimeDecisionEvidenceRepository evidenceRepository;
    private final RuntimeDecisionEvidenceService runtimeEvidenceService;
    private final BtcMeiDirectionalShadowEngine engine;
    private final ObjectMapper objectMapper;
    private final StrategyRuntimeCatalog strategyRuntimeCatalog;

    public boolean isEnabled() {
        return properties.enabled()
                && strategyRuntimeCatalog.isMode(POLICY_MODE, StrategyLifecycleMode.SHADOW);
    }

    @Transactional
    public void evaluate(MdKline eventKline) {
        if (!isEnabled() || !matchesScope(eventKline)) return;
        if (!runtimeEvidenceService.isEnabled()) {
            log.warn("[BtcMeiDirectionalShadow] runtime evidence disabled; bar ignored openTime={}",
                    eventKline.getOpenTime());
            return;
        }
        String key = SOURCE + "|" + SYMBOL + "|" + INTERVAL + "|" + eventKline.getOpenTime();
        if (!evaluatingBars.add(key)) {
            log.debug("[BtcMeiDirectionalShadow] concurrent duplicate ignored: {}", key);
            return;
        }
        try {
            evaluateLocked(eventKline);
        } finally {
            evaluatingBars.remove(key);
        }
    }

    private void evaluateLocked(MdKline eventKline) {
        if (eventKline.getOpenTime() == null) return;
        if (decisionAuditRepository.existsBySymbolAndIntervalCodeAndBarOpenTimeAndEventType(
                SYMBOL, INTERVAL, eventKline.getOpenTime(), EVENT_TYPE)) {
            log.debug("[BtcMeiDirectionalShadow] persisted duplicate ignored openTime={}",
                    eventKline.getOpenTime());
            return;
        }
        LocalDateTime expectedClose = eventKline.getOpenTime().plusHours(1);
        if (eventKline.getCloseTime() == null
                || !expectedClose.equals(eventKline.getCloseTime())
                || expectedClose.isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1))) {
            persistBlocked(eventKline, null, "EVENT_NOT_PROVEN_CLOSED", false, false, 0);
            return;
        }

        RestoredState restored = restoreLatestState();
        BtcMeiDirectionalShadowEngine.State state =
                restored == null ? null : restored.state();
        if (state != null && state.lastProcessedBarOpenTime() != null
                && !state.lastProcessedBarOpenTime().isBefore(eventKline.getOpenTime())) {
            return;
        }
        if (state == null) {
            bootstrap(eventKline, restored == null ? 0 : restored.invalidRowsScanned());
            return;
        }
        catchUp(state, eventKline, restored.invalidRowsScanned());
    }

    private void bootstrap(MdKline eventKline, int invalidStateRows) {
        LocalDateTime start = eventKline.getOpenTime().minusHours(REQUIRED_CLOSE_POINTS - 1L);
        List<MdKline> bars = klineRepository
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        SYMBOL, INTERVAL, SOURCE, start, eventKline.getOpenTime());
        if (bars == null
                || bars.size() != REQUIRED_CLOSE_POINTS
                || !start.equals(bars.get(0).getOpenTime())
                || !eventKline.getOpenTime().equals(bars.get(bars.size() - 1).getOpenTime())) {
            persistBlocked(eventKline, null, "BOOTSTRAP_HISTORY_INCOMPLETE",
                    true, false, invalidStateRows);
            return;
        }

        BtcMeiDirectionalShadowEngine.State state = engine.initialState();
        BtcMeiDirectionalShadowEngine.StepResult current = null;
        try {
            for (MdKline bar : bars) {
                current = engine.step(state, bar);
                state = current.state();
            }
        } catch (BtcMeiDirectionalShadowEngine.DataQualityException e) {
            persistBlocked(eventKline, null, "BOOTSTRAP_" + safeCode(e.getMessage()),
                    true, false, invalidStateRows);
            return;
        }
        if (current == null) {
            persistBlocked(eventKline, null, "BOOTSTRAP_NO_CURRENT_STEP",
                    true, false, invalidStateRows);
            return;
        }
        persistObserved(eventKline, current, true, false,
                bars.size(), invalidStateRows);
    }

    private void catchUp(BtcMeiDirectionalShadowEngine.State initial,
                         MdKline eventKline,
                         int invalidStateRows) {
        LocalDateTime next = initial.lastProcessedBarOpenTime().plusHours(1);
        long expected = Duration.between(next, eventKline.getOpenTime()).toHours() + 1;
        if (expected <= 0) return;
        if (expected > MAX_CATCH_UP_BARS) {
            persistBlocked(eventKline, initial, "CATCH_UP_LIMIT_EXCEEDED",
                    false, true, invalidStateRows);
            return;
        }
        List<MdKline> bars = klineRepository
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        SYMBOL, INTERVAL, SOURCE, next, eventKline.getOpenTime());
        if (bars == null
                || bars.size() != expected
                || !next.equals(bars.get(0).getOpenTime())
                || !eventKline.getOpenTime().equals(bars.get(bars.size() - 1).getOpenTime())) {
            persistBlocked(eventKline, initial, "CATCH_UP_HISTORY_INCOMPLETE",
                    false, true, invalidStateRows);
            return;
        }

        BtcMeiDirectionalShadowEngine.State state = initial;
        for (int i = 0; i < bars.size(); i++) {
            MdKline bar = bars.get(i);
            if (decisionAuditRepository.existsBySymbolAndIntervalCodeAndBarOpenTimeAndEventType(
                    SYMBOL, INTERVAL, bar.getOpenTime(), EVENT_TYPE)) {
                persistBlocked(eventKline, state, "AUDIT_STATE_DIVERGENCE",
                        false, true, invalidStateRows);
                return;
            }
            BtcMeiDirectionalShadowEngine.StepResult step;
            try {
                step = engine.step(state, bar);
            } catch (BtcMeiDirectionalShadowEngine.DataQualityException e) {
                persistBlocked(bar, state, "CATCH_UP_" + safeCode(e.getMessage()),
                        false, true, invalidStateRows);
                return;
            }
            state = step.state();
            persistObserved(bar, step, false, i < bars.size() - 1,
                    bars.size(), invalidStateRows);
        }
    }

    private void persistObserved(MdKline bar,
                                 BtcMeiDirectionalShadowEngine.StepResult step,
                                 boolean bootstrap,
                                 boolean catchUp,
                                 int batchBars,
                                 int invalidStateRows) {
        List<BtcMeiDirectionalShadowEngine.RuntimeEvent> events = step.events();
        String selectedAction = selectedAction(events);
        String stateHash = engine.stateSha256(step.state());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        Map<String, Object> auditContext = new LinkedHashMap<>();
        auditContext.put("policyMode", POLICY_MODE);
        auditContext.put("source", SOURCE);
        auditContext.put("score", step.signal().score());
        auditContext.put("eligible", step.signal().eligible());
        auditContext.put("selectedAction", selectedAction);
        auditContext.put("stateAfterSha256", stateHash);
        auditContext.put("orderSent", false);
        auditContext.put("liveImplementationPresent", false);
        BtDecisionAudit audit = saveAudit(
                bar,
                now,
                EVENT_TYPE,
                events.isEmpty() ? "INFO" : "PASS",
                null,
                selectedAction,
                auditContext);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("evidenceSchemaVersion", EVIDENCE_SCHEMA_VERSION);
        snapshot.put("policyMode", POLICY_MODE);
        snapshot.put("symbol", SYMBOL);
        snapshot.put("intervalCode", INTERVAL);
        snapshot.put("source", SOURCE);
        snapshot.put("barOpenTime", formatTime(bar.getOpenTime()));
        snapshot.put("barCloseTime", formatTime(bar.getCloseTime()));
        snapshot.put("bootstrap", bootstrap);
        snapshot.put("catchUp", catchUp);
        snapshot.put("batchBars", batchBars);
        snapshot.put("invalidStateRowsScanned", invalidStateRows);
        snapshot.put("timingCausal", true);
        snapshot.put("hourlyLatticeComplete", true);
        snapshot.put("feeModelComplete", true);
        snapshot.put("slippageModelComplete", true);
        snapshot.put("signal", step.signal());
        snapshot.put("stateAfterSha256", stateHash);
        snapshot.put("stateAfter", step.state());
        snapshot.put("events", events);
        snapshot.put("orderSent", false);
        snapshot.put("ocoModified", false);
        snapshot.put("gridModified", false);
        snapshot.put("telegramSent", false);
        snapshot.put("liveImplementationPresent", false);

        RuntimeDecisionEvidence evidence = baseEvidence(audit, now);
        evidence.setFeaturesSnapshotJson(toJson(snapshot));
        evidence.setFreshnessState(catchUp
                ? "CAUSAL_CATCH_UP_COMPLETE"
                : "CURRENT_CLOSED_BAR");
        evidence.setSelectedAction(selectedAction);
        evidence.setReason(eventReason(events, step.signal()));
        evidence.setFinalOutcome("SHADOW_OBSERVED");
        evidence.setScore(step.signal().score());
        evidence.setThreshold(ENTRY_ENTROPY_THRESHOLD);
        evidence.setDecision(decision(events));
        evidence.setIntentCreated(events.stream().anyMatch(this::isSignalEvent));
        evidence.setPolicyInputsJson(policyInputsJson(stateHash));
        evidence.setExposureSnapshotJson(exposureSnapshotJson(step.state()));
        evidence.setExecutionPreviewJson(toJson(Map.of(
                "events", events,
                "virtualExecutionOnly", true,
                "orderSent", false,
                "liveImplementationPresent", false)));
        evidenceRepository.save(evidence);
    }

    private void persistBlocked(MdKline bar,
                                BtcMeiDirectionalShadowEngine.State state,
                                String blocker,
                                boolean bootstrap,
                                boolean catchUp,
                                int invalidStateRows) {
        if (bar == null || bar.getOpenTime() == null) return;
        if (decisionAuditRepository.existsBySymbolAndIntervalCodeAndBarOpenTimeAndEventType(
                SYMBOL, INTERVAL, bar.getOpenTime(), BLOCK_EVENT_TYPE)) return;
        String safeBlocker = safeCode(blocker);
        String stateHash = state == null ? null : engine.stateSha256(state);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        Map<String, Object> auditContext = new LinkedHashMap<>();
        auditContext.put("policyMode", POLICY_MODE);
        auditContext.put("source", SOURCE);
        auditContext.put("terminalBlocker", safeBlocker);
        auditContext.put("stateAfterSha256", stateHash);
        auditContext.put("orderSent", false);
        auditContext.put("liveImplementationPresent", false);
        BtDecisionAudit audit = saveAudit(
                bar,
                now,
                BLOCK_EVENT_TYPE,
                "BLOCKED",
                safeBlocker,
                "MEI_DIRECTIONAL_SHADOW_BLOCKED",
                auditContext);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("evidenceSchemaVersion", EVIDENCE_SCHEMA_VERSION);
        snapshot.put("policyMode", POLICY_MODE);
        snapshot.put("barOpenTime", formatTime(bar.getOpenTime()));
        snapshot.put("barCloseTime", formatTime(bar.getCloseTime()));
        snapshot.put("bootstrap", bootstrap);
        snapshot.put("catchUp", catchUp);
        snapshot.put("invalidStateRowsScanned", invalidStateRows);
        snapshot.put("terminalBlocker", safeBlocker);
        snapshot.put("timingCausal", false);
        snapshot.put("hourlyLatticeComplete", false);
        snapshot.put("stateAfterSha256", stateHash);
        if (state != null) snapshot.put("stateAfter", state);
        snapshot.put("events", List.of());
        snapshot.put("orderSent", false);
        snapshot.put("ocoModified", false);
        snapshot.put("gridModified", false);
        snapshot.put("telegramSent", false);
        snapshot.put("liveImplementationPresent", false);

        RuntimeDecisionEvidence evidence = baseEvidence(audit, now);
        evidence.setFeaturesSnapshotJson(toJson(snapshot));
        evidence.setFreshnessState("INCOMPLETE_FAIL_CLOSED");
        evidence.setSelectedAction("MEI_DIRECTIONAL_SHADOW_BLOCKED");
        evidence.setReason(safeBlocker);
        evidence.setFinalOutcome("BLOCKED_DATA_QUALITY");
        evidence.setDecision("HOLD");
        evidence.setBlockerReason(safeBlocker);
        evidence.setTerminalBlocker(safeBlocker);
        evidence.setIntentCreated(false);
        evidence.setPolicyInputsJson(policyInputsJson(stateHash));
        evidence.setExecutionPreviewJson(toJson(Map.of(
                "virtualExecutionOnly", true,
                "orderSent", false,
                "blocked", true,
                "blocker", safeBlocker)));
        evidenceRepository.save(evidence);
    }

    private BtDecisionAudit saveAudit(MdKline bar,
                                      LocalDateTime now,
                                      String eventType,
                                      String outcome,
                                      String blocker,
                                      String reason,
                                      Map<String, Object> context) {
        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setEventTime(now);
        audit.setStrategyId(null);
        audit.setSymbol(SYMBOL);
        audit.setIntervalCode(INTERVAL);
        audit.setBarOpenTime(bar.getOpenTime());
        audit.setEventType(eventType);
        audit.setOutcome(outcome);
        audit.setBlocker(blocker == null ? null : truncate(blocker, 64));
        audit.setReason(truncate(reason, 500));
        audit.setContextJson(toJson(context));
        return decisionAuditRepository.save(audit);
    }

    private RuntimeDecisionEvidence baseEvidence(BtDecisionAudit audit, LocalDateTime now) {
        RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        evidence.setDecisionId(audit.getId());
        evidence.setEvidenceTime(now);
        evidence.setSymbol(SYMBOL);
        evidence.setSide("LONG");
        evidence.setStrategyId(null);
        evidence.setIntervalCode(INTERVAL);
        evidence.setSignalSource(SIGNAL_SOURCE);
        evidence.setPolicyMode(POLICY_MODE);
        evidence.setPolicyReason(
                "MEI>=60, positive 24h momentum, close>EMA20; edge-triggered virtual accumulation");
        evidence.setExecutionMode("SHADOW_ONLY");
        evidence.setOrderSent(false);
        evidence.setSuppressionReason("SHADOW_ONLY_NO_ORDER_CAPABILITY");
        evidence.setOcoPlanCreated(false);
        return evidence;
    }

    private RestoredState restoreLatestState() {
        List<RuntimeDecisionEvidence> rows = evidenceRepository
                .findByPolicyModeAndSymbolAndIntervalCodeOrderByIdDesc(
                        POLICY_MODE,
                        SYMBOL,
                        INTERVAL,
                        PageRequest.of(0, STATE_RESTORE_SCAN_LIMIT));
        if (rows == null || rows.isEmpty()) return null;
        int invalid = 0;
        for (RuntimeDecisionEvidence row : rows) {
            try {
                if (Boolean.TRUE.equals(row.getOrderSent())
                        || !"SHADOW_ONLY".equals(row.getExecutionMode())) {
                    invalid++;
                    continue;
                }
                JsonNode root = objectMapper.readTree(row.getFeaturesSnapshotJson());
                JsonNode stateNode = root.path("stateAfter");
                String expectedHash = root.path("stateAfterSha256").asText("");
                if (stateNode.isMissingNode() || stateNode.isNull() || expectedHash.isBlank()) {
                    invalid++;
                    continue;
                }
                BtcMeiDirectionalShadowEngine.State state = objectMapper.treeToValue(
                        stateNode,
                        BtcMeiDirectionalShadowEngine.State.class);
                if (!expectedHash.equals(engine.stateSha256(state))) {
                    invalid++;
                    continue;
                }
                if ("SHADOW_OBSERVED".equals(row.getFinalOutcome())) {
                    LocalDateTime evidenceBar = parseTime(root.path("barOpenTime").asText(""));
                    if (!state.lastProcessedBarOpenTime().equals(evidenceBar)) {
                        invalid++;
                        continue;
                    }
                }
                return new RestoredState(state, invalid);
            } catch (Exception ignored) {
                invalid++;
            }
        }
        return new RestoredState(null, invalid);
    }

    private String policyInputsJson(String stateHash) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("policyMode", POLICY_MODE);
        policy.put("entropyWindowsHours", List.of(ENTROPY_24H, ENTROPY_48H, ENTROPY_72H));
        policy.put("entropyWeights", List.of(
                ENTROPY_24H_WEIGHT,
                ENTROPY_48H_WEIGHT,
                ENTROPY_72H_WEIGHT));
        policy.put("entropyBins", ENTROPY_BINS);
        policy.put("entryEntropyThreshold", ENTRY_ENTROPY_THRESHOLD);
        policy.put("momentumLookbackHours", MOMENTUM_LOOKBACK_HOURS);
        policy.put("emaPeriodHours", EMA_PERIOD_HOURS);
        policy.put("entryTrigger", "FALSE_TO_TRUE_EDGE_ONLY");
        policy.put("baseNotionalUsdt", BASE_NOTIONAL_USDT);
        policy.put("maxOpenCostUsdt", MAX_OPEN_COST_USDT);
        policy.put("feeRatePerSide", FEE_RATE_PER_SIDE);
        policy.put("adverseSlippageRatePerSide", ADVERSE_SLIPPAGE_RATE_PER_SIDE);
        policy.put("netProfitTrigger", NET_PROFIT_TRIGGER);
        policy.put("minRealizedNetProfit", MIN_REALIZED_NET_PROFIT);
        policy.put("signalExecution", "NEXT_1H_OPEN");
        policy.put("forcedExit", false);
        policy.put("stateAfterSha256", stateHash);
        policy.put("liveImplementationPresent", false);
        policy.put("orderAllowed", false);
        return toJson(policy);
    }

    private String exposureSnapshotJson(BtcMeiDirectionalShadowEngine.State state) {
        LocalDateTime oldest = state.openLots().stream()
                .map(BtcMeiDirectionalShadowEngine.Lot::buyFillBarOpenTime)
                .min(LocalDateTime::compareTo)
                .orElse(null);
        BigDecimal averageCostPrice = state.inventoryQty().signum() > 0
                ? state.openCostUsdt().divide(
                        state.inventoryQty(), 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        Map<String, Object> exposure = new LinkedHashMap<>();
        exposure.put("realizedPnlUsdt", state.realizedPnlUsdt());
        exposure.put("openLotCount", state.openLots().size());
        exposure.put("openCostUsdt", state.openCostUsdt());
        exposure.put("openAverageCostPrice", averageCostPrice);
        exposure.put("oldestOpenLotTime", formatTime(oldest));
        exposure.put("maximumObservedOpenCostUsdt", state.maxOpenCostUsdt());
        exposure.put("maximumOpenCapitalLossPct", state.maxOpenCapitalLossPct());
        exposure.put("peakVirtualEquityUsdt", state.peakVirtualEquityUsdt());
        exposure.put("maximumVirtualDrawdownPct", state.maxVirtualDrawdownPct());
        return toJson(exposure);
    }

    private String selectedAction(List<BtcMeiDirectionalShadowEngine.RuntimeEvent> events) {
        if (events == null || events.isEmpty()) return "MEI_DIRECTIONAL_STATE_ADVANCE";
        Set<String> types = events.stream()
                .map(BtcMeiDirectionalShadowEngine.RuntimeEvent::eventType)
                .collect(Collectors.toSet());
        if (types.contains("VIRTUAL_SELL_FILL")) return "MEI_DIRECTIONAL_VIRTUAL_SELL";
        if (types.contains("VIRTUAL_ENTRY_QUEUED")) return "MEI_DIRECTIONAL_ENTRY_SIGNAL";
        if (types.contains("VIRTUAL_EXIT_QUEUED")) return "MEI_DIRECTIONAL_EXIT_SIGNAL";
        if (types.contains("VIRTUAL_BUY_FILL")) return "MEI_DIRECTIONAL_VIRTUAL_BUY";
        if (types.contains("VIRTUAL_ENTRY_BLOCKED")) return "MEI_DIRECTIONAL_ENTRY_BLOCKED";
        return "MEI_DIRECTIONAL_EXIT_DEFERRED";
    }

    private String eventReason(List<BtcMeiDirectionalShadowEngine.RuntimeEvent> events,
                               BtcMeiDirectionalShadowEngine.SignalSnapshot signal) {
        if (events == null || events.isEmpty()) return truncate(signal.reason(), 500);
        return truncate(events.stream()
                .map(BtcMeiDirectionalShadowEngine.RuntimeEvent::eventType)
                .distinct()
                .collect(Collectors.joining(",")), 500);
    }

    private String decision(List<BtcMeiDirectionalShadowEngine.RuntimeEvent> events) {
        boolean buy = events.stream()
                .anyMatch(event -> "VIRTUAL_ENTRY_QUEUED".equals(event.eventType()));
        boolean exit = events.stream()
                .anyMatch(event -> "VIRTUAL_EXIT_QUEUED".equals(event.eventType())
                        || "VIRTUAL_SELL_FILL".equals(event.eventType()));
        if (buy && exit) return "BUY_AND_EXIT";
        if (buy) return "BUY_SIGNAL";
        if (exit) return "EXIT";
        return "HOLD";
    }

    private boolean isSignalEvent(BtcMeiDirectionalShadowEngine.RuntimeEvent event) {
        return "VIRTUAL_ENTRY_QUEUED".equals(event.eventType())
                || "VIRTUAL_EXIT_QUEUED".equals(event.eventType());
    }

    private boolean matchesScope(MdKline kline) {
        return kline != null
                && SYMBOL.equals(normalizeSymbol(kline.getSymbol()))
                && INTERVAL.equalsIgnoreCase(kline.getIntervalCode())
                && SOURCE.equalsIgnoreCase(kline.getSource());
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.toUpperCase(Locale.ROOT)
                .replace("-", "").replace("/", "").replace("_", "");
    }

    private String formatTime(LocalDateTime value) {
        return value == null ? null : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value);
    }

    private LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            return OffsetDateTime.parse(
                    value,
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDateTime();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to serialize MEI directional runtime evidence",
                    e);
        }
    }

    private String safeCode(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN_FAIL_CLOSED";
        return truncate(value.replaceAll("[^A-Za-z0-9_:\\->]", "_"), 128);
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private record RestoredState(
            BtcMeiDirectionalShadowEngine.State state,
            int invalidRowsScanned
    ) {
    }
}
