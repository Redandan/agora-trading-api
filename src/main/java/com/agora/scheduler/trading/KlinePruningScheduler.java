package com.agora.scheduler.trading;

import com.agora.config.properties.KlinePruningProperties;
import com.agora.repository.trading.MdKlineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 1 分鐘 K線 30 天滑動窗口清理排程。
 *
 * <h3>為何需要清理</h3>
 * 1min K線解決 Polymarket alerts 的 BTC 價格延遲問題，但若無上限，存儲成本線性增長。
 * 保留 30 天（43.2K 行/symbol）足以覆蓋大多數交易回測 + 價格注入場景，超過則無交易價值。
 *
 * <h3>存儲成本估算</h3>
 * - 每行 ~850 bytes（含索引）
 * - 30 天: 1440 bars/day × 30 days = 43.2K rows/symbol ≈ 17 MB/symbol
 * - 當前關注: BTCUSDT + ETHUSDT = ~34 MB（可接受）
 * - 若來日擴展至 20+ 交易對，考慮 MySQL 8 partition strategy（DROP PARTITION 比 DELETE 快 100 倍）
 *
 * <h3>為何只清理 1min</h3>
 * 1h 和 4h 用於策略回測和歷史驗證，保留無上限。
 * 1min 純用於實時價格注入，舊數據無價值。
 *
 * <h3>執行時間</h3>
 * 每日 UTC 04:00（亞洲午夜後、美洲凌晨前，交易量最低時段）。
 * 估算耗時 < 5 秒（DELETE 帶 INDEX 無鎖表）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlinePruningScheduler {

    private final MdKlineRepository klineRepository;
    private final KlinePruningProperties props;

    // No scheduler owns this in the split repo yet; keep deletion opt-in.
    @Transactional
    public void pruneOldMinuteKlines() {
        if (!props.enabled()) {
            log.debug("[KlinePruning] disabled by config");
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(props.retentionDays());
        long start = System.currentTimeMillis();

        log.info("[KlinePruning] start: retention={}d cutoff={}", props.retentionDays(), cutoff);
        try {
            int deleted = klineRepository.deleteByIntervalCodeAndOpenTimeBefore("1m", cutoff);
            long elapsed = System.currentTimeMillis() - start;
            log.info("[KlinePruning] done: deleted={} rows elapsed={}ms", deleted, elapsed);
        } catch (Exception e) {
            log.error("[KlinePruning] failed: {}", e.getMessage(), e);
        }
    }
}
