package com.agora.service.backtest;

import com.agora.model.MarketIndicatorHistory;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OI/Funding Divergence 策略 — 偵測假買盤與真實累積。
 *
 * <p>核心假設：當 OI 暴增但現貨成交量沒有對應上升，代表槓桿假買盤，
 * 容易引發多殺多；當 Funding rate 低 + OI 穩定 + 現貨量放大，
 * 代表真實累積，是較可信的做多時機。
 *
 * <p>指標資料來源：{@code market_indicator_history} 表（每小時 snapshot）。
 * 目前系統收集的指標：{@code funding_rate}、{@code oi_change_pct_1h}。
 * 注意：回測效力受限於 indicator_history 的收集起始時間（約 2026-04 起）。
 *
 * <p>Config 參數（可透過 strategy params 覆蓋）：
 * <ul>
 *   <li>{@code fundingThresholdLong} — 做多最高 funding rate（預設 0.0001 = 0.01%/8h）</li>
 *   <li>{@code fundingThresholdHigh} — 高 funding 警戒線（預設 0.0005 = 0.05%/8h）</li>
 *   <li>{@code oiSpikeThreshold} — OI 1h 變化 % 上限（預設 2.0%）</li>
 *   <li>{@code volumeMultiplier} — 現貨量需 > MA20 × 此值（預設 1.2）</li>
 *   <li>{@code sma200Filter} — 是否要求 close > SMA200（預設 true）</li>
 *   <li>{@code stopLossPct} — 止損 %（預設 0.03）</li>
 *   <li>{@code takeProfitPct} — 止盈 %（預設 0.06，2:1 R:R）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OiFundingDivergenceStrategy implements Strategy {

    public static final String TYPE = "OI_FUNDING_DIVERGENCE";

    private static final String SYMBOL = "BTCUSDT";
    private static final String IND_FUNDING   = "funding_rate";
    private static final String IND_OI_CHANGE = "oi_change_pct_1h";
    private static final String IND_DEX_FLOW  = "dex_wbtc_net_flow_usd_1h";
    private static final String IND_CEX_DEX_SPREAD = "funding_rate_cex_dex_spread";
    private static final int DEFAULT_MAX_AGE_MINUTES = 90;
    private static final int MAX_PROVENANCE_LOOKBACK_HOURS = 24;

    private final MarketIndicatorHistoryRepository indicatorRepo;
    private final ObjectMapper objectMapper;

    // Cache: "indicator|yyyy-MM-ddTHH" -> observation. Only positive lookups are cached because
    // the hourly collector may insert a previously missing row after the initial load.
    private final ConcurrentHashMap<String, IndicatorObservation> cache = new ConcurrentHashMap<>();
    private volatile boolean cacheLoaded = false;
    private volatile LocalDateTime cacheSnapshotAt;

    record IndicatorObservation(double value,
                                LocalDateTime capturedAt,
                                LocalDateTime availableAt,
                                String providerPath,
                                String sourceEvidence) {
    }

    /**
     * Invalidates the indicator cache so the next evaluation reloads from DB.
     * Call this after backfilling new market_indicator_history data to ensure
     * the strategy sees the fresh rows without requiring a server restart.
     */
    public void invalidateCache() {
        cache.clear();
        cacheLoaded = false;
        cacheSnapshotAt = null;
        log.info("[OiFundingDivergence] cache invalidated — will reload on next evaluate()");
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public Map<String, Object> defaultExecutionConfig() {
        return Map.ofEntries(
                Map.entry("fundingThresholdLong", 0.0001),
                Map.entry("fundingThresholdHigh", 0.0005),
                Map.entry("oiSpikeThreshold", 2.0),
                Map.entry("volumeMultiplier", 1.2),
                Map.entry("sma200Filter", true),
                Map.entry("dexFlowFilter", false),
                Map.entry("dexFlowMinUsd", 0.0),     // 啟用 dexFlowFilter 時最低 DEX 淨買入金額（USD），0=任何淨買皆可
                Map.entry("spreadFilter", false),
                Map.entry("spreadThreshold", -0.00010),
                Map.entry("marketFeatureFreshnessFailClosed", false),
                Map.entry("marketFeatureReferenceTimeMode", "BAR_OPEN"),
                Map.entry("fundingMaxAgeMinutes", DEFAULT_MAX_AGE_MINUTES),
                Map.entry("oiMaxAgeMinutes", DEFAULT_MAX_AGE_MINUTES),
                Map.entry("dexFlowMaxAgeMinutes", DEFAULT_MAX_AGE_MINUTES),
                Map.entry("spreadMaxAgeMinutes", DEFAULT_MAX_AGE_MINUTES),
                Map.entry("rsiExtremeBypass", false),     // bypass sma200Filter when RSI < threshold
                Map.entry("rsiExtremeThreshold", 20.0),   // RSI below this = extreme oversold bypass
                Map.entry("stopLossPct", 0.03),
                Map.entry("fixedTakeProfitPct", 0.06),
                Map.entry("bottomCatchQualityGateEnabled", true),
                Map.entry("bottomCatchMinRiskReward", 1.0),
                Map.entry("bottomCatchMaxStopLossPct", 0.08),
                Map.entry("allowShort", false),
                Map.entry("runIntervalCode", "1h")
        );
    }

    @Override
    public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
        int i = context.getIndex();
        var current = context.getCurrent();
        if (current == null || i < 20) return StrategySignal.HOLD;

        ensureCacheLoaded();

        boolean freshnessFailClosed = getBoolean(config, "marketFeatureFreshnessFailClosed", false);
        String referenceTimeMode = getString(config, "marketFeatureReferenceTimeMode", "BAR_OPEN");
        LocalDateTime referenceTime = "BAR_CLOSE".equalsIgnoreCase(referenceTimeMode)
                && current.getCloseTime() != null
                ? current.getCloseTime()
                : current.getOpenTime();

        double fundingThresholdLong = getDouble(config, "fundingThresholdLong", 0.0001);
        double fundingThresholdHigh = getDouble(config, "fundingThresholdHigh", 0.0005);
        double oiSpikeThreshold    = getDouble(config, "oiSpikeThreshold", 2.0);
        double volumeMultiplier    = getDouble(config, "volumeMultiplier", 1.2);
        boolean sma200Filter       = getBoolean(config, "sma200Filter", true);
        // spreadFilter=true: block when HL funding >> OKX (DEX speculation diverging from CEX).
        // cexDexSpread = (okx_hr - hl_hr); very negative means HL more bullish = suspicious.
        boolean spreadFilter   = getBoolean(config, "spreadFilter", false);
        double spreadThreshold = getDouble(config, "spreadThreshold", -0.00010);

        // dexFlowFilter=true: require on-chain WBTC net buying as 4th confirmation.
        // dexFlowMinUsd: minimum net DEX buy in USD (0 = any positive is OK).
        // Default false to preserve existing backtest results; enable after V082 data accumulates.
        boolean dexFlowFilter      = getBoolean(config, "dexFlowFilter", false);
        double  dexFlowMinUsd      = getDouble(config, "dexFlowMinUsd", 0.0);

        IndicatorObservation fundingObservation = getIndicatorObservation(
                IND_FUNDING, referenceTime, freshnessFailClosed);
        IndicatorObservation oiObservation = getIndicatorObservation(
                IND_OI_CHANGE, referenceTime, freshnessFailClosed);
        IndicatorObservation dexObservation = getIndicatorObservation(
                IND_DEX_FLOW, referenceTime, freshnessFailClosed);
        IndicatorObservation spreadObservation = getIndicatorObservation(
                IND_CEX_DEX_SPREAD, referenceTime, freshnessFailClosed);

        Double fundingRate = valueOf(fundingObservation);
        Double oiChangePct = valueOf(oiObservation);
        Double dexNetFlow = valueOf(dexObservation);
        Double cexDexSpread = valueOf(spreadObservation);

        // #398 — publish trigger-condition snapshot for SIGNAL_EVAL audit.
        LiveSignalContext.putDetail("funding_rate", fundingRate);
        LiveSignalContext.putDetail("oi_change_pct", oiChangePct);
        if (dexNetFlow != null) LiveSignalContext.putDetail("dex_net_flow", dexNetFlow);
        if (cexDexSpread != null) LiveSignalContext.putDetail("cex_dex_spread", cexDexSpread);
        LiveSignalContext.putDetail("feature_reference_time", referenceTime.toString());
        LiveSignalContext.putDetail("feature_reference_time_mode", referenceTimeMode.toUpperCase());
        LiveSignalContext.putDetail("feature_freshness_fail_closed", freshnessFailClosed);

        List<String> freshnessBlockers = new ArrayList<>();
        publishMarketFeature("funding_rate", fundingObservation, referenceTime,
                getInt(config, "fundingMaxAgeMinutes", DEFAULT_MAX_AGE_MINUTES), true,
                freshnessFailClosed, freshnessBlockers);
        publishMarketFeature("oi_change_pct_1h", oiObservation, referenceTime,
                getInt(config, "oiMaxAgeMinutes", DEFAULT_MAX_AGE_MINUTES), true,
                freshnessFailClosed, freshnessBlockers);
        publishMarketFeature("dex_wbtc_net_flow_usd_1h", dexObservation, referenceTime,
                getInt(config, "dexFlowMaxAgeMinutes", DEFAULT_MAX_AGE_MINUTES), dexFlowFilter,
                freshnessFailClosed, freshnessBlockers);
        publishMarketFeature("funding_rate_cex_dex_spread", spreadObservation, referenceTime,
                getInt(config, "spreadMaxAgeMinutes", DEFAULT_MAX_AGE_MINUTES), spreadFilter,
                freshnessFailClosed, freshnessBlockers);

        // Volume vs configured MA (BacktestEngine default is 5 bars).
        double[] volumeMa = context.getIndicators().get("volumeMa");
        Double currentVol = current.getVolume() != null ? current.getVolume().doubleValue() : null;
        Double volumeMaValue = volumeMa != null && i < volumeMa.length
                && !Double.isNaN(volumeMa[i]) && volumeMa[i] > 0 ? volumeMa[i] : null;
        boolean volumeInputsValid = currentVol != null && currentVol > 0 && volumeMaValue != null;
        boolean volumeConfirmed = volumeInputsValid
                && currentVol > volumeMaValue * volumeMultiplier;

        // SMA200 trend filter — bypass when RSI is in extreme oversold territory.
        // Rationale: at RSI < rsiExtremeThreshold, the market has already de-leveraged
        // (fundingLow ✓) and panic-sold far below fair value. The SMA200 filter would
        // block entries at the exact bottoms this strategy is designed to catch.
        double[] rsiArr = context.getIndicators().get("rsi");
        double currentRsi = (rsiArr != null && i < rsiArr.length && !Double.isNaN(rsiArr[i]))
                ? rsiArr[i] : Double.NaN;
        boolean rsiExtremeBypass = getBoolean(config, "rsiExtremeBypass", false);
        double rsiExtremeThreshold = getDouble(config, "rsiExtremeThreshold", 20.0);
        boolean extremeOversold = rsiExtremeBypass && !Double.isNaN(currentRsi)
                && currentRsi < rsiExtremeThreshold;

        double[] sma200 = context.getIndicators().get("sma200");
        Double sma200Value = sma200 != null && i < sma200.length && !Double.isNaN(sma200[i])
                ? sma200[i] : null;
        boolean sma200Required = sma200Filter && !extremeOversold;
        boolean aboveSma200 = !sma200Filter
                || extremeOversold   // RSI extreme → bypass SMA200 gate
                || (sma200Value != null && current.getClosePrice().doubleValue() > sma200Value);

        publishTechnicalProvenance(current, currentVol, volumeMaValue,
                getInt(config, "volumeMaPeriod", 5), volumeMultiplier,
                sma200Value, currentRsi, sma200Required, extremeOversold);
        if (freshnessFailClosed && !volumeInputsValid) {
            freshnessBlockers.add("VOLUME_INPUT_MISSING");
        }
        if (freshnessFailClosed && sma200Required && sma200Value == null) {
            freshnessBlockers.add("SMA200_INPUT_MISSING");
        }

        // Legacy mode preserves the old backtest behavior. The versioned 508 lane
        // enables strict mode, where both core market-structure features are required.
        if (!freshnessFailClosed && fundingRate == null && oiChangePct == null) {
            LiveSignalContext.putDetail("hold_reason", "indicators_missing");
            return StrategySignal.HOLD;
        }

        boolean fundingLow = fundingRate == null
                ? !freshnessFailClosed
                : fundingRate <= fundingThresholdLong;
        boolean oiStable = oiChangePct == null
                ? !freshnessFailClosed
                : Math.abs(oiChangePct) <= oiSpikeThreshold;
        boolean dexFlowConfirmed = !dexFlowFilter
                || (dexNetFlow == null ? !freshnessFailClosed : dexNetFlow >= dexFlowMinUsd);
        boolean spreadOk = !spreadFilter
                || (cexDexSpread == null ? !freshnessFailClosed : cexDexSpread >= spreadThreshold);

        LiveSignalContext.putDetail("feature_freshness_clear", freshnessBlockers.isEmpty());
        LiveSignalContext.putDetail("feature_freshness_blockers",
                freshnessBlockers.isEmpty() ? "NONE" : String.join(",", freshnessBlockers));
        LiveSignalContext.putDetail("gate_funding_low", fundingLow);
        LiveSignalContext.putDetail("gate_oi_stable", oiStable);
        LiveSignalContext.putDetail("gate_volume_confirmed", volumeConfirmed);
        LiveSignalContext.putDetail("gate_above_sma200", aboveSma200);
        LiveSignalContext.putDetail("gate_dex_flow", dexFlowConfirmed);
        LiveSignalContext.putDetail("gate_spread_ok", spreadOk);

        if (freshnessFailClosed && !freshnessBlockers.isEmpty()) {
            recordDiag(config, referenceTime, "FEATURE_FRESHNESS_BLOCKED",
                    String.join(",", freshnessBlockers));
            LiveSignalContext.putDetail("hold_reason", "feature_freshness_blocked");
            return StrategySignal.HOLD;
        }

        // Fake buying: OI spiked AND funding high → stay out
        boolean oiSpiked     = oiChangePct != null && oiChangePct > oiSpikeThreshold;
        boolean fundingHigh  = fundingRate  != null && fundingRate  > fundingThresholdHigh;
        if (oiSpiked && fundingHigh) {
            recordDiag(config, referenceTime, "FAKE_BUYING", String.format(
                    "oi_chg=%.2f%%>%.1f%% AND funding=%.5f>%.5f",
                    oiChangePct, oiSpikeThreshold, fundingRate, fundingThresholdHigh));
            LiveSignalContext.putDetail("hold_reason", "fake_buying");
            return StrategySignal.HOLD;
        }

        // Real accumulation: funding low + OI stable + volume real + trend ok
        if (fundingLow && oiStable && volumeConfirmed && aboveSma200 && dexFlowConfirmed && spreadOk) {
            recordDiag(config, referenceTime, "BUY_SIGNAL", String.format(
                    "funding=%.5f oiChg=%.2f%% vol=%.0f ma=%.0f dexFlow=%s rsi=%.1f%s",
                    fundingRate != null ? fundingRate : 0,
                    oiChangePct != null ? oiChangePct : 0,
                    currentVol != null ? currentVol : 0, volumeMaValue != null ? volumeMaValue : 0,
                    dexNetFlow != null ? String.format("%.0f", dexNetFlow) : "n/a",
                    currentRsi,
                    extremeOversold ? " [RSI_EXTREME_BYPASS]" : ""));
            LiveSignalContext.putDetail("trigger_reason", "all_gates_passed");
            return StrategySignal.BUY;
        }

        LiveSignalContext.putDetail("hold_reason", "gate_failed");
        return StrategySignal.HOLD;
    }

    // ── Indicator data loading ────────────────────────────────────────────────

    private void ensureCacheLoaded() {
        if (cacheLoaded) return;
        synchronized (this) {
            if (cacheLoaded) return;
            LocalDateTime snapshotAt = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime since = snapshotAt.minusYears(3);
            List<MarketIndicatorHistory> funding =
                    indicatorRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                            SYMBOL, IND_FUNDING, since);
            List<MarketIndicatorHistory> oiChange =
                    indicatorRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                            SYMBOL, IND_OI_CHANGE, since);
            List<MarketIndicatorHistory> dexFlow =
                    indicatorRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                            SYMBOL, IND_DEX_FLOW, since);
            funding.forEach(row -> cacheRow(IND_FUNDING, row));
            oiChange.forEach(row -> cacheRow(IND_OI_CHANGE, row));
            dexFlow.forEach(row -> cacheRow(IND_DEX_FLOW, row));
            List<MarketIndicatorHistory> spread =
                    indicatorRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                            SYMBOL, IND_CEX_DEX_SPREAD, since);
            spread.forEach(row -> cacheRow(IND_CEX_DEX_SPREAD, row));
            log.info("[OiFundingDivergence] Cache loaded: {} funding, {} oi_change, {} dex_flow, {} spread rows",
                    funding.size(), oiChange.size(), dexFlow.size(), spread.size());
            cacheSnapshotAt = snapshotAt;
            cacheLoaded = true;
        }
    }

    private IndicatorObservation getIndicatorObservation(String indicator,
                                                         LocalDateTime referenceTime,
                                                         boolean strictAtOrBefore) {
        if (referenceTime == null) return null;
        return strictAtOrBefore
                ? getStrictObservation(indicator, referenceTime)
                : getLegacyObservation(indicator, referenceTime);
    }

    private IndicatorObservation getStrictObservation(String indicator, LocalDateTime referenceTime) {
        LocalDateTime referenceHour = referenceTime.truncatedTo(ChronoUnit.HOURS);
        IndicatorObservation futureSourceCandidate = null;
        for (int offset = 0; offset <= MAX_PROVENANCE_LOOKBACK_HOURS; offset++) {
            IndicatorObservation observation = cache.get(cacheKey(indicator, referenceHour.minusHours(offset)));
            if (observation != null && !observation.availableAt().isAfter(referenceTime)) {
                if (!observation.capturedAt().isAfter(referenceTime)) return observation;
                if (futureSourceCandidate == null) futureSourceCandidate = observation;
            }
        }

        LocalDateTime snapshot = cacheSnapshotAt;
        boolean mayHaveArrivedAfterSnapshot = snapshot == null
                || !referenceHour.isBefore(snapshot.truncatedTo(ChronoUnit.HOURS));
        if (!mayHaveArrivedAfterSnapshot) return futureSourceCandidate;

        IndicatorObservation refreshed = indicatorRepo.findTopCleanBySymbolAndIndicatorAndCapturedAtLessThanEqual(
                        SYMBOL, indicator, referenceTime)
                .filter(row -> row.getCapturedAt() != null && !row.getCapturedAt().isAfter(referenceTime))
                .map(row -> {
                    cacheRow(indicator, row);
                    return toObservation(indicator, row);
                })
                .orElse(null);
        if (refreshed != null && !refreshed.availableAt().isAfter(referenceTime)) {
            if (!refreshed.capturedAt().isAfter(referenceTime)) return refreshed;
            if (futureSourceCandidate == null) futureSourceCandidate = refreshed;
        }
        return futureSourceCandidate;
    }

    private IndicatorObservation getLegacyObservation(String indicator, LocalDateTime hourKey) {
        LocalDateTime exactHour = hourKey.truncatedTo(ChronoUnit.HOURS);
        LocalDateTime previousHour = exactHour.minusHours(1);

        IndicatorObservation exact = cache.get(cacheKey(indicator, exactHour));
        if (exact != null) return exact;

        // The bulk snapshot already proves old missing hours are absent. Query only hours
        // that can have been written after that snapshot, then fall back one hour.
        LocalDateTime snapshot = cacheSnapshotAt;
        boolean mayHaveArrivedAfterSnapshot = snapshot == null
                || !exactHour.isBefore(snapshot.truncatedTo(ChronoUnit.HOURS));
        if (mayHaveArrivedAfterSnapshot) {
            var refreshed = indicatorRepo.findTopCleanInCapturedAtWindow(
                    SYMBOL, indicator, previousHour, exactHour.plusHours(1));
            if (refreshed.isPresent()) {
                MarketIndicatorHistory row = refreshed.get();
                LocalDateTime capturedHour = row.getCapturedAt().truncatedTo(ChronoUnit.HOURS);
                IndicatorObservation observation = toObservation(indicator, row);
                cache.put(cacheKey(indicator, capturedHour), observation);
                if (capturedHour.equals(exactHour)) {
                    return observation;
                }
            }
        }

        return cache.get(cacheKey(indicator, previousHour));
    }

    private void cacheRow(String indicator, MarketIndicatorHistory row) {
        if (row == null || row.getCapturedAt() == null || row.getValue() == null) return;
        cache.put(cacheKey(indicator, row.getCapturedAt().truncatedTo(ChronoUnit.HOURS)),
                toObservation(indicator, row));
    }

    private IndicatorObservation toObservation(String indicator, MarketIndicatorHistory row) {
        String providerPath = defaultProviderPath(indicator);
        String sourceEvidence = "LEGACY_ROW_METADATA_MISSING";
        LocalDateTime effectiveCapturedAt = row.getCapturedAt();
        LocalDateTime availableAt = row.getCapturedAt();
        String metadata = row.getMetadataJson();
        if (metadata != null && !metadata.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(metadata);
                String declared = firstText(node, "providerPath", "provider", "source");
                if (declared != null) providerPath = declared;
                String effectiveText = firstText(node, "effectiveCapturedAt");
                if (effectiveText != null) {
                    LocalDateTime parsed = parseMetadataTime(effectiveText);
                    if (parsed != null) effectiveCapturedAt = parsed;
                }
                String availableText = firstText(node, "availableAt", "observedAt");
                if (availableText != null) {
                    LocalDateTime parsed = parseMetadataTime(availableText);
                    if (parsed != null) availableAt = parsed;
                }
                sourceEvidence = "ROW_METADATA";
            } catch (Exception ignored) {
                sourceEvidence = "ROW_METADATA_INVALID";
            }
        }
        return new IndicatorObservation(row.getValue().doubleValue(), effectiveCapturedAt,
                availableAt, providerPath, sourceEvidence);
    }

    private String firstText(JsonNode node, String... keys) {
        if (node == null) return null;
        for (String key : keys) {
            String value = node.path(key).asText("").trim();
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    private LocalDateTime parseMetadataTime(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (Exception ignored) {
            try {
                return LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private static String defaultProviderPath(String indicator) {
        return switch (indicator) {
            case IND_FUNDING -> "MIH:OKX_PUBLIC_FUNDING_RATE";
            case IND_OI_CHANGE -> "MIH:BINANCE_FUTURES_OR_OKX_FALLBACK_DERIVED";
            case IND_DEX_FLOW -> "MIH:THE_GRAPH_UNISWAP_V3";
            case IND_CEX_DEX_SPREAD -> "MIH:OKX_HYPERLIQUID_DERIVED";
            default -> "MIH:UNKNOWN";
        };
    }

    private static Double valueOf(IndicatorObservation observation) {
        return observation == null ? null : observation.value();
    }

    private void publishMarketFeature(String indicator,
                                      IndicatorObservation observation,
                                      LocalDateTime referenceTime,
                                      int maxAgeMinutes,
                                      boolean required,
                                      boolean failClosed,
                                      List<String> blockers) {
        String key = "feature_" + indicator;
        LiveSignalContext.putDetail(key + "_required", required);
        LiveSignalContext.putDetail(key + "_max_age_minutes", maxAgeMinutes);
        LiveSignalContext.putDetail(key + "_provider_path",
                observation != null ? observation.providerPath() : defaultProviderPath(indicator));
        LiveSignalContext.putDetail(key + "_source_evidence",
                observation != null ? observation.sourceEvidence() : "NO_ROW");

        if (observation == null) {
            String state = required ? "MISSING_REQUIRED" : "DISABLED_NOT_REQUIRED_NO_SAMPLE";
            LiveSignalContext.putDetail(key + "_freshness", state);
            if (required && failClosed) blockers.add(featureBlocker(indicator, "MISSING"));
            return;
        }

        Duration age = Duration.between(observation.capturedAt(), referenceTime);
        long ageSeconds = age.getSeconds();
        long ageMinutes = Math.floorDiv(ageSeconds, 60L);
        LiveSignalContext.putDetail(key + "_value", observation.value());
        LiveSignalContext.putDetail(key + "_captured_at", observation.capturedAt().toString());
        LiveSignalContext.putDetail(key + "_available_at", observation.availableAt().toString());
        LiveSignalContext.putDetail(key + "_age_minutes", ageMinutes);
        LiveSignalContext.putDetail(key + "_age_seconds", ageSeconds);

        if (!required) {
            LiveSignalContext.putDetail(key + "_freshness", "DISABLED_NOT_REQUIRED");
        } else if (age.isNegative()) {
            LiveSignalContext.putDetail(key + "_freshness", "FUTURE_REQUIRED");
            if (failClosed) blockers.add(featureBlocker(indicator, "FUTURE"));
        } else if (age.compareTo(Duration.ofMinutes(Math.max(1, maxAgeMinutes))) > 0) {
            LiveSignalContext.putDetail(key + "_freshness", "STALE_REQUIRED");
            if (failClosed) blockers.add(featureBlocker(indicator, "STALE"));
        } else {
            LiveSignalContext.putDetail(key + "_freshness", "FRESH");
        }
    }

    private static String featureBlocker(String indicator, String state) {
        String feature = switch (indicator) {
            case IND_FUNDING -> "FUNDING_RATE";
            case IND_OI_CHANGE -> "OI_CHANGE_PCT_1H";
            case IND_DEX_FLOW -> "DEX_WBTC_FLOW";
            case IND_CEX_DEX_SPREAD -> "CEX_DEX_SPREAD";
            default -> indicator.toUpperCase();
        };
        return feature + "_" + state;
    }

    private void publishTechnicalProvenance(com.agora.model.MdKline current,
                                            Double currentVolume,
                                            Double volumeMa,
                                            int volumeMaPeriod,
                                            double volumeMultiplier,
                                            Double sma200,
                                            double rsi,
                                            boolean sma200Required,
                                            boolean extremeOversold) {
        String source = "MD_KLINE:" + String.valueOf(current.getSource()).toUpperCase()
                + ":" + current.getIntervalCode();
        LiveSignalContext.putDetail("feature_volume_provider_path", source);
        LiveSignalContext.putDetail("feature_volume_bar_open_time", current.getOpenTime().toString());
        LiveSignalContext.putDetail("feature_volume_bar_close_time",
                current.getCloseTime() == null ? "UNKNOWN" : current.getCloseTime().toString());
        LiveSignalContext.putDetail("feature_volume_value", currentVolume);
        LiveSignalContext.putDetail("feature_volume_ma_period", volumeMaPeriod);
        LiveSignalContext.putDetail("feature_volume_ma_value", volumeMa);
        LiveSignalContext.putDetail("feature_volume_multiplier", volumeMultiplier);
        LiveSignalContext.putDetail("feature_volume_freshness",
                currentVolume != null && currentVolume > 0 && volumeMa != null
                        ? "FRESH_CLOSED_BAR" : "MISSING_REQUIRED");

        LiveSignalContext.putDetail("feature_sma200_provider_path", source);
        LiveSignalContext.putDetail("feature_sma200_required", sma200Required);
        LiveSignalContext.putDetail("feature_sma200_value", sma200);
        LiveSignalContext.putDetail("feature_sma200_close_price", current.getClosePrice());
        LiveSignalContext.putDetail("feature_sma200_rsi", Double.isNaN(rsi) ? null : rsi);
        LiveSignalContext.putDetail("feature_sma200_freshness",
                !sma200Required && extremeOversold ? "BYPASSED_RSI_EXTREME"
                        : !sma200Required ? "DISABLED_NOT_REQUIRED"
                        : sma200 != null ? "FRESH_CLOSED_BAR" : "MISSING_REQUIRED");
    }

    private static String cacheKey(String indicator, LocalDateTime hour) {
        return indicator + "|" + hour;
    }

    // ── Config helpers ────────────────────────────────────────────────────────

    private static double getDouble(Map<String, Object> config, String key, double def) {
        Object v = config.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v != null) {
            try {
                return Double.parseDouble(String.valueOf(v));
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static int getInt(Map<String, Object> config, String key, int def) {
        Object v = config.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v));
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static String getString(Map<String, Object> config, String key, String def) {
        Object value = config.get(key);
        return value == null ? def : String.valueOf(value);
    }

    private static boolean getBoolean(Map<String, Object> config, String key, boolean def) {
        Object v = config.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        if (v != null) return Boolean.parseBoolean(String.valueOf(v));
        return def;
    }

    @SuppressWarnings("unchecked")
    private static void recordDiag(Map<String, Object> config, LocalDateTime time, String code, String detail) {
        Object diagList = config.get("__diagnostics");
        if (diagList instanceof List) {
            ((List<String>) diagList).add(time + " [" + code + "] " + detail);
        }
    }
}
