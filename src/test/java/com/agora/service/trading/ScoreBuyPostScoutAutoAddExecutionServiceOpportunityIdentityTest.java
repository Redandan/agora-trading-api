package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScoreBuyPostScoutAutoAddExecutionServiceOpportunityIdentityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScoreBuyPostScoutAutoAddExecutionService service =
            new ScoreBuyPostScoutAutoAddExecutionService(
                    null, null, null, null, null, null, null,
                    objectMapper, null, null, null);

    @Test
    void priceDriftDoesNotCreateDistinctOpportunityIdentity() {
        ObjectNode original = preview("76898", "78000", "75200");

        assertThat(opportunityKey(preview("76951", "78000", "75200")))
                .as("entry-price bucket drift")
                .isEqualTo(opportunityKey(original));
        assertThat(opportunityKey(preview("76898", "78051", "75200")))
                .as("take-profit-price bucket drift")
                .isEqualTo(opportunityKey(original));
        assertThat(opportunityKey(preview("76898", "78000", "75251")))
                .as("stop-loss-price bucket drift")
                .isEqualTo(opportunityKey(original));
    }

    @Test
    void stableDecisionDimensionsRemainDistinct() {
        ObjectNode baseline = preview("76898", "78000", "75200");
        String baselineKey = opportunityKey(baseline);

        ObjectNode nextDecisionBar = baseline.deepCopy();
        nextDecisionBar.withObject("/observerSummary")
                .put("intradayReversalDecisionBarOpenTime", "2026-07-16T10:30:00Z");
        assertThat(opportunityKey(nextDecisionBar)).isNotEqualTo(baselineKey);

        assertThat(service.opportunityKey(baseline, 486L, "LONG", "STANDARD"))
                .as("strategy")
                .isNotEqualTo(baselineKey);
        assertThat(service.opportunityKey(baseline, 485L, "SHORT", "STANDARD"))
                .as("side")
                .isNotEqualTo(baselineKey);

        ObjectNode differentState = baseline.deepCopy();
        differentState.put("postScoutManagementState", "ADD_ON_PARTIAL_REVERSAL_READY");
        assertThat(opportunityKey(differentState)).as("management state").isNotEqualTo(baselineKey);

        ObjectNode differentInvalidation = baseline.deepCopy();
        differentInvalidation.put("formationInvalidationReason", "STRUCTURE_BREAK");
        assertThat(opportunityKey(differentInvalidation)).as("invalidation").isNotEqualTo(baselineKey);

        assertThat(service.opportunityKey(baseline, 485L, "LONG", "MISSED_ALPHA_MICRO"))
                .as("execution slot")
                .isNotEqualTo(baselineKey);

        ObjectNode differentAddOnType = baseline.deepCopy();
        differentAddOnType.put("addOnType", "CONFIRMATION");
        assertThat(opportunityKey(differentAddOnType)).as("add-on type").isNotEqualTo(baselineKey);

        ObjectNode differentEventRisk = baseline.deepCopy();
        differentEventRisk.put("eventRiskLevel", "R2");
        assertThat(opportunityKey(differentEventRisk)).as("event risk level").isNotEqualTo(baselineKey);
    }

    @Test
    void legacyPriceBucketIdentityCanonicalizesToStableIdentity() {
        String legacyFilterReason = "SCORE_BUY_POST_SCOUT_ADD_PULLBACK"
                + "|SLOT:STANDARD"
                + "|OPP:BTCUSDT|485|LONG|ADD_ON_PULLBACK_READY|PULLBACK"
                + "|bar=2026-07-16T10:15:00Z|entry=76850|tp=78000|sl=75200|risk=LOW|inv=NONE";

        assertThat(service.opportunityKeyFromFilterReason(legacyFilterReason))
                .isEqualTo(opportunityKey(preview("76951", "78051", "75251")));
    }

    @Test
    void matchingSuccessfulIdentityIsBlockedEvenWhenANewerDistinctExecutionExists() throws Exception {
        BtLiveSignalRepository repository = mock(BtLiveSignalRepository.class);
        Environment environment = mock(Environment.class);
        when(environment.getProperty(
                "trading.score-buy.post-scout-add.execution.opportunity-cooldown-minutes", "30"))
                .thenReturn("30");
        ScoreBuyPostScoutAutoAddExecutionService dedupService =
                new ScoreBuyPostScoutAutoAddExecutionService(
                        null, null, null, repository, null, null, null,
                        objectMapper, environment, null, null);
        BtLiveSignal newerDistinct = successfulSignal(
                "BTCUSDT|485|LONG|ADD_ON_PULLBACK_READY|PULLBACK"
                        + "|bar=2026-07-16T10:30:00Z|risk=LOW|inv=NONE|slot=STANDARD",
                LocalDateTime.of(2026, 7, 16, 10, 31));
        BtLiveSignal earlierMatchingLegacy = successfulSignal(
                "BTCUSDT|485|LONG|ADD_ON_PULLBACK_READY|PULLBACK"
                        + "|bar=2026-07-16T10:15:00Z|entry=76850|tp=78000|sl=75200|risk=LOW|inv=NONE",
                LocalDateTime.of(2026, 7, 16, 10, 16));
        when(repository.findRecentScoreBuyPostScoutAddTradesSince(
                eq(485L), eq("BTCUSDT"), any(), any()))
                .thenReturn(List.of(newerDistinct, earlierMatchingLegacy));

        Object dedup = evaluateDedup(dedupService, preview("76951", "78051", "75251"));
        Method distinct = dedup.getClass().getDeclaredMethod("distinct");
        distinct.setAccessible(true);
        Method reason = dedup.getClass().getDeclaredMethod("reason");
        reason.setAccessible(true);

        assertThat(distinct.invoke(dedup)).isEqualTo(false);
        assertThat(reason.invoke(dedup)).isEqualTo("SAME_POST_SCOUT_OPPORTUNITY_WITHIN_COOLDOWN");
    }

    @Test
    void malformedLegacyIdentityFailsClosedWithoutCanonicalizingToCurrentIdentity() throws Exception {
        BtLiveSignalRepository repository = mock(BtLiveSignalRepository.class);
        ScoreBuyPostScoutAutoAddExecutionService dedupService = dedupService(repository);
        String malformed = "BTCUSDT|485|LONG|ADD_ON_PULLBACK_READY|PULLBACK"
                + "|bar=2026-07-16T10:15:00Z|risk=LOW|risk=LOW|inv=NONE";
        BtLiveSignal row = successfulSignal(malformed, LocalDateTime.of(2026, 7, 16, 10, 16));
        when(repository.findRecentScoreBuyPostScoutAddTradesSince(
                eq(485L), eq("BTCUSDT"), any(), any())).thenReturn(List.of(row));

        assertThat(dedupService.opportunityKeyFromFilterReason(row.getFilterReason()))
                .as("malformed legacy keys must never canonicalize into a stable identity")
                .isBlank();

        Object dedup = evaluateDedup(dedupService, preview("76951", "78051", "75251"));
        assertThat(recordValue(dedup, "distinct")).isEqualTo(false);
        assertThat(recordValue(dedup, "reason"))
                .isEqualTo("MALFORMED_POST_SCOUT_OPPORTUNITY_KEY_FAIL_CLOSED");
    }

    @Test
    void boundedHistoryScanFailsClosedWhenResultIsTruncated() throws Exception {
        BtLiveSignalRepository repository = mock(BtLiveSignalRepository.class);
        ScoreBuyPostScoutAutoAddExecutionService dedupService = dedupService(repository);
        List<BtLiveSignal> rows = IntStream.range(0, 101)
                .mapToObj(i -> successfulSignal(
                        "BTCUSDT|485|LONG|ADD_ON_PULLBACK_READY|PULLBACK"
                                + "|bar=2026-07-16T09:" + String.format("%02d", i % 60)
                                + ":00Z|risk=LOW|inv=NONE",
                        LocalDateTime.of(2026, 7, 16, 10, 30).minusSeconds(i)))
                .toList();
        when(repository.findRecentScoreBuyPostScoutAddTradesSince(
                eq(485L), eq("BTCUSDT"), any(), any())).thenReturn(rows);

        Object dedup = evaluateDedup(dedupService, preview("76951", "78051", "75251"));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findRecentScoreBuyPostScoutAddTradesSince(
                eq(485L), eq("BTCUSDT"), any(), pageable.capture());
        assertThat(pageable.getValue().isPaged()).isTrue();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(101);
        assertThat(recordValue(dedup, "distinct")).isEqualTo(false);
        assertThat(recordValue(dedup, "reason"))
                .isEqualTo("POST_SCOUT_OPPORTUNITY_HISTORY_TRUNCATED_FAIL_CLOSED");
    }

    private ObjectNode preview(String entry, String tp, String sl) {
        ObjectNode preview = objectMapper.createObjectNode();
        preview.put("symbol", "BTCUSDT");
        preview.put("postScoutManagementState", "ADD_ON_PULLBACK_READY");
        preview.put("addOnType", "PULLBACK");
        preview.put("entry", entry);
        preview.put("tp", tp);
        preview.put("sl", sl);
        preview.put("eventRiskLevel", "LOW");
        preview.put("formationInvalidationReason", "NONE");
        preview.putObject("observerSummary")
                .put("intradayReversalDecisionBarOpenTime", "2026-07-16T10:15:00Z");
        return preview;
    }

    private String opportunityKey(ObjectNode preview) {
        return service.opportunityKey(preview, 485L, "LONG", "STANDARD");
    }

    private ScoreBuyPostScoutAutoAddExecutionService dedupService(BtLiveSignalRepository repository) {
        Environment environment = mock(Environment.class);
        when(environment.getProperty(
                "trading.score-buy.post-scout-add.execution.opportunity-cooldown-minutes", "30"))
                .thenReturn("30");
        return new ScoreBuyPostScoutAutoAddExecutionService(
                null, null, null, repository, null, null, null,
                objectMapper, environment, null, null);
    }

    private Object evaluateDedup(ScoreBuyPostScoutAutoAddExecutionService target, ObjectNode preview)
            throws Exception {
        Method method = ScoreBuyPostScoutAutoAddExecutionService.class.getDeclaredMethod(
                "evaluateOpportunityDedup", com.fasterxml.jackson.databind.JsonNode.class,
                long.class, String.class, String.class);
        method.setAccessible(true);
        return method.invoke(target, preview, 485L, "BTCUSDT", "STANDARD");
    }

    private Object recordValue(Object record, String accessor) throws Exception {
        Method method = record.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(record);
    }

    private BtLiveSignal successfulSignal(String opportunityKey, LocalDateTime createdAt) {
        BtLiveSignal signal = new BtLiveSignal();
        signal.setAutoTraded(true);
        signal.setCreatedAt(createdAt);
        signal.setFilterReason("SCORE_BUY_POST_SCOUT_ADD_PULLBACK|SLOT:STANDARD|OPP:" + opportunityKey);
        return signal;
    }
}
