package com.agora.service.trading;

import com.agora.model.BtDecisionAudit;
import com.agora.model.BtLiveSignal;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.model.TinyLiveEventRiskOverrideToken;
import com.agora.model.TinyLiveExecutionAudit;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.repository.trading.TinyLiveEventRiskOverrideTokenRepository;
import com.agora.repository.trading.TinyLiveExecutionAuditRepository;
import com.agora.service.TelegramService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TinyLiveExecutionService {

    private static final String SYMBOL = "BTCUSDT";
    private static final long STRATEGY_ID = 574L;
    private static final BigDecimal MAX_NOTIONAL = new BigDecimal("5.00");

    private final TinyLiveMinimumOrderPreviewService previewService;
    private final TinyLiveExecutionAuditRepository executionAuditRepository;
    private final BtLiveSignalRepository liveSignalRepository;
    private final BtDecisionAuditRepository decisionAuditRepository;
    private final RuntimeDecisionEvidenceRepository evidenceRepository;
    private final TinyLiveEventRiskOverrideTokenRepository eventRiskOverrideTokenRepository;
    private final OkxTradingService okxTradingService;
    private final TelegramService telegramService;
    private final ObjectMapper objectMapper;
    private final Environment env;
    private final AutoApprovalPolicyService autoApprovalPolicyService;
    private final AutoExplorationRolloutStateService rolloutStateService;

    @Transactional(readOnly = true)
    public String listReadiness(String symbol, Long strategyId, String side) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? STRATEGY_ID : strategyId;
        TinyLiveMinimumOrderPreviewService.PreviewResult preview = previewService.preview(sym, sid, side);
        AutoApprovalPolicyService.Decision autoApproval = autoApprovalPolicyService.evaluate(preview);
        long ordersToday = executionAuditRepository.countByCreatedAtAfterAndOrderSentIsTrue(
                LocalDate.now(ZoneOffset.UTC).atStartOfDay());
        return """
                === Tiny Live Execution Readiness ===
                boundary: READ_ONLY; no order/OCO/strategy/grid/fund/Earn behavior changed.
                symbol=%s
                strategyId=%d
                side=%s
                dailyOrderCap=1
                ordersToday=%d
                openTinyLivePositions=%d
                previewStatus=%s
                allowedAfterManualApproval=%s
                previewHash=%s
                previewTokenId=%s
                autoApprovalEligible=%s
                autoApprovalMode=%s
                autoApprovalBlockers=%s
                missedAlphaBudgetRemaining=%s
                maxLossIfWrongUsdt=%s
                allowedMistakeBudgetUsed=%s
                eventRiskOverrideUsed=%s
                eventRisk=%s
                tokenEligible=%s
                blockers=%s
                warnings=%s
                orderSent=false
                """.formatted(
                preview.symbol(),
                preview.strategyId(),
                preview.side(),
                ordersToday,
                preview.currentSameStrategyTinyLiveOpenPositions(),
                preview.status(),
                preview.allowedAfterManualApproval(),
                preview.previewHash(),
                preview.previewTokenId() == null ? "UNAVAILABLE_CONFIG_MISSING" : preview.previewTokenId(),
                isAutoApprovalMode(autoApproval.approvalMode()),
                autoApproval.approvalMode(),
                autoApproval.blockers(),
                autoApproval.missedAlphaBudgetRemaining(),
                autoApproval.maxLossIfWrongUsdt(),
                autoApproval.allowedMistakeBudgetUsed(),
                autoApproval.eventRiskOverrideUsed(),
                preview.eventRiskStatus(),
                autoApproval.autoApprovalToken() != null,
                preview.denialReasons(),
                preview.warnings());
    }

    @Transactional(readOnly = true)
    public String listExecutions(String symbol, Integer minutes, Integer limit) {
        String sym = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        int mins = minutes == null ? 1440 : Math.max(1, Math.min(minutes, 30 * 24 * 60));
        int lim = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        List<TinyLiveExecutionAudit> rows = executionAuditRepository.findRecent(
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(mins), sym, PageRequest.of(0, lim));
        StringBuilder sb = new StringBuilder("=== Tiny Live Executions ===\n")
                .append("boundary: READ_ONLY; no order/OCO/strategy/grid/fund/Earn behavior changed.\n")
                .append("filters: symbol=").append(sym == null ? "ALL" : sym)
                .append(" minutes=").append(mins).append(" limit=").append(lim).append("\n\n");
        if (rows.isEmpty()) {
            sb.append("No tiny-live execution audit rows found in selected window.\n");
            List<TinyLiveExecutionAudit> latestRows = executionAuditRepository.findLatest(sym, PageRequest.of(0, 1));
            if (latestRows.isEmpty()) {
                return sb.append("No latest known tiny-live execution audit row exists.").toString();
            }
            sb.append("latestKnownTinyLiveExecutionOutsideWindow:\n");
            appendExecutionRow(sb, latestRows.get(0), 1);
            return sb.toString();
        }
        int i = 1;
        for (TinyLiveExecutionAudit row : rows) {
            appendExecutionRow(sb, row, i++);
        }
        return sb.toString();
    }

    private void appendExecutionRow(StringBuilder sb, TinyLiveExecutionAudit row, int index) {
        sb.append(index).append(". #").append(row.getId())
                .append(" createdAt=").append(row.getCreatedAt() == null ? "N/A" : row.getCreatedAt())
                .append(" status=").append(row.getStatus())
                .append(" symbol=").append(row.getSymbol())
                .append(" strategy=").append(row.getStrategyId())
                .append(" side=").append(row.getSide())
                .append(" approvalMode=").append(nullToNA(row.getApprovalMode()))
                .append(" approvalTokenId=").append(nullToNA(row.getApprovalTokenId()))
                .append(" approvalTokenType=").append(nullToNA(row.getApprovalTokenType()))
                .append(" autoApprovalPolicyVersion=").append(nullToNA(row.getAutoApprovalPolicyVersion()))
                .append(" eventRiskOverrideUsed=").append(Boolean.TRUE.equals(row.getEventRiskOverrideUsed()))
                .append(" orderSent=").append(row.getOrderSent())
                .append(" ocoAttached=").append(row.getOcoAttached())
                .append(" orderId=").append(nullToNA(row.getOrderId()))
                .append(" ocoAlgoId=").append(row.getOcoAlgoId() == null ? "N/A" : row.getOcoAlgoId())
                .append(" notional=").append(row.getNotionalUsdt())
                .append(" qty=").append(row.getQty())
                .append(" previewHash=").append(row.getPreviewHash())
                .append(" denial=").append(nullToNA(row.getDenialReason()))
                .append("\n");
    }

    @Transactional(readOnly = true)
    public String listEventRiskOverrideTokens(String symbol, Integer minutes, Integer limit) {
        String sym = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        int mins = minutes == null ? 1440 : Math.max(1, Math.min(minutes, 30 * 24 * 60));
        int lim = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<TinyLiveEventRiskOverrideToken> rows = eventRiskOverrideTokenRepository.findRecent(
                now.minusMinutes(mins), sym, PageRequest.of(0, lim));
        StringBuilder sb = new StringBuilder("=== Tiny Live Event Risk Override Tokens ===\n")
                .append("boundary: READ_ONLY; token values are not returned; no order/OCO/strategy/grid/fund/Earn behavior changed.\n")
                .append("filters: symbol=").append(sym == null ? "ALL" : sym)
                .append(" minutes=").append(mins).append(" limit=").append(lim).append("\n\n");
        if (rows.isEmpty()) {
            return sb.append("No tiny-live event-risk override token audit rows found.").toString();
        }
        int i = 1;
        for (TinyLiveEventRiskOverrideToken row : rows) {
            sb.append(i++).append(". #").append(row.getId())
                    .append(" tokenId=").append(nullToNA(row.getTokenId()))
                    .append(" status=").append(tokenStatus(row, now))
                    .append(" createdAt=").append(row.getCreatedAt())
                    .append(" expiresAt=").append(row.getExpiresAt())
                    .append(" usedAt=").append(row.getUsedAt() == null ? "N/A" : row.getUsedAt())
                    .append(" symbol=").append(row.getSymbol())
                    .append(" strategy=").append(row.getStrategyId())
                    .append(" side=").append(row.getSide())
                    .append(" notionalUsdt=").append(row.getNotionalUsdt())
                    .append(" previewHash=").append(row.getPreviewHash())
                    .append(" reason=").append(nullToNA(row.getReason()))
                    .append("\n");
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public String autoExecutionTriggerStatus(String symbol, Long strategyId, String side) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? STRATEGY_ID : strategyId;
        String normalizedSide = normalizeSide(side);
        boolean triggerEnabled = Boolean.parseBoolean(env.getProperty(
                "trading.tiny-live.auto-execution.enabled", "false"));
        boolean triggerDryRun = Boolean.parseBoolean(env.getProperty(
                "trading.tiny-live.auto-execution.dry-run", "true"));
        String fixedDelay = env.getProperty("trading.tiny-live.auto-execution.fixed-delay-ms", "60000");
        String initialDelay = env.getProperty("trading.tiny-live.auto-execution.initial-delay-ms", "30000");
        return """
                === Tiny Live Auto Execution Trigger Status ===
                boundary: READ_ONLY; no order/OCO/strategy/grid/fund/Earn behavior changed.
                symbol=%s
                strategyId=%d
                side=%s
                triggerInstalled=true
                triggerEnabled=%s
                triggerDryRun=%s
                fixedDelayMs=%s
                initialDelayMs=%s
                executionPath=executeAutoApprovedTinyLiveIfEligible
                hardScope=BTCUSDT/574/LONG/5USDT
                requiresEnabled=true
                requiresDryRunFalseForRealOrder=true
                orderSent=false
                previewSummary:
                %s
                """.formatted(sym, sid, normalizedSide, triggerEnabled, triggerDryRun,
                fixedDelay, initialDelay, indent(previewAutoExecution(sym, sid, normalizedSide)));
    }

    @Transactional
    public String createEventRiskOverrideToken(String symbol,
                                               Long strategyId,
                                               String side,
                                               BigDecimal notionalUsdt,
                                               String previewHash,
                                               String reason) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? STRATEGY_ID : strategyId;
        String normalizedSide = normalizeSide(side);
        BigDecimal notional = notionalUsdt == null ? MAX_NOTIONAL : notionalUsdt;
        TinyLiveMinimumOrderPreviewService.PreviewResult preview = previewService.preview(sym, sid, normalizedSide);
        if (!SYMBOL.equals(sym) || sid != STRATEGY_ID || !"LONG".equals(normalizedSide)
                || notional.compareTo(MAX_NOTIONAL) != 0) {
            return overrideCreateRejected("SCOPE_NOT_ALLOWLISTED_OR_NOTIONAL_INVALID", preview);
        }
        if (previewHash == null || previewHash.isBlank()
                || !constantTimeEquals(previewHash.trim(), preview.previewHash())) {
            return overrideCreateRejected("PREVIEW_HASH_MISMATCH", preview);
        }
        if (reason == null || reason.isBlank()) {
            return overrideCreateRejected("REASON_REQUIRED", preview);
        }
        if (!"R3".equals(eventRiskLevel(preview.eventRiskStatus()))) {
            return overrideCreateRejected("EVENT_RISK_NOT_R3", preview);
        }
        if (!preview.denialReasons().contains("EVENT_RISK_HIGH")) {
            return overrideCreateRejected("EVENT_RISK_HIGH_REASON_NOT_PRESENT", preview);
        }
        if (eventRiskOverrideTokenRepository.countByStatusAndCreatedAtAfter("USED",
                LocalDateTime.now(ZoneOffset.UTC).minusHours(24)) > 0) {
            return overrideCreateRejected("EVENT_RISK_OVERRIDE_DAILY_CAP_REACHED", preview);
        }
        if (overrideTokenSecret().isBlank()) {
            return overrideCreateRejected("EVENT_RISK_OVERRIDE_TOKEN_SECRET_MISSING", preview);
        }

        Instant expiresAt = Instant.now().plusSeconds(overrideTtlSeconds());
        String tokenId = "tero_" + sha256(preview.previewHash() + "|" + expiresAt).substring(0, 16);
        String payload = tokenId + "|" + preview.previewHash() + "|" + sym + "|" + sid + "|"
                + normalizedSide + "|" + MAX_NOTIONAL + "|" + expiresAt.getEpochSecond();
        String token = payload + "|" + hmac(overrideTokenSecret(), payload);

        TinyLiveEventRiskOverrideToken row = new TinyLiveEventRiskOverrideToken();
        row.setTokenId(tokenId);
        row.setTokenHash(sha256(token));
        row.setExpiresAt(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        row.setSymbol(sym);
        row.setStrategyId(sid);
        row.setSide(normalizedSide);
        row.setNotionalUsdt(MAX_NOTIONAL);
        row.setPreviewHash(preview.previewHash());
        row.setStatus("CREATED");
        row.setReason(truncate(reason, 500));
        eventRiskOverrideTokenRepository.save(row);
        telegramService.sendAlert("Tiny-live event-risk override token created. tokenId=" + tokenId
                        + " symbol=" + sym + " strategyId=" + sid + " previewHash=" + preview.previewHash()
                        + " reason=" + truncate(reason, 220),
                false, "TinyLiveEventRiskOverride", "WARN");
        return """
                === Tiny Live Event Risk Override Token ===
                boundary: LOCAL_ONLY CONTROL; no order/OCO/strategy/grid/fund/Earn behavior changed.
                orderSent=false
                tokenCreated=true
                tokenId=%s
                tokenExpiresAt=%s
                symbol=%s
                strategyId=%d
                side=%s
                notionalUsdt=%s
                previewHash=%s
                eventRisk=%s
                token=%s
                nextReadOnlyCheck=previewTinyLiveAutoExecution(symbol,strategyId,side,eventRiskOverrideToken)
                """.formatted(tokenId, expiresAt, sym, sid, normalizedSide, MAX_NOTIONAL,
                preview.previewHash(), preview.eventRiskStatus(), token);
    }

    @Transactional(readOnly = true)
    public String previewAutoExecution(String symbol, Long strategyId, String side) {
        return previewAutoExecution(symbol, strategyId, side, null);
    }

    @Transactional(readOnly = true)
    public String previewAutoExecution(String symbol, Long strategyId, String side, String eventRiskOverrideToken) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? STRATEGY_ID : strategyId;
        String normalizedSide = normalizeSide(side);
        TinyLiveMinimumOrderPreviewService.PreviewResult preview = previewService.preview(sym, sid, normalizedSide);
        OverrideCheck override = checkEventRiskOverrideToken(eventRiskOverrideToken, preview, preview.previewHash(), false);
        AutoApprovalPolicyService.Decision approval = autoApprovalPolicyService.evaluate(preview, override.valid());
        boolean previewReady = previewReadyForExecution(preview, approval);
        boolean executionEligible = isAutoApprovalMode(approval.approvalMode())
                && approval.autoApprovalToken() != null
                && preview.previewToken() != null
                && previewReady;
        List<String> terminalBlockers = terminalBlockers(preview, approval);
        List<String> overridableBlockers = overridableBlockers(preview, approval);
        boolean overrideWouldHelp = !overridableBlockers.isEmpty() && terminalBlockers.isEmpty() && !override.valid();
        String nextRequiredAction = nextRequiredAction(terminalBlockers, overridableBlockers, override.valid());
        String blockedUntil = terminalBlockers.contains("DUPLICATE_BAR") ? "NEXT_DISTINCT_BAR" : "N/A";
        return """
                === Tiny Live Auto Execution Preview ===
                boundary: READ_ONLY PREVIEW ONLY; no order/OCO/strategy/grid/fund/Earn behavior changed.
                writesRuntimeEvidence=false
                orderSent=false
                wouldExecute=%s
                executionEligible=%s
                symbol=%s
                strategyId=%d
                side=%s
                approvalMode=%s
                approvalReason=%s
                approvalTokenEligible=%s
                eventRiskOverrideRequired=%s
                eventRiskOverrideTokenValid=%s
                eventRiskOverrideTokenId=%s
                previewStatus=%s
                previewHash=%s
                runtimeEvidenceStatus=%s
                duplicateBarMode=%s
                duplicateBarReason=%s
                currentOpportunityKey=%s
                lastOpportunityKey=%s
                isDistinctOpportunity=%s
                blockers=%s
                terminalBlockers=%s
                overridableBlockers=%s
                overrideWouldHelp=%s
                nextRequiredAction=%s
                blockedUntil=%s
                warnings=%s
                eventRisk=%s
                missedAlphaBudgetRemaining=%s
                maxLossIfWrongUsdt=%s
                allowedMistakeBudgetUsed=%s
                eventRiskOverrideUsed=%s
                recommendedExecutionMode=TINY_LIVE_AUTO_APPROVAL_TOKEN_ONLY
                """.formatted(
                executionEligible,
                executionEligible,
                preview.symbol(),
                preview.strategyId(),
                preview.side(),
                approval.approvalMode(),
                approval.approvalReason(),
                approval.autoApprovalToken() != null,
                "R3".equals(eventRiskLevel(preview.eventRiskStatus())) && !override.valid(),
                override.valid(),
                nullToNA(override.tokenId()),
                preview.status(),
                preview.previewHash(),
                preview.runtimeEvidenceStatus(),
                preview.duplicateBarMode(),
                preview.duplicateBarReason(),
                preview.currentOpportunityKey(),
                preview.lastOpportunityKey() == null ? "N/A" : preview.lastOpportunityKey(),
                preview.distinctOpportunity(),
                approval.blockers(),
                terminalBlockers,
                overridableBlockers,
                overrideWouldHelp,
                nextRequiredAction,
                blockedUntil,
                approval.warnings(),
                preview.eventRiskStatus(),
                approval.missedAlphaBudgetRemaining(),
                approval.maxLossIfWrongUsdt(),
                approval.allowedMistakeBudgetUsed(),
                approval.eventRiskOverrideUsed());
    }

    @Transactional
    public String executeAutoApprovedTinyLiveIfEligible() {
        TinyLiveMinimumOrderPreviewService.PreviewResult preview = previewService.preview(SYMBOL, STRATEGY_ID, "LONG");
        AutoApprovalPolicyService.Decision approval = autoApprovalPolicyService.evaluate(preview);
        if (!isAutoApprovalMode(approval.approvalMode())
                || approval.autoApprovalToken() == null
                || preview.previewToken() == null) {
            return """
                    === Tiny Live Autonomous Execution Attempt ===
                    boundary: INTERNAL_AUTONOMOUS_TINY_LIVE; no full autonomous execution enabled.
                    status=BLOCKED_BEFORE_EXECUTION
                    approvalMode=%s
                    approvalReason=%s
                    blockers=%s
                    warnings=%s
                    orderSent=false
                    """.formatted(approval.approvalMode(), approval.approvalReason(),
                    approval.blockers(), approval.warnings());
        }
        return executeWithApproval(preview.previewToken(), approval.autoApprovalToken(), SYMBOL, STRATEGY_ID,
                "LONG", preview.previewHash(), "AutoApprovalPolicy " + AutoApprovalPolicyService.POLICY_VERSION, null);
    }

    @Transactional
    public String executeWithApproval(String previewToken,
                                      String approvalToken,
                                      String symbol,
                                      Long strategyId,
                                      String side,
                                      String expectedPreviewHash,
                                      String humanReason) {
        return executeWithApproval(previewToken, approvalToken, symbol, strategyId, side, expectedPreviewHash,
                humanReason, null);
    }

    @Transactional
    public String executeWithApproval(String previewToken,
                                      String approvalToken,
                                      String symbol,
                                      Long strategyId,
                                      String side,
                                      String expectedPreviewHash,
                                      String humanReason,
                                      String eventRiskOverrideToken) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? STRATEGY_ID : strategyId;
        String normalizedSide = normalizeSide(side);
        TinyLiveMinimumOrderPreviewService.PreviewResult preview = previewService.preview(sym, sid, normalizedSide);
        String approvalHash = sha256(nullToEmpty(approvalToken));
        OverrideCheck override = checkEventRiskOverrideToken(eventRiskOverrideToken, preview, expectedPreviewHash, false);
        ApprovalContext approval = approvalContext(approvalToken, humanReason, override.valid());

        Optional<String> rejection = rejectionReason(preview, previewToken, approvalToken, expectedPreviewHash, approval, approvalHash);
        if (rejection.isPresent()) {
            return rejectReceipt(preview, approvalHash, approval, rejection.get());
        }
        Optional<String> overrideRejection = consumeOverrideIfNeeded(eventRiskOverrideToken, preview, expectedPreviewHash, approval);
        if (overrideRejection.isPresent()) {
            return rejectReceipt(preview, approvalHash, approval, overrideRejection.get());
        }

        TinyLiveExecutionAudit audit = baseAudit(preview, approvalHash, approval);
        audit.setStatus("ORDER_PLACEMENT_STARTED");
        executionAuditRepository.save(audit);

        TradeResult buy;
        try {
            buy = okxTradingService.placeMarketBuy(sym, preview.proposedNotionalUsdt().doubleValue());
        } catch (Exception e) {
            audit.setStatus("ORDER_FAILED");
            audit.setDenialReason("OKX_ORDER_FAILED: " + truncate(e.getMessage(), 420));
            audit.setReceiptJson(receipt(preview, audit, "ORDER_FAILED", e.getMessage(), false));
            executionAuditRepository.save(audit);
            return renderExecutionReceipt(audit);
        }

        audit.setOrderSent(true);
        audit.setOrderId(buy.getOrderId());
        audit.setQty(buy.getQty());
        audit.setEntryPrice(buy.getAvgPrice());
        audit.setNotionalUsdt(buy.getQty().multiply(buy.getAvgPrice()).setScale(8, RoundingMode.HALF_UP));
        audit.setMaxLossUsdt(maxLoss(buy.getQty(), buy.getAvgPrice(), preview.sl()));
        executionAuditRepository.save(audit);

        Long ocoAlgoId = null;
        try {
            ocoAlgoId = okxTradingService.placeOco(sym, buy.getQty(), preview.tp(), preview.sl());
            audit.setOcoAttached(true);
            audit.setOcoAlgoId(ocoAlgoId);
            audit.setStatus("EXECUTED_OCO_ATTACHED");
        } catch (Exception e) {
            audit.setStatus("CRITICAL_UNPROTECTED_TINY_LIVE");
            audit.setDenialReason("OCO_ATTACH_FAILED: " + truncate(e.getMessage(), 420));
            audit.setReceiptJson(receipt(preview, audit, "CRITICAL_UNPROTECTED_TINY_LIVE", e.getMessage(), true));
            executionAuditRepository.save(audit);
            writeDecisionAndEvidence(audit, preview, "CRITICAL_UNPROTECTED_TINY_LIVE", true);
            telegramService.sendAlert("CRITICAL_UNPROTECTED_TINY_LIVE BTCUSDT tiny-live order placed but OCO attach failed. orderId="
                    + buy.getOrderId() + " qty=" + buy.getQty() + " error=" + e.getMessage(),
                    false, "TinyLiveExecution", "CRITICAL");
            return renderExecutionReceipt(audit);
        }

        BtLiveSignal signal = createLiveSignal(preview, buy, ocoAlgoId, audit.getApprovalMode());
        audit.setLiveSignalId(signal.getId());
        audit.setReceiptJson(receipt(preview, audit, "EXECUTED_OCO_ATTACHED", null, true));
        executionAuditRepository.save(audit);
        writeDecisionAndEvidence(audit, preview, "EXECUTED_OCO_ATTACHED", true);
        telegramService.sendAlert("Tiny-live BTCUSDT executed with " + approval.mode() + ". orderId="
                        + buy.getOrderId() + " ocoAlgoId=" + ocoAlgoId + " notional=" + audit.getNotionalUsdt(),
                false, "TinyLiveExecution", "INFO");
        return renderExecutionReceipt(audit);
    }

    private Optional<String> rejectionReason(TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                             String previewToken,
                                             String approvalToken,
                                             String expectedPreviewHash,
                                             ApprovalContext approval,
                                             String approvalHash) {
        if (!SYMBOL.equals(preview.symbol()) || preview.strategyId() != STRATEGY_ID || !"LONG".equals(preview.side())) {
            return Optional.of("SCOPE_NOT_ALLOWLISTED");
        }
        if (!previewReadyForExecution(preview, approval)) {
            return Optional.of("PREVIEW_NOT_READY: " + preview.denialReasons());
        }
        if (preview.proposedNotionalUsdt() == null || preview.proposedNotionalUsdt().compareTo(MAX_NOTIONAL) > 0) {
            return Optional.of("MAX_NOTIONAL_EXCEEDED");
        }
        if (!previewService.verifyPreviewToken(previewToken, expectedPreviewHash, preview)) {
            return Optional.of("PREVIEW_TOKEN_OR_HASH_INVALID");
        }
        if (approval.mode().equals("HUMAN_APPROVAL_REQUIRED") && approval.reason().isBlank()) {
            return Optional.of("HUMAN_REASON_REQUIRED");
        }
        boolean approvalValid = isAutoApprovalMode(approval.mode())
                ? autoApprovalPolicyService.verifyAutoApprovalToken(approvalToken, expectedPreviewHash, preview)
                : approval.valid();
        if (!approvalValid) {
            return Optional.of("APPROVAL_TOKEN_INVALID");
        }
        if (executionAuditRepository.existsByApprovalTokenHash(approvalHash)) {
            return Optional.of("APPROVAL_TOKEN_ALREADY_USED");
        }
        long maxOrdersPerDay = rolloutStateService.effectiveMaxOrdersPerDay(preview.symbol(), preview.strategyId(), preview.side());
        if (executionAuditRepository.countByCreatedAtAfterAndOrderSentIsTrue(LocalDate.now(ZoneOffset.UTC).atStartOfDay()) >= maxOrdersPerDay) {
            return Optional.of("MAX_TINY_LIVE_ORDERS_TODAY_REACHED");
        }
        return Optional.empty();
    }

    private Optional<String> consumeOverrideIfNeeded(String eventRiskOverrideToken,
                                                     TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                                     String expectedPreviewHash,
                                                     ApprovalContext approval) {
        if (!isEventRiskOverrideApprovalMode(approval.mode())) {
            return Optional.empty();
        }
        OverrideCheck override = checkEventRiskOverrideToken(eventRiskOverrideToken, preview, expectedPreviewHash, true);
        if (!override.valid()) {
            return Optional.of("EVENT_RISK_OVERRIDE_TOKEN_INVALID: " + override.reason());
        }
        telegramService.sendAlert("Tiny-live event-risk override token consumed. tokenId=" + override.tokenId()
                        + " symbol=" + preview.symbol() + " strategyId=" + preview.strategyId()
                        + " previewHash=" + preview.previewHash(),
                false, "TinyLiveEventRiskOverride", "WARN");
        return Optional.empty();
    }

    private OverrideCheck checkEventRiskOverrideToken(String token,
                                                      TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                                      String expectedPreviewHash,
                                                      boolean consume) {
        if (token == null || token.isBlank()) {
            return new OverrideCheck(false, null, "TOKEN_MISSING");
        }
        if (overrideTokenSecret().isBlank()) {
            return new OverrideCheck(false, null, "TOKEN_SECRET_MISSING");
        }
        String[] parts = token.trim().split("\\|");
        if (parts.length != 8 || !parts[0].startsWith("tero_")) {
            return new OverrideCheck(false, null, "TOKEN_FORMAT_INVALID");
        }
        String tokenId = parts[0];
        String hash = parts[1];
        String symbol = parts[2];
        String strategyId = parts[3];
        String side = parts[4];
        String notional = parts[5];
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[6]);
        } catch (NumberFormatException e) {
            return new OverrideCheck(false, tokenId, "TOKEN_EXPIRY_INVALID");
        }
        String payload = tokenId + "|" + hash + "|" + symbol + "|" + strategyId + "|" + side + "|" + notional + "|" + expiresAt;
        if (!constantTimeEquals(parts[7], hmac(overrideTokenSecret(), payload))) {
            return new OverrideCheck(false, tokenId, "TOKEN_SIGNATURE_INVALID");
        }
        if (Instant.now().isAfter(Instant.ofEpochSecond(expiresAt))) {
            return new OverrideCheck(false, tokenId, "TOKEN_EXPIRED");
        }
        if (!constantTimeEquals(hash, expectedPreviewHash == null ? null : expectedPreviewHash.trim())
                || !constantTimeEquals(hash, preview.previewHash())) {
            return new OverrideCheck(false, tokenId, "PREVIEW_HASH_MISMATCH");
        }
        if (!SYMBOL.equals(symbol) || !String.valueOf(STRATEGY_ID).equals(strategyId)
                || !"LONG".equals(side) || !MAX_NOTIONAL.toPlainString().equals(notional)) {
            return new OverrideCheck(false, tokenId, "TOKEN_SCOPE_INVALID");
        }
        if (!"R3".equals(eventRiskLevel(preview.eventRiskStatus()))
                || !preview.denialReasons().contains("EVENT_RISK_HIGH")) {
            return new OverrideCheck(false, tokenId, "EVENT_RISK_OVERRIDE_NOT_APPLICABLE");
        }
        TinyLiveEventRiskOverrideToken row = eventRiskOverrideTokenRepository.findByTokenHash(sha256(token))
                .orElse(null);
        if (row == null) {
            return new OverrideCheck(false, tokenId, "TOKEN_AUDIT_NOT_FOUND");
        }
        if (!"CREATED".equals(row.getStatus()) || row.getUsedAt() != null) {
            return new OverrideCheck(false, tokenId, "TOKEN_ALREADY_USED");
        }
        if (LocalDateTime.now(ZoneOffset.UTC).isAfter(row.getExpiresAt())) {
            return new OverrideCheck(false, tokenId, "TOKEN_EXPIRED");
        }
        if (consume) {
            row.setStatus("USED");
            row.setUsedAt(LocalDateTime.now(ZoneOffset.UTC));
            eventRiskOverrideTokenRepository.save(row);
        }
        return new OverrideCheck(true, tokenId, "OK");
    }

    private boolean approvalTokenValid(String approvalToken) {
        String configuredHash = env.getProperty("trading.tiny-live.approval-token-sha256", "");
        return configuredHash != null && !configuredHash.isBlank()
                && approvalToken != null && !approvalToken.isBlank()
                && constantTimeEquals(configuredHash.trim(), sha256(approvalToken.trim()));
    }

    private ApprovalContext approvalContext(String approvalToken, String humanReason, boolean eventRiskOverrideAllowed) {
        if (autoApprovalPolicyService.looksLikeAutoApprovalToken(approvalToken)) {
            String mode = autoApprovalPolicyService.approvalModeFromToken(approvalToken);
            if (eventRiskOverrideAllowed
                    && AutoApprovalPolicyService.MODE_AUTO_APPROVED_TINY_LIVE_WITH_EVENT_RISK_OVERRIDE.equals(mode)) {
                mode = AutoApprovalPolicyService.MODE_AUTO_APPROVED_TINY_LIVE_WITH_EVENT_RISK_OVERRIDE;
            }
            return new ApprovalContext(mode,
                    autoApprovalPolicyService.tokenId(approvalToken),
                    humanReason == null || humanReason.isBlank() ? "AutoApprovalPolicy v0" : humanReason.trim(),
                    true);
        }
        return new ApprovalContext("HUMAN_APPROVAL_REQUIRED",
                "human_" + sha256(nullToEmpty(approvalToken)).substring(0, 16),
                humanReason == null ? "" : humanReason.trim(),
                approvalTokenValid(approvalToken));
    }

    private String rejectReceipt(TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                 String approvalHash,
                                 ApprovalContext approval,
                                 String reason) {
        TinyLiveExecutionAudit audit = baseAudit(preview, approvalHash, approval);
        audit.setStatus("REJECTED");
        audit.setDenialReason(reason);
        audit.setReceiptJson(receipt(preview, audit, "REJECTED", reason, false));
        return renderExecutionReceipt(audit);
    }

    private TinyLiveExecutionAudit baseAudit(TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                             String approvalHash,
                                             ApprovalContext approval) {
        TinyLiveExecutionAudit audit = new TinyLiveExecutionAudit();
        audit.setStatus("PENDING");
        audit.setSymbol(preview.symbol());
        audit.setStrategyId(preview.strategyId());
        audit.setSide(preview.side());
        audit.setPreviewTokenId(preview.previewTokenId());
        audit.setPreviewHash(preview.previewHash());
        audit.setApprovalTokenHash(approvalHash);
        audit.setApprovalMode(approval.mode());
        audit.setApprovalTokenId(approval.tokenId());
        audit.setApprovalTokenType(isAutoApprovalMode(approval.mode()) ? "AUTO" : "HUMAN");
        audit.setAutoApprovalPolicyVersion(isAutoApprovalMode(approval.mode())
                ? AutoApprovalPolicyService.POLICY_VERSION
                : null);
        audit.setEventRiskOverrideUsed(isEventRiskOverrideApprovalMode(approval.mode()));
        audit.setHumanReason(truncate(approval.reason(), 500));
        audit.setWarningsJson(toJson(preview.warnings()));
        audit.setBlockersJson(toJson(preview.denialReasons()));
        audit.setNotionalUsdt(preview.proposedNotionalUsdt());
        audit.setQty(preview.proposedQty());
        audit.setEntryPrice(preview.entry());
        audit.setTpPrice(preview.tp());
        audit.setSlPrice(preview.sl());
        audit.setMaxLossUsdt(maxLoss(preview.proposedQty(), preview.entry(), preview.sl()));
        audit.setPolicyMode("ALLOW_PROBE_ENTRY_DRY_RUN");
        audit.setTqsBand("PROBE_DRY_RUN");
        return audit;
    }

    private BtLiveSignal createLiveSignal(TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                          TradeResult buy,
                                          Long ocoAlgoId,
                                          String approvalMode) {
        BtLiveSignal signal = new BtLiveSignal();
        signal.setStrategyId(preview.strategyId());
        signal.setSymbol(preview.symbol());
        signal.setIntervalCode("TINY");
        signal.setBarOpenTime(LocalDateTime.now(ZoneOffset.UTC));
        signal.setEntryPrice(preview.entry());
        signal.setSuggestedSl(preview.sl());
        signal.setSuggestedTp(preview.tp());
        signal.setScore(new BigDecimal("0.0000"));
        signal.setNnOutput(new BigDecimal("0.0000"));
        signal.setNotifiedAt(LocalDateTime.now(ZoneOffset.UTC));
        signal.setAutoTraded(true);
        signal.setExchangeOrderId("TINY_LIVE:" + buy.getOrderId());
        signal.setActualEntryPrice(buy.getAvgPrice());
        signal.setTradedQty(buy.getQty());
        signal.setOcoQty(buy.getQty());
        signal.setOcoOrderListId(ocoAlgoId);
        signal.setSide("LONG");
        signal.setFilterReason("TINY_LIVE_MANUAL_APPROVAL");
        if (isAutoApprovalMode(approvalMode)) {
            signal.setFilterReason("TINY_LIVE_AUTO_APPROVAL");
        }
        return liveSignalRepository.save(signal);
    }

    private void writeDecisionAndEvidence(TinyLiveExecutionAudit exec,
                                          TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                          String outcome,
                                          boolean orderSent) {
        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setEventTime(LocalDateTime.now(ZoneOffset.UTC));
        audit.setStrategyId(preview.strategyId());
        audit.setSymbol(preview.symbol());
        audit.setIntervalCode("TINY");
        audit.setEventType("TINY_LIVE_EXECUTION");
        audit.setOutcome(outcome.startsWith("CRITICAL") ? "ERROR" : "PASS");
        audit.setBlocker(isAutoApprovalMode(exec.getApprovalMode())
                ? "AutoApprovedTinyLive"
                : "HumanApprovedTinyLive");
        audit.setReason(outcome);
        audit.setLiveSignalId(exec.getLiveSignalId());
        audit.setContextJson(exec.getReceiptJson());
        audit = decisionAuditRepository.save(audit);
        exec.setDecisionAuditId(audit.getId());

        RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        evidence.setDecisionId(audit.getId());
        evidence.setEvidenceTime(LocalDateTime.now(ZoneOffset.UTC));
        evidence.setSymbol(preview.symbol());
        evidence.setSide(preview.side());
        evidence.setStrategyId(preview.strategyId());
        evidence.setIntervalCode("TINY");
        evidence.setLiveSignalId(exec.getLiveSignalId());
        evidence.setSignalSource("HUMAN_APPROVED_TINY_LIVE");
        if (isAutoApprovalMode(exec.getApprovalMode())) {
            evidence.setSignalSource("AUTO_APPROVED_TINY_LIVE");
        }
        evidence.setFeaturesSnapshotJson(exec.getReceiptJson());
        evidence.setFreshnessState("PASS_PREVIEW_RECHECK");
        evidence.setSelectedAction("TINY_LIVE_EXECUTE_WITH_APPROVAL");
        evidence.setReason(outcome);
        evidence.setPolicyMode(exec.getPolicyMode());
        evidence.setFinalOutcome(outcome);
        evidence.setOrderSent(orderSent);
        evidence.setExecutionMode("TINY_LIVE_MANUAL_APPROVAL");
        if (isAutoApprovalMode(exec.getApprovalMode())) {
            evidence.setExecutionMode("TINY_LIVE_AUTO_APPROVED");
        }
        evidence.setSuppressionReason(null);
        evidence.setOcoOrderListId(exec.getOcoAlgoId() == null ? null : String.valueOf(exec.getOcoAlgoId()));
        evidence.setExecutionPreviewJson(exec.getReceiptJson());
        evidence = evidenceRepository.save(evidence);
        exec.setRuntimeEvidenceId(evidence.getId());
        executionAuditRepository.save(exec);
    }

    private String receipt(TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                           TinyLiveExecutionAudit audit,
                           String status,
                           String error,
                           boolean orderAttempted) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("version", "p5-v0");
            node.put("status", status);
            node.put("symbol", preview.symbol());
            node.put("strategyId", preview.strategyId());
            node.put("side", preview.side());
            node.put("previewTokenId", preview.previewTokenId());
            node.put("previewHash", preview.previewHash());
            node.put("approvalMode", audit.getApprovalMode());
            node.put("approvalTokenId", audit.getApprovalTokenId());
            node.put("approvalTokenType", audit.getApprovalTokenType());
            node.put("autoApprovalPolicyVersion", audit.getAutoApprovalPolicyVersion());
            node.put("eventRiskOverrideUsed", Boolean.TRUE.equals(audit.getEventRiskOverrideUsed()));
            node.put("humanReason", audit.getHumanReason());
            node.put("orderId", audit.getOrderId());
            node.put("ocoAlgoId", audit.getOcoAlgoId());
            node.put("notionalUsdt", decimal(audit.getNotionalUsdt()));
            node.put("qty", decimal(audit.getQty()));
            node.put("entryPrice", decimal(audit.getEntryPrice()));
            node.put("tp", decimal(audit.getTpPrice()));
            node.put("sl", decimal(audit.getSlPrice()));
            node.put("maxLossUsdt", decimal(audit.getMaxLossUsdt()));
            node.put("policyMode", audit.getPolicyMode());
            node.put("tqsBand", audit.getTqsBand());
            node.put("orderSent", Boolean.TRUE.equals(audit.getOrderSent()));
            node.put("ocoAttached", Boolean.TRUE.equals(audit.getOcoAttached()));
            node.put("orderAttempted", orderAttempted);
            node.put("autonomousExecutionEnabled", false);
            node.put("aiOnlyExecution", isAutoApprovalMode(audit.getApprovalMode()));
            node.put("warnings", audit.getWarningsJson());
            node.put("blockers", audit.getBlockersJson());
            if (error != null) node.put("error", error);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"status\":\"" + status + "\",\"receiptError\":\"" + e.getMessage() + "\"}";
        }
    }

    private String renderExecutionReceipt(TinyLiveExecutionAudit audit) {
        return """
                === Tiny Live Execution Receipt ===
                boundary: HUMAN_APPROVED_WRITE; no full autonomous execution enabled.
                status=%s
                symbol=%s
                strategyId=%d
                side=%s
                orderSent=%s
                ocoAttached=%s
                approvalMode=%s
                approvalTokenId=%s
                approvalTokenType=%s
                autoApprovalPolicyVersion=%s
                eventRiskOverrideUsed=%s
                orderId=%s
                ocoAlgoId=%s
                notionalUsdt=%s
                qty=%s
                entryPrice=%s
                tp=%s
                sl=%s
                denialReason=%s
                autonomousExecutionEnabled=false
                aiOnlyExecution=%s
                strategyGridFundEarnChanged=false
                receipt=%s
        """.formatted(audit.getStatus(), audit.getSymbol(), audit.getStrategyId(), audit.getSide(),
                audit.getOrderSent(), audit.getOcoAttached(), nullToNA(audit.getApprovalMode()),
                nullToNA(audit.getApprovalTokenId()), nullToNA(audit.getApprovalTokenType()),
                nullToNA(audit.getAutoApprovalPolicyVersion()), Boolean.TRUE.equals(audit.getEventRiskOverrideUsed()),
                nullToNA(audit.getOrderId()),
                audit.getOcoAlgoId() == null ? "N/A" : audit.getOcoAlgoId(),
                audit.getNotionalUsdt(), audit.getQty(), audit.getEntryPrice(), audit.getTpPrice(),
                audit.getSlPrice(), nullToNA(audit.getDenialReason()),
                isAutoApprovalMode(audit.getApprovalMode()), audit.getReceiptJson());
    }

    private boolean isAutoApprovalMode(String mode) {
        return AutoApprovalPolicyService.MODE_AUTO_APPROVED_TINY_LIVE.equals(mode)
                || AutoApprovalPolicyService.MODE_AUTO_APPROVED_TINY_LIVE_WITH_EVENT_RISK_OVERRIDE.equals(mode)
                || AutoApprovalPolicyService.MODE_AUTO_APPROVED_TINY_LIVE_WITH_RISK_OVERRIDE.equals(mode);
    }

    private boolean isEventRiskOverrideApprovalMode(String mode) {
        return AutoApprovalPolicyService.MODE_AUTO_APPROVED_TINY_LIVE_WITH_EVENT_RISK_OVERRIDE.equals(mode)
                || AutoApprovalPolicyService.MODE_AUTO_APPROVED_TINY_LIVE_WITH_RISK_OVERRIDE.equals(mode);
    }

    private boolean previewReadyForExecution(TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                             ApprovalContext approval) {
        return previewReadyForExecution(preview, approval.mode());
    }

    private boolean previewReadyForExecution(TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                             AutoApprovalPolicyService.Decision approval) {
        return previewReadyForExecution(preview, approval.approvalMode());
    }

    private boolean previewReadyForExecution(TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                             String approvalMode) {
        if (preview.allowedAfterManualApproval() && "READY_FOR_MANUAL_APPROVAL".equals(preview.status())) {
            return true;
        }
        return isEventRiskOverrideApprovalMode(approvalMode) && isEventRiskOnlyPreviewBlock(preview);
    }

    private List<String> terminalBlockers(TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                          AutoApprovalPolicyService.Decision approval) {
        return approval.blockers().stream()
                .filter(blocker -> !isEventRiskBlocker(blocker))
                .filter(blocker -> isTerminalBlocker(preview, blocker))
                .distinct()
                .toList();
    }

    private List<String> overridableBlockers(TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                             AutoApprovalPolicyService.Decision approval) {
        List<String> denialReasons = preview.denialReasons() == null ? List.of() : preview.denialReasons();
        List<String> blockers = approval.blockers() == null ? List.of() : approval.blockers();
        return java.util.stream.Stream.concat(blockers.stream(), denialReasons.stream())
                .filter(this::isEventRiskBlocker)
                .map(blocker -> "EVENT_RISK_HIGH".equals(blocker) ? "EVENT_RISK_HIGH" : "EVENT_RISK_R3_OR_HIGHER")
                .distinct()
                .toList();
    }

    private boolean isTerminalBlocker(TinyLiveMinimumOrderPreviewService.PreviewResult preview, String blocker) {
        if (blocker == null || blocker.isBlank()) {
            return false;
        }
        if (blocker.startsWith("PREVIEW_NOT_READY:")) {
            return false;
        }
        return true;
    }

    private boolean isEventRiskBlocker(String blocker) {
        return "EVENT_RISK_R3_OR_HIGHER".equals(blocker) || "EVENT_RISK_HIGH".equals(blocker);
    }

    private String nextRequiredAction(List<String> terminalBlockers,
                                      List<String> overridableBlockers,
                                      boolean overrideValid) {
        if (terminalBlockers.contains("DUPLICATE_BAR")) {
            return "WAIT_NEXT_BAR";
        }
        if (!terminalBlockers.isEmpty()) {
            return "FIX_TERMINAL_BLOCKERS";
        }
        if (!overridableBlockers.isEmpty() && !overrideValid) {
            return "CREATE_EVENT_RISK_OVERRIDE_TOKEN";
        }
        return "NONE";
    }

    private boolean isEventRiskOnlyPreviewBlock(TinyLiveMinimumOrderPreviewService.PreviewResult preview) {
        List<String> reasons = preview.denialReasons() == null ? List.of() : preview.denialReasons();
        return "NOT_READY_EVENT_RISK".equals(preview.status())
                && !reasons.isEmpty()
                && reasons.stream().allMatch("EVENT_RISK_HIGH"::equals);
    }

    private String overrideCreateRejected(String reason, TinyLiveMinimumOrderPreviewService.PreviewResult preview) {
        return """
                === Tiny Live Event Risk Override Token ===
                boundary: LOCAL_ONLY CONTROL; no order/OCO/strategy/grid/fund/Earn behavior changed.
                orderSent=false
                tokenCreated=false
                rejectionReason=%s
                symbol=%s
                strategyId=%d
                side=%s
                previewStatus=%s
                previewHash=%s
                eventRisk=%s
                blockers=%s
                """.formatted(reason, preview.symbol(), preview.strategyId(), preview.side(),
                preview.status(), preview.previewHash(), preview.eventRiskStatus(), preview.denialReasons());
    }

    private String tokenStatus(TinyLiveEventRiskOverrideToken row, LocalDateTime now) {
        if (row == null) {
            return "UNKNOWN";
        }
        if (row.getUsedAt() != null || "USED".equals(row.getStatus())) {
            return "USED";
        }
        if (row.getExpiresAt() != null && now.isAfter(row.getExpiresAt())) {
            return "EXPIRED";
        }
        return nullToNA(row.getStatus());
    }

    private String indent(String value) {
        if (value == null || value.isBlank()) {
            return "  N/A";
        }
        return "  " + value.replace("\n", "\n  ");
    }

    private long overrideTtlSeconds() {
        String value = env.getProperty("trading.tiny-live.event-risk-override-token-ttl-seconds", "600");
        try {
            return Math.max(60, Math.min(Long.parseLong(value), 600));
        } catch (NumberFormatException e) {
            return 600;
        }
    }

    private String overrideTokenSecret() {
        String dedicated = env.getProperty("trading.tiny-live.event-risk-override-token-secret", "");
        if (dedicated != null && !dedicated.isBlank()) {
            return dedicated.trim();
        }
        String fallback = env.getProperty("trading.tiny-live.auto-approval-token-secret", "");
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        String previewSecret = env.getProperty("trading.tiny-live.preview-token-secret", "");
        return previewSecret == null ? "" : previewSecret.trim();
    }

    private String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("tiny-live event-risk override token signing failed", e);
        }
    }

    private String eventRiskLevel(String eventRiskStatus) {
        if (eventRiskStatus == null || eventRiskStatus.isBlank()) {
            return "UNKNOWN";
        }
        return eventRiskStatus.trim().split("\\s+")[0].toUpperCase(Locale.ROOT);
    }

    private BigDecimal maxLoss(BigDecimal qty, BigDecimal entry, BigDecimal sl) {
        if (qty == null || entry == null || sl == null || entry.compareTo(sl) <= 0) {
            return BigDecimal.ZERO;
        }
        return entry.subtract(sl).multiply(qty).setScale(8, RoundingMode.HALF_UP);
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSide(String side) {
        if (side == null || side.isBlank()) return "LONG";
        String upper = side.trim().toUpperCase(Locale.ROOT);
        return "BUY".equals(upper) ? "LONG" : upper;
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

    private String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String nullToNA(String value) {
        return value == null || value.isBlank() ? "N/A" : value;
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private record ApprovalContext(String mode, String tokenId, String reason, boolean valid) {
    }

    private record OverrideCheck(boolean valid, String tokenId, String reason) {
    }
}
