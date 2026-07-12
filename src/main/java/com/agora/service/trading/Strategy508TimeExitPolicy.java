package com.agora.service.trading;

import java.math.BigDecimal;
import java.util.List;

/** Fixed, versioned contract for the strategy 508 4h/24h experiment. */
public final class Strategy508TimeExitPolicy {

    public static final String POLICY_MODE = "STRATEGY_508_4H_24H_V1";
    public static final long STRATEGY_ID = 508L;
    public static final String SYMBOL = "BTCUSDT";
    public static final String INTERVAL = "4h";
    public static final String KLINE_SOURCE = "okx";
    public static final int HOLD_HOURS = 24;
    public static final BigDecimal NOTIONAL_USDT = new BigDecimal("10.00");
    public static final BigDecimal TAKE_PROFIT_PCT = new BigDecimal("0.06");
    public static final BigDecimal STOP_LOSS_PCT = new BigDecimal("0.12");
    public static final BigDecimal FEE_RATE = new BigDecimal("0.001");
    public static final BigDecimal SLIPPAGE_RATE = new BigDecimal("0.0005");
    public static final BigDecimal MAX_CUMULATIVE_LOSS_USDT = new BigDecimal("3.00");
    public static final int MAX_OPEN_POSITIONS = 1;
    public static final int MAX_ORDERS_PER_DAY = 1;
    public static final int MAX_PILOT_ORDERS = 5;
    public static final int PILOT_MAX_DAYS = 60;
    public static final int FORWARD_MIN_DAYS = 30;
    public static final int FORWARD_MIN_FINALIZED_EVENTS = 5;
    public static final int HISTORICAL_MIN_FINALIZED_EVENTS = 30;
    public static final List<Integer> WINDOWS_DAYS = List.of(90, 120, 180, 270, 365);

    private Strategy508TimeExitPolicy() {
    }
}
