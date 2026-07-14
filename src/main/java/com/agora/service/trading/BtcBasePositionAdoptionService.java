package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Protected OCO-to-BTC_BASE adoption flow. This service never submits a sell order. */
@Service
@RequiredArgsConstructor
@Slf4j
public class BtcBasePositionAdoptionService {

    public static final String POLICY_MODE = "BTC_BASE_ADOPTION_V1";
    private static final String BTCUSDT = "BTCUSDT";
    private static final int MAX_POSITION_IDS = 20;

    private final BtLiveSignalRepository liveSignalRepository;
    private final BtcBasePositionAdoptionStore adoptionStore;
    private final OkxTradingService okxTradingService;
    private final OcoOrderStateInspector ocoOrderStateInspector;
    private final SpotPositionCloseService spotPositionCloseService;
    private final ObjectMapper objectMapper;

    @Value("${trading.btc-base-adoption.enabled:false}")
    private boolean featureEnabled;

    @Value("${trading.btc-base-adoption.live-action-enabled:false}")
    private boolean liveActionEnabled;

    @Value("${trading.btc-base-adoption.confirm-attempts:8}")
    private int confirmAttempts;

    @Value("${trading.btc-base-adoption.confirm-interval-ms:250}")
    private long confirmIntervalMs;

    public boolean isFeatureEnabled() {
        return featureEnabled;
    }

    public boolean isLiveActionEnabled() {
        return liveActionEnabled;
    }

    public boolean isExecutionArmed() {
        return featureEnabled && liveActionEnabled;
    }

    public String previewOrExecute(String positionIds,
                                   BigDecimal expectedTotalQty,
                                   Boolean execute,
                                   String confirmText) {
        boolean executeRequested = Boolean.TRUE.equals(execute);
        List<String> blockers = new ArrayList<>();
        List<Long> ids = parsePositionIds(positionIds, blockers);
        if (ids.isEmpty()) blockers.add("POSITION_IDS_REQUIRED");
        if (ids.size() > MAX_POSITION_IDS) blockers.add("POSITION_ID_LIMIT_EXCEEDED:" + MAX_POSITION_IDS);

        List<Assessment> assessments = new ArrayList<>();
        if (blockers.isEmpty()) {
            for (Long id : ids) {
                Assessment assessment = assess(id);
                assessments.add(assessment);
                assessment.blockers().stream()
                        .map(value -> "POSITION_" + id + ":" + value)
                        .forEach(blockers::add);
            }
        }

        BigDecimal actualTotalQty = assessments.stream()
                .map(Assessment::qty)
                .filter(this::positive)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String requiredConfirmText = requiredConfirmText(ids, actualTotalQty, assessments);
        List<String> executionBlockers = new ArrayList<>();
        if (!blockers.isEmpty()) executionBlockers.add("PRECHECK_BLOCKED");
        if (executeRequested && !featureEnabled) executionBlockers.add("FEATURE_DISABLED");
        if (executeRequested && !liveActionEnabled) executionBlockers.add("LIVE_ACTION_DISABLED");
        if (executeRequested && expectedTotalQty == null) {
            executionBlockers.add("EXPECTED_TOTAL_QTY_REQUIRED");
        } else if (executeRequested && actualTotalQty.compareTo(expectedTotalQty) != 0) {
            executionBlockers.add("EXPECTED_TOTAL_QTY_MISMATCH");
        }
        if (executeRequested && !requiredConfirmText.equals(confirmText)) {
            executionBlockers.add("CONFIRM_TEXT_MISMATCH");
        }

        ObjectNode root = base(executeRequested);
        root.put("positionIdsInput", positionIds == null ? "" : positionIds);
        ArrayNode requestedIds = root.putArray("requestedPositionIds");
        ids.forEach(requestedIds::add);
        put(root, "actualTotalQty", actualTotalQty);
        put(root, "expectedTotalQty", expectedTotalQty);
        root.put("requiredConfirmText", requiredConfirmText);
        root.put("featureEnabled", featureEnabled);
        root.put("liveActionEnabled", liveActionEnabled);
        root.put("executionArmed", isExecutionArmed());

        ArrayNode positions = root.putArray("positions");
        assessments.forEach(value -> positions.add(assessmentJson(value)));
        ArrayNode blockerNode = root.putArray("blockers");
        new LinkedHashSet<>(blockers).forEach(blockerNode::add);
        ArrayNode executionBlockerNode = root.putArray("executionBlockers");
        new LinkedHashSet<>(executionBlockers).forEach(executionBlockerNode::add);

        if (!executeRequested || !executionBlockers.isEmpty()) {
            String status;
            if (executeRequested && (!featureEnabled || !liveActionEnabled)) {
                status = "EXECUTION_BLOCKED_NOT_AUTHORIZED";
            } else if (!blockers.isEmpty()) {
                status = "BLOCKED_PRECHECK";
            } else if (executeRequested) {
                status = "EXECUTION_BLOCKED_NOT_AUTHORIZED";
            } else {
                status = "READY_FOR_EXPLICIT_EXECUTION_NOT_AUTHORIZED";
            }
            root.put("status", status);
            addSafety(root, false, false, false, false);
            return pretty(root);
        }

        List<ExecutionOutcome> outcomes = new ArrayList<>();
        boolean databaseMutated = false;
        boolean cancelAttempted = false;
        for (Assessment assessment : assessments) {
            if (assessment.alreadyAdopted()) {
                outcomes.add(new ExecutionOutcome(assessment.positionId(), "ALREADY_ADOPTED",
                        "IDEMPOTENT_NO_ACTION", true, true));
                continue;
            }
            try (PositionMutationGuard.Lease lease = PositionMutationGuard.tryAcquire(
                    assessment.positionId(), POLICY_MODE)) {
                if (!lease.acquired()) {
                    outcomes.add(new ExecutionOutcome(assessment.positionId(), "BLOCKED",
                            "POSITION_MUTATION_BUSY:" + lease.activeOperation(), false, false));
                    continue;
                }
                PositionExecutionResult result = executeOne(assessment);
                outcomes.add(result.outcome());
                databaseMutated |= result.databaseMutated();
                cancelAttempted |= result.cancelAttempted();
            }
        }

        ArrayNode outcomeNode = root.putArray("executionOutcomes");
        outcomes.forEach(value -> outcomeNode.add(outcomeJson(value)));
        long completed = outcomes.stream().filter(o -> "ADOPTED".equals(o.status())).count();
        long already = outcomes.stream().filter(o -> "ALREADY_ADOPTED".equals(o.status())).count();
        long pending = outcomes.stream().filter(o -> "PENDING".equals(o.status())).count();
        long failed = outcomes.size() - completed - already - pending;
        root.put("completedCount", completed);
        root.put("alreadyAdoptedCount", already);
        root.put("pendingCount", pending);
        root.put("failedCount", Math.max(0, failed));
        long cancelConfirmedCount = outcomes.stream().filter(ExecutionOutcome::ocoCancelConfirmed).count();
        root.put("ocoCancelConfirmedCount", cancelConfirmedCount);
        boolean cohortCompleted = !outcomes.isEmpty() && pending == 0 && failed <= 0;
        root.put("status", cohortCompleted
                ? "COMPLETED_KEEP_BTC"
                : "PARTIAL_OR_PENDING_REVIEW_REQUIRED");
        addSafety(root, databaseMutated, cancelAttempted, cohortCompleted, cohortCompleted);
        return pretty(root);
    }

    private PositionExecutionResult executeOne(Assessment assessment) {
        BtcBasePositionAdoptionStore.TransitionResult pending = adoptionStore.markPending(
                assessment.positionId(), assessment.ocoAlgoId(), assessment.qty());
        if (!pending.success()) {
            return new PositionExecutionResult(new ExecutionOutcome(assessment.positionId(), "BLOCKED",
                    pending.reason(), false, false), false, false, false);
        }
        if ("ALREADY_ADOPTED".equals(pending.status())) {
            return new PositionExecutionResult(new ExecutionOutcome(assessment.positionId(), "ALREADY_ADOPTED",
                    pending.reason(), false, true), false, false, false);
        }

        OcoOrderStateInspector.Inspection current = inspectFresh(
                assessment.symbol(), assessment.ocoAlgoId());
        if (current.filled()) {
            return new PositionExecutionResult(
                    pendingOutcome(assessment, "OCO_FILLED_RECONCILIATION_REQUIRED"),
                    true, false, false);
        }
        if (!current.queryComplete()) {
            return new PositionExecutionResult(
                    pendingOutcome(assessment, "OCO_QUERY_INCOMPLETE_BEFORE_CANCEL"),
                    true, false, false);
        }

        boolean cancelAttempted = false;
        if (current.active()) {
            cancelAttempted = true;
            try {
                okxTradingService.cancelOco(assessment.symbol(), assessment.ocoAlgoId());
            } catch (Exception cancelError) {
                OcoOrderStateInspector.Inspection afterError = inspectFresh(
                        assessment.symbol(), assessment.ocoAlgoId());
                if (afterError.filled()) {
                    return new PositionExecutionResult(
                            pendingOutcome(assessment, "CANCEL_RESULT_FILLED_RECONCILIATION_REQUIRED"),
                            true, true, false);
                }
                if (!afterError.canceled()) {
                    return new PositionExecutionResult(
                            pendingOutcome(assessment,
                                    "CANCEL_NOT_CONFIRMED:" + truncate(cancelError.getMessage(), 140)),
                            true, true, false);
                }
                current = afterError;
            }
        }

        if (!current.canceled()) {
            current = awaitTerminalState(assessment.symbol(), assessment.ocoAlgoId());
        }
        if (current.filled()) {
            return new PositionExecutionResult(
                    pendingOutcome(assessment, "OCO_FILLED_DURING_CANCEL_RECONCILIATION_REQUIRED"),
                    true, cancelAttempted, false);
        }
        if (!current.queryComplete() || !current.canceled()) {
            return new PositionExecutionResult(
                    pendingOutcome(assessment, "OCO_CANCEL_CONFIRMATION_TIMEOUT"),
                    true, cancelAttempted, false);
        }

        BtcBasePositionAdoptionStore.TransitionResult finalized = adoptionStore.finalizeManaged(
                assessment.positionId(), assessment.ocoAlgoId(), assessment.qty());
        return new PositionExecutionResult(new ExecutionOutcome(assessment.positionId(), finalized.status(),
                finalized.reason(), true, "ADOPTED".equals(finalized.status())
                || "ALREADY_ADOPTED".equals(finalized.status())), true, cancelAttempted, true);
    }

    private Assessment assess(Long positionId) {
        List<String> blockers = new ArrayList<>();
        BtLiveSignal position = liveSignalRepository.findById(positionId).orElse(null);
        if (position == null) return Assessment.missing(positionId, "POSITION_NOT_FOUND");

        String symbol = normalizeSymbol(position.getSymbol());
        if (!Boolean.TRUE.equals(position.getAutoTraded())) blockers.add("NOT_AUTO_TRADED");
        if (position.getExitTime() != null) blockers.add("POSITION_ALREADY_CLOSED");
        if (!"LONG".equalsIgnoreCase(position.getSide())) blockers.add("SPOT_LONG_ONLY");
        if (!BTCUSDT.equals(symbol)) blockers.add("BTCUSDT_ONLY_V1");
        if (spotPositionCloseService.isClosing(positionId)
                || PositionMutationGuard.isBusy(positionId)) {
            blockers.add("POSITION_MUTATION_ALREADY_IN_PROGRESS");
        }

        boolean adopted = BtcBasePositionStatePolicy.isAdoptedFromOco(position);
        boolean pending = BtcBasePositionStatePolicy.isAdoptionPending(position);
        if (adopted) {
            Long originalAlgo = BtcBasePositionStatePolicy.originalOcoAlgoId(position);
            if (position.getOcoOrderListId() != null) blockers.add("ADOPTED_MARKER_WITH_ACTIVE_OCO");
            if (position.getOcoQty() != null) blockers.add("ADOPTED_MARKER_WITH_OCO_QTY");
            if (originalAlgo == null) blockers.add("ADOPTED_ORIGINAL_OCO_ID_MISSING");
            if (!positive(position.getTradedQty())) blockers.add("TRADED_QTY_MISSING");
            return new Assessment(positionId, symbol, position.getStrategyId(), position.getIntervalCode(),
                    originalAlgo, position.getTradedQty(), "ADOPTED_FROM_OCO", false,
                    true, blockers.isEmpty(), List.copyOf(blockers));
        }
        if (BtcBasePositionStatePolicy.isBtcBase(position) && !pending) {
            blockers.add("ALREADY_NATIVE_BTC_BASE");
        }

        Long algoId = pending
                ? BtcBasePositionStatePolicy.originalOcoAlgoId(position)
                : position.getOcoOrderListId();
        if (algoId == null) blockers.add("ACTIVE_OR_PENDING_OCO_REFERENCE_REQUIRED");
        if (pending && position.getOcoOrderListId() != null
                && !position.getOcoOrderListId().equals(algoId)) {
            blockers.add("OCO_REFERENCE_CHANGED_WHILE_PENDING");
        }
        BigDecimal qty = position.getTradedQty();
        if (!positive(qty)) blockers.add("TRADED_QTY_MISSING");
        if (!positive(position.getOcoQty())) blockers.add("OCO_QTY_MISSING");
        if (positive(qty) && positive(position.getOcoQty())
                && qty.compareTo(position.getOcoQty()) != 0) {
            blockers.add("TRADED_QTY_OCO_QTY_MISMATCH");
        }

        OcoOrderStateInspector.Inspection inspection = null;
        if (algoId != null && blockers.isEmpty()) {
            inspection = inspectFresh(symbol, algoId);
            if (inspection.filled()) blockers.add("OCO_ALREADY_FILLED_RECONCILIATION_REQUIRED");
            if (!inspection.queryComplete()) blockers.add("OCO_HEALTH_QUERY_FAILED");
            if (inspection.queryComplete() && !inspection.filled()
                    && !inspection.active() && !(pending && inspection.canceled())) {
                blockers.add("OCO_STATE_NOT_ACTIVE_OR_RESUMABLE:" + inspection.parentState());
            }
            BigDecimal exchangeQty = decimal(inspection.size());
            if (!positive(exchangeQty)) {
                blockers.add("OCO_EXCHANGE_QTY_MISSING");
            } else if (positive(position.getOcoQty())
                    && exchangeQty.compareTo(position.getOcoQty()) != 0) {
                blockers.add("DB_OCO_QTY_EXCHANGE_QTY_MISMATCH");
            }
        }

        String state = inspection == null ? "MISSING" : inspection.effectiveState();
        return new Assessment(positionId, symbol, position.getStrategyId(), position.getIntervalCode(),
                algoId, qty, state, pending, false, blockers.isEmpty(), List.copyOf(blockers));
    }

    private OcoOrderStateInspector.Inspection awaitTerminalState(String symbol, Long algoId) {
        OcoOrderStateInspector.Inspection last = inspectFresh(symbol, algoId);
        int attempts = Math.max(1, confirmAttempts);
        for (int attempt = 1; attempt < attempts && !last.filled() && !last.canceled(); attempt++) {
            if (confirmIntervalMs > 0) {
                try {
                    Thread.sleep(confirmIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return last;
                }
            }
            last = inspectFresh(symbol, algoId);
        }
        return last;
    }

    private OcoOrderStateInspector.Inspection inspectFresh(String symbol, Long algoId) {
        okxTradingService.invalidateAlgoOrderCache(symbol, algoId);
        return ocoOrderStateInspector.inspectSpot(symbol, algoId);
    }

    private ExecutionOutcome pendingOutcome(Assessment assessment, String reason) {
        log.warn("[BtcBaseAdoption] position={} remains pending reason={}",
                assessment.positionId(), reason);
        return new ExecutionOutcome(assessment.positionId(), "PENDING", reason, false, false);
    }

    private ObjectNode base(boolean executeRequested) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "adoptBtcBasePositionsKeepBtc");
        root.put("policyMode", POLICY_MODE);
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("executeRequested", executeRequested);
        root.put("boundary", "Explicit BTCUSDT positions only; cancel OCO and retain BTC; never sell, close, move funds, send Telegram, or alter Grid/Earn.");
        root.put("singleActiveRuntimeRequired", true);
        root.put("walletBalanceUsedForOwnership", false);
        root.put("btcRetentionSemantics", "OCO cancellation confirmed and no sell submitted by this operation; not wallet-level attribution");
        return root;
    }

    private ObjectNode assessmentJson(Assessment value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("positionId", value.positionId());
        if (value.strategyId() == null) node.putNull("strategyId"); else node.put("strategyId", value.strategyId());
        node.put("symbol", value.symbol());
        node.put("intervalCode", value.intervalCode() == null ? "UNKNOWN" : value.intervalCode());
        if (value.ocoAlgoId() == null) node.putNull("ocoAlgoId"); else node.put("ocoAlgoId", value.ocoAlgoId());
        put(node, "ownedQty", value.qty());
        node.put("ocoState", value.ocoState());
        node.put("adoptionPending", value.adoptionPending());
        node.put("alreadyAdopted", value.alreadyAdopted());
        node.put("eligible", value.eligible());
        ArrayNode blockers = node.putArray("blockers");
        value.blockers().forEach(blockers::add);
        return node;
    }

    private ObjectNode outcomeJson(ExecutionOutcome value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("positionId", value.positionId());
        node.put("status", value.status());
        node.put("reason", value.reason());
        node.put("ocoCancelConfirmed", value.ocoCancelConfirmed());
        node.put("btcRetainedConfirmed", value.btcRetainedConfirmed());
        node.put("marketSellAttempted", false);
        return node;
    }

    private void addSafety(ObjectNode root,
                           boolean databaseMutated,
                           boolean cancelAttempted,
                           boolean cancelConfirmed,
                           boolean btcRetainedConfirmed) {
        ObjectNode safety = root.putObject("safety");
        safety.put("databaseMutated", databaseMutated);
        safety.put("ocoCancelAttempted", cancelAttempted);
        safety.put("ocoCancelConfirmed", cancelConfirmed);
        safety.put("btcRetainedConfirmed", btcRetainedConfirmed);
        safety.put("marketSellAttempted", false);
        safety.put("btcSold", false);
        safety.put("positionClosed", false);
        safety.put("orderPlaced", false);
        safety.put("telegramSent", false);
        safety.put("fundsMoved", false);
    }

    private String requiredConfirmText(List<Long> ids,
                                       BigDecimal totalQty,
                                       List<Assessment> assessments) {
        String idText = ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        String algoText = assessments.stream()
                .map(value -> value.positionId() + ":" + (value.ocoAlgoId() == null ? "?" : value.ocoAlgoId()))
                .reduce((a, b) -> a + "," + b).orElse("");
        return "ADOPT_BTC_BASE_KEEP_BTC_CANCEL_OCO|POSITIONS=" + idText
                + "|QTY=" + plain(totalQty) + "|OCO=" + algoText;
    }

    private List<Long> parsePositionIds(String value, List<String> blockers) {
        if (value == null || value.isBlank()) return List.of();
        Set<Long> ids = new LinkedHashSet<>();
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            try {
                long id = Long.parseLong(trimmed);
                if (id <= 0) blockers.add("INVALID_POSITION_ID:" + trimmed);
                else ids.add(id);
            } catch (NumberFormatException e) {
                blockers.add("INVALID_POSITION_ID:" + trimmed);
            }
        }
        return List.copyOf(ids);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return "";
        return symbol.replace("-", "").replace("/", "").trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private void put(ObjectNode node, String field, BigDecimal value) {
        if (value == null) node.putNull(field); else node.put(field, value);
    }

    private String plain(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "UNKNOWN" : value;
        return value.substring(0, max);
    }

    private String pretty(ObjectNode root) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return root.toString();
        }
    }

    private record Assessment(Long positionId,
                              String symbol,
                              Long strategyId,
                              String intervalCode,
                              Long ocoAlgoId,
                              BigDecimal qty,
                              String ocoState,
                              boolean adoptionPending,
                              boolean alreadyAdopted,
                              boolean eligible,
                              List<String> blockers) {
        static Assessment missing(Long id, String blocker) {
            return new Assessment(id, "UNKNOWN", null, null, null, null,
                    "MISSING", false, false, false, List.of(blocker));
        }
    }

    private record ExecutionOutcome(Long positionId,
                                    String status,
                                    String reason,
                                    boolean ocoCancelConfirmed,
                                    boolean btcRetainedConfirmed) {
    }

    private record PositionExecutionResult(ExecutionOutcome outcome,
                                           boolean databaseMutated,
                                           boolean cancelAttempted,
                                           boolean cancelConfirmed) {
    }
}
