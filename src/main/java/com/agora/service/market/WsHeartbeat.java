package com.agora.service.market;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Owns at most one pending heartbeat for a subscription across reconnects. */
final class WsHeartbeat {
    private final ScheduledExecutorService scheduler;
    private final long intervalSeconds;
    private ScheduledFuture<?> task;

    WsHeartbeat(ScheduledExecutorService scheduler, long intervalSeconds) {
        this.scheduler = scheduler;
        this.intervalSeconds = intervalSeconds;
    }

    synchronized void start(Runnable ping) {
        stop();
        task = scheduler.scheduleAtFixedRate(ping, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    synchronized void stop() {
        if (task != null) task.cancel(false);
        task = null;
    }
}
