package com.agora.service.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Price protection for the existing profit-only exit; never authorizes a loss exit. */
public final class BtcDraProfitExitPolicy {
    public static final String PROFILE = "DRA_PROFIT_EXIT_IOC_V1";
    private BtcDraProfitExitPolicy() { }

    public static BigDecimal minimumSellPrice(BigDecimal effectiveEntry, BigDecimal tickSize,
                                               BigDecimal takerFeeRate) {
        if (effectiveEntry == null || effectiveEntry.signum() <= 0
                || tickSize == null || tickSize.signum() <= 0
                || takerFeeRate == null || takerFeeRate.signum() < 0
                || takerFeeRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("INVALID_PROFIT_PRICE_INPUT");
        }
        BigDecimal fee = takerFeeRate.max(BtcDraPolicy.FEE_RATE_PER_SIDE);
        // Retain the original adverse-execution buffer and round UP to the provider tick.
        BigDecimal denominator = BigDecimal.ONE.subtract(fee)
                .multiply(BigDecimal.ONE.subtract(BtcDraPolicy.ADVERSE_SLIPPAGE_RATE_PER_SIDE))
                .multiply(tickSize);
        return effectiveEntry.multiply(BigDecimal.ONE.add(BtcDraPolicy.NET_PROFIT_TRIGGER))
                .divide(denominator, 0, RoundingMode.CEILING).multiply(tickSize);
    }
}
