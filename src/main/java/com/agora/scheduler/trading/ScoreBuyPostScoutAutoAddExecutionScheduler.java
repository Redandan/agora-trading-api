package com.agora.scheduler.trading;

import com.agora.service.trading.ScoreBuyPostScoutAutoAddExecutionService;
import com.agora.service.trading.ScoreBuyPostScoutAutoAddSchedulerStateService;
import com.agora.service.trading.ScoreBuyPostScoutNearTriggerAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScoreBuyPostScoutAutoAddExecutionScheduler {

    private final ScoreBuyPostScoutAutoAddExecutionService service;
    private final ScoreBuyPostScoutAutoAddSchedulerStateService stateService;
    private final ScoreBuyPostScoutNearTriggerAlertService nearTriggerAlertService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${trading.score-buy.post-scout-add.execution.enabled:false}")
    private boolean enabled;

    @Value("${trading.score-buy.post-scout-add.execution.dry-run:true}")
    private boolean dryRun;

    @Scheduled(
            fixedDelayString = "${trading.score-buy.post-scout-add.execution.fixed-delay-ms:60000}",
            initialDelayString = "${trading.score-buy.post-scout-add.execution.initial-delay-ms:90000}")
    public void tick() {
        if (!enabled) {
            stateService.markDisabled();
            return;
        }
        if (!running.compareAndSet(false, true)) {
            stateService.markSkippedOverlap();
            log.debug("[ScoreBuyPostScoutAutoAdd] previous scheduler tick still running; skip");
            return;
        }
        String mode = dryRun ? "DRY_RUN_STATUS" : "EXECUTION";
        stateService.markStarted(mode);
        try {
            if (dryRun) {
                String status = service.status("BTCUSDT", 485L);
                stateService.markCompleted(mode, compact(status), false, false);
                notifyNearTrigger(status);
                log.info("[ScoreBuyPostScoutAutoAdd] dry-run status: {}", compact(status));
                return;
            }
            String result = service.executeIfEligible("BTCUSDT", 485L);
            stateService.markCompleted(mode, compact(result), contains(result, "orderSent", "true"),
                    contains(result, "ocoAttached", "true"));
            notifyNearTrigger(result);
            log.info("[ScoreBuyPostScoutAutoAdd] execution result: {}", compact(result));
        } catch (Exception e) {
            stateService.markFailed(mode, e.getMessage());
            log.warn("[ScoreBuyPostScoutAutoAdd] scheduler failed: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    private boolean contains(String value, String key, String expected) {
        if (value == null) return false;
        String compact = value.replace("\"", "").replace(" ", "");
        return compact.contains(key + "=" + expected) || compact.contains(key + ":" + expected);
    }

    private void notifyNearTrigger(String statusOrResult) {
        try {
            nearTriggerAlertService.maybeNotify(statusOrResult);
        } catch (Exception e) {
            log.warn("[ScoreBuyPostScoutAutoAdd] near-trigger notification failed: {}", e.getMessage(), e);
        }
    }

    private String compact(String value) {
        if (value == null) return "null";
        return value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }
}
