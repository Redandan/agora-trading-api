package com.agora.research;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OkxMicrostructureDiscoveryRecoveryV3R1Test {

    @Test
    void exactFixedEventsHaveNarrowActions() {
        var trades = classify(
                "{\"event\":\"subscribe\",\"arg\":{\"channel\":\"trades\",\"instId\":\"BTC-USDT\"}}");
        assertEquals(
                OkxMicrostructureDiscoveryRecoveryV3R1.Action.ACKNOWLEDGE,
                trades.action());
        assertEquals("trades", trades.channel());
        assertNull(trades.rejectionReason());

        var count = classify(
                "{\"event\":\"channel-conn-count\",\"channel\":\"books5\",\"connCount\":\"1\",\"connId\":\"opaque\"}");
        assertEquals(
                OkxMicrostructureDiscoveryRecoveryV3R1.Action
                        .SEAL_CONTROL_EVENT_AND_CONTINUE,
                count.action());
        assertEquals("books5", count.channel());
        assertEquals(
                new OkxMicrostructureDiscoveryRecoveryV3R1.SanitizedControlEvent(
                        "channel-conn-count", null),
                count.sanitizedControlEvent());

        var notice = classify("{\"event\":\"notice\",\"code\":\"64008\"}");
        assertEquals(
                OkxMicrostructureDiscoveryRecoveryV3R1.Action.REJECT_ACTIVE_DAY,
                notice.action());
        assertEquals(
                "SERVICE_UPGRADE_NOTICE_64008",
                notice.rejectionReason());
        assertEquals(
                new OkxMicrostructureDiscoveryRecoveryV3R1.SanitizedControlEvent(
                        "notice", "64008"),
                notice.sanitizedControlEvent());
    }

    @Test
    void unknownChangedAndMalformedEventsBlockGeneration() {
        for (String raw : new String[]{
                "{\"event\":\"notice\",\"code\":\"99999\"}",
                "{\"event\":\"error\",\"code\":\"60012\"}",
                "{\"event\":\"channel-conn-count-error\",\"channel\":\"trades\"}",
                "{\"event\":\"unsubscribe\",\"arg\":{}}",
                "{\"event\":\"future-new-event\"}",
                "{\"event\":\"notice\",\"event\":\"notice\",\"code\":\"64008\"}",
                "{\"event\":\"notice\",\"code\":\"64008\"}{}",
                "not-json"
        }) {
            var error = assertThrows(
                    OkxMicrostructureDiscoveryRecoveryV3R1.BlockedException.class,
                    () -> classify(raw));
            assertEquals("UNKNOWN_EVENT", error.code());
        }
    }

    @Test
    void changedSubscriptionIdentityBlocksSeparately() {
        for (String raw : new String[]{
                "{\"event\":\"subscribe\",\"arg\":{\"channel\":\"tickers\",\"instId\":\"BTC-USDT\"}}",
                "{\"event\":\"subscribe\",\"arg\":{\"channel\":\"trades\",\"instId\":\"ETH-USDT\"}}",
                "{\"event\":\"subscribe\"}"
        }) {
            var error = assertThrows(
                    OkxMicrostructureDiscoveryRecoveryV3R1.BlockedException.class,
                    () -> classify(raw));
            assertEquals("WRONG_IDENTITY", error.code());
        }
    }

    @Test
    void controlEventChainMatchesFrozenPythonAlgorithmAndRawBytesMatter() {
        byte[] firstRaw = bytes("{\"event\":\"notice\",\"code\":\"64008\"}");
        byte[] reorderedRaw = bytes("{\"code\":\"64008\",\"event\":\"notice\"}");
        String first = OkxMicrostructureDiscoveryRecoveryV3R1.nextControlEventChain(
                OkxMicrostructureDiscoveryRecoveryV3R1.ZERO_SHA256,
                firstRaw);
        String repeated = OkxMicrostructureDiscoveryRecoveryV3R1.nextControlEventChain(
                OkxMicrostructureDiscoveryRecoveryV3R1.ZERO_SHA256,
                firstRaw);
        String reordered = OkxMicrostructureDiscoveryRecoveryV3R1.nextControlEventChain(
                OkxMicrostructureDiscoveryRecoveryV3R1.ZERO_SHA256,
                reorderedRaw);

        assertEquals(
                "a6da5a7179a32ea1a05b9e68dca34923492ebccef67ffb39cae25bf7e5ade0f8",
                first);
        assertEquals(first, repeated);
        assertEquals(
                "89acf55145c6bc956042c3997c1a9e7d71b80ef938661c605d834c2dfc855ea6",
                reordered);
        assertNotEquals(first, reordered);
    }

    @Test
    void chainInputsFailClosed() {
        assertThrows(
                OkxMicrostructureDiscoveryRecoveryV3R1.BlockedException.class,
                () -> OkxMicrostructureDiscoveryRecoveryV3R1.nextControlEventChain(
                        "A".repeat(64), bytes("{}")));
        assertThrows(
                OkxMicrostructureDiscoveryRecoveryV3R1.BlockedException.class,
                () -> OkxMicrostructureDiscoveryRecoveryV3R1.nextControlEventChain(
                        OkxMicrostructureDiscoveryRecoveryV3R1.ZERO_SHA256,
                        new byte[0]));
    }

    private static OkxMicrostructureDiscoveryRecoveryV3R1.Classification classify(String raw) {
        return OkxMicrostructureDiscoveryRecoveryV3R1.classify(bytes(raw));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
