package com.agora.service.trading;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpotRiskObservationTest {
    @Test void accountBtcIsCountedOnceAcrossBalancesWithoutAddingStrategyLedgers() {
        var report = SpotAccountRiskPolicy.snapshot(List.of(holding("BTC", "0.01", "800"), holding("USDT", "200", "200")),
                List.of(holding("BTC", "0.005", "400")));
        assertEquals(new BigDecimal("1400"), report.get("observedBalanceEquityUsd"));
        assertEquals(new BigDecimal("1200"), report.get("observedBtcExposureUsd"));
        var shocks = (Map<?, ?>) report.get("hypotheticalBtcPriceShocks");
        assertEquals(0, new BigDecimal("-360").compareTo((BigDecimal) shocks.get("btcDown30PercentEquityChangeUsd")));
        assertEquals(false, report.get("wholeAccountRiskComplete"));
        assertEquals("OBSERVE_ONLY", report.get("action"));
    }

    @Test void unavailableUnpricedAndInvalidBalancesCannotLookLikeZeroRisk() {
        for (var report : List.of(SpotAccountRiskPolicy.snapshot(null, List.of()),
                SpotAccountRiskPolicy.snapshot(List.of(holding("BTC", "1", "0")), List.of()),
                SpotAccountRiskPolicy.snapshot(List.of(holding("BTC", "-1", "-80000")), List.of()))) {
            assertTrue(report.get("valuationStatus").toString().startsWith("MISSING_PROOF"));
            assertFalse(report.containsKey("observedBtcExposureUsd"));
            assertFalse(report.containsKey("hypotheticalBtcPriceShocks"));
        }
    }

    @Test void dailyThesisChangeRequiresReviewButNeverGrantsSellAuthority() {
        var observation = observation();
        observation.put("lastSignalConditions", Map.of("ready", true, "dailyDecision", true,
                "closeAboveEma", false, "emaRisingFiveDays", false, "momentumPositive", false));
        var review = BtcDraPositionReviewPolicy.review(observation);
        assertEquals("ENTRY_CONDITIONS_CHANGED_REVIEW_ONLY", review.get("evidenceStatus"));
        assertEquals(false, review.get("riskSellAuthorized"));
        assertEquals("KEEP_EXISTING_RULES", review.get("positionAction"));
        observation.put("marketDataStatus", "STALE");
        assertEquals("REVIEW_STALE_OR_UNMATCHED_EVIDENCE", BtcDraPositionReviewPolicy.review(observation).get("evidenceStatus"));
        observation.put("pendingFeeAttempts", 1);
        assertEquals("REVIEW_ACCOUNTING_PENDING", BtcDraPositionReviewPolicy.review(observation).get("evidenceStatus"));
    }

    @Test void missingOrIntradayEvidenceIsNotHighConfidence() {
        var observation = observation();
        assertEquals("MISSING_PROOF_SIGNAL_NOT_READY", BtcDraPositionReviewPolicy.review(observation).get("evidenceStatus"));
        observation.put("lastSignalConditions", Map.of("ready", true, "dailyDecision", false));
        assertEquals("WAIT_FOR_CONFIRMED_DAILY_REVIEW", BtcDraPositionReviewPolicy.review(observation).get("evidenceStatus"));
    }

    private Map<String, Object> observation() {
        return new HashMap<>(Map.of("openOwnedLots", 1, "pendingFeeAttempts", 0,
                "openCostEvidenceStatus", "RECORDED_COST_NOT_FEE_EXACT", "marketDataStatus", "CURRENT",
                "decisionMatchesLatestClosedBar", true));
    }
    private OkxTradingService.SpotHolding holding(String currency, String qty, String value) {
        return new OkxTradingService.SpotHolding(currency, new BigDecimal(qty), new BigDecimal(qty), new BigDecimal(value));
    }
}
