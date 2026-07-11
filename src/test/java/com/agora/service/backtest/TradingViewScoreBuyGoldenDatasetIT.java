package com.agora.service.backtest;

import com.agora.model.MdKline;
import com.agora.service.ml.MlTrainingOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Explicitly invoked dataset test for the signed-in TradingView strategy 485 export.
 * The normal test suite compiles this class but does not run the external dataset flow.
 */
class TradingViewScoreBuyGoldenDatasetIT {

    private static final double NN_TOLERANCE = 1e-6;

    @AfterEach
    void clearContext() {
        LiveSignalContext.clear();
    }

    @Test
    void productionModelMatchesTradingViewNnAndBuyPoints() throws Exception {
        Path replayPath = requiredPath("tradingview.replay.csv");
        Path nnPath = requiredPath("tradingview.nn.csv");
        Path goldenPath = requiredPath("tradingview.golden.csv");
        String resultPathValue = System.getProperty("tradingview.result.json", "").trim();

        List<MdKline> klines = readReplay(replayPath);
        TreeMap<LocalDateTime, Double> expectedNn = readNnSeries(nnPath);
        assertThat(klines).isNotEmpty();
        assertThat(expectedNn).hasSize(365);

        ScoreBuyV2Strategy strategy = new ScoreBuyV2Strategy(
                mock(JdbcTemplate.class),
                mock(MlTrainingOrchestrator.class),
                new ObjectMapper(),
                new ScoreBuyStrategy());
        Map<String, Object> config = new HashMap<>(strategy.defaultExecutionConfig());
        config.put("runIntervalCode", "1d");
        config.put("tradingViewParityMode", true);
        config.put(TradingViewScoreBuyModel.REPLAY_START_CONFIG,
                TradingViewScoreBuyModel.BTCUSDT_1D_REPLAY_START_UTC.toString());
        config.put(TradingViewScoreBuyModel.REQUIRE_FULL_HISTORY_CONFIG, true);
        config.put("tradingViewAllowIncompleteHistoryShadowIntents", false);

        assertThat(klines.get(0).getOpenTime())
                .isEqualTo(TradingViewScoreBuyModel.BTCUSDT_1D_REPLAY_START_UTC);
        assertThat(TradingViewScoreBuyModel.hasCompleteReplayHistory(klines, config)).isTrue();

        BacktestEngine engine = new BacktestEngine();
        Map<String, double[]> indicators = engine.buildIndicators(klines, config);
        double[] actualNn = indicators.get(TradingViewScoreBuyModel.NN_OUTPUT_KEY);
        Map<LocalDateTime, Integer> indexByTime = new HashMap<>();
        for (int i = 0; i < klines.size(); i++) {
            indexByTime.put(klines.get(i).getOpenTime(), i);
        }

        double maxRawNnError = 0.0;
        int rawNnMismatchCount = 0;
        List<Map<String, Object>> rawNnMismatchSample = new ArrayList<>();
        for (Map.Entry<LocalDateTime, Double> row : expectedNn.entrySet()) {
            Integer index = indexByTime.get(row.getKey());
            double value = index == null ? Double.NaN : actualNn[index];
            double error = Double.isFinite(value)
                    ? Math.abs(row.getValue() - value)
                    : Double.POSITIVE_INFINITY;
            maxRawNnError = Math.max(maxRawNnError, error);
            if (!Double.isFinite(error) || error > NN_TOLERANCE) {
                rawNnMismatchCount++;
                if (rawNnMismatchSample.size() < 10) {
                    Map<String, Object> mismatch = new LinkedHashMap<>();
                    mismatch.put("time", row.getKey().toString());
                    mismatch.put("expected", row.getValue());
                    mismatch.put("actual", Double.isFinite(value) ? value : null);
                    mismatch.put("absoluteError", Double.isFinite(error) ? error : null);
                    rawNnMismatchSample.add(mismatch);
                }
            }
        }

        StateFit stateFit = fitTradingViewState(expectedNn, klines, indexByTime, indicators);

        List<TradingViewGoldenTruthVerifier.Intent> actualIntents = new ArrayList<>();
        List<BtcBaseShadowBacktestSimulator.Bar> profitBars = new ArrayList<>();
        List<BtcBaseShadowBacktestSimulator.BuyIntent> profitIntents = new ArrayList<>();
        try {
            for (int i = 0; i < klines.size(); i++) {
                MdKline current = klines.get(i);
                profitBars.add(new BtcBaseShadowBacktestSimulator.Bar(
                        current.getOpenTime(), current.getClosePrice().doubleValue()));
                if (!expectedNn.containsKey(current.getOpenTime())) {
                    continue;
                }
                MdKline previous = i == 0 ? null : klines.get(i - 1);
                LiveSignalContext.clear();
                StrategySignal signal = strategy.evaluate(
                        new StrategyContext(i, current, previous, klines, indicators), config);
                Object nnValue = LiveSignalContext.getDetails().get("tradingview_nn_output");
                Double nn = nnValue instanceof Number number ? number.doubleValue() : null;
                if (signal == StrategySignal.BUY) {
                    for (LiveSignalContext.OrderIntent intent : LiveSignalContext.getOrderIntents()) {
                        actualIntents.add(new TradingViewGoldenTruthVerifier.Intent(
                                current.getOpenTime(), intent.reason(), intent.label(),
                                BigDecimal.valueOf(intent.quantity()), nn));
                        profitIntents.add(new BtcBaseShadowBacktestSimulator.BuyIntent(
                                current.getOpenTime(), intent.quantity(), intent.reason(), intent.label(),
                                signal.name()));
                    }
                }
            }
        } finally {
            LiveSignalContext.clear();
        }

        TradingViewGoldenTruthVerifier.VerificationResult intentResult =
                new TradingViewGoldenTruthVerifier().verify(goldenPath.toString(), actualIntents);
        String profitOptimizationReport = new TradingViewProfitOptimizationService()
                .compareCurrentCandidate("BTCUSDT", "binance", 0.001, profitBars, profitIntents);
        assertThat(profitOptimizationReport)
                .contains("buyPointPolicy=PRESERVE_ALL_TRADINGVIEW_INTENTS")
                .contains("baselineExitPolicy=HOLD_BTC_BASE_NO_OCO_NO_AUTO_SELL")
                .contains("window=90d intents=9 bars=6 baselineInvested=60.00 baselinePnl=-2.48")
                .contains("window=180d intents=25 bars=16 baselineInvested=160.00 baselinePnl=-18.09")
                .contains("window=270d intents=33 bars=20 baselineInvested=200.00 baselinePnl=-30.99")
                .contains("window=365d intents=42 bars=28 baselineInvested=250.00 baselinePnl=-67.13")
                .contains("baselineExecuted=25")
                .contains("baselineSkipped=3")
                .contains("baselineTakeProfitReductions=0")
                .contains("candidatePolicy=PRIOR_252_CLOSE_HIGH_DRAWDOWN_LT20_10_LT40_20_GTE40_30_USDT_ONE_ORDER_PER_BAR_NO_AUTO_SELL")
                .contains("candidateReference=MAX_PREVIOUS_252_CLOSED_BAR_CLOSES_EXCLUDES_CURRENT_BAR")
                .contains("candidateInvested=170.00 candidatePnl=-5.78 candidateReturn=-3.40%")
                .contains("candidateInvested=250.00 candidatePnl=-35.71 candidateReturn=-14.28%")
                .contains("candidateInvested=250.00 candidatePnl=-56.95 candidateReturn=-22.78%")
                .contains("candidateInvested=250.00 candidatePnl=-80.67 candidateReturn=-32.27%")
                .contains("candidateMaxDrawdown=38.11% candidateExecuted=17 candidateShadowOnly=14 candidateSkipped=11 candidateUpsizedBars=8")
                .contains("walkForwardFold=5")
                .contains("walkForwardSummary=baselinePositiveFolds=2/5 candidatePositiveFolds=2/5")
                .contains("stressCandidatePnl=-81.01 stressCandidateReturn=-32.40%")
                .contains("candidateVerdict=REJECTED")
                .contains("candidatePromotionAllowed=false");
        boolean fullDailyNnParity = rawNnMismatchCount == 0
                && Double.isFinite(maxRawNnError)
                && maxRawNnError <= NN_TOLERANCE;
        boolean exactBuyPointParity = intentResult.exactParity();
        String status = exactBuyPointParity
                ? (fullDailyNnParity
                    ? "PASS_EXACT_PARITY"
                    : "PASS_EXACT_BUY_POINT_PARITY_WITH_RAW_NN_DRIFT")
                : "FAIL_PARITY_MISMATCH";

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("status", status);
        evidence.put("boundary", "LOCAL_READ_ONLY_BINANCE_VISION_AND_TRADINGVIEW_EXPORT");
        evidence.put("strategyId", 485);
        evidence.put("strategyType", ScoreBuyV2Strategy.TYPE);
        evidence.put("symbol", "BTCUSDT");
        evidence.put("interval", "1d");
        evidence.put("replayBars", klines.size());
        evidence.put("replayFirstBarUtc", klines.get(0).getOpenTime().toString());
        evidence.put("replayLastBarUtc", klines.get(klines.size() - 1).getOpenTime().toString());
        evidence.put("completeReplayHistory", true);
        evidence.put("nnRowsCompared", expectedNn.size());
        evidence.put("rawNnMismatchCount", rawNnMismatchCount);
        evidence.put("maxRawNnError", Double.isFinite(maxRawNnError) ? maxRawNnError : null);
        evidence.put("rawNnTolerance", NN_TOLERANCE);
        evidence.put("rawNnMismatchSample", rawNnMismatchSample);
        evidence.put("fullDailyNnParity", fullDailyNnParity);
        evidence.put("fittedWeightAtSecondNnRow", stateFit.weight());
        evidence.put("fittedBiasAtSecondNnRow", stateFit.bias());
        evidence.put("javaWeightAtSecondNnRow", stateFit.javaWeight());
        evidence.put("javaBiasAtSecondNnRow", stateFit.javaBias());
        evidence.put("fittedStateMaxOutputResidual", stateFit.maxOutputResidual());
        evidence.put("fittedStateResidualSample", stateFit.residualSample());
        evidence.put("intentParityStatus", intentResult.status());
        evidence.put("expectedIntents", intentResult.expectedIntentCount());
        evidence.put("actualIntents", intentResult.actualIntentCount());
        evidence.put("missingIntents", intentResult.missingIntentCount());
        evidence.put("extraIntents", intentResult.extraIntentCount());
        evidence.put("intentNnCompared", intentResult.nnCompared());
        evidence.put("maxIntentNnError", Double.isFinite(intentResult.maxNnError())
                ? intentResult.maxNnError() : null);
        evidence.put("intentBlocker", intentResult.blocker());
        evidence.put("profitOptimizationReport", profitOptimizationReport);
        evidence.put("replayCsvSha256", sha256(replayPath));
        evidence.put("nnCsvSha256", sha256(nnPath));
        evidence.put("goldenCsvSha256", sha256(goldenPath));
        evidence.put("productionImportAllowed", false);
        evidence.put("productionEnvChangeAllowed", false);
        evidence.put("livePromotionAllowed", false);

        if (!resultPathValue.isEmpty()) {
            Path resultPath = Path.of(resultPathValue).toAbsolutePath().normalize();
            if (resultPath.getParent() != null) {
                Files.createDirectories(resultPath.getParent());
            }
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(resultPath.toFile(), evidence);
        }

        System.out.printf(
                "TRADINGVIEW_GOLDEN_DATASET_RESULT status=%s bars=%d nnRows=%d "
                        + "rawNnMismatch=%d maxRawNnError=%.12g expectedIntents=%d actualIntents=%d "
                        + "missing=%d extra=%d maxIntentNnError=%.12g%n",
                evidence.get("status"), klines.size(), expectedNn.size(), rawNnMismatchCount,
                maxRawNnError, intentResult.expectedIntentCount(), intentResult.actualIntentCount(),
                intentResult.missingIntentCount(), intentResult.extraIntentCount(),
                intentResult.maxNnError());

        if (Boolean.getBoolean("tradingview.require.full.daily.nn")) {
            assertThat(rawNnMismatchCount).isZero();
            assertThat(maxRawNnError).isLessThanOrEqualTo(NN_TOLERANCE);
        }
        assertThat(intentResult.status()).isEqualTo("PASS_EXACT_PARITY");
        assertThat(intentResult.missingIntentCount()).isZero();
        assertThat(intentResult.extraIntentCount()).isZero();
        assertThat(intentResult.maxNnError()).isLessThanOrEqualTo(NN_TOLERANCE);
    }

    private Path requiredPath(String property) {
        String value = System.getProperty(property, "").trim();
        assertThat(value).as("required system property %s", property).isNotEmpty();
        Path path = Path.of(value).toAbsolutePath().normalize();
        assertThat(path).as(property).isRegularFile();
        return path;
    }

    private List<MdKline> readReplay(Path path) throws Exception {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = csvFormat().parse(reader)) {
            List<MdKline> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                MdKline bar = new MdKline();
                bar.setSymbol("BTCUSDT");
                bar.setIntervalCode("1d");
                bar.setSource("binance");
                bar.setOpenTime(LocalDateTime.parse(record.get("open_time")));
                bar.setCloseTime(LocalDateTime.parse(record.get("close_time")));
                bar.setOpenPrice(new BigDecimal(record.get("open")));
                bar.setHighPrice(new BigDecimal(record.get("high")));
                bar.setLowPrice(new BigDecimal(record.get("low")));
                bar.setClosePrice(new BigDecimal(record.get("close")));
                bar.setVolume(new BigDecimal(record.get("volume")));
                rows.add(bar);
            }
            return rows;
        }
    }

    private TreeMap<LocalDateTime, Double> readNnSeries(Path path) throws Exception {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = csvFormat().parse(reader)) {
            TreeMap<LocalDateTime, Double> rows = new TreeMap<>();
            for (CSVRecord record : parser) {
                LocalDateTime time = parseTime(record.get("time"));
                Double previous = rows.put(time, Double.parseDouble(record.get("NN Output Export")));
                assertThat(previous).as("duplicate NN timestamp %s", time).isNull();
            }
            return rows;
        }
    }

    private StateFit fitTradingViewState(TreeMap<LocalDateTime, Double> expectedNn,
                                         List<MdKline> klines,
                                         Map<LocalDateTime, Integer> indexByTime,
                                         Map<String, double[]> indicators) {
        List<Map.Entry<LocalDateTime, Double>> rows = new ArrayList<>(expectedNn.entrySet());
        double[] weightedSums = indicators.get(TradingViewScoreBuyModel.NN_SUM_KEY);
        double[] javaWeights = indicators.get(TradingViewScoreBuyModel.NN_WEIGHT_KEY);
        double[] javaBiases = indicators.get(TradingViewScoreBuyModel.NN_BIAS_KEY);

        double multiplier = 1.0;
        double biasDelta = 0.0;
        double sumX = 0.0;
        double sumY = 0.0;
        double sumXX = 0.0;
        double sumXY = 0.0;
        int count = 0;
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            int barIndex = indexByTime.get(rows.get(rowIndex).getKey());
            double inputTotal = (weightedSums[barIndex] - javaBiases[barIndex]) / javaWeights[barIndex];
            double x = multiplier * inputTotal;
            double y = logit(rows.get(rowIndex).getValue()) - biasDelta;
            sumX += x;
            sumY += y;
            sumXX += x * x;
            sumXY += x * y;
            count++;

            double realOutput = klines.get(barIndex).getClosePrice().doubleValue()
                    > klines.get(barIndex - 1).getClosePrice().doubleValue() ? 1.0 : 0.0;
            double error = realOutput - rows.get(rowIndex - 1).getValue();
            multiplier *= 1.0 + 0.01 * error;
            biasDelta += 0.01 * error;
        }

        double denominator = count * sumXX - sumX * sumX;
        double weight = (count * sumXY - sumX * sumY) / denominator;
        double bias = (sumY - weight * sumX) / count;
        double maxResidual = 0.0;
        List<Map<String, Object>> residuals = new ArrayList<>();
        multiplier = 1.0;
        biasDelta = 0.0;
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            int barIndex = indexByTime.get(rows.get(rowIndex).getKey());
            double inputTotal = (weightedSums[barIndex] - javaBiases[barIndex]) / javaWeights[barIndex];
            double predicted = sigmoid(weight * multiplier * inputTotal + bias + biasDelta);
            double residual = Math.abs(predicted - rows.get(rowIndex).getValue());
            maxResidual = Math.max(maxResidual, residual);
            Map<String, Object> residualRow = new LinkedHashMap<>();
            residualRow.put("time", rows.get(rowIndex).getKey().toString());
            residualRow.put("expected", rows.get(rowIndex).getValue());
            residualRow.put("fitted", predicted);
            residualRow.put("java", sigmoid(weightedSums[barIndex]));
            residualRow.put("javaPrevious", sigmoid(weightedSums[barIndex - 1]));
            residualRow.put("javaNext", barIndex + 1 < weightedSums.length
                    ? sigmoid(weightedSums[barIndex + 1]) : null);
            residualRow.put("absoluteResidual", residual);
            residuals.add(residualRow);

            double realOutput = klines.get(barIndex).getClosePrice().doubleValue()
                    > klines.get(barIndex - 1).getClosePrice().doubleValue() ? 1.0 : 0.0;
            double error = realOutput - rows.get(rowIndex - 1).getValue();
            multiplier *= 1.0 + 0.01 * error;
            biasDelta += 0.01 * error;
        }

        residuals.sort((left, right) -> Double.compare(
                ((Number) right.get("absoluteResidual")).doubleValue(),
                ((Number) left.get("absoluteResidual")).doubleValue()));

        int secondBarIndex = indexByTime.get(rows.get(1).getKey());
        return new StateFit(weight, bias, javaWeights[secondBarIndex], javaBiases[secondBarIndex],
                maxResidual, List.copyOf(residuals.subList(0, Math.min(10, residuals.size()))));
    }

    private double logit(double value) {
        return Math.log(value / (1.0 - value));
    }

    private double sigmoid(double value) {
        return 1.0 / (1.0 + Math.exp(-value));
    }

    private CSVFormat csvFormat() {
        return CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .build();
    }

    private LocalDateTime parseTime(String value) {
        String text = value.trim();
        if (text.matches("\\d{10,13}")) {
            long epoch = Long.parseLong(text);
            if (text.length() == 10) {
                epoch *= 1000L;
            }
            return Instant.ofEpochMilli(epoch).atZone(ZoneOffset.UTC).toLocalDateTime();
        }
        if (text.endsWith("Z") || text.matches(".*[+-]\\d{2}:?\\d{2}$")) {
            return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        }
        return LocalDateTime.parse(text.replace(' ', 'T'));
    }

    private String sha256(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        return java.util.HexFormat.of().formatHex(digest);
    }

    private record StateFit(double weight,
                            double bias,
                            double javaWeight,
                            double javaBias,
                            double maxOutputResidual,
                            List<Map<String, Object>> residualSample) {
    }

}
