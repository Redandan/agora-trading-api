package com.agora.scheduler.trading;

import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.service.BtStrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 每日清理 AI 探勘留下的廢棄策略 record。預設規則:
 * enabled=false 且 createdAt < N 天前 且 name 以指定 prefix 開頭。
 *
 * <p><b>動機</b>:{@code validateCandidates} 與 {@code runAdaptiveDiscovery} 每次
 * 執行都會在 bt_strategy 建 record。手動 {@code cleanupStrategies} 易遺漏,
 * 不清會無限累積污染 listStrategies 結果(2026-04-14 一次清過 38 筆)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EphemeralStrategyCleanupScheduler {

    private final BtStrategyRepository strategyRepository;
    private final BtStrategyService strategyService;
    private final com.agora.config.properties.EphemeralCleanupProperties props;

    /**
     * 由 {@link NightlyCleanupOrchestrator} 在 UTC 03:00 串行排程呼叫。
     * （原本 03:30 獨立 @Scheduled，已合并至 Orchestrator 集中管理）
     */
    public void cleanupOnSchedule() {
        if (!props.enabled()) {
            log.debug("[EphemeralCleanup] Disabled, skipping");
            return;
        }
        run();
    }

    /** 給 ops / MCP 手動觸發用(若需要)。 */
    public String runManual() {
        return run();
    }

    private String run() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(props.retainDays());
        Set<String> prefixSet = Arrays.stream(props.prefixes().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        List<BtStrategy> targets = strategyRepository.findByEnabled(false).stream()
                .filter(s -> s.getName() != null
                        && prefixSet.stream().anyMatch(p -> s.getName().startsWith(p))
                        && s.getCreatedAt() != null
                        && s.getCreatedAt().isBefore(cutoff))
                .toList();

        if (targets.isEmpty()) {
            String msg = String.format(
                    "[EphemeralCleanup] No stale strategies (cutoff=%s prefixes=%s)",
                    cutoff, prefixSet);
            log.info(msg);
            return msg;
        }

        int deleted = 0;
        for (BtStrategy s : targets) {
            try {
                String reason = String.format(
                        "排程清理 stale 策略（enabled=false, createdAt<%s, prefixes=%s）",
                        cutoff, prefixSet);
                strategyService.deleteStrategy(s.getId(), "SYSTEM", reason);
                deleted++;
            } catch (Exception e) {
                log.warn("[EphemeralCleanup] Delete failed id={} name={}: {}",
                        s.getId(), s.getName(), e.getMessage());
            }
        }
        String msg = String.format(
                "[EphemeralCleanup] Deleted %d/%d stale strategies (prefixes=%s retainDays=%d)",
                deleted, targets.size(), prefixSet, props.retainDays());
        log.info(msg);
        return msg;
    }
}
