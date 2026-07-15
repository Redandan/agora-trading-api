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
import java.util.stream.Collectors;

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
            return new MergeResult(List.of(), 0, 0, 0, 0, 0, 0, 0);
        }

        int rawRepresentationCount = rows.stream().mapToInt(EvidenceEventCanonicalizer::representationCount).sum();
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
        int fieldConflicts = 0;
        int semanticConflicts = 0;
        int duplicateSuspects = 0;
        for (List<Map<String, Object>> group : groups.values()) {
            Map<String, Object> canonical = mergeGroup(group);
            if (bool(canonical.get("identity_conflict"))) identityConflicts++;
            if (bool(canonical.get("field_union_conflict"))) fieldConflicts++;
            if (bool(canonical.get("semantic_conflict"))) semanticConflicts++;
            if (bool(canonical.get("duplicate_suspect"))) duplicateSuspects++;
            merged.add(canonical);
        }
        merged.sort(Comparator
                .comparing((Map<String, Object> row) -> time(row.get("evidence_time")),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(row -> text(row.get("canonical_event_identity"))));
        return new MergeResult(List.copyOf(merged), rawRepresentationCount, merged.size(),
                rawRepresentationCount - merged.size(), identityConflicts, fieldConflicts,
                semanticConflicts, duplicateSuspects);
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

        Set<String> decisionIds = identifiers(group, "decision_id", "decision_ids");
        Set<String> liveSignalIds = identifiers(group, "live_signal_id", "live_signal_ids");
        Set<String> symbols = normalizedValues(group, "symbol");
        Set<String> strategies = values(group, "strategy_id");
        Set<String> intervals = normalizedValues(group, "interval_code");
        Set<String> sides = normalizedValues(group, "side");
        Set<String> bars = group.stream().map(EvidenceEventCanonicalizer::barIdentity)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toCollection(TreeSet::new));

        Set<String> composites = group.stream().map(EvidenceEventCanonicalizer::identity)
                .flatMap(value -> value.composites().stream()).collect(Collectors.toCollection(TreeSet::new));
        String canonicalIdentity = canonicalIdentity(decisionIds, liveSignalIds, composites, ordered.get(0));
        boolean completeIdentity = canonicalIdentity != null;
        Set<String> identityConflictReasons = group.stream()
                .flatMap(row -> identifiers(row, "identity_conflict_reasons").stream())
                .collect(Collectors.toCollection(TreeSet::new));
        addMultiplicityReason(identityConflictReasons, decisionIds, "MULTIPLE_DECISION_IDS");
        addMultiplicityReason(identityConflictReasons, liveSignalIds, "MULTIPLE_LIVE_SIGNAL_IDS");
        addMultiplicityReason(identityConflictReasons, symbols, "SYMBOL_MISMATCH");
        addMultiplicityReason(identityConflictReasons, strategies, "STRATEGY_MISMATCH");
        addMultiplicityReason(identityConflictReasons, intervals, "INTERVAL_MISMATCH");
        addMultiplicityReason(identityConflictReasons, sides, "SIDE_MISMATCH");
        addMultiplicityReason(identityConflictReasons, bars, "BAR_OPEN_TIME_MISMATCH");
        boolean identityConflict = !identityConflictReasons.isEmpty()
                || group.stream().anyMatch(row -> bool(row.get("identity_conflict")));

        PlanUnion plan = planUnion(group);
        boolean fieldConflict = !plan.conflictReasons().isEmpty()
                || group.stream().anyMatch(row -> bool(row.get("field_union_conflict")));
        SemanticUnion semantics = semanticUnion(group);
        boolean semanticConflict = semantics.conflict()
                || group.stream().anyMatch(row -> bool(row.get("semantic_conflict")));
        boolean duplicateSuspect = !completeIdentity
                || group.stream().anyMatch(row -> bool(row.get("duplicate_suspect")));

        result.put("canonical_event_identity", completeIdentity
                ? canonicalIdentity
                : "UNRESOLVED:" + text(first(ordered.get(0), "row_source", "row_id", "runtime_evidence_id", "audit_id")));
        result.put("canonical_identity_complete", completeIdentity);
        result.put("identity_conflict", identityConflict);
        result.put("identity_conflict_reasons", List.copyOf(identityConflictReasons));
        result.put("field_union_conflict", fieldConflict);
        result.put("field_conflict_reasons", plan.conflictReasons());
        result.put("semantic_conflict", semanticConflict);
        result.put("semantic_conflict_reasons", semantics.conflictReasons());
        result.put("duplicate_suspect", duplicateSuspect);
        result.put("canonical_merge_eligible", completeIdentity && !identityConflict && !fieldConflict && !semanticConflict);
        int representationCount = group.stream().mapToInt(EvidenceEventCanonicalizer::representationCount).sum();
        result.put("representation_count", representationCount);
        result.put("duplicate_representation_count", representationCount - 1);
        result.put("source_ids", sourceIds(group));
        result.put("decision_ids", List.copyOf(decisionIds));
        result.put("live_signal_ids", List.copyOf(liveSignalIds));
        result.put("runtime_evidence_ids", sourceValues(group, "RUNTIME_EVIDENCE",
                "runtime_evidence_id", "runtime_evidence_ids", "row_id"));
        result.put("audit_ids", sourceValues(group, "DECISION_AUDIT", "audit_id", "audit_ids", "row_id"));
        result.put("event_families", semantics.eventFamilies());
        result.put("selected_actions", semantics.selectedActions());
        result.put("decisions", semantics.decisions());
        result.put("final_outcomes", semantics.finalOutcomes());
        List<String> runtimeExecutionModes = runtimeValues(group, "runtime_execution_modes", "execution_mode");
        List<String> runtimeExecutionStatuses = runtimeValues(group, "runtime_execution_statuses", "execution_status");
        List<String> runtimeFinalOutcomes = runtimeValues(group, "runtime_final_outcomes", "final_outcome");
        result.put("runtime_execution_modes", runtimeExecutionModes);
        result.put("runtime_execution_statuses", runtimeExecutionStatuses);
        result.put("runtime_final_outcomes", runtimeFinalOutcomes);

        result.put("order_sent", group.stream().anyMatch(row -> bool(row.get("order_sent"))));
        result.put("intent_created", group.stream().anyMatch(EvidenceEventCanonicalizer::explicitIntent));
        result.put("oco_plan_created", group.stream().anyMatch(row -> bool(row.get("oco_plan_created"))));
        result.remove("candidate_entry");
        result.remove("candidate_tp");
        result.remove("candidate_sl");
        putIfPresent(result, "candidate_entry", plan.entry());
        putIfPresent(result, "candidate_tp", plan.tp());
        putIfPresent(result, "candidate_sl", plan.sl());

        result.put("event_family", semanticConflict && semantics.eventFamilies().size() > 1
                ? "CONFLICT" : singleText(semantics.eventFamilies()));
        result.put("selected_action", semantics.actionConflict()
                ? "CONFLICT" : strongest(group, "selected_action"));
        result.put("decision", semantics.actionConflict()
                ? "CONFLICT" : strongest(group, "decision"));
        result.put("final_outcome", semantics.outcomeConflict()
                ? "CONFLICT" : authoritativeRuntimeOutcome(group, runtimeFinalOutcomes));
        result.put("signal_source", preferredAuditValue(group, "signal_source"));
        putIfPresent(result, "execution_mode", strongestText(runtimeExecutionModes));
        putIfPresent(result, "execution_status", strongestText(runtimeExecutionStatuses));
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
                                            Set<String> composites,
                                            Map<String, Object> representative) {
        if (decisionIds.size() == 1) return "DECISION:" + decisionIds.iterator().next();
        if (liveSignalIds.size() == 1) return "LIVE_SIGNAL:" + liveSignalIds.iterator().next();
        if (composites.size() == 1) return "MARKET_EVENT:" + composites.iterator().next();
        String existing = identifier(representative.get("canonical_event_identity"));
        return existing == null || existing.startsWith("UNRESOLVED:") ? null : existing;
    }

    private static boolean matches(Identity left, Identity right) {
        if (intersects(left.decisionIds(), right.decisionIds())) return true;
        if (intersects(left.liveSignalIds(), right.liveSignalIds())) return true;
        if (!left.decisionIds().isEmpty() && !right.decisionIds().isEmpty()) return false;
        if (!left.liveSignalIds().isEmpty() && !right.liveSignalIds().isEmpty()) return false;
        return intersects(left.composites(), right.composites());
    }

    private static Identity identity(Map<String, Object> row) {
        Set<String> decisionIds = identifiers(row, "decision_id", "decision_ids");
        Set<String> liveSignalIds = identifiers(row, "live_signal_id", "live_signal_ids");
        String symbol = normalized(row.get("symbol"));
        String strategy = identifier(row.get("strategy_id"));
        String interval = normalized(row.get("interval_code"));
        String side = normalized(row.get("side"));
        String bar = barIdentity(row);
        Set<String> families = eventFamilies(row);
        Set<String> composites = new TreeSet<>();
        if (symbol != null && strategy != null && interval != null && side != null && bar != null) {
            for (String family : families) {
                if (!"CONFLICT".equals(family)) {
                    composites.add(String.join("|", symbol, strategy, interval, side, bar, family));
                }
            }
        }
        String existing = identifier(row.get("canonical_event_identity"));
        if (existing != null && existing.startsWith("MARKET_EVENT:")) {
            composites.add(existing.substring("MARKET_EVENT:".length()));
        }
        return new Identity(decisionIds, liveSignalIds, composites);
    }

    private static Set<String> eventFamilies(Map<String, Object> row) {
        Set<String> existing = identifiers(row, "event_family", "event_families").stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .filter(value -> !"CONFLICT".equals(value))
                .collect(Collectors.toCollection(TreeSet::new));
        if (!existing.isEmpty()) return existing;

        Set<ActionClass> actions = actionClasses(row);
        Set<String> families = actions.stream().map(ActionClass::family)
                .collect(Collectors.toCollection(TreeSet::new));
        if (!families.isEmpty()) return families;
        String source = normalized(row.get("signal_source"));
        return source == null ? Set.of() : Set.of(source);
    }

    private static SemanticUnion semanticUnion(List<Map<String, Object>> group) {
        Set<ActionClass> actionClasses = group.stream().flatMap(row -> actionClasses(row).stream())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<OutcomeClass> outcomeClasses = group.stream().flatMap(row -> outcomeClasses(row).stream())
                .filter(value -> value != OutcomeClass.NEUTRAL)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> families = group.stream().flatMap(row -> eventFamilies(row).stream())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> selectedActions = textValues(group, "selected_action", "selected_actions");
        Set<String> decisions = textValues(group, "decision", "decisions");
        Set<String> finalOutcomes = textValues(group, "final_outcome", "final_outcomes");

        boolean actionConflict = incompatibleActions(actionClasses);
        boolean outcomeConflict = incompatibleOutcomes(outcomeClasses);
        Set<String> reasons = group.stream().flatMap(row -> identifiers(row, "semantic_conflict_reasons").stream())
                .collect(Collectors.toCollection(TreeSet::new));
        if (actionClasses.contains(ActionClass.BUY_ENTRY) && actionClasses.contains(ActionClass.SELL_EXIT)) {
            reasons.add("BUY_ENTRY_VS_SELL_EXIT");
        }
        if (actionClasses.contains(ActionClass.STATE_ADVANCE) && actionClasses.stream()
                .anyMatch(value -> value == ActionClass.BUY_ENTRY || value == ActionClass.SELL_EXIT)) {
            reasons.add("STATE_ADVANCE_VS_ACTION");
        }
        if (outcomeConflict) reasons.add("INCOMPATIBLE_OUTCOME");
        if (outcomeClasses.contains(OutcomeClass.UNKNOWN)) reasons.add("UNKNOWN_OUTCOME_VALUE");
        return new SemanticUnion(List.copyOf(families), List.copyOf(selectedActions), List.copyOf(decisions),
                List.copyOf(finalOutcomes), actionConflict, outcomeConflict, List.copyOf(reasons));
    }

    private static Set<ActionClass> actionClasses(Map<String, Object> row) {
        String selected = normalized(row.get("selected_action"));
        String decision = normalized(row.get("decision"));
        String source = normalized(row.get("signal_source"));
        String explicitFamily = normalized(row.get("event_family"));
        Set<ActionClass> classes = new TreeSet<>();

        boolean state = containsAny(selected, "STATE_ADVANCE", "DONCHIAN_SHADOW_STATE_ADVANCE")
                || "DONCHIAN_STATE_ADVANCE".equals(explicitFamily);
        boolean buy = containsAny(selected, "BUY", "ENTRY")
                || containsAny(decision, "BUY", "ENTRY")
                || containsAny(source, "SIGNAL_BUY", "ENTRY_SKIP", "FILTER_BLOCK", "AUTOTRADE_FAIL")
                || "BUY_ENTRY_BLOCK".equals(explicitFamily)
                || explicitIntent(row) || hasAnyPlanValue(row);
        boolean sell = containsAny(selected, "SELL", "EXIT")
                || containsAny(decision, "SELL", "EXIT")
                || containsAny(source, "SIGNAL_SELL", "SELL", "EXIT")
                || "SELL_EXIT".equals(explicitFamily);
        if (state) classes.add(ActionClass.STATE_ADVANCE);
        if (buy) classes.add(ActionClass.BUY_ENTRY);
        if (sell) classes.add(ActionClass.SELL_EXIT);
        if (classes.isEmpty() && (containsAny(selected, "HOLD", "EVALUATED", "INFO", "PASS")
                || containsAny(decision, "HOLD", "INFO", "PASS")
                || containsAny(source, "SIGNAL_EVAL", "INFO"))) {
            classes.add(ActionClass.OBSERVATION);
        }
        return classes;
    }

    private static Set<OutcomeClass> outcomeClasses(Map<String, Object> row) {
        Set<String> outcomes = textValues(row, "final_outcome", "final_outcomes");
        Set<OutcomeClass> classes = new TreeSet<>();
        if (bool(row.get("order_sent"))) classes.add(OutcomeClass.EXECUTED);
        for (String outcome : outcomes) {
            String value = outcome.toUpperCase(Locale.ROOT);
            classes.add(classifyOutcome(value));
        }
        return classes;
    }

    private static OutcomeClass classifyOutcome(String value) {
        if (containsAny(value, "EXECUT", "FILLED", "ORDER_SENT", "AUTOTRADE_OK")
                || Set.of("OCO_TP", "OCO_SL", "TIME_EXIT_24H").contains(value)) return OutcomeClass.EXECUTED;
        if (containsAny(value, "BLOCK", "SUPPRESS", "DENIED", "CANCELLED", "CANCELED", "FILTERED",
                "REJECT", "SKIP", "ERROR", "FAIL", "CRITICAL", "MISSING")) return OutcomeClass.BLOCKED;
        if ("PASS".equals(value) || "PASSED".equals(value) || value.startsWith("PASS_")
                || value.startsWith("SUCCESS")) return OutcomeClass.PASSED;
        if (value.startsWith("PENDING")
                || Set.of("INFO", "STARTED", "SHADOW_OBSERVED", "WATCHING").contains(value)) {
            return OutcomeClass.NEUTRAL;
        }
        return OutcomeClass.UNKNOWN;
    }

    private static boolean incompatibleActions(Set<ActionClass> classes) {
        return classes.contains(ActionClass.BUY_ENTRY) && classes.contains(ActionClass.SELL_EXIT)
                || classes.contains(ActionClass.STATE_ADVANCE)
                && (classes.contains(ActionClass.BUY_ENTRY) || classes.contains(ActionClass.SELL_EXIT));
    }

    private static boolean incompatibleOutcomes(Set<OutcomeClass> classes) {
        return classes.contains(OutcomeClass.EXECUTED) && classes.contains(OutcomeClass.BLOCKED)
                || classes.contains(OutcomeClass.PASSED) && classes.contains(OutcomeClass.BLOCKED)
                || classes.contains(OutcomeClass.UNKNOWN);
    }

    private static boolean hasAnyPlanValue(Map<String, Object> row) {
        if (first(row, "candidate_entry", "candidate_tp", "candidate_sl") != null) return true;
        for (String field : JSON_FIELDS) {
            JsonNode json = json(row.get(field));
            if (json == null) continue;
            for (String key : List.of("candidateEntry", "candidateTp", "candidateSl", "entryPrice", "tpPrice", "slPrice")) {
                if (!json.path(key).isMissingNode() && !json.path(key).isNull()) return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String value, String... tokens) {
        if (value == null) return false;
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }

    private static PlanUnion planUnion(List<Map<String, Object>> group) {
        Set<BigDecimal> entries = decimals(group, "candidate_entry", "candidateEntry", "entryPrice", "entry");
        Set<BigDecimal> tps = decimals(group, "candidate_tp", "candidateTp", "tpPrice", "tp", "suggestedTp");
        Set<BigDecimal> sls = decimals(group, "candidate_sl", "candidateSl", "slPrice", "sl", "suggestedSl");
        Set<String> reasons = group.stream().flatMap(row -> identifiers(row, "field_conflict_reasons").stream())
                .collect(Collectors.toCollection(TreeSet::new));
        addMultiplicityReason(reasons, entries, "PLAN_ENTRY_MISMATCH");
        addMultiplicityReason(reasons, tps, "PLAN_TP_MISMATCH");
        addMultiplicityReason(reasons, sls, "PLAN_SL_MISMATCH");
        return new PlanUnion(single(entries), single(tps), single(sls), List.copyOf(reasons));
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

    private static Object authoritativeRuntimeOutcome(List<Map<String, Object>> group,
                                                      List<String> runtimeFinalOutcomes) {
        Object runtime = runtimeFinalOutcomes.stream()
                .filter(value -> {
                    String normalized = normalized(value);
                    return normalized != null && !Set.of("PENDING", "INFO", "UNKNOWN", "N/A").contains(normalized);
                })
                .max(Comparator.comparingInt(EvidenceEventCanonicalizer::semanticStrength)
                        .thenComparing(EvidenceEventCanonicalizer::text))
                .orElse(null);
        return runtime != null ? runtime : strongest(group, "final_outcome");
    }

    private static List<String> runtimeValues(List<Map<String, Object>> group,
                                              String provenanceField,
                                              String scalarField) {
        Set<String> values = group.stream().flatMap(row -> identifiers(row, provenanceField).stream())
                .collect(Collectors.toCollection(TreeSet::new));
        group.stream()
                .filter(row -> "RUNTIME_EVIDENCE".equalsIgnoreCase(text(row.get("row_source"))))
                .map(row -> row.get(scalarField)).map(EvidenceEventCanonicalizer::identifier)
                .filter(Objects::nonNull).forEach(values::add);
        if ("execution_status".equals(scalarField)) {
            for (Map<String, Object> row : group) {
                if (!"RUNTIME_EVIDENCE".equalsIgnoreCase(text(row.get("row_source")))) continue;
                for (String jsonField : JSON_FIELDS) {
                    JsonNode node = json(row.get(jsonField));
                    if (node == null) continue;
                    for (String key : List.of("executionStatus", "execution_status")) {
                        String value = identifier(node.path(key).asText(null));
                        if (value != null) values.add(value);
                    }
                }
            }
        }
        return List.copyOf(values);
    }

    private static String strongestText(List<String> values) {
        return values.stream().max(Comparator.comparingInt(EvidenceEventCanonicalizer::semanticStrength)
                .thenComparing(EvidenceEventCanonicalizer::text)).orElse(null);
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
            ids.addAll(identifiers(row, "source_ids"));
            String source = text(row.get("row_source"));
            String id = "RUNTIME_EVIDENCE".equalsIgnoreCase(source)
                    ? identifier(first(row, "runtime_evidence_id", "row_id", "decision_id"))
                    : identifier(first(row, "audit_id", "row_id", "decision_id"));
            if (!source.isBlank()) ids.add(source + ":" + (id == null ? "UNRESOLVED" : id));
        }
        return List.copyOf(ids);
    }

    private static List<String> sourceValues(List<Map<String, Object>> group,
                                             String source,
                                             String... fields) {
        Set<String> values = new TreeSet<>();
        for (Map<String, Object> row : group) {
            Set<String> rowValues = new TreeSet<>();
            for (String field : fields) {
                if (!"row_id".equals(field)) rowValues.addAll(identifiers(row, field));
            }
            if (rowValues.isEmpty() && source.equalsIgnoreCase(text(row.get("row_source")))) {
                String rowId = identifier(row.get("row_id"));
                if (rowId != null) rowValues.add(rowId);
            }
            values.addAll(rowValues);
        }
        return List.copyOf(values);
    }

    private static Set<String> values(List<Map<String, Object>> group, String field) {
        return group.stream().flatMap(row -> identifiers(row, field).stream())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> normalizedValues(List<Map<String, Object>> group, String field) {
        return group.stream().flatMap(row -> identifiers(row, field).stream())
                .map(value -> value.toUpperCase(Locale.ROOT)).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> identifiers(List<Map<String, Object>> group, String... fields) {
        return group.stream().flatMap(row -> identifiers(row, fields).stream())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> identifiers(Map<String, Object> row, String... fields) {
        Set<String> values = new TreeSet<>();
        for (String field : fields) addIdentifiers(row.get(field), values);
        return values;
    }

    private static void addIdentifiers(Object raw, Set<String> target) {
        if (raw instanceof Iterable<?> values) {
            for (Object value : values) addIdentifiers(value, target);
            return;
        }
        if (raw != null && raw.getClass().isArray()) {
            for (Object value : (Object[]) raw) addIdentifiers(value, target);
            return;
        }
        String value = identifier(raw);
        if (value != null) target.add(value);
    }

    private static Set<String> textValues(List<Map<String, Object>> group, String... fields) {
        return group.stream().flatMap(row -> textValues(row, fields).stream())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> textValues(Map<String, Object> row, String... fields) {
        return identifiers(row, fields).stream().filter(value -> !"CONFLICT".equalsIgnoreCase(value))
                .collect(Collectors.toCollection(TreeSet::new));
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

    private static int representationCount(Map<String, Object> row) {
        Object raw = row.get("representation_count");
        if (raw instanceof Number number) return Math.max(1, number.intValue());
        try {
            return raw == null ? 1 : Math.max(1, Integer.parseInt(text(raw)));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static boolean intersects(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return false;
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = smaller == left ? right : left;
        return smaller.stream().anyMatch(larger::contains);
    }

    private static void addMultiplicityReason(Set<String> reasons, Set<?> values, String reason) {
        if (values.size() > 1) reasons.add(reason);
    }

    private static String singleText(List<String> values) {
        return values.size() == 1 ? values.get(0) : null;
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
                              int fieldConflictCount,
                              int semanticConflictCount,
                              int duplicateSuspectCount) {
        public boolean conservesRawCount() {
            return rawObservationCount == uniqueMergedEventCount + duplicateRepresentationCount;
        }
    }

    private record Identity(Set<String> decisionIds, Set<String> liveSignalIds, Set<String> composites) {
    }

    private record PlanUnion(BigDecimal entry, BigDecimal tp, BigDecimal sl, List<String> conflictReasons) {
    }

    private record SemanticUnion(List<String> eventFamilies,
                                 List<String> selectedActions,
                                 List<String> decisions,
                                 List<String> finalOutcomes,
                                 boolean actionConflict,
                                 boolean outcomeConflict,
                                 List<String> conflictReasons) {
        boolean conflict() {
            return actionConflict || outcomeConflict || !conflictReasons.isEmpty();
        }
    }

    private enum ActionClass {
        BUY_ENTRY("BUY_ENTRY_BLOCK"),
        SELL_EXIT("SELL_EXIT"),
        STATE_ADVANCE("DONCHIAN_STATE_ADVANCE"),
        OBSERVATION("SIGNAL_EVAL");

        private final String family;

        ActionClass(String family) {
            this.family = family;
        }

        String family() {
            return family;
        }
    }

    private enum OutcomeClass {
        EXECUTED,
        BLOCKED,
        PASSED,
        NEUTRAL,
        UNKNOWN
    }
}
