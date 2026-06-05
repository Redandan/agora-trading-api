package com.agora.scheduler.trading;

import com.agora.infra.notification.NotificationPort;
import com.agora.service.ml.MlPipelineDigestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日 UTC 09:17 (Taipei 17:17) 推送 ML 管線進度 digest 到 TG。
 *
 * <p>實際報告內容由 {@link MlPipelineDigestService#buildDigest(boolean)} 產出;
 * 本 class 只負責:cron 排程 + 呼叫 service + 發 TG + 錯誤吞噬。同份 digest
 * 也由 MCP 工具 {@code getDailyMlPipelineDigest} 供 on-demand 查詢使用。
 *
 * <p>Config:
 * <ul>
 *   <li>{@code meta-control.daily-ml-digest.enabled} 預設 false</li>
 *   <li>{@code meta-control.daily-ml-digest.cron} 預設 {@code 0 17 9 * * *}(UTC 每日 09:17)</li>
 *   <li>09:17 故意錯開整點 market-indicator / verifier schedulers，避免 HeatWave
 *       training 與 hourly collectors 同時搶 DB connection。</li>
 *   <li>其餘 digest 參數(drift-window-days / promote-lift-pp / ...)見 {@link MlPipelineDigestService}</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "meta-control.daily-ml-digest.enabled",
        havingValue = "true", matchIfMissing = false)
public class DailyMlPipelineDigest {

    private final MlPipelineDigestService digestService;
    private final NotificationPort notificationPort;

    @Scheduled(cron = "${meta-control.daily-ml-digest.cron:0 17 9 * * *}", zone = "UTC")
    public void dailyDigest() {
        try {
            String text = digestService.buildDigest(/*triggerTraining=*/ true);
            notificationPort.broadcast(text, /*html=*/ true);
            log.info("[DailyMlDigest] sent ({} chars)", text.length());
            // Persist KPI snapshot to ml_pipeline_progress_log for history queries.
            // Failures inside persistDailyProgress() are swallowed — never blocks digest.
            digestService.persistDailyProgress();
        } catch (Throwable t) {
            log.error("[DailyMlDigest] fatal", t);
            try {
                notificationPort.broadcast("🤖💥 <b>ML Daily Digest fatal</b>\n" + safeErr(t), true);
            } catch (Exception ignored) {}
        }
    }

    private String safeErr(Throwable t) {
        String m = t.getMessage();
        if (m == null) return t.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0, 200) + "..." : m;
    }
}
