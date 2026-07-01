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

    @Test
    void renderMessageUsesOperatorReadableEventTypesAndDetectorText() {
        List<ExecutionEvent> events = List.of(
                event(1, ExecutionEventType.GRID_SELL_FAILED_STALE,
                        "Grid SELL_FAILED level is stale",
                        "Grid #10 level 3 is SELL_FAILED with retry=3/3. Review dust/materiality before any manual sell or grid resize."),
                event(2, ExecutionEventType.GRID_OUT_OF_RANGE,
                        "Grid price is outside configured range",
                        "Grid #10 mark price is below range. Review range/rebalance status before changing grid capital."),
                event(3, ExecutionEventType.GRID_REBALANCE_LIMIT,
                        "Grid auto-rebalance limit reached",
                        "Grid #10 rebalance count is 5/5. Review market regime before allowing additional range changes."),
                event(4, ExecutionEventType.POSITION_TIMEOUT,
                        "Position has exceeded aging review threshold",
                        "Open auto-traded position has been held for 7 days. Review OCO, TP/SL distance, and current risk before any action."),
                event(5, ExecutionEventType.OCO_MISSING,
                        "Open position has no OCO protection",
                        "Auto-traded open position has no OCO order id. Review OCO poller/retryOco status before taking action."),
                event(6, ExecutionEventType.BREAKEVEN_ELIGIBLE,
                        "Breakeven protection is eligible but trailing is disabled",
                        "Position has reached the breakeven trigger. Consider enabling trailing/breakeven automation after preview."),
                event(7, ExecutionEventType.TRAILING_ELIGIBLE,
                        "Trailing protection is eligible",
                        "Position has reached the breakeven trigger. Review risk-reducing OCO preview before any action.")
        );

        String message = service.renderMessage(events);

        assertThat(message)
                .contains("類型=Grid 賣出失敗已陳舊")
                .contains("類型=Grid 價格脫離區間")
                .contains("類型=Grid 再平衡次數接近上限")
                .contains("類型=持倉時間過長")
                .contains("類型=OCO 保護缺失")
                .contains("類型=保本調整候選")
                .contains("類型=移動停利候選")
                .contains("持倉時間超過審查門檻")
                .contains("自動交易持倉已持有 7 天")
                .contains("任何處置前，先檢查 OCO poller/retryOco 狀態")
                .contains("允許再次調整區間前，先審查市場趨勢與區間狀態")
                .contains("僅審查(REVIEW_ONLY)")
                .contains("只讀(READ_ONLY)")
                .contains("未執行任何交易");
        assertThat(message)
                .doesNotContain("類型=GRID_")
                .doesNotContain("類型=POSITION_TIMEOUT")
                .doesNotContain("Grid auto-rebalance limit reached")
                .doesNotContain("Position has exceeded aging review threshold")
                .doesNotContain("Open auto-traded position has been held for")
                .doesNotContain("Auto-traded open position has no OCO order id");
    }

    private static ExecutionEvent event(int index) {
        return event(index, ExecutionEventType.OCO_MISSING,
                "event-title-" + index + " " + "title-detail ".repeat(20),
                "event-summary-" + index + " " + "long execution event detail ".repeat(40));
    }

    private static ExecutionEvent event(int index, ExecutionEventType type, String title, String summary) {
        ExecutionEvent event = new ExecutionEvent();
        event.setSource(ExecutionEventSource.EVENT_SCAN);
        event.setType(type);
        event.setSeverity(index % 3 == 0
                ? ExecutionEventSeverity.CRITICAL
                : ExecutionEventSeverity.ACTIONABLE);
        event.setRecommendation(ExecutionRecommendation.REVIEW_ONLY);
        event.setActionBoundary(ExecutionActionBoundary.READ_ONLY);
        event.setStatus(ExecutionEventStatus.ACTIVE);
        event.setSymbol("BTCUSDT");
        event.setPositionId(10_000L + index);
        event.setTitle(title);
        event.setSummary(summary);
        event.setFingerprint("fingerprint-" + index);
        event.setDetectedAt(LocalDateTime.now().minusMinutes(index));
        return event;
    }
}
