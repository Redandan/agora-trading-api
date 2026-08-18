package com.agora.research;

import com.agora.model.MdKline;
import com.agora.service.trading.BtcDonchianShadowEngine;
import com.agora.service.trading.BtcDonchianShadowPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline-only matched-capital historical screen for the already-frozen
 * BTC_DONCHIAN_20D_10D_V1 engine.
 *
 * <p>This main creates no Spring context and has no database, network,
 * exchange, order, scheduler, SHADOW, PAPER or LIVE path.</p>
 */
public final class BtcDonchianStandaloneHistoricalCli {

    private static final String AUTHORIZATION =
            "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE";
    private static final String EXPERIMENT_ID =
            "btc-donchian-20d-10d-standalone-historical-v1";
    private static final String MANIFEST_SHA256 =
            "0e76ef3bdcf4e30ae352cadd04eafdf4677cede3ac2b976790528ddc3c906ee8";
    private static final String INPUT_SHA256 =
            "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd";
    private static final int INPUT_ROWS = 52_608;
    private static final LocalDateTime INPUT_START =
            LocalDateTime.of(2019, 1, 1, 0, 0);
    private static final LocalDateTime INPUT_END =
            LocalDateTime.of(2025, 1, 1, 0, 0);
    private static final LocalDateTime DESIGN_END =
            LocalDateTime.of(2023, 1, 1, 0, 0);
    private static final List<Integer> ANNUAL_YEARS =
            List.of(2020, 2021, 2022, 2023, 2024);
    private static final DateTimeFormatter LEDGER_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    private static final double EPSILON = 1.0e-12;

    private final ObjectMapper mapper = new ObjectMapper();

    private BtcDonchianStandaloneHistoricalCli() {
    }

    public static void main(String[] args) {
        try {
            new BtcDonchianStandaloneHistoricalCli().run(Arguments.parse(args));
        } catch (Exception error) {
            System.err.println("DONCHIAN_HISTORICAL_REJECT: " + error.getMessage());
            error.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private void run(Arguments arguments) throws Exception {
        if (Files.exists(arguments.output())) {
            throw new IllegalArgumentException("OUTPUT_SEAL_REJECT: " + arguments.output());
        }
        JsonNode manifest = verifyManifest(arguments.manifest());
        verifyFrozenSources(manifest, Path.of("").toAbsolutePath().normalize());
        InputData input = loadInput(arguments.input());

        WindowResult design = replay(
                input.bars(), INPUT_START, DESIGN_END);
        WindowResult validation = replay(
                input.bars(), DESIGN_END, INPUT_END);
        Map<String, WindowResult> annual = new LinkedHashMap<>();
        for (int year : ANNUAL_YEARS) {
            annual.put(Integer.toString(year), replay(
                    input.bars(),
                    LocalDateTime.of(year, 1, 1, 0, 0),
                    LocalDateTime.of(year + 1, 1, 1, 0, 0)));
        }

        GateDecision decision = evaluateGates(design, validation, annual);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schema_version", "BTC_DONCHIAN_STANDALONE_HISTORICAL_SCREEN_V1");
        output.put("document_type", "BTC_DONCHIAN_STANDALONE_HISTORICAL_SCREEN_V1");
        output.put("status", decision.passed()
                ? "HISTORICAL_SCREEN_GATES_PASS"
                : "HISTORICAL_SCREEN_GATES_FAIL");
        output.put("authorization", AUTHORIZATION);
        output.put("experiment_id", EXPERIMENT_ID);
        output.put("manifest_sha256", MANIFEST_SHA256);
        Map<String, Object> datasetOutput = new LinkedHashMap<>();
        datasetOutput.put("sha256", input.sha256());
        datasetOutput.put("rows", input.bars().size());
        datasetOutput.put("first_open_time",
                input.bars().getFirst().getOpenTime().toString());
        datasetOutput.put("last_close_time",
                input.bars().getLast().getCloseTime().toString());
        output.put("dataset", datasetOutput);
        output.put("engine", BtcDonchianShadowEngine.class.getName());
        output.put("policy", BtcDonchianShadowPolicy.POLICY_MODE);
        Map<String, Object> windowsOutput = new LinkedHashMap<>();
        windowsOutput.put("design", design.output());
        windowsOutput.put("validation", validation.output());
        output.put("windows", windowsOutput);
        Map<String, Object> annualOutput = new LinkedHashMap<>();
        annual.forEach((year, result) -> annualOutput.put(year, result.output()));
        output.put("annual_fair_reset", annualOutput);
        output.put("gate_set", "BTC_DONCHIAN_STANDALONE_ECONOMIC_GATES_V1");
        output.put("gates", decision.gates());
        output.put("failed_gates", decision.failed());
        output.put("all_gates_passed", decision.passed());
        output.put("oos_opened", false);
        output.put("preliminary_disposition", decision.passed()
                ? "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED_AWAITING_BYTE_IDENTICAL_RERUN"
                : "NO_CANDIDATE_CLOSE_EXACT_FAMILY_AWAITING_BYTE_IDENTICAL_RERUN");
        output.put("claim_boundary",
                "Historical Design and Validation only. Determinism requires two byte-identical create-new outputs. Independent post-selection OOS, forward profitability and activation remain unproven.");

        Files.createDirectories(arguments.output().toAbsolutePath().normalize().getParent());
        Files.writeString(
                arguments.output(),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        System.out.println(mapper.writeValueAsString(Map.of(
                "status", output.get("status"),
                "failed_gate_count", decision.failed().size(),
                "output_sha256", sha256(arguments.output()))));
    }

    private JsonNode verifyManifest(Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("MANIFEST_MISSING");
        }
        String hash = sha256(path);
        if (!MANIFEST_SHA256.equals(hash)) {
            throw new IllegalArgumentException("MANIFEST_HASH_MISMATCH:" + hash);
        }
        JsonNode manifest = mapper.readTree(Files.readAllBytes(path));
        if (!EXPERIMENT_ID.equals(manifest.path("experiment_id").asText())
                || !AUTHORIZATION.equals(manifest.path("authorization").asText())
                || manifest.path("strategy_policy").path("variants").asInt(-1) != 1
                || manifest.path("determinism").path("reruns").asInt(-1) != 2) {
            throw new IllegalArgumentException("MANIFEST_CONTRACT_MISMATCH");
        }
        return manifest;
    }

    private void verifyFrozenSources(JsonNode manifest, Path repoRoot) throws Exception {
        JsonNode bindings = manifest.path("source_bindings");
        if (!bindings.isArray() || bindings.size() != 3) {
            throw new IllegalArgumentException("FROZEN_SOURCE_BINDINGS_INVALID");
        }
        for (JsonNode binding : bindings) {
            Path source = repoRoot.resolve(binding.path("path").asText()).normalize();
            if (!source.startsWith(repoRoot) || !Files.isRegularFile(source)) {
                throw new IllegalArgumentException("FROZEN_SOURCE_MISSING:" + source);
            }
            String actual = sha256(source);
            if (!binding.path("sha256").asText().equals(actual)) {
                throw new IllegalArgumentException("FROZEN_SOURCE_HASH_MISMATCH:"
                        + binding.path("path").asText() + ":" + actual);
            }
        }
    }

    private InputData loadInput(Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("DATA_REJECT_INPUT_MISSING");
        }
        String hash = sha256(path);
        if (!INPUT_SHA256.equals(hash)) {
            throw new IllegalArgumentException("DATA_REJECT_INPUT_HASH:" + hash);
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() != INPUT_ROWS) {
            throw new IllegalArgumentException("DATA_REJECT_INPUT_ROWS:" + lines.size());
        }
        List<MdKline> bars = new ArrayList<>(lines.size());
        LocalDateTime prior = null;
        for (int index = 0; index < lines.size(); index++) {
            String[] fields = lines.get(index).split("\\t", -1);
            if (fields.length != 7) {
                throw new IllegalArgumentException("DATA_REJECT_FIELDS:" + (index + 1));
            }
            MdKline bar = parseBar(fields, index + 1);
            if (prior != null && !prior.plusHours(1).equals(bar.getOpenTime())) {
                throw new IllegalArgumentException("DATA_REJECT_HOURLY_GAP:"
                        + prior + "->" + bar.getOpenTime());
            }
            prior = bar.getOpenTime();
            bars.add(bar);
        }
        if (!INPUT_START.equals(bars.getFirst().getOpenTime())
                || !INPUT_END.equals(bars.getLast().getCloseTime())) {
            throw new IllegalArgumentException("DATA_REJECT_WINDOW");
        }
        return new InputData(List.copyOf(bars), hash);
    }

    private MdKline parseBar(String[] fields, int row) {
        try {
            MdKline bar = new MdKline();
            bar.setSymbol(BtcDonchianShadowPolicy.SYMBOL);
            bar.setIntervalCode(BtcDonchianShadowPolicy.INTERVAL);
            bar.setSource(BtcDonchianShadowPolicy.SOURCE);
            bar.setOpenTime(LocalDateTime.parse(fields[0]));
            bar.setCloseTime(LocalDateTime.parse(fields[1]));
            bar.setOpenPrice(new BigDecimal(fields[2]));
            bar.setHighPrice(new BigDecimal(fields[3]));
            bar.setLowPrice(new BigDecimal(fields[4]));
            bar.setClosePrice(new BigDecimal(fields[5]));
            bar.setVolume(new BigDecimal(fields[6]));
            if (!bar.getOpenTime().plusHours(1).equals(bar.getCloseTime())
                    || bar.getOpenPrice().signum() <= 0
                    || bar.getHighPrice().signum() <= 0
                    || bar.getLowPrice().signum() <= 0
                    || bar.getClosePrice().signum() <= 0
                    || bar.getVolume().signum() < 0
                    || bar.getHighPrice().compareTo(bar.getOpenPrice().max(bar.getClosePrice())) < 0
                    || bar.getLowPrice().compareTo(bar.getOpenPrice().min(bar.getClosePrice())) > 0
                    || bar.getHighPrice().compareTo(bar.getLowPrice()) < 0) {
                throw new IllegalArgumentException("OHLCV_INVARIANT");
            }
            return bar;
        } catch (Exception error) {
            throw new IllegalArgumentException("DATA_REJECT_ROW_" + row + ":"
                    + error.getMessage(), error);
        }
    }

    private WindowResult replay(
            List<MdKline> allBars,
            LocalDateTime start,
            LocalDateTime endExclusive) {
        List<MdKline> bars = allBars.stream()
                .filter(bar -> !bar.getOpenTime().isBefore(start)
                        && bar.getOpenTime().isBefore(endExclusive))
                .toList();
        if (bars.isEmpty() || !start.equals(bars.getFirst().getOpenTime())
                || !endExclusive.equals(bars.getLast().getCloseTime())) {
            throw new IllegalArgumentException("DATA_REJECT_REPLAY_WINDOW:"
                    + start + "->" + endExclusive);
        }

        BtcDonchianShadowEngine engine =
                new BtcDonchianShadowEngine(new ObjectMapper());
        BtcDonchianShadowEngine.State state = engine.initialState();
        Map<String, PathAccumulator> paths = new LinkedHashMap<>();
        Map<String, List<Double>> tradePnls = new LinkedHashMap<>();
        Map<String, List<Double>> holdHours = new LinkedHashMap<>();
        for (String scenario : List.of("NORMAL", "STRESS")) {
            paths.put(scenario, new PathAccumulator());
            tradePnls.put(scenario, new ArrayList<>());
            holdHours.put(scenario, new ArrayList<>());
        }

        for (MdKline bar : bars) {
            BtcDonchianShadowEngine.StepResult step = engine.step(state, bar);
            state = step.state();
            for (String scenario : List.of("NORMAL", "STRESS")) {
                BtcDonchianShadowEngine.ScenarioState scenarioState =
                        state.getScenarios().get(scenario);
                double equity = scenarioState.getCurrentEquity();
                double exposure = equity > EPSILON
                        ? scenarioState.getQuantity()
                            * bar.getClosePrice().doubleValue() / equity
                        : 0.0;
                paths.get(scenario).observe(equity, exposure);
                for (Map<String, Object> trade : step.tradeLedgers().get(scenario)) {
                    double pnl = number(trade.get("profitLossEquityUnits"));
                    LocalDateTime entry = LocalDateTime.parse(
                            String.valueOf(trade.get("entryTimeUtc")), LEDGER_TIME);
                    LocalDateTime exit = LocalDateTime.parse(
                            String.valueOf(trade.get("exitTimeUtc")), LEDGER_TIME);
                    tradePnls.get(scenario).add(pnl);
                    holdHours.get(scenario).add(
                            (double) Duration.between(entry, exit).toHours());
                }
            }
        }

        Map<String, Metrics> candidate = new LinkedHashMap<>();
        Map<String, BenchmarkMetrics> buyHold = new LinkedHashMap<>();
        for (BtcDonchianShadowPolicy.Scenario scenario
                : List.of(BtcDonchianShadowPolicy.NORMAL, BtcDonchianShadowPolicy.STRESS)) {
            String name = scenario.name();
            BtcDonchianShadowEngine.ScenarioState finalState =
                    state.getScenarios().get(name);
            double total = (finalState.getCurrentEquity() - 1.0) * 100.0;
            double realized = tradePnls.get(name).stream()
                    .mapToDouble(Double::doubleValue).sum() * 100.0;
            double unrealized = total - realized;
            Double terminalHolding = finalState.getActiveTradeEntryTime() == null
                    ? null
                    : (double) Duration.between(
                            finalState.getActiveTradeEntryTime(),
                            bars.getLast().getCloseTime()).toHours();
            candidate.put(name, new Metrics(
                    realized,
                    unrealized,
                    total,
                    paths.get(name).maxDrawdownPct,
                    paths.get(name).maxUnderwaterHours,
                    finalState.getFees(),
                    finalState.getTurnover(),
                    paths.get(name).averageExposurePct(),
                    tradePnls.get(name).size(),
                    (int) tradePnls.get(name).stream().filter(value -> value > 0.0).count(),
                    percentile(holdHours.get(name), 0.5),
                    percentile(holdHours.get(name), 0.9),
                    finalState.getQuantity() > EPSILON,
                    terminalHolding,
                    contribution(tradePnls.get(name))));
            buyHold.put(name, buyAndHold(bars, scenario));
        }
        return new WindowResult(start, endExclusive, candidate, buyHold);
    }

    private BenchmarkMetrics buyAndHold(
            List<MdKline> bars,
            BtcDonchianShadowPolicy.Scenario scenario) {
        double feeRate = scenario.feeRatePerSide();
        double gross = 1.0 / (1.0 + feeRate);
        double fee = gross * feeRate;
        double fill = bars.getFirst().getOpenPrice().doubleValue()
                * (1.0 + scenario.adverseSlippageRatePerSide());
        double quantity = gross / fill;
        double cash = 1.0 - gross - fee;
        PathAccumulator path = new PathAccumulator();
        for (MdKline bar : bars) {
            double equity = cash + quantity * bar.getClosePrice().doubleValue();
            double exposure = equity > EPSILON
                    ? quantity * bar.getClosePrice().doubleValue() / equity : 0.0;
            path.observe(equity, exposure);
        }
        double total = (cash + quantity
                * bars.getLast().getClosePrice().doubleValue() - 1.0) * 100.0;
        return new BenchmarkMetrics(
                total,
                path.maxDrawdownPct,
                path.maxUnderwaterHours,
                fee,
                gross,
                path.averageExposurePct());
    }

    private GateDecision evaluateGates(
            WindowResult design,
            WindowResult validation,
            Map<String, WindowResult> annual) {
        Map<String, Boolean> gates = new LinkedHashMap<>();
        gates.put("dataset_sha256_and_52608_rows_match", true);
        gates.put("hourly_lattice_and_ohlcv_invariants_pass", true);
        gates.put("frozen_engine_policy_and_entity_sha256_match", true);

        Metrics designNormal = design.candidate().get("NORMAL");
        Metrics designStress = design.candidate().get("STRESS");
        BenchmarkMetrics designBuyHold = design.buyHold().get("NORMAL");
        gates.put("design_normal_total_return_pct_gt_0", designNormal.totalReturnPct() > 0.0);
        gates.put("design_stress_total_return_pct_gt_0", designStress.totalReturnPct() > 0.0);
        gates.put("design_normal_completed_trades_at_least_10",
                designNormal.completedTradeCount() >= 10);
        gates.put("design_normal_maximum_drawdown_at_most_60pct_of_buy_hold",
                designNormal.maximumDrawdownPct()
                        <= designBuyHold.maximumDrawdownPct() * 0.60 + EPSILON);

        Metrics normal = validation.candidate().get("NORMAL");
        Metrics stress = validation.candidate().get("STRESS");
        BenchmarkMetrics buyHold = validation.buyHold().get("NORMAL");
        gates.put("validation_normal_total_return_pct_gt_0", normal.totalReturnPct() > 0.0);
        gates.put("validation_stress_total_return_pct_gt_0", stress.totalReturnPct() > 0.0);
        gates.put("validation_normal_realized_return_pct_gt_0", normal.realizedReturnPct() > 0.0);
        gates.put("validation_normal_completed_trades_at_least_5",
                normal.completedTradeCount() >= 5);
        gates.put("validation_normal_maximum_drawdown_at_most_60pct_of_buy_hold",
                normal.maximumDrawdownPct()
                        <= buyHold.maximumDrawdownPct() * 0.60 + EPSILON);
        gates.put("validation_normal_upside_capture_at_least_15pct_when_buy_hold_positive",
                buyHold.totalReturnPct() <= 0.0
                        || normal.totalReturnPct() / buyHold.totalReturnPct() >= 0.15);
        gates.put("validation_normal_calmar_at_least_50pct_of_buy_hold_calmar",
                calmarGate(normal.totalReturnPct(), normal.maximumDrawdownPct(),
                        buyHold.totalReturnPct(), buyHold.maximumDrawdownPct()));
        gates.put("validation_stress_maximum_drawdown_no_more_than_normal_plus_5pp",
                stress.maximumDrawdownPct() <= normal.maximumDrawdownPct() + 5.0 + EPSILON);

        int normalPositiveYears = 0;
        int stressPositiveYears = 0;
        int drawdownNonWorseYears = 0;
        List<Double> annualNormalReturns = new ArrayList<>();
        for (WindowResult year : annual.values()) {
            Metrics yearNormal = year.candidate().get("NORMAL");
            Metrics yearStress = year.candidate().get("STRESS");
            BenchmarkMetrics yearBuyHold = year.buyHold().get("NORMAL");
            annualNormalReturns.add(yearNormal.totalReturnPct());
            if (yearNormal.totalReturnPct() > 0.0) normalPositiveYears++;
            if (yearStress.totalReturnPct() > 0.0) stressPositiveYears++;
            if (yearNormal.maximumDrawdownPct()
                    <= yearBuyHold.maximumDrawdownPct() + EPSILON) {
                drawdownNonWorseYears++;
            }
        }
        gates.put("normal_positive_annual_total_return_at_least_3_of_5",
                normalPositiveYears >= 3);
        gates.put("stress_positive_annual_total_return_at_least_3_of_5",
                stressPositiveYears >= 3);
        gates.put("normal_annual_drawdown_non_worse_than_buy_hold_at_least_4_of_5",
                drawdownNonWorseYears >= 4);
        gates.put("top_year_positive_total_return_contribution_at_most_60pct",
                contribution(annualNormalReturns) <= 60.0 + EPSILON);
        gates.put("top_trade_positive_realized_pnl_contribution_at_most_40pct",
                normal.topPositiveTradeContributionPct() <= 40.0 + EPSILON);
        gates.put("validation_normal_median_completed_trade_hold_at_most_2160_hours",
                normal.medianHoldHours() != null && normal.medianHoldHours() <= 2160.0);
        gates.put("validation_normal_p90_completed_trade_hold_at_most_4320_hours",
                normal.p90HoldHours() != null && normal.p90HoldHours() <= 4320.0);
        gates.put("validation_terminal_unrealized_positive_contribution_at_most_40pct_of_positive_total_return",
                normal.totalReturnPct() > 0.0
                        && Math.max(0.0, normal.unrealizedReturnPct())
                        / normal.totalReturnPct() <= 0.40 + EPSILON);

        List<String> failed = gates.entrySet().stream()
                .filter(entry -> !entry.getValue())
                .map(Map.Entry::getKey)
                .toList();
        return new GateDecision(gates, failed, failed.isEmpty());
    }

    private boolean calmarGate(
            double candidateReturn,
            double candidateDrawdown,
            double benchmarkReturn,
            double benchmarkDrawdown) {
        if (candidateDrawdown <= EPSILON) {
            return candidateReturn > 0.0;
        }
        if (benchmarkDrawdown <= EPSILON) {
            return benchmarkReturn <= 0.0 && candidateReturn > 0.0;
        }
        double candidateCalmar = candidateReturn / candidateDrawdown;
        double benchmarkCalmar = benchmarkReturn / benchmarkDrawdown;
        return benchmarkCalmar <= 0.0
                ? candidateCalmar > 0.0
                : candidateCalmar + EPSILON >= benchmarkCalmar * 0.50;
    }

    private static double contribution(List<Double> values) {
        List<Double> positive = values.stream().filter(value -> value > 0.0).toList();
        double sum = positive.stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= EPSILON) return 100.0;
        return positive.stream().mapToDouble(Double::doubleValue).max().orElse(0.0)
                / sum * 100.0;
    }

    private static Double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) return null;
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        if (sorted.size() == 1) return sorted.getFirst();
        double position = (sorted.size() - 1) * percentile;
        int lower = (int) Math.floor(position);
        int upper = Math.min(lower + 1, sorted.size() - 1);
        return sorted.get(lower)
                + (sorted.get(upper) - sorted.get(lower)) * (position - lower);
    }

    private static double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        return Double.parseDouble(String.valueOf(value));
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value)
                .setScale(8, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String nullableDecimal(Double value) {
        return value == null ? null : decimal(value);
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private record Arguments(Path input, Path manifest, Path output) {
        static Arguments parse(String[] args) {
            Path input = null;
            Path manifest = null;
            Path output = null;
            for (int index = 0; index < args.length; index++) {
                if ("--input".equals(args[index]) && index + 1 < args.length) {
                    input = Path.of(args[++index]);
                } else if ("--manifest".equals(args[index]) && index + 1 < args.length) {
                    manifest = Path.of(args[++index]);
                } else if ("--output".equals(args[index]) && index + 1 < args.length) {
                    output = Path.of(args[++index]);
                } else {
                    throw new IllegalArgumentException(
                            "usage: --input <sealed.tsv> --manifest <frozen.json> --output <create-new.json>");
                }
            }
            if (input == null || manifest == null || output == null) {
                throw new IllegalArgumentException(
                        "usage: --input <sealed.tsv> --manifest <frozen.json> --output <create-new.json>");
            }
            return new Arguments(input, manifest, output);
        }
    }

    private record InputData(List<MdKline> bars, String sha256) {
    }

    private record Metrics(
            double realizedReturnPct,
            double unrealizedReturnPct,
            double totalReturnPct,
            double maximumDrawdownPct,
            long maximumUnderwaterDurationHours,
            double feesEquityUnits,
            double turnoverEquityUnits,
            double averageExposurePct,
            int completedTradeCount,
            int winningTradeCount,
            Double medianHoldHours,
            Double p90HoldHours,
            boolean terminalPosition,
            Double terminalHoldingAgeHours,
            double topPositiveTradeContributionPct) {

        Map<String, Object> output(BenchmarkMetrics benchmark) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("realized_return_pct", decimal(realizedReturnPct));
            value.put("unrealized_return_pct", decimal(unrealizedReturnPct));
            value.put("total_return_pct", decimal(totalReturnPct));
            value.put("maximum_drawdown_pct", decimal(maximumDrawdownPct));
            value.put("maximum_underwater_duration_hours", maximumUnderwaterDurationHours);
            value.put("fees_equity_units", decimal(feesEquityUnits));
            value.put("turnover_equity_units", decimal(turnoverEquityUnits));
            value.put("average_exposure_pct", decimal(averageExposurePct));
            value.put("completed_trade_count", completedTradeCount);
            value.put("winning_trade_count", winningTradeCount);
            value.put("median_hold_hours", nullableDecimal(medianHoldHours));
            value.put("p90_hold_hours", nullableDecimal(p90HoldHours));
            value.put("terminal_position", terminalPosition);
            value.put("terminal_holding_age_hours", nullableDecimal(terminalHoldingAgeHours));
            value.put("top_positive_trade_contribution_pct",
                    decimal(topPositiveTradeContributionPct));
            value.put("calmar_ratio", nullableDecimal(maximumDrawdownPct <= EPSILON
                    ? null : totalReturnPct / maximumDrawdownPct));
            value.put("upside_capture_ratio", nullableDecimal(
                    benchmark.totalReturnPct() <= 0.0
                            ? null : totalReturnPct / benchmark.totalReturnPct()));
            return value;
        }
    }

    private record BenchmarkMetrics(
            double totalReturnPct,
            double maximumDrawdownPct,
            long maximumUnderwaterDurationHours,
            double feesEquityUnits,
            double turnoverEquityUnits,
            double averageExposurePct) {

        Map<String, Object> output() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("total_return_pct", decimal(totalReturnPct));
            value.put("maximum_drawdown_pct", decimal(maximumDrawdownPct));
            value.put("maximum_underwater_duration_hours", maximumUnderwaterDurationHours);
            value.put("fees_equity_units", decimal(feesEquityUnits));
            value.put("turnover_equity_units", decimal(turnoverEquityUnits));
            value.put("average_exposure_pct", decimal(averageExposurePct));
            value.put("calmar_ratio", nullableDecimal(maximumDrawdownPct <= EPSILON
                    ? null : totalReturnPct / maximumDrawdownPct));
            return value;
        }
    }

    private record WindowResult(
            LocalDateTime start,
            LocalDateTime endExclusive,
            Map<String, Metrics> candidate,
            Map<String, BenchmarkMetrics> buyHold) {

        Map<String, Object> output() {
            Map<String, Object> scenarios = new LinkedHashMap<>();
            for (String name : List.of("NORMAL", "STRESS")) {
                Map<String, Object> comparison = new LinkedHashMap<>();
                comparison.put("candidate",
                        candidate.get(name).output(buyHold.get(name)));
                comparison.put("buy_and_hold", buyHold.get(name).output());
                scenarios.put(name, comparison);
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("start", start.toString());
            value.put("end_exclusive", endExclusive.toString());
            value.put("scenarios", scenarios);
            return value;
        }
    }

    private record GateDecision(
            Map<String, Boolean> gates,
            List<String> failed,
            boolean passed) {
    }

    private static final class PathAccumulator {
        private double peak = 1.0;
        private double maxDrawdownPct;
        private long currentUnderwaterHours;
        private long maxUnderwaterHours;
        private double exposureSum;
        private long observations;

        private void observe(double equity, double exposure) {
            if (!Double.isFinite(equity) || equity <= 0.0
                    || !Double.isFinite(exposure) || exposure < -EPSILON) {
                throw new IllegalStateException("NONFINITE_OR_NONPOSITIVE_ECONOMIC_PATH");
            }
            if (equity > peak + EPSILON) {
                peak = equity;
                currentUnderwaterHours = 0;
            } else if (equity < peak - EPSILON) {
                currentUnderwaterHours++;
                maxUnderwaterHours = Math.max(maxUnderwaterHours, currentUnderwaterHours);
                maxDrawdownPct = Math.max(
                        maxDrawdownPct, (peak - equity) / peak * 100.0);
            } else {
                currentUnderwaterHours = 0;
            }
            exposureSum += exposure;
            observations++;
        }

        private double averageExposurePct() {
            return observations == 0 ? 0.0 : exposureSum / observations * 100.0;
        }
    }
}
