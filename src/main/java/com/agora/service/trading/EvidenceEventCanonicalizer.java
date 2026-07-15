package com.agora.service.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure read-model canonicalization for decision-audit/runtime-evidence representations.
 *
 * <p>The matcher deliberately has no time-window fallback. Rows are joined only by the
 * strongest mutually available identity: decision id, live-signal id, or a complete
 * market-event identity. Incomplete identities remain separate and fail closed.</p>
 */
public final class EvidenceEventCanonicalizer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> JSON_FIELDS = List.of(
            "policy_inputs_json", "execution_preview_json", "features_snapshot_json");
    private static final Set<String> PLACEHOLDERS = Set.of(
            "", "NONE", "N/A", "NA", "NULL", "UNKNOWN", "NOT_APPLICABLE", "PASS", "INFO", "PENDING");

    private EvidenceEventCanonicalizer() {
    }

    public static MergeResult merge(List<Map<String, Object>> inputRows) {
        List<Map<String, Object>> rows = inputRows == null
                ? List.of()
                : inputRows.stream().filter(Objects::nonNull).toList();
        if (rows.isEmpty()) {
            return new MergeResult(List.of(), 0, 0, 0, 0, 0);
        }

        List<Identity> identities = rows.stream().map(EvidenceEventCanonicalizer::identity).toList();
        int[] parents = new int[rows.size()];
        for (int i = 0; i < parents.length; i++) parents[i] = i;
        for (int left = 0; left < rows.size(); left++) {
            for (int right = left + 1; right < rows.size(); right++) {
                if (matches(identities.get(left), identities.get(right))) {
                    union(parents, left, right);
                }
            }
        }

        Map<Integer, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            groups.computeIfAbsent(find(parents, i), ignored -> new ArrayList<>()).add(rows.get(i));
        }

        List<Map<String, Object>> merged = new ArrayList<>();
        int identityConflicts = 0;
        int duplicateSuspects = 0;
        for (List<Map<String, Object>> group : groups.values()) {
            Map<String, Object> canonical = mergeGroup(group);
            if (bool(canonical.get("identity_conflict"))) identityConflicts++;
            if (bool(canonical.get("duplicate_suspect"))) duplicateSuspects++;
            merged.add(canonical);
        }
        merged.sort(Comparator
                .comparing((Map<String, Object> row) -> time(row.get("evidence_time")),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(row -> text(row.get("canonical_event_identity"))));
        return new MergeResult(List.copyOf(merged), rows.size(), merged.size(),
                rows.size() - merged.size(), identityConflicts, duplicateSuspects);
    }

    private static Map<String, Object> mergeGroup(List<Map<String, Object>> group) {
        List<Map<String, Object>> ordered = group.stream()
                .sorted(Comparator
                        .comparingInt(EvidenceEventCanonicalizer::sourcePriority)
                        .thenComparing(row -> time(row.get("evidence_time")), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(row -> text(first(row, "row_id", "runtime_evidence_id", "audit_id", "decision_id"))))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        ordered.forEach(row -> row.forEach(result::putIfAbsent));

        Set<String> decisionIds = values(group, "decision_id");
        Set<String> liveSignalIds = values(group, "live_signal_id");
        Set<String> symbols = normalizedValues(group, "symbol");
        Set<String> strategies = values(group, "strategy_id");
        Set<String> intervals = normalizedValues(group, "interval_code");
        Set<String> sides = normalizedValues(group, "side");
        Set<String> bars = group.stream().map(EvidenceEventCanonicalizer::barIdentity)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toCollection(TreeSet::new));

        Identity representative = identity(ordered.get(0));
        String canonicalIdentity = canonicalIdentity(decisionIds, liveSignalIds, group, representative);
        boolean completeIdentity = canonicalIdentity != null;
        boolean identityConflict = decisionIds.size() > 1 || liveSignalIds.size() > 1
                || symbols.size() > 1 || strategies.size() > 1
                || intervals.size() > 1 || sides.size() > 1 || bars.size() > 1;

        PlanUnion plan = planUnion(group);
        boolean fieldConflict = plan.conflict();
        boolean duplicateSuspect = !completeIdentity;

        result.put("canonical_event_identity", completeIdentity
                ? canonicalIdentity
                : "UNRESOLVED:" + text(first(ordered.get(0), "row_source", "row_id", "runtime_evidence_id", "audit_id")));
        result.put("canonical_identity_complete", completeIdentity);
        result.put("identity_conflict", identityConflict);
        result.put("field_union_conflict", fieldConflict);
        result.put("duplicate_suspect", duplicateSuspect);
        result.put("canonical_merge_eligible", completeIdentity && !identityConflict && !fieldConflict);
        result.put("representation_count", group.size());
        result.put("duplicate_representation_count", group.size() - 1);
        result.put("source_ids", sourceIds(group));
        result.put("decision_ids", List.copyOf(decisionIds));
        result.put("live_signal_ids", List.copyOf(liveSignalIds));
        result.put("runtime_evidence_ids", sourceValues(group, "RUNTIME_EVIDENCE", "runtime_evidence_id", "row_id"));
        result.put("audit_ids", sourceValues(group, "DECISION_AUDIT", "audit_id", "row_id"));

        result.put("order_sent", group.stream().anyMatch(row -> bool(row.get("order_sent"))));
        result.put("intent_created", group.stream().anyMatch(EvidenceEventCanonicalizer::explicitIntent));
        result.put("oco_plan_created", group.stream().anyMatch(row -> bool(row.get("oco_plan_created"))));
        putIfPresent(result, "candidate_entry", plan.entry());
        putIfPresent(result, "candidate_tp", plan.tp());
        putIfPresent(result, "candidate_sl", plan.sl());

        result.put("selected_action", strongest(group, "selected_action"));
        result.put("decision", strongest(group, "decision"));
        result.put("final_outcome", authoritativeRuntimeOutcome(group));
        result.put("signal_source", preferredAuditValue(group, "signal_source"));
        result.put("execution_mode", preferredRuntimeValue(group, "execution_mode"));
        result.put("policy_mode", strongest(group, "policy_mode"));
        mergeMeaningful(result, group, "terminal_blocker");
        mergeMeaningful(result, group, "blocker_reason");
        mergeMeaningful(result, group, "suppression_reason");
        mergeMeaningful(result, group, "reason");

        Object auditTime = preferredAuditValue(group, "evidence_time");
        if (auditTime != null && !text(auditTime).isBlank()) result.put("evidence_time", auditTime);
        Object auditBar = preferredAuditValue(group, "bar_open_time");
        if (auditBar != null && !text(auditBar).isBlank()) result.put("bar_open_time", auditBar);
        return result;
    }

    private static String canonicalIdentity(Set<String> decisionIds,
                                            Set<String> liveSignalIds,
                                            List<Map<String, Object>> group,
                                            Identity representative) {
        if (decisionIds.size() == 1) return "DECISION:" + decisionIds.iterator().next();
        if (decisionIds.isEmpty() && liveSignalIds.size() == 1) return "LIVE_SIGNAL:" + liveSignalIds.iterator().next();
        if (decisionIds.size() <= 1 && liveSignalIds.size() == 1) return "LIVE_SIGNAL:" + liveSignalIds.iterator().next();
        Set<String> composites = group.stream().map(EvidenceEventCanonicalizer::identity)
                .map(Identity::composite).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        return composites.size() == 1 ? "MARKET_EVENT:" + composites.iterator().next()
                : representative.composite() == null ? null : "MARKET_EVENT:" + representative.composite();
    }

    private static boolean matches(Identity left, Identity right) {
        if (left.decisionId() != null && right.decisionId() != null) {
            return left.decisionId().equals(right.decisionId());
        }
        if (left.liveSignalId() != null && right.liveSignalId() != null) {
            return left.liveSignalId().equals(right.liveSignalId());
        }
        return left.composite() != null && left.composite().equals(right.composite());
    }

    private static Identity identity(Map<String, Object> row) {
        String decisionId = identifier(row.get("decision_id"));
        String liveSignalId = identifier(row.get("live_signal_id"));
        String symbol = normalized(row.get("symbol"));
        String strategy = identifier(row.get("strategy_id"));
        String interval = normalized(row.get("interval_code"));
        String side = normalized(row.get("side"));
        String bar = barIdentity(row);
        String family = eventFamily(row);
        String composite = symbol == null || strategy == null || interval == null || side == null
                || bar == null || family == null
                ? null : String.join("|", symbol, strategy, interval, side, bar, family);
        return new Identity(decisionId, liveSignalId, composite);
    }

    private static String eventFamily(Map<String, Object> row) {
        String combined = normalized(first(row, "event_family", "signal_source", "selected_action", "decision"));
        String all = normalized(text(row.get("signal_source")) + " " + text(row.get("selected_action"))
                + " " + text(row.get("decision")));
        if (all == null) return null;
        if (all.contains("DONCHIAN")) return "DONCHIAN_STATE";
        if (all.contains("SIGNAL_SELL") || all.contains("EXIT") || all.contains("SELL")) return "SELL_EXIT";
        if (all.contains("ENTRY") || all.contains("AUTOTRADE") || all.contains("SIGNAL_BUY")
                || all.contains("FILTER_BLOCK") || all.contains("BUY")) return "BUY_ENTRY";
        if (all.contains("SIGNAL_EVAL") || all.contains("EVALUATED") || all.contains("HOLD")) return "SIGNAL_EVAL";
        return combined;
    }

    private static PlanUnion planUnion(List<Map<String, Object>> group) {
        Set<BigDecimal> entries = decimals(group, "candidate_entry", "candidateEntry", "entryPrice", "entry");
        Set<BigDecimal> tps = decimals(group, "candidate_tp", "candidateTp", "tpPrice", "tp", "suggestedTp");
        Set<BigDecimal> sls = decimals(group, "candidate_sl", "candidateSl", "slPrice", "sl", "suggestedSl");
        return new PlanUnion(single(entries), single(tps), single(sls),
                entries.size() > 1 || tps.size() > 1 || sls.size() > 1);
    }

    private static Set<BigDecimal> decimals(List<Map<String, Object>> group, String directKey, String... jsonKeys) {
        Set<BigDecimal> values = new TreeSet<>();
        for (Map<String, Object> row : group) {
            decimal(row.get(directKey), values);
            for (String field : JSON_FIELDS) {
                JsonNode json = json(row.get(field));
                if (json == null) continue;
                for (String key : jsonKeys) decimal(json.path(key).asText(null), values);
            }
        }
        return values;
    }

    private static void decimal(Object value, Set<BigDecimal> target) {
        if (value == null || text(value).isBlank()) return;
        try {
            target.add(new BigDecimal(text(value)).stripTrailingZeros());
        } catch (NumberFormatException ignored) {
        }
    }

    private static BigDecimal single(Set<BigDecimal> values) {
        return values.size() == 1 ? values.iterator().next() : null;
    }

    private static boolean explicitIntent(Map<String, Object> row) {
        if (bool(row.get("intent_created"))) return true;
        for (String field : JSON_FIELDS) {
            JsonNode json = json(row.get(field));
            if (json == null) continue;
            for (String key : List.of("intentCreated", "intent_created")) {
                JsonNode value = json.path(key);
                if (value.asBoolean(false) || "true".equalsIgnoreCase(value.asText())) return true;
            }
        }
        return false;
    }

    private static JsonNode json(Object raw) {
        if (raw instanceof JsonNode node) return node;
        if (raw == null || text(raw).isBlank()) return null;
        try {
            return JSON.readTree(text(raw));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void mergeMeaningful(Map<String, Object> result,
                                        List<Map<String, Object>> group,
                                        String field) {
        Set<String> values = group.stream().map(row -> text(row.get(field)).trim())
                .filter(value -> meaningfulEvidenceValue(field, value))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        if (!values.isEmpty()) result.put(field, String.join(" | ", values));
    }

    private static boolean meaningfulEvidenceValue(String field, String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (PLACEHOLDERS.contains(normalized)) return false;
        if (!"reason".equals(field) && normalized.contains("GATEPASS") && normalized.contains("INFO")) {
            return false;
        }
        return true;
    }

    private static Object authoritativeRuntimeOutcome(List<Map<String, Object>> group) {
        Object runtime = group.stream()
                .filter(row -> "RUNTIME_EVIDENCE".equalsIgnoreCase(text(row.get("row_source"))))
                .map(row -> row.get("final_outcome"))
                .filter(Objects::nonNull)
                .filter(value -> {
                    String normalized = normalized(value);
                    return normalized != null && !Set.of("PENDING", "INFO", "UNKNOWN", "N/A").contains(normalized);
                })
                .max(Comparator.comparingInt(EvidenceEventCanonicalizer::semanticStrength)
                        .thenComparing(EvidenceEventCanonicalizer::text))
                .orElse(null);
        return runtime != null ? runtime : strongest(group, "final_outcome");
    }

    private static Object strongest(List<Map<String, Object>> group, String field) {
        return group.stream().map(row -> row.get(field)).filter(Objects::nonNull)
                .max(Comparator.comparingInt(EvidenceEventCanonicalizer::semanticStrength)
                        .thenComparing(EvidenceEventCanonicalizer::text))
                .orElse(null);
    }

    private static int semanticStrength(Object value) {
        String normalized = normalized(value);
        if (normalized == null) return 0;
        if (normalized.contains("EXECUT") || normalized.contains("ORDER_SENT") || normalized.contains("AUTOTRADE_OK")) return 100;
        if (normalized.contains("BLOCK") || normalized.contains("REJECT")) return 90;
        if (normalized.contains("BUY") || normalized.contains("SELL")) return 80;
        if (normalized.contains("SKIP") || normalized.contains("SUPPRESS") || normalized.contains("FAIL")) return 70;
        if (normalized.contains("PASS")) return 30;
        if (normalized.contains("HOLD") || normalized.contains("INFO") || normalized.contains("PENDING")) return 10;
        return 20;
    }

    private static Object preferredRuntimeValue(List<Map<String, Object>> group, String field) {
        return preferredSourceValue(group, "RUNTIME_EVIDENCE", field);
    }

    private static Object preferredAuditValue(List<Map<String, Object>> group, String field) {
        Object audit = preferredSourceValue(group, "DECISION_AUDIT", field);
        return audit != null ? audit : strongest(group, field);
    }

    private static Object preferredSourceValue(List<Map<String, Object>> group, String source, String field) {
        return group.stream().filter(row -> source.equalsIgnoreCase(text(row.get("row_source"))))
                .map(row -> row.get(field)).filter(Objects::nonNull)
                .max(Comparator.comparingInt(EvidenceEventCanonicalizer::semanticStrength)
                        .thenComparing(EvidenceEventCanonicalizer::text))
                .orElse(null);
    }

    private static List<String> sourceIds(List<Map<String, Object>> group) {
        Set<String> ids = new TreeSet<>();
        for (Map<String, Object> row : group) {
            String source = text(row.get("row_source"));
            String id = "RUNTIME_EVIDENCE".equalsIgnoreCase(source)
                    ? identifier(first(row, "runtime_evidence_id", "row_id", "decision_id"))
                    : identifier(first(row, "audit_id", "row_id", "decision_id"));
            ids.add(source + ":" + (id == null ? "UNRESOLVED" : id));
        }
        return List.copyOf(ids);
    }

    private static List<String> sourceValues(List<Map<String, Object>> group,
                                             String source,
                                             String... fields) {
        Set<String> values = new TreeSet<>();
        for (Map<String, Object> row : group) {
            if (!source.equalsIgnoreCase(text(row.get("row_source")))) continue;
            String value = identifier(first(row, fields));
            if (value != null) values.add(value);
        }
        return List.copyOf(values);
    }

    private static Set<String> values(List<Map<String, Object>> group, String field) {
        return group.stream().map(row -> identifier(row.get(field))).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> normalizedValues(List<Map<String, Object>> group, String field) {
        return group.stream().map(row -> normalized(row.get(field))).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }

    private static String barIdentity(Map<String, Object> row) {
        Object direct = first(row, "bar_open_time", "barOpenTime");
        if (direct != null && !text(direct).isBlank()) return text(direct).trim();
        for (String field : JSON_FIELDS) {
            JsonNode json = json(row.get(field));
            if (json == null) continue;
            for (String key : List.of("barOpenTime", "bar_open_time")) {
                String value = json.path(key).asText("").trim();
                if (!value.isBlank()) return value;
            }
        }
        return null;
    }

    private static Object first(Map<String, Object> row, String... fields) {
        for (String field : fields) {
            Object value = row.get(field);
            if (value != null && !text(value).isBlank()) return value;
        }
        return null;
    }

    private static String identifier(Object value) {
        if (value == null) return null;
        String normalized = text(value).trim();
        return normalized.isBlank() || "0".equals(normalized) || "N/A".equalsIgnoreCase(normalized)
                || "NULL".equalsIgnoreCase(normalized) ? null : normalized;
    }

    private static String normalized(Object value) {
        String identifier = identifier(value);
        return identifier == null ? null : identifier.toUpperCase(Locale.ROOT);
    }

    private static int sourcePriority(Map<String, Object> row) {
        return "DECISION_AUDIT".equalsIgnoreCase(text(row.get("row_source"))) ? 0 : 1;
    }

    private static LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime localDateTime) return localDateTime;
        if (value == null || text(value).isBlank()) return null;
        try {
            return LocalDateTime.parse(text(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return value != null && Boolean.parseBoolean(text(value));
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static void putIfPresent(Map<String, Object> row, String key, Object value) {
        if (value != null) row.put(key, value);
    }

    private static int find(int[] parents, int value) {
        if (parents[value] != value) parents[value] = find(parents, parents[value]);
        return parents[value];
    }

    private static void union(int[] parents, int left, int right) {
        int leftRoot = find(parents, left);
        int rightRoot = find(parents, right);
        if (leftRoot != rightRoot) parents[Math.max(leftRoot, rightRoot)] = Math.min(leftRoot, rightRoot);
    }

    public record MergeResult(List<Map<String, Object>> rows,
                              int rawObservationCount,
                              int uniqueMergedEventCount,
                              int duplicateRepresentationCount,
                              int identityConflictCount,
                              int duplicateSuspectCount) {
        public boolean conservesRawCount() {
            return rawObservationCount == uniqueMergedEventCount + duplicateRepresentationCount;
        }
    }

    private record Identity(String decisionId, String liveSignalId, String composite) {
    }

    private record PlanUnion(BigDecimal entry, BigDecimal tp, BigDecimal sl, boolean conflict) {
    }
}
