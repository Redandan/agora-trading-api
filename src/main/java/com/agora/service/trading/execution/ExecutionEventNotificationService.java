package com.agora.service.trading.execution;

import com.agora.config.properties.ExecutionEventProperties;
import com.agora.enums.trading.ExecutionEventSeverity;
import com.agora.model.ExecutionEvent;
import com.agora.service.TelegramService;
import com.agora.service.TgNotificationDeduper;
import com.agora.service.telegram.ExecutionEventTelegramButtons;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExecutionEventNotificationService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final ExecutionEventService eventService;
    private final ExecutionEventProperties properties;
    private final TelegramService telegramService;
    private final TgNotificationDeduper deduper;

    public String sendActiveEventNotification(Boolean dryRun) {
        boolean previewOnly = dryRun == null || dryRun;
        List<ExecutionEvent> events = eventService.listActive(null, null, properties.listLimit());
        if (events.isEmpty()) {
            return "SKIPPED: no active execution events";
        }

        String message = renderMessage(events);
        if (previewOnly) {
            return "DRY_RUN: execution-event notification not sent\n\n" + message;
        }

        String dedupKey = "ExecutionEventNotification:" + fingerprintSet(events);
        if (!deduper.shouldSend(dedupKey,
                Duration.ofMinutes(properties.notificationTtlMinutes()),
                TgNotificationDeduper.Severity.WARN)) {
            return "SKIPPED: repeated execution-event notification key=" + dedupKey;
        }

        ExecutionEvent primary = highestPriority(events);
        telegramService.sendChannelMessageWithKeyboard(
                message,
                false,
                ExecutionEventTelegramButtons.buildKeyboard(primary.getSymbol(), primary.getPositionId()),
                dedupKey,
                level(events));
        return "SENT: execution-event notification sent with Telegram drill-down buttons";
    }

    private ExecutionEvent highestPriority(List<ExecutionEvent> events) {
        return events.stream()
                .max(Comparator
                        .comparing(ExecutionEventNotificationService::severityRank)
                        .thenComparing(ExecutionEvent::getDetectedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(events.get(0));
    }

    String renderMessage(List<ExecutionEvent> events) {
        List<ExecutionEvent> sorted = events.stream()
                .sorted(Comparator
                        .comparing(ExecutionEventNotificationService::severityRank).reversed()
                        .thenComparing(ExecutionEvent::getDetectedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        ExecutionEvent primary = sorted.get(0);
        String symbols = sorted.stream()
                .map(ExecutionEvent::getSymbol)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .collect(Collectors.joining(","));

        StringBuilder sb = new StringBuilder();
        sb.append("[執行事件] ").append(symbols.isBlank() ? "全部(ALL)" : symbols).append("\n")
                .append("產生時間(台北)=").append(LocalDateTime.now(TAIPEI).format(FMT)).append("\n")
                .append("事件數=").append(sorted.size())
                .append(" 最高等級=").append(toDisplay(primary.getSeverity()))
                .append(" 邊界=").append(toDisplayBoundary("READ_ONLY")).append("\n")
                .append("操作建議=").append(toDisplayRecommendation("REVIEW_ONLY"))
                .append(" / 先用按鈕做只讀 drill-down，再決定是否調整 OCO、trailing、strategy、grid 或資金。\n\n");

        int i = 1;
        for (ExecutionEvent e : sorted) {
            sb.append(i++).append(". ")
                    .append(toDisplay(e.getSeverity())).append(" ")
                    .append("類型=").append(e.getType()).append(" ")
                    .append(e.getSymbol() == null ? "未知(UNKNOWN)" : e.getSymbol());
            if (e.getPositionId() != null) sb.append(" 倉位#").append(e.getPositionId());
            sb.append("\n")
                    .append("   ").append(toDisplayTitle(e.getTitle())).append("\n")
                    .append("   ").append(toDisplaySummary(e.getSummary())).append("\n")
                    .append("   建議=").append(toDisplay(e.getRecommendation()))
                    .append(" 邊界=").append(toDisplay(e.getActionBoundary()))
                    .append(" 偵測時間=").append(formatTime(e.getDetectedAt()))
                    .append("\n");
        }

        sb.append("\n未執行任何交易、OCO、策略、Grid 訂單或資金行為變更。");
        return sb.toString();
    }

    private static String toDisplay(Object value) {
        if (value == null) return "N/A";
        return switch (value.toString()) {
            case "CRITICAL" -> "緊急(CRITICAL)";
            case "ACTIONABLE" -> "可處理(ACTIONABLE)";
            case "WATCH" -> "觀察(WATCH)";
            case "INFO" -> "資訊(INFO)";
            case "REVIEW_ONLY" -> toDisplayRecommendation("REVIEW_ONLY");
            case "READ_ONLY" -> toDisplayBoundary("READ_ONLY");
            default -> value.toString();
        };
    }

    private static String toDisplayRecommendation(String raw) {
        if ("REVIEW_ONLY".equals(raw)) {
            return "僅審查(REVIEW_ONLY)";
        }
        return raw;
    }

    private static String toDisplayBoundary(String raw) {
        if ("READ_ONLY".equals(raw)) {
            return "只讀(READ_ONLY)";
        }
        return raw;
    }

    private static String toDisplayTitle(String title) {
        if (title == null) return "N/A";
        return switch (title) {
            case "Grid price is outside configured range" -> "Grid 價格超出設定區間";
            case "Grid SELL_FAILED level is stale" -> "Grid SELL_FAILED 層級已陳舊";
            case "Open auto-traded position is missing OCO protection" -> "自動交易持倉缺少 OCO 保護";
            default -> title;
        };
    }

    private static String toDisplaySummary(String summary) {
        if (summary == null) return "N/A";
        if (summary.equals("Review OCO poller before any manual action.")) {
            return "在任何手動操作前，先檢查 OCO poller 狀態。";
        }
        if (summary.startsWith("Grid #") && summary.contains("mark price is below range")) {
            return summary.replace("mark price is below range", "現價低於區間")
                    .replace("Review range/rebalance status before changing grid capital.",
                            "調整 Grid 資金前，先確認區間與再平衡狀態。");
        }
        if (summary.startsWith("Grid #") && summary.contains("mark price is above range")) {
            return summary.replace("mark price is above range", "現價高於區間")
                    .replace("Review range/rebalance status before changing grid capital.",
                            "調整 Grid 資金前，先確認區間與再平衡狀態。");
        }
        if (summary.startsWith("Grid #") && summary.contains("is SELL_FAILED with retry=3/3")) {
            return summary.replace(" is SELL_FAILED with retry=3/3. Review dust/materiality before any manual sell or grid resize.",
                    " 已 SELL_FAILED 且重試 3/3。手動賣出或調整 Grid 前，先確認是否為 dust 與是否具實質影響。");
        }
        return summary;
    }

    private static int severityRank(ExecutionEvent event) {
        if (event == null || event.getSeverity() == null) return 0;
        return switch (event.getSeverity()) {
            case CRITICAL -> 4;
            case ACTIONABLE -> 3;
            case WATCH -> 2;
            case INFO -> 1;
        };
    }

    private static String level(List<ExecutionEvent> events) {
        boolean critical = events.stream().anyMatch(e -> e.getSeverity() == ExecutionEventSeverity.CRITICAL);
        boolean warn = events.stream().anyMatch(e ->
                e.getSeverity() == ExecutionEventSeverity.ACTIONABLE || e.getSeverity() == ExecutionEventSeverity.WATCH);
        if (critical) return "CRITICAL";
        if (warn) return "WARN";
        return "INFO";
    }

    private static String fingerprintSet(List<ExecutionEvent> events) {
        String raw = events.stream()
                .map(ExecutionEvent::getFingerprint)
                .filter(s -> s != null && !s.isBlank())
                .sorted()
                .collect(Collectors.joining("|"));
        return sha256(raw).substring(0, 16);
    }

    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String formatTime(LocalDateTime utc) {
        if (utc == null) return "N/A";
        return utc.atZone(ZoneId.of("UTC")).withZoneSameInstant(TAIPEI).format(FMT) + " Taipei";
    }
}
