package com.agora.scheduler.trading;

import com.agora.repository.trading.BtDecisionAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 決策審計 TTL 清理排程。
 *
 * <h3>為何分批刪</h3>
 * bt_decision_audit 日增 3-6K 列,1-2M/年。若直接 {@code DELETE WHERE event_time < cutoff}
 * 在百萬列上會鎖爆 + undo log 爆量。分批 {@code LIMIT 10000} loop 直到清乾淨,每批 tx 獨立。
 *
 * <h3>與 Partition 的關係</h3>
 * V029 建表時已做 MySQL 8 range partition by event_time monthly。Partition DROP 本來是更優的
 * 清理方式(instant,不產生 undo log),但需要 auto-maintain partition 的 scheduler。
 * Phase 1 先用分批 DELETE(相容非 partitioned 表),Phase 2 再上 DROP PARTITION。
 *
 * <h3>執行時間</h3>
 * 每日 UTC 03:30(亞洲午夜後、美洲凌晨前,交易量最低時段)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionAuditCleanupScheduler {

    private final BtDecisionAuditRepository repo;
    private final com.agora.config.properties.AuditCleanupProperties props;

    // @Scheduled 已移至 NightlyCleanupOrchestrator（03:00 UTC 串行執行，避免與其他清理任務同時 lock DB）
    @Transactional
    public void cleanup() {
        if (!props.enabled()) {
            log.debug("[AuditCleanup] disabled by config");
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(props.retentionDays());
        long start = System.currentTimeMillis();
        int totalDeleted = 0;
        int maxIters = 200; // 安全上限:200 × 10K = 2M/次

        log.info("[AuditCleanup] start: cutoff={} batchSize={}", cutoff, props.cleanupBatchSize());
        while (maxIters-- > 0) {
            int n;
            try {
                n = repo.deleteOlderThan(cutoff, props.cleanupBatchSize());
            } catch (Throwable t) {
                log.warn("[AuditCleanup] batch delete failed: {}", t.getMessage());
                break;
            }
            if (n <= 0) break;
            totalDeleted += n;
        }
        long elapsed = System.currentTimeMillis() - start;
        log.info("[AuditCleanup] done: deleted={} elapsed={}ms", totalDeleted, elapsed);
    }
}
