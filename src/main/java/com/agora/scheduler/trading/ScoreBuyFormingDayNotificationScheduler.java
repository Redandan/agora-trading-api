package com.agora.scheduler.trading;

import com.agora.infra.notification.NotificationPort;
import com.agora.service.TgNotificationDeduper;
import com.agora.service.trading.ScoreBuyFormingDayObserverService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScoreBuyFormingDayNotificationScheduler {

    private static final String SOURCE_PREFIX = "ScoreBuyFormingDay";

    private final ScoreBuyFormingDayObserverService observerService;
    private final NotificationPort notificationPort;
    private final TgNotificationDeduper tgNotificationDeduper;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> lastFingerprint = new AtomicReference<>();

    @Value("${trading.score-buy.forming-day.notification.enabled:false}")
    private boolean enabled;

    @Value("${trading.score-buy.forming-day.notification.telegram-enabled:false}")
    private boolean telegramEnabled;

    @Value("${trading.score-buy.forming-day.notification.symbol:BTCUSDT}")
    private String symbol;

    @Value("${trading.score-buy.forming-day.notification.strategy-id:485}")
    private Long strategyId;

    @Value("${trading.score-buy.forming-day.notification.cooldown-minutes:90}")
    private long cooldownMinutes;

    @Value("${trading.score-buy.forming-day.notification.non-actionable-cooldown-minutes:720}")
    private long nonActionableCooldownMinutes;

    @Scheduled(
            fixedDelayString = "${trading.score-buy.forming-day.notification.fixed-delay-ms:900000}",
            initialDelayString = "${trading.score-buy.forming-day.notification.initial-delay-ms:180000}")
    public void evaluateScoreBuyFormingDay() {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("[ScoreBuyFormingDayNotification] previous run still active; skip");
            return;
        }
        try {
            JsonNode status = objectMapper.readTree(observerService.getStatus(symbol, strategyId));
            String fingerprint = fingerprint(status);
            String previous = lastFingerprint.getAndSet(fingerprint);
            log.info("[ScoreBuyFormingDayNotification] state={} action={} readiness={} feasible={} blockers={}",
                    text(status, "scoreBuyFormingState"),
                    text(status, "recommendedAction"),
                    text(status, "executionReadiness"),
                    status.path("executionFeasible").asBoolean(false),
                    status.path("executionHardBlockers"));
            maybeNotify(status, previous, fingerprint);
        } catch (Throwable t) {
            log.warn("[ScoreBuyFormingDayNotification] evaluate failed: {}", t.getMessage(), t);
        } finally {
            running.set(false);
        }
    }

    private void maybeNotify(JsonNode status, String previous, String fingerprint) {
        if (!telegramEnabled || !isNotifiable(status, previous, fingerprint)) {
            return;
        }
        String state = text(status, "scoreBuyFormingState");
        String readiness = text(status, "executionReadiness");
        boolean nonActionable = isNonActionable(status);
        String source = SOURCE_PREFIX + ":" + text(status, "symbol") + ":" + text(status, "strategyId")
                + ":" + state + ":" + (nonActionable ? "NON_ACTIONABLE" : readiness);
        TgNotificationDeduper.Severity severity = nonActionable
                ? TgNotificationDeduper.Severity.FYI
                : severity(state, status.path("executionFeasible").asBoolean(false));
        long ttlMinutes = nonActionable ? nonActionableCooldownMinutes : cooldownMinutes;
        if (!tgNotificationDeduper.shouldSend(source, Duration.ofMinutes(Math.max(1L, ttlMinutes)), severity)) {
            return;
        }
        notificationPort.alert(message(status, previous), true, source, alertLevel(severity));
    }

    private boolean isNotifiable(JsonNode status, String previous, String fingerprint) {
        String state = text(status, "scoreBuyFormingState");
        if (!importantState(state)) {
            return false;
        }
        if (previous == null) {
            return !"NONE".equals(state);
        }
        return !fingerprint.equals(previous);
    }

    private boolean importantState(String state) {
        return switch (state) {
            case "WATCHING", "PRE_TRIGGER", "SCOUT_ACTIVE", "EARLY_RECOVERY_SCOUT", "PRE_POSITION",
                 "CONFIRMED_DAILY_SCORE_BUY", "INVALIDATED" -> true;
            default -> false;
        };
    }

    private String fingerprint(JsonNode status) {
        return String.join("|",
                text(status, "symbol"),
                text(status, "strategyId"),
                text(status, "scoreBuyFormingState"),
                text(status, "recommendedAction"),
                text(status, "executionReadiness"),
                text(status, "eventRiskLevel"),
                text(status, "invalidationReason"),
                String.valueOf(status.path("executionFeasible").asBoolean(false)),
                String.valueOf(status.path("belowExchangeMinimum").asBoolean(false)));
    }

    private boolean isNonActionable(JsonNode status) {
        if (status == null || status.isMissingNode()) {
            return false;
        }
        if (status.path("executionFeasible").asBoolean(false)) {
            return false;
        }
        String readiness = text(status, "executionReadiness");
        return status.path("belowExchangeMinimum").asBoolean(false)
                || readiness.contains("NO_RECOMMENDED_NOTIONAL")
                || readiness.contains("BELOW_MIN_NOTIONAL");
    }

    private TgNotificationDeduper.Severity severity(String state, boolean executionFeasible) {
        if ("CONFIRMED_DAILY_SCORE_BUY".equals(state)) {
            return TgNotificationDeduper.Severity.WARN;
        }
        if ("EARLY_RECOVERY_SCOUT".equals(state)) {
            return TgNotificationDeduper.Severity.WARN;
        }
        if ("PRE_POSITION".equals(state) && executionFeasible) {
            return TgNotificationDeduper.Severity.WARN;
        }
        return TgNotificationDeduper.Severity.FYI;
    }

    private String alertLevel(TgNotificationDeduper.Severity severity) {
        return severity == TgNotificationDeduper.Severity.WARN ? "WARN" : "INFO";
    }

    private String message(JsonNode status, String previousFingerprint) {
        List<String> lines = new ArrayList<>();
        lines.add("<b>SCORE_BUY 日內成形觀察</b>");
        lines.add("只讀觀察(REVIEW_ONLY / READ_ONLY)；不是 BUY/SELL 指令。");
        lines.add("標的=" + esc(text(status, "symbol")) + " 策略=" + esc(text(status, "strategyId")));
        lines.add("狀態=" + esc(displayStatus(text(status, "scoreBuyFormingState")))
                + " 前次=" + esc(displayStatus(previousState(previousFingerprint))));
        lines.add("狀態說明=" + esc(stateMeaning(text(status, "scoreBuyFormingState"))));
        lines.add("可操作性=" + esc(actionability(status)));
        lines.add("建議動作=" + esc(displayStatus(text(status, "recommendedAction"))));
        lines.add("執行準備=" + esc(displayStatus(text(status, "executionReadiness"))));
        lines.add("可執行=" + yesNo(status.path("executionFeasible").asBoolean(false))
                + " 低於交易所最小金額=" + yesNo(status.path("belowExchangeMinimum").asBoolean(false)));
        lines.add("預估名義金額=" + esc(text(status, "recommendedNotionalPreview"))
                + " 交易所最小金額=" + esc(text(status, "exchangeMinNotionalUsdt")));
        lines.add("事件風險=" + esc(displayStatus(text(status, "eventRiskLevel")))
                + " 倍率=" + esc(text(status, "eventRiskMultiplier")));
        lines.add("日線成形RSI=" + esc(text(status, "formingDailyRsi"))
                + " 接近下軌=" + yesNo(status.path("formingDailyNearLowerBb").asBoolean(false))
                + " 量能比=" + esc(text(status, "formingDailyVolumeRatio")));
        lines.add("下跌門檻=" + esc(displayStatus(text(status, "formingDailyDipGateState"))));
        lines.add("盤中反轉=" + esc(displayStatus(text(status, "intradayReversalStatus")))
                + " 錯過機會風險=" + esc(displayStatus(text(status, "missedOpportunityRisk"))));
        lines.add("觀察層硬阻擋=" + esc(arrayText(status.path("observerHardBlockers"))));
        lines.add("執行層硬阻擋=" + esc(arrayText(status.path("executionHardBlockers"))));
        lines.add("只讀確認(READ_ONLY)：已下單=" + yesNo(status.path("orderSent").asBoolean(false))
                + " OCO已修改=" + yesNo(status.path("ocoModified").asBoolean(false))
                + " RuntimeEvidence已寫入=" + yesNo(status.path("writesRuntimeEvidence").asBoolean(false)));
        return String.join("\n", lines);
    }

    private String stateMeaning(String state) {
        return switch (state) {
            case "WATCHING" -> "條件正在觀察中，尚不代表可執行。";
            case "PRE_TRIGGER" -> "日線成形 setup 接近，但仍只是預備。";
            case "SCOUT_ACTIVE" ->
                    "已有受限 SCORE_BUY scout 倉位；應監控 OCO/結果，等待日線確認或退出。";
            case "EARLY_RECOVERY_SCOUT" ->
                    "受限早期反彈 scout 狀態；只有在所有硬性安全門檻通過時，獨立執行策略才可使用 tiny 預備倉。";
            case "PRE_POSITION" ->
                    "受限預備倉狀態；不是完整日線 SCORE_BUY 部署。";
            case "CONFIRMED_DAILY_SCORE_BUY" ->
                    "日線 SCORE_BUY thesis 已確認；較大分批部署仍需通過寫入路徑檢查。";
            case "INVALIDATED" ->
                    "成形 setup 已失效；scout/預備倉路徑不應繼續。";
            default -> "不可操作。";
        };
    }

    private String actionability(JsonNode status) {
        String state = text(status, "scoreBuyFormingState");
        String readiness = text(status, "executionReadiness");
        if ("INVALIDATED".equals(state)) {
            return "不操作；失效原因=" + text(status, "invalidationReason");
        }
        if ("SCOUT_ACTIVE".equals(state)) {
            return "監控既有 scout；不代表要追加下單。";
        }
        if ("EARLY_RECOVERY_SCOUT".equals(state) || "PRE_POSITION".equals(state)) {
            if ("NOT_EXECUTABLE_READ_ONLY_PREVIEW".equals(readiness)) {
                return "observer 只讀；這是有效的受限 setup 預覽，不是失效。";
            }
            return "受限 setup 預覽；若要執行，必須走獨立受保護寫入路徑。";
        }
        if ("CONFIRMED_DAILY_SCORE_BUY".equals(state)) {
            return "日線確認預覽；執行仍需要分批預算、OCO、證據與風控檢查。";
        }
        return "只觀察。";
    }

    private String yesNo(boolean value) {
        return value ? "是" : "否";
    }

    private String displayStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "N/A";
        }
        return switch (raw) {
            case "WATCHING" -> "觀察中(WATCHING)";
            case "PRE_TRIGGER" -> "預觸發(PRE_TRIGGER)";
            case "SCOUT_ACTIVE" -> "Scout持倉中(SCOUT_ACTIVE)";
            case "EARLY_RECOVERY_SCOUT" -> "早期反彈Scout(EARLY_RECOVERY_SCOUT)";
            case "PRE_POSITION" -> "預備倉(PRE_POSITION)";
            case "CONFIRMED_DAILY_SCORE_BUY" -> "日線SCORE_BUY確認(CONFIRMED_DAILY_SCORE_BUY)";
            case "INVALIDATED" -> "已失效(INVALIDATED)";
            case "PRE_POSITION_PREVIEW_ONLY_BELOW_MIN_NOTIONAL" -> "預備倉預覽但低於最小金額(PRE_POSITION_PREVIEW_ONLY_BELOW_MIN_NOTIONAL)";
            case "EARLY_RECOVERY_SCOUT_PREVIEW_ONLY" -> "早期反彈Scout只讀預覽(EARLY_RECOVERY_SCOUT_PREVIEW_ONLY)";
            case "DAILY_SCORE_BUY_CONFIRMED_PREVIEW_ONLY" -> "日線SCORE_BUY確認只讀預覽(DAILY_SCORE_BUY_CONFIRMED_PREVIEW_ONLY)";
            case "MONITOR_ACTIVE_SCOUT" -> "監控既有Scout(MONITOR_ACTIVE_SCOUT)";
            case "PREPARE_ONLY_NO_ORDER" -> "僅預備不下單(PREPARE_ONLY_NO_ORDER)";
            case "NOT_EXECUTABLE_READ_ONLY_PREVIEW" -> "不可執行：只讀預覽(NOT_EXECUTABLE_READ_ONLY_PREVIEW)";
            case "NOT_EXECUTABLE_BELOW_MIN_NOTIONAL" -> "不可執行：低於最小金額(NOT_EXECUTABLE_BELOW_MIN_NOTIONAL)";
            case "NOT_EXECUTABLE_NO_RECOMMENDED_NOTIONAL" -> "不可執行：沒有建議名義金額(NOT_EXECUTABLE_NO_RECOMMENDED_NOTIONAL)";
            case "PASS" -> "通過(PASS)";
            case "R0" -> "R0";
            case "R1" -> "R1";
            case "R2" -> "R2 警示";
            case "R3" -> "R3 高風險";
            case "HIGH_BUT_R3_SCALED" -> "高但已用R3縮放(HIGH_BUT_R3_SCALED)";
            case "UNKNOWN" -> "未知(UNKNOWN)";
            default -> raw;
        };
    }

    private String previousState(String previousFingerprint) {
        if (previousFingerprint == null || previousFingerprint.isBlank()) {
            return "NONE";
        }
        String[] parts = previousFingerprint.split("\\|", -1);
        return parts.length > 2 ? parts[2] : "UNKNOWN";
    }

    private String arrayText(JsonNode node) {
        if (node == null || !node.isArray()) {
            return "[]";
        }
        List<String> values = new ArrayList<>();
        node.forEach(v -> values.add(v.asText()));
        return values.toString();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "N/A";
        }
        return value.isTextual() ? value.asText() : value.asText();
    }

    private String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
