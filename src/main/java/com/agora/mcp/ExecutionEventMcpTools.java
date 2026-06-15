package com.agora.mcp;

import com.agora.enums.trading.ExecutionEventStatus;
import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.model.ExecutionEvent;
import com.agora.service.trading.execution.ExecutionEventDetector;
import com.agora.service.trading.execution.ExecutionEventNotificationService;
import com.agora.service.trading.execution.ExecutionEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ExecutionEventMcpTools {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final List<ExecutionEventDetector> detectors;
    private final ExecutionEventService eventService;
    private final ExecutionEventNotificationService notificationService;

    @Tool(description = "Scan read-only execution-manager events and optionally persist them into bt_execution_event. " +
            "No trading, OCO, strategy, grid, or fund behavior is changed. param: dryRun=true only previews.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    public String scanExecutionEvents(Boolean dryRun) {
        boolean previewOnly = Boolean.TRUE.equals(dryRun);
        LocalDateTime now = LocalDateTime.now();
        List<ExecutionEventService.Draft> drafts = new ArrayList<>();
        for (ExecutionEventDetector detector : detectors) {
            drafts.addAll(detector.detect(now));
        }

        if (previewOnly) {
            return renderDrafts(drafts, true);
        }

        ExecutionEventService.CleanupResult cleanup = eventService.cleanupStale(LocalDateTime.now());
        int saved = 0;
        for (ExecutionEventService.Draft draft : drafts) {
            eventService.upsert(draft);
            saved++;
        }
        return renderDrafts(drafts, false) + "\n\nsaved=" + saved
                + "\nexpired=" + cleanup.expired()
                + "\nresolvedClosedPositions=" + cleanup.resolvedClosedPositions()
                + "\nNo trading, OCO, strategy, grid order, or fund behavior was changed.";
    }

    @Tool(description = "List active execution-manager events from bt_execution_event. Read-only. " +
            "param: symbol optional, positionId optional, limit default 20.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    public String listExecutionEvents(String symbol, Long positionId, Integer limit) {
        int max = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        List<ExecutionEvent> events = eventService.listActive(symbol, positionId, max);
        StringBuilder sb = new StringBuilder();
        sb.append("=== Execution Events ===\n")
                .append("boundary: READ_ONLY report; no trading/OCO/strategy/grid/fund behavior changed.\n")
                .append("filters: symbol=").append(blank(symbol) ? "ALL" : symbol.trim().toUpperCase())
                .append(" positionId=").append(positionId == null ? "ALL" : positionId)
                .append(" limit=").append(max).append("\n\n");
        if (events.isEmpty()) {
            sb.append("No active execution events.\n");
            return sb.toString();
        }
        int i = 1;
        for (ExecutionEvent e : events) {
            sb.append(i++).append(". #").append(e.getId())
                    .append(" ").append(formatTime(e.getDetectedAt()))
                    .append(" ").append(e.getSymbol());
            if (e.getPositionId() != null) sb.append(" pos#").append(e.getPositionId());
            sb.append(" ").append(e.getType())
                    .append(" ").append(e.getSeverity())
                    .append(" recommendation=").append(e.getRecommendation())
                    .append(" boundary=").append(e.getActionBoundary())
                    .append("\n   ").append(e.getTitle())
                    .append("\n   ").append(e.getSummary())
                    .append("\n   fingerprint=").append(e.getFingerprint())
                    .append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "Expire stale execution-manager events whose expiresAt has passed. " +
            "Read-only with respect to trading/OCO/strategy/grid/funds; only event status is updated.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    public String expireExecutionEvents() {
        ExecutionEventService.CleanupResult cleanup = eventService.cleanupStale(LocalDateTime.now());
        return "expired=" + cleanup.expired()
                + "\nresolvedClosedPositions=" + cleanup.resolvedClosedPositions()
                + "\nNo trading, OCO, strategy, grid order, or fund behavior was changed.";
    }

    @Tool(description = "Mark an execution-manager event as ACKED, IGNORED, RESOLVED, or EXPIRED. " +
            "This only updates bt_execution_event status; no trading, OCO, strategy, grid, or fund behavior is changed. " +
            "params: eventId required, status=ACKED|IGNORED|RESOLVED|EXPIRED.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    public String markExecutionEventStatus(Long eventId, String status) {
        ExecutionEventStatus parsed = parseStatus(status);
        ExecutionEvent event = eventService.markStatus(eventId, parsed);
        return "=== Execution Event Status Updated ===\n"
                + "eventId=" + event.getId()
                + " status=" + event.getStatus()
                + " type=" + event.getType()
                + " symbol=" + event.getSymbol()
                + (event.getPositionId() == null ? "" : " positionId=" + event.getPositionId())
                + "\n"
                + event.getTitle()
                + "\nNo trading, OCO, strategy, grid order, or fund behavior was changed.";
    }

    @Tool(description = "Send or preview a compact Telegram card for active execution-manager events. " +
            "No trading, OCO, strategy, grid, or fund behavior is changed. param: dryRun=true previews only.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    public String sendExecutionEventNotification(Boolean dryRun) {
        return notificationService.sendActiveEventNotification(dryRun);
    }

    private static ExecutionEventStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        ExecutionEventStatus parsed = ExecutionEventStatus.valueOf(status.trim().toUpperCase());
        if (parsed == ExecutionEventStatus.ACTIVE) {
            throw new IllegalArgumentException("ACTIVE is not a terminal status");
        }
        return parsed;
    }

    private String renderDrafts(List<ExecutionEventService.Draft> drafts, boolean dryRun) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Execution Event Scan ===\n")
                .append("mode=").append(dryRun ? "DRY_RUN" : "PERSIST")
                .append(" detectors=").append(detectors.size())
                .append(" events=").append(drafts.size())
                .append("\n")
                .append("boundary: READ_ONLY detection; no trading/OCO/strategy/grid/fund behavior changed.\n\n");
        if (drafts.isEmpty()) {
            sb.append("No execution events detected.");
            return sb.toString();
        }
        int i = 1;
        for (ExecutionEventService.Draft d : drafts) {
            sb.append(i++).append(". ").append(formatTime(d.detectedAt()))
                    .append(" ").append(d.symbol())
                    .append(d.positionId() == null ? "" : " pos#" + d.positionId())
                    .append(" ").append(d.type())
                    .append(" ").append(d.severity())
                    .append(" recommendation=").append(d.recommendation())
                    .append(" boundary=").append(d.actionBoundary())
                    .append("\n   ").append(d.title())
                    .append("\n   ").append(d.summary())
                    .append("\n   fingerprint=").append(d.fingerprint())
                    .append("\n");
        }
        return sb.toString();
    }

    private static String formatTime(LocalDateTime utc) {
        if (utc == null) return "time=N/A";
        return utc.atZone(ZoneId.of("UTC")).withZoneSameInstant(TAIPEI).format(FMT) + " Taipei";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
