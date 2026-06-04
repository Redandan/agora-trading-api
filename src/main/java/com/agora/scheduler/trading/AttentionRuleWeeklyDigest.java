package com.agora.scheduler.trading;

import com.agora.model.AttentionRule;
import com.agora.repository.trading.AttentionRuleRepository;
import com.agora.infra.notification.NotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 每週一 UTC 09:00 (Taipei 17:00) 發送 Attention Rule 週報,幫助人類評估規則效用。
 *
 * <p>分類:
 * <ul>
 *   <li>🔥 <b>近 7 天觸發</b>:hitCount 有更新的規則(依 lastHitAt 降冪)</li>
 *   <li>💤 <b>從未觸發</b>:啟用但 hitCount = 0 的規則(建議檢視條件)</li>
 *   <li>⏰ <b>近 7 天過期</b>:expiresAt 落在 now-7d ~ now 範圍的規則</li>
 * </ul>
 *
 * <p>因 hitCount 是累積值,無法精準算「本週新增幾次」。用 lastHitAt 是否在 7 天內作代理指標。
 * 未來可改用 bt_decision_audit 查 event_type=ATTENTION_HIT 精確統計,此處為 Phase 1 簡化版。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttentionRuleWeeklyDigest {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final AttentionRuleRepository ruleRepo;
    private final NotificationPort notificationPort;

    @Value("${meta-control.attention-weekly-digest.enabled:true}")
    private boolean enabled;

    /** 每週一 09:10 UTC (Taipei 17:10 每週一)。DailyMlPipelineDigest 已錯開到 09:17。 */
    @Scheduled(cron = "0 10 9 * * MON", zone = "UTC")
    public void sendWeeklyDigest() {
        if (!enabled) return;
        try {
            String msg = buildDigest(LocalDateTime.now(ZoneOffset.UTC));
            notificationPort.broadcast(msg, true);
            log.info("[AttentionWeeklyDigest] sent weekly digest");
        } catch (Throwable t) {
            log.error("[AttentionWeeklyDigest] failed: {}", t.getMessage(), t);
        }
    }

    /** 供測試與 MCP `triggerAttentionDigest` 工具直接呼叫(即時查看 digest 內容)。 */
    public String buildDigest(LocalDateTime now) {
        LocalDateTime weekAgo = now.minusDays(7);
        List<AttentionRule> allRules = ruleRepo.findByEnabledTrueOrderByCreatedAtDesc();

        List<AttentionRule> fired   = new ArrayList<>();  // hitCount > 0 且 lastHitAt 在 7d 內
        List<AttentionRule> dormant = new ArrayList<>();  // 啟用但 hitCount 0 或未在 7d 內觸發
        List<AttentionRule> expired = new ArrayList<>();  // 過期在 7d 內

        for (AttentionRule r : allRules) {
            boolean isExpired = r.getExpiresAt() != null && r.getExpiresAt().isBefore(now);
            if (isExpired && r.getExpiresAt().isAfter(weekAgo)) {
                expired.add(r);
            } else if (!isExpired) {
                boolean firedThisWeek = r.getHitCount() != null && r.getHitCount() > 0
                        && r.getLastHitAt() != null && r.getLastHitAt().isAfter(weekAgo);
                if (firedThisWeek) {
                    fired.add(r);
                } else {
                    dormant.add(r);
                }
            }
        }
        fired.sort(Comparator.comparing(AttentionRule::getLastHitAt).reversed());
        dormant.sort(Comparator.comparing(AttentionRule::getCreatedAt));

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 <b>Attention Rule 週報</b> %s%n", now.format(DateTimeFormatter.ISO_LOCAL_DATE)));
        sb.append(String.format("共 %d 條啟用規則%n%n", allRules.size()));

        if (!fired.isEmpty()) {
            sb.append("🔥 <b>近 7 天觸發</b>:\n");
            for (AttentionRule r : fired) {
                sb.append(String.format("  • <code>%s</code>: hits=%d last=%s%n",
                        r.getName(),
                        r.getHitCount(),
                        r.getLastHitAt() != null ? r.getLastHitAt().format(TS_FMT) : "-"));
            }
            sb.append('\n');
        }

        if (!dormant.isEmpty()) {
            sb.append("💤 <b>從未觸發</b> (建議檢視條件):\n");
            for (AttentionRule r : dormant) {
                sb.append(String.format("  • <code>%s</code> [%s/%s]%n",
                        r.getName(), r.getAction(), r.getSeverity()));
            }
            sb.append('\n');
        }

        if (!expired.isEmpty()) {
            sb.append("⏰ <b>近 7 天過期</b>:\n");
            for (AttentionRule r : expired) {
                sb.append(String.format("  • <code>%s</code> (%s 過期)%n",
                        r.getName(),
                        r.getExpiresAt() != null ? r.getExpiresAt().format(TS_FMT) : "-"));
            }
            sb.append('\n');
        }

        if (fired.isEmpty() && dormant.isEmpty() && expired.isEmpty()) {
            sb.append("ℹ️ 目前無任何啟用中的 attention rule");
        }

        return sb.toString();
    }
}
