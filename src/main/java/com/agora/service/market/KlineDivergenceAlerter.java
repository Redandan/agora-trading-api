package com.agora.service.market;

import com.agora.infra.notification.NotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享的 K 線跨源偏差告警邏輯,被以下兩處共用:
 * <ul>
 *   <li>{@link com.agora.listener.KlineDivergenceListener} — 即時 listener,套用 60s 去重</li>
 *   <li>{@link com.agora.scheduler.trading.KlineDivergenceMonitor} — 手動 scan,不去重(ad hoc 探查)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineDivergenceAlerter {

    private final NotificationPort notificationPort;
    private final com.agora.config.properties.KlineDivergenceProperties props;

    /** key = "BTCUSDT:1h", value = epoch ms of last alert. 上限 = symbols×intervals ≈ 8,不需 evict。 */
    private final ConcurrentHashMap<String, Long> lastAlertAt = new ConcurrentHashMap<>();

    public double getWarnPct() { return props.warnPct(); }
    public double getCriticalPct() { return props.criticalPct(); }

    public static double diffPct(BigDecimal binClose, BigDecimal okxClose) {
        if (okxClose == null || okxClose.signum() == 0) return 0;
        return Math.abs(binClose.doubleValue() - okxClose.doubleValue())
                / okxClose.doubleValue() * 100;
    }

    /**
     * 即時單筆告警(60s 去重)。回傳 true 表示實際送出告警。
     */
    public boolean tryAlertSingle(String symbol, String intervalCode, LocalDateTime openTime,
                                   BigDecimal binClose, BigDecimal okxClose,
                                   BigDecimal binVol, BigDecimal okxVol) {
        if (!props.enabled()) {
            return false;
        }
        double diff = diffPct(binClose, okxClose);
        DivergenceLevel level = classify(diff, binVol, okxVol);
        if (level == DivergenceLevel.OK) {
            return false;
        }

        String sample = formatSample(symbol, intervalCode, openTime, binClose, okxClose, diff, binVol, okxVol);
        if (level == DivergenceLevel.THIN_SOURCE) {
            log.info("[KlineDivergence] THIN_SOURCE downgraded (event-driven): {}", sample);
            return false;
        }

        String key = symbol + ":" + intervalCode;
        long now = Instant.now().toEpochMilli();
        long windowMs = props.dedupWindowSeconds() * 1000;
        // Atomic check-and-set: keep old timestamp if still inside window, else replace with now.
        // 防止 @Async listener 對同 key 重疊執行時雙發 TG。
        long stored = lastAlertAt.merge(key, now,
                (old, fresh) -> (fresh - old) < windowMs ? old : fresh);
        if (stored != now) {
            log.debug("[KlineDivergence] dedup suppressed {} (last={}ms ago, level={})",
                    key, now - stored, level);
            return false;
        }

        log.info("[KlineDivergence] {} (event-driven): {}", level, sample);

        sendBatchAlert(level.name(), List.of(sample), 0, List.of());
        return true;
    }

    public DivergenceLevel classify(double diffPct, BigDecimal binVol, BigDecimal okxVol) {
        if (diffPct < props.warnPct()) {
            return DivergenceLevel.OK;
        }
        if (isThinSourceDivergence(binVol, okxVol)) {
            return DivergenceLevel.THIN_SOURCE;
        }
        if (diffPct >= props.criticalPct()) {
            return DivergenceLevel.CRITICAL;
        }
        return DivergenceLevel.WARN;
    }

    public String formatSample(String symbol, String intervalCode, LocalDateTime openTime,
                               BigDecimal binClose, BigDecimal okxClose, double diff,
                               BigDecimal binVol, BigDecimal okxVol) {
        return String.format("%s@%s %s: bin=%.2f okx=%.2f 差=%.3f%% (binVol=%.2f okxVol=%.2f)",
                symbol, intervalCode, openTime, binClose, okxClose, diff, binVol, okxVol);
    }

    private boolean isThinSourceDivergence(BigDecimal binVol, BigDecimal okxVol) {
        if (!props.thinSourceDowngradeEnabled() || binVol == null || okxVol == null) {
            return false;
        }
        double bin = Math.max(0.0, binVol.doubleValue());
        double okx = Math.max(0.0, okxVol.doubleValue());
        double min = Math.min(bin, okx);
        double max = Math.max(bin, okx);
        if (min >= props.thinSourceMinVolume()) {
            return false;
        }
        if (min == 0.0) {
            return max > 0.0;
        }
        return (max / min) >= props.thinSourceMaxVolumeRatio();
    }

    /** 批次告警(manual scan 用,不去重)。 */
    public void sendBatchAlert(String level, List<String> samples,
                                int extraWarnCount, List<String> extraWarnSamples) {
        if (samples.isEmpty()) return;
        String icon = "CRITICAL".equals(level) ? "🚫" : "⚠️";
        double threshold = "CRITICAL".equals(level) ? props.criticalPct() : props.warnPct();

        StringBuilder msg = new StringBuilder();
        msg.append(icon).append(" <b>K 線跨源偏差 ").append(level).append("</b>\n");
        msg.append("檢測到 ").append(samples.size()).append(" 筆 Binance vs OKX close 差異 > ")
           .append(threshold).append("%:\n\n");
        int show = Math.min(5, samples.size());
        for (int i = 0; i < show; i++) {
            msg.append("• ").append(samples.get(i)).append("\n");
        }
        if (samples.size() > show) {
            msg.append("… 另 ").append(samples.size() - show).append(" 筆\n");
        }
        if (extraWarnCount > 0) {
            msg.append("\n另含 ").append(extraWarnCount).append(" 筆 WARN 級別\n");
        }
        msg.append("\n實盤執行於 OKX;若偏差持續擴大,考慮人工檢視 Binance.us 流動性或暫停交易。");
        try {
            notificationPort.broadcast(msg.toString(), true);
        } catch (Exception e) {
            log.error("[KlineDivergence] TG alert failed: {}", e.getMessage());
        }
    }

    public enum DivergenceLevel {
        OK,
        THIN_SOURCE,
        WARN,
        CRITICAL
    }
}
