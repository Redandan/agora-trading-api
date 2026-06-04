package com.agora.scheduler.trading;

import com.agora.service.trading.TinyLiveExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controlled tiny-live auto-execution trigger.
 *
 * <p>This scheduler owns no trading logic. It only calls the existing
 * TinyLiveExecutionService path, which re-runs preview, AutoApprovalPolicy,
 * token, cap, OCO, evidence, and budget gates before any order can be sent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TinyLiveAutoExecutionScheduler {

    private final TinyLiveExecutionService tinyLiveExecutionService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${trading.tiny-live.auto-execution.enabled:false}")
    private boolean enabled;

    @Value("${trading.tiny-live.auto-execution.dry-run:true}")
    private boolean dryRun;

    @Scheduled(
            fixedDelayString = "${trading.tiny-live.auto-execution.fixed-delay-ms:60000}",
            initialDelayString = "${trading.tiny-live.auto-execution.initial-delay-ms:30000}")
    public void runControlledTinyLiveSweep() {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("[TinyLiveAutoExecution] previous sweep still running; skip");
            return;
        }
        try {
            if (dryRun) {
                String preview = tinyLiveExecutionService.previewAutoExecution("BTCUSDT", 574L, "LONG");
                log.info("[TinyLiveAutoExecution] dry-run preview: {}", compact(preview));
                return;
            }
            String result = tinyLiveExecutionService.executeAutoApprovedTinyLiveIfEligible();
            log.info("[TinyLiveAutoExecution] execution sweep result: {}", compact(result));
        } catch (Throwable t) {
            log.error("[TinyLiveAutoExecution] sweep failed: {}", t.getMessage(), t);
        } finally {
            running.set(false);
        }
    }

    private String compact(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }
}
