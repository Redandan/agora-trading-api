package com.agora.service.trading;

import com.agora.model.AutonomousExplorationLoopTransition;
import com.agora.repository.trading.AutonomousExplorationLoopTransitionRepository;
import com.agora.service.TelegramService;
import com.agora.service.TgNotificationDeduper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutonomousExplorationLoopService {

    public static final String STATE_WAIT_OPEN_POSITION = "WAIT_OPEN_POSITION";
    public static final String STATE_WAIT_OUTCOME_LABEL = "WAIT_OUTCOME_LABEL";
    public static final String STATE_WAIT_DAILY_CAP_RESET = "WAIT_DAILY_CAP_RESET";
    public static final String STATE_WAIT_SIGNAL_BUY = "WAIT_SIGNAL_BUY";
    public static final String STATE_WAIT_EV_PASS = "WAIT_EV_PASS";
    public static final String STATE_READY_TO_EXPLORE = "READY_TO_EXPLORE";
    public static final String STATE_AUTO_EXECUTE_TINY_LIVE = "AUTO_EXECUTE_TINY_LIVE";
    public static final String STATE_HALT_AND_NOTIFY = "HALT_AND_NOTIFY";

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final long DEFAULT_STRATEGY_ID = 574L;
    private static final String DEFAULT_SIDE = "LONG";

    private final AutonomousExplorationMonitorService monitorService;
    private final ExplorationPolicyService explorationPolicyService;
    private final TinyLiveExecutionService tinyLiveExecutionService;
    private final AutonomousExplorationLoopTransitionRepository transitionRepository;
    private final TelegramService telegramService;
    private final TgNotificationDeduper tgNotificationDeduper;
    private final ObjectMapper objectMapper;
    private final Environment env;
    private final AutoExplorationRolloutStateService rolloutStateService;

    @Transactional(readOnly = true)
    public Status evaluateStatus(String symbol, Long strategyId, String side) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        String normalizedSide = normalizeSide(side);
        AutonomousExplorationMonitorService.Report monitor = monitorService.evaluate(sym, sid, normalizedSide);
        ExplorationPolicyService.Decision readiness = explorationPolicyService.evaluate(sym, sid, normalizedSide);
        Optional<AutonomousExplorationLoopTransition> latest =
                transitionRepository.findFirstBySymbolAndStrategyIdAndSideOrderByGeneratedAtDesc(sym, sid, normalizedSide);

        String state = deriveState(monitor, readiness);
        String previousState = latest.map(AutonomousExplorationLoopTransition::getState).orElse("NONE");
        Instant lastTransitionAt = latest.map(t -> t.getGeneratedAt().toInstant(ZoneOffset.UTC)).orElse(null);
        boolean wouldExecuteNow = STATE_READY_TO_EXPLORE.equals(state);

        return new Status(
                Instant.now(),
                loopEnabled(),
                productionEnabled(),
                state,
                previousState,
                lastTransitionAt,
                nextCheckAt(),
                blockers(monitor, readiness),
                warnings(monitor, readiness),
                monitor.lastTinyLiveExecution(),
                latestOutcomeLabel(monitor),
                dailyCapStatus(readiness),
                explorationBudgetStatus(readiness),
                monitor.explorationReadinessSummary(),
                monitor.governanceDriftSummary(),
                wouldExecuteNow,
                false,
                monitor,
                readiness);
    }

    @Transactional(readOnly = true)
    public String getAutonomousExplorationLoopStatus(String symbol, Long strategyId, String side) {
        return evaluateStatus(symbol, strategyId, side).render();
    }

    @Transactional
    public Status runLoopOnce(String symbol, Long strategyId, String side) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        String normalizedSide = normalizeSide(side);
        Status status = evaluateStatus(sym, sid, normalizedSide);
        boolean stateChanged;
        if (STATE_READY_TO_EXPLORE.equals(status.currentState())) {
            persistForcedTransition(sym, sid, normalizedSide, status.currentState(), status.previousState(),
                    "READY_TO_EXPLORE scheduler tick observed; production execution still requires promotion gates.",
                    status.blockers(), status.warnings(), status.readiness().evidence().decisionId(),
                    parseTinyLiveExecutionId(status.lastTinyLiveExecution()));
            stateChanged = !STATE_READY_TO_EXPLORE.equals(status.previousState());
        } else {
            stateChanged = persistTransitionIfChanged(sym, sid, normalizedSide, status.currentState(), status.previousState(),
                    reason(status), status.blockers(), status.warnings(), status.readiness().evidence().decisionId(),
                    parseTinyLiveExecutionId(status.lastTinyLiveExecution()));
        }
        if (stateChanged) {
            maybeNotifyTransition(status);
        }

        if (!STATE_READY_TO_EXPLORE.equals(status.currentState())) {
            return status;
        }
        if (!productionEnabled()) {
            log.info("[AutonomousExplorationLoop] READY_TO_EXPLORE but production-enabled=false; no order sent");
            return status;
        }
        int readyTicks = consecutiveReadyTicks(sym, sid, normalizedSide);
        if (readyTicks < 3) {
            log.info("[AutonomousExplorationLoop] READY_TO_EXPLORE but consecutiveReadyTicks={} < 3; no order sent",
                    readyTicks);
            return status;
        }

        persistTransitionIfChanged(sym, sid, normalizedSide, STATE_AUTO_EXECUTE_TINY_LIVE, status.currentState(),
                "All gates passed; delegating to existing auto-token tiny-live execution path.",
                List.of(), status.warnings(), status.readiness().evidence().decisionId(),
                parseTinyLiveExecutionId(status.lastTinyLiveExecution()));
        maybeNotifyTransitionState(STATE_AUTO_EXECUTE_TINY_LIVE, status.currentState(), List.of(), status.warnings(),
                true, true);
        String receipt;
        try {
            receipt = tinyLiveExecutionService.executeAutoApprovedTinyLiveIfEligible();
        } catch (Exception e) {
            persistForcedTransition(sym, sid, normalizedSide, STATE_HALT_AND_NOTIFY, STATE_AUTO_EXECUTE_TINY_LIVE,
                    "Execution path threw before/while delegating: " + truncate(e.getMessage(), 420),
                    List.of("EXECUTION_PATH_EXCEPTION"), status.warnings(), status.readiness().evidence().decisionId(),
                    parseTinyLiveExecutionId(status.lastTinyLiveExecution()));
            maybeNotifyTransitionState(STATE_HALT_AND_NOTIFY, STATE_AUTO_EXECUTE_TINY_LIVE,
                    List.of("EXECUTION_PATH_EXCEPTION"), status.warnings(), false, true);
            throw e;
        }

        if (receipt != null && receipt.contains("CRITICAL_UNPROTECTED_TINY_LIVE")) {
            persistForcedTransition(sym, sid, normalizedSide, STATE_HALT_AND_NOTIFY, STATE_AUTO_EXECUTE_TINY_LIVE,
                    "Tiny-live execution returned CRITICAL_UNPROTECTED_TINY_LIVE.",
                    List.of("CRITICAL_UNPROTECTED_TINY_LIVE"), status.warnings(),
                    status.readiness().evidence().decisionId(), parseTinyLiveExecutionId(status.lastTinyLiveExecution()));
            maybeNotifyTransitionState(STATE_HALT_AND_NOTIFY, STATE_AUTO_EXECUTE_TINY_LIVE,
                    List.of("CRITICAL_UNPROTECTED_TINY_LIVE"), status.warnings(), false, true);
            return evaluateStatus(sym, sid, normalizedSide);
        }
        if (receipt != null && receipt.contains("orderSent=true")) {
            persistForcedTransition(sym, sid, normalizedSide, STATE_WAIT_OPEN_POSITION, STATE_AUTO_EXECUTE_TINY_LIVE,
                    "Existing execution path sent one tiny-live order; waiting for OCO-protected position outcome.",
                    List.of("OPEN_TINY_LIVE_POSITION_PENDING_RECONCILE"), status.warnings(),
                    status.readiness().evidence().decisionId(), parseTinyLiveExecutionId(status.lastTinyLiveExecution()));
            maybeNotifyTransitionState(STATE_AUTO_EXECUTE_TINY_LIVE, STATE_READY_TO_EXPLORE,
                    List.of(), status.warnings(), false, true);
            return evaluateStatus(sym, sid, normalizedSide);
        }

        Status after = evaluateStatus(sym, sid, normalizedSide);
        persistTransitionIfChanged(sym, sid, normalizedSide, after.currentState(), STATE_AUTO_EXECUTE_TINY_LIVE,
                "Execution path rechecked gates and did not send an order.",
                after.blockers(), after.warnings(), after.readiness().evidence().decisionId(),
                parseTinyLiveExecutionId(after.lastTinyLiveExecution()));
        return after;
    }

    private String deriveState(AutonomousExplorationMonitorService.Report monitor,
                               ExplorationPolicyService.Decision readiness) {
        if ("WAIT_OCO_HEALTH".equals(monitor.monitorStatus())
                || "ERROR_NEEDS_OPERATOR".equals(monitor.monitorStatus())
                || containsAny(monitor.blockers(), "OCO_HEALTH_ABNORMAL", "CRITICAL_UNPROTECTED", "SYSTEM_HEALTH_CRITICAL")) {
            return STATE_HALT_AND_NOTIFY;
        }
        if ((readiness.openTinyLivePositions() > 0 && readiness.openTinyLiveWait().blocksNewExploration())
                || containsAny(monitor.blockers(), "OPEN_TINY_LIVE_POSITION")) {
            return STATE_WAIT_OPEN_POSITION;
        }
        if (shouldWaitForLastTinyLiveOutcome(monitor, readiness)) {
            return STATE_WAIT_OUTCOME_LABEL;
        }
        if (readiness.ordersToday() > 0 || containsAny(monitor.blockers(), "DAILY_EXPLORATION_CAP_REACHED")) {
            return STATE_WAIT_DAILY_CAP_RESET;
        }
        if (readiness.eligible() && monitor.blockers().isEmpty()) {
            return STATE_READY_TO_EXPLORE;
        }
        if (containsAny(monitor.blockers(), "NO_CURRENT_BUY_CANDIDATE")) {
            return STATE_WAIT_SIGNAL_BUY;
        }
        return STATE_WAIT_EV_PASS;
    }

    private boolean hasRecentExecution(AutonomousExplorationMonitorService.Report monitor) {
        String latest = monitor.lastTinyLiveExecution();
        return latest != null && latest.startsWith("1. #");
    }

    private boolean hasUnresolvedOutcomeLabels(AutonomousExplorationMonitorService.Report monitor) {
        String raw = monitor.outcomeLabelRaw();
        return intValue(raw, "unresolvedCandidates") > 0;
    }

    private boolean shouldWaitForLastTinyLiveOutcome(AutonomousExplorationMonitorService.Report monitor,
                                                     ExplorationPolicyService.Decision readiness) {
        if (!hasRecentExecution(monitor) || !hasMatureUnresolvedOutcomeLabels(monitor)) {
            return false;
        }
        if (readiness != null && readiness.openTinyLiveWait() != null
                && readiness.openTinyLiveWait().openPositionCount() > 0
                && readiness.openTinyLiveWait().staleSlotReleaseEligible()) {
            return false;
        }
        return true;
    }

    private boolean hasMatureUnresolvedOutcomeLabels(AutonomousExplorationMonitorService.Report monitor) {
        String raw = monitor.outcomeLabelRaw();
        int unresolved = intValue(raw, "unresolvedCandidates");
        if (unresolved <= 0) {
            return false;
        }
        int pendingForward = intValue(raw, "pendingForwardWindowCount");
        if (unresolved <= pendingForward && percentValue(raw, "matureLabelCoveragePct") >= 99.9) {
            return false;
        }
        return true;
    }

    private List<String> blockers(AutonomousExplorationMonitorService.Report monitor,
                                  ExplorationPolicyService.Decision readiness) {
        List<String> values = new ArrayList<>();
        values.addAll(readiness.blockers());
        values.addAll(monitor.blockers());
        return values.stream().distinct().toList();
    }

    private List<String> warnings(AutonomousExplorationMonitorService.Report monitor,
                                  ExplorationPolicyService.Decision readiness) {
        List<String> values = new ArrayList<>();
        values.addAll(readiness.warnings());
        values.addAll(monitor.warnings());
        if (hasRecentExecution(monitor)
                && hasUnresolvedOutcomeLabels(monitor)
                && !hasMatureUnresolvedOutcomeLabels(monitor)) {
            values.add("OUTCOME_LABELS_PENDING_FORWARD_WINDOW_NOT_LOOP_BLOCKING");
        }
        if (hasRecentExecution(monitor)
                && hasMatureUnresolvedOutcomeLabels(monitor)
                && readiness.openTinyLiveWait() != null
                && readiness.openTinyLiveWait().openPositionCount() > 0
                && readiness.openTinyLiveWait().staleSlotReleaseEligible()) {
            values.add("STALE_TINY_LIVE_SLOT_RELEASE_SKIPS_OUTCOME_LABEL_WAIT");
        }
        return values.stream().distinct().toList();
    }

    private String dailyCapStatus(ExplorationPolicyService.Decision readiness) {
        long maxOrders = rolloutStateService.effectiveMaxOrdersPerDay(
                readiness.preview().symbol(), readiness.preview().strategyId(), readiness.preview().side());
        return "ordersToday=%d maxOrdersPerDay=%d available=%s"
                .formatted(readiness.ordersToday(), maxOrders, readiness.ordersToday() < maxOrders);
    }

    private String explorationBudgetStatus(ExplorationPolicyService.Decision readiness) {
        return "budgetRemaining=%s maxDailyLoss=%s expectedLossIfWrong=%s available=%s"
                .formatted(readiness.explorationBudgetRemaining(), readiness.maxDailyExplorationLossUsdt(),
                        readiness.expectedLossIfWrong(),
                        readiness.explorationBudgetRemaining().compareTo(readiness.expectedLossIfWrong()) >= 0);
    }

    private String latestOutcomeLabel(AutonomousExplorationMonitorService.Report monitor) {
        String raw = monitor.outcomeLabelRaw();
        String labels = value(raw, "rowsByCorrectnessLabel");
        return labels == null || labels.equals("N/A") ? monitor.outcomeLabelSummary() : labels;
    }

    private boolean persistTransitionIfChanged(String symbol,
                                               long strategyId,
                                               String side,
                                               String state,
                                               String previousState,
                                               String reason,
                                               List<String> blockers,
                                               List<String> warnings,
                                               Long decisionId,
                                               Long tinyLiveExecutionId) {
        Optional<AutonomousExplorationLoopTransition> latest =
                transitionRepository.findFirstBySymbolAndStrategyIdAndSideOrderByGeneratedAtDesc(symbol, strategyId, side);
        if (latest.isPresent() && state.equals(latest.get().getState())) {
            return false;
        }
        persistForcedTransition(symbol, strategyId, side, state,
                latest.map(AutonomousExplorationLoopTransition::getState).orElse(previousState),
                reason, blockers, warnings, decisionId, tinyLiveExecutionId);
        return true;
    }

    @Transactional(readOnly = true)
    public int consecutiveReadyTicks(String symbol, Long strategyId, String side) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        String normalizedSide = normalizeSide(side);
        List<AutonomousExplorationLoopTransition> rows =
                transitionRepository.findRecent(sym, sid, normalizedSide, PageRequest.of(0, 10));
        int count = 0;
        for (AutonomousExplorationLoopTransition row : rows) {
            if (STATE_READY_TO_EXPLORE.equals(row.getState())) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private void persistForcedTransition(String symbol,
                                         long strategyId,
                                         String side,
                                         String state,
                                         String previousState,
                                         String reason,
                                         List<String> blockers,
                                         List<String> warnings,
                                         Long decisionId,
                                         Long tinyLiveExecutionId) {
        AutonomousExplorationLoopTransition row = new AutonomousExplorationLoopTransition();
        row.setGeneratedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setSymbol(symbol);
        row.setStrategyId(strategyId);
        row.setSide(side);
        row.setState(state);
        row.setPreviousState(previousState);
        row.setReason(truncate(reason, 500));
        row.setBlockersJson(json(blockers));
        row.setWarningsJson(json(warnings));
        row.setDecisionId(decisionId);
        row.setTinyLiveExecutionId(tinyLiveExecutionId);
        transitionRepository.save(row);
    }

    private void maybeNotifyTransition(Status status) {
        if (!telegramEnabled()) {
            return;
        }
        if (!isNotifiableState(status.currentState())) {
            return;
        }
        String key = "AutonomousExplorationLoop:" + status.currentState();
        if (tgNotificationDeduper.shouldSend(key, Duration.ofHours(6), severity(status.currentState()))) {
            telegramService.sendAlert(status.notificationMessage(), false, key, level(status.currentState()));
        }
    }

    private void maybeNotifyTransitionState(String state,
                                            String previousState,
                                            List<String> blockers,
                                            List<String> warnings,
                                            boolean wouldExecuteNow,
                                            boolean productionEnabled) {
        if (!telegramEnabled() || !isNotifiableState(state)) {
            return;
        }
        String key = "AutonomousExplorationLoop:" + state;
        if (tgNotificationDeduper.shouldSend(key, Duration.ofHours(6), severity(state))) {
            String message = "Autonomous Exploration Loop state changed\n"
                    + "state=" + state + "\n"
                    + "previousState=" + previousState + "\n"
                    + "blockers=" + blockers + "\n"
                    + "warnings=" + warnings + "\n"
                    + "wouldExecuteNow=" + wouldExecuteNow + "\n"
                    + "productionEnabled=" + productionEnabled;
            telegramService.sendAlert(message, false, key, level(state));
        }
    }

    private boolean isNotifiableState(String state) {
        return STATE_READY_TO_EXPLORE.equals(state)
                || STATE_AUTO_EXECUTE_TINY_LIVE.equals(state)
                || STATE_HALT_AND_NOTIFY.equals(state)
                || containsText(state, "OCO");
    }

    private TgNotificationDeduper.Severity severity(String state) {
        return STATE_HALT_AND_NOTIFY.equals(state) ? TgNotificationDeduper.Severity.WARN : TgNotificationDeduper.Severity.FYI;
    }

    private String level(String state) {
        return STATE_HALT_AND_NOTIFY.equals(state) ? "WARN" : "INFO";
    }

    private String reason(Status status) {
        return switch (status.currentState()) {
            case STATE_WAIT_OPEN_POSITION -> "Open tiny-live position exists; wait for close/OCO reconciliation.";
            case STATE_WAIT_OUTCOME_LABEL -> "Last tiny-live execution has not reached a mature outcome label.";
            case STATE_WAIT_DAILY_CAP_RESET -> "Daily exploration cap is reached; wait for reset.";
            case STATE_WAIT_SIGNAL_BUY -> "No current BUY candidate; wait for the strategy to produce a fresh candidate.";
            case STATE_WAIT_EV_PASS -> "Readiness gates are not all passing; wait for next candidate.";
            case STATE_READY_TO_EXPLORE -> "All exploration gates currently pass; ready to delegate to auto-token path.";
            case STATE_HALT_AND_NOTIFY -> "Safety monitor found an operator-required condition.";
            default -> status.monitor().nextRecommendedAction();
        };
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private boolean loopEnabled() {
        return rolloutStateService.effectiveLoopEnabled(DEFAULT_SYMBOL, DEFAULT_STRATEGY_ID, DEFAULT_SIDE);
    }

    private boolean productionEnabled() {
        return rolloutStateService.effectiveProductionEnabled(DEFAULT_SYMBOL, DEFAULT_STRATEGY_ID, DEFAULT_SIDE);
    }

    private boolean telegramEnabled() {
        return Boolean.parseBoolean(env.getProperty("trading.exploration.loop.telegram.enabled", "false"));
    }

    private Instant nextCheckAt() {
        long delay = longProperty("trading.exploration.loop.fixed-delay-ms", 60000L);
        return Instant.now().plusMillis(Math.max(1000L, delay));
    }

    private long longProperty(String key, long fallback) {
        try {
            return Long.parseLong(env.getProperty(key, String.valueOf(fallback)));
        } catch (Exception e) {
            return fallback;
        }
    }

    private Long parseTinyLiveExecutionId(String summary) {
        if (summary == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("#(\\d+)").matcher(summary);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    private int intValue(String text, String key) {
        String value = value(text, key);
        try {
            return Integer.parseInt(value.replaceAll("[^0-9-]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private double percentValue(String text, String key) {
        String value = value(text, key);
        try {
            return Double.parseDouble(value.replace("%", "").replace("+", "").trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String value(String text, String key) {
        if (text == null) {
            return "N/A";
        }
        Pattern pattern = Pattern.compile("(?m)^" + Pattern.quote(key) + "=([^\\n]+)");
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "N/A";
    }

    private boolean containsAny(List<String> values, String... needles) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            for (String needle : needles) {
                if (containsText(value, needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsText(String value, String needle) {
        return value != null && value.toUpperCase(Locale.ROOT).contains(needle.toUpperCase(Locale.ROOT));
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? DEFAULT_SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSide(String side) {
        if (side == null || side.isBlank()) return DEFAULT_SIDE;
        String upper = side.trim().toUpperCase(Locale.ROOT);
        return "BUY".equals(upper) ? DEFAULT_SIDE : upper;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record Status(Instant generatedAt,
                         boolean loopEnabled,
                         boolean productionEnabled,
                         String currentState,
                         String previousState,
                         Instant lastTransitionAt,
                         Instant nextCheckAt,
                         List<String> blockers,
                         List<String> warnings,
                         String lastTinyLiveExecution,
                         String lastOutcomeLabel,
                         String dailyCapStatus,
                         String explorationBudgetStatus,
                         String readinessSummary,
                         String governanceDriftSummary,
                         boolean wouldExecuteNow,
                         boolean orderSent,
                         AutonomousExplorationMonitorService.Report monitor,
                         ExplorationPolicyService.Decision readiness) {
        public String render() {
            return """
                    === Autonomous Exploration Loop v0 ===
                    boundary=READ_ONLY; no order/OCO/strategy/grid/fund/Earn behavior changed.
                    generatedAt=%s
                    loopEnabled=%s
                    productionEnabled=%s
                    currentState=%s
                    previousState=%s
                    lastTransitionAt=%s
                    nextCheckAt=%s
                    blockers=%s
                    warnings=%s
                    lastTinyLiveExecution=%s
                    openTinyLiveWaitSummary=%s
                    lastOutcomeLabel=%s
                    dailyCapStatus=%s
                    explorationBudgetStatus=%s
                    readinessSummary=%s
                    governanceDriftSummary=%s
                    wouldExecuteNow=%s
                    orderSent=false
                    """.formatted(generatedAt, loopEnabled, productionEnabled, currentState, previousState,
                    lastTransitionAt == null ? "N/A" : lastTransitionAt, nextCheckAt, blockers, warnings,
                    lastTinyLiveExecution, openTinyLiveWaitSummary(),
                    lastOutcomeLabel, dailyCapStatus, explorationBudgetStatus,
                    readinessSummary, governanceDriftSummary, wouldExecuteNow);
        }

        private String openTinyLiveWaitSummary() {
            if (readiness == null || readiness.openTinyLiveWait() == null) {
                return "N/A";
            }
            return readiness.openTinyLiveWait().renderCompact();
        }

        public String notificationMessage() {
            return "Autonomous Exploration Loop state changed\n"
                    + "state=" + currentState + "\n"
                    + "previousState=" + previousState + "\n"
                    + "blockers=" + blockers + "\n"
                    + "warnings=" + warnings + "\n"
                    + "wouldExecuteNow=" + wouldExecuteNow + "\n"
                    + "productionEnabled=" + productionEnabled;
        }
    }
}
