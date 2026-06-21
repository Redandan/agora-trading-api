package com.agora.service.backtest;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataFreshnessReplayCandidateIdsTest {

    @Test
    void createIsStableForSameReplayInputs() {
        LocalDateTime latestBarOpen = LocalDateTime.parse("2026-06-14T15:00:00");

        String first = DataFreshnessReplayCandidateIds.create(
                574L, "btcusdt", "1h", "okx", latestBarOpen);
        String second = DataFreshnessReplayCandidateIds.create(
                574L, "BTCUSDT", "1H", "OKX", latestBarOpen);

        assertEquals(first, second);
        assertTrue(first.matches("dfsr1_[0-9a-f]{24}"));
    }

    @Test
    void createChangesWhenReplayBarChanges() {
        String first = DataFreshnessReplayCandidateIds.create(
                574L, "BTCUSDT", "1h", "okx", LocalDateTime.parse("2026-06-14T15:00:00"));
        String second = DataFreshnessReplayCandidateIds.create(
                574L, "BTCUSDT", "1h", "okx", LocalDateTime.parse("2026-06-14T16:00:00"));

        assertNotEquals(first, second);
    }
}
