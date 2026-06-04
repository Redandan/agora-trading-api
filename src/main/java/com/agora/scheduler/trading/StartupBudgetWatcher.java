package com.agora.scheduler.trading;

import com.agora.infra.notification.NotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * #361 Layer 2 — Runtime watcher for Spring Boot {@code ReadinessState}.
 *
 * <p>Logs progress every 10s during startup. Fires TG WARN at 30s if still
 * {@code REFUSING_TRAFFIC}, ERROR at 180s. Helps detect stuck
 * {@code ApplicationRunner} / blocked health probes BEFORE deploy.sh's 240s timeout.
 *
 * <p>Auto-stops once {@code ACCEPTING_TRAFFIC}. No-op after that.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupBudgetWatcher {

    private final NotificationPort notificationPort;
    private final com.agora.config.properties.StartupWatcherProperties props;

    private volatile boolean ready = false;
    private volatile ReadinessState lastReadiness = ReadinessState.REFUSING_TRAFFIC;
    private LocalDateTime startedAt;
    private boolean warnFired = false;
    private boolean errorFired = false;

    @EventListener(ApplicationStartedEvent.class)
    public void onStarted(ApplicationStartedEvent event) {
        if (!props.enabled()) return;
        this.startedAt = LocalDateTime.now();
        log.info("[StartupBudget] watcher armed (warn={}s, error={}s)", props.warnSeconds(), props.errorSeconds());

        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "startup-budget-watcher");
            t.setDaemon(true);
            return t;
        });

        exec.scheduleAtFixedRate(() -> {
            if (ready) {
                exec.shutdown();
                return;
            }
            long elapsedSec = Duration.between(startedAt, LocalDateTime.now()).toSeconds();
            ReadinessState state = lastReadiness;
            log.info("[StartupBudget] elapsed={}s readiness={}", elapsedSec, state);

            if (state == ReadinessState.ACCEPTING_TRAFFIC) {
                ready = true;
                log.info("[StartupBudget] ✅ traffic accepted at {}s", elapsedSec);
                exec.shutdown();
                return;
            }

            if (!warnFired && elapsedSec >= props.warnSeconds()) {
                warnFired = true;
                log.warn("[StartupBudget] ⚠️ stuck REFUSING_TRAFFIC for {}s — possible blocked ApplicationRunner",
                        elapsedSec);
                try {
                    notificationPort.broadcast(String.format(
                            "⚠️ <b>App startup slow</b>\nElapsed: %ds\nReadiness: %s\n→ check ApplicationRunner / DB connection",
                            elapsedSec, state), true);
                } catch (Exception ignore) { /* TG fallback */ }
            }
            if (!errorFired && elapsedSec >= props.errorSeconds()) {
                errorFired = true;
                log.error("[StartupBudget] 🔴 stuck >{}s — deploy.sh will likely time out at 240s",
                        elapsedSec);
                try {
                    notificationPort.broadcast(String.format(
                            "🔴 <b>App startup CRITICAL</b>\nElapsed: %ds (>180s threshold)\nReadiness: %s\n→ deploy will fail soon",
                            elapsedSec, state), true);
                } catch (Exception ignore) { /* TG fallback */ }
            }
        }, props.tickSeconds(), props.tickSeconds(), TimeUnit.SECONDS);
    }

    @EventListener
    public void onAvailabilityChange(AvailabilityChangeEvent<ReadinessState> event) {
        ReadinessState state = event.getState();
        this.lastReadiness = state;
        if (state == ReadinessState.ACCEPTING_TRAFFIC) {
            ready = true;
            if (startedAt != null) {
                long elapsed = Duration.between(startedAt, LocalDateTime.now()).toSeconds();
                log.info("[StartupBudget] readiness changed to ACCEPTING_TRAFFIC at {}s", elapsed);
            }
        }
    }
}
