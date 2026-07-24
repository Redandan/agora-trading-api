package com.agora.service.tradingview;

import java.math.BigDecimal;

/**
 * Stable identity and market contract for the owner's TradingView daily BTC
 * accumulation strategy.
 *
 * <p>The owner-facing alias is 508, while the captured Pine-parity database
 * row is currently 485. Production row 508 is a separate one-hour
 * OI/Funding strategy and must never be used as this contract's identity.</p>
 */
public final class TradingViewDailyStrategyContract {

    public static final String KEY = "TV_BTC_DAILY_ACCUMULATION_V1";
    public static final int CONTRACT_VERSION = 1;
    public static final String OWNER_ALIAS = "508";
    public static final long CURRENT_DATABASE_STRATEGY_ID = 485L;
    public static final long LEGACY_DATABASE_ID_COLLISION = 508L;
    public static final String SIGNAL_SYMBOL = "BTCUSDT";
    public static final String SIGNAL_INTERVAL = "1d";
    public static final String SIGNAL_SOURCE = "binance";
    public static final String EXECUTION_SYMBOL = "BTCUSDT";
    public static final String POSITION_MODEL = "BTC_INVENTORY_ACCUMULATION";
    public static final String EXIT_POLICY = "NONE";
    public static final String PAPER_EXECUTION_TIMING = "NEXT_AVAILABLE_DAILY_OPEN";
    public static final BigDecimal PAPER_FEE_RATE = new BigDecimal("0.0010");

    private TradingViewDailyStrategyContract() {
    }
}
