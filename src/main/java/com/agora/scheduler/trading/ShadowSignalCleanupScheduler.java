package com.agora.scheduler.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 自動 close 過期的 shadow 訊號(auto_traded != 1 且未平倉超過策略 maxHoldingHours)。
 *
 * <p>Disabled by default for split-service deploys; enable
 * {@code shadow-cleanup.enabled=true} only after this service should own
 * shadow signal timeout writes.
 *
 * <p><b>背景</b>:LiveSignalEvaluator 的 #332 dedup gate 用 {@code exit_time IS NULL}
 * 判定「strategy 還有開倉」,以防止同 bar 重複生 entry 污染 ML training。
 * 但 shadow 訊號(auto_traded NULL)走 notify 路徑不真開倉,沒有 OCO 機制,
 * 也沒被 SELL signal 路徑 close → 永遠卡在 exit_time IS NULL → strategy 永遠無法再開新訊號。
 *
 * <p><b>2026-05-05 觀察</b>:#508/#563/#566/#488 4 個 strategy 累積 14+ 筆 stale shadow,
 * 真實 shadow stats 顯示有 alpha 但 live 端無對應 fired。手動清理後恢復。
 *
 * <p><b>本 scheduler</b> 每 30min 跑一次,把:
 * <ul>
 *   <li>auto_traded 不是 1(NULL 或 0)</li>
 *   <li>exit_time IS NULL</li>
 *   <li>created_at &lt; NOW - max(strategy.maxHoldingHours, 24h)</li>
 * </ul>
 * 自動 set exit_time + exit_reason='SHADOW_TIMEOUT'。真倉(auto_traded=1)不影響。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShadowSignalCleanupScheduler {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** Default fallback hours if strategy config doesn't specify maxHoldingHours. */
    @Value("${shadow-cleanup.default-max-hours:24}")
    private int defaultMaxHours;

    @Value("${shadow-cleanup.enabled:false}")
    private boolean enabled;

    @Scheduled(fixedDelay = 1_800_000L, initialDelay = 300_000L)  // 30min, start after 5min
    public void cleanup() {
        if (!enabled) return;
        try {
            // Strategy id → maxHoldingHours from config_json (fallback to default)
            var rows = jdbc.queryForList(
                "SELECT id, config_json FROM bt_strategy WHERE enabled = 1");

            int totalClosed = 0;
            for (var row : rows) {
                Long strategyId = ((Number) row.get("id")).longValue();
                String configJson = (String) row.get("config_json");
                int maxHours = defaultMaxHours;
                try {
                    if (configJson != null && !configJson.isBlank()) {
                        JsonNode node = objectMapper.readTree(configJson);
                        JsonNode mh = node.path("maxHoldingHours");
                        if (mh.isNumber() && mh.asInt() > 0) {
                            maxHours = Math.max(mh.asInt(), defaultMaxHours);
                        }
                    }
                } catch (Exception e) {
                    log.debug("[ShadowCleanup] strategy {} config parse failed: {}",
                            strategyId, e.getMessage());
                }
                LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusHours(maxHours);
                // exit_time = created_at + maxHoldingHours (proper "would have expired" timestamp)
                // 而非 NOW() — 後者會 poison cooldown gate 把 stale shadow 當「剛平倉」算進冷卻 60min。
                int closed = jdbc.update(
                    "UPDATE bt_live_signal " +
                    "SET exit_time = TIMESTAMPADD(HOUR, ?, created_at), " +
                    "    exit_reason = 'SHADOW_TIMEOUT' " +
                    "WHERE strategy_id = ? " +
                    "  AND exit_time IS NULL " +
                    "  AND (auto_traded IS NULL OR auto_traded = 0) " +
                    "  AND created_at < ?",
                    maxHours, strategyId, cutoff);
                if (closed > 0) {
                    totalClosed += closed;
                    log.info("[ShadowCleanup] strategy {} closed {} stale shadow signals (> {}h)",
                            strategyId, closed, maxHours);
                }
            }
            if (totalClosed == 0) {
                log.debug("[ShadowCleanup] no stale shadow signals");
            } else {
                log.info("[ShadowCleanup] total closed: {} stale shadow signals", totalClosed);
            }
        } catch (Throwable t) {
            log.error("[ShadowCleanup] tick failed: {}", t.getMessage(), t);
        }
    }
}
