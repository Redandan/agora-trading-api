package com.agora.service.trading;

import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.backtest.IndicatorUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only SCORE_BUY conviction preview.
 *
 * <p>This is intentionally not an execution service. It exposes why #485
 * SCORE_BUY_V2 is or is not forming, including 1h and 15m intraday proxy views,
 * so rollout/governance does not confuse "no candidate" with "blocked by tiny
 * live".</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreBuyConvictionPreviewService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final long DEFAULT_STRATEGY_ID = 485L;
    private static final int LOOKBACK_BARS = 260;
    private static final int RSI_PERIOD = 14;
    private static final int BB_PERIOD = 20;
    private static final int VOL_MA_PERIOD = 20;
    private static final double DEFAULT_BUY_THRESHOLD = 0.70;
    private static final double DEFAULT_RSI_OVERSOLD = 35.0;
    private static final double DEFAULT_VOLUME_MULT = 1.30;

    private final BtStrategyRepository strategyRepository;
    private final MdKlineRepository klineRepository;
    private final CapitalAllocationPolicyPreviewService capitalAllocationPolicyPreviewService;
    private final ObjectMapper objectMapper;

    public String preview(String symbol, Long strategyId) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "previewScoreBuyConviction");
        root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence write behavior changed.");
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("symbol", sym);
        root.put("strategyId", sid);
        root.put("orderSent", false);
        root.put("ocoModified", false);
        root.put("telegramSent", false);
        root.put("writesRuntimeEvidence", false);

        Optional<BtStrategy> strategyOpt = strategyRepository.findById(sid);
        if (strategyOpt.isEmpty()) {
            root.put("status", "STRATEGY_NOT_FOUND");
            return write(root);
        }
        BtStrategy strategy = strategyOpt.get();
        JsonNode config = readConfig(strategy.getConfigJson());
        StrategyParams params = StrategyParams.from(config);

        ObjectNode strategyNode = root.putObject("strategy");
        strategyNode.put("id", strategy.getId());
        strategyNode.put("name", strategy.getName());
        strategyNode.put("type", strategy.getStrategyType());
        strategyNode.put("enabled", Boolean.TRUE.equals(strategy.getEnabled()));
        strategyNode.put("notifyOnly", config.path("notifyOnly").asBoolean(false));
        strategyNode.put("klineSource", nullTo(strategy.getKlineSource(), "default"));
        strategyNode.put("buyThreshold", params.buyThreshold());
        strategyNode.put("rsiOversold", params.rsiOversold());
        strategyNode.put("volumeBreakoutMultiplier", params.volumeBreakoutMultiplier());
        strategyNode.put("minWarmupBars", params.minWarmupBars());

        FrameResult daily = evaluateFrame(sym, "1d", params, false);
        FrameResult oneHour = evaluateFrame(sym, "1h", params, false);
        FrameResult fifteenMinute = evaluateFrame(sym, "15m", params, true);

        root.set("dailyScoreBuyGate", daily.toJson(objectMapper));
        root.set("intradayProxy1h", oneHour.toJson(objectMapper));
        root.set("intradayProxy15m", fifteenMinute.toJson(objectMapper));

        Conviction conviction = classifyConviction(daily, oneHour, fifteenMinute);
        root.put("conviction", conviction.name());
        root.put("scoreBuyTriggerStatus", daily.dipGatePass()
                ? "DAILY_DIP_GATE_PASSED_ML_REQUIRED"
                : "NOT_TRIGGERED_DAILY_DIP_GATE_FAILED");
        root.put("mlGateStatus", daily.dipGatePass()
                ? "NOT_EVALUATED_IN_PREVIEW_V0; SCORE_BUY_V2 still requires promoted ML p_win >= buyThreshold before BUY"
                : "NOT_EVALUATED_DIP_GATE_FAILED");

        ObjectNode sample = root.putObject("sampleDiagnosis");
        sample.put("runtimeEvidenceCandidates", "NO_RECENT_RUNTIME_CANDIDATES_OBSERVED_FOR_STRATEGY_485");
        sample.put("interpretation", "No #485 evidence means the strategy is not surfacing BUY candidates; use this preview to inspect pre-gate state before ML.");
        sample.put("likelyReason", strongestFailureReason(daily, oneHour, fifteenMinute));

        CapitalSnapshot capital = readCapitalSnapshot(sym);
        root.set("capitalSnapshot", capital.toJson(objectMapper));
        SizingPreview sizing = sizingPreview(conviction, capital);
        root.set("scoreBuySizingPreview", sizing.toJson(objectMapper));

        ArrayNode warnings = root.putArray("warnings");
        warnings.add("SCORE_BUY true path should be treated as higher-conviction than $5 tiny-live, but this tool only previews bounded sizing.");
        warnings.add("R3/event risk/open tiny-live position should be risk scalers for SCORE_BUY preview, not proof that SCORE_BUY is invalid.");
        if (capital.earnUsdt() == null) {
            warnings.add("Earn USDT not readable in preview; do not assume deployable capital is only trading-account USDT.");
        }
        if (daily.dipGatePass()) {
            warnings.add("Daily dip gate passed; execution still needs promoted ML p_win and explicit execution policy.");
        }
        return write(root);
    }

    private FrameResult evaluateFrame(String symbol, String intervalCode, StrategyParams params, boolean allowSynthetic15m) {
        List<MdKline> bars = loadBars(symbol, intervalCode, LOOKBACK_BARS);
        boolean synthetic = false;
        String source = "md_kline:" + intervalCode;
        String freshness = freshnessStatus(intervalCode, bars);
        int requiredBars = proxyRequiredBars(intervalCode, params);
        if ((bars.size() < Math.min(LOOKBACK_BARS, params.minWarmupBars() + 1) || "STALE".equals(freshness))
                && allowSynthetic15m && "15m".equals(intervalCode)) {
            bars = synthesize15mFrom1m(symbol);
            synthetic = true;
            source = "synthetic_15m_from_1m";
            freshness = freshnessStatus(intervalCode, bars);
        }
        if (bars.size() < requiredBars) {
            return FrameResult.insufficient(intervalCode, source, synthetic, freshness, bars.size(), requiredBars);
        }

        double[] close = bars.stream().mapToDouble(k -> safeDouble(k.getClosePrice())).toArray();
        double[] volume = bars.stream().mapToDouble(k -> safeDouble(k.getVolume())).toArray();
        double[] rsi = IndicatorUtils.rsi(close, RSI_PERIOD);
        double[] bollMid = IndicatorUtils.bollingerMiddle(close, BB_PERIOD);
        double[] bollLow = IndicatorUtils.bollingerLower(close, BB_PERIOD, 2.0);
        double[] volMa20 = IndicatorUtils.sma(volume, VOL_MA_PERIOD);
        int idx = bars.size() - 1;
        if (!valid(rsi, idx) || !valid(bollMid, idx) || !valid(bollLow, idx) || !valid(volMa20, idx)) {
            return FrameResult.indicatorUnavailable(intervalCode, source, synthetic, freshness, bars.size());
        }

        MdKline current = bars.get(idx);
        double currentClose = close[idx];
        double currentVolume = volume[idx];
        double bbTrigger = bollLow[idx] + (bollMid[idx] - bollLow[idx]) * 0.3;
        boolean rsiOk = rsi[idx] < params.rsiOversold();
        boolean bbOk = currentClose < bbTrigger;
        boolean volumeOk = volMa20[idx] > 0.0 && currentVolume > volMa20[idx] * params.volumeBreakoutMultiplier();
        List<String> missing = new ArrayList<>();
        if (!rsiOk) missing.add("RSI_NOT_OVERSOLD");
        if (!bbOk) missing.add("NOT_NEAR_LOWER_BOLLINGER");
        if (!volumeOk) missing.add("NO_VOLUME_BREAKOUT");

        return new FrameResult(
                intervalCode,
                source,
                synthetic,
                freshness,
                bars.size(),
                current.getOpenTime(),
                currentClose,
                currentVolume,
                round(rsi[idx], 4),
                round(params.rsiOversold(), 4),
                round(bollLow[idx], 4),
                round(bollMid[idx], 4),
                round(bbTrigger, 4),
                round(volMa20[idx], 4),
                round(params.volumeBreakoutMultiplier(), 4),
                rsiOk,
                bbOk,
                volumeOk,
                rsiOk && bbOk && volumeOk,
                missing,
                "OK"
        );
    }

    private List<MdKline> loadBars(String symbol, String intervalCode, int limit) {
        try {
            List<MdKline> rows = new ArrayList<>(klineRepository.findBySymbolAndIntervalCodeOrderByOpenTimeDesc(
                    symbol, intervalCode, PageRequest.of(0, limit)));
            rows.sort(Comparator.comparing(MdKline::getOpenTime));
            return rows;
        } catch (Exception e) {
            log.warn("[ScoreBuyPreview] load bars failed symbol={} interval={} error={}", symbol, intervalCode, e.getMessage());
            return List.of();
        }
    }

    private List<MdKline> synthesize15mFrom1m(String symbol) {
        List<MdKline> oneMinute = loadBars(symbol, "1m", LOOKBACK_BARS * 16);
        if (oneMinute.isEmpty()) return List.of();
        Map<LocalDateTime, Bucket> buckets = new LinkedHashMap<>();
        for (MdKline k : oneMinute) {
            LocalDateTime t = k.getOpenTime().truncatedTo(ChronoUnit.HOURS)
                    .plusMinutes((k.getOpenTime().getMinute() / 15) * 15L);
            buckets.computeIfAbsent(t, Bucket::new).add(k);
        }
        List<MdKline> out = new ArrayList<>();
        for (Bucket b : buckets.values()) {
            out.add(b.toKline(symbol));
        }
        out.sort(Comparator.comparing(MdKline::getOpenTime));
        if (out.size() > LOOKBACK_BARS) {
            return new ArrayList<>(out.subList(out.size() - LOOKBACK_BARS, out.size()));
        }
        return out;
    }

    private int proxyRequiredBars(String intervalCode, StrategyParams params) {
        if ("15m".equals(intervalCode)) {
            return Math.min(params.minWarmupBars() + 1, 100);
        }
        return params.minWarmupBars() + 1;
    }

    private String freshnessStatus(String intervalCode, List<MdKline> bars) {
        if (bars == null || bars.isEmpty()) return "NO_DATA";
        LocalDateTime latest = bars.stream()
                .map(MdKline::getOpenTime)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        if (latest == null) return "NO_DATA";
        long ageMinutes = ChronoUnit.MINUTES.between(latest, LocalDateTime.now(ZoneOffset.UTC));
        long staleAfter = switch (intervalCode) {
            case "15m" -> 60L;
            case "1h" -> 180L;
            case "1d" -> 60L * 48L;
            default -> 60L * 6L;
        };
        return ageMinutes > staleAfter ? "STALE" : "FRESH";
    }

    private Conviction classifyConviction(FrameResult daily, FrameResult oneHour, FrameResult fifteenMinute) {
        int score = 0;
        if (daily.dipGatePass()) score += 6;
        if (oneHour.dipGatePass()) score += 3;
        if (fifteenMinute.dipGatePass()) score += 2;
        score += nearMissPoints(daily);
        score += nearMissPoints(oneHour);
        score += nearMissPoints(fifteenMinute);
        if (score >= 7) return Conviction.HIGH;
        if (score >= 4) return Conviction.MEDIUM;
        if (score >= 2) return Conviction.LOW;
        return Conviction.NONE;
    }

    private int nearMissPoints(FrameResult r) {
        if (!"OK".equals(r.status()) || r.dipGatePass()) return 0;
        int ok = 0;
        if (r.rsiOk()) ok++;
        if (r.nearLowerBollinger()) ok++;
        if (r.volumeBreakout()) ok++;
        return ok >= 2 ? 1 : 0;
    }

    private String strongestFailureReason(FrameResult daily, FrameResult oneHour, FrameResult fifteenMinute) {
        if (!"OK".equals(daily.status())) return "daily_" + daily.status().toLowerCase(Locale.ROOT);
        if (!daily.dipGatePass()) return "daily_dip_gate_failed:" + String.join(",", daily.missingReasons());
        if (!oneHour.dipGatePass() && !fifteenMinute.dipGatePass()) return "daily_passed_but_intraday_proxy_not_confirming";
        return "dip_gate_forming_ml_gate_not_previewed";
    }

    private CapitalSnapshot readCapitalSnapshot(String symbol) {
        try {
            CapitalAllocationPolicyPreviewService.CapitalAllocationSnapshot snapshot =
                    capitalAllocationPolicyPreviewService.snapshot(symbol);
            return new CapitalSnapshot(
                    snapshot.freeUsdt(),
                    snapshot.earnFlexibleUsdt(),
                    snapshot.totalObservedCapitalUsdt(),
                    snapshot.liquidAfterReserveUsdt(),
                    snapshot.deployableAfterPlannedRedeemUsdt(),
                    snapshot.scoreBuyReserveTargetUsdt(),
                    snapshot.scoreBuyRedeemNeededUsdt(),
                    snapshot.requiresEarnReserveTopUp(),
                    snapshot.warnings());
        } catch (Exception e) {
            return new CapitalSnapshot(
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    false,
                    List.of("capitalAllocationSnapshotReadFailed=" + e.getMessage()));
        }
    }

    private SizingPreview sizingPreview(Conviction conviction, CapitalSnapshot capital) {
        BigDecimal reserveAwareDeployable = capital.reserveAwareDeployableUsdt();
        BigDecimal base;
        String mode;
        switch (conviction) {
            case HIGH -> {
                base = new BigDecimal("50");
                mode = "HIGH_CONVICTION_BOUNDED_SCORE_BUY_PREVIEW";
            }
            case MEDIUM -> {
                base = new BigDecimal("25");
                mode = "MEDIUM_CONVICTION_STAGED_PREVIEW";
            }
            case LOW -> {
                base = new BigDecimal("10");
                mode = "LOW_CONVICTION_PROBE_PREVIEW";
            }
            default -> {
                base = BigDecimal.ZERO;
                mode = "NO_BUY_PREVIEW";
            }
        }
        BigDecimal reserveAwareNotional = reserveAwareDeployable == null
                ? base
                : base.min(reserveAwareDeployable.max(BigDecimal.ZERO));
        if (reserveAwareNotional.compareTo(BigDecimal.ZERO) < 0) reserveAwareNotional = BigDecimal.ZERO;
        BigDecimal immediateTradableCap = capital.liquidAfterReserveUsdt() == null
                ? BigDecimal.ZERO
                : base.min(capital.liquidAfterReserveUsdt().max(BigDecimal.ZERO));
        boolean requiresTopUp = reserveAwareNotional.compareTo(immediateTradableCap) > 0 || capital.requiresEarnReserveTopUp();
        return new SizingPreview(mode, base, reserveAwareNotional, immediateTradableCap, requiresTopUp,
                "v1 uses reserve-aware observed capital and planned SCORE_BUY reserve; Earn reserve top-up is surfaced but not auto-redeemed.");
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

    private static boolean valid(double[] arr, int idx) {
        return arr != null && idx >= 0 && idx < arr.length && !Double.isNaN(arr[idx]) && Double.isFinite(arr[idx]);
    }

    private static double safeDouble(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    private static double round(double value, int scale) {
        if (!Double.isFinite(value)) return value;
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private static String nullTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record StrategyParams(double buyThreshold,
                                 double rsiOversold,
                                 double volumeBreakoutMultiplier,
                                 int minWarmupBars) {
        static StrategyParams from(JsonNode config) {
            return new StrategyParams(
                    config.path("buyThreshold").asDouble(DEFAULT_BUY_THRESHOLD),
                    config.path("rsiOversold").asDouble(DEFAULT_RSI_OVERSOLD),
                    config.path("volumeBreakoutMultiplier").asDouble(DEFAULT_VOLUME_MULT),
                    config.path("minWarmupBars").asInt(200)
            );
        }
    }

    public record FrameResult(String intervalCode,
                              String source,
                              boolean synthetic,
                              String freshnessStatus,
                              int barsUsed,
                              LocalDateTime latestOpenTime,
                              double close,
                              double volume,
                              double rsi,
                              double rsiThreshold,
                              double bollLow,
                              double bollMid,
                              double lowerBandTrigger,
                              double volumeMa20,
                              double volumeBreakoutMultiplier,
                              boolean rsiOk,
                              boolean nearLowerBollinger,
                              boolean volumeBreakout,
                              boolean dipGatePass,
                              List<String> missingReasons,
                              String status) {
        static FrameResult insufficient(String intervalCode, String source, boolean synthetic, String freshnessStatus, int bars, int required) {
            return new FrameResult(intervalCode, source, synthetic, freshnessStatus, bars, null, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    false, false, false, false,
                    List.of("INSUFFICIENT_BARS required=" + required + " actual=" + bars),
                    "INSUFFICIENT_BARS");
        }

        static FrameResult indicatorUnavailable(String intervalCode, String source, boolean synthetic, String freshnessStatus, int bars) {
            return new FrameResult(intervalCode, source, synthetic, freshnessStatus, bars, null, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    false, false, false, false, List.of("INDICATOR_UNAVAILABLE"), "INDICATOR_UNAVAILABLE");
        }

        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            n.put("intervalCode", intervalCode);
            n.put("source", source);
            n.put("synthetic", synthetic);
            n.put("freshnessStatus", freshnessStatus);
            n.put("status", status);
            n.put("barsUsed", barsUsed);
            if (latestOpenTime != null) n.put("latestOpenTime", latestOpenTime.toString());
            putFinite(n, "close", close);
            putFinite(n, "volume", volume);
            putFinite(n, "rsi", rsi);
            putFinite(n, "rsiThreshold", rsiThreshold);
            putFinite(n, "bollLow", bollLow);
            putFinite(n, "bollMid", bollMid);
            putFinite(n, "lowerBandTrigger", lowerBandTrigger);
            putFinite(n, "volumeMa20", volumeMa20);
            putFinite(n, "volumeBreakoutMultiplier", volumeBreakoutMultiplier);
            n.put("rsiOk", rsiOk);
            n.put("nearLowerBollinger", nearLowerBollinger);
            n.put("volumeBreakout", volumeBreakout);
            n.put("dipGatePass", dipGatePass);
            ArrayNode arr = n.putArray("missingReasons");
            missingReasons.forEach(arr::add);
            return n;
        }

        private static void putFinite(ObjectNode n, String field, double value) {
            if (Double.isFinite(value)) n.put(field, value);
            else n.putNull(field);
        }
    }

    private enum Conviction {
        NONE, LOW, MEDIUM, HIGH
    }

    private record CapitalSnapshot(BigDecimal tradingUsdt,
                                   BigDecimal earnUsdt,
                                   BigDecimal observedTotalUsdt,
                                   BigDecimal liquidAfterReserveUsdt,
                                   BigDecimal reserveAwareDeployableUsdt,
                                   BigDecimal scoreBuyReserveTargetUsdt,
                                   BigDecimal scoreBuyRedeemNeededUsdt,
                                   boolean requiresEarnReserveTopUp,
                                   List<String> notes) {
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            putMoney(n, "tradingAvailableUsdt", tradingUsdt);
            putMoney(n, "earnFlexibleUsdt", earnUsdt);
            putMoney(n, "observedTotalUsdt", observedTotalUsdt);
            putMoney(n, "liquidAfterReserveUsdt", liquidAfterReserveUsdt);
            putMoney(n, "reserveAwareDeployableUsdt", reserveAwareDeployableUsdt);
            putMoney(n, "scoreBuyReserveTargetUsdt", scoreBuyReserveTargetUsdt);
            putMoney(n, "scoreBuyRedeemNeededUsdt", scoreBuyRedeemNeededUsdt);
            n.put("requiresEarnReserveTopUp", requiresEarnReserveTopUp);
            n.put("earnAutoRedeemInPreview", false);
            ArrayNode arr = n.putArray("notes");
            notes.forEach(arr::add);
            return n;
        }
    }

    private record SizingPreview(String sizingMode,
                                 BigDecimal nominalTierUsdt,
                                 BigDecimal recommendedNotionalUsdt,
                                 BigDecimal immediateTradableNotionalCapUsdt,
                                 boolean requiresEarnReserveTopUpBeforeExecution,
                                 String notionalCapReason) {
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            n.put("sizingMode", sizingMode);
            putMoney(n, "nominalTierUsdt", nominalTierUsdt);
            putMoney(n, "recommendedNotionalUsdt", recommendedNotionalUsdt);
            putMoney(n, "immediateTradableNotionalCapUsdt", immediateTradableNotionalCapUsdt);
            n.put("requiresEarnReserveTopUpBeforeExecution", requiresEarnReserveTopUpBeforeExecution);
            n.put("sizingCapitalMode", "RESERVE_AWARE_OBSERVED_CAPITAL_PREVIEW");
            n.put("notionalCapReason", notionalCapReason);
            n.put("executionMode", "READ_ONLY_PREVIEW_ONLY");
            return n;
        }
    }

    private static void putMoney(ObjectNode n, String field, BigDecimal value) {
        if (value == null) n.putNull(field);
        else n.put(field, value.setScale(2, RoundingMode.HALF_UP));
    }

    private static class Bucket {
        final LocalDateTime openTime;
        LocalDateTime closeTime;
        BigDecimal open;
        BigDecimal high;
        BigDecimal low;
        BigDecimal close;
        BigDecimal volume = BigDecimal.ZERO;

        Bucket(LocalDateTime openTime) {
            this.openTime = openTime;
        }

        void add(MdKline k) {
            if (open == null) open = k.getOpenPrice();
            high = high == null ? k.getHighPrice() : high.max(k.getHighPrice());
            low = low == null ? k.getLowPrice() : low.min(k.getLowPrice());
            close = k.getClosePrice();
            closeTime = k.getCloseTime();
            volume = volume.add(k.getVolume() == null ? BigDecimal.ZERO : k.getVolume());
        }

        MdKline toKline(String symbol) {
            MdKline k = new MdKline();
            k.setSymbol(symbol);
            k.setIntervalCode("15m");
            k.setOpenTime(openTime);
            k.setCloseTime(closeTime == null ? openTime.plusMinutes(15) : closeTime);
            k.setOpenPrice(open == null ? BigDecimal.ZERO : open);
            k.setHighPrice(high == null ? BigDecimal.ZERO : high);
            k.setLowPrice(low == null ? BigDecimal.ZERO : low);
            k.setClosePrice(close == null ? BigDecimal.ZERO : close);
            k.setVolume(volume);
            k.setSource("synthetic_1m");
            return k;
        }
    }
}
