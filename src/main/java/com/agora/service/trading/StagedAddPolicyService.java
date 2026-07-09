package com.agora.service.trading;

import com.agora.model.BtDecisionAudit;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StagedAddPolicyService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final String DEFAULT_SIDE = "LONG";
    private static final String DEFAULT_INTERVAL = "1h";
    private static final BigDecimal EXCHANGE_MIN_NOTIONAL = new BigDecimal("5.00");
    private static final BigDecimal DEFAULT_ADD_NOTIONAL = new BigDecimal("5.00");

    private final BtStrategyRepository strategyRepository;
    private final BtLiveSignalRepository liveSignalRepository;
    private final BtDecisionAuditRepository decisionAuditRepository;
    private final RuntimeDecisionEvidenceService runtimeDecisionEvidenceService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public String getStagedAddReadiness(String symbol,
                                        Long strategyId,
                                        String side,
                                        String intervalCode,
                                        Double expectedR,
                                        String tqsBand,
                                        BigDecimal entry,
                                        BigDecimal tp,
                                        BigDecimal sl) {
        Evaluation eval = evaluate(symbol, strategyId, side, intervalCode, expectedR, tqsBand, entry, tp, sl);
        return write(eval.toJson(objectMapper));
    }

    @Transactional(readOnly = true)
    public String getEntryDedupGovernanceDashboard(String symbol, Integer hours) {
        String sym = normalizeSymbol(symbol);
        int windowHours = hours == null ? 24 : Math.max(1, Math.min(hours, 168));
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(windowHours);
        List<BtDecisionAudit> audits = decisionAuditRepository.findWindow(
                        since,
                        LocalDateTime.now(ZoneOffset.UTC),
                        sym,
                        null,
                        false,
                        List.of("ENTRY_SKIP"),
                        PageRequest.of(0, 200))
                .stream()
                .filter(this::isEntryDedup)
                .toList();

        Map<String, List<BtDecisionAudit>> groups = new LinkedHashMap<>();
        for (BtDecisionAudit audit : audits) {
            String key = (audit.getStrategyId() == null ? "UNKNOWN" : audit.getStrategyId())
                    + "|" + nullToDefault(audit.getIntervalCode(), "UNKNOWN");
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(audit);
        }

        ArrayNode groupArray = objectMapper.createArrayNode();
        int wouldAllow = 0;
        int exactDuplicate = 0;
        int budgetBlocked = 0;
        int hardSafetyBlocked = 0;
        for (Map.Entry<String, List<BtDecisionAudit>> group : groups.entrySet()) {
            BtDecisionAudit latest = group.getValue().stream()
                    .max(Comparator.comparing(BtDecisionAudit::getEventTime,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
            if (latest == null || latest.getStrategyId() == null) continue;
            Evaluation eval = evaluate(sym, latest.getStrategyId(), DEFAULT_SIDE, latest.getIntervalCode(),
                    null, null, null, null, null);
            if (eval.wouldAllowStagedAdd()) wouldAllow++;
            if (eval.exactDuplicate()) exactDuplicate++;
            if (eval.blockers().stream().anyMatch(v -> contains(v, "BUDGET"))) budgetBlocked++;
            if (eval.blockers().stream().anyMatch(this::isHardSafetyBlocker)) hardSafetyBlocked++;

            ObjectNode node = objectMapper.createObjectNode();
            node.put("strategyId", latest.getStrategyId());
            node.put("intervalCode", nullToDefault(latest.getIntervalCode(), "UNKNOWN"));
            node.put("entryDedupSkips", group.getValue().size());
            node.put("latestAuditId", latest.getId());
            node.put("latestReason", nullToDefault(latest.getReason(), "N/A"));
            node.put("decision", eval.decision());
            node.put("wouldAllowStagedAdd", eval.wouldAllowStagedAdd());
            node.put("exactDuplicate", eval.exactDuplicate());
            node.put("sameStrategyExposureUsed", money(eval.sameStrategyExposureUsed()));
            node.put("sameStrategyExposureLimit", money(eval.sameStrategyExposureLimit()));
            node.put("remainingAddBudget", money(eval.remainingAddBudget()));
            node.set("blockers", stringArray(eval.blockers()));
            node.set("warnings", stringArray(eval.warnings()));
            groupArray.add(node);
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "getEntryDedupGovernanceDashboard");
        root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence behavior changed.");
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("symbol", sym);
        root.put("hours", windowHours);
        root.put("entryDedupSkipCount", audits.size());
        root.put("strategyIntervalGroupCount", groups.size());
        root.put("wouldAllowStagedAddGroups", wouldAllow);
        root.put("exactDuplicateGroups", exactDuplicate);
        root.put("budgetBlockedGroups", budgetBlocked);
        root.put("hardSafetyBlockedGroups", hardSafetyBlocked);
        root.set("groups", groupArray);
        root.put("orderSent", false);
        root.put("ocoModified", false);
        root.put("writesRuntimeEvidence", false);
        return write(root);
    }

    Evaluation evaluate(String symbol,
                        Long strategyId,
                        String side,
                        String intervalCode,
                        Double expectedROverride,
                        String tqsBandOverride,
                        BigDecimal entryOverride,
                        BigDecimal tpOverride,
                        BigDecimal slOverride) {
        String sym = normalizeSymbol(symbol);
        String normalizedSide = normalizeSide(side);
        String interval = normalizeInterval(intervalCode);
        long sid = strategyId == null ? 0L : strategyId;
        Optional<BtStrategy> strategy = sid > 0 ? strategyRepository.findById(sid) : Optional.empty();
        JsonNode config = strategy.map(BtStrategy::getConfigJson).map(this::readJson).orElse(objectMapper.createObjectNode());
        boolean allowSameStrategyCrossInterval = bool(config, "stagedAddAllowSameStrategyCrossInterval",
                bool(config, "microAddAllowSameStrategyCrossInterval", false));

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        BtDecisionAudit candidateAudit = latestCandidateAudit(sym, sid, interval);
        List<RuntimeDecisionEvidence> evidenceRows = runtimeDecisionEvidenceService.listRecent(sym, 1440, 100)
                .stream()
                .filter(row -> row.getStrategyId() != null && row.getStrategyId() == sid)
                .toList();
        RuntimeDecisionEvidence latestEvidence = evidenceRows.stream()
                .max(Comparator.comparing(RuntimeDecisionEvidence::getEvidenceTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);

        List<BtLiveSignal> openSameStrategy = liveSignalRepository
                .findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(sid)
                .stream()
                .filter(p -> sym.equalsIgnoreCase(p.getSymbol()))
                .filter(p -> normalizedSide.equalsIgnoreCase(nullToDefault(p.getSide(), DEFAULT_SIDE)))
                .filter(p -> allowSameStrategyCrossInterval
                        || interval.equalsIgnoreCase(nullToDefault(p.getIntervalCode(), interval)))
                .toList();
        List<BtLiveSignal> openAll = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull();

        BigDecimal sameExposure = openSameStrategy.stream().map(this::notional).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalExposure = openAll.stream().map(this::notional).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal openMaxLoss = openAll.stream().map(this::maxLoss).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal entry = firstNonNull(entryOverride, moneyFromAudit(candidateAudit, "entry"), moneyFromEvidence(latestEvidence, "entryPrice"));
        BigDecimal tp = firstNonNull(tpOverride, moneyFromAudit(candidateAudit, "tp"), moneyFromEvidence(latestEvidence, "tpPrice"));
        BigDecimal sl = firstNonNull(slOverride, moneyFromAudit(candidateAudit, "sl"), moneyFromEvidence(latestEvidence, "slPrice"));
        Double expectedR = firstNonNull(expectedROverride, doubleFromAudit(candidateAudit, "expected_r"), doubleFromEvidence(latestEvidence, "expectedR"));
        String tqsBand = firstText(tqsBandOverride, tqsBandFromEvidence(latestEvidence));
        String eventRisk = firstText(textFromAudit(candidateAudit, "event_risk"), textFromEvidence(latestEvidence, "eventRiskLevel"), "UNKNOWN");

        BigDecimal exposureLimit = configuredMoney(config, "stagedAddSameStrategyExposureLimitUsdt",
                configuredMoney(config, "microAddMaxSameStrategyExposureUsdt", defaultExposureLimit(sid)));
        BigDecimal addNotionalCap = configuredMoney(config, "stagedAddNotionalUsdt",
                configuredMoney(config, "microAddNotionalUsdt", DEFAULT_ADD_NOTIONAL));
        int maxAddsPerDay = configuredInt(config, "stagedAddMaxOrdersPerDay", 1);
        int maxOpenPositions = configuredInt(config, "stagedAddMaxOpenPositions", defaultMaxOpenPositions(sid));
        BigDecimal maxLossCap = configuredMoney(config, "stagedAddOpenMaxLossCapUsdt",
                configuredMoney(config, "exposureOptimizerOpenMaxLossCapUsdt", new BigDecimal("7")));
        BigDecimal capitalUsdt = configuredMoney(config, "exposureOptimizerCapitalUsdt", BigDecimal.ZERO);
        BigDecimal exposureCapPct = configuredMoney(config, "exposureOptimizerMaxActualExposurePct", new BigDecimal("50"));

        long todayEntries = liveSignalRepository.countByStrategyIdAndAutoTradedIsTrueAndCreatedAtAfter(
                sid, LocalDate.now(ZoneOffset.UTC).atStartOfDay());
        BigDecimal remaining = exposureLimit.subtract(sameExposure).max(BigDecimal.ZERO).setScale(2, RoundingMode.DOWN);
        BigDecimal recommended = addNotionalCap.min(remaining).setScale(2, RoundingMode.DOWN);
        BigDecimal candidateMaxLoss = maxLoss(entry, sl, recommended, normalizedSide);
        Opportunity current = currentOpportunity(sym, sid, normalizedSide, interval, candidateAudit,
                latestEvidence, entry, tp, sl, expectedR, tqsBand, eventRisk);
        Opportunity lastOpen = latestOpenOpportunity(openSameStrategy, expectedR, tqsBand, eventRisk);
        boolean exactDuplicate = lastOpen != null
                && (current.previewHash().equals(lastOpen.previewHash())
                || (current.barOpenTime() != null && current.barOpenTime().equals(lastOpen.barOpenTime())
                && priceTupleSame(current, lastOpen)));

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (sid <= 0 || strategy.isEmpty()) blockers.add("STRATEGY_NOT_FOUND");
        if (!"LONG".equals(normalizedSide)) blockers.add("SIDE_NOT_SUPPORTED_V0");
        if (candidateAudit == null && entryOverride == null && latestEvidence == null) blockers.add("NO_RECENT_ENTRY_CANDIDATE");
        if (openSameStrategy.isEmpty()) blockers.add("NO_EXISTING_POSITION_FOR_STAGED_ADD");
        if (exactDuplicate) blockers.add("EXACT_DUPLICATE_OPPORTUNITY");
        if (exposureLimit.compareTo(BigDecimal.ZERO) <= 0) blockers.add("STRATEGY_NOT_STAGED_ADD_ALLOWLISTED");
        if (remaining.compareTo(EXCHANGE_MIN_NOTIONAL) < 0) blockers.add("STAGED_ADD_BUDGET_BELOW_EXCHANGE_MIN");
        if (recommended.compareTo(EXCHANGE_MIN_NOTIONAL) < 0) blockers.add("RECOMMENDED_NOTIONAL_BELOW_EXCHANGE_MIN");
        if (openSameStrategy.size() >= maxOpenPositions) blockers.add("STAGED_ADD_OPEN_POSITION_LIMIT_REACHED");
        if (maxAddsPerDay > 0 && todayEntries >= maxAddsPerDay) blockers.add("STAGED_ADD_DAILY_CAP_REACHED");
        if (expectedR == null || expectedR <= 0) blockers.add(expectedR == null ? "EV_UNKNOWN" : "EV_FAIL");
        if (!tqsAtLeastProbe(tqsBand)) blockers.add(tqsBand == null ? "TQS_UNKNOWN" : "TQS_BELOW_PROBE_DRY_RUN");
        if (evidenceRows.isEmpty()) blockers.add("RUNTIME_EVIDENCE_MISSING");
        if (latestEvidence != null && contains(latestEvidence.getFreshnessState(), "BLOCK")) blockers.add("DATA_FRESHNESS_HARD_FAIL");
        if (openSameStrategy.stream().anyMatch(p -> p.getOcoOrderListId() == null)) blockers.add("EXISTING_POSITION_OCO_MISSING");
        if (maxLossCap.compareTo(BigDecimal.ZERO) > 0
                && openMaxLoss.add(candidateMaxLoss).compareTo(maxLossCap) > 0) {
            blockers.add("OPEN_MAX_LOSS_CAP_EXCEEDED");
        }
        if (capitalUsdt.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal exposureCap = capitalUsdt.multiply(exposureCapPct)
                    .divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
            if (totalExposure.add(recommended).compareTo(exposureCap) > 0) {
                blockers.add("TOTAL_EXPOSURE_CAP_EXCEEDED");
            }
        }
        if (!configuredLiveEnabled(config)) {
            warnings.add("READ_ONLY_OR_SHADOW_ONLY_V0_NO_LIVE_STAGED_ADD_ENABLED");
        }

        String decision;
        if (blockers.contains("EXACT_DUPLICATE_OPPORTUNITY")) {
            decision = "BLOCK_EXACT_DUPLICATE";
        } else if (blockers.stream().anyMatch(v -> contains(v, "BUDGET") || contains(v, "NOTIONAL"))) {
            decision = "BLOCK_BUDGET_EXHAUSTED";
        } else if (!blockers.isEmpty()) {
            decision = "BLOCK_HARD_SAFETY";
        } else if (configuredLiveEnabled(config)) {
            decision = "READY_STAGED_ADD_DRY_RUN";
            warnings.add("LIVE_WRITE_PATH_NOT_IMPLEMENTED_BY_THIS_READ_ONLY_POLICY");
        } else {
            decision = "SHADOW_STAGED_ADD_CANDIDATE";
        }
        boolean wouldAllow = blockers.isEmpty();
        return new Evaluation(sym, sid, normalizedSide, interval, decision, blockers, warnings,
                current, lastOpen, exactDuplicate, sameExposure, exposureLimit, remaining, recommended,
                candidateMaxLoss, openSameStrategy.size(), maxOpenPositions, todayEntries, maxAddsPerDay,
                expectedR, tqsBand, eventRisk, evidenceRows.size(), totalExposure, openMaxLoss, wouldAllow, now);
    }

    private BtDecisionAudit latestCandidateAudit(String symbol, long strategyId, String intervalCode) {
        return decisionAuditRepository.findWindow(
                        LocalDateTime.now(ZoneOffset.UTC).minusHours(24),
                        LocalDateTime.now(ZoneOffset.UTC),
                        symbol,
                        strategyId,
                        false,
                        List.of("ENTRY_SKIP", "SIGNAL_EVAL", "SIGNAL_BUY", "ATTENTION_HIT"),
                        PageRequest.of(0, 50))
                .stream()
                .filter(a -> intervalCode.equalsIgnoreCase(nullToDefault(a.getIntervalCode(), intervalCode)))
                .filter(a -> isBuyLike(a) || isEntryDedup(a))
                .max(Comparator.comparing(BtDecisionAudit::getEventTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    private boolean isBuyLike(BtDecisionAudit audit) {
        return contains(audit.getEventType(), "BUY")
                || contains(audit.getReason(), "BUY")
                || contains(audit.getContextJson(), "LONG")
                || contains(audit.getContextJson(), "BUY");
    }

    private boolean isEntryDedup(BtDecisionAudit audit) {
        return contains(audit.getBlocker(), "EntryDedup") || contains(audit.getReason(), "EntryDedup");
    }

    private boolean isHardSafetyBlocker(String blocker) {
        return contains(blocker, "RUNTIME_EVIDENCE")
                || contains(blocker, "OCO")
                || contains(blocker, "DATA_FRESHNESS")
                || contains(blocker, "EV_")
                || contains(blocker, "TQS_")
                || contains(blocker, "CAP_EXCEEDED");
    }

    private Opportunity currentOpportunity(String symbol,
                                           long strategyId,
                                           String side,
                                           String interval,
                                           BtDecisionAudit audit,
                                           RuntimeDecisionEvidence evidence,
                                           BigDecimal entry,
                                           BigDecimal tp,
                                           BigDecimal sl,
                                           Double expectedR,
                                           String tqsBand,
                                           String eventRisk) {
        LocalDateTime bar = audit != null && audit.getBarOpenTime() != null
                ? audit.getBarOpenTime()
                : LocalDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0);
        Long auditId = audit == null ? null : audit.getId();
        Long evidenceId = evidence == null ? null : evidence.getId();
        String previewHash = hash(symbol, strategyId, side, interval, bar, money(entry), money(tp), money(sl),
                evBucket(expectedR), nullToDefault(tqsBand, "UNKNOWN"), nullToDefault(eventRisk, "UNKNOWN"));
        return new Opportunity(opportunityKey(symbol, strategyId, side, interval, bar, auditId, evidenceId,
                previewHash, evBucket(expectedR), tqsBand, eventRisk, entry, tp, sl),
                previewHash,
                auditId,
                evidenceId,
                bar,
                entry,
                tp,
                sl,
                audit == null ? "EVIDENCE_OR_MANUAL" : "AUDIT:" + audit.getEventType());
    }

    private Opportunity latestOpenOpportunity(List<BtLiveSignal> open,
                                              Double expectedR,
                                              String tqsBand,
                                              String eventRisk) {
        return open.stream()
                .max(Comparator.comparing(BtLiveSignal::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(p -> {
                    BigDecimal entry = p.getActualEntryPrice() != null ? p.getActualEntryPrice() : p.getEntryPrice();
                    BigDecimal tp = p.getSuggestedTp();
                    BigDecimal sl = p.getSuggestedSl();
                    String side = nullToDefault(p.getSide(), DEFAULT_SIDE);
                    String interval = nullToDefault(p.getIntervalCode(), DEFAULT_INTERVAL);
                    String previewHash = hash(p.getSymbol(), p.getStrategyId(), side, interval, p.getBarOpenTime(),
                            money(entry), money(tp), money(sl), evBucket(expectedR), nullToDefault(tqsBand, "UNKNOWN"),
                            nullToDefault(eventRisk, "UNKNOWN"));
                    return new Opportunity(opportunityKey(p.getSymbol(), p.getStrategyId(), side, interval,
                            p.getBarOpenTime(), null, null, previewHash, evBucket(expectedR), tqsBand, eventRisk,
                            entry, tp, sl), previewHash, null, null, p.getBarOpenTime(), entry, tp, sl,
                            "OPEN_POSITION:" + p.getId());
                })
                .orElse(null);
    }

    private String opportunityKey(String symbol,
                                  long strategyId,
                                  String side,
                                  String interval,
                                  LocalDateTime bar,
                                  Long auditId,
                                  Long evidenceId,
                                  String previewHash,
                                  String evBucket,
                                  String tqsBand,
                                  String eventRisk,
                                  BigDecimal entry,
                                  BigDecimal tp,
                                  BigDecimal sl) {
        return hash(symbol, strategyId, side, interval, bar, auditId, evidenceId, previewHash, evBucket,
                nullToDefault(tqsBand, "UNKNOWN"), nullToDefault(eventRisk, "UNKNOWN"), money(entry), money(tp), money(sl));
    }

    private String hash(Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                digest.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '|');
            }
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("hash failed", e);
        }
    }

    private boolean priceTupleSame(Opportunity a, Opportunity b) {
        return money(a.entry()).equals(money(b.entry()))
                && money(a.tp()).equals(money(b.tp()))
                && money(a.sl()).equals(money(b.sl()));
    }

    private BigDecimal defaultExposureLimit(long strategyId) {
        if (strategyId == 575L) return new BigDecimal("15.00");
        if (strategyId == 508L) return new BigDecimal("10.00");
        if (strategyId == 485L) return new BigDecimal("50.00");
        return BigDecimal.ZERO;
    }

    private int defaultMaxOpenPositions(long strategyId) {
        if (strategyId == 575L || strategyId == 508L) return 2;
        if (strategyId == 485L) return 5;
        return 1;
    }

    private boolean configuredLiveEnabled(JsonNode config) {
        return bool(config, "stagedAddLiveEnabled", false)
                || bool(config, "microAddLiveEnabled", false)
                || "ALLOW_STAGED_MICRO_ADD_LIVE_IF_EV_POSITIVE"
                .equalsIgnoreCase(text(config, "entryDedupDecisionMode", ""));
    }

    private boolean tqsAtLeastProbe(String band) {
        if (band == null || band.isBlank()) return false;
        String b = band.trim().toUpperCase(Locale.ROOT);
        return b.equals("PROBE_DRY_RUN") || b.equals("SMALL_DRY_RUN") || b.equals("CAPPED_SMALL_DRY_RUN");
    }

    private BigDecimal notional(BtLiveSignal signal) {
        BigDecimal entry = signal.getActualEntryPrice() != null ? signal.getActualEntryPrice() : signal.getEntryPrice();
        BigDecimal qty = signal.getOcoQty() != null && signal.getOcoQty().signum() > 0 ? signal.getOcoQty() : signal.getTradedQty();
        if (entry == null || qty == null) return BigDecimal.ZERO;
        return entry.multiply(qty).abs();
    }

    private BigDecimal maxLoss(BtLiveSignal signal) {
        BigDecimal entry = signal.getActualEntryPrice() != null ? signal.getActualEntryPrice() : signal.getEntryPrice();
        BigDecimal qty = signal.getOcoQty() != null && signal.getOcoQty().signum() > 0 ? signal.getOcoQty() : signal.getTradedQty();
        BigDecimal sl = signal.getSuggestedSl();
        if (entry == null || qty == null || sl == null) return BigDecimal.ZERO;
        boolean isShort = "SHORT".equalsIgnoreCase(signal.getSide());
        BigDecimal diff = isShort ? sl.subtract(entry) : entry.subtract(sl);
        return diff.signum() > 0 ? diff.multiply(qty).abs() : BigDecimal.ZERO;
    }

    private BigDecimal maxLoss(BigDecimal entry, BigDecimal sl, BigDecimal notional, String side) {
        if (entry == null || entry.signum() <= 0 || sl == null || notional == null || notional.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal pct = "SHORT".equalsIgnoreCase(side)
                ? sl.subtract(entry).divide(entry, 8, RoundingMode.HALF_UP)
                : entry.subtract(sl).divide(entry, 8, RoundingMode.HALF_UP);
        return pct.signum() > 0 ? notional.multiply(pct).setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private BigDecimal moneyFromAudit(BtDecisionAudit audit, String field) {
        if (audit == null) return null;
        return money(readJson(audit.getContextJson()), field);
    }

    private Double doubleFromAudit(BtDecisionAudit audit, String field) {
        if (audit == null) return null;
        JsonNode ctx = readJson(audit.getContextJson());
        return number(ctx, field);
    }

    private String textFromAudit(BtDecisionAudit audit, String field) {
        if (audit == null) return null;
        return text(readJson(audit.getContextJson()), field, null);
    }

    private BigDecimal moneyFromEvidence(RuntimeDecisionEvidence evidence, String field) {
        if (evidence == null) return null;
        BigDecimal fromExecution = money(readJson(evidence.getExecutionPreviewJson()), field);
        if (fromExecution != null) return fromExecution;
        return money(readJson(evidence.getFeaturesSnapshotJson()), field);
    }

    private Double doubleFromEvidence(RuntimeDecisionEvidence evidence, String field) {
        if (evidence == null) return null;
        Double fromEv = number(readJson(evidence.getEvResultJson()), field);
        if (fromEv != null) return fromEv;
        return number(readJson(evidence.getPolicyInputsJson()), field);
    }

    private String textFromEvidence(RuntimeDecisionEvidence evidence, String field) {
        if (evidence == null) return null;
        String fromExec = text(readJson(evidence.getExecutionPreviewJson()), field, null);
        if (fromExec != null) return fromExec;
        return text(readJson(evidence.getPolicyInputsJson()), field, null);
    }

    private String tqsBandFromEvidence(RuntimeDecisionEvidence evidence) {
        if (evidence == null) return null;
        String value = text(readJson(evidence.getTqsResultJson()), "tqsBand", null);
        if (value != null) return value;
        return text(readJson(evidence.getTqsJson()), "tqsBand", null);
    }

    private BigDecimal configuredMoney(JsonNode config, String key, BigDecimal def) {
        BigDecimal value = money(config, key);
        return value == null ? def : value;
    }

    private int configuredInt(JsonNode config, String key, int def) {
        JsonNode n = config.path(key);
        if (n.isNumber()) return n.asInt();
        if (n.isTextual()) {
            try { return Integer.parseInt(n.asText().trim()); } catch (Exception ignored) {}
        }
        return def;
    }

    private boolean bool(JsonNode config, String key, boolean def) {
        JsonNode n = config.path(key);
        if (n.isBoolean()) return n.asBoolean();
        if (n.isNumber()) return n.asInt() != 0;
        if (n.isTextual()) return "true".equalsIgnoreCase(n.asText()) || "1".equals(n.asText());
        return def;
    }

    private BigDecimal money(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || !node.has(field)) return null;
        JsonNode n = node.path(field);
        if (n.isNumber()) return n.decimalValue();
        if (n.isTextual() && !n.asText().isBlank()) {
            try { return new BigDecimal(n.asText().trim()); } catch (Exception ignored) {}
        }
        return null;
    }

    private Double number(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || !node.has(field)) return null;
        JsonNode n = node.path(field);
        if (n.isNumber()) return n.asDouble();
        if (n.isTextual() && !n.asText().isBlank()) {
            try { return Double.parseDouble(n.asText().trim()); } catch (Exception ignored) {}
        }
        return null;
    }

    private String text(JsonNode node, String field, String def) {
        if (node == null || node.isMissingNode() || !node.has(field)) return def;
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? def : value;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private String evBucket(Double expectedR) {
        if (expectedR == null) return "UNKNOWN";
        if (expectedR < 0) return "NEGATIVE";
        if (expectedR < 0.2) return "LOW_POSITIVE";
        if (expectedR < 0.5) return "POSITIVE";
        return "HIGH_POSITIVE";
    }

    private String money(BigDecimal value) {
        if (value == null) return "N/A";
        return value.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? DEFAULT_SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSide(String side) {
        if (side == null || side.isBlank()) return DEFAULT_SIDE;
        String s = side.trim().toUpperCase(Locale.ROOT);
        return "BUY".equals(s) ? "LONG" : s;
    }

    private String normalizeInterval(String intervalCode) {
        return intervalCode == null || intervalCode.isBlank() ? DEFAULT_INTERVAL : intervalCode.trim();
    }

    private boolean contains(String value, String needle) {
        return value != null && needle != null
                && value.toUpperCase(Locale.ROOT).contains(needle.toUpperCase(Locale.ROOT));
    }

    private String nullToDefault(String value, String def) {
        return value == null || value.isBlank() ? def : value;
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        if (values != null) values.stream().distinct().forEach(array::add);
        return array;
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    record Opportunity(String opportunityKey,
                       String previewHash,
                       Long auditId,
                       Long evidenceId,
                       LocalDateTime barOpenTime,
                       BigDecimal entry,
                       BigDecimal tp,
                       BigDecimal sl,
                       String source) {
    }

    record Evaluation(String symbol,
                      long strategyId,
                      String side,
                      String intervalCode,
                      String decision,
                      List<String> blockers,
                      List<String> warnings,
                      Opportunity currentOpportunity,
                      Opportunity lastOpportunity,
                      boolean exactDuplicate,
                      BigDecimal sameStrategyExposureUsed,
                      BigDecimal sameStrategyExposureLimit,
                      BigDecimal remainingAddBudget,
                      BigDecimal recommendedAddNotional,
                      BigDecimal maxLossIfWrong,
                      int openSameStrategyPositions,
                      int maxOpenPositions,
                      long ordersToday,
                      int maxOrdersPerDay,
                      Double expectedR,
                      String tqsBand,
                      String eventRisk,
                      int runtimeEvidenceRows,
                      BigDecimal totalOpenExposure,
                      BigDecimal openMaxLoss,
                      boolean wouldAllowStagedAdd,
                      LocalDateTime generatedAt) {

        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode root = mapper.createObjectNode();
            root.put("tool", "getStagedAddReadiness");
            root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence behavior changed.");
            root.put("generatedAtUtc", generatedAt.toString());
            root.put("symbol", symbol);
            root.put("strategyId", strategyId);
            root.put("side", side);
            root.put("intervalCode", intervalCode);
            root.put("decision", decision);
            root.put("wouldAllowStagedAdd", wouldAllowStagedAdd);
            root.put("orderSent", false);
            root.put("ocoModified", false);
            root.put("writesRuntimeEvidence", false);
            root.put("exactDuplicate", exactDuplicate);
            root.put("duplicateMode", "DISTINCT_OPPORTUNITY");
            root.put("currentOpportunityKey", currentOpportunity == null ? null : currentOpportunity.opportunityKey());
            root.put("currentPreviewHash", currentOpportunity == null ? null : currentOpportunity.previewHash());
            root.put("currentOpportunitySource", currentOpportunity == null ? "UNKNOWN" : currentOpportunity.source());
            root.put("lastOpportunityKey", lastOpportunity == null ? null : lastOpportunity.opportunityKey());
            root.put("lastPreviewHash", lastOpportunity == null ? null : lastOpportunity.previewHash());
            root.put("lastOpportunitySource", lastOpportunity == null ? "NONE" : lastOpportunity.source());
            root.put("sameStrategyExposureUsed", format(sameStrategyExposureUsed));
            root.put("sameStrategyExposureLimit", format(sameStrategyExposureLimit));
            root.put("remainingAddBudget", format(remainingAddBudget));
            root.put("recommendedAddNotional", format(recommendedAddNotional));
            root.put("exchangeMinNotionalUsdt", format(EXCHANGE_MIN_NOTIONAL));
            root.put("maxLossIfWrong", format(maxLossIfWrong));
            root.put("openSameStrategyPositions", openSameStrategyPositions);
            root.put("maxOpenPositions", maxOpenPositions);
            root.put("ordersToday", ordersToday);
            root.put("maxOrdersPerDay", maxOrdersPerDay);
            root.put("expectedR", expectedR == null ? null : expectedR);
            root.put("tqsBand", tqsBand == null ? "UNKNOWN" : tqsBand);
            root.put("eventRisk", eventRisk == null ? "UNKNOWN" : eventRisk);
            root.put("runtimeEvidenceRows", runtimeEvidenceRows);
            root.put("totalOpenExposure", format(totalOpenExposure));
            root.put("openMaxLoss", format(openMaxLoss));
            root.set("blockers", array(mapper, blockers));
            root.set("warnings", array(mapper, warnings));
            return root;
        }

        private static ArrayNode array(ObjectMapper mapper, List<String> values) {
            ArrayNode out = mapper.createArrayNode();
            if (values != null) values.stream().distinct().forEach(out::add);
            return out;
        }

        private static String format(BigDecimal value) {
            if (value == null) return "N/A";
            return value.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
    }
}
