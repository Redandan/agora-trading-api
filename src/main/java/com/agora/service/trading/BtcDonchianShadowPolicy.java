package com.agora.service.trading;

import java.time.LocalDateTime;

/** Frozen policy and golden-ledger contract for BTC_DONCHIAN_20D_10D_V1. */
public final class BtcDonchianShadowPolicy {

    public static final String POLICY_MODE = "BTC_DONCHIAN_20D_10D_V1";
    public static final String STATE_SCHEMA_VERSION = "BTC_DONCHIAN_SHADOW_STATE_V1";
    public static final String EVIDENCE_SCHEMA_VERSION = "BTC_DONCHIAN_SHADOW_EVIDENCE_V1";
    public static final String SYMBOL = "BTCUSDT";
    public static final String INTERVAL = "1h";
    public static final String SOURCE = "okx";
    public static final String RESEARCH_INSTRUMENT = "BTC-USDT";

    public static final int ENTRY_LOOKBACK_DAYS = 20;
    public static final int EXIT_LOOKBACK_DAYS = 10;
    public static final int ATR_LOOKBACK_DAYS = 14;
    public static final double INITIAL_STOP_ATR_MULTIPLE = 2.0;
    public static final double EQUITY_RISK_PER_TRADE = 0.01;
    public static final double MAXIMUM_EXPOSURE = 1.0;
    public static final int MAX_CATCH_UP_BARS = 24 * 30;

    public static final int FORWARD_MIN_DAYS = 30;
    public static final int FORWARD_MIN_UNIQUE_ENTRIES = 5;
    public static final int FORWARD_MIN_COMPLETED_TRADES = 5;

    public static final LocalDateTime GOLDEN_FIRST_OPEN_TIME = LocalDateTime.of(2019, 1, 1, 0, 0);
    public static final LocalDateTime GOLDEN_LAST_OPEN_TIME = LocalDateTime.of(2026, 7, 13, 8, 0);
    public static final int GOLDEN_ROW_COUNT = 66_009;
    public static final String GOLDEN_DATASET_ID = "okx-btc-usdt-1h-20260713T090000Z";
    public static final String GOLDEN_DATASET_SHA256 =
            "74bccfdc621884447e224536cedb7471f8c28bbb612f38e81d8b23e02ff8cfd8";
    public static final String GOLDEN_PRICE_BAR_LEDGER_SHA256 =
            "361ab6910872079db4e58c45897828b3399c5d9cb8346afcd1970536d1ee6a6d";

    public static final Scenario NORMAL = new Scenario(
            "NORMAL", 0.001, 0.0005, 0,
            65, 82, 41,
            "7fc83c87c723eb06cd2777400d5d041ed23da0d90b9735b5309572264813baef",
            "78ad4a0a9744ceaa52a477af022068f1d35c7f81789e3b1aaaa0745484639d52",
            "f63b7418c42082fcaa05e45e244e1293ae0e9109dc6bb0925d123d73a17120b8");
    public static final Scenario STRESS = new Scenario(
            "STRESS", 0.002, 0.001, 1,
            65, 82, 41,
            "4b2cb322e5a8f785b954df3f537d7475da7328992df626f1f3f2a2e8a5883b73",
            "9a146373c476efb322a39de9fbcdb1075d0e63c2aeaa4d23e6a57e4bc85aac44",
            "44e4b7bbab2e428c43acad4051e7561317008c84c5b84a42edb92829487f14e5");

    private BtcDonchianShadowPolicy() {
    }

    public record Scenario(
            String name,
            double feeRatePerSide,
            double adverseSlippageRatePerSide,
            int signalDelayBars,
            int expectedSignals,
            int expectedOrders,
            int expectedTrades,
            String expectedSignalLedgerSha256,
            String expectedOrderLedgerSha256,
            String expectedTradeLedgerSha256
    ) {
    }
}
