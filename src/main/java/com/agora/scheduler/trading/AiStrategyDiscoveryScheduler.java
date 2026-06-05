package com.agora.scheduler.trading;

import com.agora.config.properties.AiStrategyDiscoveryProperties;
import com.agora.dto.backtest.AiStrategyDiscoveryRequest;
import com.agora.dto.backtest.AiStrategyDiscoveryResponse;
import com.agora.service.ai.AiStrategyDiscoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 策略自動探勘排程器
 * <p>
 * 根據 {@code ai.strategy.discovery.enabled} 設定決定是否啟用定時探勘。
 * 預設每天凌晨 3 點執行一次，可透過 {@code ai.strategy.discovery.cron} 調整排程。
 * <p>
 * 探勘結果（含最佳策略 ID）會記錄在 log；
 * 生成的策略以 {@code aiGenerated=true} 標記儲存在 {@code bt_strategy} 表中，
 * 供後續透過 {@code GET /backtests/strategies/query} 過濾查詢。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiStrategyDiscoveryScheduler {

    private final AiStrategyDiscoveryService discoveryService;
    private final AiStrategyDiscoveryProperties props;

    /**
     * 定時觸發 AI 策略探勘（預設每天凌晨 3 點，使用伺服器預設時區）。
     * 可透過 application.properties 設定以下屬性覆蓋：
     * - {@code ai.strategy.discovery.cron}：Cron 表達式（預設 "0 0 3 * * ?"）
     * - 如需指定時區，可搭配 {@code spring.task.scheduling.pool.size} 或
     *   在部署時設定 JVM 的 {@code -Duser.timezone=Asia/Taipei}
     */
    // 預設 05:30 UTC（避開夜間 DB 維護窗口 + Groq/backtest 資源競爭）
    @Scheduled(cron = "${ai.strategy.discovery.cron:0 30 5 * * ?}")
    public void scheduledDiscovery() {
        if (!props.enabled()) {
            log.debug("[AI探勘排程] ai.strategy.discovery.enabled=false，跳過本次探勘");
            return;
        }

        log.info("[AI探勘排程] 開始定時 AI 策略探勘，symbol={}, intervalCode={}", props.symbol(), props.intervalCode());
        try {
            AiStrategyDiscoveryRequest request = buildRequest();
            AiStrategyDiscoveryResponse response = discoveryService.discover(request);

            if (response.getBestStrategy() != null) {
                log.info("[AI探勘排程] 探勘完成，批次={}, 最佳策略={}, score={}, totalReturn={}",
                        response.getDiscoveryBatch(),
                        response.getBestStrategy().getStrategyName(),
                        response.getBestStrategy().getScore(),
                        response.getBestStrategy().getTotalReturn());
            } else {
                log.warn("[AI探勘排程] 探勘完成，批次={}, 但未找到有效策略", response.getDiscoveryBatch());
            }
        } catch (Exception e) {
            log.error("[AI探勘排程] AI 策略探勘定時任務執行失敗", e);
        }
    }

    private AiStrategyDiscoveryRequest buildRequest() {
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(props.lookbackDays());

        AiStrategyDiscoveryRequest request = new AiStrategyDiscoveryRequest();
        request.setSymbol(props.symbol());
        request.setIntervalCode(props.intervalCode());
        request.setStartTime(startTime);
        request.setEndTime(endTime);
        request.setInitialCapital(new BigDecimal(props.initialCapital()));
        request.setCandidateCount(props.candidateCount());
        return request;
    }
}
