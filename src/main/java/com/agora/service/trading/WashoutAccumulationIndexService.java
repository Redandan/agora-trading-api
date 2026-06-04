package com.agora.service.trading;

import com.agora.model.MarketIndicatorHistory;
import com.agora.model.MdKline;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.repository.trading.MdKlineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class WashoutAccumulationIndexService {

    private static final String SYMBOL = "BTCUSDT";
    private static final String INTERVAL = "1h";
    private static final String SOURCE = "okx";
    private static final int MIN_BARS = 100;
    private static final double EPSILON = 1e-9;
    private static final List<String> INDICATORS = List.of(
            "wai_score",
            "wai_stage",
            "wai_volume_dryup_score",
            "wai_price_stability_score",
            "wai_stop_hunt_score",
            "wai_probe_pump_score",
            "wai_structure_confirm_score",
            "wai_breakout_ready",
            "wai_invalidated",
            "wai_volume_ratio_20",
            "wai_volume_ratio_50",
            "wai_current_volume_dryup_score",
            "wai_dryup_memory_score",
            "wai_lower_wick_ratio",
            "wai_close_position");
    private static final List<String> CONTEXT_INDICATORS = List.of(
            "funding_rate",
            "oi_change_pct_1h",
            "long_short_ratio",
            "whale_buy_ratio",
            "orderbook_imbalance",
            "market_phase",
            "btc_atr_units_1h",
            "btc_change_pct_1h",
            "btc_change_pct_4h",
            "spot_taker_buy_usd_15m");

    private final MdKlineRepository klineRepo;
    private final MarketIndicatorHistoryRepository indicatorRepo;

    @Transactional
    public CalculationResult calculateAndPersistLatest(String symbol, String intervalCode) {
        String sym = normalizeSymbol(symbol);
        String interval = normalizeInterval(intervalCode);
        if (!SYMBOL.equals(sym) || !INTERVAL.equals(interval)) {
            return CalculationResult.skipped(sym, interval, "WAI_PHASE1_SCOPE_ONLY_BTCUSDT_1H");
        }
        List<MdKline> bars = klineRepo.findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(
                sym, interval, SOURCE, PageRequest.of(0, MIN_BARS));
        if (bars.size() < MIN_BARS) {
            log.info("[WAI] skip {}@{}: insufficient {} source bars {} < {}",
                    sym, interval, SOURCE, bars.size(), MIN_BARS);
            return CalculationResult.skipped(sym, interval, "INSUFFICIENT_KLINES_" + bars.size());
        }
        Collections.reverse(bars);
        MdKline latest = bars.get(bars.size() - 1);
        Map<String, Double> context = latestContext(sym, latest.getCloseTime());
        WaiSnapshot snapshot = compute(bars, bars.size() - 1, context);
        int written = persist(sym, latest.getCloseTime(), snapshot);
        log.info("[WAI] {}@{} capturedAt={} score={} stage={} invalidated={} breakoutReady={} written={}",
                sym, interval, latest.getCloseTime(), snapshot.waiScore(), snapshot.waiStage(),
                snapshot.waiInvalidated(), snapshot.waiBreakoutReady(), written);
        return new CalculationResult(sym, interval, false, null, latest.getCloseTime(), snapshot, written);
    }

    @Transactional(readOnly = true)
    public String scanWaiAccuracy(String symbol, Integer days, String intervalCode) {
        String sym = normalizeSymbol(symbol);
        String interval = normalizeInterval(intervalCode);
        int d = days == null ? 180 : Math.max(1, Math.min(days, 180));
        if (!SYMBOL.equals(sym) || !INTERVAL.equals(interval)) {
            return "WAI Phase 1 supports BTCUSDT 1h only. requested=" + sym + "@" + interval;
        }
        LocalDateTime end = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime start = end.minusDays(d).minusDays(7);
        List<MdKline> bars = klineRepo.findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                sym, interval, SOURCE, start, end);
        if (bars.size() < MIN_BARS + 48) {
            return "=== WAI Accuracy Scan ===\n"
                    + "boundary=READ_ONLY; no trading/OCO/strategy/grid/fund/Earn behavior changed.\n"
                    + "status=INSUFFICIENT_KLINES\n"
                    + "symbol=" + sym + "\nintervalCode=" + interval + "\ndays=" + d + "\n"
                    + "bars=" + bars.size() + "\n";
        }
        Map<String, NavigableMap<LocalDateTime, Double>> contextSeries = loadContextSeries(sym, start);
        List<SignalOutcome> outcomes = new ArrayList<>();
        LocalDateTime scoreStart = end.minusDays(d);
        for (int i = MIN_BARS - 1; i < bars.size() - 48; i++) {
            MdKline bar = bars.get(i);
            if (bar.getOpenTime().isBefore(scoreStart)) {
                continue;
            }
            Map<String, Double> context = contextAt(contextSeries, bar.getCloseTime());
            WaiSnapshot snapshot = compute(bars, i, context);
            outcomes.add(outcome(bars, i, snapshot));
        }
        return renderScan(sym, interval, d, outcomes);
    }

    WaiSnapshot compute(List<MdKline> bars, int index, Map<String, Double> context) {
        if (bars == null || bars.size() < MIN_BARS || index < MIN_BARS - 1 || index >= bars.size()) {
            throw new IllegalArgumentException("WAI requires at least 100 bars and a valid index");
        }
        MdKline cur = bars.get(index);
        MdKline prev3 = bars.get(Math.max(0, index - 3));
        double open = d(cur.getOpenPrice());
        double high = d(cur.getHighPrice());
        double low = d(cur.getLowPrice());
        double close = d(cur.getClosePrice());
        double volume = d(cur.getVolume());
        double smaVol20 = smaVolume(bars, index, 20);
        double smaVol50 = smaVolume(bars, index, 50);
        double volumeRatio20 = ratio(volume, smaVol20);
        double volumeRatio50 = ratio(volume, smaVol50);
        Double proxyTurnoverRatio = null;

        double prevLow24h = rollingLow(bars, index - 1, 24);
        double rangeLow24h = rollingLow(bars, index, 24);
        double rangeHigh24h = rollingHigh(bars, index, 24);
        double supportDistancePct = rangeLow24h > 0 ? (close - rangeLow24h) / rangeLow24h : 0;
        double drawdownFrom24hHighPct = rangeHigh24h > 0 ? (rangeHigh24h - close) / rangeHigh24h : 0;
        double range = Math.max(high - low, EPSILON);
        double lowerWickRatio = (Math.min(open, close) - low) / range;
        double closePosition = (close - low) / range;
        double priceChange1h = open > 0 ? (close - open) / open : 0;
        double prev3Close = d(prev3.getClosePrice());
        double priceChange3h = prev3Close > 0 ? (close - prev3Close) / prev3Close : 0;
        double smaClose20 = smaClose(bars, index, 20);

        int currentVolumeDryup = volumeDryupScore(volumeRatio20, volumeRatio50);
        int dryupMemoryScore = dryupMemoryScore(bars, index, 12);
        int volumeDryup = Math.max(currentVolumeDryup, dryupMemoryScore);
        int priceStability = priceStabilityScore(close, rangeLow24h, drawdownFrom24hHighPct,
                value(context, "btc_change_pct_4h"));
        int stopHunt = stopHuntScore(low, close, prevLow24h, lowerWickRatio, closePosition);
        int probePump = probePumpScore(priceChange1h, priceChange3h, volumeRatio20, close, smaClose20);
        int structure = structureScore(context);
        int score = volumeDryup + priceStability + stopHunt + probePump + structure;
        boolean invalidated = close < prevLow24h * 0.985
                || value(context, "btc_change_pct_4h") <= -3.5
                || (volumeRatio20 >= 2.5 && close < open);
        int stage = stage(score);
        if (invalidated) {
            stage = 0;
        }
        boolean breakoutReady = score >= 82
                && !invalidated
                && close >= smaClose20
                && volumeRatio20 >= 0.8
                && volumeRatio20 <= 1.8;

        return new WaiSnapshot(score, stage, volumeDryup, priceStability, stopHunt, probePump, structure,
                breakoutReady, invalidated, volumeRatio20, volumeRatio50, proxyTurnoverRatio, lowerWickRatio,
                closePosition, supportDistancePct, drawdownFrom24hHighPct, priceChange1h, priceChange3h,
                smaClose20, currentVolumeDryup, dryupMemoryScore, cur.getCloseTime());
    }

    private int persist(String symbol, LocalDateTime capturedAt, WaiSnapshot s) {
        int written = 0;
        written += insert(symbol, "wai_score", s.waiScore(), capturedAt);
        written += insert(symbol, "wai_stage", s.waiStage(), capturedAt);
        written += insert(symbol, "wai_volume_dryup_score", s.waiVolumeDryupScore(), capturedAt);
        written += insert(symbol, "wai_price_stability_score", s.waiPriceStabilityScore(), capturedAt);
        written += insert(symbol, "wai_stop_hunt_score", s.waiStopHuntScore(), capturedAt);
        written += insert(symbol, "wai_probe_pump_score", s.waiProbePumpScore(), capturedAt);
        written += insert(symbol, "wai_structure_confirm_score", s.waiStructureConfirmScore(), capturedAt);
        written += insert(symbol, "wai_breakout_ready", s.waiBreakoutReady() ? 1 : 0, capturedAt);
        written += insert(symbol, "wai_invalidated", s.waiInvalidated() ? 1 : 0, capturedAt);
        written += insert(symbol, "wai_volume_ratio_20", s.volumeRatio20(), capturedAt);
        written += insert(symbol, "wai_volume_ratio_50", s.volumeRatio50(), capturedAt);
        written += insert(symbol, "wai_current_volume_dryup_score", s.currentVolumeDryupScore(), capturedAt);
        written += insert(symbol, "wai_dryup_memory_score", s.dryupMemoryScore(), capturedAt);
        if (s.proxyTurnoverRatio() != null) {
            written += insert(symbol, "wai_proxy_turnover_ratio", s.proxyTurnoverRatio(), capturedAt);
        }
        written += insert(symbol, "wai_lower_wick_ratio", s.lowerWickRatio(), capturedAt);
        written += insert(symbol, "wai_close_position", s.closePosition(), capturedAt);
        return written;
    }

    private int insert(String symbol, String indicator, double value, LocalDateTime capturedAt) {
        return indicatorRepo.insertIgnore(symbol, indicator, capturedAt,
                BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP));
    }

    private int volumeDryupScore(double ratio20, double ratio50) {
        if (ratio20 <= 0.40 && ratio50 <= 0.55) return 25;
        if (ratio20 <= 0.55) return 20;
        if (ratio20 <= 0.70) return 12;
        return 0;
    }

    private int dryupMemoryScore(List<MdKline> bars, int index, int lookback) {
        int best = 0;
        int start = Math.max(49, index - lookback);
        for (int i = start; i < index; i++) {
            double volume = d(bars.get(i).getVolume());
            best = Math.max(best, volumeDryupScore(
                    ratio(volume, smaVolume(bars, i, 20)),
                    ratio(volume, smaVolume(bars, i, 50))));
        }
        return best;
    }

    private int priceStabilityScore(double close, double rangeLow24h, double drawdownFrom24hHighPct,
                                    double btcChangePct4h) {
        int score = 0;
        if (close > rangeLow24h * 1.003) score += 8;
        if (drawdownFrom24hHighPct <= 0.035) score += 6;
        if (btcChangePct4h > -1.5) score += 6;
        return score;
    }

    private int stopHuntScore(double low, double close, double prevLow24h,
                              double lowerWickRatio, double closePosition) {
        int score = 0;
        boolean falseBreak = low < prevLow24h * 0.997 && close > prevLow24h;
        if (falseBreak) score += 12;
        if (lowerWickRatio >= 0.45) score += 5;
        if (closePosition >= 0.60) score += 3;
        return score;
    }

    private int probePumpScore(double priceChange1h, double priceChange3h, double volumeRatio20,
                               double close, double smaClose20) {
        int score = 0;
        boolean probePump = between(priceChange1h, 0.003, 0.018)
                || between(priceChange3h, 0.008, 0.030);
        boolean healthyVolume = volumeRatio20 >= 0.8 && volumeRatio20 <= 1.8;
        if (probePump) score += 10;
        if (healthyVolume) score += 5;
        if (close > smaClose20) score += 5;
        return score;
    }

    private int structureScore(Map<String, Double> context) {
        int score = 0;
        if (value(context, "oi_change_pct_1h") >= -1.0) score += 3;
        if (value(context, "funding_rate") <= 0.0001) score += 3;
        if (value(context, "whale_buy_ratio") >= 0.52) score += 3;
        if (value(context, "orderbook_imbalance") > 0) score += 3;
        Double marketPhase = context.get("market_phase");
        double atrUnits = value(context, "btc_atr_units_1h");
        boolean notBearish = marketPhase != null && marketPhase >= 0;
        if (notBearish || atrUnits < 1.5) score += 3;
        return score;
    }

    private int stage(int score) {
        if (score < 40) return 0;
        if (score < 55) return 1;
        if (score < 70) return 2;
        if (score < 82) return 3;
        return 4;
    }

    private String renderScan(String symbol, String interval, int days, List<SignalOutcome> outcomes) {
        int[] thresholds = {60, 65, 70, 75, 80, 82, 85, 90};
        StringBuilder sb = new StringBuilder("=== WAI Accuracy Scan ===\n")
                .append("boundary=READ_ONLY; no trading/OCO/strategy/grid/fund/Earn behavior changed.\n")
                .append("symbol=").append(symbol).append("\n")
                .append("intervalCode=").append(interval).append("\n")
                .append("days=").append(days).append("\n")
                .append("sampledBars=").append(outcomes.size()).append("\n")
                .append("successLabel=futureReturn24h>=1.2% AND futureDrawdown24h>-1.5%\n\n");
        Map<Integer, Long> stageCounts = new LinkedHashMap<>();
        for (int i = 0; i <= 4; i++) {
            int stage = i;
            stageCounts.put(stage, outcomes.stream().filter(o -> o.snapshot().waiStage() == stage).count());
        }
        sb.append("stageBreakdown=").append(stageCounts).append("\n\n");
        sb.append(String.format("%-10s | %7s | %7s | %10s | %10s | %10s | %10s | %s%n",
                "threshold", "samples", "winRate", "medMAE24", "avgMax24", "avgDD24", "uniqueDays", "acceptance"));
        sb.append("-".repeat(96)).append("\n");
        for (int threshold : thresholds) {
            List<SignalOutcome> rows = outcomes.stream()
                    .filter(o -> o.snapshot().waiScore() >= threshold)
                    .toList();
            long wins = rows.stream().filter(SignalOutcome::success).count();
            double winRate = rows.isEmpty() ? 0 : wins * 100.0 / rows.size();
            double medMae = median(rows.stream().map(SignalOutcome::futureDrawdown24h).sorted().toList());
            double avgMax24 = rows.stream().mapToDouble(SignalOutcome::futureMaxReturn24h).average().orElse(0);
            double avgDd24 = rows.stream().mapToDouble(SignalOutcome::futureDrawdown24h).average().orElse(0);
            long uniqueDays = rows.stream().map(o -> o.time().toLocalDate()).distinct().count();
            String acceptance = threshold == 82 ? acceptance(rows, winRate, medMae, uniqueDays) : "-";
            sb.append(String.format(Locale.ROOT, "%-10d | %7d | %6.1f%% | %9.2f%% | %9.2f%% | %9.2f%% | %10d | %s%n",
                    threshold, rows.size(), winRate, medMae, avgMax24, avgDd24, uniqueDays, acceptance));
        }
        sb.append("\nfalsePositiveExamples(score>=82):\n");
        outcomes.stream()
                .filter(o -> o.snapshot().waiScore() >= 82)
                .filter(o -> !o.success())
                .limit(8)
                .forEach(o -> sb.append("  ").append(o.time())
                        .append(" score=").append(o.snapshot().waiScore())
                        .append(" stage=").append(o.snapshot().waiStage())
                        .append(" max24=").append(fmtPct(o.futureMaxReturn24h()))
                        .append(" dd24=").append(fmtPct(o.futureDrawdown24h()))
                        .append(" max48=").append(fmtPct(o.futureMaxReturn48h()))
                        .append(" dd48=").append(fmtPct(o.futureDrawdown48h()))
                        .append("\n"));
        return sb.toString();
    }

    private String acceptance(List<SignalOutcome> rows, double winRate, double medianMae, long uniqueDays) {
        if (rows.size() >= 5 && winRate >= 55.0 && medianMae > -1.5 && uniqueDays > 1) {
            return "PASS_TARGET";
        }
        return "NEEDS_MORE_OR_WEAKER_EDGE";
    }

    private SignalOutcome outcome(List<MdKline> bars, int index, WaiSnapshot snapshot) {
        MdKline entry = bars.get(index);
        double entryClose = d(entry.getClosePrice());
        double maxHigh24 = entryClose;
        double minLow24 = entryClose;
        double maxHigh48 = entryClose;
        double minLow48 = entryClose;
        for (int j = index + 1; j <= Math.min(index + 48, bars.size() - 1); j++) {
            double high = d(bars.get(j).getHighPrice());
            double low = d(bars.get(j).getLowPrice());
            if (j <= index + 24) {
                maxHigh24 = Math.max(maxHigh24, high);
                minLow24 = Math.min(minLow24, low);
            }
            maxHigh48 = Math.max(maxHigh48, high);
            minLow48 = Math.min(minLow48, low);
        }
        double maxReturn24 = pct(maxHigh24, entryClose);
        double drawdown24 = pct(minLow24, entryClose);
        double maxReturn48 = pct(maxHigh48, entryClose);
        double drawdown48 = pct(minLow48, entryClose);
        boolean success = maxReturn24 >= 1.2 && drawdown24 > -1.5;
        return new SignalOutcome(entry.getCloseTime(), snapshot, maxReturn24, drawdown24, maxReturn48, drawdown48, success);
    }

    private double pct(double value, double base) {
        return base > 0 ? (value - base) / base * 100.0 : 0.0;
    }

    private double median(List<Double> sorted) {
        if (sorted == null || sorted.isEmpty()) {
            return 0;
        }
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    private Map<String, Double> latestContext(String symbol, LocalDateTime at) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (String indicator : CONTEXT_INDICATORS) {
            indicatorRepo.findTopCleanBySymbolAndIndicatorAndCapturedAtLessThanEqual(symbol, indicator, at)
                    .map(MarketIndicatorHistory::getValue)
                    .map(BigDecimal::doubleValue)
                    .ifPresent(v -> map.put(indicator, v));
        }
        return map;
    }

    private Map<String, NavigableMap<LocalDateTime, Double>> loadContextSeries(String symbol, LocalDateTime since) {
        Map<String, NavigableMap<LocalDateTime, Double>> series = new LinkedHashMap<>();
        for (String indicator : CONTEXT_INDICATORS) {
            NavigableMap<LocalDateTime, Double> values = new TreeMap<>();
            List<MarketIndicatorHistory> rows =
                    indicatorRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(symbol, indicator, since);
            for (MarketIndicatorHistory row : rows) {
                if (row.getValue() != null) {
                    values.put(row.getCapturedAt(), row.getValue().doubleValue());
                }
            }
            series.put(indicator, values);
        }
        return series;
    }

    private Map<String, Double> contextAt(Map<String, NavigableMap<LocalDateTime, Double>> series, LocalDateTime at) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (Map.Entry<String, NavigableMap<LocalDateTime, Double>> entry : series.entrySet()) {
            Map.Entry<LocalDateTime, Double> floor = entry.getValue().floorEntry(at);
            if (floor != null) {
                map.put(entry.getKey(), floor.getValue());
            }
        }
        return map;
    }

    private double smaVolume(List<MdKline> bars, int index, int length) {
        return bars.subList(index - length + 1, index + 1).stream()
                .map(MdKline::getVolume)
                .filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0);
    }

    private double smaClose(List<MdKline> bars, int index, int length) {
        return bars.subList(index - length + 1, index + 1).stream()
                .map(MdKline::getClosePrice)
                .filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0);
    }

    private double rollingLow(List<MdKline> bars, int index, int length) {
        int end = Math.max(0, index);
        int start = Math.max(0, end - length + 1);
        return bars.subList(start, end + 1).stream()
                .map(MdKline::getLowPrice)
                .filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .min()
                .orElse(0);
    }

    private double rollingHigh(List<MdKline> bars, int index, int length) {
        int end = Math.max(0, index);
        int start = Math.max(0, end - length + 1);
        return bars.subList(start, end + 1).stream()
                .map(MdKline::getHighPrice)
                .filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .max()
                .orElse(0);
    }

    private boolean between(double value, double min, double max) {
        return value >= min && value <= max;
    }

    private double ratio(double numerator, double denominator) {
        return denominator > EPSILON ? numerator / denominator : 0;
    }

    private double value(Map<String, Double> context, String key) {
        return context == null || context.get(key) == null ? 0 : context.get(key);
    }

    private double d(BigDecimal value) {
        return value == null ? 0 : value.doubleValue();
    }

    private String fmtPct(double value) {
        return String.format(Locale.ROOT, "%+.2f%%", value);
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeInterval(String intervalCode) {
        return intervalCode == null || intervalCode.isBlank() ? INTERVAL : intervalCode.trim().toLowerCase(Locale.ROOT);
    }

    public List<String> indicatorNames() {
        return INDICATORS;
    }

    public record CalculationResult(String symbol,
                                    String intervalCode,
                                    boolean skipped,
                                    String skipReason,
                                    LocalDateTime capturedAt,
                                    WaiSnapshot snapshot,
                                    int rowsWritten) {
        public static CalculationResult skipped(String symbol, String intervalCode, String reason) {
            return new CalculationResult(symbol, intervalCode, true, reason, null, null, 0);
        }
    }

    public record WaiSnapshot(int waiScore,
                              int waiStage,
                              int waiVolumeDryupScore,
                              int waiPriceStabilityScore,
                              int waiStopHuntScore,
                              int waiProbePumpScore,
                              int waiStructureConfirmScore,
                              boolean waiBreakoutReady,
                              boolean waiInvalidated,
                              double volumeRatio20,
                              double volumeRatio50,
                              Double proxyTurnoverRatio,
                              double lowerWickRatio,
                              double closePosition,
                              double supportDistancePct,
                              double drawdownFrom24hHighPct,
                              double priceChange1h,
                              double priceChange3h,
                              double smaClose20,
                              int currentVolumeDryupScore,
                              int dryupMemoryScore,
                              LocalDateTime capturedAt) {
    }

    private record SignalOutcome(LocalDateTime time,
                                 WaiSnapshot snapshot,
                                 double futureMaxReturn24h,
                                 double futureDrawdown24h,
                                 double futureMaxReturn48h,
                                 double futureDrawdown48h,
                                 boolean success) {
    }
}
