package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.TinyLiveExecutionAuditRepository;
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

class AutoApprovalPolicyServiceTest {

    private final TinyLiveMinimumOrderPreviewService previewService = mock(TinyLiveMinimumOrderPreviewService.class);
    private final TinyLiveExecutionAuditRepository executionAuditRepository = mock(TinyLiveExecutionAuditRepository.class);
    private final BtLiveSignalRepository liveSignalRepository = mock(BtLiveSignalRepository.class);
    private final AutoExplorationRolloutStateService rolloutStateService = mock(AutoExplorationRolloutStateService.class);
    private final MockEnvironment env = new MockEnvironment();

    @Test
    void doesNotTreatMissingBuyCandidateAsTerminalOcoFailure() {
        AutoApprovalPolicyService service = service();

        AutoApprovalPolicyService.Decision decision = service.decide(preview(
                "NOT_READY_NO_CURRENT_BUY_CANDIDATE",
                false,
                List.of("NO_CURRENT_BUY_CANDIDATE", "OCO_PREFLIGHT_FAILED"),
                "NOT_READY_MISSING_ENTRY_TP_SL"
        ));

        assertThat(decision.blockers()).contains("NO_CURRENT_BUY_CANDIDATE");
        assertThat(decision.blockers()).doesNotContain("OCO_PREFLIGHT_FAIL");
        assertThat(decision.warnings()).contains("ocoPreflightPendingUntilBuyCandidate=NOT_READY_MISSING_ENTRY_TP_SL");
    }

    @Test
    void keepsConsecutiveLossHardStopByDefault() {
        AutoApprovalPolicyService service = service();
        when(liveSignalRepository.findClosedTinyLiveSince(any(), anyString(), any()))
                .thenReturn(List.of(loss("-0.10"), loss("-0.20")));

        AutoApprovalPolicyService.Decision decision = service.decide(readyPreview());

        assertThat(decision.blockers()).contains("AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES");
        assertThat(decision.approvalInputs()).contains("\"ignoreConsecutiveLossHardStop\":false");
        assertThat(decision.approvalInputs()).contains("\"consecutiveTinyLiveLosses\":2");
    }

    @Test
    void explicitEnvCanOverrideConsecutiveLossHardStopOnly() {
        env.setProperty("trading.tiny-live.auto-approval.ignore-consecutive-loss-hard-stop", "true");
        AutoApprovalPolicyService service = service();
        when(liveSignalRepository.findClosedTinyLiveSince(any(), anyString(), any()))
                .thenReturn(List.of(loss("-0.10"), loss("-0.20")));

        AutoApprovalPolicyService.Decision decision = service.decide(readyPreview());

        assertThat(decision.blockers()).doesNotContain("AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES");
        assertThat(decision.warnings()).contains("consecutiveTinyLiveLossHardStopOverride=true");
        assertThat(decision.approvalInputs()).contains("\"ignoreConsecutiveLossHardStop\":true");
    }

    private AutoApprovalPolicyService service() {
        when(executionAuditRepository.existsByStatusAndCreatedAtAfter(anyString(), any())).thenReturn(false);
        when(executionAuditRepository.countByEventRiskOverrideUsedIsTrueAndCreatedAtAfter(any())).thenReturn(0L);
        when(liveSignalRepository.findClosedTinyLiveSince(any(), anyString(), any())).thenReturn(List.of());
        when(rolloutStateService.effectiveMaxOrdersPerDay(anyString(), any(Long.class), anyString())).thenReturn(1L);
        return new AutoApprovalPolicyService(previewService, executionAuditRepository, liveSignalRepository,
                rolloutStateService, new ObjectMapper(), env);
    }

    private static BtLiveSignal loss(String pnl) {
        BtLiveSignal row = new BtLiveSignal();
        row.setRealizedPnl(new BigDecimal(pnl));
        return row;
    }

    private static TinyLiveMinimumOrderPreviewService.PreviewResult readyPreview() {
        return preview("READY_FOR_MANUAL_APPROVAL", true, List.of(), "PASS_READY");
    }

    private static TinyLiveMinimumOrderPreviewService.PreviewResult preview(String status,
                                                                           boolean allowed,
                                                                           List<String> denialReasons,
                                                                           String ocoPreflightStatus) {
        return new TinyLiveMinimumOrderPreviewService.PreviewResult(
                "BTCUSDT",
                574L,
                "LONG",
                "TINY_LIVE_MANUAL_APPROVAL_PREVIEW",
                status,
                allowed,
                denialReasons,
                List.of(),
                allowed ? "BUY" : "HOLD",
                "RUNTIME_DECISION_EVIDENCE",
                allowed ? "BUY" : "HOLD; holdReason=no_threshold_hit",
                1L,
                2L,
                "2026-06-21T00:00:00",
                "1h",
                1L,
                allowed ? "N/A" : "LATEST_SIGNAL_HOLD",
                new BigDecimal("5"),
                new BigDecimal("5.00"),
                new BigDecimal("0.00008"),
                new BigDecimal("0.00001"),
                new BigDecimal("0.00000001"),
                new BigDecimal("0.1"),
                new BigDecimal("64000"),
                new BigDecimal("65280"),
                new BigDecimal("63360"),
                "NO_DUPLICATE_BAR",
                "NO_OPEN_CANDIDATE",
                "DISTINCT_OPPORTUNITY",
                "NO_RECENT_DUPLICATE_BAR",
                "current-key",
                "last-key",
                true,
                "PASS_READY",
                "R0 score=10",
                ocoPreflightStatus,
                "AVAILABLE_CANONICAL_SHADOW_EVIDENCE",
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
