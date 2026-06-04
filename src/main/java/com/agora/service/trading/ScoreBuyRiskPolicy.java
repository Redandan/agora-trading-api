package com.agora.service.trading;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class ScoreBuyRiskPolicy {

    static final String BTC_SPOT_DISASTER_SL_POLICY = "BTC_SPOT_ANTI_WICK_DISASTER_SL";
    static final BigDecimal TP_MULTIPLIER = new BigDecimal("1.0300");
    static final BigDecimal BTC_SPOT_DISASTER_SL_MULTIPLIER = new BigDecimal("0.8800");
    static final BigDecimal BTC_SPOT_DISASTER_SL_PCT = new BigDecimal("12.00");

    private ScoreBuyRiskPolicy() {
    }

    static BigDecimal takeProfit(BigDecimal entry) {
        return positive(entry) ? scalePrice(entry.multiply(TP_MULTIPLIER)) : BigDecimal.ZERO;
    }

    static BigDecimal disasterStopLoss(BigDecimal entry) {
        return positive(entry) ? scalePrice(entry.multiply(BTC_SPOT_DISASTER_SL_MULTIPLIER)) : BigDecimal.ZERO;
    }

    static BigDecimal maxLossIfWrong(BigDecimal notional, BigDecimal entry, BigDecimal sl) {
        if (!positive(notional) || !positive(entry) || !positive(sl)) {
            return BigDecimal.ZERO;
        }
        return notional.multiply(entry.subtract(sl).divide(entry, 8, RoundingMode.HALF_UP))
                .setScale(4, RoundingMode.HALF_UP);
    }

    static void putStopLossPolicy(ObjectNode node) {
        node.put("slPolicy", BTC_SPOT_DISASTER_SL_POLICY);
        node.put("slDistancePct", BTC_SPOT_DISASTER_SL_PCT.stripTrailingZeros().toPlainString());
    }

    private static BigDecimal scalePrice(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }
}
