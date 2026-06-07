package com.agora.scheduler.trading;

import com.agora.service.market.PolymarketMonitorService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polymarket 預警排程器。
 *
 * <p>兩個 job：
 * <ol>
 *   <li>每 15 分鐘掃描賠率 + Volume 異動 → 偵測信號 → TG 通知</li>
 *   <li>每 30 分鐘回填 btc_price_4h_later，供 ML 訓練標籤使用</li>
 * </ol>
 *
 * <p>Config（application.yml 可覆蓋）：
 * <ul>
 *   <li>{@code polymarket.monitor.enabled}（預設 false）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "polymarket.monitor.enabled", havingValue = "true", matchIfMissing = false)
public class PolymarketMonitorScheduler {

    private final PolymarketMonitorService monitorService;

    @Value("${polymarket.monitor.enabled:false}")
    private boolean enabled;

    @PostConstruct
    void logConfig() {
        log.info("[PolymarketMonitor] config: enabled={}", enabled);
    }

    /** 每 15 分鐘掃一次，首次延遲 2 分鐘等應用完全啟動 */
    @Scheduled(fixedDelay = 900_000, initialDelay = 120_000)
    public void tick() {
        if (!enabled) return;
        try {
            monitorService.runSnapshot();
        } catch (Throwable t) {
            log.error("[PolymarketMonitor] fatal in tick: {}", t.getMessage(), t);
        }
    }

    /** 每 30 分鐘回填 4h 後 BTC 價格 — 不影響 ML 訓練以外的功能 */
    @Scheduled(fixedDelay = 1_800_000, initialDelay = 300_000)
    public void backfill4h() {
        if (!enabled) return;
        try {
            monitorService.backfill4hBtcPrice();
        } catch (Throwable t) {
            log.error("[PolymarketMonitor] fatal in backfill4h: {}", t.getMessage(), t);
        }
    }

    /** 每 4 小時發送 HIGH 事件彙整；EXTREME 仍維持即時發送。 */
    @Scheduled(cron = "0 10 */4 * * *", zone = "UTC")
    public void sendHighDigest() {
        if (!enabled) return;
        try {
            monitorService.sendHighPriorityDigestIfAny(4);
        } catch (Throwable t) {
            log.error("[PolymarketMonitor] fatal in sendHighDigest: {}", t.getMessage(), t);
        }
    }
}
