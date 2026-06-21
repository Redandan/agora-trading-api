package com.agora.service.backtest;

import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
class DataFreshnessShadowReplayCandidatePlanBuilder {

    static final String FIXED_CONFIG_SNAPSHOT_ONLY = "FIXED_CONFIG_SNAPSHOT_ONLY";
    static final String NOT_REPLAYABLE_DYNAMIC_ATR_CONFIG = "NOT_REPLAYABLE_DYNAMIC_ATR_CONFIG";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    Optional<CandidatePlan> build(BtStrategy strategy, MdKline newest) {
        if (strategy == null || newest == null || newest.getClosePrice() == null) {
            return Optional.empty();
        }
        Map<String, Object> config = parseConfig(strategy.getConfigJson());
        if (usesDynamicAtrPlan(config)) {
            return Optional.of(CandidatePlan.unavailable(NOT_REPLAYABLE_DYNAMIC_ATR_CONFIG));
        }

        BigDecimal entry = newest.getClosePrice();
        double stopLossPct = clamp(getDouble(config, "fixedStopLossPct", 0.05), 0.001, 0.50);
        double takeProfitPct = clamp(getDouble(config, "fixedTakeProfitPct", 0.10), 0.001, 1.00);
        int maxHoldingHours = getInt(config, "maxHoldingHours", 0);
        double[] capped = applyHorizonCap(stopLossPct, takeProfitPct, maxHoldingHours);
        stopLossPct = capped[0];
        takeProfitPct = capped[1];

        BigDecimal sl = entry.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(stopLossPct)))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal tp = entry.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(takeProfitPct)))
                .setScale(2, RoundingMode.HALF_UP);
        return Optional.of(new CandidatePlan(
                true,
                FIXED_CONFIG_SNAPSHOT_ONLY,
                entry,
                tp,
                sl,
                stopLossPct,
                takeProfitPct,
                maxHoldingHours));
    }

    private Map<String, Object> parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(configJson, MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private boolean usesDynamicAtrPlan(Map<String, Object> config) {
        return getDouble(config, "atrSlMultiplier", 0.0) > 0.0
                || getDouble(config, "atrTpMultiplier", 0.0) > 0.0
                || getBoolean(config, "atrFallback", false)
                || hasText(config.get("higherTfForSl"));
    }

    private double[] applyHorizonCap(double slPct, double tpPct, int maxHoldingHours) {
        double tpCap;
        double slCap;
        if (maxHoldingHours <= 0) {
            tpCap = 0.20;
            slCap = 0.10;
        } else if (maxHoldingHours <= 12) {
            tpCap = 0.04;
            slCap = 0.03;
        } else if (maxHoldingHours <= 24) {
            tpCap = 0.06;
            slCap = 0.04;
        } else if (maxHoldingHours <= 48) {
            tpCap = 0.10;
            slCap = 0.06;
        } else if (maxHoldingHours <= 72) {
            tpCap = 0.15;
            slCap = 0.08;
        } else {
            tpCap = 0.20;
            slCap = 0.10;
        }
        return new double[]{Math.min(slPct, slCap), Math.min(tpPct, tpCap)};
    }

    private double getDouble(Map<String, Object> map, String key, double fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private int getInt(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean getBoolean(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
            if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
        }
        return fallback;
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    record CandidatePlan(
            boolean available,
            String source,
            BigDecimal entry,
            BigDecimal tp,
            BigDecimal sl,
            double stopLossPct,
            double takeProfitPct,
            int maxHoldingHours) {

        static CandidatePlan unavailable(String source) {
            return new CandidatePlan(false, source, null, null, null, 0.0, 0.0, 0);
        }
    }
}
