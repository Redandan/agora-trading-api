package com.agora.scheduler.trading;

import com.agora.service.trading.ScoreBuyPrePositionAutoExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScoreBuyPrePositionAutoExecutionScheduler {

    private final ScoreBuyPrePositionAutoExecutionService service;

    @Value("${trading.score-buy.pre-position.execution.enabled:false}")
    private boolean enabled;

    @Value("${trading.score-buy.pre-position.execution.dry-run:true}")
    private boolean dryRun;

    @Scheduled(
            fixedDelayString = "${trading.score-buy.pre-position.execution.fixed-delay-ms:60000}",
            initialDelayString = "${trading.score-buy.pre-position.execution.initial-delay-ms:60000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            if (dryRun) {
                log.info("[ScoreBuyPrePositionAutoExecution] dry-run status: {}",
                        service.status("BTCUSDT", 485L));
                return;
            }
            log.info("[ScoreBuyPrePositionAutoExecution] execution result: {}",
                    service.executeIfEligible("BTCUSDT", 485L));
        } catch (Exception e) {
            log.warn("[ScoreBuyPrePositionAutoExecution] scheduler failed: {}", e.getMessage(), e);
        }
    }
}
