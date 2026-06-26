package com.agora.service.trading;

import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.backtest.EntryFeatureSnapshot;
import com.agora.service.ml.MlTrainingOrchestrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreBuyMlGateDiagnosticService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final long DEFAULT_STRATEGY_ID = 485L;
    private static final String DEFAULT_INTERVAL = "1d";
    private static final int LOOKBACK_BARS = 260;
    private static final Pattern HW_SCHEMA_PATTERN = Pattern.compile(
            "Provided\\s*-\\s*\\[(?<provided>[^]]*)]\\s*vs\\s*Trained\\s*-\\s*\\[(?<trained>[^]]*)]",
            Pattern.CASE_INSENSITIVE);

    private final BtStrategyRepository strategyRepository;
    private final MdKlineRepository klineRepository;
    private final JdbcTemplate jdbcTemplate;
    private final MlTrainingOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public String diagnose(String symbol, Long strategyId, String intervalCode) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        String interval = normalizeInterval(intervalCode);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "diagnoseScoreBuyMlGate");
        root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence write behavior changed.");
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("symbol", sym);
        root.put("strategyId", sid);
        root.put("intervalCode", interval);
        root.put("orderSent", false);
        root.put("ocoModified", false);
        root.put("telegramSent", false);
        root.put("writesRuntimeEvidence", false);

        Optional<BtStrategy> strategyOpt = strategyRepository.findById(sid);
        if (strategyOpt.isEmpty()) {
            root.put("scorebuy_ml_gate_status", "BLOCKED_STRATEGY_NOT_FOUND");
            root.put("decision", "BLOCK");
            putArray(root, "missingRequirements", List.of("strategy row exists"));
            root.put("notAuthorization", notAuthorization());
            return write(root);
        }
        BtStrategy strategy = strategyOpt.get();
        JsonNode config = readConfig(strategy.getConfigJson());
        String modelName = config.path("mlModelName").asText("signal_scorer");
        double buyThreshold = config.path("buyThreshold").asDouble(0.7);
        interval = config.path("runIntervalCode").asText(interval);

        ObjectNode strategyNode = root.putObject("strategy");
        strategyNode.put("id", strategy.getId());
        strategyNode.put("name", strategy.getName());
        strategyNode.put("type", strategy.getStrategyType());
        strategyNode.put("enabled", Boolean.TRUE.equals(strategy.getEnabled()));
        strategyNode.put("notifyOnly", config.path("notifyOnly").asBoolean(false));
        strategyNode.put("buyThreshold", round(buyThreshold, 6));
        strategyNode.put("mlModelName", modelName);

        PromotedModel promoted = findPromotedModel(modelName);
        ObjectNode modelNode = root.putObject("promotedModel");
        modelNode.put("modelName", modelName);
        modelNode.put("promotedModelVersion", promoted.id());
        modelNode.put("registryVersion", promoted.version());
        modelNode.put("status", promoted.status());
        modelNode.put("trainingView", nullTo(promoted.trainingView(), "UNKNOWN"));
        modelNode.put("hasHeatwaveHandle", promoted.heatwaveHandle() != null && !promoted.heatwaveHandle().isBlank());
        root.put("promotedModelVersion", promoted.id());

        List<String> expectedFeatures = expectedScoreBuyFeatures();
        List<String> trainedFeatures = promoted.trainedFeatures().isEmpty() ? expectedFeatures : promoted.trainedFeatures();
        putArray(root, "expectedFeatures", expectedFeatures);
        putArray(root, "trainedFeatures", trainedFeatures);

        List<MdKline> bars = loadBars(sym, interval);
        ObjectNode sourceNode = root.putObject("source");
        sourceNode.put("barsUsed", bars.size());
        sourceNode.put("requiredBars", LOOKBACK_BARS);
        if (!bars.isEmpty()) {
            sourceNode.put("latestOpenTime", bars.get(bars.size() - 1).getOpenTime().toString());
        }
        if (bars.size() < Math.min(LOOKBACK_BARS, 60)) {
            root.put("scorebuy_ml_gate_status", "BLOCKED_INSUFFICIENT_KLINES");
            root.put("decision", "BLOCK");
            putArray(root, "providedFeatures", List.of());
            putArray(root, "missingRequirements", List.of("latest kline window has enough bars for ML feature snapshot"));
            root.put("notAuthorization", notAuthorization());
            return write(root);
        }

        Map<String, Object> features = buildFeatures(sid, sym, interval, bars, bars.size() - 1);
        List<String> providedFeatures = features.keySet().stream().sorted().toList();
        putArray(root, "providedFeatures", providedFeatures);
        putArray(root, "missingExpectedFeatures", diff(expectedFeatures, providedFeatures));
        putArray(root, "extraProvidedFeatures", diff(providedFeatures, expectedFeatures));

        ObjectNode featurePreview = root.putObject("featurePreview");
        for (String key : List.of("strategy_id", "is_short", "is_btc", "is_1h", "entry_price",
                "hour_of_day", "day_of_week", "adx14", "rsi14", "atr_pct",
                "volume_ratio_ma20", "bb_width_pct", "dd_50bar_pct", "dist_from_ema200_pct")) {
            putFeature(featurePreview, key, features.get(key));
        }

        List<String> missingRequirements = new ArrayList<>();
        if (promoted.id() <= 0) {
            missingRequirements.add("PROMOTED model exists for " + modelName);
        }
        if (promoted.heatwaveHandle() == null || promoted.heatwaveHandle().isBlank()) {
            missingRequirements.add("PROMOTED model has heatwave_handle");
        }
        if (!missingRequirements.isEmpty()) {
            root.put("scorebuy_ml_gate_status", "BLOCKED_NO_PROMOTED_MODEL");
            root.put("decision", "BLOCK");
            putArray(root, "missingRequirements", missingRequirements);
            root.put("failureOwner", "MODEL_PROMOTION");
            root.put("notAuthorization", notAuthorization());
            return write(root);
        }

        try {
            String predJson = orchestrator.predictOne(promoted.heatwaveHandle(), features);
            Double pWin = extractPWin(predJson);
            root.put("predictionStatus", pWin == null ? "NO_PWIN_IN_RESPONSE" : "PREDICTED");
            if (pWin != null) {
                root.put("pWin", round(pWin, 6));
                root.put("buyThreshold", round(buyThreshold, 6));
                root.put("decision", pWin >= buyThreshold ? "ML_GATE_PASS_REVIEW_ONLY" : "ML_GATE_BLOCK");
                root.put("scorebuy_ml_gate_status", pWin >= buyThreshold
                        ? "READY_ML_GATE_PASS_NOT_EXECUTION_AUTHORIZATION"
                        : "READY_ML_GATE_BLOCK_NOT_EXECUTION_AUTHORIZATION");
            } else {
                root.put("decision", "BLOCK");
                root.put("scorebuy_ml_gate_status", "BLOCKED_NO_PWIN_IN_RESPONSE");
                missingRequirements.add("HeatWave response contains p_win probability");
            }
            root.put("failureOwner", "NONE");
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            root.put("predictionStatus", "PREDICT_ERROR");
            root.put("predictError", message);
            root.put("decision", "BLOCK");
            root.put("scorebuy_ml_gate_status", classifyPredictError(message));
            SchemaMismatch mismatch = parseSchemaMismatch(message);
            putArray(root, "schemaMismatchProvided", mismatch.provided());
            putArray(root, "schemaMismatchTrained", mismatch.trained());
            putArray(root, "schemaMissingFromProvided", diff(mismatch.trained(), mismatch.provided()));
            putArray(root, "schemaExtraProvided", diff(mismatch.provided(), mismatch.trained()));
            root.put("failureOwner", classifyFailureOwner(message, expectedFeatures, mismatch));
            missingRequirements.add("HeatWave predictOne succeeds with ScoreBuy feature vector");
        }

        putArray(root, "missingRequirements", missingRequirements);
        root.put("notAuthorization", notAuthorization());
        return write(root);
    }

    private Map<String, Object> buildFeatures(long strategyId, String symbol, String intervalCode,
                                              List<MdKline> bars, int index) {
        java.util.LinkedHashMap<String, Object> f = new java.util.LinkedHashMap<>();
        MdKline bar = bars.get(index);
        f.put("strategy_id", strategyId);
        f.put("is_short", 0);
        f.put("is_btc", "BTCUSDT".equalsIgnoreCase(symbol) ? 1 : 0);
        f.put("is_1h", "1h".equalsIgnoreCase(intervalCode) ? 1 : 0);
        f.put("entry_price", bar.getClosePrice().doubleValue());
        LocalDateTime t = bar.getOpenTime();
        f.put("hour_of_day", t.getHour());
        int javaDow = t.getDayOfWeek().getValue();
        f.put("day_of_week", javaDow == 7 ? 1 : javaDow + 1);
        EntryFeatureSnapshot.compute(bars, index).forEach((k, v) -> {
            if (v != null) f.put(k, v);
        });
        for (String key : EntryFeatureSnapshot.ALL_FEATURE_KEYS) f.putIfAbsent(key, null);
        for (String key : EntryFeatureSnapshot.STATIC_FEATURE_KEYS) f.putIfAbsent(key, null);
        return f;
    }

    private List<MdKline> loadBars(String symbol, String intervalCode) {
        List<MdKline> rows = new ArrayList<>(klineRepository.findBySymbolAndIntervalCodeOrderByOpenTimeDesc(
                symbol, intervalCode, PageRequest.of(0, LOOKBACK_BARS)));
        rows.sort(Comparator.comparing(MdKline::getOpenTime));
        return rows;
    }

    private PromotedModel findPromotedModel(String modelName) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, version, status, heatwave_handle, training_view, feature_importance_json "
                            + "FROM ml_model_registry WHERE model_name = ? AND status = 'PROMOTED' LIMIT 1",
                    modelName);
            if (rows.isEmpty()) {
                return PromotedModel.none(modelName);
            }
            Map<String, Object> row = rows.get(0);
            return new PromotedModel(
                    asLong(row.get("id")),
                    asLong(row.get("version")),
                    nullTo((String) row.get("status"), "UNKNOWN"),
                    (String) row.get("heatwave_handle"),
                    (String) row.get("training_view"),
                    parseFeatureImportanceKeys(row.get("feature_importance_json")));
        } catch (Exception e) {
            log.warn("[ScoreBuyMlGateDiagnostic] promoted model lookup failed: {}", e.getMessage());
            return PromotedModel.none(modelName);
        }
    }

    private List<String> parseFeatureImportanceKeys(Object raw) {
        if (raw == null) return List.of();
        try {
            JsonNode node = objectMapper.readTree(String.valueOf(raw));
            Set<String> keys = new LinkedHashSet<>();
            if (node.isObject()) {
                node.fieldNames().forEachRemaining(keys::add);
            } else if (node.isArray()) {
                for (JsonNode item : node) {
                    String name = item.path("feature").asText(item.path("name").asText(""));
                    if (!name.isBlank()) keys.add(name);
                }
            }
            return keys.stream().sorted().toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Double extractPWin(String predJson) {
        try {
            JsonNode root = objectMapper.readTree(predJson);
            JsonNode probs = root.path("ml_results").path("probabilities");
            if (probs.isMissingNode() || probs.isNull()) probs = root.path("probabilities");
            JsonNode p1 = probs.get("1");
            return p1 == null ? null : p1.asDouble();
        } catch (Exception e) {
            return null;
        }
    }

    private String classifyPredictError(String message) {
        return message != null && message.contains("ML003011")
                ? "BLOCKED_SCHEMA_MISMATCH"
                : "BLOCKED_PREDICT_ERROR";
    }

    private String classifyFailureOwner(String message, List<String> expectedFeatures, SchemaMismatch mismatch) {
        if (message == null || !message.contains("ML003011")) return "HEATWAVE_OR_MODEL_RUNTIME";
        if (!mismatch.trained().isEmpty() && !expectedFeatures.containsAll(mismatch.trained())) {
            return "MODEL_RETRAIN_OR_FEATURE_BUILDER_ALIGNMENT";
        }
        return "FEATURE_BUILDER_ALIGNMENT";
    }

    private SchemaMismatch parseSchemaMismatch(String message) {
        if (message == null) return new SchemaMismatch(List.of(), List.of());
        Matcher matcher = HW_SCHEMA_PATTERN.matcher(message);
        if (!matcher.find()) return new SchemaMismatch(List.of(), List.of());
        return new SchemaMismatch(parseQuotedList(matcher.group("provided")), parseQuotedList(matcher.group("trained")));
    }

    private List<String> parseQuotedList(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String cleaned = part.trim().replace("'", "").replace("\"", "");
            if (!cleaned.isBlank()) out.add(cleaned);
        }
        return out.stream().sorted().toList();
    }

    private List<String> expectedScoreBuyFeatures() {
        List<String> keys = new ArrayList<>();
        keys.addAll(EntryFeatureSnapshot.ALL_FEATURE_KEYS);
        keys.addAll(EntryFeatureSnapshot.STATIC_FEATURE_KEYS);
        return keys.stream().distinct().sorted().toList();
    }

    private List<String> diff(List<String> left, List<String> right) {
        if (left == null || left.isEmpty()) return List.of();
        Set<String> rightSet = right == null ? Set.of() : new java.util.HashSet<>(right);
        return left.stream().filter(v -> !rightSet.contains(v)).sorted().toList();
    }

    private void putArray(ObjectNode node, String name, List<String> values) {
        ArrayNode array = node.putArray(name);
        if (values != null) values.forEach(array::add);
    }

    private void putFeature(ObjectNode node, String key, Object value) {
        if (value == null) {
            node.putNull(key);
        } else if (value instanceof Number n) {
            node.put(key, n.doubleValue());
        } else {
            node.put(key, String.valueOf(value));
        }
    }

    private JsonNode readConfig(String configJson) {
        try {
            return objectMapper.readTree(configJson == null || configJson.isBlank() ? "{}" : configJson);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String write(ObjectNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? DEFAULT_SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeInterval(String intervalCode) {
        return intervalCode == null || intervalCode.isBlank() ? DEFAULT_INTERVAL : intervalCode.trim();
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static double round(double value, int scale) {
        if (!Double.isFinite(value)) return value;
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private static String nullTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String notAuthorization() {
        return "read-only ScoreBuy ML gate diagnostic only; does not authorize live trading, ScoreBuy execution, strategy activation, orders, OCO modification, grid/fund/Earn/Telegram/exchange mutation, DB writes, deploy, production env changes, external backfill/import, model promotion, or retraining";
    }

    private record PromotedModel(long id, long version, String status, String heatwaveHandle,
                                 String trainingView, List<String> trainedFeatures) {
        static PromotedModel none(String modelName) {
            return new PromotedModel(0, 0, "NO_PROMOTED_MODEL:" + modelName, null, null, List.of());
        }
    }

    private record SchemaMismatch(List<String> provided, List<String> trained) {}
}
