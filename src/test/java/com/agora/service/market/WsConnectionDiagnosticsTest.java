package com.agora.service.market;

import org.junit.jupiter.api.Test;
import java.io.EOFException;
import java.time.Instant;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import static org.junit.jupiter.api.Assertions.*;

class WsConnectionDiagnosticsTest {
    @Test
    void nullMessageHasTypeAndDowntimeCoversAllFailedReconnects() {
        var diagnostics = new WsConnectionDiagnostics();
        Instant start = Instant.parse("2026-10-02T10:00:00Z");
        diagnostics.received(start.minusSeconds(10));
        diagnostics.failed(new EOFException(), start);
        diagnostics.failed(new IllegalStateException("credential must not appear"), start.plusSeconds(5));
        String context = diagnostics.failureContext(503);
        assertTrue(context.contains("type=IllegalStateException"));
        assertTrue(context.contains("failures=2"));
        assertTrue(context.contains("lastMessageAt=2026-10-02T09:59:50Z"));
        assertFalse(context.contains("credential"));
        assertEquals(15000, diagnostics.ready(start.plusSeconds(15)));
        assertEquals(0, diagnostics.ready(start.plusSeconds(16)));
    }

    @Test
    void reconnectDoesNotAccumulateHeartbeatTasksAndStopRemovesTheLastOne() {
        var scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        try {
            var heartbeat = new WsHeartbeat(scheduler, 86400);
            for (int i = 0; i < 100; i++) heartbeat.start(() -> fail("must not ping in this test"));
            assertEquals(1, scheduler.getQueue().size());
            heartbeat.stop();
            assertEquals(0, scheduler.getQueue().size());
        } finally {
            scheduler.shutdownNow();
        }
    }
}
