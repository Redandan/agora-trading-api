package com.agora.service.tradingview;

import java.math.BigDecimal;

/**
 * Versioned research contract that keeps the frozen TradingView score-buy
 * entries and adds deterministic, per-lot profit harvesting.
 *
 * <p>This contract is registered as PAPER and has no exchange adapter. It
 * remains non-live until its forward acceptance gates and a separate live
 * inventory/sell design are reviewed.</p>
 */
public final class TradingViewScoreBuyAutoExitStrategyContract {

    public static final String KEY = "TV_BTC_DAILY_SCORE_BUY_AUTO_EXIT_V2";
    public static final int CONTRACT_VERSION = 2;
    public static final String OWNER_ALIAS = "508";
    public static final String ENTRY_CONTRACT_KEY = TradingViewDailyStrategyContract.KEY;
    public static final long CURRENT_DATABASE_STRATEGY_ID =
            TradingViewDailyStrategyContract.CURRENT_DATABASE_STRATEGY_ID;
    public static final long LEGACY_DATABASE_ID_COLLISION =
            TradingViewDailyStrategyContract.LEGACY_DATABASE_ID_COLLISION;
    public static final String SIGNAL_SYMBOL = TradingViewDailyStrategyContract.SIGNAL_SYMBOL;
    public static final String SIGNAL_INTERVAL = TradingViewDailyStrategyContract.SIGNAL_INTERVAL;
    public static final String SIGNAL_SOURCE = TradingViewDailyStrategyContract.SIGNAL_SOURCE;
    public static final String EXECUTION_SYMBOL = TradingViewDailyStrategyContract.EXECUTION_SYMBOL;
    public static final String POSITION_MODEL = "INDEPENDENT_ACCUMULATION_LOTS";
    public static final String EXIT_POLICY = "PER_LOT_NET_PROFIT_TARGET";
    public static final String PAPER_EXECUTION_TIMING = "NEXT_AVAILABLE_DAILY_OPEN";

    /**
     * A lot becomes eligible for an exit after its estimated net liquidation
     * value is at least five percent above its original gross buy notional.
     */
    public static final BigDecimal NET_PROFIT_TRIGGER = new BigDecimal("0.0500");

    /**
     * A queued exit is deferred when the next daily open would realize less
     * than one percent net profit after exit costs.
     */
    public static final BigDecimal MIN_REALIZED_NET_PROFIT = new BigDecimal("0.0100");

    public static final BigDecimal PAPER_FEE_RATE =
            TradingViewDailyStrategyContract.PAPER_FEE_RATE;
    public static final BigDecimal PAPER_ADVERSE_SLIPPAGE_RATE =
            new BigDecimal("0.0005");

    private TradingViewScoreBuyAutoExitStrategyContract() {
    }
}
