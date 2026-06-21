package com.agora.service.backtest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;

final class DataFreshnessReplayCandidateIds {

    private DataFreshnessReplayCandidateIds() {
    }

    static String create(Long strategyId,
                         String symbol,
                         String intervalCode,
                         String klineSource,
                         LocalDateTime latestBarOpen) {
        String raw = String.join("|",
                "dfsr1",
                text(strategyId),
                normalize(symbol),
                normalize(intervalCode),
                normalize(klineSource),
                latestBarOpen == null ? "UNKNOWN_BAR" : latestBarOpen.toString());
        return "dfsr1_" + sha256(raw).substring(0, 24);
    }

    private static String text(Object value) {
        return value == null ? "UNKNOWN" : String.valueOf(value);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? "UNKNOWN"
                : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("sha256 failed", e);
        }
    }
}
