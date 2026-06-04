package com.agora.scheduler.trading;

import com.agora.service.trading.ScoreBuyConfirmedDeployAutoExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScoreBuyConfirmedDeployAutoExecutionScheduler {

    private final ScoreBuyConfirmedDeployAutoExecutionService service;

    @Value("${trading.score-buy.confirmed-deploy.execution.enabled:false}")
    private boolean enabled;

    @Value("${trading.score-buy.confirmed-deploy.execution.dry-run:true}")
    private boolean dryRun;

    @Scheduled(
            fixedDelayString = "${trading.score-buy.confirmed-deploy.execution.fixed-delay-ms:60000}",
            initialDelayString = "${trading.score-buy.confirmed-deploy.execution.initial-delay-ms:120000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            if (dryRun) {
                log.info("[ScoreBuyConfirmedDeploy] dry-run status: {}",
                        service.status("BTCUSDT", 485L));
                return;
            }
            log.info("[ScoreBuyConfirmedDeploy] execution result: {}",
                    service.executeIfEligible("BTCUSDT", 485L));
        } catch (Exception e) {
            log.warn("[ScoreBuyConfirmedDeploy] scheduler failed: {}", e.getMessage(), e);
        }
    }
}
