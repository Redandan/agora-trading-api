package com.agora.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OkxMicrostructureDiscoveryRecoveryCheckpointV3R1Test {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final String ZERO = "0".repeat(64);

    @Test
    void canonicalCheckpointRoundTripsAndRejectsTampering() {
        var initial = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.initial(
                binding(), "boot-a", Instant.parse("2026-08-31T23:50:00Z"));
        var active = active(initial, "boot-a", START);
        byte[] bytes = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.canonicalBytes(active);

        var parsed = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.parse(bytes);
        assertArrayEquals(bytes,
                OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.canonicalBytes(parsed));
        assertEquals(OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Phase.ACTIVE_DAY,
                parsed.phase());
        assertEquals(List.of("books5", "trades"), parsed.acknowledgedChannels());

        byte[] changed = bytes.clone();
        changed[20] = changed[20] == 'a' ? (byte) 'b' : (byte) 'a';
        assertThrows(IllegalArgumentException.class,
                () -> OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.parse(changed));
    }

    @Test
    void restartPlanUsesOnlyBootClockAndLivenessState() {
        var initial = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.initial(
                binding(), "boot-a", Instant.parse("2026-08-31T23:50:00Z"));
        var active = active(initial, "boot-a", START);

        var processRestart = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.restartPlan(
                active,
                Instant.parse("2026-09-01T13:00:00Z"),
                Instant.parse("2026-08-30T00:00:00Z"),
                "boot-a");
        assertEquals(1, processRestart.size());
        assertEquals("PROCESS_RESTART_BEFORE_DAY_COMPLETE", processRestart.get(0).reason());
        assertEquals(17, processRestart.get(0).observation().completedMinuteCount());

        var hostRecovery = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.restartPlan(
                active,
                Instant.parse("2026-09-03T03:00:00Z"),
                Instant.parse("2026-09-02T10:00:00Z"),
                "boot-b");
        assertEquals(List.of(
                        "HOST_REBOOT_BEFORE_DAY_COMPLETE",
                        "HOST_REBOOT_BEFORE_DAY_COMPLETE",
                        "DUAL_CHANNEL_NOT_READY_AT_DAY_START"),
                hostRecovery.stream().map(
                        OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.PlannedRejection::reason)
                        .toList());
        assertEquals(List.of(START, START.plusDays(1), START.plusDays(2)),
                hostRecovery.stream().map(
                        OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.PlannedRejection::day)
                        .toList());
        assertNull(hostRecovery.get(1).observation().startedAt());
        assertFalse(new String(
                OkxMicrostructureDiscoveryRecoveryDropV3R1.rejection(
                        binding(), hostRecovery.get(1).day(), hostRecovery.get(1).reason(),
                        hostRecovery.get(1).observation(), null,
                        hostRecovery.get(1).rejectedAt()).envelopeBytes(),
                java.nio.charset.StandardCharsets.UTF_8).contains("return"));
    }

    @Test
    void exactUtcBoundaryDoesNotRejectReadyDay() {
        var initial = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.initial(
                binding(), "boot-a", Instant.parse("2026-08-31T23:50:00Z"));
        assertEquals(List.of(),
                OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.restartPlan(
                        initial,
                        Instant.parse("2026-09-01T00:00:00Z"),
                        Instant.parse("2026-08-30T00:00:00Z"),
                        "boot-a"));
    }

    @Test
    void pendingUpgradeNoticeSurvivesCrashWithExactReasonAndSanitizedEvent() {
        var initial = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.initial(
                binding(), "boot-a", Instant.parse("2026-08-31T23:50:00Z"));
        var active = active(initial, "boot-a", START);
        var pending = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.pendingRejection(
                active,
                "SERVICE_UPGRADE_NOTICE_64008",
                new OkxMicrostructureDiscoveryRecoveryDropV3R1.SanitizedControlEvent(
                        "notice", "64008"),
                Instant.parse("2026-09-01T12:00:02Z"));
        var restored = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.parse(
                OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.canonicalBytes(pending));
        var plan = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.restartPlan(
                restored,
                Instant.parse("2026-09-01T12:01:00Z"),
                Instant.parse("2026-08-30T00:00:00Z"),
                "boot-a");

        assertEquals("SERVICE_UPGRADE_NOTICE_64008", plan.getFirst().reason());
        assertEquals(
                new OkxMicrostructureDiscoveryRecoveryDropV3R1.SanitizedControlEvent(
                        "notice", "64008"),
                plan.getFirst().sanitizedControlEvent());
        assertThrows(IllegalArgumentException.class, () ->
                OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.afterDisposition(
                        pending, "boot-a", START, "COMPLETE", "b".repeat(64),
                        Instant.parse("2026-09-02T00:00:00Z")));
    }

    @Test
    void preparedAtomicTransactionCompletesAfterRestart(@TempDir Path temp) throws Exception {
        var store = new OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Store(temp);
        var initial = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.initial(
                binding(), "boot-a", Instant.parse("2026-08-31T23:50:00Z"));
        store.save(null, initial);
        var active = active(initial, "boot-a", START);

        store.prepare(initial, active);
        // loadAndRecover is the restart path and completes the prepared transition.
        assertEquals(active, store.loadAndRecover(binding()));
        assertFalse(Files.exists(temp.resolve("checkpoint.next.json")));
        assertFalse(Files.exists(temp.resolve("checkpoint.intent.json")));
        Instant at = Instant.parse("2026-09-01T13:00:01Z");
        var publishing = pendingPublication(
                active, START, "SOURCE_LIVENESS_REJECTED", "b".repeat(64), at);
        var after = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.afterDisposition(
                publishing, "boot-a", START, "SOURCE_LIVENESS_REJECTED",
                "b".repeat(64), at);
        assertThrows(IllegalStateException.class, () -> store.save(initial, after));
    }

    @Test
    void dispositionMustAdvanceOneDayAndTerminalIsImmutable() {
        var initial = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.initial(
                binding(), "boot-a", Instant.parse("2026-08-31T23:50:00Z"));
        var active = active(initial, "boot-a", START);
        Instant at = Instant.parse("2026-09-01T13:00:01Z");
        var publishing = pendingPublication(
                active, START, "SOURCE_LIVENESS_REJECTED", "b".repeat(64), at);
        var after = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.afterDisposition(
                publishing, "boot-a", START, "SOURCE_LIVENESS_REJECTED",
                "b".repeat(64), at);
        assertEquals(OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Phase.BETWEEN_DAYS,
                after.phase());
        assertThrows(IllegalArgumentException.class, () ->
                OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.active(
                        after, "boot-a", START.plusDays(2),
                        Instant.parse("2026-09-03T00:00:00Z"),
                        Instant.parse("2026-09-03T00:01:00Z"),
                        List.of("books5", "trades"), 1, 2, 2,
                        ZERO, ZERO, Instant.parse("2026-09-03T00:01:00Z")));
    }

    @Test
    void firstFourteenCompleteDaysStopSourceBeforeCalendarDeadline() {
        var snapshot = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.initial(
                binding(), "boot-a", Instant.parse("2026-08-31T23:50:00Z"));
        for (int index = 0; index < 14; index++) {
            LocalDate day = START.plusDays(index);
            snapshot = active(snapshot, "boot-a", day);
            Instant at = day.plusDays(1).atStartOfDay().toInstant(
                    java.time.ZoneOffset.UTC);
            snapshot = pendingPublication(
                    snapshot, day, "COMPLETE", "b".repeat(64), at);
            snapshot = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.afterDisposition(
                    snapshot, "boot-a", day, "COMPLETE", "b".repeat(64),
                    at);
        }
        assertEquals(14, snapshot.currentCompleteStreakCount());
        assertEquals(OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Phase.TERMINAL,
                snapshot.phase());
        var terminal = snapshot;
        assertThrows(IllegalArgumentException.class, () ->
                OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.active(
                        terminal, "boot-a", START.plusDays(14),
                        Instant.parse("2026-09-15T00:00:00Z"),
                        Instant.parse("2026-09-15T00:01:00Z"),
                        List.of("books5", "trades"), 1, 2, 2,
                        ZERO, ZERO, Instant.parse("2026-09-15T00:01:00Z")));
    }

    private OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Snapshot active(
            OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Snapshot previous,
            String bootId,
            LocalDate day) {
        Instant start = day.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        return OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.active(
                previous,
                bootId,
                day,
                start,
                start.plusSeconds(43_200),
                List.of("trades", "books5"),
                17,
                123,
                2,
                "1".repeat(64),
                "2".repeat(64),
                start.plusSeconds(43_201));
    }

    private OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Snapshot pendingPublication(
            OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Snapshot previous,
            LocalDate day,
            String kind,
            String envelopeHash,
            Instant at) {
        String prefix = "okx-btc-usdt-microstructure-" + day;
        var pending = new OkxMicrostructureDiscoveryRecoveryDropV3R1.PendingPublication(
                day,
                kind,
                prefix + ("COMPLETE".equals(kind)
                        ? ".complete.envelope.json" : ".rejection.envelope.json"),
                envelopeHash,
                "COMPLETE".equals(kind) ? prefix + ".json" : null,
                "COMPLETE".equals(kind) ? "c".repeat(64) : null,
                at);
        return OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.pendingPublication(
                previous, pending, at);
    }

    private OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding binding() {
        return new OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding(
                "okx-btcusdt-microstructure-discovery-v3r1-20260901-r3",
                "okx-btcusdt-microstructure-forward-v3r1-20260901-r3",
                "20260812T030000Z",
                "a".repeat(64),
                START,
                START.plusDays(41));
    }
}
