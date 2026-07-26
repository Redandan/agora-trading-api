package com.agora.service.trading;

import java.math.BigDecimal;

/**
 * Frozen policy for BTC Daily Reversal Accumulation V1.
 *
 * <p>DRA is the no-drawdown-filter candidate selected by the 2026-07-26
 * ablation study. It observes OKX hourly bars, makes entry decisions only on
 * the UTC daily close. Its separately bounded execution profile permits one
 * 30 USDT OKX spot lot when the explicit LIVE switch is armed.</p>
 */
public final class BtcDraPolicy {

    public static final String POLICY_MODE = "BTC_DAILY_REVERSAL_ACCUMULATION_V1";
    public static final String STATE_SCHEMA_VERSION = "BTC_DRA_RUNTIME_STATE_V1";
    public static final String EVIDENCE_SCHEMA_VERSION = "BTC_DRA_RUNTIME_EVIDENCE_V1";
    public static final String SYMBOL = "BTCUSDT";
    public static final String INTERVAL = "1h";
    public static final String SOURCE = "okx";
    public static final String EXECUTION_SYMBOL = "BTCUSDT";
    public static final long RUNTIME_LEDGER_STRATEGY_ID = -10001L;

    public static final int DAILY_EMA_PERIOD_DAYS = 20;
    public static final int EMA_SLOPE_LOOKBACK_DAYS = 5;
    public static final int MOMENTUM_LOOKBACK_HOURS = 24;
    public static final int ENTRY_COOLDOWN_DAYS = 7;
    public static final int ARM_EXPIRY_DAYS = 30;
    public static final int REQUIRED_CLOSE_POINTS = MOMENTUM_LOOKBACK_HOURS + 1;
    public static final int REQUIRED_DAILY_EMA_POINTS = EMA_SLOPE_LOOKBACK_DAYS + 1;
    public static final int BOOTSTRAP_HISTORY_HOURS = 24 * 90;
    public static final int MAX_CATCH_UP_BARS = 24 * 30;

    public static final BigDecimal BASE_NOTIONAL_USDT = new BigDecimal("30.00");
    public static final BigDecimal MAX_OPEN_COST_USDT = new BigDecimal("250.00");
    public static final BigDecimal FEE_RATE_PER_SIDE = new BigDecimal("0.0010");
    public static final BigDecimal ADVERSE_SLIPPAGE_RATE_PER_SIDE = new BigDecimal("0.0005");
    public static final BigDecimal NET_PROFIT_TRIGGER = new BigDecimal("0.0500");
    public static final BigDecimal MIN_REALIZED_NET_PROFIT = new BigDecimal("0.0100");

    private BtcDraPolicy() {
    }
}
