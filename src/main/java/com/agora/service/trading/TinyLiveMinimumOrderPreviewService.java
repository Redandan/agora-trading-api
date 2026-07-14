package com.agora.service.trading;

import com.agora.config.properties.TradingGridProperties;
import com.agora.model.BtDecisionAudit;
import com.agora.model.BtGrid;
import com.agora.model.BtGridLevel;
import com.agora.model.BtLiveSignal;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtGridRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TinyLiveMinimumOrderPreviewService {

    private static final long ALLOWLISTED_STRATEGY_ID = 574L;
    private static final String ALLOWLISTED_SYMBOL = "BTCUSDT";
    private static final String EXECUTION_MODE = "TINY_LIVE_MANUAL_APPROVAL_PREVIEW";
    private static final BigDecimal DEFAULT_MIN_SIZE_BTC = new BigDecimal("0.00001");
    private static final BigDecimal DEFAULT_LOT_SIZE_BTC = new BigDecimal("0.00000001");
    private static final BigDecimal DEFAULT_TICK_SIZE_USDT = new BigDecimal("0.10");

    private final RuntimeDecisionEvidenceService evidenceService;
    private final BtDecisionAuditRepository decisionAuditRepository;
    private final BtLiveSignalRepository liveSignalRepository;
    private final BtGridRepository gridRepository;
    private final BtGridLevelRepository gridLevelRepository;
    private final EventRiskLevelEngine eventRiskLevelEngine;
    private final OkxTradingService okxTradingService;
    private final OcoOrderStateInspector ocoOrderStateInspector;
    private final TradingGridProperties gridProperties;
    private final AutoExplorationRolloutStateService rolloutStateService;
    private final ObjectMapper objectMapper;
    private final Environment env;

    @Transactional(readOnly = true)
    public String previewTinyLiveMinimumOrder(String symbol, Long strategyId, String side) {
        return preview(symbol, strategyId, side).render();
    }

    @Transactional(readOnly = true)
    public PreviewResult preview(String symbol, Long strategyId, String side) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? ALLOWLISTED_STRATEGY_ID : strategyId;
        String normalizedSide = normalizeSide(side);
        List<String> denialReasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!ALLOWLISTED_SYMBOL.equals(sym)) {
            denialReasons.add("SYMBOL_NOT_ALLOWLISTED");
        }
        if (sid != ALLOWLISTED_STRATEGY_ID) {
            denialReasons.add("STRATEGY_NOT_ALLOWLISTED");
        }
        if (!"LONG".equals(normalizedSide)) {
            denialReasons.add("SIDE_NOT_SUPPORTED_FOR_TINY_LIVE_PREVIEW");
        }

        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(24);
        List<RuntimeDecisionEvidence> evidenceRows = evidenceService.listRecent(sym, 1440, 100);
        List<BtDecisionAudit> audits = decisionAuditRepository.findRecent(
                since, sym, PageRequest.of(0, 200));
        List<RuntimeDecisionEvidence> relevantEvidenceRows = evidenceRows.stream()
                .filter(r -> sid == safeLong(r.getStrategyId()))
                .toList();
        List<BtDecisionAudit> relevantAudits = audits.stream()
                .filter(a -> sid == safeLong(a.getStrategyId()))
                .toList();
        CandidateSnapshot candidate = latestCandidate(sid, sym, relevantEvidenceRows, relevantAudits);
        latestLiveSignalCandidate(sid, sym).ifPresent(candidate::fillMissingFromLiveSignal);
        warnings.addAll(candidate.signalConditionDiagnostics());

        BigDecimal entry = firstNonNull(candidate.entry, safePrice(sym));
        BigDecimal tp = candidate.tp;
        BigDecimal sl = candidate.sl;

        OkxTradingService.SpotInstrumentRules rules = instrumentRules(sym, warnings);
        BigDecimal minSize = firstNonNull(rules.minSize(), DEFAULT_MIN_SIZE_BTC);
        BigDecimal lotSize = firstNonNull(rules.lotSize(), DEFAULT_LOT_SIZE_BTC);
        BigDecimal tickSize = firstNonNull(rules.tickSize(), DEFAULT_TICK_SIZE_USDT);
        BigDecimal requiredMinNotional = entry == null
                ? gridProperties.minSellNotionalUsdt()
                : gridProperties.minSellNotionalUsdt().max(minSize.multiply(entry));
        BigDecimal proposedNotional = requiredMinNotional.setScale(2, RoundingMode.CEILING);
        BigDecimal proposedQty = entry == null || entry.signum() <= 0
                ? BigDecimal.ZERO
                : roundUpToLot(proposedNotional.divide(entry, 16, RoundingMode.CEILING), lotSize);

        BigDecimal availableUsdt = availableUsdt(warnings);
        if (availableUsdt == null) {
            denialReasons.add("ACCOUNT_BALANCE_UNAVAILABLE");
        } else if (availableUsdt.compareTo(proposedNotional) < 0) {
            denialReasons.add("INSUFFICIENT_AVAILABLE_USDT");
        }

        AccountExposure exposure = accountExposure(sym, sid, entry);
        if (exposure.sameStrategyOpenTinyLivePositions > 0 && !exposure.staleTinyLiveSlotReleaseEligible) {
            denialReasons.add("TINY_LIVE_POSITION_ALREADY_OPEN");
        } else if (exposure.sameStrategyOpenTinyLivePositions > 0) {
            warnings.add("staleTinyLiveSlotReleaseEligible=true");
            warnings.add("staleTinyLiveSlotReleaseReason=" + exposure.staleTinyLiveSlotReleaseReason);
        }
        long maxOrdersToday = rolloutStateService.effectiveMaxOrdersPerDay(sym, sid, normalizedSide);
        if (exposure.autoTradesToday >= maxOrdersToday) {
            denialReasons.add("MAX_TINY_LIVE_ORDERS_TODAY_REACHED");
        }
        if (exposure.openAutoPositions > 0) {
            warnings.add("existingAutoTradePositionCount=" + exposure.openAutoPositions);
        }
        if (exposure.sameStrategyOpenAutoPositions > 0) {
            warnings.add("sameStrategyOpenAutoPositionCount=" + exposure.sameStrategyOpenAutoPositions);
        }
        if (exposure.gridExposureUsdt.signum() > 0) {
            warnings.add("existingGridExposureUsdt=" + money(exposure.gridExposureUsdt));
        }

        String duplicateCandidateStatus = exposure.sameStrategyOpenShadowOrLiveCandidate > 0
                ? "OPEN_CANDIDATE_EXISTS"
                : "NO_OPEN_CANDIDATE";

        String evStatus = evStatus(relevantEvidenceRows, relevantAudits, candidate);
        boolean currentBuyCandidate = candidate.isBuyCandidate();
        String noCurrentBuyCandidateReason = currentBuyCandidate ? "N/A" : candidate.noCurrentBuyCandidateReason();
        if (!currentBuyCandidate) {
            denialReasons.add("NO_CURRENT_BUY_CANDIDATE");
        } else if (!evStatus.startsWith("PASS")) {
            denialReasons.add("EXPECTED_VALUE_GATE_NOT_PASSED");
        }

        String runtimeEvidenceStatus = runtimeEvidenceStatus(relevantEvidenceRows);
        if (!runtimeEvidenceStatus.startsWith("AVAILABLE")) {
            denialReasons.add("RUNTIME_EVIDENCE_NOT_AVAILABLE");
        }

        EventRiskLevelEngine.Snapshot risk = eventRiskLevelEngine.evaluate(sym);
        String eventRiskStatus = risk.level().name() + " score=" + risk.score();
        if (risk.level().atLeast(EventRiskLevelEngine.RiskLevel.R3)) {
            denialReasons.add("EVENT_RISK_HIGH");
        } else if (risk.level().atLeast(EventRiskLevelEngine.RiskLevel.R2)) {
            warnings.add("eventRiskElevated=" + eventRiskStatus);
        }

        String ocoPreflightStatus = ocoPreflightStatus(entry, tp, sl, exposure.openAutoPositions);
        if (!ocoPreflightStatus.startsWith("PASS")) {
            if (currentBuyCandidate) {
                denialReasons.add("OCO_PREFLIGHT_FAILED");
            } else {
                warnings.add("ocoPreflightPendingUntilBuyCandidate=" + ocoPreflightStatus);
            }
        }

        DuplicateBarDecision duplicateBar = duplicateBarDecision(
                sym, sid, normalizedSide, candidate, relevantEvidenceRows, relevantAudits,
                evStatus, eventRiskStatus, entry, tp, sl);
        if (duplicateBar.blocked()) {
            denialReasons.add("DUPLICATE_BAR_SUPPRESSED");
            if (exposure.sameStrategyOpenShadowOrLiveCandidate > 0) {
                denialReasons.add("OPEN_CANDIDATE_EXISTS");
            }
        } else if (duplicateBar.sawCoarseDuplicate()) {
            warnings.add("duplicateBarRefined=" + duplicateBar.reason());
        }

        String status = status(denialReasons);
        boolean allowed = denialReasons.isEmpty();
        PreviewResult result = new PreviewResult(
                sym,
                sid,
                normalizedSide,
                EXECUTION_MODE,
                status,
                allowed,
                denialReasons,
                warnings,
                candidate.currentSignalDecision(),
                candidate.signalSource,
                candidate.signalReason,
                candidate.auditId,
                candidate.evidenceId,
                candidate.signalTime == null ? null : candidate.signalTime.toString(),
                candidate.signalInterval,
                candidate.signalAgeMinutes(),
                noCurrentBuyCandidateReason,
                requiredMinNotional,
                proposedNotional,
                proposedQty,
                minSize,
                lotSize,
                tickSize,
                entry,
                tp,
                sl,
                duplicateBar.status(),
                duplicateCandidateStatus,
                duplicateBar.mode(),
                duplicateBar.reason(),
                duplicateBar.currentOpportunityKey(),
                duplicateBar.lastOpportunityKey(),
                duplicateBar.distinctOpportunity(),
                evStatus,
                eventRiskStatus,
                ocoPreflightStatus,
                runtimeEvidenceStatus,
                availableUsdt,
                exposure.openAutoPositions,
                exposure.sameStrategyOpenTinyLivePositions,
                exposure.sameStrategyOpenAutoPositions,
                exposure.gridExposureUsdt,
                exposure.autoTradesToday,
                maxOrdersToday,
                null,
                null,
                null,
                null);
        return result.withToken(tokenize(result));
    }

    private PreviewToken tokenize(PreviewResult result) {
        String hash = previewHash(result);
        String secret = env.getProperty("trading.tiny-live.preview-token-secret", "");
        if (secret == null || secret.isBlank()) {
            return new PreviewToken(null, null, hash, null);
        }
        long ttl = Long.parseLong(env.getProperty("trading.tiny-live.preview-token-ttl-seconds", "300"));
        Instant expiresAt = Instant.now().plusSeconds(Math.max(30, Math.min(ttl, 900)));
        String tokenId = "tlp_" + shortHash(hash + "|" + expiresAt);
        String payload = tokenId + "|" + hash + "|" + expiresAt.getEpochSecond();
        String signature = hmac(secret, payload);
        return new PreviewToken(tokenId, payload + "|" + signature, hash, expiresAt);
    }

    public boolean verifyPreviewToken(String previewToken, String expectedPreviewHash, PreviewResult currentPreview) {
        if (previewToken == null || previewToken.isBlank()
                || expectedPreviewHash == null || expectedPreviewHash.isBlank()
                || currentPreview == null) {
            return false;
        }
        String secret = env.getProperty("trading.tiny-live.preview-token-secret", "");
        if (secret == null || secret.isBlank()) {
            return false;
        }
        String[] parts = previewToken.trim().split("\\|");
        if (parts.length != 4) {
            return false;
        }
        String tokenId = parts[0];
        String hash = parts[1];
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (!tokenId.startsWith("tlp_") || Instant.now().isAfter(Instant.ofEpochSecond(expiresAt))) {
            return false;
        }
        String payload = tokenId + "|" + hash + "|" + expiresAt;
        if (!constantTimeEquals(parts[3], hmac(secret, payload))) {
            return false;
        }
        return constantTimeEquals(hash, expectedPreviewHash.trim())
                && constantTimeEquals(hash, previewHash(currentPreview));
    }

    public String previewHash(PreviewResult result) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("symbol", result.symbol());
        node.put("strategyId", result.strategyId());
        node.put("side", result.side());
        node.put("status", result.status());
        node.put("allowedAfterManualApproval", result.allowedAfterManualApproval());
        node.put("proposedNotionalUsdt", money(result.proposedNotionalUsdt()));
        node.put("proposedQty", qty(result.proposedQty()));
        node.put("entry", money(result.entry()));
        node.put("tp", money(result.tp()));
        node.put("sl", money(result.sl()));
        node.put("duplicateBarStatus", result.duplicateBarStatus());
        node.put("duplicateBarMode", result.duplicateBarMode());
        node.put("currentOpportunityKey", result.currentOpportunityKey());
        node.put("evStatus", result.evStatus());
        node.put("eventRiskStatus", result.eventRiskStatus());
        node.put("ocoPreflightStatus", result.ocoPreflightStatus());
        node.put("runtimeEvidenceStatus", result.runtimeEvidenceStatus());
        node.put("currentSameStrategyTinyLiveOpenPositions", result.currentSameStrategyTinyLiveOpenPositions());
        node.put("autoTradesToday", result.autoTradesToday());
        node.put("maxNotionalUsdt", "5.00");
        return sha256(node.toString());
    }

    private String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("preview token signing failed", e);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("sha256 failed", e);
        }
    }

    private String shortHash(String value) {
        return sha256(value).substring(0, 16);
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    public record PreviewToken(
            String previewTokenId,
            String previewToken,
            String previewHash,
            Instant previewExpiresAt
    ) {
    }

    public record PreviewResult(
            String symbol,
            long strategyId,
            String side,
            String executionMode,
            String status,
            boolean allowedAfterManualApproval,
            List<String> denialReasons,
            List<String> warnings,
            String currentSignalDecision,
            String currentSignalSource,
            String currentSignalReason,
            Long currentSignalAuditId,
            Long currentSignalEvidenceId,
            String currentSignalTime,
            String currentSignalInterval,
            Long currentSignalAgeMinutes,
            String noCurrentBuyCandidateReason,
            BigDecimal requiredMinNotional,
            BigDecimal proposedNotionalUsdt,
            BigDecimal proposedQty,
            BigDecimal okxMinSize,
            BigDecimal okxLotSize,
            BigDecimal okxTickSize,
            BigDecimal entry,
            BigDecimal tp,
            BigDecimal sl,
            String duplicateBarStatus,
            String duplicateCandidateStatus,
            String duplicateBarMode,
            String duplicateBarReason,
            String currentOpportunityKey,
            String lastOpportunityKey,
            boolean distinctOpportunity,
            String evStatus,
            String eventRiskStatus,
            String ocoPreflightStatus,
            String runtimeEvidenceStatus,
            BigDecimal availableUsdt,
            int currentAutoTradeOpenPositions,
            long currentSameStrategyTinyLiveOpenPositions,
            long currentSameStrategyAutoTradeOpenPositions,
            BigDecimal currentGridExposureUsdt,
            long autoTradesToday,
            long maxOrdersToday,
            String previewTokenId,
            String previewToken,
            String previewHash,
            Instant previewExpiresAt
    ) {
        private PreviewResult withToken(PreviewToken token) {
            return new PreviewResult(symbol, strategyId, side, executionMode, status, allowedAfterManualApproval,
                    denialReasons, warnings, currentSignalDecision, currentSignalSource, currentSignalReason,
                    currentSignalAuditId, currentSignalEvidenceId, currentSignalTime, currentSignalInterval,
                    currentSignalAgeMinutes, noCurrentBuyCandidateReason,
                    requiredMinNotional, proposedNotionalUsdt, proposedQty,
                    okxMinSize, okxLotSize, okxTickSize, entry, tp, sl, duplicateBarStatus,
                    duplicateCandidateStatus, duplicateBarMode, duplicateBarReason, currentOpportunityKey,
                    lastOpportunityKey, distinctOpportunity, evStatus, eventRiskStatus, ocoPreflightStatus,
                    runtimeEvidenceStatus, availableUsdt, currentAutoTradeOpenPositions,
                    currentSameStrategyTinyLiveOpenPositions, currentSameStrategyAutoTradeOpenPositions,
                    currentGridExposureUsdt, autoTradesToday, maxOrdersToday, token.previewTokenId(), token.previewToken(),
                    token.previewHash(), token.previewExpiresAt());
        }

        public String render() {
            return """
                === Tiny Live Minimum Order Preflight Preview ===
                boundary: READ_ONLY PREVIEW ONLY; no order/OCO/strategy/grid/fund/Earn/autonomous-execution behavior changed.
                executionMode=%s
                status=%s
                allowedAfterManualApproval=%s
                denialReasons=%s
                warnings=%s

                symbol=%s
                strategyId=%d
                side=%s
                currentSignalDecision=%s
                currentSignalSource=%s
                currentSignalReason=%s
                currentSignalAuditId=%s
                currentSignalEvidenceId=%s
                currentSignalTime=%s
                currentSignalInterval=%s
                currentSignalAgeMinutes=%s
                noCurrentBuyCandidateReason=%s

                requiredMinNotional=%s
                proposedNotionalUsdt=%s
                proposedQty=%s
                okxMinSize=%s
                okxLotSize=%s
                okxTickSize=%s

                entry=%s
                tp=%s
                sl=%s

                duplicateBarStatus=%s
                duplicateCandidateStatus=%s
                duplicateBarMode=%s
                duplicateBarReason=%s
                currentOpportunityKey=%s
                lastOpportunityKey=%s
                isDistinctOpportunity=%s
                evStatus=%s
                eventRiskStatus=%s
                ocoPreflightStatus=%s
                runtimeEvidenceStatus=%s
                previewTokenId=%s
                previewHash=%s
                previewExpiresAt=%s
                previewToken=%s

                availableUsdt=%s
                currentAutoTradeOpenPositions=%d
                currentSameStrategyTinyLiveOpenPositions=%d
                currentSameStrategyAutoTradeOpenPositions=%d
                currentGridExposureUsdt=%s
                autoTradesTodayScope=TINY_LIVE_STRATEGY_SYMBOL
                tinyLiveAutoTradesToday=%d
                autoTradesToday=%d

                recommendedExecutionMode=%s
                recommendedMaxOrdersToday=%d
                recommendedMaxOpenTinyLivePositions=1
                orderSent=false
                """.formatted(
                    executionMode,
                    status,
                    allowedAfterManualApproval,
                    denialReasons,
                    warnings,
                    symbol,
                    strategyId,
                    side,
                    currentSignalDecision == null ? "UNKNOWN" : currentSignalDecision,
                    currentSignalSource == null ? "UNKNOWN" : currentSignalSource,
                    currentSignalReason == null ? "UNKNOWN" : currentSignalReason,
                    currentSignalAuditId == null ? "N/A" : currentSignalAuditId,
                    currentSignalEvidenceId == null ? "N/A" : currentSignalEvidenceId,
                    currentSignalTime == null ? "UNKNOWN" : currentSignalTime,
                    currentSignalInterval == null ? "UNKNOWN" : currentSignalInterval,
                    currentSignalAgeMinutes == null ? "UNKNOWN" : currentSignalAgeMinutes,
                    noCurrentBuyCandidateReason == null ? "N/A" : noCurrentBuyCandidateReason,
                    money(requiredMinNotional),
                    money(proposedNotionalUsdt),
                    qty(proposedQty),
                    qty(okxMinSize),
                    qty(okxLotSize),
                    money(okxTickSize),
                    money(entry),
                    money(tp),
                    money(sl),
                    duplicateBarStatus,
                    duplicateCandidateStatus,
                    duplicateBarMode,
                    duplicateBarReason,
                    currentOpportunityKey,
                    lastOpportunityKey == null ? "N/A" : lastOpportunityKey,
                    distinctOpportunity,
                    evStatus,
                    eventRiskStatus,
                    ocoPreflightStatus,
                    runtimeEvidenceStatus,
                    previewTokenId == null ? "UNAVAILABLE_CONFIG_MISSING" : previewTokenId,
                    previewHash,
                    previewExpiresAt == null ? "UNAVAILABLE_CONFIG_MISSING" : previewExpiresAt,
                    previewToken == null ? "UNAVAILABLE_CONFIG_MISSING" : previewToken,
                    availableUsdt == null ? "UNKNOWN" : money(availableUsdt),
                    currentAutoTradeOpenPositions,
                    currentSameStrategyTinyLiveOpenPositions,
                    currentSameStrategyAutoTradeOpenPositions,
                    money(currentGridExposureUsdt),
                    autoTradesToday,
                    autoTradesToday,
                    executionMode,
                    maxOrdersToday);
        }
    }

    private CandidateSnapshot latestCandidate(long strategyId,
                                              String symbol,
                                              List<RuntimeDecisionEvidence> rows,
                                              List<BtDecisionAudit> audits) {
        CandidateSnapshot candidate = new CandidateSnapshot();
        rows.stream()
                .filter(r -> strategyId == safeLong(r.getStrategyId()) && symbol.equalsIgnoreCase(nullToEmpty(r.getSymbol())))
                .sorted(Comparator.comparing(RuntimeDecisionEvidence::getEvidenceTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(candidate::fillMissingFromEvidence);
        audits.stream()
                .filter(a -> strategyId == safeLong(a.getStrategyId()) && symbol.equalsIgnoreCase(nullToEmpty(a.getSymbol())))
                .sorted(Comparator.comparing(BtDecisionAudit::getEventTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(candidate::fillMissingFromAudit);
        return candidate;
    }

    private Optional<BtLiveSignal> latestLiveSignalCandidate(long strategyId, String symbol) {
        return liveSignalRepository.findByStrategyIdAndCreatedAtAfter(
                        strategyId, LocalDateTime.now(ZoneOffset.UTC).minusDays(7))
                .stream()
                .filter(s -> symbol.equalsIgnoreCase(s.getSymbol()))
                .max(Comparator.comparing(BtLiveSignal::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
    }

    private OkxTradingService.SpotInstrumentRules instrumentRules(String symbol, List<String> warnings) {
        try {
            return okxTradingService.getSpotInstrumentRules(symbol);
        } catch (Exception e) {
            warnings.add("instrumentRulesFallback=" + e.getMessage());
            return new OkxTradingService.SpotInstrumentRules("BTC-USDT",
                    DEFAULT_MIN_SIZE_BTC, DEFAULT_LOT_SIZE_BTC, DEFAULT_TICK_SIZE_USDT);
        }
    }

    private BigDecimal safePrice(String symbol) {
        try {
            return okxTradingService.getLastPrice(symbol);
        } catch (Exception ignored) {
            return null;
        }
    }

    private BigDecimal availableUsdt(List<String> warnings) {
        try {
            return okxTradingService.getSpotHoldings().stream()
                    .filter(h -> "USDT".equalsIgnoreCase(h.ccy))
                    .map(h -> h.availBal)
                    .findFirst()
                    .orElse(BigDecimal.ZERO);
        } catch (Exception e) {
            warnings.add("balanceReadFailed=" + e.getMessage());
            return null;
        }
    }

    private AccountExposure accountExposure(String symbol, long strategyId, BigDecimal markPrice) {
        List<BtLiveSignal> openAuto = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull();
        long sameStrategyOpenAuto = openAuto.stream()
                .filter(s -> strategyId == safeLong(s.getStrategyId()))
                .filter(s -> symbol.equalsIgnoreCase(nullToEmpty(s.getSymbol())))
                .count();
        List<BtLiveSignal> sameStrategyOpenTinyLiveRows = openAuto.stream()
                .filter(s -> strategyId == safeLong(s.getStrategyId()))
                .filter(s -> symbol.equalsIgnoreCase(nullToEmpty(s.getSymbol())))
                .filter(this::isTinyLivePosition)
                .toList();
        long sameStrategyOpenTinyLive = sameStrategyOpenTinyLiveRows.size();
        long sameStrategyOpenCandidate = liveSignalRepository.findByStrategyIdAndCreatedAtAfter(
                        strategyId, LocalDateTime.now(ZoneOffset.UTC).minusDays(7))
                .stream()
                .filter(s -> symbol.equalsIgnoreCase(s.getSymbol()))
                .filter(s -> "LONG".equalsIgnoreCase(nullToEmpty(s.getSide())))
                .filter(s -> s.getExitTime() == null)
                .count();
        long tinyLiveAutoTradesToday = liveSignalRepository.countTinyLiveAutoTradesSince(
                strategyId, symbol, LocalDate.now(ZoneOffset.UTC).atStartOfDay());
        BigDecimal gridExposure = BigDecimal.ZERO;
        if (markPrice != null) {
            for (BtGrid grid : gridRepository.findBySymbolAndClosedAtIsNull(symbol)) {
                for (BtGridLevel level : gridLevelRepository.findByGridIdAndStatusIn(
                        grid.getId(), List.of("HOLDING", "SELL_FAILED", "SELL_PARTIAL"))) {
                    if (level.getFilledQty() != null) {
                        gridExposure = gridExposure.add(level.getFilledQty().multiply(markPrice));
                    }
                }
            }
        }
        String staleReleaseReason = staleSlotReleaseReason(sameStrategyOpenTinyLiveRows);
        return new AccountExposure(openAuto.size(), sameStrategyOpenAuto, sameStrategyOpenTinyLive,
                sameStrategyOpenCandidate,
                tinyLiveAutoTradesToday, gridExposure, "ELIGIBLE".equals(staleReleaseReason), staleReleaseReason);
    }

    private String staleSlotReleaseReason(List<BtLiveSignal> rows) {
        if (!booleanProperty("trading.exploration.loop.stale-position-slot-release.enabled", true)) {
            return "DISABLED_BY_CONFIG";
        }
        if (rows == null || rows.isEmpty()) {
            return "NO_OPEN_TINY_LIVE";
        }
        if (rows.size() != 1) {
            return "OPEN_TINY_LIVE_COUNT_NOT_ONE";
        }
        BtLiveSignal row = rows.get(0);
        if (row.getOcoOrderListId() == null) {
            return "OCO_NOT_ATTACHED";
        }
        LocalDateTime openedAt = openedAt(row);
        if (openedAt == null) {
            return "OPEN_TIME_UNAVAILABLE";
        }
        double staleHours = doubleProperty("trading.exploration.loop.open-position-stale-hours", 72.0);
        double age = Math.max(0, java.time.Duration.between(openedAt.toInstant(ZoneOffset.UTC),
                LocalDateTime.now(ZoneOffset.UTC).toInstant(ZoneOffset.UTC)).toMinutes()) / 60.0;
        if (age < staleHours) {
            return "NOT_STALE_YET";
        }
        BigDecimal notional = positionNotionalUsdt(row);
        BigDecimal maxNotional = decimalProperty("trading.exploration.loop.stale-position-slot-release.max-open-notional-usdt",
                new BigDecimal("5.50"));
        if (notional == null || notional.compareTo(maxNotional) > 0) {
            return "OPEN_NOTIONAL_ABOVE_STALE_RELEASE_CAP";
        }
        return "ELIGIBLE";
    }

    private LocalDateTime openedAt(BtLiveSignal row) {
        if (row.getCreatedAt() != null) {
            return row.getCreatedAt();
        }
        if (row.getBarOpenTime() != null) {
            return row.getBarOpenTime();
        }
        return row.getNotifiedAt();
    }

    private BigDecimal positionNotionalUsdt(BtLiveSignal row) {
        BigDecimal price = firstNonNull(row.getActualEntryPrice(), row.getEntryPrice());
        BigDecimal qty = firstNonNull(row.getOcoQty(), row.getTradedQty());
        if (price == null || qty == null) {
            return null;
        }
        return price.multiply(qty);
    }

    private boolean isTinyLivePosition(BtLiveSignal signal) {
        return contains(signal.getFilterReason(), "TINY_LIVE")
                || contains(signal.getExchangeOrderId(), "TINY_LIVE");
    }

    private DuplicateBarDecision duplicateBarDecision(String symbol,
                                                      long strategyId,
                                                      String side,
                                                      CandidateSnapshot candidate,
                                                      List<RuntimeDecisionEvidence> rows,
                                                      List<BtDecisionAudit> audits,
                                                      String evStatus,
                                                      String eventRiskStatus,
                                                      BigDecimal entry,
                                                      BigDecimal tp,
                                                      BigDecimal sl) {
        String currentCoreHash = previewCoreHash(symbol, strategyId, side, evStatus, eventRiskStatus, entry, tp, sl);
        String currentKey = opportunityKey(symbol, strategyId, side, candidate, currentCoreHash,
                evBucket(evStatus), tqsBand(candidate), eventRiskLevel(eventRiskStatus), entry, tp, sl);
        OpportunityFingerprint last = latestDuplicateOpportunity(rows, audits);
        if (last == null) {
            return new DuplicateBarDecision("NO_DUPLICATE_BAR", "DISTINCT_OPPORTUNITY",
                    "NO_RECENT_DUPLICATE_BAR", currentKey, null, true, false, false);
        }
        if (currentKey.equals(last.opportunityKey())) {
            return new DuplicateBarDecision("DUPLICATE_BAR_ACTIVE", "DISTINCT_OPPORTUNITY",
                    "EXACT_SAME_OPPORTUNITY_KEY", currentKey, last.opportunityKey(), false, true, true);
        }
        if (currentCoreHash.equals(last.previewCoreHash())) {
            return new DuplicateBarDecision("DUPLICATE_BAR_ACTIVE", "DISTINCT_OPPORTUNITY",
                    "SAME_PREVIEW_HASH", currentKey, last.opportunityKey(), false, true, true);
        }
        return new DuplicateBarDecision("NO_DUPLICATE_BAR", "DISTINCT_OPPORTUNITY",
                "COARSE_DUPLICATE_DISTINCT_OPPORTUNITY_CONTINUED", currentKey, last.opportunityKey(), true, true, false);
    }

    private OpportunityFingerprint latestDuplicateOpportunity(List<RuntimeDecisionEvidence> rows,
                                                              List<BtDecisionAudit> audits) {
        OpportunityFingerprint row = rows.stream()
                .filter(this::isDuplicateBar)
                .max(Comparator.comparing(RuntimeDecisionEvidence::getEvidenceTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::opportunityFromEvidence)
                .orElse(null);
        OpportunityFingerprint audit = audits.stream()
                .filter(this::isDuplicateBar)
                .max(Comparator.comparing(BtDecisionAudit::getEventTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::opportunityFromAudit)
                .orElse(null);
        if (row == null) return audit;
        if (audit == null) return row;
        LocalDateTime rowTime = row.eventTime();
        LocalDateTime auditTime = audit.eventTime();
        if (rowTime == null) return audit;
        if (auditTime == null) return row;
        return rowTime.isAfter(auditTime) ? row : audit;
    }

    private OpportunityFingerprint opportunityFromEvidence(RuntimeDecisionEvidence row) {
        CandidateSnapshot candidate = new CandidateSnapshot();
        candidate.fillMissingFromEvidence(row);
        String evStatus = contains(row.getEvResultJson(), "\"ev_reason\":\"pass\"")
                || contains(row.getReason(), "ExpectedValueGatePass")
                ? "PASS_EXPECTED_VALUE_GATE"
                : "UNKNOWN_EV";
        String eventRisk = firstTextFromJson(row.getRiskGateResultJson(), "eventRisk", "eventRiskStatus");
        if (eventRisk == null) {
            eventRisk = firstTextFromJson(row.getFeaturesSnapshotJson(), "eventRisk", "eventRiskStatus");
        }
        BigDecimal entry = firstNonNull(candidate.entry, firstDecimalFromJson(row.getExecutionPreviewJson(), "entryPrice", "entry"));
        BigDecimal tp = firstNonNull(candidate.tp, firstDecimalFromJson(row.getExecutionPreviewJson(), "tpPrice", "tp"));
        BigDecimal sl = firstNonNull(candidate.sl, firstDecimalFromJson(row.getExecutionPreviewJson(), "slPrice", "sl"));
        String coreHash = previewCoreHash(row.getSymbol(), safeLong(row.getStrategyId()), defaultSide(row.getSide()),
                evStatus, eventRisk, entry, tp, sl);
        return new OpportunityFingerprint(opportunityKey(row.getSymbol(), safeLong(row.getStrategyId()),
                defaultSide(row.getSide()), candidate, coreHash, evBucket(evStatus), tqsBand(candidate),
                eventRiskLevel(eventRisk), entry, tp, sl), coreHash, row.getEvidenceTime());
    }

    private OpportunityFingerprint opportunityFromAudit(BtDecisionAudit audit) {
        CandidateSnapshot candidate = new CandidateSnapshot();
        candidate.fillMissingFromAudit(audit);
        String evStatus = contains(audit.getReason(), "ExpectedValueGatePass")
                || contains(audit.getContextJson(), "\"ev_reason\":\"pass\"")
                ? "PASS_EXPECTED_VALUE_GATE"
                : "UNKNOWN_EV";
        String eventRisk = firstTextFromJson(audit.getContextJson(), "eventRisk", "eventRiskStatus");
        String side = firstNonNull(firstTextFromJson(audit.getContextJson(), "side", "signalSide"), "LONG");
        String coreHash = previewCoreHash(audit.getSymbol(), safeLong(audit.getStrategyId()), side,
                evStatus, eventRisk, candidate.entry, candidate.tp, candidate.sl);
        return new OpportunityFingerprint(opportunityKey(audit.getSymbol(), safeLong(audit.getStrategyId()),
                side, candidate, coreHash, evBucket(evStatus), tqsBand(candidate),
                eventRiskLevel(eventRisk), candidate.entry, candidate.tp, candidate.sl), coreHash, audit.getEventTime());
    }

    private String previewCoreHash(String symbol,
                                   long strategyId,
                                   String side,
                                   String evStatus,
                                   String eventRiskStatus,
                                   BigDecimal entry,
                                   BigDecimal tp,
                                   BigDecimal sl) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("symbol", normalizeSymbol(symbol));
        node.put("strategyId", strategyId);
        node.put("side", normalizeSide(side));
        node.put("evStatus", nullToEmpty(evStatus));
        node.put("eventRiskBucket", eventRiskLevel(eventRiskStatus));
        node.put("entry", money(entry));
        node.put("tp", money(tp));
        node.put("sl", money(sl));
        node.put("maxNotionalUsdt", "5.00");
        return sha256(node.toString());
    }

    private String opportunityKey(String symbol,
                                  long strategyId,
                                  String side,
                                  CandidateSnapshot candidate,
                                  String previewCoreHash,
                                  String evBucket,
                                  String tqsBand,
                                  String eventRiskBucket,
                                  BigDecimal entry,
                                  BigDecimal tp,
                                  BigDecimal sl) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("symbol", normalizeSymbol(symbol));
        node.put("strategyId", strategyId);
        node.put("side", normalizeSide(side));
        node.put("barTimestamp", nullToEmpty(candidate.barTimestamp));
        node.put("signalAuditId", candidate.auditId == null ? "UNKNOWN" : String.valueOf(candidate.auditId));
        node.put("runtimeDecisionId", candidate.runtimeDecisionId == null ? "UNKNOWN" : String.valueOf(candidate.runtimeDecisionId));
        node.put("previewHash", previewCoreHash);
        node.put("evBucket", evBucket);
        node.put("tqsBand", tqsBand);
        node.put("entryTpSlTupleHash", entryTupleHash(entry, tp, sl));
        node.put("eventRiskBucket", eventRiskBucket);
        node.put("evidenceDecisionId", candidate.evidenceDecisionId == null ? "UNKNOWN" : String.valueOf(candidate.evidenceDecisionId));
        return sha256(node.toString());
    }

    private String entryTupleHash(BigDecimal entry, BigDecimal tp, BigDecimal sl) {
        return sha256(money(entry) + "|" + money(tp) + "|" + money(sl));
    }

    private boolean isDuplicateBar(RuntimeDecisionEvidence row) {
        return contains(row.getTerminalBlocker(), "DuplicateBar")
                || contains(row.getBlockerReason(), "DuplicateBar")
                || contains(row.getReason(), "DuplicateBar");
    }

    private boolean isDuplicateBar(BtDecisionAudit audit) {
        return contains(audit.getBlocker(), "DuplicateBar")
                || contains(audit.getReason(), "DuplicateBar");
    }

    private String evStatus(List<RuntimeDecisionEvidence> rows, List<BtDecisionAudit> audits, CandidateSnapshot candidate) {
        boolean pass = rows.stream().anyMatch(r -> contains(r.getEvResultJson(), "\"ev_reason\":\"pass\"")
                        || contains(r.getReason(), "ExpectedValueGatePass"))
                || audits.stream().anyMatch(a -> contains(a.getReason(), "ExpectedValueGatePass")
                        || contains(a.getContextJson(), "\"ev_reason\":\"pass\""))
                || "pass".equalsIgnoreCase(candidate.evReason);
        if (pass) {
            return "PASS_EXPECTED_VALUE_GATE";
        }
        boolean failed = rows.stream().anyMatch(r -> contains(r.getEvResultJson(), "expectedR<minExpectedR")
                        || contains(r.getEvResultJson(), "expectedR<=0")
                        || contains(r.getReason(), "ExpectedValueGate"))
                || audits.stream().anyMatch(a -> "ExpectedValueGate".equalsIgnoreCase(a.getBlocker())
                        || contains(a.getReason(), "ExpectedValueGate")
                        || contains(a.getContextJson(), "expectedR<minExpectedR")
                        || contains(a.getContextJson(), "expectedR<=0"))
                || "expectedR<minExpectedR".equalsIgnoreCase(candidate.evReason)
                || "expectedR<=0".equalsIgnoreCase(candidate.evReason);
        return failed ? "FAIL_EXPECTED_VALUE_GATE" : "NOT_READY_EV_SAMPLE";
    }

    private String runtimeEvidenceStatus(List<RuntimeDecisionEvidence> rows) {
        if (!evidenceService.isEnabled()) {
            return "NOT_READY_ENABLED_FALSE";
        }
        if (rows.isEmpty()) {
            return "NOT_READY_NO_CANONICAL_ROWS";
        }
        boolean shadowEvidence = rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.getIntentCreated())
                || "SHADOW_MODE".equalsIgnoreCase(r.getSuppressionReason())
                || contains(r.getWarningsJson(), "fearGreedWarning"));
        return shadowEvidence ? "AVAILABLE_CANONICAL_SHADOW_EVIDENCE" : "AVAILABLE_CANONICAL_ROWS";
    }

    private String ocoPreflightStatus(BigDecimal entry, BigDecimal tp, BigDecimal sl, long openAutoPositions) {
        if (entry == null || tp == null || sl == null) {
            return "NOT_READY_MISSING_ENTRY_TP_SL";
        }
        if (sl.compareTo(entry) >= 0 || tp.compareTo(entry) <= 0) {
            return "NOT_READY_INVALID_SL_TP";
        }
        if (openAutoPositions > 0) {
            for (BtLiveSignal pos : liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()) {
                if (pos.getOcoOrderListId() == null
                        && !BtcBasePositionStatePolicy.isIntentionalNoOco(pos)) {
                    return "NOT_READY_EXISTING_OCO_MISSING";
                }
                if (BtcBasePositionStatePolicy.isIntentionalNoOco(pos)) continue;
                boolean isShort = "SHORT".equalsIgnoreCase(pos.getSide());
                OcoOrderStateInspector.Inspection inspection = isShort
                        ? ocoOrderStateInspector.inspectSwap(pos.getSymbol(), pos.getOcoOrderListId())
                        : ocoOrderStateInspector.inspectSpot(pos.getSymbol(), pos.getOcoOrderListId());
                if (inspection.filled()) {
                    return "NOT_READY_EXISTING_OCO_SYNC_ERROR";
                }
                if (!inspection.queryComplete()) {
                    return "NOT_READY_EXISTING_OCO_READ_FAILED";
                }
                if (!inspection.active()) {
                    return "NOT_READY_EXISTING_OCO_SYNC_ERROR";
                }
            }
        }
        return "PASS_OCO_PREVIEW";
    }

    private String status(List<String> denialReasons) {
        if (denialReasons.contains("DUPLICATE_BAR_SUPPRESSED")) {
            return "NOT_READY_DUPLICATE_BAR";
        }
        if (denialReasons.contains("NO_CURRENT_BUY_CANDIDATE")) {
            return "NOT_READY_NO_CURRENT_BUY_CANDIDATE";
        }
        if (denialReasons.contains("EVENT_RISK_HIGH")) {
            return "NOT_READY_EVENT_RISK";
        }
        if (denialReasons.contains("OCO_PREFLIGHT_FAILED")) {
            return "NOT_READY_OCO";
        }
        if (denialReasons.contains("TINY_LIVE_POSITION_ALREADY_OPEN")) {
            return "NOT_READY_OPEN_TINY_LIVE_POSITION";
        }
        if (denialReasons.contains("MAX_TINY_LIVE_ORDERS_TODAY_REACHED")) {
            return "NOT_READY_DAILY_CAP";
        }
        if (denialReasons.stream().anyMatch(r -> r.contains("RUNTIME_EVIDENCE")
                || r.contains("ACCOUNT_BALANCE"))) {
            return "NOT_READY_RUNTIME_EVIDENCE";
        }
        if (denialReasons.stream().anyMatch(r -> r.contains("EXPECTED_VALUE"))) {
            return "NOT_READY_EXPECTED_VALUE";
        }
        return denialReasons.isEmpty() ? "READY_FOR_MANUAL_APPROVAL" : "NOT_READY_SAFETY_GATES";
    }

    private String evBucket(String evStatus) {
        if (evStatus == null || evStatus.isBlank()) {
            return "UNKNOWN_EV";
        }
        return evStatus.startsWith("PASS") ? "EV_PASS" : "EV_NOT_PASS";
    }

    private String tqsBand(CandidateSnapshot candidate) {
        return candidate == null || candidate.tqsBand == null || candidate.tqsBand.isBlank()
                ? "UNKNOWN_TQS"
                : candidate.tqsBand;
    }

    private String eventRiskLevel(String eventRiskStatus) {
        if (eventRiskStatus == null || eventRiskStatus.isBlank()) {
            return "UNKNOWN_EVENT_RISK";
        }
        return eventRiskStatus.trim().split("\\s+")[0].toUpperCase(Locale.ROOT);
    }

    private String defaultSide(String side) {
        return side == null || side.isBlank() ? "LONG" : normalizeSide(side);
    }

    private BigDecimal firstDecimalFromJson(String json, String... keys) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            for (String key : keys) {
                BigDecimal value = firstDecimal(node, key);
                if (value != null) return value;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String firstTextFromJson(String json, String... keys) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            for (String key : keys) {
                JsonNode value = node.get(key);
                if (value != null && !value.isNull() && !value.asText("").isBlank()) {
                    return value.asText();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private BigDecimal firstDecimal(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return value.decimalValue();
        if (value.isTextual() && !value.asText().isBlank()) {
            try {
                return new BigDecimal(value.asText());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? ALLOWLISTED_SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSide(String side) {
        if (side == null || side.isBlank()) {
            return "LONG";
        }
        String upper = side.trim().toUpperCase(Locale.ROOT);
        return "BUY".equals(upper) ? "LONG" : upper;
    }

    private BigDecimal roundUpToLot(BigDecimal value, BigDecimal lotSize) {
        if (lotSize == null || lotSize.signum() <= 0) {
            return value.setScale(8, RoundingMode.CEILING);
        }
        return value.divide(lotSize, 0, RoundingMode.CEILING).multiply(lotSize)
                .setScale(Math.max(0, lotSize.scale()), RoundingMode.UNNECESSARY);
    }

    private static String money(BigDecimal value) {
        return value == null ? "UNKNOWN" : value.stripTrailingZeros().toPlainString();
    }

    private static String qty(BigDecimal value) {
        return value == null ? "UNKNOWN" : value.stripTrailingZeros().toPlainString();
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String signalDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (upper.contains("HOLD")) {
            return "HOLD";
        }
        if (upper.contains("BUY") || upper.contains("LONG")
                || upper.contains("ENTRY") || upper.contains("SHADOW_EXECUTION_INTENT")
                || upper.contains("EXPECTEDVALUEGATEPASS")) {
            return "BUY";
        }
        if (upper.contains("SELL") || upper.contains("SHORT")) {
            return "SELL";
        }
        return null;
    }

    private boolean booleanProperty(String key, boolean fallback) {
        return Boolean.parseBoolean(env.getProperty(key, String.valueOf(fallback)));
    }

    private double doubleProperty(String key, double fallback) {
        try {
            return Double.parseDouble(env.getProperty(key, String.valueOf(fallback)));
        } catch (Exception e) {
            return fallback;
        }
    }

    private BigDecimal decimalProperty(String key, BigDecimal fallback) {
        try {
            return new BigDecimal(env.getProperty(key, fallback.toPlainString()));
        } catch (Exception e) {
            return fallback;
        }
    }

    private long safeLong(Long value) {
        return value == null ? Long.MIN_VALUE : value;
    }

    private String barTimestamp(LocalDateTime time, String intervalCode) {
        if (time == null) {
            return "UNKNOWN_BAR";
        }
        String interval = intervalCode == null ? "" : intervalCode.toLowerCase(Locale.ROOT);
        LocalDateTime bucket = time;
        if ("1h".equals(interval)) {
            bucket = time.withMinute(0).withSecond(0).withNano(0);
        } else if ("4h".equals(interval)) {
            int hour = (time.getHour() / 4) * 4;
            bucket = time.withHour(hour).withMinute(0).withSecond(0).withNano(0);
        } else if ("1d".equals(interval)) {
            bucket = time.toLocalDate().atStartOfDay();
        } else {
            bucket = time.withSecond(0).withNano(0);
        }
        return bucket.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean contains(String value, String needle) {
        return value != null && needle != null
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private final class CandidateSnapshot {
        private BigDecimal entry;
        private BigDecimal tp;
        private BigDecimal sl;
        private String evReason;
        private String tqsBand;
        private String signalDecision;
        private String signalSource;
        private String signalReason;
        private String signalInterval;
        private LocalDateTime signalTime;
        private Long auditId;
        private Long runtimeDecisionId;
        private Long evidenceId;
        private Long evidenceDecisionId;
        private Long liveSignalId;
        private String barTimestamp;
        private String mihIndicator;
        private String mihAggregation;
        private BigDecimal mihValue;
        private BigDecimal buyThreshold;
        private BigDecimal buyBelow;
        private BigDecimal sellAbove;
        private BigDecimal sellBelow;
        private BigDecimal requireAbove;
        private BigDecimal sma720;
        private BigDecimal gain24hPct;
        private BigDecimal fundingRate;
        private BigDecimal fundingAvgPrev;
        private String holdReason;
        private String triggerReason;

        private void fillMissingFromJson(String json) {
            if (json == null || json.isBlank()) {
                return;
            }
            try {
                JsonNode node = objectMapper.readTree(json);
                if (entry == null) entry = firstDecimal(node, "candidateEntry", "entry");
                if (tp == null) tp = firstDecimal(node, "candidateTp", "tp");
                if (sl == null) sl = firstDecimal(node, "candidateSl", "sl");
                if (evReason == null) evReason = firstText(node, "ev_reason");
                if (tqsBand == null) tqsBand = firstText(node, "tqsBand", "tqs_band", "qualityBand");
                if (signalDecision == null) signalDecision = signalDecision(firstText(node,
                        "signalDecision", "selectedAction", "selected_action", "action", "decision", "side", "signalSide"));
                if (barTimestamp == null) barTimestamp = firstText(node, "barOpenTime", "bar_open_time", "barTimestamp");
                fillStrategyDecisionDetails(strategyDecisionNode(node));
            } catch (Exception ignored) {
            }
        }

        private void fillMissingFromEvidence(RuntimeDecisionEvidence row) {
            if (row == null) return;
            if (evidenceId == null) evidenceId = row.getId();
            if (evidenceDecisionId == null) evidenceDecisionId = row.getDecisionId();
            if (runtimeDecisionId == null) runtimeDecisionId = row.getDecisionId();
            if (liveSignalId == null) liveSignalId = row.getLiveSignalId();
            if (barTimestamp == null) barTimestamp = barTimestamp(row.getEvidenceTime(), row.getIntervalCode());
            if (shouldReplaceSignalMetadata(row.getEvidenceTime())) {
                signalDecision = signalDecision(firstNonNull(row.getReason(), row.getBlockerReason(),
                        row.getSelectedAction(), row.getDecision(), row.getSide()));
                signalSource = "RUNTIME_DECISION_EVIDENCE";
                signalReason = firstNonNull(row.getReason(), row.getBlockerReason(), row.getSelectedAction());
                signalInterval = row.getIntervalCode();
                signalTime = row.getEvidenceTime();
            }
            fillMissingFromJson(row.getFeaturesSnapshotJson());
            fillMissingFromJson(row.getTqsResultJson());
            fillMissingFromJson(row.getExecutionPreviewJson());
            if (tqsBand == null) tqsBand = firstTextFromJson(row.getTqsResultJson(), "tqsBand", "band");
        }

        private void fillMissingFromAudit(BtDecisionAudit audit) {
            if (audit == null) return;
            if (auditId == null) auditId = audit.getId();
            if (liveSignalId == null) liveSignalId = audit.getLiveSignalId();
            if (barTimestamp == null) {
                barTimestamp = audit.getBarOpenTime() == null
                        ? barTimestamp(audit.getEventTime(), audit.getIntervalCode())
                        : audit.getBarOpenTime().toString();
            }
            if (shouldReplaceSignalMetadata(audit.getEventTime())) {
                signalDecision = signalDecision(firstNonNull(audit.getReason(), audit.getBlocker()));
                signalSource = "BT_DECISION_AUDIT";
                signalReason = firstNonNull(audit.getReason(), audit.getBlocker(), audit.getEventType());
                signalInterval = audit.getIntervalCode();
                signalTime = audit.getEventTime();
            }
            fillMissingFromJson(audit.getContextJson());
        }

        private void fillMissingFromLiveSignal(BtLiveSignal signal) {
            if (entry == null) entry = signal.getEntryPrice();
            if (tp == null) tp = signal.getSuggestedTp();
            if (sl == null) sl = signal.getSuggestedSl();
            if (liveSignalId == null) liveSignalId = signal.getId();
            if (signalDecision == null) {
                signalDecision = signalDecision(signal.getSide());
                signalSource = "BT_LIVE_SIGNAL";
                signalReason = firstNonNull(signal.getFilterReason(), signal.getSide());
                signalInterval = signal.getIntervalCode();
                signalTime = signal.getCreatedAt();
            }
            if (barTimestamp == null) barTimestamp = barTimestamp(signal.getCreatedAt(), signal.getIntervalCode());
        }

        private boolean shouldReplaceSignalMetadata(LocalDateTime incomingTime) {
            if (signalTime == null) {
                return true;
            }
            return incomingTime != null && incomingTime.isAfter(signalTime);
        }

        private boolean isBuyCandidate() {
            if ("BUY".equals(signalDecision) || "LONG".equals(signalDecision)) {
                return true;
            }
            return "pass".equalsIgnoreCase(evReason)
                    || signalDecision == null && (entry != null || tp != null || sl != null);
        }

        private String currentSignalDecision() {
            return signalDecision == null ? "UNKNOWN" : signalDecision;
        }

        private Long signalAgeMinutes() {
            if (signalTime == null) {
                return null;
            }
            return Math.max(0, java.time.Duration.between(
                    signalTime.toInstant(ZoneOffset.UTC),
                    LocalDateTime.now(ZoneOffset.UTC).toInstant(ZoneOffset.UTC)).toMinutes());
        }

        private String noCurrentBuyCandidateReason() {
            if ("HOLD".equals(signalDecision)) {
                return "LATEST_SIGNAL_HOLD";
            }
            if ("SELL".equals(signalDecision)) {
                return "LATEST_SIGNAL_SELL_OR_SHORT";
            }
            if (signalDecision == null || "UNKNOWN".equals(signalDecision)) {
                return "LATEST_SIGNAL_DECISION_UNKNOWN";
            }
            return "LATEST_SIGNAL_NOT_BUY";
        }

        private List<String> signalConditionDiagnostics() {
            List<String> diagnostics = new ArrayList<>();
            String summary = signalConditionSummary();
            if (summary != null) {
                diagnostics.add("signalConditionSummary=" + summary);
            }
            diagnostics.addAll(signalProximityDiagnostics());
            String failed = failedSignalCondition();
            if (failed != null) {
                diagnostics.add("failedSignalCondition=" + failed);
            }
            if (triggerReason != null && !triggerReason.isBlank()) {
                diagnostics.add("signalTriggerReason=" + triggerReason);
            }
            return diagnostics;
        }

        private List<String> signalProximityDiagnostics() {
            if (!"HOLD".equals(currentSignalDecision())) {
                return List.of();
            }
            SignalProximity proximity = signalProximity();
            if (proximity == null) {
                return List.of();
            }
            List<String> diagnostics = new ArrayList<>();
            diagnostics.add("signalProximityState=" + proximity.state());
            diagnostics.add("signalThresholdGap=" + money(proximity.gap()));
            diagnostics.add("signalThresholdGapPct=" + money(proximity.gapPct()));
            diagnostics.add("nextRequiredAction=" + proximity.nextRequiredAction());
            return diagnostics;
        }

        private SignalProximity signalProximity() {
            BigDecimal maxGapPct = decimalProperty("trading.tiny-live.signal-near-threshold-gap-pct",
                    new BigDecimal("0.10"));
            if (mihValue != null && buyThreshold != null && buyThreshold.signum() > 0
                    && mihValue.compareTo(buyThreshold) < 0) {
                BigDecimal gap = buyThreshold.subtract(mihValue);
                BigDecimal pct = gap.divide(buyThreshold.abs(), 8, RoundingMode.HALF_UP);
                String state = pct.compareTo(maxGapPct) <= 0
                        ? "NEAR_BUY_THRESHOLD"
                        : "FAR_FROM_BUY_THRESHOLD";
                return new SignalProximity(state, gap, pct, "WAIT_BUY_THRESHOLD_CROSS");
            }
            if (mihValue != null && buyBelow != null && buyBelow.signum() >= 0
                    && mihValue.compareTo(buyBelow) > 0) {
                BigDecimal gap = mihValue.subtract(buyBelow);
                BigDecimal denom = buyBelow.abs().max(BigDecimal.ONE);
                BigDecimal pct = gap.divide(denom, 8, RoundingMode.HALF_UP);
                String state = pct.compareTo(maxGapPct) <= 0
                        ? "NEAR_BUY_BELOW_THRESHOLD"
                        : "FAR_FROM_BUY_BELOW_THRESHOLD";
                return new SignalProximity(state, gap, pct, "WAIT_BUY_BELOW_THRESHOLD_CROSS");
            }
            return null;
        }

        private String signalConditionSummary() {
            if (mihIndicator == null && mihValue == null && holdReason == null && triggerReason == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder("CMI_MIH");
            append(sb, "indicator", mihIndicator);
            append(sb, "value", money(mihValue));
            append(sb, "aggregation", mihAggregation);
            append(sb, "buyThreshold", money(buyThreshold));
            append(sb, "buyBelow", money(buyBelow));
            append(sb, "sellAbove", money(sellAbove));
            append(sb, "sellBelow", money(sellBelow));
            append(sb, "requireAbove", money(requireAbove));
            append(sb, "holdReason", holdReason);
            append(sb, "triggerReason", triggerReason);
            append(sb, "sma720", money(sma720));
            append(sb, "gain24hPct", money(gain24hPct));
            append(sb, "fundingRate", money(fundingRate));
            append(sb, "fundingAvgPrev", money(fundingAvgPrev));
            return sb.toString();
        }

        private String failedSignalCondition() {
            if (!"HOLD".equals(currentSignalDecision())) {
                return null;
            }
            if ("indicator_missing".equalsIgnoreCase(holdReason)) {
                return "indicator_missing(" + nullToEmpty(mihIndicator) + ")";
            }
            if ("below_require_above".equalsIgnoreCase(holdReason)) {
                return compare("mih_value_below_require_above", mihValue, "<", requireAbove);
            }
            if ("below_sma720".equalsIgnoreCase(holdReason)) {
                return compare("close_below_sma720", null, "<", sma720);
            }
            if ("funding_not_improving".equalsIgnoreCase(holdReason)) {
                return compare("funding_not_improving", fundingRate, "<=", fundingAvgPrev);
            }
            if ("creating_new_low".equalsIgnoreCase(holdReason)) {
                return "creating_new_low";
            }
            if ("gain_24h_excessive".equalsIgnoreCase(holdReason)) {
                return "gain_24h_excessive(gain24hPct=" + money(gain24hPct) + ")";
            }
            if ("no_threshold_hit".equalsIgnoreCase(holdReason)) {
                List<String> parts = new ArrayList<>();
                if (mihValue != null && buyThreshold != null && buyThreshold.signum() >= 0
                        && mihValue.compareTo(buyThreshold) < 0) {
                    parts.add(compare("mih_value_below_buy_threshold", mihValue, "<", buyThreshold));
                }
                if (mihValue != null && buyBelow != null && buyBelow.signum() >= 0
                        && mihValue.compareTo(buyBelow) > 0) {
                    parts.add(compare("mih_value_above_buy_below", mihValue, ">", buyBelow));
                }
                if (!parts.isEmpty()) {
                    return String.join(";", parts);
                }
            }
            if (holdReason != null && !holdReason.isBlank()) {
                return "hold_reason=" + holdReason;
            }
            return null;
        }

        private String compare(String label, BigDecimal left, String op, BigDecimal right) {
            String leftText = left == null ? "UNKNOWN" : money(left);
            String rightText = right == null ? "UNKNOWN" : money(right);
            return label + "(" + leftText + op + rightText + ")";
        }

        private void append(StringBuilder sb, String key, String value) {
            if (value != null && !value.isBlank() && !"UNKNOWN".equals(value)) {
                sb.append(' ').append(key).append('=').append(value);
            }
        }

        private JsonNode strategyDecisionNode(JsonNode node) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            JsonNode direct = node.get("strategy_decision");
            if (direct != null && direct.isObject()) {
                return direct;
            }
            JsonNode extras = node.path("extras");
            JsonNode nested = extras.get("strategy_decision");
            return nested != null && nested.isObject() ? nested : null;
        }

        private void fillStrategyDecisionDetails(JsonNode details) {
            if (details == null || !details.isObject()) {
                return;
            }
            if (mihIndicator == null) mihIndicator = firstText(details, "mih_indicator");
            if (mihAggregation == null) mihAggregation = firstText(details, "mih_aggregation");
            if (mihValue == null) mihValue = firstDecimal(details, "mih_value");
            if (buyThreshold == null) buyThreshold = firstDecimal(details, "buy_threshold");
            if (buyBelow == null) buyBelow = firstDecimal(details, "buy_below");
            if (sellAbove == null) sellAbove = firstDecimal(details, "sell_above");
            if (sellBelow == null) sellBelow = firstDecimal(details, "sell_below");
            if (requireAbove == null) requireAbove = firstDecimal(details, "require_above");
            if (sma720 == null) sma720 = firstDecimal(details, "sma720");
            if (gain24hPct == null) gain24hPct = firstDecimal(details, "gain_24h_pct");
            if (fundingRate == null) fundingRate = firstDecimal(details, "funding_rate");
            if (fundingAvgPrev == null) fundingAvgPrev = firstDecimal(details, "funding_avg_prev");
            if (holdReason == null) holdReason = firstText(details, "hold_reason");
            if (triggerReason == null) triggerReason = firstText(details, "trigger_reason");
            if (signalReason != null && holdReason != null && "HOLD".equals(signalDecision)
                    && !contains(signalReason, "holdReason=")) {
                signalReason = signalReason + "; holdReason=" + holdReason;
            }
        }

        private BigDecimal firstDecimal(JsonNode node, String... keys) {
            for (String key : keys) {
                JsonNode value = node.get(key);
                if (value == null || value.isNull()) continue;
                if (value.isNumber()) return value.decimalValue();
                if (value.isTextual() && !value.asText().isBlank()) {
                    try {
                        return new BigDecimal(value.asText());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return null;
        }

        private String firstText(JsonNode node, String... keys) {
            for (String key : keys) {
                JsonNode value = node.get(key);
                if (value != null && !value.isNull() && !value.asText("").isBlank()) {
                    return value.asText();
                }
            }
            return null;
        }
    }

    private record AccountExposure(
            int openAutoPositions,
            long sameStrategyOpenAutoPositions,
            long sameStrategyOpenTinyLivePositions,
            long sameStrategyOpenShadowOrLiveCandidate,
            long autoTradesToday,
            BigDecimal gridExposureUsdt,
            boolean staleTinyLiveSlotReleaseEligible,
            String staleTinyLiveSlotReleaseReason
    ) {
    }

    private record DuplicateBarDecision(
            String status,
            String mode,
            String reason,
            String currentOpportunityKey,
            String lastOpportunityKey,
            boolean distinctOpportunity,
            boolean sawCoarseDuplicate,
            boolean blocked
    ) {
    }

    private record OpportunityFingerprint(
            String opportunityKey,
            String previewCoreHash,
            LocalDateTime eventTime
    ) {
    }

    private record SignalProximity(
            String state,
            BigDecimal gap,
            BigDecimal gapPct,
            String nextRequiredAction
    ) {
    }
}
