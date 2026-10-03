package com.agora.service.strategy;

import com.agora.config.properties.BtcDraRuntimeProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.model.*;
import com.agora.repository.trading.*;
import com.agora.service.trading.BtcBasePositionStatePolicy;
import com.agora.service.trading.BtcDraPolicy;
import com.agora.service.tradingview.TradingViewScoreBuyAutoExitStrategyContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.query.parser.PartTree;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StrategyRuntimeObservationServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-02T11:10:00");

    @Test
    void freshnessUsesClosedSourceBarsAndAllowsOnlyTwoMinuteArrivalGrace() {
        assertEquals("CURRENT", StrategyRuntimeObservationService.freshness(NOW.withMinute(0), "1h", NOW));
        assertEquals("STALE", StrategyRuntimeObservationService.freshness(NOW.withMinute(0).minusHours(1), "1h", NOW));
        assertEquals("CURRENT", StrategyRuntimeObservationService.freshness(NOW.withMinute(0).minusHours(1), "1h", NOW.withMinute(1)));
        assertEquals("INVALID_FUTURE_BAR", StrategyRuntimeObservationService.freshness(NOW.plusHours(1), "1h", NOW));
        assertEquals("MISSING_PROOF", StrategyRuntimeObservationService.freshness(null, "1h", NOW));
        assertEquals("CURRENT", StrategyRuntimeObservationService.freshness(NOW.toLocalDate().atStartOfDay(), "1d", NOW));
        assertEquals("CURRENT", StrategyRuntimeObservationService.freshness(
                NOW.toLocalDate().atStartOfDay().minusNanos(1_000_000), "1d", NOW));
        assertEquals("STALE", StrategyRuntimeObservationService.freshness(
                NOW.toLocalDate().minusDays(1).atStartOfDay().minusNanos(1_000_000), "1d", NOW));
    }

    @Test
    void exposesPendingFeeEvenWithoutAnOpenLotAndExcludesLegacyInventory() {
        var f = new Fixture();
        var legacy = new BtLiveSignal();
        legacy.setSymbol("BTCUSDT");
        legacy.setFilterReason(BtcBasePositionStatePolicy.ADOPTED_FROM_OCO_PREFIX + "1");
        legacy.setTradedQty(BigDecimal.ONE);
        legacy.setEntryPrice(new BigDecimal("100"));
        f.lots = List.of(legacy);
        f.pending = new SpotExecutionAttempt();
        f.pending.setId(1L);
        f.pending.setCreatedAt(NOW.minusDays(44));
        Map<String, Object> out = f.service().snapshot(f.catalog.require(BtcDraPolicy.POLICY_MODE), NOW);
        assertEquals("AVAILABLE", out.get("observationStatus"));
        assertEquals(0, out.get("openOwnedLots"));
        assertEquals(BigDecimal.ZERO, out.get("recordedOpenCostUsdt"));
        assertEquals(1L, out.get("pendingFeeAttempts"));
        assertEquals(1L, out.get("oldestPendingFeeAttemptId"));
        assertEquals("PENDING", out.get("lastDecisionFeeStatus"));
        assertEquals(true, out.get("decisionMatchesLatestClosedBar"));
    }

    @Test
    void staleDecisionAndForeignSourceCannotAppearAsCurrentDecisionEvidence() {
        var f = new Fixture();
        f.features = "{\"source\":\"okx\",\"barOpenTime\":\"2026-10-01T10:00:00\"}";
        assertEquals(false, f.service().snapshot(f.catalog.require(BtcDraPolicy.POLICY_MODE), NOW)
                .get("decisionMatchesLatestClosedBar"));
        f.features = "{\"source\":\"binance\",\"barOpenTime\":\"2026-10-02T10:00:00\"}";
        var out = f.service().snapshot(f.catalog.require(BtcDraPolicy.POLICY_MODE), NOW);
        assertEquals("MISSING_PROOF_SOURCE_MISMATCH", out.get("decisionEvidenceStatus"));
        assertEquals(false, out.get("decisionMatchesLatestClosedBar"));
    }

    @Test
    void malformedEvidenceAndDatabaseFailureAreNotHealthyEmptyInventory() {
        var f = new Fixture();
        f.features = "broken-json";
        assertEquals("UNAVAILABLE", f.service().snapshot(f.catalog.require(BtcDraPolicy.POLICY_MODE), NOW)
                .get("observationStatus"));
        f.failDatabase = true;
        String report = f.service().report();
        assertTrue(report.contains("UNAVAILABLE"));
        assertFalse(report.contains("openOwnedLots"));
    }

    @Test
    void owner509ShowsPersistedNoBuyDecisionWithoutInventingAStalledStrategy() {
        var f = new Fixture();
        f.bar.setOpenTime(NOW.toLocalDate().minusDays(1).atStartOfDay());
        f.bar.setCloseTime(NOW.toLocalDate().atStartOfDay());
        f.audit = new BtDecisionAudit();
        f.audit.setId(81217L);
        f.audit.setBarOpenTime(f.bar.getOpenTime());
        f.audit.setContextJson("{\"source\":\"LOCAL_TRADINGVIEW_PARITY\",\"decision\":\"LOCAL_TRADINGVIEW_NO_BUY\",\"blockers\":\"NO_CURRENT_BUY_CANDIDATE\"}");
        var out = f.service().snapshot(f.catalog.require(TradingViewScoreBuyAutoExitStrategyContract.KEY), NOW);
        assertEquals("LOCAL_TRADINGVIEW_NO_BUY", out.get("lastDecision"));
        assertEquals(true, out.get("decisionMatchesLatestClosedBar"));
        assertEquals("MISSING_PROOF_NO_PROVIDER_ATTEMPT_LEDGER", out.get("pendingFeeAttempts"));
    }

    @Test
    void newReadQueriesResolveAgainstMappedEntityProperties() {
        new PartTree("findFirstBySymbolAndIntervalCodeAndSourceAndCloseTimeLessThanEqualOrderByOpenTimeDesc", MdKline.class);
        new PartTree("findFirstByStrategyIdAndSymbolAndEventTypeOrderByEventTimeDescIdDesc", BtDecisionAudit.class);
        new PartTree("findFirstByStrategyContractAndFeeReconciliationStatusOrderByCreatedAtAscIdAsc", SpotExecutionAttempt.class);
        new PartTree("countByStrategyContractAndFeeReconciliationStatus", SpotExecutionAttempt.class);
    }

    @Test
    void entryDecisionRequiresMatchingProfileAndBarInsteadOfGuessingLegacyCooldown() {
        var f = new Fixture();
        var def = f.catalog.require(BtcDraPolicy.POLICY_MODE);
        assertEquals("MISSING_PROOF_LEGACY_OR_INVALID_ENTRY_DECISION", f.service().snapshot(def, NOW).get("entryDecisionStatus"));
        f.features = """
                {"source":"okx","barOpenTime":"2026-10-02T10:00:00",
                 "entryDecision":{"schema":"DRA_ENTRY_DECISION_V1","profile":"DRA_V1_VIRTUAL250_SINGLE30_CURRENT_QUOTE",
                 "barOpenUtc":"2026-10-02T10:00:00","queuedCandidate":false,"stage":"VIRTUAL_SIGNAL_COOLDOWN"}}
                """;
        assertTrue(f.service().snapshot(def, NOW).containsKey("entryDecision"));
        f.features = f.features.replace("\"barOpenUtc\":\"2026-10-02T10:00:00\"", "\"barOpenUtc\":\"2026-10-02T09:00:00\"");
        assertFalse(f.service().snapshot(def, NOW).containsKey("entryDecision"));
    }

    @Test
    void liveDispositionMustBelongToTheSameEvaluatedBar() {
        var f = new Fixture();
        var def = f.catalog.require(BtcDraPolicy.POLICY_MODE);
        f.audit = new BtDecisionAudit();
        f.audit.setBarOpenTime(f.bar.getOpenTime());
        f.audit.setContextJson("{\"schema\":\"SPOT_ENTRY_EVAL_V1\",\"owner\":\"DRA_V1\",\"candidate\":true,\"disposition\":\"BLOCKED:DRA_SINGLE_LOT_ALREADY_OPEN\"}");
        assertTrue(f.service().snapshot(def, NOW).containsKey("lastLiveEntryEvaluation"));
        f.audit.setBarOpenTime(f.bar.getOpenTime().minusHours(1));
        assertFalse(f.service().snapshot(def, NOW).containsKey("lastLiveEntryEvaluation"));
    }

    private static class Fixture {
        final StrategyRuntimeCatalog catalog = new StrategyRuntimeCatalog();
        final MdKline bar = new MdKline();
        List<BtLiveSignal> lots = List.of();
        BtDecisionAudit audit;
        SpotExecutionAttempt pending;
        boolean failDatabase;
        String features = "{\"source\":\"okx\",\"barOpenTime\":\"2026-10-02T10:00:00\",\"liveExecution\":{\"feeStatus\":\"PENDING\"}}";

        Fixture() {
            bar.setOpenTime(NOW.withMinute(0).minusHours(1));
            bar.setCloseTime(NOW.withMinute(0));
        }

        StrategyRuntimeObservationService service() {
            return new StrategyRuntimeObservationService(catalog,
                    proxy(MdKlineRepository.class, (method, args) -> {
                        if (failDatabase) throw new IllegalStateException("database unavailable");
                        if (method.startsWith("findFirstBySymbol")) return Optional.of(bar);
                        throw new AssertionError(method);
                    }),
                    proxy(RuntimeDecisionEvidenceRepository.class, (method, args) -> {
                        if (!method.startsWith("findByPolicyModeAndSymbol")) throw new AssertionError(method);
                        var row = new RuntimeDecisionEvidence();
                        row.setFeaturesSnapshotJson(features);
                        row.setId(32012L);
                        return List.of(row);
                    }),
                    proxy(BtDecisionAuditRepository.class, (method, args) -> {
                        if (method.startsWith("findFirstByStrategyId")) return Optional.ofNullable(audit);
                        throw new AssertionError(method);
                    }),
                    proxy(BtLiveSignalRepository.class, (method, args) -> {
                        if (method.equals("findByAutoTradedIsTrueAndExitTimeIsNull")) return lots;
                        throw new AssertionError(method);
                    }),
                    proxy(SpotExecutionAttemptRepository.class, (method, args) -> {
                        if (method.startsWith("countByStrategyContract")) return pending == null ? 0L : 1L;
                        if (method.startsWith("findFirstByStrategyContract")) return Optional.ofNullable(pending);
                        throw new AssertionError(method);
                    }),
                    new TradingViewLocalSignalProperties(true, 485, "BTCUSDT", "1d", "binance", 320, 3, 72,
                            BigDecimal.TEN, new BigDecimal("80"), TradingViewLocalSignalProperties.ExecutionMode.BTC_BASE_LIVE,
                            new BigDecimal("250"), 15),
                    new BtcDraRuntimeProperties(BtcDraRuntimeProperties.Mode.LIVE, new BigDecimal("30"), new BigDecimal("30"), 15),
                    new ObjectMapper());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> invocation.invoke(method.getName(), args));
    }

    @FunctionalInterface
    private interface Invocation { Object invoke(String method, Object[] args); }
}
