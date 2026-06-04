package com.agora.scheduler.trading;

import com.agora.service.trading.AutoExplorationRolloutControllerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class AutoExplorationRolloutScheduler {

    private final AutoExplorationRolloutControllerService controllerService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${trading.exploration.rollout.auto-enabled:false}")
    private boolean enabled;

    @Scheduled(
            fixedDelayString = "${trading.exploration.rollout.fixed-delay-ms:300000}",
            initialDelayString = "${trading.exploration.rollout.initial-delay-ms:90000}")
    public void runAutoExplorationRollout() {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("[AutoExplorationRollout] previous run still active; skip");
            return;
        }
        try {
            AutoExplorationRolloutControllerService.Status status =
                    controllerService.evaluateAndAdvance("BTCUSDT", 574L, "LONG");
            log.info("[AutoExplorationRollout] stage={} recommended={} canAutoPromote={} blockers={}",
                    status.currentStage(), status.recommendedStage(), status.canAutoPromote(), status.promotionBlockers());
        } catch (Throwable t) {
            log.error("[AutoExplorationRollout] scheduler failed: {}", t.getMessage(), t);
        } finally {
            running.set(false);
        }
    }
}
