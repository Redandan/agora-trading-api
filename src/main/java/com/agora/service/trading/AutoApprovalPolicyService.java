package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.TinyLiveExecutionAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AutoApprovalPolicyService {

    public static final String MODE_BLOCKED = "BLOCKED";
    public static final String MODE_HUMAN_APPROVAL_REQUIRED = "HUMAN_APPROVAL_REQUIRED";
    public static final String MODE_AUTO_APPROVED_TINY_LIVE = "AUTO_APPROVED_TINY_LIVE";
    public static final String MODE_AUTO_APPROVED_TINY_LIVE_WITH_EVENT_RISK_OVERRIDE = "AUTO_APPROVED_TINY_LIVE_WITH_EVENT_RISK_OVERRIDE";
    public static final String MODE_AUTO_APPROVED_TINY_LIVE_WITH_RISK_OVERRIDE = "AUTO_APPROVED_TINY_LIVE_WITH_RISK_OVERRIDE";
    public static final String POLICY_VERSION = "auto-approval-v0";

    private static final String SYMBOL = "BTCUSDT";
    private static final long STRATEGY_ID = 574L;
    private static final String SIDE = "LONG";
    private static final String MAX_NOTIONAL = "5.00";

    private final TinyLiveMinimumOrderPreviewService previewService;
    private final TinyLiveExecutionAuditRepository executionAuditRepository;
    private final BtLiveSignalRepository liveSignalRepository;
    private final AutoExplorationRolloutStateService rolloutStateService;
    private final ObjectMapper objectMapper;
    private final Environment env;

    @Transactional(readOnly = true)
    public String previewTinyLiveAutoApproval(String symbol, Long strategyId, String side) {
        return evaluate(symbol, strategyId, side).render();
    }

    @Transactional(readOnly = true)
    public Decision evaluate(String symbol, Long strategyId, String side) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? STRATEGY_ID : strategyId;
        String normalizedSide = normalizeSide(side);
        TinyLiveMinimumOrderPreviewService.PreviewResult preview = previewService.preview(sym, sid, normalizedSide);
        return evaluate(preview);
    }

    public Decision evaluate(TinyLiveMinimumOrderPreviewService.PreviewResult preview) {
        return evaluate(preview, false);
    }

    public Decision decide(TinyLiveMinimumOrderPreviewService.PreviewResult preview) {
        return decide(preview, false);
    }

    public Decision evaluate(TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                             boolean eventRiskOverrideAllowed) {
        return decide(preview, eventRiskOverrideAllowed).withToken(tokenize(preview, eventRiskOverrideAllowed));
    }

    public Decision decide(TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                           boolean eventRiskOverrideAllowed) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>(preview.warnings() == null ? List.of() : preview.warnings());
        List<String> previewDenialReasons = effectivePreviewDenialReasons(preview);
        BudgetSnapshot budget = budgetSnapshot(preview);

        if (!SYMBOL.equals(preview.symbol()) || preview.strategyId() != STRATEGY_ID || !SIDE.equals(preview.side())) {
            blockers.add("SCOPE_NOT_ALLOWLISTED");
        }
        if (executionAuditRepository.existsByStatusAndCreatedAtAfter("CRITICAL_UNPROTECTED_TINY_LIVE",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(30))) {
            blockers.add("AUTO_APPROVAL_DISABLED_CRITICAL_UNPROTECTED_TINY_LIVE");
        }
        String eventRisk = eventRiskLevel(preview.eventRiskStatus());
        boolean r3OverrideCandidate = eventRiskOverrideAllowed
                && "R3".equals(eventRisk)
                && isEventRiskOnlyPreviewBlock(preview);
        if ((!"READY_FOR_MANUAL_APPROVAL".equals(preview.status()) || !preview.allowedAfterManualApproval())
                && !r3OverrideCandidate) {
            blockers.add("PREVIEW_NOT_READY:" + previewDenialReasons);
        }
        if ("DUPLICATE_BAR_ACTIVE".equals(preview.duplicateBarStatus())
                || contains(previewDenialReasons, "DUPLICATE_BAR_SUPPRESSED")) {
            blockers.add("DUPLICATE_BAR");
        }
        boolean noCurrentBuyCandidate = contains(previewDenialReasons, "NO_CURRENT_BUY_CANDIDATE");
        if (noCurrentBuyCandidate) {
            blockers.add("NO_CURRENT_BUY_CANDIDATE");
        } else if (preview.evStatus().startsWith("NOT_READY")) {
            blockers.add("EV_SAMPLE_MISSING");
        } else if (!preview.evStatus().startsWith("PASS")) {
            blockers.add("EV_FAIL");
        }
        if (!preview.ocoPreflightStatus().startsWith("PASS") && !noCurrentBuyCandidate) {
            blockers.add("OCO_PREFLIGHT_FAIL");
        } else if (!preview.ocoPreflightStatus().startsWith("PASS")) {
            addWarningIfAbsent(warnings, "ocoPreflightPendingUntilBuyCandidate=" + preview.ocoPreflightStatus());
        }
        if (!runtimeEvidenceAvailable(preview.runtimeEvidenceStatus())) {
            blockers.add("RUNTIME_EVIDENCE_MISSING");
        } else if (!"AVAILABLE_CANONICAL_SHADOW_EVIDENCE".equals(preview.runtimeEvidenceStatus())) {
            warnings.add("runtimeEvidenceCanonicalRowsNoShadowIntent=true");
        }
        boolean staleSlotReleaseEligible = contains(preview.warnings(), "staleTinyLiveSlotReleaseEligible=true");
        if (preview.currentSameStrategyTinyLiveOpenPositions() > 0 && !staleSlotReleaseEligible) {
            blockers.add("OPEN_TINY_LIVE_POSITION");
        } else if (preview.currentSameStrategyTinyLiveOpenPositions() > 0) {
            warnings.add("OPEN_TINY_LIVE_POSITION_STALE_SLOT_RELEASE_ELIGIBLE");
        }
        long maxOrdersPerDay = rolloutStateService.effectiveMaxOrdersPerDay(preview.symbol(), preview.strategyId(), preview.side());
        if (preview.autoTradesToday() >= maxOrdersPerDay) {
            blockers.add("DAILY_CAP_REACHED");
        }
        if (budget.dailyLossBudgetReached()) {
            blockers.add("DAILY_TINY_LIVE_LOSS_BUDGET_REACHED");
        }
        if (budget.maxLossIfWrongUsdt().compareTo(budget.missedAlphaBudgetRemaining()) > 0) {
            blockers.add("MISSED_ALPHA_BUDGET_INSUFFICIENT_FOR_MAX_LOSS");
        }
        boolean ignoreConsecutiveLossHardStop = booleanProperty(
                "trading.tiny-live.auto-approval.ignore-consecutive-loss-hard-stop", false);
        if (budget.consecutiveLosses() >= 2 && !ignoreConsecutiveLossHardStop) {
            blockers.add("AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES");
        } else if (budget.consecutiveLosses() >= 2) {
            warnings.add("consecutiveTinyLiveLossHardStopOverride=true");
        }
        if (preview.proposedNotionalUsdt() == null
                || preview.proposedNotionalUsdt().compareTo(new java.math.BigDecimal(MAX_NOTIONAL)) > 0) {
            blockers.add("MAX_NOTIONAL_EXCEEDED");
        }
        boolean eventRiskOverrideUsed = false;
        if ("R3".equals(eventRisk) || "R4".equals(eventRisk)) {
            if (eventRiskOverrideAllowed && "R3".equals(eventRisk)) {
                if (r3OverrideUsedInLast24h()) {
                    blockers.add("EVENT_RISK_OVERRIDE_DAILY_CAP_REACHED");
                } else {
                    warnings.add("eventRiskOverrideUsed=true");
                    eventRiskOverrideUsed = true;
                }
            } else {
                blockers.add("EVENT_RISK_R3_OR_HIGHER");
            }
        } else if ("R2".equals(eventRisk)) {
            warnings.add("eventRisk=R2");
        }
        for (String reason : preview.denialReasons()) {
            if ("EVENT_RISK_HIGH".equals(reason)) {
                if (eventRiskOverrideAllowed && "R3".equals(eventRisk)) {
                    if (r3OverrideUsedInLast24h()) {
                        blockers.add("EVENT_RISK_OVERRIDE_DAILY_CAP_REACHED");
                    } else {
                        warnings.add("eventRiskOverrideReason=EVENT_RISK_HIGH");
                        eventRiskOverrideUsed = true;
                    }
                } else {
                    blockers.add("EVENT_RISK_R3_OR_HIGHER");
                }
            }
            if ("MAX_TINY_LIVE_ORDERS_TODAY_REACHED".equals(reason)) {
                blockers.add("DAILY_CAP_REACHED");
            }
            if ("TINY_LIVE_POSITION_ALREADY_OPEN".equals(reason) && !staleSlotReleaseEligible) {
                blockers.add("OPEN_TINY_LIVE_POSITION");
            }
        }

        List<String> distinctWarnings = warnings.stream().distinct().toList();
        List<String> distinctBlockers = blockers.stream().distinct().toList();
        String mode;
        String reason;
        if (!distinctBlockers.isEmpty()) {
            mode = MODE_BLOCKED;
            reason = "Hard gates blocked tiny-live auto approval: " + distinctBlockers;
        } else if (tokenSecret().isBlank()) {
            mode = MODE_HUMAN_APPROVAL_REQUIRED;
            reason = "Hard gates passed, but auto approval token signing secret is not configured.";
        } else {
            mode = eventRiskOverrideUsed
                    ? MODE_AUTO_APPROVED_TINY_LIVE_WITH_EVENT_RISK_OVERRIDE
                    : MODE_AUTO_APPROVED_TINY_LIVE;
            reason = eventRiskOverrideUsed
                    ? "All non-event hard gates passed; R3 tiny-live override is enabled and bounded to $5."
                    : "All hard gates passed for BTCUSDT strategy 574 LONG $5 tiny-live auto approval.";
        }
        return new Decision(mode, reason, buildInputsJson(preview, eventRisk, budget, eventRiskOverrideUsed),
                distinctWarnings,
                distinctBlockers, preview.previewHash(), budget.missedAlphaBudgetRemaining(),
                budget.maxLossIfWrongUsdt(), budget.allowedMistakeBudgetUsed(), eventRiskOverrideUsed,
                null, null, null);
    }

    public boolean verifyAutoApprovalToken(String token,
                                           String expectedPreviewHash,
                                           TinyLiveMinimumOrderPreviewService.PreviewResult currentPreview) {
        if (token == null || token.isBlank()
                || expectedPreviewHash == null || expectedPreviewHash.isBlank()
                || currentPreview == null) {
            return false;
        }
        String secret = tokenSecret();
        if (secret.isBlank()) {
            return false;
        }
        String[] parts = token.trim().split("\\|");
        if (parts.length != 10) {
            return false;
        }
        String tokenId = parts[0];
        String hash = parts[1];
        String symbol = parts[2];
        String strategyId = parts[3];
        String side = parts[4];
        String notional = parts[5];
        String approvalMode = parts[6];
        String policyVersion = parts[7];
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[8]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (!tokenId.startsWith("taa_") || Instant.now().isAfter(Instant.ofEpochSecond(expiresAt))) {
            return false;
        }
        String payload = tokenId + "|" + hash + "|" + symbol + "|" + strategyId + "|" + side + "|" + notional
                + "|" + approvalMode + "|" + policyVersion + "|" + expiresAt;
        if (!constantTimeEquals(parts[9], hmac(secret, payload))) {
            return false;
        }
        boolean tokenRequestsEventRiskOverride = MODE_AUTO_APPROVED_TINY_LIVE_WITH_EVENT_RISK_OVERRIDE.equals(approvalMode)
                || MODE_AUTO_APPROVED_TINY_LIVE_WITH_RISK_OVERRIDE.equals(approvalMode);
        Decision decision = decide(currentPreview, tokenRequestsEventRiskOverride);
        return isAutoApproved(decision.approvalMode())
                && constantTimeEquals(hash, expectedPreviewHash.trim())
                && constantTimeEquals(hash, previewService.previewHash(currentPreview))
                && SYMBOL.equals(symbol)
                && String.valueOf(STRATEGY_ID).equals(strategyId)
                && SIDE.equals(side)
                && MAX_NOTIONAL.equals(notional)
                && decision.approvalMode().equals(approvalMode)
                && POLICY_VERSION.equals(policyVersion);
    }

    public String tokenId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String[] parts = token.trim().split("\\|");
        return parts.length >= 1 ? parts[0] : null;
    }

    public boolean looksLikeAutoApprovalToken(String token) {
        return token != null && token.startsWith("taa_");
    }

    public String approvalModeFromToken(String token) {
        if (token == null || token.isBlank()) {
            return MODE_AUTO_APPROVED_TINY_LIVE;
        }
        String[] parts = token.trim().split("\\|");
        return parts.length >= 7 ? parts[6] : MODE_AUTO_APPROVED_TINY_LIVE;
    }

    private ApprovalToken tokenize(TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                   boolean eventRiskOverrideAllowed) {
        Decision decision = decide(preview, eventRiskOverrideAllowed);
        if (!isAutoApproved(decision.approvalMode())) {
            return new ApprovalToken(null, null, null);
        }
        String secret = tokenSecret();
        if (secret.isBlank()) {
            return new ApprovalToken(null, null, null);
        }
        long ttl = Long.parseLong(env.getProperty("trading.tiny-live.auto-approval-token-ttl-seconds", "120"));
        Instant expiresAt = Instant.now().plusSeconds(Math.max(30, Math.min(ttl, 120)));
        String tokenId = "taa_" + shortHash(preview.previewHash() + "|" + expiresAt);
        String payload = tokenId + "|" + preview.previewHash() + "|" + preview.symbol() + "|"
                + preview.strategyId() + "|" + preview.side() + "|" + MAX_NOTIONAL + "|"
                + decision.approvalMode() + "|" + POLICY_VERSION + "|" + expiresAt.getEpochSecond();
        return new ApprovalToken(tokenId, payload + "|" + hmac(secret, payload), expiresAt);
    }

    private String tokenSecret() {
        String dedicated = env.getProperty("trading.tiny-live.auto-approval-token-secret", "");
        if (dedicated != null && !dedicated.isBlank()) {
            return dedicated.trim();
        }
        String previewSecret = env.getProperty("trading.tiny-live.preview-token-secret", "");
        return previewSecret == null ? "" : previewSecret.trim();
    }

    private String buildInputsJson(TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                   String eventRisk,
                                   BudgetSnapshot budget,
                                   boolean eventRiskOverrideUsed) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("symbol", preview.symbol());
            node.put("strategyId", preview.strategyId());
            node.put("side", preview.side());
            node.put("notionalUsdt", MAX_NOTIONAL);
            node.put("policyVersion", POLICY_VERSION);
            node.put("maxOrdersPerDay", 1);
            node.put("maxOpenTinyLivePositions", 1);
            node.put("duplicateBarStatus", preview.duplicateBarStatus());
            node.put("evStatus", preview.evStatus());
            node.put("tqsBandMinimum", "PROBE_DRY_RUN");
            node.put("ocoPreflightStatus", preview.ocoPreflightStatus());
            node.put("runtimeEvidenceStatus", preview.runtimeEvidenceStatus());
            node.put("eventRisk", eventRisk);
            node.put("eventRiskOverrideUsed", eventRiskOverrideUsed);
            node.put("missedAlphaBudgetRemaining", decimal(budget.missedAlphaBudgetRemaining()));
            node.put("maxLossIfWrongUsdt", decimal(budget.maxLossIfWrongUsdt()));
            node.put("allowedMistakeBudgetUsed", decimal(budget.allowedMistakeBudgetUsed()));
            node.put("maxDailyTinyLiveLossUsdt", "2");
            node.put("maxConsecutiveTinyLiveLosses", 2);
            node.put("consecutiveTinyLiveLosses", budget.consecutiveLosses());
            node.put("ignoreConsecutiveLossHardStop", booleanProperty(
                    "trading.tiny-live.auto-approval.ignore-consecutive-loss-hard-stop", false));
            node.put("autoTradesToday", preview.autoTradesToday());
            node.put("openTinyLivePositions", preview.currentSameStrategyTinyLiveOpenPositions());
            node.put("existingAutoTradePositionCount", preview.currentAutoTradeOpenPositions());
            node.put("sameStrategyOpenAutoPositionCount", preview.currentSameStrategyAutoTradeOpenPositions());
            node.put("existingGridExposureUsdt", decimal(preview.currentGridExposureUsdt()));
            node.put("orderSent", false);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    private BudgetSnapshot budgetSnapshot(TinyLiveMinimumOrderPreviewService.PreviewResult preview) {
        BigDecimal maxLossIfWrong = maxLoss(preview);
        List<BtLiveSignal> closedToday = liveSignalRepository.findClosedTinyLiveSince(
                STRATEGY_ID, SYMBOL, LocalDateTime.now(ZoneOffset.UTC).toLocalDate().atStartOfDay());
        if (closedToday == null) {
            closedToday = List.of();
        }
        BigDecimal lossUsed = closedToday.stream()
                .map(BtLiveSignal::getRealizedPnl)
                .filter(v -> v != null && v.signum() < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = new BigDecimal("2.00").subtract(lossUsed);
        int consecutiveLosses = consecutiveLosses();
        return new BudgetSnapshot(remaining.max(BigDecimal.ZERO), maxLossIfWrong, lossUsed,
                lossUsed.compareTo(new BigDecimal("2.00")) >= 0, consecutiveLosses);
    }

    private int consecutiveLosses() {
        List<BtLiveSignal> rows = liveSignalRepository.findClosedTinyLiveSince(
                STRATEGY_ID, SYMBOL, LocalDateTime.now(ZoneOffset.UTC).minusDays(30));
        if (rows == null) {
            rows = List.of();
        }
        int count = 0;
        for (BtLiveSignal row : rows) {
            BigDecimal pnl = row.getRealizedPnl();
            if (pnl == null || pnl.signum() >= 0) {
                break;
            }
            count++;
        }
        return count;
    }

    private BigDecimal maxLoss(TinyLiveMinimumOrderPreviewService.PreviewResult preview) {
        if (preview.proposedQty() == null || preview.entry() == null || preview.sl() == null
                || preview.entry().compareTo(preview.sl()) <= 0) {
            return BigDecimal.ZERO;
        }
        return preview.entry().subtract(preview.sl()).multiply(preview.proposedQty());
    }

    private boolean isEventRiskOnlyPreviewBlock(TinyLiveMinimumOrderPreviewService.PreviewResult preview) {
        List<String> reasons = preview.denialReasons() == null ? List.of() : preview.denialReasons();
        boolean onlyEventRiskReason = !reasons.isEmpty()
                && reasons.stream().allMatch("EVENT_RISK_HIGH"::equals);
        return onlyEventRiskReason && "NOT_READY_EVENT_RISK".equals(preview.status());
    }

    private List<String> effectivePreviewDenialReasons(TinyLiveMinimumOrderPreviewService.PreviewResult preview) {
        List<String> reasons = preview.denialReasons() == null ? List.of() : preview.denialReasons();
        if (!contains(reasons, "NO_CURRENT_BUY_CANDIDATE")
                || preview.ocoPreflightStatus() == null
                || preview.ocoPreflightStatus().startsWith("PASS")) {
            return reasons;
        }
        return reasons.stream()
                .filter(reason -> !"OCO_PREFLIGHT_FAILED".equals(reason))
                .toList();
    }

    private void addWarningIfAbsent(List<String> warnings, String warning) {
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    private boolean r3OverrideUsedInLast24h() {
        return executionAuditRepository.countByEventRiskOverrideUsedIsTrueAndCreatedAtAfter(
                LocalDateTime.now(ZoneOffset.UTC).minusHours(24)) > 0;
    }

    private boolean isAutoApproved(String mode) {
        return MODE_AUTO_APPROVED_TINY_LIVE.equals(mode)
                || MODE_AUTO_APPROVED_TINY_LIVE_WITH_EVENT_RISK_OVERRIDE.equals(mode)
                || MODE_AUTO_APPROVED_TINY_LIVE_WITH_RISK_OVERRIDE.equals(mode);
    }

    private String eventRiskLevel(String eventRiskStatus) {
        if (eventRiskStatus == null || eventRiskStatus.isBlank()) {
            return "UNKNOWN";
        }
        return eventRiskStatus.trim().split("\\s+")[0].toUpperCase(Locale.ROOT);
    }

    private boolean runtimeEvidenceAvailable(String runtimeEvidenceStatus) {
        return runtimeEvidenceStatus != null
                && runtimeEvidenceStatus.toUpperCase(Locale.ROOT).startsWith("AVAILABLE_CANONICAL");
    }

    private boolean booleanProperty(String key, boolean defaultValue) {
        return Boolean.parseBoolean(env.getProperty(key, Boolean.toString(defaultValue)));
    }

    private String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("auto approval token signing failed", e);
        }
    }

    private String shortHash(String value) {
        return sha256(value).substring(0, 16);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("sha256 failed", e);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return left != null && right != null
                && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private boolean contains(List<String> values, String needle) {
        return values != null && values.stream().anyMatch(v -> needle.equals(v));
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSide(String side) {
        if (side == null || side.isBlank()) return SIDE;
        String upper = side.trim().toUpperCase(Locale.ROOT);
        return "BUY".equals(upper) ? SIDE : upper;
    }

    private static String decimal(java.math.BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    public record ApprovalToken(String tokenId, String token, Instant expiresAt) {
    }

    public record Decision(String approvalMode,
                           String approvalReason,
                           String approvalInputs,
                           List<String> warnings,
                           List<String> blockers,
                           String previewHash,
                           BigDecimal missedAlphaBudgetRemaining,
                           BigDecimal maxLossIfWrongUsdt,
                           BigDecimal allowedMistakeBudgetUsed,
                           boolean eventRiskOverrideUsed,
                           String autoApprovalTokenId,
                           String autoApprovalToken,
                           Instant autoApprovalExpiresAt) {
        private Decision withToken(ApprovalToken token) {
            return new Decision(approvalMode, approvalReason, approvalInputs, warnings, blockers, previewHash,
                    missedAlphaBudgetRemaining, maxLossIfWrongUsdt, allowedMistakeBudgetUsed,
                    eventRiskOverrideUsed, token.tokenId(), token.token(), token.expiresAt());
        }

        public String render() {
            return """
                    === Tiny Live Auto Approval Preview ===
                    boundary=READ_ONLY; no order/OCO/strategy/grid/fund/Earn behavior changed.
                    writesRuntimeEvidence=false
                    orderSent=false
                    approvalMode=%s
                    approvalReason=%s
                    approvalInputs=%s
                    missedAlphaBudgetRemaining=%s
                    maxLossIfWrongUsdt=%s
                    allowedMistakeBudgetUsed=%s
                    warnings=%s
                    blockers=%s
                    eventRiskOverrideUsed=%s
                    autoApprovalEligible=%s
                    wouldGenerateToken=%s
                    autoApprovalPolicyVersion=%s
                    autoApprovalTokenId=%s
                    autoApprovalExpiresAt=%s
                    autoApprovalToken=%s
                    previewHash=%s
                    recommendedExecutionMode=TINY_LIVE_AUTO_APPROVAL_TOKEN_ONLY
                    """.formatted(
                    approvalMode,
                    approvalReason,
                    approvalInputs,
                    decimal(missedAlphaBudgetRemaining),
                    decimal(maxLossIfWrongUsdt),
                    decimal(allowedMistakeBudgetUsed),
                    warnings,
                    blockers,
                    eventRiskOverrideUsed,
                    isAutoApprovedMode(approvalMode),
                    isAutoApprovedMode(approvalMode) && autoApprovalToken != null,
                    POLICY_VERSION,
                    autoApprovalTokenId == null ? "UNAVAILABLE_NOT_APPROVED_OR_CONFIG_MISSING" : autoApprovalTokenId,
                    autoApprovalExpiresAt == null ? "UNAVAILABLE_NOT_APPROVED_OR_CONFIG_MISSING" : autoApprovalExpiresAt,
                    autoApprovalToken == null ? "UNAVAILABLE_NOT_APPROVED_OR_CONFIG_MISSING" : autoApprovalToken,
                    previewHash);
        }

        private static boolean isAutoApprovedMode(String mode) {
            return MODE_AUTO_APPROVED_TINY_LIVE.equals(mode)
                    || MODE_AUTO_APPROVED_TINY_LIVE_WITH_EVENT_RISK_OVERRIDE.equals(mode)
                    || MODE_AUTO_APPROVED_TINY_LIVE_WITH_RISK_OVERRIDE.equals(mode);
        }
    }

    private record BudgetSnapshot(BigDecimal missedAlphaBudgetRemaining,
                                  BigDecimal maxLossIfWrongUsdt,
                                  BigDecimal allowedMistakeBudgetUsed,
                                  boolean dailyLossBudgetReached,
                                  int consecutiveLosses) {
    }
}
