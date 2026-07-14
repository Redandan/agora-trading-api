package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only BTC base position manager preview for explicitly selected OCO positions.
 *
 * <p>V1 does not persist adoption and never cancels or modifies an OCO. Position
 * ownership comes only from {@link BtLiveSignal}; wallet BTC, Grid inventory,
 * manual holdings, and unrelated positions are deliberately excluded.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BtcBasePositionManagerService {

    public static final String POLICY_MODE = "BTC_BASE_POSITION_MANAGER_V1";
    public static final String STAGE = "READ_ONLY_SHADOW_PREVIEW";

    private static final String BTCUSDT = "BTCUSDT";
    private static final String BTC_BASE_PREFIX = "LOCAL_TRADINGVIEW_BTC_BASE:";
    private static final BigDecimal FEE_RATE_PER_SIDE = new BigDecimal("0.001");
    private static final BigDecimal QTY_TOLERANCE = new BigDecimal("0.00000001");
    private static final int DEFAULT_HORIZON_HOURS = 168;
    private static final int MAX_POSITION_IDS = 20;
    private static final long STALE_NEGATIVE_HOURS = 72;
    private static final int RECOVERY_REVIEW_TTL_HOURS = 24;

    private final BtLiveSignalRepository liveSignalRepository;
    private final OkxTradingService okxTradingService;
    private final OcoOutcomeAnalysisService ocoOutcomeAnalysisService;
    private final SpotPositionCloseService spotPositionCloseService;
    private final OcoOrderStateInspector ocoOrderStateInspector;
    private final ObjectMapper objectMapper;

    public String status(String symbol) {
        String normalized = normalizeSymbol(symbol);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ObjectNode root = base("getBtcBasePositionManagerStatus", now);
        root.put("symbol", normalized);

        ArrayNode blockers = root.putArray("blockers");
        if (!BTCUSDT.equals(normalized)) {
            blockers.add("BTCUSDT_ONLY_V1");
        }

        List<BtLiveSignal> open = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull().stream()
                .filter(p -> normalized.equalsIgnoreCase(p.getSymbol()))
                .toList();
        List<BtLiveSignal> btcBase = open.stream().filter(this::isExistingBtcBaseSlice).toList();
        List<BtLiveSignal> ocoCandidates = open.stream()
                .filter(p -> !isExistingBtcBaseSlice(p))
                .filter(this::isOpenSpotLong)
                .filter(p -> p.getOcoOrderListId() != null)
                .toList();

        ObjectNode inventory = root.putObject("inventory");
        inventory.put("openAutoTradedPositionCount", open.size());
        inventory.put("existingBtcBaseNoOcoSliceCount", btcBase.size());
        inventory.put("recordedOcoCandidateCount", ocoCandidates.size());
        inventory.put("walletBalanceUsed", false);
        inventory.put("gridInventoryIncluded", false);
        inventory.put("manualBtcIncluded", false);
        inventory.put("ownershipSource", "explicit bt_live_signal rows only");

        ArrayNode existingIds = inventory.putArray("existingBtcBaseSliceIds");
        btcBase.stream().map(BtLiveSignal::getId).forEach(existingIds::add);
        ArrayNode candidateIds = inventory.putArray("recordedOcoCandidateIds");
        ocoCandidates.stream().map(BtLiveSignal::getId).forEach(candidateIds::add);

        root.put("persistedAdoptionImplemented", false);
        root.put("persistedManagedPositionCount", 0);
        root.put("explicitPositionIdsRequiredForPreview", true);
        root.put("liveActionsImplemented", false);
        root.put("operatorAction", blockers.isEmpty()
                ? "Use previewBtcBasePositionAdoption with explicit positionIds; no position is adopted by this status call."
                : "Do not use V1 outside BTCUSDT.");
        addSafety(root);
        return pretty(root);
    }

    public String previewAdoption(String positionIds, Integer horizonHours) {
        return preview("previewBtcBasePositionAdoption", "ADOPTION", positionIds, horizonHours);
    }

    public String previewDisposition(String positionIds, Integer horizonHours) {
        return preview("previewBtcBasePositionDisposition", "DISPOSITION", positionIds, horizonHours);
    }

    private String preview(String tool,
                           String focus,
                           String positionIds,
                           Integer horizonHours) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int hours = horizonHours != null && horizonHours > 0 ? horizonHours : DEFAULT_HORIZON_HOURS;
        ObjectNode root = base(tool, now);
        root.put("focus", focus);
        root.put("horizonHours", hours);
        root.put("horizonSemantics", "informational only; OCO EV MVP does not model time-to-resolution");
        root.put("positionIdsInput", positionIds == null ? "" : positionIds);

        List<String> cohortBlockers = new ArrayList<>();
        List<Long> ids = parsePositionIds(positionIds, cohortBlockers);
        ArrayNode requested = root.putArray("requestedPositionIds");
        ids.forEach(requested::add);

        if (ids.isEmpty()) {
            cohortBlockers.add("POSITION_IDS_REQUIRED");
        }
        if (ids.size() > MAX_POSITION_IDS) {
            cohortBlockers.add("POSITION_ID_LIMIT_EXCEEDED:" + MAX_POSITION_IDS);
        }

        List<PositionAssessment> assessments = new ArrayList<>();
        if (cohortBlockers.isEmpty()) {
            for (Long id : ids) {
                assessments.add(assess(id, hours, now));
            }
        }

        ArrayNode positions = root.putArray("positions");
        for (PositionAssessment assessment : assessments) {
            positions.add(toJson(assessment));
            assessment.blockers().stream()
                    .map(blocker -> "POSITION_" + assessment.positionId() + ":" + blocker)
                    .forEach(cohortBlockers::add);
        }

        Aggregate aggregate = aggregate(assessments);
        ObjectNode aggregateNode = root.putObject("aggregate");
        put(aggregateNode, "ownedQty", aggregate.ownedQty());
        put(aggregateNode, "costUsdt", aggregate.costUsdt());
        put(aggregateNode, "currentValueUsdt", aggregate.currentValueUsdt());
        put(aggregateNode, "weightedEntry", aggregate.weightedEntry());
        put(aggregateNode, "estimatedFeeAdjustedBreakEven", aggregate.feeAdjustedBreakEven());
        put(aggregateNode, "grossUnrealizedPnlUsdt", aggregate.unrealizedPnlUsdt());
        put(aggregateNode, "estimatedExitNowNetPnlUsdt", aggregate.exitNowNetPnlUsdt());
        put(aggregateNode, "originalBracketMaxLossUsdt", aggregate.originalMaxLossUsdt());
        put(aggregateNode, "remainingLossToRecordedSlUsdt", aggregate.remainingLossUsdt());
        put(aggregateNode, "remainingUpsideToRecordedTpUsdt", aggregate.remainingUpsideUsdt());
        put(aggregateNode, "heuristicCombinedEvUsdt", aggregate.combinedEvUsdt());
        aggregateNode.put("ownershipComplete", aggregate.ownershipComplete());
        aggregateNode.put("currentPriceComplete", aggregate.currentPriceComplete());
        aggregateNode.put("bracketComplete", aggregate.bracketComplete());
        aggregateNode.put("heuristicEvComplete", aggregate.heuristicEvComplete());
        aggregateNode.put("estimatedFeeRatePerSide", FEE_RATE_PER_SIDE);
        aggregateNode.put("feeEvidenceExact", false);
        aggregateNode.put("walletBalanceUsed", false);
        aggregateNode.put("gridInventoryIncluded", false);
        aggregateNode.put("manualBtcIncluded", false);

        boolean eligible = cohortBlockers.isEmpty()
                && assessments.size() == ids.size()
                && assessments.stream().allMatch(PositionAssessment::eligible);
        String recommendation = recommendation(eligible, assessments, aggregate);
        ObjectNode decision = root.putObject("decision");
        decision.put("adoptionEligible", eligible);
        decision.put("adoptionPersisted", false);
        decision.put("recommendedDisposition", recommendation);
        decision.put("recoveryReviewTtlHours", RECOVERY_REVIEW_TTL_HOURS);
        decision.put("recoveryTtlIsProfitEdge", false);
        decision.put("requiresOperatorApproval", true);
        decision.put("wouldCancelOco", false);
        decision.put("wouldPlaceOrder", false);
        decision.put("wouldClosePosition", false);
        decision.put("futureExecutionPath", "SpotPositionCloseService.closeAtMarket after a separate protected live authorization");
        decision.put("reason", recommendationReason(recommendation));

        ArrayNode blockers = root.putArray("blockers");
        new LinkedHashSet<>(cohortBlockers).forEach(blockers::add);
        ArrayNode warnings = root.putArray("warnings");
        warnings.add("HEURISTIC_EV_NOT_STATISTICALLY_CALIBRATED");
        warnings.add("OCO_REMAINS_ACTIVE_DURING_PREVIEW");
        warnings.add("BTC_BASE_MANAGER_MUST_NOT_HIDE_OR_INDEFINITELY_HOLD_FAILED_TRADES");
        warnings.add("RECOVERY_REVIEW_TTL_IS_RISK_GOVERNANCE_NOT_A_PROVEN_PROFIT_EDGE");

        root.put("verdict", eligible ? recommendation : "BLOCKED_FAIL_CLOSED");
        addSafety(root);
        return pretty(root);
    }

    private PositionAssessment assess(Long positionId,
                                      int horizonHours,
                                      LocalDateTime now) {
        List<String> blockers = new ArrayList<>();
        BtLiveSignal position = liveSignalRepository.findById(positionId).orElse(null);
        if (position == null) {
            blockers.add("POSITION_NOT_FOUND");
            return PositionAssessment.missing(positionId, blockers);
        }

        String symbol = normalizeSymbol(position.getSymbol());
        String side = position.getSide() == null ? "LONG" : position.getSide().toUpperCase(Locale.ROOT);
        if (!Boolean.TRUE.equals(position.getAutoTraded())) blockers.add("NOT_AUTO_TRADED");
        if (position.getExitTime() != null) blockers.add("POSITION_ALREADY_CLOSED");
        if (!"LONG".equals(side)) blockers.add("SPOT_LONG_ONLY");
        if (!BTCUSDT.equals(symbol)) blockers.add("BTCUSDT_ONLY_V1");
        if (isExistingBtcBaseSlice(position)) blockers.add("ALREADY_BTC_BASE_NO_OCO_SLICE");
        if (spotPositionCloseService.isClosing(positionId)) blockers.add("CLOSE_ALREADY_IN_PROGRESS");

        BigDecimal entry = positive(position.getActualEntryPrice())
                ? position.getActualEntryPrice() : position.getEntryPrice();
        BigDecimal tradedQty = position.getTradedQty();
        BigDecimal ocoQty = position.getOcoQty();
        BigDecimal tp = position.getSuggestedTp();
        BigDecimal sl = position.getSuggestedSl();
        if (!positive(entry)) blockers.add("ENTRY_PRICE_MISSING");
        if (!positive(tradedQty)) blockers.add("TRADED_QTY_MISSING");
        if (!positive(ocoQty)) blockers.add("OCO_QTY_MISSING");
        if (!positive(tp) || !positive(sl)) blockers.add("OCO_BRACKET_MISSING");
        if (position.getOcoOrderListId() == null) blockers.add("ACTIVE_OCO_REQUIRED");

        boolean ownershipExact = positive(tradedQty) && positive(ocoQty)
                && tradedQty.subtract(ocoQty).abs().compareTo(QTY_TOLERANCE) <= 0;
        if (!ownershipExact) blockers.add("TRADED_QTY_OCO_QTY_MISMATCH");
        BigDecimal ownedQty = ownershipExact ? ocoQty : null;

        OcoState ocoState = position.getOcoOrderListId() == null
                ? new OcoState("MISSING", false, "ACTIVE_OCO_REQUIRED")
                : inspectOco(position);
        if (!ocoState.healthy() && !blockers.contains(ocoState.blocker())) {
            blockers.add(ocoState.blocker());
        }

        BigDecimal current = null;
        try {
            current = okxTradingService.getLastPrice(symbol);
            if (!positive(current)) blockers.add("CURRENT_PRICE_INVALID");
        } catch (Exception e) {
            blockers.add("CURRENT_PRICE_QUERY_FAILED");
        }

        Double pTpFirst = null;
        BigDecimal evUsdt = null;
        String suggestion = "UNAVAILABLE";
        if (position.getOcoOrderListId() != null && positive(entry) && positive(ownedQty)
                && positive(tp) && positive(sl)) {
            try {
                OcoOutcomeAnalysisService.Outcome outcome = ocoOutcomeAnalysisService.analyze(positionId, horizonHours);
                pTpFirst = outcome.pTpFirstAdjusted();
                evUsdt = BigDecimal.valueOf(outcome.evUsdt());
                suggestion = outcome.suggestion();
            } catch (Exception e) {
                blockers.add("HEURISTIC_EV_ANALYSIS_FAILED");
            }
        }

        long ageHours = position.getCreatedAt() == null
                ? 0L : Math.max(0L, Duration.between(position.getCreatedAt(), now).toHours());
        BigDecimal cost = multiply(entry, ownedQty);
        BigDecimal currentValue = multiply(current, ownedQty);
        BigDecimal pnl = currentValue != null && cost != null ? currentValue.subtract(cost) : null;
        BigDecimal pnlPct = pnl != null && positive(cost)
                ? pnl.multiply(new BigDecimal("100")).divide(cost, 6, RoundingMode.HALF_UP) : null;
        String disposition = perPositionDisposition(evUsdt, suggestion, ageHours);

        return new PositionAssessment(
                positionId,
                position.getStrategyId(),
                symbol,
                position.getIntervalCode(),
                side,
                signalSource(position),
                ageHours,
                entry,
                current,
                tradedQty,
                ocoQty,
                ownedQty,
                cost,
                currentValue,
                pnl,
                pnlPct,
                tp,
                sl,
                position.getOcoOrderListId(),
                ocoState.state(),
                ocoState.healthy(),
                ownershipExact,
                pTpFirst,
                evUsdt,
                suggestion,
                disposition,
                blockers.isEmpty(),
                List.copyOf(blockers));
    }

    private OcoState inspectOco(BtLiveSignal position) {
        OcoOrderStateInspector.Inspection inspection = ocoOrderStateInspector.inspectSpot(
                position.getSymbol(), position.getOcoOrderListId());
        if (inspection.filled()) {
            String blocker = inspection.filledChildOrderId() == null
                    ? "OCO_ALREADY_FILLED_RECONCILIATION_REQUIRED"
                    : "OCO_CHILD_FILLED_RECONCILIATION_REQUIRED";
            return new OcoState(inspection.parentState(), false, blocker);
        }
        if (!inspection.queryComplete()) {
            log.warn("[BtcBasePositionManager] OCO query incomplete position={} algoId={} errors={}",
                    position.getId(), position.getOcoOrderListId(), inspection.errors());
            return new OcoState(inspection.parentState(), false, "OCO_HEALTH_QUERY_FAILED");
        }
        if (!inspection.active()) {
            return new OcoState(inspection.parentState(), false, "OCO_STATE_NOT_ACTIVE");
        }
        return new OcoState(inspection.parentState(), true, "");
    }

    private Aggregate aggregate(List<PositionAssessment> assessments) {
        boolean ownershipComplete = !assessments.isEmpty() && assessments.stream()
                .allMatch(p -> positive(p.ownedQty()) && positive(p.entry()));
        boolean currentPriceComplete = ownershipComplete && assessments.stream()
                .allMatch(p -> positive(p.current()));
        boolean bracketComplete = currentPriceComplete && assessments.stream()
                .allMatch(p -> positive(p.tp()) && positive(p.sl()));
        boolean heuristicEvComplete = !assessments.isEmpty() && assessments.stream()
                .allMatch(p -> p.evUsdt() != null);
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal currentValue = BigDecimal.ZERO;
        BigDecimal originalMaxLoss = BigDecimal.ZERO;
        BigDecimal remainingLoss = BigDecimal.ZERO;
        BigDecimal remainingUpside = BigDecimal.ZERO;
        BigDecimal combinedEv = BigDecimal.ZERO;
        boolean hasEv = false;

        for (PositionAssessment p : assessments) {
            if (!positive(p.ownedQty()) || !positive(p.entry())) continue;
            qty = qty.add(p.ownedQty());
            cost = cost.add(p.entry().multiply(p.ownedQty()));
            if (positive(p.current())) {
                currentValue = currentValue.add(p.current().multiply(p.ownedQty()));
            }
            if (positive(p.sl())) {
                originalMaxLoss = originalMaxLoss.add(p.entry().subtract(p.sl()).max(BigDecimal.ZERO)
                        .multiply(p.ownedQty()));
                if (positive(p.current())) {
                    remainingLoss = remainingLoss.add(p.current().subtract(p.sl()).max(BigDecimal.ZERO)
                            .multiply(p.ownedQty()));
                }
            }
            if (positive(p.tp()) && positive(p.current())) {
                remainingUpside = remainingUpside.add(p.tp().subtract(p.current()).max(BigDecimal.ZERO)
                        .multiply(p.ownedQty()));
            }
            if (p.evUsdt() != null) {
                combinedEv = combinedEv.add(p.evUsdt());
                hasEv = true;
            }
        }

        BigDecimal ownedQty = ownershipComplete ? qty : null;
        BigDecimal exactCost = ownershipComplete ? cost : null;
        BigDecimal exactCurrentValue = currentPriceComplete ? currentValue : null;
        BigDecimal weightedEntry = ownershipComplete && positive(qty)
                ? cost.divide(qty, 8, RoundingMode.HALF_UP) : null;
        BigDecimal breakEven = ownershipComplete && positive(qty)
                ? cost.multiply(BigDecimal.ONE.add(FEE_RATE_PER_SIDE))
                .divide(qty.multiply(BigDecimal.ONE.subtract(FEE_RATE_PER_SIDE)), 8, RoundingMode.HALF_UP)
                : null;
        BigDecimal unrealized = currentPriceComplete ? currentValue.subtract(cost) : null;
        BigDecimal estimatedEntryFee = cost.multiply(FEE_RATE_PER_SIDE);
        BigDecimal estimatedExitFee = currentValue.multiply(FEE_RATE_PER_SIDE);
        BigDecimal exitNowNet = currentPriceComplete
                ? currentValue.subtract(cost).subtract(estimatedEntryFee).subtract(estimatedExitFee) : null;

        return new Aggregate(ownedQty, exactCost, exactCurrentValue, weightedEntry, breakEven, unrealized,
                exitNowNet,
                ownershipComplete && bracketComplete ? originalMaxLoss : null,
                bracketComplete ? remainingLoss : null,
                bracketComplete ? remainingUpside : null,
                heuristicEvComplete && hasEv ? combinedEv : null,
                ownershipComplete, currentPriceComplete, bracketComplete, heuristicEvComplete);
    }

    private String recommendation(boolean eligible,
                                  List<PositionAssessment> assessments,
                                  Aggregate aggregate) {
        if (!eligible) return "DO_NOT_ADOPT";
        boolean anyRetire = assessments.stream()
                .anyMatch(p -> "RETIRE_CLOSE_REVIEW".equals(p.disposition()));
        if (anyRetire) return "RETIRE_CLOSE_REVIEW";
        if (aggregate.combinedEvUsdt() != null && aggregate.combinedEvUsdt().signum() < 0) {
            return "RECOVERY_EXIT_REVIEW";
        }
        return "KEEP_OCO_UNDER_MANAGER_REVIEW";
    }

    private String perPositionDisposition(BigDecimal evUsdt, String suggestion, long ageHours) {
        if ("CLOSE".equalsIgnoreCase(suggestion)) return "RETIRE_CLOSE_REVIEW";
        if (evUsdt != null && evUsdt.signum() < 0 && ageHours >= STALE_NEGATIVE_HOURS) {
            return "RETIRE_CLOSE_REVIEW";
        }
        if (evUsdt != null && evUsdt.signum() < 0) return "RECOVERY_EXIT_REVIEW";
        return "KEEP_OCO";
    }

    private String recommendationReason(String recommendation) {
        return switch (recommendation) {
            case "RETIRE_CLOSE_REVIEW" ->
                    "At least one selected position is stale with negative heuristic EV or has a CLOSE signal; review controlled retirement, not indefinite BTC accumulation.";
            case "RECOVERY_EXIT_REVIEW" ->
                    "Selected positions remain OCO-protected, but combined heuristic EV is negative; use only a bounded recovery exit review.";
            case "KEEP_OCO_UNDER_MANAGER_REVIEW" ->
                    "No selected position currently has negative heuristic EV; keep the recorded OCO while monitoring.";
            default ->
                    "One or more ownership, position, price, or live OCO checks failed; fail closed and keep existing exchange protection unchanged.";
        };
    }

    private ObjectNode toJson(PositionAssessment p) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("positionId", p.positionId());
        if (p.strategyId() == null) node.putNull("strategyId"); else node.put("strategyId", p.strategyId());
        putText(node, "symbol", p.symbol());
        putText(node, "intervalCode", p.intervalCode());
        putText(node, "side", p.side());
        putText(node, "signalSource", p.signalSource());
        node.put("ageHours", p.ageHours());
        put(node, "entry", p.entry());
        put(node, "current", p.current());
        put(node, "tradedQty", p.tradedQty());
        put(node, "ocoQty", p.ocoQty());
        put(node, "ownedQty", p.ownedQty());
        put(node, "costUsdt", p.costUsdt());
        put(node, "currentValueUsdt", p.currentValueUsdt());
        put(node, "grossUnrealizedPnlUsdt", p.unrealizedPnlUsdt());
        put(node, "grossUnrealizedPnlPct", p.unrealizedPnlPct());
        put(node, "recordedTp", p.tp());
        put(node, "recordedSl", p.sl());
        if (p.ocoAlgoId() == null) node.putNull("ocoAlgoId"); else node.put("ocoAlgoId", p.ocoAlgoId());
        putText(node, "ocoState", p.ocoState());
        node.put("ocoHealthConfirmed", p.ocoHealthConfirmed());
        node.put("ownershipExact", p.ownershipExact());
        if (p.pTpFirstAdjusted() == null) node.putNull("heuristicPTpFirst");
        else node.put("heuristicPTpFirst", p.pTpFirstAdjusted());
        put(node, "heuristicEvUsdt", p.evUsdt());
        putText(node, "heuristicSuggestion", p.heuristicSuggestion());
        putText(node, "proposedDisposition", p.disposition());
        node.put("adoptionEligible", p.eligible());
        node.put("walletBalanceUsed", false);
        node.put("gridInventoryIncluded", false);
        ArrayNode blockers = node.putArray("blockers");
        p.blockers().forEach(blockers::add);
        return node;
    }

    private ObjectNode base(String tool, LocalDateTime now) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", tool);
        root.put("policyMode", POLICY_MODE);
        root.put("stage", STAGE);
        root.put("generatedAtUtc", now.toString());
        root.put("writeMode", false);
        root.put("boundary", "READ_ONLY; no adoption persistence, order, OCO, strategy, grid, fund, Earn, Telegram, scheduler, or exchange mutation");
        return root;
    }

    private void addSafety(ObjectNode root) {
        ObjectNode safety = root.putObject("safety");
        safety.put("databaseMutated", false);
        safety.put("runtimeEvidenceWritten", false);
        safety.put("orderSent", false);
        safety.put("positionClosed", false);
        safety.put("ocoCancelled", false);
        safety.put("ocoModified", false);
        safety.put("telegramSent", false);
        safety.put("fundsMoved", false);
        safety.put("walletBalanceUsedForOwnership", false);
    }

    private List<Long> parsePositionIds(String value, List<String> blockers) {
        if (value == null || value.isBlank()) return List.of();
        Set<Long> ids = new LinkedHashSet<>();
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            try {
                long id = Long.parseLong(trimmed);
                if (id <= 0) {
                    blockers.add("INVALID_POSITION_ID:" + trimmed);
                } else {
                    ids.add(id);
                }
            } catch (NumberFormatException e) {
                blockers.add("INVALID_POSITION_ID:" + trimmed);
            }
        }
        return List.copyOf(ids);
    }

    private boolean isOpenSpotLong(BtLiveSignal position) {
        return position != null
                && Boolean.TRUE.equals(position.getAutoTraded())
                && position.getExitTime() == null
                && !"SHORT".equalsIgnoreCase(position.getSide());
    }

    private boolean isExistingBtcBaseSlice(BtLiveSignal position) {
        return position != null
                && position.getFilterReason() != null
                && position.getFilterReason().startsWith(BTC_BASE_PREFIX);
    }

    private String signalSource(BtLiveSignal position) {
        String filter = position.getFilterReason() == null ? "" : position.getFilterReason();
        String order = position.getExchangeOrderId() == null ? "" : position.getExchangeOrderId();
        if (filter.startsWith(BTC_BASE_PREFIX) || order.startsWith("LOCAL_TV_BTC_BASE:")) {
            return "LOCAL_TRADINGVIEW_BTC_BASE";
        }
        if (filter.startsWith("LOCAL_TRADINGVIEW_PARITY:") || order.startsWith("LOCAL_TV:")) {
            return "LOCAL_TRADINGVIEW_OCO";
        }
        if (Strategy508TimeExitPolicy.POLICY_MODE.equals(filter)) {
            return Strategy508TimeExitPolicy.POLICY_MODE;
        }
        return position.getStrategyId() == null
                ? "UNKNOWN_LEGACY_OCO" : "STRATEGY_" + position.getStrategyId() + "_LEGACY_OCO";
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return BTCUSDT;
        return symbol.replace("-", "").replace("/", "").trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal multiply(BigDecimal left, BigDecimal right) {
        return positive(left) && positive(right) ? left.multiply(right) : null;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private void put(ObjectNode node, String field, BigDecimal value) {
        if (value == null) node.putNull(field);
        else node.put(field, value.setScale(Math.min(8, Math.max(2, value.scale())), RoundingMode.HALF_UP));
    }

    private void putText(ObjectNode node, String field, String value) {
        if (value == null) node.putNull(field); else node.put(field, value);
    }

    private String pretty(ObjectNode root) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }

    private record OcoState(String state, boolean healthy, String blocker) {
    }

    private record Aggregate(
            BigDecimal ownedQty,
            BigDecimal costUsdt,
            BigDecimal currentValueUsdt,
            BigDecimal weightedEntry,
            BigDecimal feeAdjustedBreakEven,
            BigDecimal unrealizedPnlUsdt,
            BigDecimal exitNowNetPnlUsdt,
            BigDecimal originalMaxLossUsdt,
            BigDecimal remainingLossUsdt,
            BigDecimal remainingUpsideUsdt,
            BigDecimal combinedEvUsdt,
            boolean ownershipComplete,
            boolean currentPriceComplete,
            boolean bracketComplete,
            boolean heuristicEvComplete
    ) {
    }

    private record PositionAssessment(
            Long positionId,
            Long strategyId,
            String symbol,
            String intervalCode,
            String side,
            String signalSource,
            long ageHours,
            BigDecimal entry,
            BigDecimal current,
            BigDecimal tradedQty,
            BigDecimal ocoQty,
            BigDecimal ownedQty,
            BigDecimal costUsdt,
            BigDecimal currentValueUsdt,
            BigDecimal unrealizedPnlUsdt,
            BigDecimal unrealizedPnlPct,
            BigDecimal tp,
            BigDecimal sl,
            Long ocoAlgoId,
            String ocoState,
            boolean ocoHealthConfirmed,
            boolean ownershipExact,
            Double pTpFirstAdjusted,
            BigDecimal evUsdt,
            String heuristicSuggestion,
            String disposition,
            boolean eligible,
            List<String> blockers
    ) {
        static PositionAssessment missing(Long positionId, List<String> blockers) {
            return new PositionAssessment(positionId, null, null, null, null, null, 0,
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, "MISSING", false, false, null, null,
                    "UNAVAILABLE", "DO_NOT_ADOPT", false, List.copyOf(blockers));
        }
    }
}
