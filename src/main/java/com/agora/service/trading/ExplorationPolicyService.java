package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ExplorationPolicyService {

    public static final String MODE_BLOCKED = "BLOCKED";
    public static final String MODE_NO_EXPLORATION = "NO_EXPLORATION";
    public static final String MODE_EXPLORE_SHADOW_ONLY = "EXPLORE_SHADOW_ONLY";
    public static final String MODE_EXPLORE_TINY_LIVE = "EXPLORE_TINY_LIVE";

    private static final String SYMBOL = "BTCUSDT";
    private static final long STRATEGY_ID = 574L;
    private static final String SIDE = "LONG";
    private static final BigDecimal MAX_DAILY_EXPLORATION_LOSS = new BigDecimal("2.00");

    private final TinyLiveMinimumOrderPreviewService previewService;
    private final RuntimeDecisionEvidenceService evidenceService;
    private final BtLiveSignalRepository liveSignalRepository;
    private final AutoExplorationRolloutStateService rolloutStateService;
    private final ObjectMapper objectMapper;
    private final Environment env;

    @Transactional(readOnly = true)
    public String getExplorationReadiness(String symbol, Long strategyId, String side) {
        return evaluate(symbol, strategyId, side).renderReadiness();
    }

    @Transactional(readOnly = true)
    public String previewExplorationCandidate(String symbol, Long strategyId, String side) {
        return evaluate(symbol, strategyId, side).renderCandidatePreview();
    }

    @Transactional(readOnly = true)
    public Decision evaluate(String symbol, Long strategyId, String side) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? STRATEGY_ID : strategyId;
        String normalizedSide = normalizeSide(side);
        TinyLiveMinimumOrderPreviewService.PreviewResult preview = previewService.preview(sym, sid, normalizedSide);
        EvidenceSnapshot evidence = evidenceSnapshot(sym, sid);
        SampleSnapshot samples = sampleSnapshot();
        BudgetSnapshot budget = budgetSnapshot(preview, samples);
        OpenTinyLiveWaitSnapshot openTinyLiveWait = openTinyLiveWaitSnapshot(
                sym, sid, normalizedSide, preview.currentSameStrategyTinyLiveOpenPositions());

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>(preview.warnings() == null ? List.of() : preview.warnings());

        if (!SYMBOL.equals(sym) || sid != STRATEGY_ID || !SIDE.equals(normalizedSide)) {
            blockers.add("SCOPE_NOT_ALLOWLISTED");
        }
        if (contains(preview.denialReasons(), "DUPLICATE_BAR_SUPPRESSED")
                || "DUPLICATE_BAR_ACTIVE".equals(preview.duplicateBarStatus())
                || !preview.distinctOpportunity()) {
            blockers.add("DUPLICATE_BAR_SAME_OPPORTUNITY");
        }
        if (containsText(preview.denialReasons(), "DATA_FRESHNESS")
                || containsText(preview.runtimeEvidenceStatus(), "FRESHNESS")
                || containsText(evidence.freshnessState(), "BLOCKED")) {
            blockers.add("DATA_FRESHNESS_HARD_FAIL");
        }
        if (contains(preview.denialReasons(), "NO_CURRENT_BUY_CANDIDATE")) {
            blockers.add("NO_CURRENT_BUY_CANDIDATE");
        } else if (preview.evStatus().startsWith("NOT_READY")) {
            blockers.add("EV_SAMPLE_MISSING");
        } else if (!preview.evStatus().startsWith("PASS")) {
            blockers.add("EV_FAIL");
        }
        if (!isTqsAtLeastProbe(evidence.tqsBand())) {
            blockers.add("TQS_BELOW_PROBE_DRY_RUN");
        }
        if (!preview.ocoPreflightStatus().startsWith("PASS")) {
            blockers.add("OCO_PREFLIGHT_FAIL");
        }
        if (!preview.runtimeEvidenceStatus().startsWith("AVAILABLE_CANONICAL")) {
            blockers.add("RUNTIME_EVIDENCE_MISSING");
        }
        if (preview.currentSameStrategyTinyLiveOpenPositions() > 0 && openTinyLiveWait.blocksNewExploration()) {
            blockers.add("OPEN_TINY_LIVE_POSITION");
        } else if (preview.currentSameStrategyTinyLiveOpenPositions() > 0) {
            warnings.add("OPEN_TINY_LIVE_POSITION_STALE_SLOT_RELEASE_ELIGIBLE");
        }
        if (openTinyLiveWait.isAging()) {
            warnings.add(openTinyLiveWait.status());
        }
        long maxOrdersPerDay = rolloutStateService.effectiveMaxOrdersPerDay(sym, sid, normalizedSide);
        if (preview.autoTradesToday() >= maxOrdersPerDay) {
            blockers.add("DAILY_EXPLORATION_CAP_REACHED");
        }
        if (contains(preview.denialReasons(), "INSUFFICIENT_AVAILABLE_USDT")) {
            blockers.add("CAPITAL_UNAVAILABLE");
        }
        if (contains(preview.denialReasons(), "ACCOUNT_BALANCE_UNAVAILABLE")) {
            warnings.add("ACCOUNT_BALANCE_UNAVAILABLE_EXECUTION_RECHECK_REQUIRED");
        }
        if (budget.explorationBudgetRemaining().compareTo(BigDecimal.ZERO) <= 0) {
            blockers.add("DAILY_EXPLORATION_LOSS_BUDGET_EXCEEDED");
        }
        if (budget.expectedLossIfWrong().compareTo(budget.explorationBudgetRemaining()) > 0) {
            blockers.add("EXPLORATION_BUDGET_INSUFFICIENT_FOR_MAX_LOSS");
        }

        String eventRisk = eventRiskLevel(preview.eventRiskStatus());
        if ("R3".equals(eventRisk) || "R4".equals(eventRisk)) {
            blockers.add("EVENT_RISK_R3_OR_HIGHER");
        } else if ("R2".equals(eventRisk)) {
            warnings.add("eventRisk=R2");
        }

        double learningValue = expectedLearningValue(evidence, samples, preview, warnings);
        String candidateQuality = candidateQuality(evidence, preview);
        if (learningValue <= 0.0) {
            blockers.add("NO_POSITIVE_EXPECTED_LEARNING_VALUE");
        }

        List<String> distinctBlockers = blockers.stream().distinct().toList();
        List<String> distinctWarnings = warnings.stream().distinct().toList();
        String mode;
        boolean eligible;
        String recommendedAction;
        if (!distinctBlockers.isEmpty()) {
            mode = MODE_BLOCKED;
            eligible = false;
            recommendedAction = "DO_NOT_EXPLORE";
        } else if (learningValue < 0.35) {
            mode = MODE_EXPLORE_SHADOW_ONLY;
            eligible = false;
            recommendedAction = "COLLECT_MORE_SHADOW_EVIDENCE";
        } else {
            mode = MODE_EXPLORE_TINY_LIVE;
            eligible = true;
            recommendedAction = "FEED_AUTO_APPROVAL_POLICY";
        }

        return new Decision(mode, eligible, distinctBlockers, distinctWarnings,
                budget.explorationBudgetRemaining(), MAX_DAILY_EXPLORATION_LOSS,
                preview.autoTradesToday(), preview.currentSameStrategyTinyLiveOpenPositions(),
                BigDecimal.valueOf(learningValue).setScale(2, RoundingMode.HALF_UP),
                budget.expectedLossIfWrong(), candidateQuality, recommendedAction,
                preview, evidence, samples, openTinyLiveWait);
    }

    private EvidenceSnapshot evidenceSnapshot(String symbol, long strategyId) {
        List<RuntimeDecisionEvidence> rows = evidenceService.listRecent(symbol, 1440, 100);
        RuntimeDecisionEvidence latest = rows == null ? null : rows.stream()
                .filter(r -> r.getStrategyId() != null && r.getStrategyId() == strategyId)
                .filter(r -> symbol.equalsIgnoreCase(nullToEmpty(r.getSymbol())))
                .findFirst()
                .orElse(null);
        if (latest == null) {
            return new EvidenceSnapshot(false, "UNKNOWN", "UNKNOWN", 0, null);
        }
        String tqsBand = firstTextFromJson(latest.getTqsResultJson(), "tqsBand", "band");
        int qualityScore = firstIntFromJson(latest.getTqsResultJson(), "qualityScore", "score");
        return new EvidenceSnapshot(true,
                blankToUnknown(tqsBand),
                blankToUnknown(latest.getFreshnessState()),
                qualityScore,
                latest.getDecisionId());
    }

    private SampleSnapshot sampleSnapshot() {
        LocalDateTime since30d = LocalDateTime.now(ZoneOffset.UTC).minusDays(30);
        List<BtLiveSignal> closed = liveSignalRepository.findClosedTinyLiveSince(STRATEGY_ID, SYMBOL, since30d);
        if (closed == null) {
            closed = List.of();
        }
        int truePositive = 0;
        int falsePositive = 0;
        int lowEdge = 0;
        for (BtLiveSignal row : closed) {
            BigDecimal pnl = row.getRealizedPnl();
            if (pnl == null) {
                continue;
            }
            if (pnl.compareTo(new BigDecimal("0.10")) > 0) {
                truePositive++;
            } else if (pnl.compareTo(new BigDecimal("-0.10")) < 0) {
                falsePositive++;
            } else {
                lowEdge++;
            }
        }
        return new SampleSnapshot(closed.size(), truePositive, falsePositive, lowEdge, closed);
    }

    private OpenTinyLiveWaitSnapshot openTinyLiveWaitSnapshot(String symbol,
                                                             long strategyId,
                                                             String side,
                                                             long previewOpenCount) {
        List<BtLiveSignal> rows = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull();
        rows = rows == null ? List.of() : rows.stream()
                .filter(row -> strategyId == safeLong(row.getStrategyId()))
                .filter(row -> symbol.equalsIgnoreCase(nullToEmpty(row.getSymbol())))
                .filter(row -> side.equalsIgnoreCase(blankToDefault(row.getSide(), SIDE)))
                .filter(this::isTinyLivePosition)
                .toList();
        long count = Math.max(previewOpenCount, rows.size());
        if (count <= 0) {
            return OpenTinyLiveWaitSnapshot.none();
        }
        BtLiveSignal oldest = rows.stream()
                .filter(row -> openedAt(row) != null)
                .min((a, b) -> openedAt(a).compareTo(openedAt(b)))
                .orElse(rows.isEmpty() ? null : rows.get(0));
        if (oldest == null) {
            return OpenTinyLiveWaitSnapshot.unknown(count);
        }
        LocalDateTime openedAt = openedAt(oldest);
        if (openedAt == null) {
            return OpenTinyLiveWaitSnapshot.unknown(count);
        }
        BigDecimal ageHours = BigDecimal.valueOf(Math.max(0, Duration.between(
                        openedAt.toInstant(ZoneOffset.UTC), LocalDateTime.now(ZoneOffset.UTC).toInstant(ZoneOffset.UTC))
                .toMinutes()) / 60.0).setScale(2, RoundingMode.HALF_UP);
        double age = ageHours.doubleValue();
        double staleHours = doubleProperty("trading.exploration.loop.open-position-stale-hours", 72.0);
        double agingHours = doubleProperty("trading.exploration.loop.open-position-aging-hours", 24.0);
        String status;
        String recommendation;
        if (age >= staleHours) {
            status = "OPEN_TINY_LIVE_POSITION_STALE_REVIEW";
            recommendation = staleSlotReleaseEligible(count, oldest)
                    ? "Stale tiny-live is OCO-protected and small enough to release the exploration slot; all other hard gates still apply."
                    : "Inspect OCO/outcome state; keep read-only loop blocked until position closes or an explicit manual policy handles stale tiny-live.";
        } else if (age >= agingHours) {
            status = "OPEN_TINY_LIVE_POSITION_AGING";
            recommendation = "Continue waiting, but track whether the position becomes stale before the next exploration slot.";
        } else {
            status = "WAIT_OPEN_POSITION_NORMAL";
            recommendation = "Wait for current OCO-protected tiny-live position to close.";
        }
        return new OpenTinyLiveWaitSnapshot(count, status, oldest.getId(), openedAt, ageHours,
                oldest.getOcoOrderListId(), firstNonNull(oldest.getActualEntryPrice(), oldest.getEntryPrice()),
                oldest.getSuggestedTp(), oldest.getSuggestedSl(),
                firstNonNull(oldest.getOcoQty(), oldest.getTradedQty()),
                oldest.getExchangeOrderId(), positionNotionalUsdt(oldest),
                staleSlotReleaseEligible(count, oldest), staleSlotReleaseReason(count, oldest),
                recommendation);
    }

    private LocalDateTime openedAt(BtLiveSignal row) {
        if (row == null) {
            return null;
        }
        if (row.getCreatedAt() != null) {
            return row.getCreatedAt();
        }
        if (row.getBarOpenTime() != null) {
            return row.getBarOpenTime();
        }
        return row.getNotifiedAt();
    }

    private boolean isTinyLivePosition(BtLiveSignal signal) {
        return containsText(signal.getFilterReason(), "TINY_LIVE")
                || containsText(signal.getExchangeOrderId(), "TINY_LIVE");
    }

    private boolean staleSlotReleaseEligible(long openCount, BtLiveSignal oldest) {
        return "ELIGIBLE".equals(staleSlotReleaseReason(openCount, oldest));
    }

    private String staleSlotReleaseReason(long openCount, BtLiveSignal oldest) {
        if (!booleanProperty("trading.exploration.loop.stale-position-slot-release.enabled", true)) {
            return "DISABLED_BY_CONFIG";
        }
        if (openCount != 1) {
            return "OPEN_TINY_LIVE_COUNT_NOT_ONE";
        }
        if (oldest == null) {
            return "OPEN_POSITION_DETAIL_UNAVAILABLE";
        }
        if (oldest.getOcoOrderListId() == null) {
            return "OCO_NOT_ATTACHED";
        }
        LocalDateTime openedAt = openedAt(oldest);
        if (openedAt == null) {
            return "OPEN_TIME_UNAVAILABLE";
        }
        double staleHours = doubleProperty("trading.exploration.loop.open-position-stale-hours", 72.0);
        double age = Math.max(0, Duration.between(openedAt.toInstant(ZoneOffset.UTC),
                LocalDateTime.now(ZoneOffset.UTC).toInstant(ZoneOffset.UTC)).toMinutes()) / 60.0;
        if (age < staleHours) {
            return "NOT_STALE_YET";
        }
        BigDecimal notional = positionNotionalUsdt(oldest);
        BigDecimal maxNotional = decimalProperty("trading.exploration.loop.stale-position-slot-release.max-open-notional-usdt",
                new BigDecimal("5.50"));
        if (notional == null || notional.compareTo(maxNotional) > 0) {
            return "OPEN_NOTIONAL_ABOVE_STALE_RELEASE_CAP";
        }
        return "ELIGIBLE";
    }

    private BigDecimal positionNotionalUsdt(BtLiveSignal signal) {
        if (signal == null) {
            return null;
        }
        BigDecimal price = firstNonNull(signal.getActualEntryPrice(), signal.getEntryPrice());
        BigDecimal qty = firstNonNull(signal.getOcoQty(), signal.getTradedQty());
        if (price == null || qty == null) {
            return null;
        }
        return price.multiply(qty);
    }

    private BudgetSnapshot budgetSnapshot(TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                          SampleSnapshot samples) {
        BigDecimal used = samples.closedRows().stream()
                .map(BtLiveSignal::getRealizedPnl)
                .filter(v -> v != null && v.signum() < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = MAX_DAILY_EXPLORATION_LOSS.subtract(used).max(BigDecimal.ZERO);
        BigDecimal expectedLoss = maxLoss(preview);
        return new BudgetSnapshot(remaining, expectedLoss, used);
    }

    private BigDecimal maxLoss(TinyLiveMinimumOrderPreviewService.PreviewResult preview) {
        if (preview.proposedQty() == null || preview.entry() == null || preview.sl() == null
                || preview.entry().compareTo(preview.sl()) <= 0) {
            return BigDecimal.ZERO;
        }
        return preview.entry().subtract(preview.sl()).multiply(preview.proposedQty());
    }

    private double expectedLearningValue(EvidenceSnapshot evidence,
                                         SampleSnapshot samples,
                                         TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                         List<String> warnings) {
        double value = 0.0;
        int passSamples = samples.truePositiveCount() + samples.falsePositiveCount();
        if (passSamples == 0) {
            value += 0.45;
        } else if (passSamples < 3) {
            value += 0.25;
        }
        if (samples.closedTinyLiveCount() == 0) {
            value += 0.20;
        }
        if ("PROBE_DRY_RUN".equalsIgnoreCase(evidence.tqsBand())) {
            value += 0.15;
        }
        if (preview.evStatus().startsWith("PASS")) {
            value += 0.10;
        }
        if (warnings.stream().anyMatch(w -> w.contains("eventRisk=R2") || w.contains("eventRiskElevated"))) {
            value -= 0.05;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String candidateQuality(EvidenceSnapshot evidence,
                                    TinyLiveMinimumOrderPreviewService.PreviewResult preview) {
        return "tqsBand=%s qualityScore=%d evStatus=%s runtimeEvidence=%s distinctOpportunity=%s"
                .formatted(evidence.tqsBand(), evidence.qualityScore(), preview.evStatus(),
                        preview.runtimeEvidenceStatus(), preview.distinctOpportunity());
    }

    private boolean isTqsAtLeastProbe(String tqsBand) {
        if (tqsBand == null) {
            return false;
        }
        String band = tqsBand.trim().toUpperCase(Locale.ROOT);
        return "PROBE_DRY_RUN".equals(band)
                || "SMALL_DRY_RUN".equals(band)
                || "CAPPED_SMALL_DRY_RUN".equals(band);
    }

    private String firstTextFromJson(String json, String... fields) {
        try {
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonNode node = objectMapper.readTree(json);
            for (String field : fields) {
                JsonNode value = node.get(field);
                if (value != null && !value.isNull() && !value.asText().isBlank()) {
                    return value.asText();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private int firstIntFromJson(String json, String... fields) {
        try {
            if (json == null || json.isBlank()) {
                return 0;
            }
            JsonNode node = objectMapper.readTree(json);
            for (String field : fields) {
                JsonNode value = node.get(field);
                if (value != null && value.canConvertToInt()) {
                    return value.asInt();
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private boolean contains(List<String> values, String needle) {
        return values != null && values.stream().anyMatch(needle::equals);
    }

    private boolean containsText(List<String> values, String needle) {
        return values != null && values.stream().anyMatch(v -> containsText(v, needle));
    }

    private boolean containsText(String value, String needle) {
        return value != null && value.toUpperCase(Locale.ROOT).contains(needle);
    }

    private String eventRiskLevel(String eventRiskStatus) {
        if (eventRiskStatus == null || eventRiskStatus.isBlank()) {
            return "UNKNOWN";
        }
        return eventRiskStatus.trim().split("\\s+")[0].toUpperCase(Locale.ROOT);
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSide(String side) {
        if (side == null || side.isBlank()) return SIDE;
        String upper = side.trim().toUpperCase(Locale.ROOT);
        return "BUY".equals(upper) ? SIDE : upper;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private double doubleProperty(String key, double fallback) {
        try {
            return Double.parseDouble(env.getProperty(key, String.valueOf(fallback)));
        } catch (Exception e) {
            return fallback;
        }
    }

    private boolean booleanProperty(String key, boolean fallback) {
        return Boolean.parseBoolean(env.getProperty(key, String.valueOf(fallback)));
    }

    private BigDecimal decimalProperty(String key, BigDecimal fallback) {
        try {
            return new BigDecimal(env.getProperty(key, fallback.toPlainString()));
        } catch (Exception e) {
            return fallback;
        }
    }

    private BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
        return first != null ? first : second;
    }

    public record Decision(String explorationMode,
                           boolean eligible,
                           List<String> blockers,
                           List<String> warnings,
                           BigDecimal explorationBudgetRemaining,
                           BigDecimal maxDailyExplorationLossUsdt,
                           long ordersToday,
                           long openTinyLivePositions,
                           BigDecimal expectedLearningValue,
                           BigDecimal expectedLossIfWrong,
                           String candidateQuality,
                           String recommendedAction,
                           TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                           EvidenceSnapshot evidence,
                           SampleSnapshot samples,
                           OpenTinyLiveWaitSnapshot openTinyLiveWait) {
        public String renderReadiness() {
            return """
                    === Controlled Exploration Readiness ===
                    boundary=READ_ONLY; no order/OCO/strategy/grid/fund/Earn behavior changed.
                    explorationMode=%s
                    eligible=%s
                    blockers=%s
                    warnings=%s
                    explorationBudgetRemaining=%s
                    maxDailyExplorationLossUsdt=%s
                    ordersToday=%d
                    openTinyLivePositions=%d
                    openTinyLiveWait=%s
                    expectedLearningValue=%s
                    expectedLossIfWrong=%s
                    candidateQuality=%s
                    recommendedAction=%s
                    symbol=%s
                    strategyId=%d
                    side=%s
                    eventRisk=%s
                    previewStatus=%s
                    runtimeEvidenceStatus=%s
                    tqsBand=%s
                    truePositiveSamples=%d
                    falsePositiveSamples=%d
                    passSampleCount=%d
                    orderSent=false
                    """.formatted(explorationMode, eligible, blockers, warnings,
                    money(explorationBudgetRemaining), money(maxDailyExplorationLossUsdt),
                    ordersToday, openTinyLivePositions, openTinyLiveWait.renderCompact(), expectedLearningValue,
                    money(expectedLossIfWrong), candidateQuality, recommendedAction,
                    preview.symbol(), preview.strategyId(), preview.side(), preview.eventRiskStatus(),
                    preview.status(), preview.runtimeEvidenceStatus(), evidence.tqsBand(),
                    samples.truePositiveCount(), samples.falsePositiveCount(),
                    samples.truePositiveCount() + samples.falsePositiveCount());
        }

        public String renderCandidatePreview() {
            return """
                    === Controlled Exploration Candidate Preview ===
                    boundary=READ_ONLY; no order/OCO/strategy/grid/fund/Earn behavior changed.
                    wouldExplore=%s
                    explorationMode=%s
                    reason=%s
                    learningValue=%s
                    riskCost=%s
                    suggestedNotionalUsdt=5.00
                    labelGoal=collect TRUE_POSITIVE/FALSE_POSITIVE tiny-live samples
                    expectedOutcomeLabelTarget=TRUE_POSITIVE_OR_FALSE_POSITIVE_OR_LOW_EDGE
                    requiredAuditFields=[explorationMode,learningValue,labelGoal,expectedLossIfWrong,explorationBudgetRemainingBefore,explorationBudgetRemainingAfter,sourceBlockerBeingTested,outcomeLabelLink]
                    blockers=%s
                    warnings=%s
                    openTinyLiveWait=%s
                    recommendedAction=%s
                    orderSent=false
                    """.formatted(eligible, explorationMode,
                    blockers.isEmpty() ? "Hard gates pass; exploration may feed AutoApprovalPolicy." : "Blocked by " + blockers,
                    expectedLearningValue, money(expectedLossIfWrong), blockers, warnings,
                    openTinyLiveWait.renderCompact(),
                    recommendedAction);
        }

        private static String money(BigDecimal value) {
            return value == null ? "0" : value.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
    }

    public record EvidenceSnapshot(boolean canonicalAvailable,
                                   String tqsBand,
                                   String freshnessState,
                                   int qualityScore,
                                   Long decisionId) {
    }

    public record SampleSnapshot(int closedTinyLiveCount,
                                 int truePositiveCount,
                                 int falsePositiveCount,
                                 int lowEdgeCount,
                                 List<BtLiveSignal> closedRows) {
    }

    public record OpenTinyLiveWaitSnapshot(long openPositionCount,
                                           String status,
                                           Long oldestLiveSignalId,
                                           LocalDateTime openedAt,
                                           BigDecimal ageHours,
                                           Long ocoOrderListId,
                                           BigDecimal entryPrice,
                                           BigDecimal tpPrice,
                                           BigDecimal slPrice,
                                           BigDecimal qty,
                                           String exchangeOrderId,
                                           BigDecimal openNotionalUsdt,
                                           boolean staleSlotReleaseEligible,
                                           String staleSlotReleaseReason,
                                           String recommendation) {
        static OpenTinyLiveWaitSnapshot none() {
            return new OpenTinyLiveWaitSnapshot(0, "NO_OPEN_TINY_LIVE", null, null, BigDecimal.ZERO,
                    null, null, null, null, null, null,
                    null, false, "NO_OPEN_TINY_LIVE",
                    "No open tiny-live position blocks exploration.");
        }

        static OpenTinyLiveWaitSnapshot unknown(long count) {
            return new OpenTinyLiveWaitSnapshot(count, "OPEN_TINY_LIVE_POSITION_DETAIL_UNAVAILABLE", null, null,
                    BigDecimal.ZERO, null, null, null, null, null, null,
                    null, false, "OPEN_POSITION_DETAIL_UNAVAILABLE",
                    "Open tiny-live count is visible, but position detail was not found in bt_live_signal.");
        }

        boolean isAging() {
            return "OPEN_TINY_LIVE_POSITION_AGING".equals(status)
                    || "OPEN_TINY_LIVE_POSITION_STALE_REVIEW".equals(status)
                    || "OPEN_TINY_LIVE_POSITION_DETAIL_UNAVAILABLE".equals(status);
        }

        boolean blocksNewExploration() {
            return openPositionCount > 0 && !staleSlotReleaseEligible;
        }

        String renderCompact() {
            return "status=%s openPositionCount=%d oldestLiveSignalId=%s openedAt=%s ageHours=%s ocoOrderListId=%s entry=%s tp=%s sl=%s qty=%s openNotionalUsdt=%s staleSlotReleaseEligible=%s staleSlotReleaseReason=%s recommendation=%s"
                    .formatted(status, openPositionCount, na(oldestLiveSignalId), na(openedAt), money(ageHours),
                            na(ocoOrderListId), money(entryPrice), money(tpPrice), money(slPrice), money(qty),
                            money(openNotionalUsdt), staleSlotReleaseEligible, staleSlotReleaseReason, recommendation);
        }

        private static String na(Object value) {
            return value == null ? "N/A" : String.valueOf(value);
        }

        private static String money(BigDecimal value) {
            return value == null ? "N/A" : value.setScale(8, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString();
        }
    }

    private record BudgetSnapshot(BigDecimal explorationBudgetRemaining,
                                  BigDecimal expectedLossIfWrong,
                                  BigDecimal lossUsed) {
    }
}
