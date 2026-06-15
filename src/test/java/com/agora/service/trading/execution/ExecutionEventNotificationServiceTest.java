package com.agora.service.trading.execution;

import com.agora.enums.trading.ExecutionActionBoundary;
import com.agora.enums.trading.ExecutionEventSeverity;
import com.agora.enums.trading.ExecutionEventSource;
import com.agora.enums.trading.ExecutionEventStatus;
import com.agora.enums.trading.ExecutionEventType;
import com.agora.enums.trading.ExecutionRecommendation;
import com.agora.model.ExecutionEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionEventNotificationServiceTest {

    private final ExecutionEventNotificationService service =
            new ExecutionEventNotificationService(null, null, null, null);

    @Test
    void renderMessageKeepsLargeActiveEventSetUnderTelegramLimit() {
        List<ExecutionEvent> events = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            events.add(event(i));
        }

        String message = service.renderMessage(events);

        assertThat(message.length()).isLessThanOrEqualTo(3800);
        assertThat(message).contains("已省略");
        assertThat(message).contains("未執行任何交易");
        assertThat(message).doesNotContain("event-title-79");
    }

    private static ExecutionEvent event(int index) {
        ExecutionEvent event = new ExecutionEvent();
        event.setSource(ExecutionEventSource.EVENT_SCAN);
        event.setType(ExecutionEventType.OCO_MISSING);
        event.setSeverity(index % 3 == 0
                ? ExecutionEventSeverity.CRITICAL
                : ExecutionEventSeverity.ACTIONABLE);
        event.setRecommendation(ExecutionRecommendation.REVIEW_ONLY);
        event.setActionBoundary(ExecutionActionBoundary.READ_ONLY);
        event.setStatus(ExecutionEventStatus.ACTIVE);
        event.setSymbol("BTCUSDT");
        event.setPositionId(10_000L + index);
        event.setTitle("event-title-" + index + " " + "title-detail ".repeat(20));
        event.setSummary("event-summary-" + index + " " + "long execution event detail ".repeat(40));
        event.setFingerprint("fingerprint-" + index);
        event.setDetectedAt(LocalDateTime.now().minusMinutes(index));
        return event;
    }
}
