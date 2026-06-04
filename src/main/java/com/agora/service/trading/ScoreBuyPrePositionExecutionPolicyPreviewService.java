package com.agora.service.trading;

import com.agora.model.BtDecisionAudit;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ScoreBuyPrePositionExecutionPolicyPreviewService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final long DEFAULT_STRATEGY_ID = 485L;
    private static final String SIDE = "LONG";
    private static final String INTERVAL = "1d";

    private final ScoreBuyPrePositionApprovalPreviewService approvalPreviewService;
    private final RuntimeDecisionEvidenceService runtimeDecisionEvidenceService;
    private final BtLiveSignalRepository liveSignalRepository;
    private final BtDecisionAuditRepository decisionAuditRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public String preview(String symbol, Long strategyId) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;

        JsonNode approval = readJson(approvalPreviewService.preview(sym, sid));
        boolean coarseDedupWouldBlock = liveSignalRepository
                .existsByStrategyIdAndSymbolAndSideAndIntervalCodeAndExitTimeIsNull(sid, sym, SIDE, INTERVAL);
        List<BtDecisionAudit> recentDedupAudits = recentDedupAudits(sym, sid);
        List<RuntimeDecisionEvidence> recentEvidenceRows = runtimeDecisionEvidenceService.listRecent(sym, 1440, 100)
                .stream()
                .filter(row -> row.getStrategyId() != null && row.getStrategyId() == sid)
                .toList();
        OpportunityFingerprint currentOpportunity = currentOpportunity(sym, sid, approval);
        OpportunityFingerprint lastOpportunity = latestPriorOpportunity(recentDedupAudits, recentEvidenceRows);
        DuplicateDecision duplicateDecision = duplicateDecision(currentOpportunity, lastOpportunity);

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        copyArray(approval.path("blockers"), blockers);
        copyArray(approval.path("warnings"), warnings);

        boolean stagedAddAllowed = approval.path("stagedAddBudgetStatus")
                .path("sameThesisAddAllowed").asBoolean(false);
        boolean exactDuplicate = duplicateDecision.exactDuplicate();
        if (!stagedAddAllowed) {
            blockers.add("SAME_THESIS_STAGED_ADD_BUDGET_NOT_AVAILABLE");
        }
        if (exactDuplicate) {
            blockers.add("EXACT_DUPLICATE_OPPORTUNITY");
        }

        boolean autoEligible = approval.path("autoApprovalEligible").asBoolean(false);
        boolean executionEligible = approval.path("executionEligible").asBoolean(false);
        boolean stagedPolicyWouldAllow = executionEligible && stagedAddAllowed && !exactDuplicate;
        boolean dedupMismatch = coarseDedupWouldBlock && stagedPolicyWouldAllow;
        if (dedupMismatch) {
            warnings.add("COARSE_ENTRY_DEDUP_WOULD_BLOCK_BUT_STAGED_ADD_POLICY_WOULD_ALLOW");
        }
        if (!recentDedupAudits.isEmpty()) {
            warnings.add("RECENT_DEDUP_AUDITS_PRESENT:" + recentDedupAudits.size());
        }

        String executionPolicy;
        String reason;
        if (!blockers.isEmpty()) {
            executionPolicy = "BLOCKED";
            reason = "Hard pre-position execution gates are not ready.";
        } else if (autoEligible) {
            executionPolicy = "AUTO_APPROVED_SCORE_BUY_PRE_POSITION_PREVIEW";
            reason = "All read-only gates pass for bounded SCORE_BUY pre-position; this tool does not create token or order.";
        } else if (executionEligible) {
            executionPolicy = "READY_FOR_MANUAL_APPROVAL";
            reason = "Read-only gates pass but auto approval is not allowed under current risk state.";
        } else {
            executionPolicy = "BLOCKED";
            reason = "Approval preview is not execution eligible.";
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "previewScoreBuyPrePositionExecution");
        root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence write behavior changed.");
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("symbol", sym);
        root.put("strategyId", sid);
        root.put("side", SIDE);
        root.put("intervalCode", INTERVAL);
        root.put("executionPolicy", executionPolicy);
        root.put("executionPolicyReason", reason);
        root.put("missedOpportunityRisk", text(approval, "wouldMissOpportunityRisk", "UNKNOWN"));
        root.put("coarseEntryDedupWouldBlock", coarseDedupWouldBlock);
        root.put("stagedAddPolicyWouldAllow", stagedPolicyWouldAllow);
        root.put("entryDedupMismatch", dedupMismatch);
        root.put("duplicateMode", "DISTINCT_OPPORTUNITY");
        root.put("duplicateOpportunityReason", duplicateDecision.reason());
        root.put("currentOpportunityKey", currentOpportunity.opportunityKey());
        root.put("currentPreviewHash", currentOpportunity.previewHash());
        root.put("lastOpportunityKey", lastOpportunity == null ? null : lastOpportunity.opportunityKey());
        root.put("lastPreviewHash", lastOpportunity == null ? null : lastOpportunity.previewHash());
        root.put("lastOpportunitySource", lastOpportunity == null ? "NONE" : lastOpportunity.source());
        root.put("isDistinctOpportunity", duplicateDecision.distinctOpportunity());
        root.put("exactDuplicateOpportunity", exactDuplicate);
        root.put("autoApprovalEligible", autoEligible);
        root.put("executionEligible", executionEligible);
        root.put("wouldCreateApprovalToken", false);
        root.put("wouldExecuteIfWritePathEnabled", "AUTO_APPROVED_SCORE_BUY_PRE_POSITION_PREVIEW".equals(executionPolicy));
        root.put("wouldExecute", false);
        root.put("recommendedExecutionMode", "SCORE_BUY_PRE_POSITION_EXECUTION_POLICY_PREVIEW_ONLY");
        root.put("approvalMode", text(approval, "approvalMode", "UNKNOWN"));
        root.put("prePositionReadiness", text(approval, "prePositionReadiness", "UNKNOWN"));
        root.put("scoreBuyFormingState", text(approval, "scoreBuyFormingState", "UNKNOWN"));
        root.put("scoreBuyHoldingState", text(approval, "scoreBuyHoldingState", "UNKNOWN"));
        root.put("holdBtcMode", approval.path("holdBtcMode").asBoolean(false));
        root.put("holdBtcReason", text(approval, "holdBtcReason", "NONE"));
        root.put("autoSellAllowed", false);
        root.put("autoAddAllowed", approval.path("autoAddAllowed").asBoolean(false)
                && "AUTO_APPROVED_SCORE_BUY_PRE_POSITION_PREVIEW".equals(executionPolicy));
        root.put("disasterOcoMode", text(approval, "disasterOcoMode", "KEEP_12PCT_HARD_OCO"));
        root.put("eventRiskLevel", text(approval, "eventRiskLevel", "UNKNOWN"));
        root.put("eventRiskMultiplier", approval.path("eventRiskMultiplier").asDouble(1.0));
        root.put("runtimeEvidenceStatus", text(approval, "runtimeEvidenceStatus", "UNKNOWN"));
        root.put("ocoPreflightStatus", text(approval, "ocoPreflightStatus", "UNKNOWN"));
        root.put("proposedNotionalUsdt", text(approval, "proposedNotionalUsdt", "0"));
        root.put("entry", text(approval, "entry", "0"));
        root.put("tp", text(approval, "tp", "0"));
        root.put("sl", text(approval, "sl", "0"));
        root.put("maxLossIfWrongUsdt", text(approval, "maxLossIfWrongUsdt", "0"));
        root.set("stagedAddBudgetStatus", approval.path("stagedAddBudgetStatus").deepCopy());
        root.set("nextRearmConditions", approval.path("nextRearmConditions").isArray()
                ? approval.path("nextRearmConditions").deepCopy()
                : objectMapper.createArrayNode());
        root.set("recentDedupAudits", recentAuditArray(recentDedupAudits));
        root.set("blockers", stringArray(blockers));
        root.set("warnings", stringArray(warnings));
        root.set("requiredWritePathChecks", approval.path("requiredWritePathChecks").deepCopy());
        root.put("orderSent", false);
        root.put("ocoModified", false);
        root.put("telegramSent", false);
        root.put("writesRuntimeEvidence", false);
        return write(root);
    }

    private List<BtDecisionAudit> recentDedupAudits(String symbol, long strategyId) {
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(24);
        return decisionAuditRepository.findWindow(
                        since,
                        LocalDateTime.now(ZoneOffset.UTC),
                        symbol,
                        strategyId,
                        false,
                        List.of("ENTRY_SKIP"),
                        PageRequest.of(0, 10))
                .stream()
                .filter(row -> contains(row.getBlocker(), "EntryDedup") || contains(row.getBlocker(), "DuplicateBar")
                        || contains(row.getReason(), "EntryDedup") || contains(row.getReason(), "DuplicateBar"))
                .toList();
    }

    private OpportunityFingerprint currentOpportunity(String symbol, long strategyId, JsonNode approval) {
        String barTimestamp = LocalDateTime.now(ZoneOffset.UTC).toLocalDate().atStartOfDay().toString();
        String previewHash = previewHash(symbol, strategyId, SIDE,
                text(approval, "eventRiskLevel", "UNKNOWN"),
                text(approval, "prePositionReadiness", "UNKNOWN"),
                text(approval, "scoreBuyFormingState", "UNKNOWN"),
                text(approval, "entry", "0"),
                text(approval, "tp", "0"),
                text(approval, "sl", "0"),
                text(approval, "proposedNotionalUsdt", "0"),
                text(approval, "maxLossIfWrongUsdt", "0"));
        return new OpportunityFingerprint(opportunityKey(symbol, strategyId, SIDE, barTimestamp,
                "CURRENT_PREVIEW", null, null, previewHash,
                text(approval, "eventRiskLevel", "UNKNOWN"),
                text(approval, "runtimeEvidenceStatus", "UNKNOWN"),
                text(approval, "prePositionReadiness", "UNKNOWN"),
                text(approval, "scoreBuyFormingState", "UNKNOWN"),
                text(approval, "entry", "0"),
                text(approval, "tp", "0"),
                text(approval, "sl", "0")),
                previewHash,
                "CURRENT_PREVIEW",
                LocalDateTime.now(ZoneOffset.UTC));
    }

    private OpportunityFingerprint latestPriorOpportunity(List<BtDecisionAudit> audits,
                                                           List<RuntimeDecisionEvidence> rows) {
        OpportunityFingerprint audit = audits.stream()
                .max(Comparator.comparing(BtDecisionAudit::getEventTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::opportunityFromAudit)
                .orElse(null);
        OpportunityFingerprint evidence = rows.stream()
                .filter(row -> contains(row.getTerminalBlocker(), "DuplicateBar")
                        || contains(row.getTerminalBlocker(), "EntryDedup")
                        || contains(row.getBlockerReason(), "DuplicateBar")
                        || contains(row.getBlockerReason(), "EntryDedup")
                        || contains(row.getReason(), "DuplicateBar")
                        || contains(row.getReason(), "EntryDedup")
                        || "ENTRY_SKIP".equalsIgnoreCase(row.getSelectedAction()))
                .max(Comparator.comparing(RuntimeDecisionEvidence::getEvidenceTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::opportunityFromEvidence)
                .orElse(null);
        if (audit == null) return evidence;
        if (evidence == null) return audit;
        if (audit.eventTime() == null) return evidence;
        if (evidence.eventTime() == null) return audit;
        return audit.eventTime().isAfter(evidence.eventTime()) ? audit : evidence;
    }

    private OpportunityFingerprint opportunityFromAudit(BtDecisionAudit audit) {
        String side = firstTextFromJson(audit.getContextJson(), "side", "signalSide");
        if (side == null) side = SIDE;
        String bar = audit.getBarOpenTime() != null
                ? audit.getBarOpenTime().toString()
                : barTimestamp(audit.getEventTime(), audit.getIntervalCode());
        String entry = firstTextFromJson(audit.getContextJson(), "entry", "candidateEntry", "price");
        String tp = firstTextFromJson(audit.getContextJson(), "tp", "candidateTp");
        String sl = firstTextFromJson(audit.getContextJson(), "sl", "candidateSl");
        String previewHash = firstTextFromJson(audit.getContextJson(), "previewHash", "scoreBuyPreviewHash");
        if (previewHash == null) {
            previewHash = previewHash(audit.getSymbol(), safeLong(audit.getStrategyId()), side,
                    firstTextFromJson(audit.getContextJson(), "eventRisk", "eventRiskStatus"),
                    firstTextFromJson(audit.getContextJson(), "prePositionReadiness"),
                    firstTextFromJson(audit.getContextJson(), "scoreBuyFormingState"),
                    entry, tp, sl,
                    firstTextFromJson(audit.getContextJson(), "proposedNotionalUsdt", "notionalUsdt"),
                    firstTextFromJson(audit.getContextJson(), "maxLossIfWrongUsdt"));
        }
        return new OpportunityFingerprint(opportunityKey(audit.getSymbol(), safeLong(audit.getStrategyId()), side,
                bar, "AUDIT", audit.getId(), null, previewHash,
                firstTextFromJson(audit.getContextJson(), "eventRisk", "eventRiskStatus"),
                "AUDIT", firstTextFromJson(audit.getContextJson(), "prePositionReadiness"),
                firstTextFromJson(audit.getContextJson(), "scoreBuyFormingState"),
                entry, tp, sl), previewHash, "AUDIT:" + audit.getId(), audit.getEventTime());
    }

    private OpportunityFingerprint opportunityFromEvidence(RuntimeDecisionEvidence row) {
        String side = row.getSide() == null || row.getSide().isBlank() ? SIDE : row.getSide();
        String bar = barTimestamp(row.getEvidenceTime(), row.getIntervalCode());
        String entry = firstTextFromJson(row.getExecutionPreviewJson(), "entry", "entryPrice");
        String tp = firstTextFromJson(row.getExecutionPreviewJson(), "tp", "tpPrice");
        String sl = firstTextFromJson(row.getExecutionPreviewJson(), "sl", "slPrice");
        String previewHash = firstTextFromJson(row.getExecutionPreviewJson(), "previewHash", "scoreBuyPreviewHash");
        if (previewHash == null) {
            previewHash = previewHash(row.getSymbol(), safeLong(row.getStrategyId()), side,
                    firstTextFromJson(row.getRiskGateResultJson(), "eventRisk", "eventRiskStatus"),
                    firstTextFromJson(row.getPolicyInputsJson(), "prePositionReadiness"),
                    firstTextFromJson(row.getFeaturesSnapshotJson(), "scoreBuyFormingState"),
                    entry, tp, sl,
                    firstTextFromJson(row.getExecutionPreviewJson(), "proposedNotionalUsdt", "notionalUsdt"),
                    firstTextFromJson(row.getExecutionPreviewJson(), "maxLossIfWrongUsdt"));
        }
        return new OpportunityFingerprint(opportunityKey(row.getSymbol(), safeLong(row.getStrategyId()), side,
                bar, "EVIDENCE", row.getDecisionId(), row.getId(), previewHash,
                firstTextFromJson(row.getRiskGateResultJson(), "eventRisk", "eventRiskStatus"),
                textValue(row.getFinalOutcome(), "EVIDENCE"),
                firstTextFromJson(row.getPolicyInputsJson(), "prePositionReadiness"),
                firstTextFromJson(row.getFeaturesSnapshotJson(), "scoreBuyFormingState"),
                entry, tp, sl), previewHash, "EVIDENCE:" + row.getId(), row.getEvidenceTime());
    }

    private DuplicateDecision duplicateDecision(OpportunityFingerprint current, OpportunityFingerprint last) {
        if (last == null) {
            return new DuplicateDecision(false, true, "NO_PRIOR_DEDUP_OPPORTUNITY");
        }
        if (current.opportunityKey().equals(last.opportunityKey())) {
            return new DuplicateDecision(true, false, "EXACT_SAME_OPPORTUNITY_KEY");
        }
        if (current.previewHash().equals(last.previewHash())) {
            return new DuplicateDecision(true, false, "SAME_PREVIEW_HASH");
        }
        return new DuplicateDecision(false, true, "DISTINCT_OPPORTUNITY_CONTINUED");
    }

    private String previewHash(String symbol, long strategyId, String side, String eventRisk,
                               String readiness, String formingState, String entry, String tp,
                               String sl, String proposedNotional, String maxLoss) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("symbol", normalizeSymbol(symbol));
        node.put("strategyId", strategyId);
        node.put("side", normalizeSide(side));
        node.put("eventRisk", textValue(eventRisk, "UNKNOWN"));
        node.put("prePositionReadiness", textValue(readiness, "UNKNOWN"));
        node.put("scoreBuyFormingState", textValue(formingState, "UNKNOWN"));
        node.put("entry", textValue(entry, "UNKNOWN"));
        node.put("tp", textValue(tp, "UNKNOWN"));
        node.put("sl", textValue(sl, "UNKNOWN"));
        node.put("proposedNotionalUsdt", textValue(proposedNotional, "UNKNOWN"));
        node.put("maxLossIfWrongUsdt", textValue(maxLoss, "UNKNOWN"));
        return sha256(node.toString());
    }

    private String opportunityKey(String symbol, long strategyId, String side, String barTimestamp,
                                  String source, Long auditOrDecisionId, Long evidenceId, String previewHash,
                                  String eventRisk, String runtimeEvidenceStatus, String readiness,
                                  String formingState, String entry, String tp, String sl) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("symbol", normalizeSymbol(symbol));
        node.put("strategyId", strategyId);
        node.put("side", normalizeSide(side));
        node.put("intervalCode", INTERVAL);
        node.put("barTimestamp", textValue(barTimestamp, "UNKNOWN_BAR"));
        node.put("source", textValue(source, "UNKNOWN_SOURCE"));
        node.put("signalAuditId", auditOrDecisionId == null ? "UNKNOWN" : String.valueOf(auditOrDecisionId));
        node.put("runtimeDecisionId", auditOrDecisionId == null ? "UNKNOWN" : String.valueOf(auditOrDecisionId));
        node.put("evidenceDecisionId", evidenceId == null ? "UNKNOWN" : String.valueOf(evidenceId));
        node.put("previewHash", previewHash);
        node.put("eventRiskBucket", textValue(eventRisk, "UNKNOWN_EVENT_RISK"));
        node.put("runtimeEvidenceStatus", textValue(runtimeEvidenceStatus, "UNKNOWN_EVIDENCE"));
        node.put("prePositionReadiness", textValue(readiness, "UNKNOWN_READINESS"));
        node.put("scoreBuyFormingState", textValue(formingState, "UNKNOWN_STATE"));
        node.put("entryTpSlTupleHash", sha256(textValue(entry, "UNKNOWN") + "|" + textValue(tp, "UNKNOWN") + "|" + textValue(sl, "UNKNOWN")));
        return sha256(node.toString());
    }

    private ArrayNode recentAuditArray(List<BtDecisionAudit> audits) {
        ArrayNode array = objectMapper.createArrayNode();
        for (BtDecisionAudit audit : audits) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("auditId", audit.getId());
            node.put("eventTime", audit.getEventTime() != null ? audit.getEventTime().toString() : "N/A");
            node.put("eventType", audit.getEventType());
            node.put("blocker", audit.getBlocker());
            node.put("reason", audit.getReason());
            node.put("intervalCode", audit.getIntervalCode());
            array.add(node);
        }
        return array;
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("parseError", e.getMessage());
            return node;
        }
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? DEFAULT_SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSide(String side) {
        if (side == null || side.isBlank()) {
            return SIDE;
        }
        String upper = side.trim().toUpperCase(Locale.ROOT);
        return "BUY".equals(upper) ? SIDE : upper;
    }

    private long safeLong(Long value) {
        return value == null ? Long.MIN_VALUE : value;
    }

    private String barTimestamp(LocalDateTime time, String intervalCode) {
        if (time == null) {
            return "UNKNOWN_BAR";
        }
        String interval = intervalCode == null ? INTERVAL : intervalCode.toLowerCase(Locale.ROOT);
        if ("1d".equals(interval)) {
            return time.toLocalDate().atStartOfDay().toString();
        }
        if ("1h".equals(interval)) {
            return time.withMinute(0).withSecond(0).withNano(0).toString();
        }
        return time.withSecond(0).withNano(0).toString();
    }

    private String text(JsonNode node, String key, String fallback) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() || value.asText("").isBlank() ? fallback : value.asText();
    }

    private String firstTextFromJson(String json, String... keys) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            for (String key : keys) {
                String value = firstText(node, key);
                if (value != null) return value;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String firstText(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText("");
        return text.isBlank() ? null : text;
    }

    private String textValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean contains(String value, String needle) {
        return value != null && needle != null
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private void copyArray(JsonNode array, List<String> target) {
        if (!array.isArray()) return;
        for (JsonNode value : array) {
            if (!value.asText("").isBlank()) {
                target.add(value.asText());
            }
        }
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        values.stream().distinct().forEach(array::add);
        return array;
    }

    private String write(ObjectNode root) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return root.toString();
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private record OpportunityFingerprint(String opportunityKey,
                                          String previewHash,
                                          String source,
                                          LocalDateTime eventTime) {
    }

    private record DuplicateDecision(boolean exactDuplicate,
                                     boolean distinctOpportunity,
                                     String reason) {
    }
}
