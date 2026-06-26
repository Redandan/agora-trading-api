package com.agora.service.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ScoreBuyMlFeatureSupport {

    private static final List<String> MARKET_INDICATORS = List.of(
            "fear_greed",
            "funding_rate",
            "oi_change_pct_1h",
            "whale_buy_ratio",
            "dex_wbtc_net_flow_usd_1h",
            "us_10y_yield",
            "us_vix",
            "btc_dvol"
    );

    public static void appendPromotedModelMarketIndicatorAliases(JdbcTemplate jdbc,
                                                                 Map<String, Object> features,
                                                                 String symbol) {
        Map<String, Double> values = latestMarketIndicators(jdbc, symbol);
        features.put("mih_fear_greed", values.get("fear_greed"));
        features.put("mih_funding_rate", values.get("funding_rate"));
        features.put("mih_oi_change_pct_1h", values.get("oi_change_pct_1h"));
        features.put("mih_whale_buy_ratio", values.get("whale_buy_ratio"));
        features.put("mih_dex_wbtc_net_flow", values.get("dex_wbtc_net_flow_usd_1h"));
        features.put("mih_us_10y_yield", values.get("us_10y_yield"));
        features.put("mih_us_vix", values.get("us_vix"));
        features.put("mih_btc_dvol", values.get("btc_dvol"));
    }

    public static Map<String, Object> alignToTrainedFeatures(Map<String, Object> features,
                                                             List<String> trainedFeatures) {
        if (trainedFeatures == null || trainedFeatures.isEmpty()) {
            return features;
        }
        Map<String, Object> aligned = new LinkedHashMap<>();
        for (String key : trainedFeatures) {
            aligned.put(key, features.getOrDefault(key, null));
        }
        return aligned;
    }

    public static List<String> parseFeatureImportanceKeys(ObjectMapper objectMapper, Object raw) {
        if (raw == null) return List.of();
        try {
            JsonNode node = objectMapper.readTree(String.valueOf(raw));
            Set<String> keys = new LinkedHashSet<>();
            collectFeatureImportanceKeys(node, keys);
            return keys.stream().sorted().toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static void collectFeatureImportanceKeys(JsonNode node, Set<String> keys) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isObject()) {
            JsonNode nested = node.get("permutation_importance");
            if (nested != null && nested.isObject()) {
                nested.fieldNames().forEachRemaining(keys::add);
                return;
            }
            node.fieldNames().forEachRemaining(keys::add);
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String name = item.path("feature").asText(item.path("name").asText(""));
                if (!name.isBlank()) keys.add(name);
            }
        }
    }

    private static Map<String, Double> latestMarketIndicators(JdbcTemplate jdbc, String symbol) {
        try {
            String placeholders = String.join(",", MARKET_INDICATORS.stream().map(v -> "?").toList());
            List<Object> args = new ArrayList<>();
            args.add(symbol);
            args.addAll(MARKET_INDICATORS);
            args.add(symbol);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ mih.indicator, mih.value "
                            + "FROM market_indicator_history mih "
                            + "INNER JOIN ("
                            + "  SELECT indicator, MAX(captured_at) AS max_at "
                            + "  FROM market_indicator_history "
                            + "  WHERE symbol = ? AND indicator IN (" + placeholders + ") "
                            + "  GROUP BY indicator"
                            + ") latest ON mih.indicator = latest.indicator AND mih.captured_at = latest.max_at "
                            + "WHERE mih.symbol = ?",
                    args.toArray());
            Map<String, Double> values = new HashMap<>();
            for (Map<String, Object> row : rows) {
                Object val = row.get("value");
                if (val instanceof Number n) {
                    values.put(String.valueOf(row.get("indicator")), n.doubleValue());
                }
            }
            return values;
        } catch (Exception ignored) {
            return Map.of();
        }
    }
}
