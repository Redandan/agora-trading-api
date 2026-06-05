package com.agora.scheduler.trading;

import com.agora.model.AttentionRule;
import com.agora.repository.trading.AttentionRuleRepository;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.meta.ScorecardReportService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 每週一 UTC 09:30 (Taipei 17:30) 推送統一 scorecard 到 TG —— 交易策略 / ML 模型 / 被動基準
 * 的一站式比較,讓操作者週一早上開 session 時就能看到「我們上週到底贏還是輸」。
 *
 * <p>執行內容委託給 {@link ScorecardReportService#formatAsText()},同樣內容也由
 * MCP 工具 {@code getStrategyScorecard} 提供,兩個入口共用單一 formatter,避免 drift。
 *
 * <p>容錯:
 * <ul>
 *   <li>formatAsText() 內部已 per-section try/catch,失敗一區其他區仍會出。</li>
 *   <li>此 scheduler 額外一層外包,避免 TG 送失敗造成後續 scheduled job 爆。</li>
 *   <li>{@code firstRun} flag 吸收 Spring 啟動時序:若剛部署的 JVM 在 cron window
 *       內冷啟動,第一次 tick 視為 no-op,避免重複送或以半初始化 bean 算錯。</li>
 * </ul>
 *
 * <p>Config:
 * <ul>
 *   <li>{@code meta-control.scorecard-digest.enabled}(預設 {@code false})</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyScorecardDigest {

    private final ScorecardReportService scorecardReportService;
    private final NotificationPort notificationPort;
    private final AttentionRuleRepository attentionRuleRepo;
    private final MarketIndicatorHistoryRepository indicatorHistoryRepo;
    private final ObjectMapper objectMapper;

    @Value("${meta-control.scorecard-digest.enabled:false}")
    private boolean enabled;

    /** #429 — 視窗(天):active attention rule 引用的 mih_indicator 在這段時間內 0 samples → zombie。 */
    private static final int ZOMBIE_LOOKBACK_DAYS = 30;

    private volatile boolean firstRun = true;

    /** 每週一 09:30 UTC (Taipei 17:30 每週一)。offset 30 min 錯開 {@code AttentionRuleWeeklyDigest}。 */
    @Scheduled(cron = "0 30 9 * * MON", zone = "UTC")
    public void sendWeeklyDigest() {
        if (!enabled) return;
        if (firstRun) {
            // 啟動立刻落在 Mon 09:30 UTC 視窗的極小機率情境 — 避免部署後立即雙發。
            firstRun = false;
            log.info("[WeeklyScorecardDigest] first-run skip (guard against deploy-time cron overlap)");
            return;
        }
        try {
            String text = scorecardReportService.formatAsText();
            String zombie = buildZombieAttentionRuleSection(LocalDateTime.now(ZoneOffset.UTC));
            String full = zombie.isEmpty() ? text : (text + zombie);
            // Plain text (no <b>/<code> markup) → pass useHtml=false so TG does not reject
            // on accidental `<` / `>` / `&` in future rows.
            notificationPort.broadcast(full, false);
            log.info("[WeeklyScorecardDigest] sent weekly scorecard ({} chars, zombie={})",
                    full.length(), !zombie.isEmpty());
        } catch (Throwable t) {
            log.error("[WeeklyScorecardDigest] failed: {}", t.getMessage(), t);
        }
    }

    /**
     * #429 — Audit active attention rules whose {@code mih_indicator} predicate has
     * had 0 fresh samples in the last {@link #ZOMBIE_LOOKBACK_DAYS} days.
     * Such rules can never fire (indicator collection stopped, e.g. VDI freeze in
     * #321 / #568) and bloat the rule pool. Reported here so the operator can
     * disable them via {@code disableAttentionRule} MCP.
     *
     * <p>Returns empty string when no zombies — caller appends only when non-empty.
     * Errors are swallowed: a malformed predicate JSON skips that rule, a DB hiccup
     * fails the whole section to empty (digest must still go out).
     */
    String buildZombieAttentionRuleSection(LocalDateTime now) {
        try {
            LocalDateTime since = now.minusDays(ZOMBIE_LOOKBACK_DAYS);
            List<AttentionRule> active = attentionRuleRepo.findActive(now);
            List<String> zombies = new ArrayList<>();
            for (AttentionRule r : active) {
                String row = checkSingleRule(r, since);
                if (row != null) zombies.add(row);
            }
            if (zombies.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("\n=== 💤 Zombie Attention Rules (")
                    .append(ZOMBIE_LOOKBACK_DAYS).append("d 0 samples) ===\n");
            for (String z : zombies) sb.append(z).append('\n');
            sb.append("→ 建議透過 disableAttentionRule MCP 關閉,或檢查 indicator 收集流程。\n");
            return sb.toString();
        } catch (Throwable t) {
            log.warn("[WeeklyScorecardDigest] zombie audit failed: {}", t.getMessage());
            return "";
        }
    }

    private String checkSingleRule(AttentionRule rule, LocalDateTime since) {
        try {
            Map<String, Object> pred = objectMapper.readValue(
                    rule.getPredicateJson(), new TypeReference<>() {});
            Object indObj = pred.get("mih_indicator");
            if (!(indObj instanceof String ind) || ind.isEmpty()) return null;
            String symbol = pred.get("symbol") instanceof String s && !s.isEmpty()
                    ? s : "BTCUSDT";
            long count = indicatorHistoryRepo.countCleanSince(symbol, ind, since);
            if (count > 0) return null;
            return String.format("- [#%d] %s (indicator=%s, symbol=%s, 0 samples)",
                    rule.getId(), rule.getName(), ind, symbol);
        } catch (Exception e) {
            log.debug("[WeeklyScorecardDigest] zombie check skip rule={}: {}",
                    rule.getId(), e.getMessage());
            return null;
        }
    }
}
