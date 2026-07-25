package com.agora.service.trading;

import java.math.BigDecimal;

/**
 * Frozen research contract for the source-pinned MEI directional candidate.
 *
 * <p>This is a new strategy lineage derived from the old strategy-567 idea.
 * Old 567 backtest results do not transfer to this contract.</p>
 */
public final class BtcMeiDirectionalShadowPolicy {

    public static final String POLICY_MODE = "BTC_MEI_DIRECTIONAL_ACCUMULATION_V1";
    public static final String STATE_SCHEMA_VERSION = "BTC_MEI_DIRECTIONAL_SHADOW_STATE_V1";
    public static final String EVIDENCE_SCHEMA_VERSION = "BTC_MEI_DIRECTIONAL_SHADOW_EVIDENCE_V2";
    public static final String SYMBOL = "BTCUSDT";
    public static final String INTERVAL = "1h";
    public static final String SOURCE = "okx";

    public static final int ENTROPY_BINS = 20;
    public static final int ENTROPY_24H = 24;
    public static final int ENTROPY_48H = 48;
    public static final int ENTROPY_72H = 72;
    public static final double ENTROPY_24H_WEIGHT = 0.40;
    public static final double ENTROPY_48H_WEIGHT = 0.35;
    public static final double ENTROPY_72H_WEIGHT = 0.25;
    public static final double ENTRY_ENTROPY_THRESHOLD = 60.0;
    public static final int MOMENTUM_LOOKBACK_HOURS = 24;
    public static final int EMA_PERIOD_HOURS = 20;
    public static final int REQUIRED_CLOSE_POINTS = ENTROPY_72H + 1;
    public static final int MAX_CATCH_UP_BARS = 24 * 30;

    public static final BigDecimal BASE_NOTIONAL_USDT = new BigDecimal("10.00");
    public static final BigDecimal MAX_OPEN_COST_USDT = new BigDecimal("250.00");
    public static final BigDecimal FEE_RATE_PER_SIDE = new BigDecimal("0.0010");
    public static final BigDecimal ADVERSE_SLIPPAGE_RATE_PER_SIDE = new BigDecimal("0.0005");
    public static final BigDecimal NET_PROFIT_TRIGGER = new BigDecimal("0.0500");
    public static final BigDecimal MIN_REALIZED_NET_PROFIT = new BigDecimal("0.0100");

    private BtcMeiDirectionalShadowPolicy() {
    }
}
