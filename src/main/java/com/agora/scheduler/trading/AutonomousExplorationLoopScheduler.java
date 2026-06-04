package com.agora.scheduler.trading;

import com.agora.service.trading.AutonomousExplorationLoopService;
import com.agora.service.trading.AutoExplorationRolloutStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class AutonomousExplorationLoopScheduler {

    private final AutonomousExplorationLoopService loopService;
    private final AutoExplorationRolloutStateService rolloutStateService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${trading.exploration.loop.enabled:false}")
    private boolean enabled;

    @Scheduled(
            fixedDelayString = "${trading.exploration.loop.fixed-delay-ms:60000}",
            initialDelayString = "${trading.exploration.loop.initial-delay-ms:60000}")
    public void runAutonomousExplorationLoop() {
        if (!enabled && !rolloutStateService.effectiveLoopEnabled("BTCUSDT", 574L, "LONG")) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("[AutonomousExplorationLoop] previous run still active; skip");
            return;
        }
        try {
            AutonomousExplorationLoopService.Status status =
                    loopService.runLoopOnce("BTCUSDT", 574L, "LONG");
            log.info("[AutonomousExplorationLoop] state={} blockers={} wouldExecuteNow={} productionEnabled={}",
                    status.currentState(), status.blockers(), status.wouldExecuteNow(), status.productionEnabled());
        } catch (Throwable t) {
            log.error("[AutonomousExplorationLoop] loop failed: {}", t.getMessage(), t);
        } finally {
            running.set(false);
        }
    }
}
