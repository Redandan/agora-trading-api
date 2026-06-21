package com.agora.service.trading;

import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExplorationPolicyServiceTest {

    private final TinyLiveMinimumOrderPreviewService previewService = mock(TinyLiveMinimumOrderPreviewService.class);
    private final RuntimeDecisionEvidenceService evidenceService = mock(RuntimeDecisionEvidenceService.class);
    private final BtLiveSignalRepository liveSignalRepository = mock(BtLiveSignalRepository.class);
    private final AutoExplorationRolloutStateService rolloutStateService = mock(AutoExplorationRolloutStateService.class);

    @Test
    void missingBuyCandidateDoesNotBecomeTerminalOcoBlocker() {
        when(previewService.preview(anyString(), any(Long.class), anyString()))
                .thenReturn(previewWithoutBuyCandidate());
        when(evidenceService.listRecent(anyString(), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of(runtimeEvidence()));
        when(liveSignalRepository.findClosedTinyLiveSince(any(), anyString(), any())).thenReturn(List.of());
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()).thenReturn(List.of());
        when(rolloutStateService.effectiveMaxOrdersPerDay(anyString(), any(Long.class), anyString())).thenReturn(1L);

        ExplorationPolicyService service = new ExplorationPolicyService(previewService, evidenceService,
                liveSignalRepository, rolloutStateService, new ObjectMapper(), new MockEnvironment());

        ExplorationPolicyService.Decision decision = service.evaluate("BTCUSDT", 574L, "LONG");

        assertThat(decision.blockers()).contains("NO_CURRENT_BUY_CANDIDATE");
        assertThat(decision.blockers()).doesNotContain("OCO_PREFLIGHT_FAIL");
        assertThat(decision.warnings()).contains("ocoPreflightPendingUntilBuyCandidate=NOT_READY_MISSING_ENTRY_TP_SL");
    }

    private static RuntimeDecisionEvidence runtimeEvidence() {
        RuntimeDecisionEvidence row = new RuntimeDecisionEvidence();
        row.setStrategyId(574L);
        row.setSymbol("BTCUSDT");
        row.setTqsResultJson("{\"tqsBand\":\"PROBE_DRY_RUN\",\"qualityScore\":80}");
        row.setFreshnessState("OK");
        row.setDecisionId(123L);
        return row;
    }

    private static TinyLiveMinimumOrderPreviewService.PreviewResult previewWithoutBuyCandidate() {
        return new TinyLiveMinimumOrderPreviewService.PreviewResult(
                "BTCUSDT",
                574L,
                "LONG",
                "TINY_LIVE_MANUAL_APPROVAL_PREVIEW",
                "NOT_READY_NO_CURRENT_BUY_CANDIDATE",
                false,
                List.of("NO_CURRENT_BUY_CANDIDATE", "OCO_PREFLIGHT_FAILED"),
                List.of(),
                "HOLD",
                "RUNTIME_DECISION_EVIDENCE",
                "HOLD; holdReason=no_threshold_hit",
                1L,
                2L,
                "2026-06-21T00:00:00",
                "1h",
                1L,
                "LATEST_SIGNAL_HOLD",
                new BigDecimal("5"),
                new BigDecimal("5.00"),
                new BigDecimal("0.00008"),
                new BigDecimal("0.00001"),
                new BigDecimal("0.00000001"),
                new BigDecimal("0.1"),
                new BigDecimal("64000"),
                null,
                null,
                "NO_DUPLICATE_BAR",
                "NO_OPEN_CANDIDATE",
                "DISTINCT_OPPORTUNITY",
                "NO_RECENT_DUPLICATE_BAR",
                "current-key",
                "last-key",
                true,
                "PASS_READY",
                "R0 score=10",
                "NOT_READY_MISSING_ENTRY_TP_SL",
                "AVAILABLE_CANONICAL_ROWS",
                new BigDecimal("100"),
                0,
                0,
                0,
                BigDecimal.ZERO,
                0,
                1,
                "preview-id",
                "preview-token",
                "preview-hash",
                Instant.now().plusSeconds(60));
    }
}
