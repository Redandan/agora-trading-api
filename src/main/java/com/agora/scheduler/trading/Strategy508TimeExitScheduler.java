package com.agora.scheduler.trading;

import com.agora.service.trading.Strategy508TimeExitOutcomeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class Strategy508TimeExitScheduler {

    private final Strategy508TimeExitOutcomeService outcomeService;

    @Scheduled(initialDelay = 45_000, fixedDelay = 60_000)
    public void resolveAndExit() {
        try {
            outcomeService.processPending();
        } catch (Exception e) {
            log.error("[508TimeExit] scheduler failed: {}", e.getMessage(), e);
        }
    }
}
