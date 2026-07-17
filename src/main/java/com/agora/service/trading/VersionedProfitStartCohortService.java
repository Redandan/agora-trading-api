package com.agora.service.trading;

import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.service.backtest.LiveSignalEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Fail-closed identity binding for VERSIONED_PROFIT_START_ACCEPTANCE_V1.
 *
 * <p>The cohort is derived from the deployed commit, the complete local
 * TradingView execution configuration, the effective signal source, the
 * execution mode, and an explicitly persisted effective-from timestamp. It
 * does not enable execution. The selected execution lane must still pass all
 * existing live, order, exposure, freshness, event-risk, and OCO gates.</p>
 */
@Service
@RequiredArgsConstructor
public class VersionedProfitStartCohortService {

    public static final String CONTRACT_VERSION = "VERSIONED_PROFIT_START_ACCEPTANCE_V1";
    public static final String STRATEGY_FAMILY = "SCORE_BUY_V2";
    public static final String MODEL_VERSION = "local-tradingview-parity-v1";
    public static final String EXACT_EVIDENCE_BLOCKER =
            "EXACT_IMMUTABLE_ALL_FILL_SIGNED_FEE_BINDING_NOT_IMPLEMENTED";
    public static final String METRIC_READER_BLOCKER =
            "CURRENT_COHORT_CANONICAL_METRIC_READER_NOT_IMPLEMENTED";
    public static final String TINY_LIVE_HARD_GATE_BLOCKER =
            "TINY_LIVE_HARD_GATE_SNAPSHOT_NOT_IMPLEMENTED";

    private static final long STRATEGY_ID = 485L;
    private static final String SYMBOL = "BTCUSDT";
    private static final String PREFIX = "trading.versioned-profit-start.cohort.";
    private static final Pattern FULL_COMMIT = Pattern.compile("^[0-9a-fA-F]{40}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern SAFE_COHORT = Pattern.compile("^[A-Z0-9._:-]{1,120}$");

    private final TradingViewLocalSignalProperties localProps;
    private final TradingSignalSourcePolicy signalSourcePolicy;
    private final BtStrategyRepository strategyRepository;
    private final ObjectMapper objectMapper;
    private final Environment environment;

    public Snapshot snapshot() {
        boolean enabled = environment.getProperty(PREFIX + "enabled", Boolean.class, false);
        String codeCommit = resolveCodeCommit();
        BtStrategy strategy = null;
        boolean strategyReadFailed = false;
        try {
            strategy = strategyRepository.findById(STRATEGY_ID).orElse(null);
        } catch (Exception ignored) {
            strategyReadFailed = true;
        }
        String configSha256;
        try {
            configSha256 = configSha256(strategy);
        } catch (Exception ignored) {
            configSha256 = "UNKNOWN";
        }
        String signalSource = signalSourcePolicy.primary();
        String executionMode = localProps.executionMode().name();
        String effectiveFromRaw = trim(environment.getProperty(PREFIX + "effective-from"));
        Instant effectiveFrom = parseInstant(effectiveFromRaw);
        List<String> blockers = new ArrayList<>();

        if (!enabled) blockers.add("COHORT_NOT_ENABLED");
        if (!FULL_COMMIT.matcher(codeCommit).matches()) blockers.add("DEPLOYED_CODE_COMMIT_UNAVAILABLE");
        if (effectiveFromRaw.isBlank()) blockers.add("EFFECTIVE_FROM_NOT_CONFIGURED");
        else if (effectiveFrom == null) blockers.add("EFFECTIVE_FROM_INVALID");
        else if (effectiveFrom.isAfter(Instant.now())) blockers.add("EFFECTIVE_FROM_IN_FUTURE");
        if (!localProps.enabled()) blockers.add("LOCAL_TRADINGVIEW_EVALUATOR_DISABLED");
        if (localProps.strategyId() != STRATEGY_ID) blockers.add("STRATEGY_ID_NOT_485");
        if (strategyReadFailed) blockers.add("STRATEGY_485_CONFIG_READ_FAILED");
        else if (strategy == null) blockers.add("STRATEGY_485_CONFIG_UNAVAILABLE");
        else {
            if (!STRATEGY_FAMILY.equals(normalize(strategy.getStrategyType()))) {
                blockers.add("STRATEGY_FAMILY_NOT_SCORE_BUY_V2");
            }
            if (!csvContains(strategy.getSymbols(), SYMBOL, true)) {
                blockers.add("STRATEGY_CONFIG_SYMBOL_NOT_BTCUSDT");
            }
        }
        if (!SHA256.matcher(configSha256).matches()) blockers.add("CONFIG_SHA256_UNAVAILABLE");
        if (!csvContains(localProps.allowedSymbols(), SYMBOL, true)) blockers.add("SYMBOL_NOT_ALLOWLISTED_BTCUSDT");
        if (!csvContains(localProps.allowedIntervals(), "1d", false)) blockers.add("INTERVAL_NOT_ALLOWLISTED_1D");
        if (!csvContains(localProps.allowedSources(), "binance", false)) blockers.add("SOURCE_NOT_ALLOWLISTED_BINANCE");
        if (!"LOCAL_TRADINGVIEW".equals(signalSource)) blockers.add("SIGNAL_SOURCE_NOT_LOCAL_TRADINGVIEW");
        if (!isTinyLiveMode(executionMode)) blockers.add("EXECUTION_MODE_NOT_TINY_LIVE");

        String cohortId = blockers.isEmpty()
                ? cohortId(codeCommit, configSha256, effectiveFrom)
                : "NOT_STARTED";
        if (!"NOT_STARTED".equals(cohortId) && !SAFE_COHORT.matcher(cohortId).matches()) {
            blockers.add("DERIVED_COHORT_ID_INVALID");
            cohortId = "NOT_STARTED";
        }
        boolean identityReady = blockers.isEmpty();
        List<String> activationBlockers = List.of(
                TINY_LIVE_HARD_GATE_BLOCKER,
                METRIC_READER_BLOCKER,
                EXACT_EVIDENCE_BLOCKER);
        boolean activationReady = activationBlockers.isEmpty();
        return new Snapshot(
                CONTRACT_VERSION,
                identityReady ? "COHORT_IDENTITY_READY_ACTIVATION_BLOCKED" : "NOT_STARTED",
                enabled,
                identityReady,
                activationReady,
                cohortId,
                STRATEGY_ID,
                STRATEGY_FAMILY,
                SYMBOL,
                codeCommit,
                configSha256,
                MODEL_VERSION,
                signalSource,
                executionMode,
                effectiveFrom,
                List.copyOf(blockers),
                activationBlockers,
                activationBlockers,
                true,
                false);
    }

    public String liveExecutionBlocker(long strategyId, String symbol, String executionMode) {
        return liveExecutionBlocker(snapshot(), strategyId, symbol, executionMode);
    }

    public String liveExecutionBlocker(Snapshot snapshot, long strategyId, String symbol, String executionMode) {
        if (snapshot == null) return "VERSIONED_PROFIT_START_COHORT_SNAPSHOT_MISSING";
        if (!snapshot.identityReady()) {
            return "VERSIONED_PROFIT_START_COHORT_NOT_READY:" + String.join(",", snapshot.identityBlockers());
        }
        if (strategyId != snapshot.strategyId()) return "VERSIONED_PROFIT_START_STRATEGY_MISMATCH";
        if (!snapshot.symbol().equals(normalizeSymbol(symbol))) {
            return "VERSIONED_PROFIT_START_SYMBOL_MISMATCH";
        }
        if (!snapshot.executionMode().equals(normalize(executionMode))) {
            return "VERSIONED_PROFIT_START_EXECUTION_MODE_MISMATCH";
        }
        if (!snapshot.activationReady()) {
            return "VERSIONED_PROFIT_START_ACTIVATION_NOT_READY:"
                    + String.join(",", snapshot.activationBlockers());
        }
        return null;
    }

    public String currentCohortMarker() {
        return currentCohortMarker(snapshot());
    }

    public String currentCohortMarker(Snapshot snapshot) {
        return snapshot.identityReady() ? "|COHORT:" + snapshot.cohortId() : "";
    }

    public void bind(Map<String, Object> context) {
        bind(context, snapshot());
    }

    public void bind(Map<String, Object> context, Snapshot snapshot) {
        if (context == null) return;
        context.put("versionedProfitStartCohort", asMap(snapshot));
    }

    public void bind(ObjectNode node) {
        if (node == null) return;
        node.set("versionedProfitStartCohort", asJson(snapshot()));
    }

    public String status() {
        ObjectNode root = asJson(snapshot());
        root.put("tool", "getVersionedProfitStartCohortStatus");
        root.put("boundary", "READ_ONLY; does not enable live execution, place orders, modify OCO/Grid, send Telegram, or write DB/provider evidence.");
        ObjectNode executionJudgmentSemantics = root.putObject("executionJudgmentSemantics");
        ObjectNode expectedValueGate = executionJudgmentSemantics.putObject("expectedValueGate");
        expectedValueGate.put("decimalScale", LiveSignalEvaluator.EXPECTED_VALUE_DECIMAL_SCALE);
        expectedValueGate.put("roundingMode", LiveSignalEvaluator.EXPECTED_VALUE_ROUNDING_MODE.name());
        expectedValueGate.put("comparison", "normalizedExpectedR > 0 && normalizedExpectedR >= normalizedMinExpectedR");
        LiveSignalEvaluator.ExpectedValueThresholdDecision boundaryProbe =
                LiveSignalEvaluator.evaluateExpectedValueThreshold(0.19999990000002504, 0.2);
        expectedValueGate.put("boundaryProbeRawExpectedR", "0.19999990000002504");
        expectedValueGate.put("boundaryProbeMinExpectedR", "0.2");
        expectedValueGate.put("boundaryProbeNormalizedExpectedR",
                boundaryProbe.normalizedExpectedR().toPlainString());
        expectedValueGate.put("boundaryProbeNormalizedMinExpectedR",
                boundaryProbe.normalizedMinExpectedR().toPlainString());
        expectedValueGate.put("boundaryProbePassed", boundaryProbe.passed());
        expectedValueGate.put("invalidInputsFailClosed", true);
        root.put("currentCohortCountsStatus", "NOT_MEASURABLE_COHORT_METRIC_READER_NOT_IMPLEMENTED");
        root.putNull("currentCohortClosedEpisodes");
        root.putNull("currentCohortExactFeeEpisodes");
        root.putNull("currentCohortPositiveExactNetEpisodes");
        root.put("sessionMustRemainOpen", true);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception ignored) {
            return root.toString();
        }
    }

    private ObjectNode asJson(Snapshot snapshot) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("contractVersion", snapshot.contractVersion());
        node.put("state", snapshot.state());
        node.put("enabled", snapshot.enabled());
        node.put("identityReady", snapshot.identityReady());
        node.put("activationReady", snapshot.activationReady());
        node.put("cohortId", snapshot.cohortId());
        node.put("strategyId", snapshot.strategyId());
        node.put("strategyFamily", snapshot.strategyFamily());
        node.put("symbol", snapshot.symbol());
        node.put("codeCommit", snapshot.codeCommit());
        node.put("configSha256", snapshot.configSha256());
        node.put("modelVersion", snapshot.modelVersion());
        node.put("signalSource", snapshot.signalSource());
        node.put("executionMode", snapshot.executionMode());
        if (snapshot.effectiveFrom() == null) node.putNull("effectiveFrom");
        else node.put("effectiveFrom", snapshot.effectiveFrom().toString());
        ArrayNode identityBlockers = node.putArray("identityBlockers");
        snapshot.identityBlockers().forEach(identityBlockers::add);
        ArrayNode activationBlockers = node.putArray("activationBlockers");
        snapshot.activationBlockers().forEach(activationBlockers::add);
        ArrayNode finalBlockers = node.putArray("finalAcceptanceBlockers");
        snapshot.finalAcceptanceBlockers().forEach(finalBlockers::add);
        node.put("legacyRowsExcluded", snapshot.legacyRowsExcluded());
        node.put("exactNetAcceptanceAllowed", snapshot.exactNetAcceptanceAllowed());
        return node;
    }

    private Map<String, Object> asMap(Snapshot snapshot) {
        Map<String, Object> out = new TreeMap<>();
        out.put("contractVersion", snapshot.contractVersion());
        out.put("state", snapshot.state());
        out.put("identityReady", snapshot.identityReady());
        out.put("activationReady", snapshot.activationReady());
        out.put("cohortId", snapshot.cohortId());
        out.put("strategyId", snapshot.strategyId());
        out.put("strategyFamily", snapshot.strategyFamily());
        out.put("symbol", snapshot.symbol());
        out.put("codeCommit", snapshot.codeCommit());
        out.put("configSha256", snapshot.configSha256());
        out.put("modelVersion", snapshot.modelVersion());
        out.put("signalSource", snapshot.signalSource());
        out.put("executionMode", snapshot.executionMode());
        out.put("effectiveFrom", snapshot.effectiveFrom() == null ? "" : snapshot.effectiveFrom().toString());
        out.put("identityBlockers", snapshot.identityBlockers());
        out.put("activationBlockers", snapshot.activationBlockers());
        out.put("legacyRowsExcluded", snapshot.legacyRowsExcluded());
        return out;
    }

    private String configSha256(BtStrategy strategy) {
        TreeMap<String, String> values = new TreeMap<>();
        values.put("allowedIntervals", normalizeCsv(localProps.allowedIntervals(), false));
        values.put("allowedSources", normalizeCsv(localProps.allowedSources(), false));
        values.put("allowedSymbols", normalizeCsv(localProps.allowedSymbols(), true));
        values.put("btcBaseMaxExposureUsdt", decimal(localProps.btcBaseMaxExposureUsdt()));
        values.put("catchUpBars", String.valueOf(localProps.catchUpBars()));
        values.put("defaultNotionalUsdt", decimal(localProps.defaultNotionalUsdt()));
        values.put("enabled", String.valueOf(localProps.enabled()));
        values.put("executionDryRun", String.valueOf(localProps.effectiveExecutionDryRun()));
        values.put("executionEnabled", String.valueOf(localProps.effectiveExecutionEnabled()));
        values.put("executionLiveOrderEnabled", String.valueOf(localProps.effectiveExecutionLiveOrderEnabled()));
        values.put("executionMaxOpenPositions", String.valueOf(localProps.executionMaxOpenPositions()));
        values.put("executionMaxOrdersPerBar", String.valueOf(localProps.executionMaxOrdersPerBar()));
        values.put("executionMaxOrdersPerDay", String.valueOf(localProps.executionMaxOrdersPerDay()));
        values.put("executionMode", localProps.executionMode().name());
        values.put("executionStopLossPct", decimal(localProps.executionStopLossPct()));
        values.put("executionTakeProfitPct", decimal(localProps.executionTakeProfitPct()));
        values.put("historyBars", String.valueOf(localProps.historyBars()));
        values.put("maxNotionalUsdt", decimal(localProps.maxNotionalUsdt()));
        values.put("maxSignalAgeHours", String.valueOf(localProps.maxSignalAgeHours()));
        values.put("signalSourcePrimary", signalSourcePolicy.primary());
        values.put("strategyId", String.valueOf(localProps.strategyId()));
        values.put("strategy.alphaSource", strategy == null ? "" : trim(strategy.getAlphaSource()));
        values.put("strategy.configFingerprint", strategy == null ? "" : trim(strategy.getConfigFingerprint()));
        values.put("strategy.configJson", strategy == null ? "" : trim(strategy.getConfigJson()));
        values.put("strategy.enabled", strategy == null ? "" : String.valueOf(strategy.getEnabled()));
        values.put("strategy.klineSource", strategy == null ? "" : normalize(strategy.getKlineSource()));
        values.put("strategy.name", strategy == null ? "" : trim(strategy.getName()));
        values.put("strategy.strategyType", strategy == null ? "" : normalize(strategy.getStrategyType()));
        values.put("strategy.symbols", strategy == null ? "" : normalizeCsv(strategy.getSymbols(), true));
        values.put("strategy.triggerConditions", strategy == null ? "" : trim(strategy.getTriggerConditions()));
        try {
            return sha256(objectMapper.writeValueAsString(values));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to canonicalize versioned-profit config", e);
        }
    }

    private String resolveCodeCommit() {
        for (String key : List.of("app.git.commit", "git.commit.id")) {
            String candidate = trim(environment.getProperty(key));
            if (FULL_COMMIT.matcher(candidate).matches()) return candidate.toLowerCase(Locale.ROOT);
        }
        try {
            Path commitFile = Path.of("app.commit");
            if (Files.isRegularFile(commitFile)) {
                String candidate = Files.readString(commitFile, StandardCharsets.UTF_8).trim();
                if (FULL_COMMIT.matcher(candidate).matches()) return candidate.toLowerCase(Locale.ROOT);
            }
        } catch (Exception ignored) {
            // Missing/unreadable deploy metadata fails closed in snapshot().
        }
        return "UNKNOWN";
    }

    private String cohortId(String commit, String configHash, Instant effectiveFrom) {
        String effectiveHash = sha256(effectiveFrom.toString()).substring(0, 8).toUpperCase(Locale.ROOT);
        return "VPSTART1-485-BTCUSDT-"
                + commit.substring(0, 8).toUpperCase(Locale.ROOT) + "-"
                + configHash.substring(0, 12).toUpperCase(Locale.ROOT) + "-"
                + effectiveHash;
    }

    private boolean isTinyLiveMode(String mode) {
        return "LIVE_MICRO".equals(mode) || "BTC_BASE_LIVE_MICRO".equals(mode);
    }

    private boolean csvContains(String csv, String expected, boolean symbol) {
        String target = symbol ? normalizeSymbol(expected) : normalize(expected).toLowerCase(Locale.ROOT);
        return java.util.Arrays.stream(csv == null ? new String[0] : csv.split(","))
                .map(value -> symbol ? normalizeSymbol(value) : normalize(value).toLowerCase(Locale.ROOT))
                .anyMatch(target::equals);
    }

    private String normalizeCsv(String csv, boolean symbol) {
        return java.util.Arrays.stream(csv == null ? new String[0] : csv.split(","))
                .map(value -> symbol ? normalizeSymbol(value) : normalize(value).toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private String normalizeSymbol(String raw) {
        return normalize(raw).toUpperCase(Locale.ROOT).replace("-", "").replace("/", "").replace("_", "");
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Snapshot(String contractVersion,
                           String state,
                           boolean enabled,
                           boolean identityReady,
                           boolean activationReady,
                           String cohortId,
                           long strategyId,
                           String strategyFamily,
                           String symbol,
                           String codeCommit,
                           String configSha256,
                           String modelVersion,
                           String signalSource,
                           String executionMode,
                           Instant effectiveFrom,
                           List<String> identityBlockers,
                           List<String> activationBlockers,
                           List<String> finalAcceptanceBlockers,
                           boolean legacyRowsExcluded,
                           boolean exactNetAcceptanceAllowed) {
    }
}
