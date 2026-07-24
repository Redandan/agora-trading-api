package com.agora.service.trading;

import com.agora.config.properties.BtcDonchianShadowProperties;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.agora.service.trading.BtcDonchianShadowPolicy.ATR_LOOKBACK_DAYS;
import static com.agora.service.trading.BtcDonchianShadowPolicy.ENTRY_LOOKBACK_DAYS;
import static com.agora.service.trading.BtcDonchianShadowPolicy.EQUITY_RISK_PER_TRADE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.EVIDENCE_SCHEMA_VERSION;
import static com.agora.service.trading.BtcDonchianShadowPolicy.EXIT_LOOKBACK_DAYS;
import static com.agora.service.trading.BtcDonchianShadowPolicy.GOLDEN_FIRST_OPEN_TIME;
import static com.agora.service.trading.BtcDonchianShadowPolicy.INITIAL_STOP_ATR_MULTIPLE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.INTERVAL;
import static com.agora.service.trading.BtcDonchianShadowPolicy.MAXIMUM_EXPOSURE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.MAX_CATCH_UP_BARS;
import static com.agora.service.trading.BtcDonchianShadowPolicy.NORMAL;
import static com.agora.service.trading.BtcDonchianShadowPolicy.POLICY_MODE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.SOURCE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.STRESS;
import static com.agora.service.trading.BtcDonchianShadowPolicy.SYMBOL;

/** Closed-bar, evidence-only runtime lane. This class has no trading, OCO, or notification dependency. */
@Service
@RequiredArgsConstructor
@Slf4j
public class BtcDonchianShadowLaneService {

    static final String EVENT_TYPE = "BTC_DONCHIAN_SHADOW";
    static final String BLOCK_EVENT_TYPE = "BTC_DONCHIAN_BLOCKED";
    private static final String SIGNAL_SOURCE = "OKX_CLOSED_1H_DONCHIAN";
    private static final int STATE_RESTORE_SCAN_LIMIT = 50;

    private final Set<String> evaluatingBars = ConcurrentHashMap.newKeySet();

    private final BtcDonchianShadowProperties properties;
    private final MdKlineRepository klineRepository;
    private final BtDecisionAuditRepository decisionAuditRepository;
    private final RuntimeDecisionEvidenceRepository evidenceRepository;
    private final RuntimeDecisionEvidenceService runtimeEvidenceService;
    private final BtcDonchianShadowEngine engine;
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
            log.warn("[BtcDonchianShadow] runtime evidence disabled; closed bar ignored openTime={}",
                    eventKline.getOpenTime());
            return;
        }
        String key = SOURCE + "|" + SYMBOL + "|" + INTERVAL + "|" + eventKline.getOpenTime();
        if (!evaluatingBars.add(key)) {
            log.debug("[BtcDonchianShadow] concurrent duplicate ignored: {}", key);
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
            log.debug("[BtcDonchianShadow] persisted duplicate ignored openTime={}", eventKline.getOpenTime());
            return;
        }
        LocalDateTime expectedClose = eventKline.getOpenTime().plusHours(1);
        if (eventKline.getCloseTime() == null || !expectedClose.equals(eventKline.getCloseTime())
                || expectedClose.isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1))) {
            persistBlocked(eventKline, null, "EVENT_NOT_PROVEN_CLOSED", false, false, 0);
            return;
        }

        RestoredState restored = restoreLatestState();
        BtcDonchianShadowEngine.State state = restored == null ? null : restored.state();
        if (state != null && state.getLastProcessedBarOpenTime() != null
                && !state.getLastProcessedBarOpenTime().isBefore(eventKline.getOpenTime())) {
            log.debug("[BtcDonchianShadow] event is not newer than restored state event={} state={}",
                    eventKline.getOpenTime(), state.getLastProcessedBarOpenTime());
            return;
        }

        if (state == null) {
            bootstrap(eventKline, restored == null ? 0 : restored.invalidRowsScanned());
            return;
        }
        catchUp(state, eventKline, restored.invalidRowsScanned());
    }

    private void bootstrap(MdKline eventKline, int invalidStateRows) {
        if (eventKline.getOpenTime().isBefore(GOLDEN_FIRST_OPEN_TIME)) {
            persistBlocked(eventKline, null, "BOOTSTRAP_EVENT_BEFORE_RESEARCH_ANCHOR",
                    true, false, invalidStateRows);
            return;
        }
        List<MdKline> bars = klineRepository
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        SYMBOL, INTERVAL, SOURCE, GOLDEN_FIRST_OPEN_TIME, eventKline.getOpenTime());
        long expected = Duration.between(GOLDEN_FIRST_OPEN_TIME, eventKline.getOpenTime()).toHours() + 1;
        if (bars == null || bars.size() != expected || bars.isEmpty()
                || !GOLDEN_FIRST_OPEN_TIME.equals(bars.get(0).getOpenTime())
                || !eventKline.getOpenTime().equals(bars.get(bars.size() - 1).getOpenTime())) {
            persistBlocked(eventKline, null, "BOOTSTRAP_HISTORY_INCOMPLETE",
                    true, false, invalidStateRows);
            return;
        }

        BtcDonchianShadowEngine.State state = engine.initialState();
        BtcDonchianShadowEngine.StepResult current = null;
        try {
            for (MdKline bar : bars) current = engine.step(state, bar);
        } catch (BtcDonchianShadowEngine.DataQualityException e) {
            persistBlocked(eventKline, null, "BOOTSTRAP_" + safeCode(e.getMessage()),
                    true, false, invalidStateRows);
            return;
        }
        if (current == null) {
            persistBlocked(eventKline, null, "BOOTSTRAP_NO_CURRENT_STEP",
                    true, false, invalidStateRows);
            return;
        }
        persistObserved(eventKline, current, true, false, bars.size(), invalidStateRows);
    }

    private void catchUp(BtcDonchianShadowEngine.State state,
                         MdKline eventKline,
                         int invalidStateRows) {
        LocalDateTime next = state.getLastProcessedBarOpenTime().plusHours(1);
        long expected = Duration.between(next, eventKline.getOpenTime()).toHours() + 1;
        if (expected <= 0) return;
        if (expected > MAX_CATCH_UP_BARS) {
            persistBlocked(eventKline, state, "CATCH_UP_LIMIT_EXCEEDED",
                    false, true, invalidStateRows);
            return;
        }
        List<MdKline> bars = klineRepository
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        SYMBOL, INTERVAL, SOURCE, next, eventKline.getOpenTime());
        if (bars == null || bars.size() != expected || bars.isEmpty()
                || !next.equals(bars.get(0).getOpenTime())
                || !eventKline.getOpenTime().equals(bars.get(bars.size() - 1).getOpenTime())) {
            persistBlocked(eventKline, state, "CATCH_UP_HISTORY_INCOMPLETE",
                    false, true, invalidStateRows);
            return;
        }
        for (int i = 0; i < bars.size(); i++) {
            MdKline bar = bars.get(i);
            if (decisionAuditRepository.existsBySymbolAndIntervalCodeAndBarOpenTimeAndEventType(
                    SYMBOL, INTERVAL, bar.getOpenTime(), EVENT_TYPE)) {
                persistBlocked(eventKline, state, "AUDIT_STATE_DIVERGENCE",
                        false, true, invalidStateRows);
                return;
            }
            BtcDonchianShadowEngine.StepResult step;
            try {
                step = engine.step(state, bar);
            } catch (BtcDonchianShadowEngine.DataQualityException e) {
                persistBlocked(bar, state, "CATCH_UP_" + safeCode(e.getMessage()),
                        false, true, invalidStateRows);
                return;
            }
            boolean catchUp = i < bars.size() - 1;
            persistObserved(bar, step, false, catchUp, bars.size(), invalidStateRows);
        }
    }

    private void persistObserved(MdKline bar,
                                 BtcDonchianShadowEngine.StepResult step,
                                 boolean bootstrap,
                                 boolean catchUp,
                                 int batchBars,
                                 int invalidStateRows) {
        List<BtcDonchianShadowEngine.RuntimeEvent> events = step.events();
        String selectedAction = selectedAction(events);
        String stateHash = engine.stateSha256(step.state());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        Map<String, Object> auditContext = new LinkedHashMap<>();
        auditContext.put("policyMode", POLICY_MODE);
        auditContext.put("evidenceSchemaVersion", EVIDENCE_SCHEMA_VERSION);
        auditContext.put("source", SOURCE);
        auditContext.put("barCloseTime", BtcDonchianEvidenceTime.format(bar.getCloseTime()));
        auditContext.put("bootstrap", bootstrap);
        auditContext.put("catchUp", catchUp);
        auditContext.put("batchBars", batchBars);
        auditContext.put("eventCount", events.size());
        auditContext.put("selectedAction", selectedAction);
        auditContext.put("stateAfterSha256", stateHash);
        auditContext.put("orderSent", false);
        auditContext.put("liveImplementationPresent", false);
        BtDecisionAudit audit = saveAudit(bar, now, EVENT_TYPE, events.isEmpty() ? "INFO" : "PASS",
                null, selectedAction, auditContext);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("evidenceSchemaVersion", EVIDENCE_SCHEMA_VERSION);
        snapshot.put("policyMode", POLICY_MODE);
        snapshot.put("symbol", SYMBOL);
        snapshot.put("intervalCode", INTERVAL);
        snapshot.put("source", SOURCE);
        snapshot.put("barOpenTime", BtcDonchianEvidenceTime.format(bar.getOpenTime()));
        snapshot.put("barCloseTime", BtcDonchianEvidenceTime.format(bar.getCloseTime()));
        snapshot.put("bootstrap", bootstrap);
        snapshot.put("catchUp", catchUp);
        snapshot.put("batchBars", batchBars);
        snapshot.put("invalidStateRowsScanned", invalidStateRows);
        snapshot.put("timingCausal", true);
        snapshot.put("hourlyLatticeComplete", true);
        snapshot.put("feeModelComplete", true);
        snapshot.put("slippageModelComplete", true);
        snapshot.put("stateAfterSha256", stateHash);
        snapshot.put("stateAfter", step.state());
        snapshot.put("events", events);
        snapshot.put("orderSent", false);
        snapshot.put("ocoModified", false);
        snapshot.put("telegramSent", false);
        snapshot.put("liveImplementationPresent", false);

        RuntimeDecisionEvidence evidence = baseEvidence(audit, now);
        evidence.setFeaturesSnapshotJson(toJson(snapshot));
        evidence.setFreshnessState(catchUp ? "CAUSAL_CATCH_UP_COMPLETE" : "CURRENT_CLOSED_BAR");
        evidence.setSelectedAction(selectedAction);
        evidence.setReason(eventReason(events));
        evidence.setFinalOutcome("SHADOW_OBSERVED");
        evidence.setDecision(decision(events));
        evidence.setIntentCreated(events.stream().anyMatch(this::isSignalEvent));
        evidence.setPolicyInputsJson(policyInputsJson(stateHash));
        evidence.setExecutionPreviewJson(toJson(Map.of(
                "events", events,
                "virtualExecutionOnly", true,
                "orderSent", false,
                "liveImplementationPresent", false)));
        evidenceRepository.save(evidence);
    }

    private void persistBlocked(MdKline bar,
                                BtcDonchianShadowEngine.State state,
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
        auditContext.put("bootstrap", bootstrap);
        auditContext.put("catchUp", catchUp);
        auditContext.put("terminalBlocker", safeBlocker);
        auditContext.put("stateAfterSha256", stateHash);
        auditContext.put("orderSent", false);
        auditContext.put("liveImplementationPresent", false);
        BtDecisionAudit audit = saveAudit(bar, now, BLOCK_EVENT_TYPE, "BLOCKED", safeBlocker,
                "DONCHIAN_SHADOW_BLOCKED", auditContext);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("evidenceSchemaVersion", EVIDENCE_SCHEMA_VERSION);
        snapshot.put("policyMode", POLICY_MODE);
        snapshot.put("barOpenTime", BtcDonchianEvidenceTime.format(bar.getOpenTime()));
        snapshot.put("barCloseTime", BtcDonchianEvidenceTime.format(bar.getCloseTime()));
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
        snapshot.put("telegramSent", false);
        snapshot.put("liveImplementationPresent", false);

        RuntimeDecisionEvidence evidence = baseEvidence(audit, now);
        evidence.setFeaturesSnapshotJson(toJson(snapshot));
        evidence.setFreshnessState("INCOMPLETE_FAIL_CLOSED");
        evidence.setSelectedAction("DONCHIAN_SHADOW_BLOCKED");
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
        evidence.setPolicyReason("20D breakout, 10D exit, ATR14 x2 initial stop, 1% virtual equity risk");
        evidence.setExecutionMode("SHADOW_ONLY");
        evidence.setOrderSent(false);
        evidence.setSuppressionReason("SHADOW_ONLY_NO_ORDER_CAPABILITY");
        evidence.setOcoPlanCreated(false);
        return evidence;
    }

    private RestoredState restoreLatestState() {
        List<RuntimeDecisionEvidence> rows = evidenceRepository
                .findByPolicyModeAndSymbolAndIntervalCodeOrderByIdDesc(
                        POLICY_MODE, SYMBOL, INTERVAL, PageRequest.of(0, STATE_RESTORE_SCAN_LIMIT));
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
                BtcDonchianShadowEngine.State state = objectMapper.treeToValue(
                        stateNode, BtcDonchianShadowEngine.State.class);
                if (!expectedHash.equals(engine.stateSha256(state))) {
                    invalid++;
                    continue;
                }
                if ("SHADOW_OBSERVED".equals(row.getFinalOutcome())) {
                    LocalDateTime evidenceBar = BtcDonchianEvidenceTime.parse(
                            root.path("barOpenTime").asText(""));
                    if (!evidenceBar.equals(state.getLastProcessedBarOpenTime())) {
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
        policy.put("entryLookbackDays", ENTRY_LOOKBACK_DAYS);
        policy.put("exitLookbackDays", EXIT_LOOKBACK_DAYS);
        policy.put("atrLookbackDays", ATR_LOOKBACK_DAYS);
        policy.put("initialStopAtrMultiple", INITIAL_STOP_ATR_MULTIPLE);
        policy.put("equityRiskPerTrade", EQUITY_RISK_PER_TRADE);
        policy.put("maximumExposure", MAXIMUM_EXPOSURE);
        policy.put("signalExecution", "NEXT_1H_OPEN");
        policy.put("stopExecution", "STOP_PRICE_OR_GAP_OPEN_WHICHEVER_IS_WORSE");
        policy.put("normalFeeRatePerSide", NORMAL.feeRatePerSide());
        policy.put("normalAdverseSlippageRatePerSide", NORMAL.adverseSlippageRatePerSide());
        policy.put("stressFeeRatePerSide", STRESS.feeRatePerSide());
        policy.put("stressAdverseSlippageRatePerSide", STRESS.adverseSlippageRatePerSide());
        policy.put("stressSignalDelayBars", STRESS.signalDelayBars());
        policy.put("stateAfterSha256", stateHash);
        policy.put("liveImplementationPresent", false);
        policy.put("orderAllowed", false);
        return toJson(policy);
    }

    private String selectedAction(List<BtcDonchianShadowEngine.RuntimeEvent> events) {
        if (events == null || events.isEmpty()) return "DONCHIAN_SHADOW_STATE_ADVANCE";
        Set<String> types = events.stream().map(BtcDonchianShadowEngine.RuntimeEvent::eventType)
                .collect(Collectors.toSet());
        if (types.stream().anyMatch(type -> type.endsWith("SIGNAL"))) return "DONCHIAN_SHADOW_SIGNAL";
        if (types.contains("ATR_STOP_EXIT")) return "DONCHIAN_SHADOW_ATR_STOP";
        if (types.contains("VIRTUAL_TRADE_CLOSED")) return "DONCHIAN_SHADOW_TRADE_CLOSED";
        return "DONCHIAN_SHADOW_VIRTUAL_FILL";
    }

    private String eventReason(List<BtcDonchianShadowEngine.RuntimeEvent> events) {
        if (events == null || events.isEmpty()) return "NO_SIGNAL_STATE_ADVANCED";
        return events.stream().map(BtcDonchianShadowEngine.RuntimeEvent::eventType)
                .distinct().collect(Collectors.joining(","));
    }

    private String decision(List<BtcDonchianShadowEngine.RuntimeEvent> events) {
        if (events.stream().anyMatch(event -> "ENTRY_SIGNAL".equals(event.eventType()))) return "BUY_SIGNAL";
        if (events.stream().anyMatch(event -> "EXIT_SIGNAL".equals(event.eventType()))) return "SELL_SIGNAL";
        if (events.stream().anyMatch(event -> "ATR_STOP_EXIT".equals(event.eventType())
                || "VIRTUAL_TRADE_CLOSED".equals(event.eventType()))) return "EXIT";
        return "HOLD";
    }

    private boolean isSignalEvent(BtcDonchianShadowEngine.RuntimeEvent event) {
        return "ENTRY_SIGNAL".equals(event.eventType()) || "EXIT_SIGNAL".equals(event.eventType());
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize Donchian runtime evidence", e);
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

    private record RestoredState(BtcDonchianShadowEngine.State state, int invalidRowsScanned) {
    }
}
