package com.agora.service.trading;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceEventCanonicalizerTest {

    @Test
    void weakRuntimeAndBlockedAuditMergeIndependentOfInputOrder() {
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 77L, "4h", "LONG", "2026-07-15T00:00:00");
        runtime.put("selected_action", "HOLD");
        runtime.put("decision", "HOLD");
        Map<String, Object> audit = row("DECISION_AUDIT", 77L, "4h", "LONG", "2026-07-15T00:00:00");
        audit.put("signal_source", "ENTRY_SKIP");
        audit.put("selected_action", "BLOCK");
        audit.put("terminal_blocker", "TradePlanQualityGate");
        audit.put("policy_inputs_json", "{\"intentCreated\":true,\"candidateEntry\":100,\"candidateTp\":106,\"candidateSl\":88}");

        EvidenceEventCanonicalizer.MergeResult forward = EvidenceEventCanonicalizer.merge(List.of(runtime, audit));
        EvidenceEventCanonicalizer.MergeResult reverse = EvidenceEventCanonicalizer.merge(List.of(audit, runtime));

        assertThat(forward.rows()).isEqualTo(reverse.rows());
        assertThat(forward.rawObservationCount()).isEqualTo(2);
        assertThat(forward.uniqueMergedEventCount()).isEqualTo(1);
        assertThat(forward.duplicateRepresentationCount()).isEqualTo(1);
        assertThat(forward.conservesRawCount()).isTrue();
        assertThat(forward.rows().get(0))
                .containsEntry("intent_created", true)
                .containsEntry("selected_action", "BLOCK")
                .containsEntry("candidate_entry", new java.math.BigDecimal("1E+2"))
                .containsEntry("candidate_tp", new java.math.BigDecimal("106"))
                .containsEntry("candidate_sl", new java.math.BigDecimal("88"));
        EvidenceEventCanonicalizer.MergeResult remerged = EvidenceEventCanonicalizer.merge(forward.rows());
        assertThat(remerged.rows()).isEqualTo(forward.rows());
        assertThat(remerged.rawObservationCount()).isEqualTo(2);
        assertThat(remerged.duplicateRepresentationCount()).isEqualTo(1);
    }

    @Test
    void candidatePlanCanBeUnionedAcrossRepresentationsAndAnyOrderSentWins() {
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 88L, "4h", "LONG", "2026-07-15T00:00:00");
        runtime.put("execution_preview_json", "{\"candidateEntry\":100}");
        runtime.put("order_sent", true);
        Map<String, Object> audit = row("DECISION_AUDIT", 88L, "4h", "LONG", "2026-07-15T00:00:00");
        audit.put("policy_inputs_json", "{\"candidateTp\":106,\"candidateSl\":88}");

        Map<String, Object> merged = EvidenceEventCanonicalizer.merge(List.of(audit, runtime)).rows().get(0);

        assertThat(merged).containsEntry("order_sent", true);
        assertThat(merged.get("candidate_entry")).isNotNull();
        assertThat(merged.get("candidate_tp")).isNotNull();
        assertThat(merged.get("candidate_sl")).isNotNull();
    }

    @Test
    void sameMinuteDifferentIntervalSideOrBarRemainDistinct() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(fallbackRow("4h", "LONG", "2026-07-15T00:00:00", "SIGNAL_EVAL"));
        rows.add(fallbackRow("1h", "LONG", "2026-07-15T00:00:00", "SIGNAL_EVAL"));
        rows.add(fallbackRow("4h", "SHORT", "2026-07-15T00:00:00", "SIGNAL_EVAL"));
        rows.add(fallbackRow("4h", "LONG", "2026-07-14T20:00:00", "SIGNAL_EVAL"));
        Map<String, Object> differentAction = fallbackRow("4h", "LONG", "2026-07-15T00:00:00", "SIGNAL_BUY");
        rows.add(differentAction);

        EvidenceEventCanonicalizer.MergeResult result = EvidenceEventCanonicalizer.merge(rows);

        assertThat(result.uniqueMergedEventCount()).isEqualTo(5);
        assertThat(result.duplicateRepresentationCount()).isZero();
    }

    @Test
    void nullAuditStrategyDoesNotWildcardMatchNearbyRuntime() {
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 101L, "4h", "LONG", "2026-07-15T00:00:00");
        Map<String, Object> audit = row("DECISION_AUDIT", 202L, "4h", "LONG", "2026-07-15T00:02:00");
        audit.put("strategy_id", null);

        EvidenceEventCanonicalizer.MergeResult result = EvidenceEventCanonicalizer.merge(List.of(runtime, audit));

        assertThat(result.uniqueMergedEventCount()).isEqualTo(2);
        assertThat(result.duplicateRepresentationCount()).isZero();
    }

    @Test
    void conflictingStrongIdentityFailsClosed() {
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 303L, "4h", "LONG", "2026-07-15T00:00:00");
        Map<String, Object> audit = row("DECISION_AUDIT", 303L, "1h", "SHORT", "2026-07-15T00:00:00");

        EvidenceEventCanonicalizer.MergeResult result = EvidenceEventCanonicalizer.merge(List.of(runtime, audit));

        assertThat(result.uniqueMergedEventCount()).isEqualTo(1);
        assertThat(result.identityConflictCount()).isEqualTo(1);
        assertThat(result.rows().get(0))
                .containsEntry("identity_conflict", true)
                .containsEntry("canonical_merge_eligible", false);
    }

    @Test
    void runtimeExecutionOutcomeIsAuthoritativeAndInfoPlaceholderCannotHideRealBlocker() {
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 404L, "4h", "LONG", "2026-07-15T00:00:00");
        runtime.put("final_outcome", "PASS");
        runtime.put("blocker_reason", "AttentionRule: ExpectedValueGatePass / INFO");
        Map<String, Object> audit = row("DECISION_AUDIT", 404L, "4h", "LONG", "2026-07-15T00:00:00");
        audit.put("final_outcome", "BLOCKED");
        audit.put("blocker_reason", "TradePlanQualityGate");

        Map<String, Object> merged = EvidenceEventCanonicalizer.merge(List.of(runtime, audit)).rows().get(0);

        assertThat(merged)
                .containsEntry("final_outcome", "CONFLICT")
                .containsEntry("blocker_reason", "TradePlanQualityGate")
                .containsEntry("semantic_conflict", true)
                .containsEntry("canonical_merge_eligible", false);
    }

    @Test
    void sameLiveSignalWithDifferentDecisionIdsIsOneFailClosedConflictComponent() {
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 501L, "4h", "LONG", "2026-07-15T00:00:00");
        runtime.put("live_signal_id", 9901L);
        Map<String, Object> audit = row("DECISION_AUDIT", 502L, "4h", "LONG", "2026-07-15T00:00:00");
        audit.put("live_signal_id", 9901L);

        EvidenceEventCanonicalizer.MergeResult result = EvidenceEventCanonicalizer.merge(List.of(runtime, audit));

        assertThat(result.uniqueMergedEventCount()).isEqualTo(1);
        assertThat(result.duplicateRepresentationCount()).isEqualTo(1);
        assertThat(result.identityConflictCount()).isEqualTo(1);
        assertThat(result.rows().get(0))
                .containsEntry("identity_conflict", true)
                .containsEntry("canonical_merge_eligible", false);
    }

    @Test
    void sameBarDonchianStateAdvanceAndBlockedBuyStaySeparateWithoutStrongIdentity() {
        Map<String, Object> state = fallbackRow("4h", "LONG", "2026-07-15T00:00:00", "DONCHIAN_SHADOW");
        state.put("selected_action", "DONCHIAN_SHADOW_STATE_ADVANCE");
        state.put("decision", "HOLD");
        Map<String, Object> buy = fallbackRow("4h", "LONG", "2026-07-15T00:00:00", "ENTRY_SKIP");
        buy.put("selected_action", "BLOCK");
        buy.put("decision", "BUY");
        buy.put("terminal_blocker", "TradePlanQualityGate");

        EvidenceEventCanonicalizer.MergeResult result = EvidenceEventCanonicalizer.merge(List.of(state, buy));

        assertThat(result.uniqueMergedEventCount()).isEqualTo(2);
        assertThat(result.duplicateRepresentationCount()).isZero();
        assertThat(result.rows()).extracting(row -> row.get("event_family"))
                .containsExactlyInAnyOrder("DONCHIAN_STATE_ADVANCE", "BUY_ENTRY_BLOCK");
    }

    @Test
    void strongIdentityJoiningStateAdvanceAndBlockedBuyFailsSemanticConflict() {
        Map<String, Object> state = row("RUNTIME_EVIDENCE", 601L, "4h", "LONG", "2026-07-15T00:00:00");
        state.put("signal_source", "DONCHIAN_SHADOW");
        state.put("selected_action", "DONCHIAN_SHADOW_STATE_ADVANCE");
        state.put("decision", "HOLD");
        Map<String, Object> buy = row("DECISION_AUDIT", 601L, "4h", "LONG", "2026-07-15T00:00:00");
        buy.put("signal_source", "ENTRY_SKIP");
        buy.put("selected_action", "BLOCK");
        buy.put("decision", "BUY");

        Map<String, Object> merged = EvidenceEventCanonicalizer.merge(List.of(state, buy)).rows().get(0);

        assertThat(merged)
                .containsEntry("semantic_conflict", true)
                .containsEntry("canonical_merge_eligible", false);
        assertThat(merged.get("semantic_conflict_reasons").toString()).contains("STATE_ADVANCE_VS_ACTION");
        assertThat(EvidenceEventCanonicalizer.merge(List.of(state, buy)).semanticConflictCount()).isEqualTo(1);
    }

    @Test
    void sameDecisionBuyAndSellOrEntryAndExitFailsSemanticConflict() {
        Map<String, Object> buy = row("RUNTIME_EVIDENCE", 701L, "4h", "LONG", "2026-07-15T00:00:00");
        buy.put("selected_action", "BUY_ENTRY");
        buy.put("decision", "BUY");
        Map<String, Object> sell = row("DECISION_AUDIT", 701L, "4h", "LONG", "2026-07-15T00:00:00");
        sell.put("selected_action", "SELL_EXIT");
        sell.put("decision", "SELL");

        Map<String, Object> merged = EvidenceEventCanonicalizer.merge(List.of(buy, sell)).rows().get(0);

        assertThat(merged)
                .containsEntry("selected_action", "CONFLICT")
                .containsEntry("decision", "CONFLICT")
                .containsEntry("semantic_conflict", true)
                .containsEntry("canonical_merge_eligible", false);
        assertThat(merged.get("semantic_conflict_reasons").toString()).contains("BUY_ENTRY_VS_SELL_EXIT");
    }

    @Test
    void sameLiveSignalConflictDoesNotDependOnMissingDecisionBridgeOrPermutation() {
        Map<String, Object> first = row("RUNTIME_EVIDENCE", 801L, "4h", "LONG", "2026-07-15T00:00:00");
        first.put("live_signal_id", 9988L);
        Map<String, Object> second = row("DECISION_AUDIT", 802L, "4h", "LONG", "2026-07-15T00:00:00");
        second.put("live_signal_id", 9988L);
        Map<String, Object> bridge = row("RUNTIME_EVIDENCE", 0L, "4h", "LONG", "2026-07-15T00:00:00");
        bridge.put("decision_id", null);
        bridge.put("live_signal_id", 9988L);

        EvidenceEventCanonicalizer.MergeResult direct = EvidenceEventCanonicalizer.merge(List.of(first, second));
        for (List<Map<String, Object>> permutation : List.of(
                List.of(first, second, bridge),
                List.of(bridge, first, second),
                List.of(second, bridge, first))) {
            EvidenceEventCanonicalizer.MergeResult result = EvidenceEventCanonicalizer.merge(permutation);
            assertThat(result.uniqueMergedEventCount()).isEqualTo(1);
            assertThat(result.identityConflictCount()).isEqualTo(1);
            assertThat(result.rows().get(0).get("canonical_merge_eligible")).isEqualTo(false);
            assertThat(result.rows().get(0).get("canonical_event_identity"))
                    .isEqualTo(direct.rows().get(0).get("canonical_event_identity"));
        }
    }

    @Test
    void canonicalOutputCanBeMergedAgainWithoutLosingProvenanceConflictOrCounts() {
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 901L, "4h", "LONG", "2026-07-15T00:00:00");
        runtime.put("live_signal_id", 9909L);
        Map<String, Object> audit = row("DECISION_AUDIT", 902L, "4h", "LONG", "2026-07-15T00:00:00");
        audit.put("live_signal_id", 9909L);

        EvidenceEventCanonicalizer.MergeResult first = EvidenceEventCanonicalizer.merge(List.of(runtime, audit));
        EvidenceEventCanonicalizer.MergeResult second = EvidenceEventCanonicalizer.merge(first.rows());

        assertThat(second.rows()).isEqualTo(first.rows());
        assertThat(second.rawObservationCount()).isEqualTo(first.rawObservationCount());
        assertThat(second.uniqueMergedEventCount()).isEqualTo(first.uniqueMergedEventCount());
        assertThat(second.duplicateRepresentationCount()).isEqualTo(first.duplicateRepresentationCount());
        assertThat(second.conservesRawCount()).isTrue();
        assertThat(second.rows().get(0).get("source_ids"))
                .isEqualTo(first.rows().get(0).get("source_ids"));
    }

    @Test
    void runtimeAuthorityAndFullCanonicalRowSurviveRemergeAndAssociativeGrouping() {
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 1001L, "4h", "LONG", "2026-07-15T00:00:00");
        runtime.put("execution_mode", "LIVE_MICRO");
        runtime.put("execution_status", "EXECUTED_OCO_ATTACHED");
        runtime.put("final_outcome", "EXECUTED_OCO_ATTACHED");
        Map<String, Object> audit = row("DECISION_AUDIT", 1001L, "4h", "LONG", "2026-07-15T00:00:00");
        audit.put("decision", "BUY");
        Map<String, Object> supplement = row("RUNTIME_EVIDENCE", 1001L, "4h", "LONG", "2026-07-15T00:00:00");
        supplement.put("terminal_blocker", "NONE");

        EvidenceEventCanonicalizer.MergeResult direct = EvidenceEventCanonicalizer.merge(List.of(runtime, audit, supplement));
        Map<String, Object> canonical = direct.rows().get(0);
        assertThat(canonical).containsEntry("execution_mode", "LIVE_MICRO")
                .containsEntry("execution_status", "EXECUTED_OCO_ATTACHED");
        assertThat(EvidenceEventCanonicalizer.merge(direct.rows()).rows()).isEqualTo(direct.rows());

        for (List<Map<String, Object>> grouping : List.of(
                concat(EvidenceEventCanonicalizer.merge(List.of(runtime, audit)).rows(), supplement),
                concat(runtime, EvidenceEventCanonicalizer.merge(List.of(audit, supplement)).rows()),
                concat(EvidenceEventCanonicalizer.merge(List.of(runtime, supplement)).rows(), audit))) {
            assertThat(EvidenceEventCanonicalizer.merge(grouping).rows()).isEqualTo(direct.rows());
        }
    }

    @Test
    void suppressedLegacyAndUnknownOutcomesFailClosed() {
        Map<String, Object> pass = row("RUNTIME_EVIDENCE", 1101L, "4h", "LONG", "2026-07-15T00:00:00");
        pass.put("final_outcome", "PASS");
        for (String blocked : List.of("SUPPRESS", "SUPPRESSED", "DENIED", "CANCELLED", "FILTERED")) {
            Map<String, Object> legacy = row("DECISION_AUDIT", 1101L, "4h", "LONG", "2026-07-15T00:00:00");
            legacy.put("final_outcome", blocked);
            Map<String, Object> merged = EvidenceEventCanonicalizer.merge(List.of(pass, legacy)).rows().get(0);
            assertThat(merged).containsEntry("semantic_conflict", true)
                    .containsEntry("canonical_merge_eligible", false);
        }
        Map<String, Object> unknown = row("RUNTIME_EVIDENCE", 1102L, "4h", "LONG", "2026-07-15T00:00:00");
        unknown.put("final_outcome", "MYSTERY_LEGACY_VALUE");
        Map<String, Object> merged = EvidenceEventCanonicalizer.merge(List.of(unknown)).rows().get(0);
        assertThat(merged.get("semantic_conflict_reasons").toString()).contains("UNKNOWN_OUTCOME_VALUE");
        assertThat(merged).containsEntry("canonical_merge_eligible", false);
    }

    @Test
    void dispositionTokensRespectSellExitAndBuyDirection() {
        Map<String, Object> blockedSell = row("RUNTIME_EVIDENCE", 1201L, "4h", "SHORT", "2026-07-15T00:00:00");
        blockedSell.put("selected_action", "BLOCK_SELL");
        blockedSell.put("decision", "SELL");
        blockedSell.put("final_outcome", "BLOCKED");
        Map<String, Object> skippedExit = row("RUNTIME_EVIDENCE", 1202L, "4h", "SHORT", "2026-07-15T00:00:00");
        skippedExit.put("selected_action", "SKIP_EXIT");
        skippedExit.put("decision", "EXIT");
        skippedExit.put("final_outcome", "SKIPPED");
        Map<String, Object> blockedBuy = row("RUNTIME_EVIDENCE", 1203L, "4h", "LONG", "2026-07-15T00:00:00");
        blockedBuy.put("selected_action", "BLOCK_BUY");
        blockedBuy.put("decision", "BUY");
        blockedBuy.put("final_outcome", "BLOCKED");

        assertThat(EvidenceEventCanonicalizer.merge(List.of(blockedSell)).rows().get(0))
                .containsEntry("event_family", "SELL_EXIT").containsEntry("semantic_conflict", false);
        assertThat(EvidenceEventCanonicalizer.merge(List.of(skippedExit)).rows().get(0))
                .containsEntry("event_family", "SELL_EXIT").containsEntry("semantic_conflict", false);
        assertThat(EvidenceEventCanonicalizer.merge(List.of(blockedBuy)).rows().get(0))
                .containsEntry("event_family", "BUY_ENTRY_BLOCK").containsEntry("semantic_conflict", false);
    }

    @Test
    void machineConflictReasonsAndCountsSurviveRemerge() {
        Map<String, Object> left = row("RUNTIME_EVIDENCE", 1301L, "4h", "LONG", "2026-07-15T00:00:00");
        left.put("live_signal_id", 77L);
        left.put("candidate_entry", "100");
        Map<String, Object> right = row("DECISION_AUDIT", 1302L, "4h", "LONG", "2026-07-15T00:00:00");
        right.put("live_signal_id", 77L);
        right.put("candidate_entry", "101");
        EvidenceEventCanonicalizer.MergeResult first = EvidenceEventCanonicalizer.merge(List.of(left, right));
        EvidenceEventCanonicalizer.MergeResult second = EvidenceEventCanonicalizer.merge(first.rows());
        assertThat(first.rows().get(0).get("identity_conflict_reasons").toString())
                .contains("MULTIPLE_DECISION_IDS");
        assertThat(first.rows().get(0).get("field_conflict_reasons").toString())
                .contains("PLAN_ENTRY_MISMATCH");
        assertThat(first.identityConflictCount()).isEqualTo(1);
        assertThat(first.fieldConflictCount()).isEqualTo(1);
        assertThat(second.rows()).isEqualTo(first.rows());
        assertThat(second.fieldConflictCount()).isEqualTo(first.fieldConflictCount());
    }

    @Test
    void dispositionOnlySourcesDoNotInjectBuyDirectionIntoSellOrExit() {
        List<String> sources = List.of("FILTER_BLOCK", "ENTRY_SKIP", "AUTOTRADE_FAIL");
        List<String> directions = List.of("SELL", "EXIT", "SELL");
        for (int i = 0; i < sources.size(); i++) {
            Map<String, Object> row = row("DECISION_AUDIT", 1401L + i, "4h", "SHORT", "2026-07-15T00:00:00");
            row.put("signal_source", sources.get(i));
            row.put("selected_action", "BLOCK");
            row.put("decision", directions.get(i));
            row.put("final_outcome", "BLOCKED");

            Map<String, Object> merged = EvidenceEventCanonicalizer.merge(List.of(row)).rows().get(0);

            assertThat(merged).containsEntry("event_family", "SELL_EXIT")
                    .containsEntry("semantic_conflict", false)
                    .containsEntry("canonical_merge_eligible", true);
            assertThat(merged.get("semantic_conflict_reasons").toString())
                    .doesNotContain("BUY_ENTRY_VS_SELL_EXIT");
        }

        Map<String, Object> blockedBuy = row("DECISION_AUDIT", 1410L, "4h", "LONG", "2026-07-15T00:00:00");
        blockedBuy.put("signal_source", "FILTER_BLOCK");
        blockedBuy.put("selected_action", "BLOCK");
        blockedBuy.put("decision", "BUY");
        blockedBuy.put("final_outcome", "BLOCKED");
        assertThat(EvidenceEventCanonicalizer.merge(List.of(blockedBuy)).rows().get(0))
                .containsEntry("event_family", "BUY_ENTRY_BLOCK")
                .containsEntry("semantic_conflict", false);
    }

    @Test
    void negativeOutcomeTokensTakePriorityOverExecutedSubstrings() {
        for (String negative : List.of(
                "NOT_EXECUTED", "EXECUTION_FAILED", "NOT_FILLED", "UNEXECUTED", "UNFILLED",
                "ORDER_NOT_SENT", "EXECUTION_NOT_SENT", "FILL_UNCONFIRMED")) {
            Map<String, Object> pass = row("RUNTIME_EVIDENCE", 1501L, "4h", "LONG", "2026-07-15T00:00:00");
            pass.put("final_outcome", "PASS");
            Map<String, Object> failed = row("DECISION_AUDIT", 1501L, "4h", "LONG", "2026-07-15T00:00:00");
            failed.put("final_outcome", negative);

            Map<String, Object> merged = EvidenceEventCanonicalizer.merge(List.of(pass, failed)).rows().get(0);

            assertThat(merged).containsEntry("final_outcome", "CONFLICT")
                    .containsEntry("semantic_conflict", true)
                    .containsEntry("canonical_merge_eligible", false);
        }
    }

    @Test
    void singleNegativeOutcomeRemainsBlockedBuyInsteadOfExecuted() {
        Map<String, Object> row = row("RUNTIME_EVIDENCE", 1601L, "4h", "LONG", "2026-07-15T00:00:00");
        row.put("selected_action", "BLOCK_BUY");
        row.put("decision", "BUY");
        row.put("final_outcome", "NOT_EXECUTED");

        Map<String, Object> merged = EvidenceEventCanonicalizer.merge(List.of(row)).rows().get(0);

        assertThat(merged).containsEntry("final_outcome", "NOT_EXECUTED")
                .containsEntry("event_family", "BUY_ENTRY_BLOCK")
                .containsEntry("order_sent", false)
                .containsEntry("semantic_conflict", false);
    }

    @Test
    void actualProducerFailureOutcomesRemainNegativeDespiteOrderSentTokens() {
        for (String negative : List.of(
                "ORDER_FAILED",
                "CRITICAL_ORDER_SENT_LIVE_SIGNAL_SAVE_FAILED",
                "BTC_BASE_ORDER_FAILED",
                "CRITICAL_BTC_BASE_ORDER_SENT_LIVE_SIGNAL_SAVE_FAILED",
                "CRITICAL_ORDER_SENT_FILL_UNCONFIRMED",
                "CRITICAL_ORDER_SENT_SIGNAL_SAVE_FAILED",
                "CRITICAL_ORDER_SENT_OCO_ATTACH_FAILED")) {
            Map<String, Object> pass = row("RUNTIME_EVIDENCE", 1701L, "4h", "LONG", "2026-07-15T00:00:00");
            pass.put("final_outcome", "PASS");
            Map<String, Object> failed = row("DECISION_AUDIT", 1701L, "4h", "LONG", "2026-07-15T00:00:00");
            failed.put("final_outcome", negative);

            Map<String, Object> merged = EvidenceEventCanonicalizer.merge(List.of(pass, failed)).rows().get(0);

            assertThat(merged).containsEntry("semantic_conflict", true)
                    .containsEntry("canonical_merge_eligible", false);
        }
    }

    private List<Map<String, Object>> concat(List<Map<String, Object>> rows, Map<String, Object> row) {
        List<Map<String, Object>> result = new ArrayList<>(rows);
        result.add(row);
        return result;
    }

    private List<Map<String, Object>> concat(Map<String, Object> row, List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(row);
        result.addAll(rows);
        return result;
    }

    private Map<String, Object> fallbackRow(String interval, String side, String bar, String signalSource) {
        Map<String, Object> row = row("RUNTIME_EVIDENCE", 0L, interval, side, "2026-07-15T00:00:00");
        row.put("decision_id", null);
        row.put("live_signal_id", null);
        row.put("bar_open_time", LocalDateTime.parse(bar));
        row.put("signal_source", signalSource);
        return row;
    }

    private Map<String, Object> row(String source, long decisionId, String interval, String side, String time) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("row_source", source);
        row.put("row_id", decisionId);
        row.put("runtime_evidence_id", "RUNTIME_EVIDENCE".equals(source) ? decisionId + 1000 : null);
        row.put("audit_id", "DECISION_AUDIT".equals(source) ? decisionId : null);
        row.put("decision_id", decisionId);
        row.put("evidence_time", LocalDateTime.parse(time));
        row.put("symbol", "BTCUSDT");
        row.put("strategy_id", 508L);
        row.put("interval_code", interval);
        row.put("side", side);
        row.put("bar_open_time", LocalDateTime.parse("2026-07-15T00:00:00"));
        row.put("signal_source", "SIGNAL_EVAL");
        row.put("order_sent", false);
        return row;
    }
}
