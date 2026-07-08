package com.agora.scheduler.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.infra.notification.NotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 每 10 分鐘掃描 notifiedAt = NULL 的 bt_live_signal 記錄並重試 TG 通知。
 *
 * <p>這些記錄是由 LiveSignalEvaluator Phase 1 save 成功但 Phase 2 TG 發送失敗所留下的。
 * 等待 5 分鐘後才重試（避免與原始發送過於接近）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "trading.live-signal.retry-notification.enabled", havingValue = "true", matchIfMissing = false)
public class LiveSignalRetryScheduler {

    private static final int RETRY_DELAY_MINUTES = 5;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BtLiveSignalRepository liveSignalRepository;
    private final NotificationPort notificationPort;

    @Value("${trading.live-signal.retry-notification.enabled:false}")
    private boolean enabled;

    @Scheduled(cron = "0 */10 * * * ?")
    public void retryPending() {
        if (!enabled) {
            log.debug("[LiveSignalRetry] disabled by trading.live-signal.retry-notification.enabled=false");
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(RETRY_DELAY_MINUTES);
        List<BtLiveSignal> pending = liveSignalRepository
                .findByNotifiedAtIsNullAndCreatedAtBefore(cutoff);

        if (pending.isEmpty()) return;

        log.info("[LiveSignalRetry] Found {} pending notification(s)", pending.size());

        for (BtLiveSignal signal : pending) {
            try {
                String msg = buildRetryMessage(signal);
                notificationPort.broadcast(msg, true);
                signal.setNotifiedAt(LocalDateTime.now(ZoneOffset.UTC));
                liveSignalRepository.save(signal);
                log.info("[LiveSignalRetry] Retry OK: id={} symbol={} bar={}",
                        signal.getId(), signal.getSymbol(),
                        signal.getBarOpenTime().format(FMT));
            } catch (Exception e) {
                log.error("[LiveSignalRetry] Retry failed: id={} error={}",
                        signal.getId(), e.getMessage());
            }
        }
    }

    private String buildRetryMessage(BtLiveSignal s) {
        String barTime = s.getBarOpenTime().plusHours(8).format(FMT);
        boolean isShort = "SHORT".equals(s.getSide());
        // 距離% 取絕對值，方向透過標籤說明
        double slPct = s.getSuggestedSl() != null
                ? Math.abs(s.getSuggestedSl().doubleValue() / s.getEntryPrice().doubleValue() - 1.0) * 100 : 5.0;
        double tpPct = s.getSuggestedTp() != null
                ? Math.abs(s.getSuggestedTp().doubleValue() / s.getEntryPrice().doubleValue() - 1.0) * 100 : 10.0;

        String scoreLine = s.getScore() != null && s.getScore().doubleValue() > 0
                ? String.format("📊 Score: <b>%.3f</b>  NN: <b>%.3f</b>\n",
                        s.getScore().doubleValue(), s.getNnOutput().doubleValue())
                : "";

        String header = isShort ? "📉 做空候選" : "🟡 買入候選";
        String slDir  = isShort ? "+" : "-";
        String tpDir  = isShort ? "-" : "+";

        return String.format(
            "%s <b>%s (%s)</b>  <i>(補送)</i>\n\n" +
            "📅 K線: %s (UTC+8)\n" +
            "💰 收盤價: <b>$%s</b>\n\n" +
            "%s" +
            "🛡 建議止損: $%s (%s%.1f%%)\n" +
            "🎯 建議止盈: $%s (%s%.1f%%)",
            header, s.getSymbol(), s.getIntervalCode().toUpperCase(),
            barTime,
            formatPrice(s.getEntryPrice().doubleValue()),
            scoreLine,
            s.getSuggestedSl() != null ? formatPrice(s.getSuggestedSl().doubleValue()) : "N/A", slDir, slPct,
            s.getSuggestedTp() != null ? formatPrice(s.getSuggestedTp().doubleValue()) : "N/A", tpDir, tpPct
        );
    }

    private String formatPrice(double price) {
        return price >= 1000
                ? String.format("%,.2f", price)
                : String.format("%.4f", price);
    }
}
