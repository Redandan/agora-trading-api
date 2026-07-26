package com.agora.service.trading;

import com.agora.config.properties.BtcDraShadowProperties;
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

import static com.agora.service.trading.BtcDraShadowPolicy.ADVERSE_SLIPPAGE_RATE_PER_SIDE;
import static com.agora.service.trading.BtcDraShadowPolicy.ARM_EXPIRY_DAYS;
import static com.agora.service.trading.BtcDraShadowPolicy.BASE_NOTIONAL_USDT;
import static com.agora.service.trading.BtcDraShadowPolicy.BOOTSTRAP_HISTORY_HOURS;
import static com.agora.service.trading.BtcDraShadowPolicy.DAILY_EMA_PERIOD_DAYS;
import static com.agora.service.trading.BtcDraShadowPolicy.EMA_SLOPE_LOOKBACK_DAYS;
import static com.agora.service.trading.BtcDraShadowPolicy.ENTRY_COOLDOWN_DAYS;
import static com.agora.service.trading.BtcDraShadowPolicy.EVIDENCE_SCHEMA_VERSION;
import static com.agora.service.trading.BtcDraShadowPolicy.FEE_RATE_PER_SIDE;
import static com.agora.service.trading.BtcDraShadowPolicy.INTERVAL;
import static com.agora.service.trading.BtcDraShadowPolicy.MAX_CATCH_UP_BARS;
import static com.agora.service.trading.BtcDraShadowPolicy.MAX_OPEN_COST_USDT;
import static com.agora.service.trading.BtcDraShadowPolicy.MIN_REALIZED_NET_PROFIT;
import static com.agora.service.trading.BtcDraShadowPolicy.MOMENTUM_LOOKBACK_HOURS;
import static com.agora.service.trading.BtcDraShadowPolicy.NET_PROFIT_TRIGGER;
import static com.agora.service.trading.BtcDraShadowPolicy.POLICY_MODE;
import static com.agora.service.trading.BtcDraShadowPolicy.SOURCE;
import static com.agora.service.trading.BtcDraShadowPolicy.SYMBOL;

/**
 * Source-pinned evidence-only runtime lane for BTC DRA V1.
 *
 * <p>This class deliberately has no exchange, OCO, Grid, fund, or notification
 * dependency. Its only writes are decision audit and runtime evidence rows.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BtcDraShadowLaneService {

    static final String EVENT_TYPE = "BTC_DRA_SHADOW";
    static final String BLOCK_EVENT_TYPE = "BTC_DRA_BLOCKED";
    private static final String SIGNAL_SOURCE = "OKX_CLOSED_1H_DRA";
    private static final int STATE_RESTORE_SCAN_LIMIT = 50;

    private final Set<String> evaluatingBars = ConcurrentHashMap.newKeySet();

    private final BtcDraShadowProperties properties;
    private final MdKlineRepository klineRepository;
    private final BtDecisionAuditRepository decisionAuditRepository;
    private final RuntimeDecisionEvidenceRepository evidenceRepository;
    private final RuntimeDecisionEvidenceService runtimeEvidenceService;
    private final BtcDraShadowEngine engine;
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
            log.warn("[BtcDraShadow] runtime evidence disabled; bar ignored openTime={}",
                    eventKline.getOpenTime());
            return;
        }
        String key = SOURCE + "|" + SYMBOL + "|" + INTERVAL + "|"
                + eventKline.getOpenTime();
        if (!evaluatingBars.add(key)) {
            log.debug("[BtcDraShadow] concurrent duplicate ignored: {}", key);
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
        if (decisionAuditRepository
                .existsBySymbolAndIntervalCodeAndBarOpenTimeAndEventType(
                        SYMBOL,
                        INTERVAL,
                        eventKline.getOpenTime(),
                        EVENT_TYPE)) {
            log.debug("[BtcDraShadow] persisted duplicate ignored openTime={}",
                    eventKline.getOpenTime());
            return;
        }
        LocalDateTime expectedClose = eventKline.getOpenTime().plusHours(1);
        if (eventKline.getCloseTime() == null
                || !expectedClose.equals(eventKline.getCloseTime())
                || expectedClose.isAfter(
                        LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1))) {
            persistBlocked(
                    eventKline,
                    null,
                    "EVENT_NOT_PROVEN_CLOSED",
                    false,
                    false,
                    0);
            return;
        }

        RestoredState restored = restoreLatestState();
        BtcDraShadowEngine.State state =
                restored == null ? null : restored.state();
        if (state != null
                && state.lastProcessedBarOpenTime() != null
                && !state.lastProcessedBarOpenTime()
                .isBefore(eventKline.getOpenTime())) {
            return;
        }
        if (state == null) {
            if (restored != null && restored.currentSchemaEvidenceSeen()) {
                log.warn("[BtcDraShadow] current-schema state restore failed; "
                                + "blocking instead of re-bootstrap openTime={} "
                                + "invalidRows={}",
                        eventKline.getOpenTime(),
                        restored.invalidRowsScanned());
                persistBlocked(
                        eventKline,
                        null,
                        "STATE_RESTORE_FAILED",
                        false,
                        false,
                        restored.invalidRowsScanned());
                return;
            }
            bootstrap(
                    eventKline,
                    restored == null ? 0 : restored.invalidRowsScanned());
            return;
        }
        catchUp(state, eventKline, restored.invalidRowsScanned());
    }

    private void bootstrap(MdKline eventKline, int invalidStateRows) {
        LocalDateTime start = eventKline.getOpenTime()
                .minusHours(BOOTSTRAP_HISTORY_HOURS - 1L);
        List<MdKline> bars = klineRepository
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        SYMBOL,
                        INTERVAL,
                        SOURCE,
                        start,
                        eventKline.getOpenTime());
        if (bars == null
                || bars.size() != BOOTSTRAP_HISTORY_HOURS
                || !start.equals(bars.get(0).getOpenTime())
                || !eventKline.getOpenTime()
                .equals(bars.get(bars.size() - 1).getOpenTime())) {
            persistBlocked(
                    eventKline,
                    null,
                    "BOOTSTRAP_HISTORY_INCOMPLETE",
                    true,
                    false,
                    invalidStateRows);
            return;
        }

        BtcDraShadowEngine.State state = engine.initialState();
        BtcDraShadowEngine.StepResult current = null;
        try {
            for (int i = 0; i < bars.size(); i++) {
                MdKline bar = bars.get(i);
                current = i == bars.size() - 1
                        ? engine.step(state, bar)
                        : engine.warmup(state, bar);
                state = current.state();
            }
        } catch (BtcDraShadowEngine.DataQualityException e) {
            persistBlocked(
                    eventKline,
                    null,
                    "BOOTSTRAP_" + safeCode(e.getMessage()),
                    true,
                    false,
                    invalidStateRows);
            return;
        }
        if (current == null) {
            persistBlocked(
                    eventKline,
                    null,
                    "BOOTSTRAP_NO_CURRENT_STEP",
                    true,
                    false,
                    invalidStateRows);
            return;
        }
        persistObserved(
                eventKline,
                current,
                true,
                false,
                bars.size(),
                invalidStateRows);
    }

    private void catchUp(
            BtcDraShadowEngine.State initial,
            MdKline eventKline,
            int invalidStateRows) {
        LocalDateTime next = initial.lastProcessedBarOpenTime().plusHours(1);
        long expected =
                Duration.between(next, eventKline.getOpenTime()).toHours() + 1;
        if (expected <= 0) return;
        if (expected > MAX_CATCH_UP_BARS) {
            persistBlocked(
                    eventKline,
                    initial,
                    "CATCH_UP_LIMIT_EXCEEDED",
                    false,
                    true,
                    invalidStateRows);
            return;
        }
        List<MdKline> bars = klineRepository
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        SYMBOL,
                        INTERVAL,
                        SOURCE,
                        next,
                        eventKline.getOpenTime());
        if (bars == null
                || bars.size() != expected
                || !next.equals(bars.get(0).getOpenTime())
                || !eventKline.getOpenTime()
                .equals(bars.get(bars.size() - 1).getOpenTime())) {
            persistBlocked(
                    eventKline,
                    initial,
                    "CATCH_UP_HISTORY_INCOMPLETE",
                    false,
                    true,
                    invalidStateRows);
            return;
        }

        BtcDraShadowEngine.State state = initial;
        for (int i = 0; i < bars.size(); i++) {
            MdKline bar = bars.get(i);
            if (decisionAuditRepository
                    .existsBySymbolAndIntervalCodeAndBarOpenTimeAndEventType(
                            SYMBOL,
                            INTERVAL,
                            bar.getOpenTime(),
                            EVENT_TYPE)) {
                persistBlocked(
                        eventKline,
                        state,
                        "AUDIT_STATE_DIVERGENCE",
                        false,
                        true,
                        invalidStateRows);
                return;
            }
            BtcDraShadowEngine.StepResult step;
            try {
                step = engine.step(state, bar);
            } catch (BtcDraShadowEngine.DataQualityException e) {
                persistBlocked(
                        bar,
                        state,
                        "CATCH_UP_" + safeCode(e.getMessage()),
                        false,
                        true,
                        invalidStateRows);
                return;
            }
            state = step.state();
            persistObserved(
                    bar,
                    step,
                    false,
                    i < bars.size() - 1,
                    bars.size(),
                    invalidStateRows);
        }
    }

    private void persistObserved(
            MdKline bar,
            BtcDraShadowEngine.StepResult step,
            boolean bootstrap,
            boolean catchUp,
            int batchBars,
            int invalidStateRows) {
        List<BtcDraShadowEngine.RuntimeEvent> events = step.events();
        String selectedAction = selectedAction(events);
        String canonicalStateJson = engine.stateCanonicalJson(step.state());
        String stateHash = engine.canonicalStateSha256(canonicalStateJson);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        Map<String, Object> auditContext = new LinkedHashMap<>();
        auditContext.put("policyMode", POLICY_MODE);
        auditContext.put("source", SOURCE);
        auditContext.put(
                "dailyReversalConfirmed",
                step.signal().dailyReversalConfirmed());
        auditContext.put("entryEligible", step.signal().entryEligible());
        auditContext.put("armed", step.state().armedAt() != null);
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
        snapshot.put("stateAfterCanonicalJson", canonicalStateJson);
        snapshot.put("events", events);
        snapshot.put("orderSent", false);
        snapshot.put("ocoModified", false);
        snapshot.put("gridModified", false);
        snapshot.put("telegramSent", false);
        snapshot.put("liveImplementationPresent", false);

        RuntimeDecisionEvidence evidence = baseEvidence(audit, now);
        evidence.setFeaturesSnapshotJson(toJson(snapshot));
        evidence.setFreshnessState(
                catchUp ? "CAUSAL_CATCH_UP_COMPLETE" : "CURRENT_CLOSED_BAR");
        evidence.setSelectedAction(selectedAction);
        evidence.setReason(eventReason(events, step.signal()));
        evidence.setFinalOutcome("SHADOW_OBSERVED");
        evidence.setScore(step.signal().entryEligible() ? 1.0 : 0.0);
        evidence.setThreshold(1.0);
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

    private void persistBlocked(
            MdKline bar,
            BtcDraShadowEngine.State state,
            String blocker,
            boolean bootstrap,
            boolean catchUp,
            int invalidStateRows) {
        if (bar == null || bar.getOpenTime() == null) return;
        if (decisionAuditRepository
                .existsBySymbolAndIntervalCodeAndBarOpenTimeAndEventType(
                        SYMBOL,
                        INTERVAL,
                        bar.getOpenTime(),
                        BLOCK_EVENT_TYPE)) {
            return;
        }
        String safeBlocker = safeCode(blocker);
        String canonicalStateJson =
                state == null ? null : engine.stateCanonicalJson(state);
        String stateHash = canonicalStateJson == null
                ? null
                : engine.canonicalStateSha256(canonicalStateJson);
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
                "DRA_SHADOW_BLOCKED",
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
        if (canonicalStateJson != null) {
            snapshot.put("stateAfterCanonicalJson", canonicalStateJson);
        }
        snapshot.put("events", List.of());
        snapshot.put("orderSent", false);
        snapshot.put("ocoModified", false);
        snapshot.put("gridModified", false);
        snapshot.put("telegramSent", false);
        snapshot.put("liveImplementationPresent", false);

        RuntimeDecisionEvidence evidence = baseEvidence(audit, now);
        evidence.setFeaturesSnapshotJson(toJson(snapshot));
        evidence.setFreshnessState("INCOMPLETE_FAIL_CLOSED");
        evidence.setSelectedAction("DRA_SHADOW_BLOCKED");
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

    private BtDecisionAudit saveAudit(
            MdKline bar,
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

    private RuntimeDecisionEvidence baseEvidence(
            BtDecisionAudit audit,
            LocalDateTime now) {
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
                "UTC daily close above daily EMA20, rising five-day EMA20, "
                        + "positive 24h momentum; no MEI or drawdown gate");
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
        boolean currentSchemaEvidenceSeen = false;
        for (RuntimeDecisionEvidence row : rows) {
            try {
                JsonNode root = objectMapper.readTree(
                        row.getFeaturesSnapshotJson());
                if (!EVIDENCE_SCHEMA_VERSION.equals(
                        root.path("evidenceSchemaVersion").asText(""))) {
                    invalid++;
                    continue;
                }
                currentSchemaEvidenceSeen = true;
                if (Boolean.TRUE.equals(row.getOrderSent())
                        || !"SHADOW_ONLY".equals(row.getExecutionMode())) {
                    invalid++;
                    continue;
                }
                String canonicalStateJson =
                        root.path("stateAfterCanonicalJson").asText("");
                String expectedHash =
                        root.path("stateAfterSha256").asText("");
                if (canonicalStateJson.isBlank()
                        || expectedHash.isBlank()
                        || !expectedHash.equals(
                        engine.canonicalStateSha256(canonicalStateJson))) {
                    invalid++;
                    continue;
                }
                BtcDraShadowEngine.State state = objectMapper.readValue(
                        canonicalStateJson,
                        BtcDraShadowEngine.State.class);
                if (!expectedHash.equals(engine.stateSha256(state))) {
                    invalid++;
                    continue;
                }
                if ("SHADOW_OBSERVED".equals(row.getFinalOutcome())) {
                    LocalDateTime evidenceBar =
                            parseTime(root.path("barOpenTime").asText(""));
                    if (!state.lastProcessedBarOpenTime().equals(evidenceBar)) {
                        invalid++;
                        continue;
                    }
                }
                return new RestoredState(state, invalid, true);
            } catch (Exception ignored) {
                invalid++;
            }
        }
        return new RestoredState(null, invalid, currentSchemaEvidenceSeen);
    }

    private String policyInputsJson(String stateHash) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("policyMode", POLICY_MODE);
        policy.put("dailyDecisionHourUtc", 23);
        policy.put("dailyEmaPeriodDays", DAILY_EMA_PERIOD_DAYS);
        policy.put("emaSlopeLookbackDays", EMA_SLOPE_LOOKBACK_DAYS);
        policy.put("momentumLookbackHours", MOMENTUM_LOOKBACK_HOURS);
        policy.put("entryCooldownDays", ENTRY_COOLDOWN_DAYS);
        policy.put("armExpiryDays", ARM_EXPIRY_DAYS);
        policy.put("meiGatePresent", false);
        policy.put("drawdownGatePresent", false);
        policy.put("baseNotionalUsdt", BASE_NOTIONAL_USDT);
        policy.put("maxOpenCostUsdt", MAX_OPEN_COST_USDT);
        policy.put("feeRatePerSide", FEE_RATE_PER_SIDE);
        policy.put(
                "adverseSlippageRatePerSide",
                ADVERSE_SLIPPAGE_RATE_PER_SIDE);
        policy.put("netProfitTrigger", NET_PROFIT_TRIGGER);
        policy.put("minRealizedNetProfit", MIN_REALIZED_NET_PROFIT);
        policy.put("signalExecution", "NEXT_1H_OPEN");
        policy.put("forcedExit", false);
        policy.put("stateAfterSha256", stateHash);
        policy.put("liveImplementationPresent", false);
        policy.put("orderAllowed", false);
        return toJson(policy);
    }

    private String exposureSnapshotJson(BtcDraShadowEngine.State state) {
        LocalDateTime oldest = state.openLots().stream()
                .map(BtcDraShadowEngine.Lot::buyFillBarOpenTime)
                .min(LocalDateTime::compareTo)
                .orElse(null);
        BigDecimal averageCostPrice = state.inventoryQty().signum() > 0
                ? state.openCostUsdt().divide(
                        state.inventoryQty(),
                        8,
                        RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        Map<String, Object> exposure = new LinkedHashMap<>();
        exposure.put("realizedPnlUsdt", state.realizedPnlUsdt());
        exposure.put("openLotCount", state.openLots().size());
        exposure.put("openCostUsdt", state.openCostUsdt());
        exposure.put("openAverageCostPrice", averageCostPrice);
        exposure.put("oldestOpenLotTime", formatTime(oldest));
        exposure.put(
                "maximumObservedOpenCostUsdt",
                state.maxOpenCostUsdt());
        exposure.put(
                "maximumOpenCapitalLossPct",
                state.maxOpenCapitalLossPct());
        exposure.put(
                "peakVirtualEquityUsdt",
                state.peakVirtualEquityUsdt());
        exposure.put(
                "maximumVirtualDrawdownPct",
                state.maxVirtualDrawdownPct());
        return toJson(exposure);
    }

    private String selectedAction(List<BtcDraShadowEngine.RuntimeEvent> events) {
        if (events == null || events.isEmpty()) return "DRA_STATE_ADVANCE";
        Set<String> types = events.stream()
                .map(BtcDraShadowEngine.RuntimeEvent::eventType)
                .collect(Collectors.toSet());
        if (types.contains("VIRTUAL_SELL_FILL")) return "DRA_VIRTUAL_SELL";
        if (types.contains("VIRTUAL_ENTRY_QUEUED")) return "DRA_ENTRY_SIGNAL";
        if (types.contains("VIRTUAL_EXIT_QUEUED")) return "DRA_EXIT_SIGNAL";
        if (types.contains("VIRTUAL_BUY_FILL")) return "DRA_VIRTUAL_BUY";
        if (types.contains("VIRTUAL_ENTRY_BLOCKED")) return "DRA_ENTRY_BLOCKED";
        if (types.contains("DRA_ARMED")) return "DRA_ARMED";
        if (types.contains("DRA_ARM_EXPIRED")) return "DRA_ARM_EXPIRED";
        return "DRA_EXIT_DEFERRED";
    }

    private String eventReason(
            List<BtcDraShadowEngine.RuntimeEvent> events,
            BtcDraShadowEngine.SignalSnapshot signal) {
        if (events == null || events.isEmpty()) {
            return truncate(signal.reason(), 500);
        }
        return truncate(events.stream()
                .map(BtcDraShadowEngine.RuntimeEvent::eventType)
                .distinct()
                .collect(Collectors.joining(",")), 500);
    }

    private String decision(List<BtcDraShadowEngine.RuntimeEvent> events) {
        boolean buy = events.stream()
                .anyMatch(event ->
                        "VIRTUAL_ENTRY_QUEUED".equals(event.eventType()));
        boolean exit = events.stream()
                .anyMatch(event ->
                        "VIRTUAL_EXIT_QUEUED".equals(event.eventType())
                                || "VIRTUAL_SELL_FILL".equals(
                                event.eventType()));
        if (buy && exit) return "BUY_AND_EXIT";
        if (buy) return "BUY_SIGNAL";
        if (exit) return "EXIT";
        return "HOLD";
    }

    private boolean isSignalEvent(BtcDraShadowEngine.RuntimeEvent event) {
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
                .replace("-", "")
                .replace("/", "")
                .replace("_", "");
    }

    private String formatTime(LocalDateTime value) {
        return value == null
                ? null
                : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value);
    }

    private LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(
                    value,
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME);
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
                    "Unable to serialize DRA runtime evidence",
                    e);
        }
    }

    private String safeCode(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN_FAIL_CLOSED";
        return truncate(
                value.replaceAll("[^A-Za-z0-9_:\\->]", "_"),
                128);
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private record RestoredState(
            BtcDraShadowEngine.State state,
            int invalidRowsScanned,
            boolean currentSchemaEvidenceSeen
    ) {
    }
}
