package com.agora.scheduler.trading;

import com.agora.infra.notification.NotificationPort;
import com.agora.service.TgNotificationDeduper;
import com.agora.service.TgNotificationDeduper.Severity;
import com.agora.service.ml.MlPipelineDigestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * #334 — 監控 PROMOTED ML 模型的近窗 (30d) edge。連續 N 天低於閾值 → TG 提醒考慮重訓。
 *
 * <p>邏輯：每日 06:00 UTC 跑一次，呼叫 {@link MlPipelineDigestService#getPromotedShortWindowEdgePp()}
 * 取近窗 edge，與 {@code drift-alert-pp}（預設 5pp）比較：
 * <ul>
 *   <li>edge < threshold 且距上次紀錄日期 ≥ 1 天 → 連續計數 +1</li>
 *   <li>edge ≥ threshold → 計數歸零</li>
 *   <li>連續 ≥ {@code stalenessConsecutiveDays}（預設 7） → 發 TG，並進入 7 天 cooldown</li>
 *   <li>樣本不足 / eval 失敗 → 不動計數（資訊不足，不誤報）</li>
 * </ul>
 *
 * <p>State 用 in-memory，重啟即清空 — 重啟後重新累積，最多延誤 7 天再次警報，可接受。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MlEdgeStalenessWatcher {

    /** #362 — dedup key, static. DB warm-up reads tg_notification_log row with this source. */
    static final String DEDUP_KEY = "MlEdgeWatcher:lowEdge";

    private final MlPipelineDigestService digestService;
    private final NotificationPort notificationPort;
    private final TgNotificationDeduper deduper;
    private final com.agora.config.properties.MlEdgeWatcherProperties props;

    private int consecutiveLowEdgeDays = 0;
    private LocalDate lastCheckedDate = null;

    /** 每日 06:05 UTC（在 DailyMlPipelineDigest 之後跑，重用其 eval 快取若有）。*/
    @Scheduled(cron = "0 5 6 * * *", zone = "UTC")
    public void tick() {
        if (!props.enabled()) return;
        try {
            check();
        } catch (Throwable t) {
            log.error("[MlEdgeWatcher] tick failed: {}", t.getMessage(), t);
        }
    }

    private void check() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (today.equals(lastCheckedDate)) return;  // 防止同一天多次（手動觸發）疊加
        lastCheckedDate = today;

        Double edgePp = digestService.getPromotedShortWindowEdgePp();
        if (edgePp == null) {
            log.info("[MlEdgeWatcher] no edge data (low samples / no PROMOTED) — count unchanged at {}",
                    consecutiveLowEdgeDays);
            return;
        }

        double threshold = digestService.getDriftAlertPp();
        if (edgePp < threshold) {
            consecutiveLowEdgeDays++;
            log.info("[MlEdgeWatcher] edge={} < {} pp — consecutive low days = {}",
                    String.format("%+.1f", edgePp), threshold, consecutiveLowEdgeDays);
        } else {
            if (consecutiveLowEdgeDays > 0) {
                log.info("[MlEdgeWatcher] edge={} ≥ {} pp — counter reset (was {})",
                        String.format("%+.1f", edgePp), threshold, consecutiveLowEdgeDays);
            }
            consecutiveLowEdgeDays = 0;
            return;
        }

        if (consecutiveLowEdgeDays < props.consecutiveDays()) return;

        // #362 — TgNotificationDeduper replaces lastAlertAt + cooldownDays.
        // Cooldown survives restart via DB warm-up.
        Duration cooldown = Duration.ofDays(props.cooldownDays());
        if (!deduper.shouldSend(DEDUP_KEY, cooldown, Severity.WARN)) {
            log.debug("[MlEdgeWatcher] alert within cooldown — suppress");
            return;
        }
        sendAlert(edgePp, threshold);
    }

    private void sendAlert(double edgePp, double threshold) {
        String msg = String.format(
                "🟡 <b>ML 近窗 edge 持續低迷</b>%n"
                + "PROMOTED 模型 30d edge 連續 <b>%d</b> 天 &lt; <b>%+.1f pp</b>%n"
                + "最新值：<code>%+.1f pp</code>%n%n"
                + "→ 可能是 regime mismatch（v19 訓練偏 trending，近期 SIDEWAYS 不利）。%n"
                + "建議：%n"
                + "1. 看 <code>getModelRegimePerformance</code> 確認近窗 regime 分布%n"
                + "2. 若連續 4 週 edge &lt; 5pp → 考慮重訓（含 SIDEWAYS sample reweight）",
                consecutiveLowEdgeDays, threshold, edgePp);

        log.warn("[MlEdgeWatcher] STALENESS ALERT — consecutive low edge days={} edge={}pp",
                consecutiveLowEdgeDays, edgePp);
        try {
            // sendAlert (not sendMessage) writes log row with source=DEDUP_KEY
            // for the deduper's DB warm-up on next restart.
            notificationPort.alert(msg, true, DEDUP_KEY, "WARN");
        } catch (Exception e) {
            log.warn("[MlEdgeWatcher] TG send failed: {}", e.getMessage());
        }
    }
}
