package com.agora.service.trading.execution;

import com.agora.enums.trading.ExecutionActionBoundary;
import com.agora.enums.trading.ExecutionEventSeverity;
import com.agora.enums.trading.ExecutionEventSource;
import com.agora.enums.trading.ExecutionEventStatus;
import com.agora.enums.trading.ExecutionEventType;
import com.agora.enums.trading.ExecutionRecommendation;
import com.agora.model.ExecutionEvent;
import com.agora.repository.trading.ExecutionEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionEventServiceTest {

    @Test
    void listActiveKeepsOnlyLatestEventForSamePositionScope() {
        ExecutionEventRepository repository = mock(ExecutionEventRepository.class);
        ExecutionEventService service = new ExecutionEventService(repository);
        LocalDateTime now = LocalDateTime.now();
        ExecutionEvent latest = event(2L, 150L, 574L, now, "new");
        ExecutionEvent older = event(1L, 150L, 574L, now.minusHours(6), "old");
        ExecutionEvent otherPosition = event(3L, 152L, 574L, now.minusMinutes(5), "other-position");

        when(repository.findActive(
                eq(ExecutionEventStatus.ACTIVE),
                eq("BTCUSDT"),
                isNull(),
                any(LocalDateTime.class),
                any(Pageable.class)))
                .thenReturn(List.of(latest, older, otherPosition));

        List<ExecutionEvent> active = service.listActive("btcusdt", null, 10);

        assertThat(active).extracting(ExecutionEvent::getId)
                .containsExactly(2L, 3L);
    }

    @Test
    void cleanupStaleIncludesClosedPositionResolution() {
        ExecutionEventRepository repository = mock(ExecutionEventRepository.class);
        ExecutionEventService service = new ExecutionEventService(repository);
        LocalDateTime now = LocalDateTime.of(2026, 6, 15, 6, 30);

        when(repository.expireStale(now, ExecutionEventStatus.ACTIVE, ExecutionEventStatus.EXPIRED))
                .thenReturn(2);
        when(repository.resolveClosedPositionEvents(now, ExecutionEventStatus.ACTIVE, ExecutionEventStatus.RESOLVED))
                .thenReturn(3);

        ExecutionEventService.CleanupResult result = service.cleanupStale(now);

        assertThat(result.expired()).isEqualTo(2);
        assertThat(result.resolvedClosedPositions()).isEqualTo(3);
        assertThat(result.expiredSuperseded()).isZero();
        assertThat(result.total()).isEqualTo(5);
    }

    @Test
    void cleanupStaleExpiresSupersededActiveEventsForSamePositionScope() {
        ExecutionEventRepository repository = mock(ExecutionEventRepository.class);
        ExecutionEventService service = new ExecutionEventService(repository);
        LocalDateTime now = LocalDateTime.of(2026, 6, 15, 6, 30);
        ExecutionEvent latest = event(2L, 150L, 574L, now, "new");
        ExecutionEvent older = event(1L, 150L, 574L, now.minusHours(6), "old");
        ExecutionEvent otherPosition = event(3L, 152L, 574L, now.minusMinutes(5), "other-position");

        when(repository.findActive(
                eq(ExecutionEventStatus.ACTIVE),
                isNull(),
                isNull(),
                eq(now),
                any(Pageable.class)))
                .thenReturn(List.of(latest, older, otherPosition));

        ExecutionEventService.CleanupResult result = service.cleanupStale(now);

        assertThat(result.expiredSuperseded()).isEqualTo(1);
        assertThat(result.total()).isEqualTo(1);
        assertThat(older.getStatus()).isEqualTo(ExecutionEventStatus.EXPIRED);
        assertThat(older.getResolvedAt()).isEqualTo(now);
        assertThat(latest.getStatus()).isEqualTo(ExecutionEventStatus.ACTIVE);
        assertThat(otherPosition.getStatus()).isEqualTo(ExecutionEventStatus.ACTIVE);
    }

    private static ExecutionEvent event(Long id, Long positionId, Long strategyId,
                                        LocalDateTime detectedAt, String fingerprint) {
        ExecutionEvent event = new ExecutionEvent();
        event.setId(id);
        event.setSource(ExecutionEventSource.POSITION_TIMEOUT);
        event.setType(ExecutionEventType.POSITION_TIMEOUT);
        event.setSeverity(ExecutionEventSeverity.ACTIONABLE);
        event.setRecommendation(ExecutionRecommendation.REVIEW_ONLY);
        event.setActionBoundary(ExecutionActionBoundary.READ_ONLY);
        event.setStatus(ExecutionEventStatus.ACTIVE);
        event.setSymbol("BTCUSDT");
        event.setPositionId(positionId);
        event.setStrategyId(strategyId);
        event.setIntervalCode("SB_ADD");
        event.setTitle("Position timeout");
        event.setSummary("Review aging position.");
        event.setFingerprint(fingerprint);
        event.setDetectedAt(detectedAt);
        return event;
    }
}
