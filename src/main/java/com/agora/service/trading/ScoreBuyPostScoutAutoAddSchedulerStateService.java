package com.agora.service.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ScoreBuyPostScoutAutoAddSchedulerStateService {

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.initial());
    private final AtomicLong tickCounter = new AtomicLong();
    private final AtomicLong skippedOverlapCounter = new AtomicLong();

    public void markDisabled() {
        snapshot.set(snapshot.get().withStatus("DISABLED", "scheduler disabled", Instant.now()));
    }

    public void markStarted(String mode) {
        long tick = tickCounter.incrementAndGet();
        Snapshot current = snapshot.get();
        snapshot.set(new Snapshot(
                tick,
                skippedOverlapCounter.get(),
                Instant.now(),
                current.lastCompletedAtUtc(),
                "STARTED",
                mode,
                "scheduler tick started",
                false,
                false,
                null));
    }

    public void markSkippedOverlap() {
        long skipped = skippedOverlapCounter.incrementAndGet();
        Snapshot current = snapshot.get();
        snapshot.set(new Snapshot(
                current.tickCount(),
                skipped,
                current.lastTickAtUtc(),
                Instant.now(),
                "SKIPPED_OVERLAP",
                current.lastMode(),
                "previous scheduler tick still running",
                false,
                false,
                null));
    }

    public void markCompleted(String mode, String summary, boolean orderSent, boolean ocoAttached) {
        Snapshot current = snapshot.get();
        snapshot.set(new Snapshot(
                current.tickCount(),
                skippedOverlapCounter.get(),
                current.lastTickAtUtc(),
                Instant.now(),
                "COMPLETED",
                mode,
                truncate(summary, 500),
                orderSent,
                ocoAttached,
                null));
    }

    public void markFailed(String mode, String error) {
        Snapshot current = snapshot.get();
        snapshot.set(new Snapshot(
                current.tickCount(),
                skippedOverlapCounter.get(),
                current.lastTickAtUtc(),
                Instant.now(),
                "FAILED",
                mode,
                "scheduler failed",
                false,
                false,
                truncate(error, 500)));
    }

    public Snapshot snapshot() {
        return snapshot.get();
    }

    public ObjectNode toJson(ObjectMapper objectMapper, boolean enabled, boolean dryRun,
                             long fixedDelayMs, long initialDelayMs) {
        Snapshot current = snapshot();
        ObjectNode node = objectMapper.createObjectNode();
        node.put("schedulerInstalled", true);
        node.put("schedulerEnabled", enabled);
        node.put("schedulerDryRun", dryRun);
        node.put("schedulerFixedDelayMs", fixedDelayMs);
        node.put("schedulerInitialDelayMs", initialDelayMs);
        node.put("schedulerExecutionPath", "ScoreBuyPostScoutAutoAddExecutionScheduler -> executeIfEligible");
        node.put("schedulerTickCount", current.tickCount());
        node.put("schedulerSkippedOverlapCount", current.skippedOverlapCount());
        node.put("schedulerLastTickAtUtc", instantText(current.lastTickAtUtc()));
        node.put("schedulerLastCompletedAtUtc", instantText(current.lastCompletedAtUtc()));
        node.put("schedulerNextCheckAtUtc", nextCheckAtText(current.lastCompletedAtUtc(), fixedDelayMs));
        node.put("schedulerLastStatus", current.lastStatus());
        node.put("schedulerLastMode", current.lastMode());
        node.put("schedulerLastSummary", current.lastSummary());
        node.put("schedulerLastOrderSent", current.lastOrderSent());
        node.put("schedulerLastOcoAttached", current.lastOcoAttached());
        node.put("schedulerLastError", current.lastError() == null ? "" : current.lastError());
        return node;
    }

    private String nextCheckAtText(Instant completedAt, long fixedDelayMs) {
        if (completedAt == null) {
            return "UNKNOWN_PENDING_FIRST_TICK";
        }
        return completedAt.plusMillis(Math.max(1L, fixedDelayMs)).toString();
    }

    private String instantText(Instant instant) {
        return instant == null ? "N/A" : instant.toString();
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record Snapshot(long tickCount,
                           long skippedOverlapCount,
                           Instant lastTickAtUtc,
                           Instant lastCompletedAtUtc,
                           String lastStatus,
                           String lastMode,
                           String lastSummary,
                           boolean lastOrderSent,
                           boolean lastOcoAttached,
                           String lastError) {
        private static Snapshot initial() {
            return new Snapshot(0, 0, null, null, "NOT_STARTED", "N/A",
                    "scheduler has not ticked since process start", false, false, null);
        }

        private Snapshot withStatus(String status, String summary, Instant completedAt) {
            return new Snapshot(tickCount, skippedOverlapCount, lastTickAtUtc, completedAt,
                    status, lastMode, summary, false, false, null);
        }
    }
}
