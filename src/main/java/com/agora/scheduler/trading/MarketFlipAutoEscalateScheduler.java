package com.agora.scheduler.trading;

import com.agora.model.MarketFlipEvent;
import com.agora.repository.trading.MarketFlipEventRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.meta.DecisionAuditWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 每 15 分鐘掃 PENDING {@link MarketFlipEvent},超過 escalate-age-minutes (預設 60) 未處理就:
 *   1. 發 TG 告警 (「AI 沒來審,系統自動升級」)
 *   2. 標記 status = AUTO_ESCALATED
 *   3. 寫 audit
 *
 * <p>用意:Claude scheduled task 可能因為 claude.ai session 掛、網路問題或 cron skip 沒跑;
 * 此 scheduler 是 fallback safety net。
 *
 * <p>Config: {@code meta-control.market-flip.auto-escalate-enabled} (預設 false)
 *           {@code meta-control.market-flip.escalate-age-minutes} (預設 60)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketFlipAutoEscalateScheduler {

    private final MarketFlipEventRepository eventRepo;
    private final NotificationPort notificationPort;
    private final DecisionAuditWriter auditWriter;
    private final com.agora.config.properties.MarketFlipProperties props;

    @Scheduled(fixedDelay = 900_000, initialDelay = 300_000)  // 每 15min, 啟動 5min 後首跑
    public void tick() {
        if (!props.autoEscalateEnabled()) return;
        try {
            LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(props.escalateAgeMinutes());
            List<MarketFlipEvent> stale = eventRepo.findStaleEvents(cutoff);
            if (stale.isEmpty()) return;

            log.warn("[MarketFlipEscalate] 發現 {} 筆老化 PENDING events (age > {}min),升級發 TG",
                    stale.size(), props.escalateAgeMinutes());

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("⏰ <b>Market Flip Auto-Escalate</b>%n"));
            sb.append(String.format("%d 筆 flip event 超過 %d 分鐘未被 AI 審閱:%n%n",
                    stale.size(), props.escalateAgeMinutes()));
            for (MarketFlipEvent e : stale) {
                long age = java.time.Duration.between(e.getDetectedAt(),
                        LocalDateTime.now(ZoneOffset.UTC)).toMinutes();
                sb.append(String.format("• [%d] %s/%s %s→%s (Δ=%s, age=%dmin)%n",
                        e.getId(), e.getSymbol(), e.getIndicator(),
                        e.getPrevValue().toPlainString(),
                        e.getCurrentValue().toPlainString(),
                        e.getDeltaValue().toPlainString(), age));
                e.setStatus("AUTO_ESCALATED");
                e.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
                eventRepo.save(e);
                auditWriter.logOverrideApplied(null, e.getSymbol(), "MarketFlip.AutoEscalate",
                        "eventId=" + e.getId() + " age=" + age + "min (AI 未審,scheduler 升級)");
            }
            sb.append("\n建議手動跑 <code>listPendingFlipEvents</code> 接手處理。");

            try {
                notificationPort.broadcast(sb.toString(), true);
            } catch (Exception e) {
                log.warn("[MarketFlipEscalate] TG send failed: {}", e.getMessage());
            }
        } catch (Throwable t) {
            log.error("[MarketFlipEscalate] tick failed: {}", t.getMessage(), t);
        }
    }
}
