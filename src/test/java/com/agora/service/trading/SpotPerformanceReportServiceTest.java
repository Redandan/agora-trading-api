package com.agora.service.trading;

import com.agora.model.BtDecisionAudit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SpotPerformanceReportServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SpotPerformanceReportService report = new SpotPerformanceReportService(null, mapper);
    private final LocalDateTime start = LocalDateTime.of(2026, 10, 2, 8, 0);

    @Test void drawdownUsesPeakReferenceEquityAndIncludesUnrealizedLosses() throws Exception {
        var result = report.summarize("DRA_V1", List.of(sample(0, "30", "30", "0"),
                sample(1, "30", "36", "0.5"), sample(2, "30", "27", "1")), List.of(), start.plusHours(3));
        assertEquals("COMPLETE_OBSERVED_WINDOW", result.get("seriesStatus"));
        assertEquals(0, new BigDecimal("0.25").compareTo((BigDecimal) result.get("sampledMaximumDrawdownRatio")));
        assertEquals(0, new BigDecimal("9").compareTo((BigDecimal) result.get("sampledMaximumDrawdownUsdt")));
        assertEquals(0, new BigDecimal("0.5").compareTo((BigDecimal) result.get("meanHourlyCapitalUtilization")));
    }

    @Test void missingHoursDuplicateSamplesAndChangedCapitalSuppressDrawdown() throws Exception {
        var first = sample(0, "30", "30", "0");
        for (var rows : List.of(List.of(first, sample(2, "30", "25", "1")),
                List.of(first, first, sample(1, "30", "25", "1")),
                List.of(first, sample(1, "60", "55", "1")))) {
            var result = report.summarize("DRA_V1", rows, List.of(), start.plusHours(3));
            assertNull(result.get("sampledMaximumDrawdownRatio"));
            assertNotEquals("COMPLETE_OBSERVED_WINDOW", result.get("seriesStatus"));
        }
    }

    @Test void emptyStaleAndIncompleteEvidenceAreExplicit() throws Exception {
        var empty = report.summarize("DRA_V1", List.of(), List.of(), start.plusHours(8));
        assertEquals("MISSING_PROOF_NO_FORWARD_SAMPLES", empty.get("seriesStatus"));
        var stale = report.summarize("DRA_V1", List.of(sample(0, "30", "30", "0")), List.of(), start.plusHours(8));
        assertEquals("STALE", stale.get("latestStatus"));
        assertNull(stale.get("sampledMaximumDrawdownRatio"));
        BtDecisionAudit malformed = sample(1, "30", "30", "0"); malformed.setContextJson("{");
        var bad = report.summarize("DRA_V1", List.of(sample(0, "30", "30", "0"), malformed), List.of(), start.plusHours(3));
        assertEquals(1, bad.get("invalidSamples"));
        assertNull(bad.get("sampledMaximumDrawdownRatio"));
    }

    @Test void blockedEntryCountsDeduplicateSignalBarsAndExcludeNoSignalAndDuplicateDelivery() throws Exception {
        var block = block(0, "DRA_LIVE_SINGLE_LOT_CAP", true);
        var result = report.summarize("DRA_V1", List.of(), List.of(block, block,
                block(1, "DRA_DUPLICATE_SIGNAL_RESERVATION", true), block(2, "NO_SIGNAL", false)), start.plusHours(4));
        Map<?, ?> evidence = (Map<?, ?>) result.get("blockedEntryEvidence");
        assertEquals(1, evidence.get("observedDistinctBlockedSignalBars"));
        assertEquals("OBSERVED_LOWER_BOUND_NOT_COMPLETE_OPPORTUNITY_LEDGER", evidence.get("coverage"));
    }

    @Test void forwardDecisionsKeepDeferredAndAmbiguousSignalsSeparateFromHandledOrders() throws Exception {
        var result = report.entryEvaluations("DRA_V1", List.of(evaluation(0, false, "NO_QUEUED_ENTRY"),
                evaluation(1, true, "DEFERRED_BUY_RECONCILIATION"),
                evaluation(2, true, "BUY_PATH_BLOCKED_OR_UNCONFIRMED"), evaluation(3, true, "BUY_PATH_HANDLED")));
        assertEquals(4, result.get("observedEvaluationBars")); assertEquals(3, result.get("candidateBars"));
        assertEquals(1, result.get("deferredCandidateBars")); assertEquals(1, result.get("unconfirmedCandidateBars"));
        assertEquals("INCOMPLETE_OBSERVED_WINDOW", result.get("coverage"));
        var duplicate = evaluation(0, true, "BUY_PATH_HANDLED");
        assertNull(report.entryEvaluations("DRA_V1", List.of(duplicate, duplicate)).get("candidateBars"));
    }

    @Test void knownCapacityBlockIsNotAnAmbiguousProviderOutcome() throws Exception {
        var result = report.entryEvaluations("DRA_V1", List.of(
                evaluation(0, true, "BLOCKED:DRA_SINGLE_LOT_ALREADY_OPEN"),
                evaluation(1, true, "UNCONFIRMED_PROVIDER_SUBMISSION")));
        assertEquals(1, result.get("explicitBlockedCandidateBars"));
        assertEquals(1, result.get("unconfirmedCandidateBars"));
        assertEquals(2, result.get("candidateBars"));
    }

    private BtDecisionAudit sample(int hour, String capital, String equity, String utilization) throws Exception {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("schema", SpotPerformancePolicy.EVENT); n.put("owner", "DRA_V1"); n.put("status", "OBSERVED");
        n.put("basis", SpotPerformancePolicy.BASIS); n.put("markTimeUtc", start.plusHours(hour + 1).toString());
        n.put("referenceCapitalUsdt", new BigDecimal(capital)); n.put("referenceEquityUsdt", new BigDecimal(equity));
        n.put("capitalUtilization", new BigDecimal(utilization));
        return row(hour, n);
    }
    private BtDecisionAudit block(int hour, String reason, boolean candidate) throws Exception {
        var row = row(hour, Map.of("entryEvidenceSchema", "LIVE_ENTRY_V1", "entryCandidate", candidate, "freshSignal", true));
        row.setReason(reason); return row;
    }
    private BtDecisionAudit evaluation(int hour, boolean candidate, String disposition) throws Exception {
        return row(hour, Map.of("schema", "SPOT_ENTRY_EVAL_V1", "owner", "DRA_V1",
                "candidate", candidate, "disposition", disposition));
    }
    private BtDecisionAudit row(int hour, Map<String, Object> n) throws Exception {
        var row = new BtDecisionAudit(); row.setStrategyId(BtcDraPolicy.RUNTIME_LEDGER_STRATEGY_ID);
        row.setBarOpenTime(start.plusHours(hour)); row.setContextJson(mapper.writeValueAsString(n)); return row;
    }
}
