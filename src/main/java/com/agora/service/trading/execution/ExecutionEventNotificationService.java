package com.agora.service.trading.execution;

import com.agora.config.properties.ExecutionEventProperties;
import com.agora.enums.trading.ExecutionEventSeverity;
import com.agora.enums.trading.ExecutionEventType;
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
    private static final int TELEGRAM_SAFE_TEXT_LIMIT = 3800;
    private static final int EVENT_TITLE_LIMIT = 120;
    private static final int EVENT_SUMMARY_LIMIT = 220;
    private static final String SAFETY_FOOTER = "\n未執行任何交易、OCO、策略、Grid 訂單或資金行為變更。";

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
        int omitted = 0;
        for (ExecutionEvent e : sorted) {
            String block = renderEventBlock(i, e);
            if (sb.length() + block.length() + SAFETY_FOOTER.length() > TELEGRAM_SAFE_TEXT_LIMIT) {
                omitted = sorted.size() - i + 1;
                break;
            }
            sb.append(block);
            i++;
        }

        if (omitted > 0) {
            sb.append("\n另有 ").append(omitted)
                    .append(" 筆 active event 已省略；請用按鈕或只讀 MCP 查完整列表。\n");
        }

        sb.append(SAFETY_FOOTER);
        return enforceTelegramLimit(sb.toString());
    }

    private static String renderEventBlock(int index, ExecutionEvent e) {
        StringBuilder sb = new StringBuilder();
        sb.append(index).append(". ")
                .append(toDisplay(e.getSeverity())).append(" ")
                .append("類型=").append(toDisplayType(e.getType())).append(" ")
                .append(e.getSymbol() == null ? "未知(UNKNOWN)" : e.getSymbol());
        if (e.getPositionId() != null) sb.append(" 倉位#").append(e.getPositionId());
        sb.append("\n")
                .append("   ").append(compact(toDisplayTitle(e.getTitle()), EVENT_TITLE_LIMIT)).append("\n")
                .append("   ").append(compact(toDisplaySummary(e.getSummary()), EVENT_SUMMARY_LIMIT)).append("\n")
                .append("   建議=").append(toDisplay(e.getRecommendation()))
                .append(" 邊界=").append(toDisplay(e.getActionBoundary()))
                .append(" 偵測時間=").append(formatTime(e.getDetectedAt()))
                .append("\n");
        return sb.toString();
    }

    private static String compact(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private static String enforceTelegramLimit(String message) {
        if (message.length() <= TELEGRAM_SAFE_TEXT_LIMIT) {
            return message;
        }
        String suffix = "\n...[truncated for Telegram safety]";
        return message.substring(0, TELEGRAM_SAFE_TEXT_LIMIT - suffix.length()) + suffix;
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

    private static String toDisplayType(ExecutionEventType type) {
        if (type == null) return "N/A";
        String display = switch (type) {
            case BREAKEVEN_ELIGIBLE -> "保本調整候選";
            case TRAILING_ELIGIBLE -> "移動停利候選";
            case OCO_MISSING -> "OCO 保護缺失";
            case OCO_RISK_REDUCTION_PREVIEW -> "OCO 風險降低預覽";
            case POSITION_TIMEOUT -> "持倉時間過長";
            case GRID_SELL_FAILED_STALE -> "Grid 賣出失敗已陳舊";
            case GRID_OUT_OF_RANGE -> "Grid 價格脫離區間";
            case GRID_REBALANCE_LIMIT -> "Grid 再平衡次數接近上限";
            case REPEATED_BUY_PRESSURE -> "重複買壓";
            case MARKET_RISK_FLIP -> "市場風險翻轉";
            case NEW_ENTRY_BLOCKED -> "新進場被阻擋";
            case EXECUTION_MANAGER_PROMOTION -> "執行管理升級審查";
        };
        return display + " (" + type.name() + ")";
    }

    private static String toDisplayTitle(String title) {
        if (title == null) return "N/A";
        return switch (title) {
            case "Breakeven protection is eligible" -> "保本調整條件已接近";
            case "Breakeven protection is eligible but trailing is disabled" -> "保本調整條件已接近，但 trailing 尚未啟用";
            case "Grid price is outside configured range" -> "Grid 價格超出設定區間";
            case "Grid SELL_FAILED level is stale" -> "Grid SELL_FAILED 層級已陳舊";
            case "Grid auto-rebalance limit reached" -> "Grid 自動再平衡已達上限";
            case "Grid auto-rebalance limit is near" -> "Grid 自動再平衡接近上限";
            case "Open position has no OCO protection" -> "自動交易持倉缺少 OCO 保護";
            case "Open auto-traded position is missing OCO protection" -> "自動交易持倉缺少 OCO 保護";
            case "Position has exceeded aging review threshold" -> "持倉時間超過審查門檻";
            case "Trailing protection is eligible" -> "移動停利條件已接近";
            default -> title;
        };
    }

    private static String toDisplaySummary(String summary) {
        if (summary == null) return "N/A";
        if (summary.equals("Review OCO poller before any manual action.")) {
            return "在任何手動操作前，先檢查 OCO poller 狀態。";
        }
        if (summary.equals("Position has reached the breakeven trigger. Review risk-reducing OCO preview before any action.")) {
            return "持倉已達保本觸發條件。任何操作前，先檢查只降低風險的 OCO 預覽。";
        }
        if (summary.equals("Position has reached the breakeven trigger. Consider enabling trailing/breakeven automation after preview.")) {
            return "持倉已達保本觸發條件。先看預覽，再評估是否啟用 trailing/保本自動化。";
        }
        if (summary.startsWith("Auto-traded open position has no OCO order id.")) {
            return summary.replace("Auto-traded open position has no OCO order id.",
                            "自動交易開倉目前沒有 OCO order id。")
                    .replace("Review OCO poller/retryOco status before taking action.",
                            "任何處置前，先檢查 OCO poller/retryOco 狀態。");
        }
        if (summary.startsWith("Open auto-traded position has been held for ")) {
            return summary.replace("Open auto-traded position has been held for ",
                            "自動交易持倉已持有 ")
                    .replace(" days. Review OCO, TP/SL distance, and current risk before any action.",
                            " 天。任何操作前，先檢查 OCO、TP/SL 距離與目前風險。");
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
        if (summary.startsWith("Grid #") && summary.contains("rebalance count is ")) {
            return summary.replace(" rebalance count is ", " 再平衡次數為 ")
                    .replace("Review market regime before allowing additional range changes.",
                            "允許再次調整區間前，先審查市場趨勢與區間狀態。");
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
