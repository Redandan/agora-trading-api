package com.agora.service.trading;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed, versioned contract for the strategy 508 4h/24h experiment. */
public final class Strategy508TimeExitPolicy {

    public static final String POLICY_MODE = "STRATEGY_508_4H_24H_V1";
    public static final String COHORT_SCHEMA_VERSION = "STRATEGY_508_TIME_EXIT_COHORT_V1";
    public static final String RAW_COUNTERFACTUAL_COHORT = "RAW_SIGNAL_COUNTERFACTUAL";
    public static final String EXECUTABLE_SHADOW_COHORT = "EXECUTABLE_SHADOW";
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
    public static final int ENTRY_MAX_DELAY_MINUTES = 2;
    public static final BigDecimal MAX_CUMULATIVE_LOSS_USDT = new BigDecimal("3.00");
    public static final int MAX_OPEN_POSITIONS = 1;
    public static final int MAX_ORDERS_PER_DAY = 1;
    public static final int MAX_PILOT_ORDERS = 5;
    public static final int PILOT_MAX_DAYS = 60;
    public static final int FORWARD_MIN_DAYS = 30;
    public static final int FORWARD_MIN_FINALIZED_EVENTS = 5;
    public static final int HISTORICAL_MIN_FINALIZED_EVENTS = 30;
    public static final int MARKET_FEATURE_MAX_AGE_MINUTES = 90;
    public static final boolean EXACT_LIVE_FILL_EVIDENCE_IMPLEMENTED = false;
    public static final List<Integer> WINDOWS_DAYS = List.of(90, 120, 180, 270, 365);

    public static void applyMarketFeatureFreshnessPolicy(Map<String, Object> config) {
        config.put("marketFeatureFreshnessFailClosed", true);
        config.put("marketFeatureReferenceTimeMode", "BAR_CLOSE");
        config.put("fundingMaxAgeMinutes", MARKET_FEATURE_MAX_AGE_MINUTES);
        config.put("oiMaxAgeMinutes", MARKET_FEATURE_MAX_AGE_MINUTES);
        config.put("dexFlowMaxAgeMinutes", MARKET_FEATURE_MAX_AGE_MINUTES);
        config.put("spreadMaxAgeMinutes", MARKET_FEATURE_MAX_AGE_MINUTES);
    }

    public static String strategyConfigSha256(ObjectMapper objectMapper, Map<String, Object> strategyConfig) {
        return sha256(objectMapper, strategyConfig == null ? Map.of() : strategyConfig);
    }

    public static String effectiveConfigSha256(ObjectMapper objectMapper, Map<String, Object> strategyConfig) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("policyMode", POLICY_MODE);
        contract.put("strategyId", STRATEGY_ID);
        contract.put("symbol", SYMBOL);
        contract.put("intervalCode", INTERVAL);
        contract.put("source", KLINE_SOURCE);
        contract.put("entryExecution", "NEXT_1M_OPEN");
        contract.put("entryMaxDelayMinutes", ENTRY_MAX_DELAY_MINUTES);
        contract.put("exitExecution", "OCO_TP_SL_THEN_24H_MARKET");
        contract.put("holdHours", HOLD_HOURS);
        contract.put("notionalUsdt", NOTIONAL_USDT.toPlainString());
        contract.put("takeProfitPct", TAKE_PROFIT_PCT.toPlainString());
        contract.put("stopLossPct", STOP_LOSS_PCT.toPlainString());
        contract.put("historicalFeeRate", FEE_RATE.toPlainString());
        contract.put("historicalSlippageRate", SLIPPAGE_RATE.toPlainString());
        contract.put("maxCumulativeLossUsdt", MAX_CUMULATIVE_LOSS_USDT.toPlainString());
        contract.put("maxOpenPositions", MAX_OPEN_POSITIONS);
        contract.put("maxOrdersPerUtcDay", MAX_ORDERS_PER_DAY);
        contract.put("maxPilotOrders", MAX_PILOT_ORDERS);
        contract.put("pilotMaxDays", PILOT_MAX_DAYS);
        contract.put("strategyConfig", strategyConfig == null ? Map.of() : strategyConfig);
        return sha256(objectMapper, contract);
    }

    private static String sha256(ObjectMapper objectMapper, Object value) {
        if (objectMapper == null) return null;
        try {
            byte[] canonical = objectMapper.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private Strategy508TimeExitPolicy() {
    }
}
