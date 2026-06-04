package com.agora.service.trading;

import com.agora.infra.notification.NotificationPort;
import com.agora.service.TgNotificationDeduper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreBuyPostScoutNearTriggerAlertService {

    private static final int MAX_TG_SOURCE_LENGTH = 100;
    private static final String SOURCE_PREFIX = "SBPostScoutNear";

    private final ObjectMapper objectMapper;
    private final NotificationPort notificationPort;
    private final TgNotificationDeduper tgNotificationDeduper;

    @Value("${trading.score-buy.post-scout-add.notification.enabled:true}")
    private boolean enabled;

    @Value("${trading.score-buy.post-scout-add.notification.telegram-enabled:true}")
    private boolean telegramEnabled;

    @Value("${trading.score-buy.post-scout-add.notification.cooldown-minutes:30}")
    private long cooldownMinutes;

    @Value("${trading.score-buy.post-scout-add.notification.near-trigger-cooldown-minutes:180}")
    private long nearTriggerCooldownMinutes;

    @Value("${trading.score-buy.post-scout-add.notification.daily-cap-wait-cooldown-minutes:720}")
    private long dailyCapWaitCooldownMinutes;

    @Value("${trading.score-buy.post-scout-add.notification.near-pullback-gap-pct:0.35}")
    private double nearPullbackGapPct;

    @Value("${trading.score-buy.post-scout-add.notification.near-confirmation-gap-pct:0.35}")
    private double nearConfirmationGapPct;

    @Value("${trading.score-buy.post-scout-add.notification.near-rsi-overheat-buffer:3.0}")
    private double nearRsiOverheatBuffer;

    public AlertDecision evaluate(String rawStatus) {
        JsonNode status = read(rawStatus);
        if (status == null || status.isMissingNode()) {
            return AlertDecision.suppressed("UNPARSABLE_STATUS");
        }
        if (status.path("orderSent").asBoolean(false)) {
            return AlertDecision.suppressed("ORDER_ALREADY_SENT");
        }
        JsonNode next = status.path("nextTriggerSummary");
        if (next.isMissingNode() || next.isNull()) {
            return AlertDecision.suppressed("MISSING_NEXT_TRIGGER_SUMMARY");
        }
        List<String> hardGateBlockers = arrayText(next.path("hardGateBlockers"));
        if (!hardGateBlockers.isEmpty()) {
            return AlertDecision.suppressed("HARD_GATE_BLOCKERS_PRESENT:" + hardGateBlockers);
        }

        String symbol = text(status, "symbol", "BTCUSDT");
        String strategyId = text(status, "strategyId", "485");
        String state = text(status, "postScoutManagementState", text(next, "state", "UNKNOWN"));
        String primaryGap = text(next, "primaryGap", "UNKNOWN");
        String action = text(next, "nextRequiredAction", "UNKNOWN");
        boolean dailyCapOnlyBlocker = isDailyCapOnlyBlocker(status);
        boolean executionEligible = status.path("executionEligible").asBoolean(false)
                || next.path("addOnEligible").asBoolean(false)
                || "READY".equalsIgnoreCase(primaryGap);
        JsonNode pullback = next.path("pullbackPath");
        JsonNode confirmation = next.path("confirmationPath");
        double pullbackGap = number(pullback, "currentAbovePullbackMaxPct");
        double confirmationGap = number(confirmation, "currentBelowConfirmationMinPct");
        double oneHourRsi = number(pullback, "oneHourRsi");
        double oneHourRsiMax = number(pullback, "oneHourRsiMax");
        double fifteenMinuteRsi = number(pullback, "fifteenMinuteRsi");
        boolean oneHourOverheated = pullback.path("oneHourOverheated").asBoolean(false);
        boolean fifteenMinuteOverheated = pullback.path("fifteenMinuteOverheated").asBoolean(false);

        String alertKind = null;
        TgNotificationDeduper.Severity severity = TgNotificationDeduper.Severity.FYI;
        long ttlMinutes = Math.max(1L, cooldownMinutes);
        if (dailyCapOnlyBlocker
                && (executionEligible
                || status.path("eligibleAfterDailyCapResetPreview").asBoolean(false)
                || status.path("wouldExecuteAfterDailyCapReset").asBoolean(false))) {
            alertKind = "DAILY_CAP_WAIT_READY_AFTER_RESET";
            ttlMinutes = Math.max(1L, dailyCapWaitCooldownMinutes);
        } else if (executionEligible) {
            alertKind = "READY_FOR_WRITE_PATH_RECHECK";
            severity = TgNotificationDeduper.Severity.WARN;
        } else if (nearPullback(pullbackGap, primaryGap)) {
            alertKind = "PULLBACK_ZONE_NEAR";
            ttlMinutes = Math.max(1L, nearTriggerCooldownMinutes);
        } else if (nearConfirmation(confirmationGap)) {
            alertKind = "CONFIRMATION_RECLAIM_NEAR";
            ttlMinutes = Math.max(1L, nearTriggerCooldownMinutes);
        } else if (nearCooldown(oneHourOverheated, oneHourRsi, oneHourRsiMax)
                || nearCooldown(fifteenMinuteOverheated, fifteenMinuteRsi, 65.0)) {
            alertKind = "RSI_COOLDOWN_NEAR";
            ttlMinutes = Math.max(1L, nearTriggerCooldownMinutes);
        }
        if (alertKind == null) {
            return AlertDecision.suppressed("NOT_NEAR_TRIGGER");
        }

        String source = dailyCapOnlyBlocker
                ? limitSource(SOURCE_PREFIX + ":" + symbol + ":" + strategyId
                + ":DAILY_CAP_WAIT:" + resetBucket(status))
                : limitSource(SOURCE_PREFIX + ":" + symbol + ":" + strategyId
                + ":" + shortKey(state)
                + ":" + shortKey(alertKind)
                + ":" + shortKey(primaryGap));
        return new AlertDecision(true,
                "ALERTABLE_" + alertKind,
                source,
                alertKind,
                severity,
                ttlMinutes,
                message(status, next, alertKind, primaryGap, action, pullbackGap, confirmationGap,
                        oneHourRsi, fifteenMinuteRsi));
    }

    public boolean maybeNotify(String rawStatus) {
        if (!enabled || !telegramEnabled) {
            return false;
        }
        try {
            AlertDecision decision = evaluate(rawStatus);
            if (!decision.alertable()) {
                log.debug("[ScoreBuyPostScoutNearTrigger] suppressed: {}", decision.reason());
                return false;
            }
            Duration ttl = Duration.ofMinutes(Math.max(1L, decision.ttlMinutes()));
            if (!tgNotificationDeduper.shouldSend(decision.source(), ttl, decision.severity())) {
                return false;
            }
            notificationPort.alert(decision.message(), true, decision.source(), alertLevel(decision.severity()));
            return true;
        } catch (Throwable t) {
            log.warn("[ScoreBuyPostScoutNearTrigger] notify failed: {}", t.getMessage(), t);
            return false;
        }
    }

    private boolean nearPullback(double pullbackGap, String primaryGap) {
        if (!Double.isFinite(pullbackGap)) {
            return false;
        }
        String gap = primaryGap == null ? "" : primaryGap.toUpperCase(Locale.ROOT);
        return pullbackGap <= nearPullbackGapPct
                && (gap.contains("PULLBACK")
                || "INTRADAY_REVERSAL_NOT_CONFIRMED".equals(gap)
                || "WAIT_PULLBACK_OR_CONFIRMATION".equals(gap)
                || "READY".equals(gap));
    }

    private boolean nearConfirmation(double confirmationGap) {
        return Double.isFinite(confirmationGap) && confirmationGap <= nearConfirmationGapPct;
    }

    private boolean nearCooldown(boolean overheated, double current, double max) {
        return overheated
                && Double.isFinite(current)
                && Double.isFinite(max)
                && current <= max + nearRsiOverheatBuffer;
    }

    private boolean isDailyCapOnlyBlocker(JsonNode status) {
        if (status == null || status.isMissingNode()) {
            return false;
        }
        if (status.path("dailyCapOnlyBlocker").asBoolean(false)) {
            return true;
        }
        String primaryNoBuyReason = text(status, "primaryNoBuyReason", "");
        if ("DAILY_CAP_WAIT".equalsIgnoreCase(primaryNoBuyReason)) {
            return true;
        }
        List<String> blockers = arrayText(status.path("blockers"));
        return blockers.size() == 1
                && "DAILY_SCORE_BUY_POST_SCOUT_ADD_CAP_REACHED".equalsIgnoreCase(blockers.get(0));
    }

    private String message(JsonNode status,
                           JsonNode next,
                           String alertKind,
                           String primaryGap,
                           String action,
                           double pullbackGap,
                           double confirmationGap,
                           double oneHourRsi,
                           double fifteenMinuteRsi) {
        JsonNode pullback = next.path("pullbackPath");
        JsonNode confirmation = next.path("confirmationPath");
        List<String> lines = new ArrayList<>();
        lines.add("<b>SCORE_BUY post-scout 接近觸發</b>");
        lines.add("只通知(NOTIFY_ONLY)；本訊息不會下單，也不會修改 OCO、策略、Grid、資金或 Earn。");
        lines.add("標的=" + esc(text(status, "symbol", "BTCUSDT"))
                + " 策略=" + esc(text(status, "strategyId", "485")));
        lines.add("狀態=" + esc(displayStatus(text(status, "postScoutManagementState", text(next, "state", "UNKNOWN"))))
                + " 觸發類型=" + esc(displayStatus(alertKind)));
        lines.add("主要不買原因=" + esc(displayStatus(text(status, "primaryNoBuyReason", "N/A")))
                + " 可執行=" + yesNo(status.path("executionEligible").asBoolean(false))
                + " 目前會下單=" + yesNo(status.path("wouldExecute").asBoolean(false)));
        lines.add("頂層阻擋=" + esc(arrayText(status.path("blockers")).toString()));
        lines.add("每日額度重置後可再評估=" + yesNo(status.path("eligibleAfterDailyCapResetPreview").asBoolean(false))
                + " 重置後可能執行=" + yesNo(status.path("wouldExecuteAfterDailyCapReset").asBoolean(false))
                + " 下次重置UTC=" + esc(text(status, "nextDailyCapResetAtUtc", "N/A"))
                + " 台北=" + esc(text(status, "nextDailyCapResetAtAsiaTaipei", "N/A")));
        lines.add("主要缺口=" + esc(displayStatus(primaryGap)) + " 下一步=" + esc(displayStatus(action)));
        lines.add("現價=" + esc(text(pullback, "currentPrice", "N/A"))
                + " 回落上限=" + esc(text(pullback, "requiredPullbackMaxPrice", "N/A"))
                + " 差距%=" + esc(fmt(pullbackGap)));
        lines.add("確認價下限=" + esc(text(confirmation, "requiredConfirmationMinPrice", "N/A"))
                + " 確認差距%=" + esc(fmt(confirmationGap)));
        lines.add("1h RSI=" + esc(fmt(oneHourRsi))
                + " 15m RSI=" + esc(fmt(fifteenMinuteRsi))
                + " 事件風險=" + esc(displayStatus(text(status, "eventRiskLevel", "UNKNOWN"))));
        JsonNode reversal = next.path("intradayReversalDetails");
        lines.add("盤中反轉=" + esc(displayStatus(text(next, "intradayReversalStatus", "UNKNOWN")))
                + " 訊號數=" + esc(text(next, "intradayReversalSignalCount", "N/A"))
                + " 部分反轉觀察=" + yesNo(next.path("partialReversalWatch").asBoolean(false)));
        lines.add("下一個盤中反轉要求=" + esc(displayStatus(text(next, "nextIntradayReversalRequirement", "UNKNOWN")))
                + " 缺少反轉訊號=" + esc(arrayText(reversal.path("missingSignals")).toString()));
        lines.add("觸發阻擋=" + esc(arrayText(next.path("triggerBlockingSignals")).toString()));
        lines.add("硬性安全阻擋=" + esc(arrayText(next.path("hardGateBlockers")).toString()));
        lines.add("只讀確認(READ_ONLY)：已下單=" + yesNo(status.path("orderSent").asBoolean(false))
                + " OCO已修改=" + yesNo(status.path("ocoModified").asBoolean(false))
                + " RuntimeEvidence已寫入=" + yesNo(status.path("writesRuntimeEvidence").asBoolean(false)));
        return String.join("\n", lines);
    }

    private String yesNo(boolean value) {
        return value ? "是" : "否";
    }

    private String displayStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "N/A";
        }
        return switch (raw) {
            case "DAILY_CAP_WAIT" -> "等待每日額度(DAILY_CAP_WAIT)";
            case "DAILY_CAP_WAIT_READY_AFTER_RESET" -> "每日額度重置後可再評估(DAILY_CAP_WAIT_READY_AFTER_RESET)";
            case "WAIT_PULLBACK_OR_CONFIRMATION" -> "等待回落或確認(WAIT_PULLBACK_OR_CONFIRMATION)";
            case "PRICE_ABOVE_PULLBACK_ZONE" -> "價格仍高於回落區(PRICE_ABOVE_PULLBACK_ZONE)";
            case "WAIT_FOR_PULLBACK_OR_RECOVERY_CONFIRMATION" -> "等待回落或反轉確認(WAIT_FOR_PULLBACK_OR_RECOVERY_CONFIRMATION)";
            case "HOLD_SCOUT_MONITOR" -> "持有 scout 觀察(HOLD_SCOUT_MONITOR)";
            case "EARLY_RECOVERY_SCOUT" -> "早期反彈 scout(EARLY_RECOVERY_SCOUT)";
            case "PARTIAL" -> "部分成立(PARTIAL)";
            case "PASS" -> "通過(PASS)";
            case "UNKNOWN" -> "未知(UNKNOWN)";
            case "R0" -> "R0";
            case "R1" -> "R1";
            case "R2" -> "R2 警示";
            case "R3" -> "R3 高風險";
            default -> raw;
        };
    }

    private String alertLevel(TgNotificationDeduper.Severity severity) {
        return severity == TgNotificationDeduper.Severity.WARN ? "WARN" : "INFO";
    }

    private JsonNode read(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawStatus);
        } catch (Exception e) {
            log.debug("[ScoreBuyPostScoutNearTrigger] JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    private List<String> arrayText(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(v -> {
            String text = v.asText("");
            if (!text.isBlank()) {
                values.add(text);
            }
        });
        return values;
    }

    private String text(JsonNode node, String key, String fallback) {
        JsonNode value = node == null ? null : node.path(key);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        String text = value.asText("");
        return text.isBlank() ? fallback : text;
    }

    private double number(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.path(key);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return Double.NaN;
        }
        return value.asDouble(Double.NaN);
    }

    private String gapBucket(double value) {
        if (!Double.isFinite(value)) return "NA";
        if (value <= 0.10) return "le0.10";
        if (value <= 0.25) return "le0.25";
        if (value <= 0.50) return "le0.50";
        if (value <= 1.00) return "le1.00";
        return "gt1.00";
    }

    private String shortKey(String value) {
        String key = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "ADD_ON_CONFIRMATION_READY" -> "CONFIRM";
            case "ADD_ON_PULLBACK_READY" -> "PULLBACK";
            case "ADD_ON_DAILY_CONFIRMATION_READY" -> "DAILY";
            case "WAIT_PULLBACK_AFTER_SCOUT" -> "WAIT_PB";
            case "HOLD_SCOUT_MONITOR" -> "HOLD";
            case "HOLD_SCOUT_HARD_BLOCKED" -> "HARD_BLOCK";
            case "READY_FOR_WRITE_PATH_RECHECK" -> "READY_RECHECK";
            case "PULLBACK_ZONE_NEAR" -> "PB_NEAR";
            case "CONFIRMATION_RECLAIM_NEAR" -> "CF_NEAR";
            case "RSI_COOLDOWN_NEAR" -> "RSI_NEAR";
            case "PRICE_ABOVE_PULLBACK_ZONE" -> "PB_ABOVE";
            case "OVERHEATED_AND_PRICE_ABOVE_PULLBACK_ZONE" -> "HOT_PB_ABOVE";
            case "ONE_HOUR_RSI_OVERHEATED" -> "1H_HOT";
            case "FIFTEEN_MINUTE_RSI_OVERHEATED" -> "15M_HOT";
            case "INTRADAY_REVERSAL_NOT_CONFIRMED" -> "NO_REVERSAL";
            case "WAIT_PULLBACK_OR_CONFIRMATION" -> "WAIT";
            default -> sanitizeShort(key);
        };
    }

    private String sanitizeShort(String value) {
        if (value == null || value.isBlank()) {
            return "NA";
        }
        String sanitized = value.replaceAll("[^A-Z0-9_]", "");
        return sanitized.length() <= 16 ? sanitized : sanitized.substring(0, 16);
    }

    private String resetBucket(JsonNode status) {
        String reset = text(status, "nextDailyCapResetAtUtc", "NO_RESET")
                .toUpperCase(Locale.ROOT)
                .replace("-", "")
                .replace(":", "")
                .replace("T", "_")
                .replace("Z", "");
        return sanitizeShort(reset);
    }

    private String limitSource(String source) {
        if (source.length() <= MAX_TG_SOURCE_LENGTH) {
            return source;
        }
        String hash = Integer.toHexString(source.hashCode());
        int prefixLength = Math.max(0, MAX_TG_SOURCE_LENGTH - hash.length() - 1);
        return source.substring(0, prefixLength) + ":" + hash;
    }

    private String fmt(double value) {
        if (!Double.isFinite(value)) {
            return "N/A";
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public record AlertDecision(boolean alertable,
                                String reason,
                                String source,
                                String alertKind,
                                TgNotificationDeduper.Severity severity,
                                long ttlMinutes,
                                String message) {
        static AlertDecision suppressed(String reason) {
            return new AlertDecision(false, reason, "", "", TgNotificationDeduper.Severity.FYI, 0L, "");
        }
    }
}
