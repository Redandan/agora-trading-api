package com.agora.scheduler.trading;

import com.agora.model.BtDecisionAudit;
import com.agora.mcp.TradingManagerMcpTools;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.market.PolymarketMonitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日午夜 TG 報告統一排程器（UTC 00:00）。
 *
 * <h3>為何集中排程</h3>
 * 原本 3 個 TG 報告分散在 00:00 / 00:03 / 00:07，人工錯開是為了避免同秒觸發
 * 導致的 Telegram rate limit。集中後串行執行，自然形成毫秒級間隔，
 * 比人工 offset 更可靠，且少佔 2 個 @Scheduled thread slot。
 *
 * <h3>執行順序（各 step 獨立 try/catch）</h3>
 * <ol>
 *   <li>DailyReport：過去 24h 策略 + 訊號摘要（完整日報）</li>
 *   <li>OcoPnlReport：開倉 PnL + OCO 狀態摘要</li>
 *   <li>LiveSignalHealth：WS 訂閱狀態 + 訊號系統健康摘要</li>
 *   <li>TradingManagerActionSummary：昨日執行經理只讀行動摘要</li>
 * </ol>
 *
 * <h3>TG Rate Limit</h3>
 * TG Bot API 上限 30 msg/s（全域），3 條訊息串行發送間隔 ~1-2s，不觸發限制。
 * 各 step 自有 try/catch，TG 失敗只 log，不中斷後續報告。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "trading.daily-tg-report.enabled", havingValue = "true")
public class DailyTgReportOrchestrator {

    private final DailyReportScheduler dailyReportScheduler;
    private final OcoPositionPollerScheduler ocoPositionPollerScheduler;
    private final LiveSignalHealthScheduler liveSignalHealthScheduler;
    private final PolymarketMonitorService polymarketMonitorService;
    private final NotificationPort notificationPort;
    private final BtDecisionAuditRepository decisionAuditRepository;
    private final TradingManagerMcpTools tradingManagerMcpTools;

    static final int FILTER_BLOCK_ALERT_THRESHOLD = 3;

    /**
     * 每日 UTC 00:00（原本 DailyReport 00:00、OcoPnl 00:03、LiveSignalHealth 00:07
     * 分散觸發，現合併串行執行）。
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    public void sendDailyReports() {
        log.info("[DailyTgReport] ===== Start daily TG report sequence =====");
        long t0 = System.currentTimeMillis();

        // Step 1-3 合併：策略日報 + 成交明細 + WS 健康 → 一條訊息
        safeRun("combinedDailyReport", () -> {
            LocalDateTime now      = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
            LocalDateTime dayStart = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS).minusDays(1);
            LocalDateTime dayEnd   = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS);

            String mainSummary = dailyReportScheduler.buildSummary(dayStart, dayEnd);
            String pnlContent  = ocoPositionPollerScheduler.buildPnlContent(dayStart, dayEnd);
            String wsContent   = liveSignalHealthScheduler.buildWsContent();
            String managerContent = buildManagerDailySection();

            StringBuilder combined = new StringBuilder(mainSummary);
            if (pnlContent != null && !pnlContent.isBlank()) {
                combined.append("\n\n").append(pnlContent);
            }
            if (wsContent != null && !wsContent.isBlank()) {
                combined.append("\n\n").append(wsContent);
            }
            if (managerContent != null && !managerContent.isBlank()) {
                combined.append("\n\n").append(managerContent);
            }
            notificationPort.broadcast(combined.toString(), true);
        });

        // Step 4: Polymarket MEDIUM 每日彙整（含 AI 翻譯 + BTC 資金方向分析）
        safeRun("polymarketDigest", () -> {
            String digest = polymarketMonitorService.buildDailyDigest();
            if (digest != null) notificationPort.broadcast(digest, true);
        });

        // Step 5: #214 FILTER_BLOCK 日摘要（≥3 次 → 發 TG 提醒，避免訊號被靜默過濾）
        safeRun("filterBlockDigest", this::sendFilterBlockDigestIfNeeded);

        log.info("[DailyTgReport] ===== Complete. total elapsed={}ms =====",
                System.currentTimeMillis() - t0);
    }

    /** #214: 若昨日 FILTER_BLOCK ≥ 3 次，發 TG 摘要（blocker 分組統計）。 */
    private void sendFilterBlockDigestIfNeeded() {
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(24);
        List<BtDecisionAudit> blocks = decisionAuditRepository.findFilterBlockSince(since);
        if (blocks.size() < FILTER_BLOCK_ALERT_THRESHOLD) return;

        Map<String, Long> byBlocker = new LinkedHashMap<>();
        for (BtDecisionAudit a : blocks) {
            String key = a.getBlocker() != null ? a.getBlocker() : "unknown";
            byBlocker.merge(key, 1L, Long::sum);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【資料品質警告】\n");
        sb.append("期間：過去 24 小時\n");
        sb.append("被過濾訊號：").append(blocks.size()).append(" 筆\n");
        sb.append("原因：資料新鮮度不足\n");
        sb.append("影響：近期策略訊號可信度下降\n");
        sb.append("處置：檢查資料源；修復前避免過度信任新訊號\n\n");
        sb.append("明細（依 blocker）：\n");
        byBlocker.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> sb.append(String.format("• %s: %d 次\n", e.getKey(), e.getValue())));
        sb.append("\n標籤：CHECK_FEED / DATA_QUALITY / FILTER_BLOCK");

        notificationPort.broadcast(sb.toString(), true);
        log.info("[FilterBlockDigest] sent: {} blocks, {} unique blockers", blocks.size(), byBlocker.size());
    }

    private String buildManagerDailySection() {
        try {
            String raw = tradingManagerMcpTools.getTradingManagerActionSummary(false, 1, "BTCUSDT");
            return formatManagerDailySection(raw);
        } catch (Exception e) {
            log.warn("[DailyTgReport] trading manager daily section failed: {}", e.getMessage(), e);
            return "🧭 <b>昨日執行經理報告</b>\n"
                    + "⚠️ 無法生成執行經理摘要："
                    + HtmlUtils.htmlEscape(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                    + "\nREAD_ONLY；交易、OCO、策略、Grid、資金行為均未變更。";
        }
    }

    static String formatManagerDailySection(String raw) {
        if (raw == null || raw.isBlank()) return null;

        Map<String, List<String>> buckets = new LinkedHashMap<>();
        String current = null;
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.matches("^(CRITICAL|WARN|ACTIONS|WATCH|DO_NOT|INFO):$")) {
                current = trimmed.substring(0, trimmed.length() - 1);
                buckets.putIfAbsent(current, new ArrayList<>());
                continue;
            }
            if (current != null && trimmed.startsWith("- ")) {
                buckets.get(current).add(trimmed.substring(2).trim());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🧭 <b>昨日執行經理報告</b>\n");
        sb.append("READ_ONLY；不是 BUY/SELL/加倉指令，只用來檢查倉位、OCO、Grid 與系統風險。\n");
        appendManagerBucket(sb, "CRITICAL", "🚨 CRITICAL", buckets);
        appendManagerBucket(sb, "WARN", "⚠️ WARN", buckets);
        appendManagerBucket(sb, "ACTIONS", "✅ ACTIONS", buckets);
        appendManagerBucket(sb, "WATCH", "👀 WATCH", buckets);
        appendManagerBucket(sb, "DO_NOT", "⛔ DO_NOT", buckets);
        appendManagerBucket(sb, "INFO", "ℹ️ INFO", buckets);
        return sb.toString().trim();
    }

    private static void appendManagerBucket(StringBuilder sb, String key, String label, Map<String, List<String>> buckets) {
        List<String> items = buckets.get(key);
        if (items == null || items.isEmpty()) return;
        sb.append("\n<b>").append(label).append("</b>\n");
        for (String item : items) {
            sb.append("  - ").append(HtmlUtils.htmlEscape(item)).append("\n");
        }
    }

    /**
     * 執行單一 TG 報告 step，失敗只 log 不影響後續。
     */
    private void safeRun(String label, Runnable task) {
        long t = System.currentTimeMillis();
        try {
            log.debug("[DailyTgReport] >> step: {}", label);
            task.run();
            log.debug("[DailyTgReport] << step: {} OK ({}ms)", label, System.currentTimeMillis() - t);
        } catch (Exception e) {
            log.error("[DailyTgReport] << step: {} FAILED ({}ms): {}",
                    label, System.currentTimeMillis() - t, e.getMessage(), e);
        }
    }
}
