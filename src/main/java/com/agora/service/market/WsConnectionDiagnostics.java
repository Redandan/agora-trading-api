package com.agora.service.market;

import java.time.Duration;
import java.time.Instant;

/** Observes one connection without scheduling, reconnecting, or sending anything. */
public final class WsConnectionDiagnostics {
    private Instant disconnectedAt;
    private Instant lastMessageAt;
    private long failures;
    private String lastFailureType = "NONE";

    public synchronized void failed(Throwable error, Instant now) {
        failures++;
        lastFailureType = error == null ? "UNKNOWN" : error.getClass().getSimpleName();
        disconnected(now);
    }

    public synchronized void disconnected(Instant now) {
        if (disconnectedAt == null) disconnectedAt = now;
    }

    public synchronized void received(Instant now) {
        lastMessageAt = now;
    }

    /** Recovery means subscription acknowledgement, not merely a TCP connection. */
    public synchronized long ready(Instant now) {
        long elapsed = disconnectedAt == null ? 0
                : Math.max(0, Duration.between(disconnectedAt, now).toMillis());
        disconnectedAt = null;
        return elapsed;
    }

    public synchronized String failureContext(Integer httpStatus) {
        // Do not include provider bodies, headers, credentials, or arbitrary exception text.
        return "type=" + lastFailureType + " httpStatus=" + httpStatus
                + " failures=" + failures + " disconnectedAt=" + disconnectedAt
                + " lastMessageAt=" + lastMessageAt;
    }
}
