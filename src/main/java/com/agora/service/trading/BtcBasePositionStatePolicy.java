package com.agora.service.trading;

import com.agora.model.BtLiveSignal;

import java.time.Instant;

/** Shared persisted-state semantics for intentional BTC base positions. */
public final class BtcBasePositionStatePolicy {

    public static final String BTC_BASE_PREFIX = "LOCAL_TRADINGVIEW_BTC_BASE:";
    public static final String ADOPTION_PENDING_PREFIX =
            BTC_BASE_PREFIX + "ADOPTION_PENDING:V1:OCO=";
    public static final String ADOPTED_FROM_OCO_PREFIX =
            BTC_BASE_PREFIX + "ADOPTED_FROM_OCO:V1:OCO=";
    public static final String TV509_POSITION_PREFIX = BTC_BASE_PREFIX + "TV509:";
    public static final String DRA_V1_POSITION_PREFIX = BTC_BASE_PREFIX + "DRA_V1:";
    public static final int FILTER_REASON_MAX_LENGTH = 500;

    private static final String PREVIOUS_NULL_SUFFIX = "|PREV_NULL";
    private static final String PREVIOUS_REASON_SEPARATOR = "|PREV=";

    private BtcBasePositionStatePolicy() {
    }

    public static boolean isBtcBase(BtLiveSignal position) {
        return position != null && isBtcBaseReason(position.getFilterReason());
    }

    public static boolean isBtcBaseReason(String filterReason) {
        return filterReason != null && filterReason.startsWith(BTC_BASE_PREFIX);
    }

    public static boolean isAdoptionPending(BtLiveSignal position) {
        return position != null && isAdoptionPendingReason(position.getFilterReason());
    }

    public static boolean isAdoptionPendingReason(String filterReason) {
        return filterReason != null && filterReason.startsWith(ADOPTION_PENDING_PREFIX);
    }

    public static boolean isAdoptedFromOco(BtLiveSignal position) {
        return position != null && isAdoptedFromOcoReason(position.getFilterReason());
    }

    public static boolean isAdoptedFromOcoReason(String filterReason) {
        return filterReason != null && filterReason.startsWith(ADOPTED_FROM_OCO_PREFIX);
    }

    public static boolean isTv509Position(BtLiveSignal position) {
        return position != null && startsWith(position.getFilterReason(), TV509_POSITION_PREFIX);
    }

    public static boolean isDraV1Position(BtLiveSignal position) {
        return position != null && startsWith(position.getFilterReason(), DRA_V1_POSITION_PREFIX);
    }

    public static boolean isIntentionalNoOco(BtLiveSignal position) {
        return position != null
                && position.getOcoOrderListId() == null
                && isBtcBase(position);
    }

    public static Long originalOcoAlgoId(BtLiveSignal position) {
        return position == null ? null : originalOcoAlgoId(position.getFilterReason());
    }

    public static Long originalOcoAlgoId(String filterReason) {
        String prefix;
        if (isAdoptionPendingReason(filterReason)) {
            prefix = ADOPTION_PENDING_PREFIX;
        } else if (isAdoptedFromOcoReason(filterReason)) {
            prefix = ADOPTED_FROM_OCO_PREFIX;
        } else {
            return null;
        }
        int end = filterReason.indexOf('|', prefix.length());
        String raw = end < 0 ? filterReason.substring(prefix.length())
                : filterReason.substring(prefix.length(), end);
        try {
            long value = Long.parseLong(raw);
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static String pendingMarker(Long originalOcoAlgoId, String previousReason) {
        if (originalOcoAlgoId == null || originalOcoAlgoId <= 0) {
            throw new IllegalArgumentException("originalOcoAlgoId must be positive");
        }
        String marker = ADOPTION_PENDING_PREFIX + originalOcoAlgoId
                + "|AT=" + Instant.now()
                + (previousReason == null
                ? PREVIOUS_NULL_SUFFIX
                : PREVIOUS_REASON_SEPARATOR + previousReason);
        if (marker.length() > FILTER_REASON_MAX_LENGTH) {
            throw new IllegalArgumentException("FILTER_REASON_TOO_LONG_TO_PRESERVE");
        }
        return marker;
    }

    public static String adoptedMarkerFromPending(String pendingReason) {
        if (!isAdoptionPendingReason(pendingReason)) {
            throw new IllegalArgumentException("ADOPTION_PENDING_MARKER_REQUIRED");
        }
        return ADOPTED_FROM_OCO_PREFIX + pendingReason.substring(ADOPTION_PENDING_PREFIX.length());
    }

    public static String previousReason(String marker) {
        if (!isAdoptionPendingReason(marker) && !isAdoptedFromOcoReason(marker)) {
            return marker;
        }
        if (marker.endsWith(PREVIOUS_NULL_SUFFIX)) {
            return null;
        }
        int separator = marker.indexOf(PREVIOUS_REASON_SEPARATOR);
        return separator < 0 ? null : marker.substring(separator + PREVIOUS_REASON_SEPARATOR.length());
    }

    public static String managementState(BtLiveSignal position) {
        if (isAdoptionPending(position)) return "ADOPTION_PENDING";
        if (isAdoptedFromOco(position)) return "ADOPTED_FROM_OCO";
        if (isTv509Position(position)) return "TV509_OWNED";
        if (isDraV1Position(position)) return "DRA_V1_OWNED";
        if (isBtcBase(position)) return "NATIVE_BTC_BASE";
        return "NONE";
    }

    public static String automaticExitPolicy(BtLiveSignal position) {
        if (isTv509Position(position) || isDraV1Position(position)) {
            return "PER_LOT_NET_PROFIT_TARGET";
        }
        if (isBtcBase(position)) {
            return "NONE";
        }
        return "UNKNOWN";
    }

    private static boolean startsWith(String value, String prefix) {
        return value != null && value.startsWith(prefix);
    }
}
