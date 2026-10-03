package com.agora.service.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Balance-level scenarios, not forecasts, realized PnL, loss limits or order authority. */
public final class SpotAccountRiskPolicy {
    private SpotAccountRiskPolicy() { }

    public static Map<String, Object> snapshot(List<OkxTradingService.SpotHolding> trading,
                                               List<OkxTradingService.SpotHolding> funding) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", "SPOT_ACCOUNT_RISK_OBSERVATION_V1");
        out.put("action", "OBSERVE_ONLY");
        out.put("coverage", "TRADING_AND_FUNDING_BALANCES_EARN_EXCLUDED_GRID_COVERAGE_UNVERIFIED");
        out.put("wholeAccountRiskComplete", false);
        out.put("wholeAccountLossLimit", "NOT_CONFIGURED_NO_AUTOMATIC_POSITION_CHANGE");
        out.put("strategyBudgets", "SEPARATE_STRATEGY_ALLOCATION_CAPS_ARE_NOT_ACCOUNT_LOSS_LIMITS");
        out.put("scenarioAssumptions", "BTC_PRICE_ONLY_OTHER_BALANCES_FIXED_NO_PROBABILITY_FEES_OR_USDT_DEPEG_MODEL");
        if (trading == null || funding == null) {
            out.put("valuationStatus", "MISSING_PROOF_ACCOUNT_READ_FAILED");
            return out;
        }
        var all = java.util.stream.Stream.concat(trading.stream(), funding.stream()).toList();
        boolean complete = all.stream().allMatch(h -> h != null && h.ccy != null
                && h.cashBal != null && h.cashBal.signum() >= 0 && h.availBal != null && h.availBal.signum() >= 0
                && h.eqUsd != null && h.eqUsd.signum() >= 0
                && (h.cashBal.signum() == 0 || h.eqUsd.signum() > 0));
        if (!complete) {
            out.put("valuationStatus", "MISSING_PROOF_UNPRICED_OR_INVALID_BALANCE");
            return out;
        }
        BigDecimal total = all.stream().map(h -> h.eqUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal btc = all.stream().filter(h -> "BTC".equalsIgnoreCase(h.ccy))
                .map(h -> h.eqUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        out.put("valuationStatus", "OBSERVED_BALANCES_ESTIMATED_USD");
        out.put("observedBalanceEquityUsd", total);
        out.put("observedBtcExposureUsd", btc);
        out.put("btcShareOfObservedBalances", total.signum() > 0 ? btc.divide(total, 8, RoundingMode.HALF_UP) : null);
        out.put("availableTradingUsdt", trading.stream().filter(h -> "USDT".equalsIgnoreCase(h.ccy))
                .map(h -> h.availBal).reduce(BigDecimal.ZERO, BigDecimal::add));
        Map<String, BigDecimal> shocks = new LinkedHashMap<>();
        for (String pct : List.of("10", "30", "50")) {
            shocks.put("btcDown" + pct + "PercentEquityChangeUsd", btc.multiply(new BigDecimal(pct)).movePointLeft(2).negate());
        }
        out.put("hypotheticalBtcPriceShocks", shocks);
        return out;
    }
}
