package com.agora.service.trading;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Canonical time contract for persisted BTC Donchian SHADOW evidence. */
final class BtcDonchianEvidenceTime {

    private BtcDonchianEvidenceTime() {
    }

    static String format(LocalDateTime value) {
        return value == null ? null : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value);
    }

    static LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            // Older rows used the global serializer, which appended +08:00 to UTC-valued wall time.
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDateTime();
        }
    }
}
