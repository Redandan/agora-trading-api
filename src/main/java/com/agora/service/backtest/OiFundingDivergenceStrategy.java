package com.agora.service.backtest;

import com.agora.model.MarketIndicatorHistory;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private final MarketIndicatorHistoryRepository indicatorRepo;

    // Cache: "indicator|yyyy-MM-ddTHH" -> value (Optional.empty = no data for that hour)
    // Historical data is immutable; safe to cache across all backtest runs.
    private final ConcurrentHashMap<String, Optional<Double>> cache = new ConcurrentHashMap<>();
    private volatile boolean cacheLoaded = false;

    /**
     * Invalidates the indicator cache so the next evaluation reloads from DB.
     * Call this after backfilling new market_indicator_history data to ensure
     * the strategy sees the fresh rows without requiring a server restart.
     */
    public void invalidateCache() {
        cache.clear();
        cacheLoaded = false;
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
                Map.entry("rsiExtremeBypass", false),     // bypass sma200Filter when RSI < threshold
                Map.entry("rsiExtremeThreshold", 20.0),   // RSI below this = extreme oversold bypass
                Map.entry("stopLossPct", 0.03),
                Map.entry("fixedTakeProfitPct", 0.06),
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

        LocalDateTime barHour = current.getOpenTime().truncatedTo(ChronoUnit.HOURS);

        Double fundingRate  = getIndicatorValue(IND_FUNDING, barHour);
        Double oiChangePct  = getIndicatorValue(IND_OI_CHANGE, barHour);
        Double dexNetFlow   = getIndicatorValue(IND_DEX_FLOW, barHour);
        Double cexDexSpread = getIndicatorValue(IND_CEX_DEX_SPREAD, barHour);

        // #398 — publish trigger-condition snapshot for SIGNAL_EVAL audit.
        LiveSignalContext.putDetail("funding_rate", fundingRate);
        LiveSignalContext.putDetail("oi_change_pct", oiChangePct);
        if (dexNetFlow != null) LiveSignalContext.putDetail("dex_net_flow", dexNetFlow);
        if (cexDexSpread != null) LiveSignalContext.putDetail("cex_dex_spread", cexDexSpread);

        // No indicator data at all for this period → HOLD (before collector started)
        if (fundingRate == null && oiChangePct == null) {
            LiveSignalContext.putDetail("hold_reason", "indicators_missing");
            return StrategySignal.HOLD;
        }

        double fundingThresholdLong = getDouble(config, "fundingThresholdLong", 0.0001);
        double fundingThresholdHigh = getDouble(config, "fundingThresholdHigh", 0.0005);
        double oiSpikeThreshold    = getDouble(config, "oiSpikeThreshold", 2.0);
        double volumeMultiplier    = getDouble(config, "volumeMultiplier", 1.2);
        boolean sma200Filter       = getBoolean(config, "sma200Filter", true);
        // spreadFilter=true: block when HL funding >> OKX (DEX speculation diverging from CEX).
        // cexDexSpread = (okx_hr - hl_hr); very negative means HL more bullish = suspicious.
        boolean spreadFilter   = getBoolean(config, "spreadFilter", false);
        double spreadThreshold = getDouble(config, "spreadThreshold", -0.00010);
        boolean spreadOk = !spreadFilter || cexDexSpread == null || cexDexSpread >= spreadThreshold;

        // dexFlowFilter=true: require on-chain WBTC net buying as 4th confirmation.
        // dexFlowMinUsd: minimum net DEX buy in USD (0 = any positive is OK).
        // Default false to preserve existing backtest results; enable after V082 data accumulates.
        boolean dexFlowFilter      = getBoolean(config, "dexFlowFilter", false);
        double  dexFlowMinUsd      = getDouble(config, "dexFlowMinUsd", 0.0);

        // Volume vs MA20
        double[] volumeMa = context.getIndicators().get("volumeMa");
        double currentVol = current.getVolume() != null ? current.getVolume().doubleValue() : 0;
        boolean volumeConfirmed = volumeMa != null
                && i < volumeMa.length
                && volumeMa[i] > 0
                && currentVol > volumeMa[i] * volumeMultiplier;

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
        boolean aboveSma200 = !sma200Filter
                || extremeOversold   // RSI extreme → bypass SMA200 gate
                || (sma200 != null && i < sma200.length
                    && !Double.isNaN(sma200[i])
                    && current.getClosePrice().doubleValue() > sma200[i]);

        // Fake buying: OI spiked AND funding high → stay out
        boolean oiSpiked     = oiChangePct != null && oiChangePct > oiSpikeThreshold;
        boolean fundingHigh  = fundingRate  != null && fundingRate  > fundingThresholdHigh;
        if (oiSpiked && fundingHigh) {
            recordDiag(config, barHour, "FAKE_BUYING", String.format(
                    "oi_chg=%.2f%%>%.1f%% AND funding=%.5f>%.5f",
                    oiChangePct, oiSpikeThreshold, fundingRate, fundingThresholdHigh));
            LiveSignalContext.putDetail("hold_reason", "fake_buying");
            return StrategySignal.HOLD;
        }

        // Real accumulation: funding low + OI stable + volume real + trend ok
        boolean fundingLow = fundingRate == null || fundingRate <= fundingThresholdLong;
        boolean oiStable   = oiChangePct == null || Math.abs(oiChangePct) <= oiSpikeThreshold;
        // dexFlowConfirmed: true when filter disabled, or when on-chain data shows net buying.
        // Treats null DEX data as neutral (pass-through) to avoid false negatives during gaps.
        boolean dexFlowConfirmed = !dexFlowFilter || dexNetFlow == null || dexNetFlow >= dexFlowMinUsd;

        // #398 — surface each gate's pass/fail so HOLD audit shows the failing condition
        LiveSignalContext.putDetail("gate_funding_low", fundingLow);
        LiveSignalContext.putDetail("gate_oi_stable", oiStable);
        LiveSignalContext.putDetail("gate_volume_confirmed", volumeConfirmed);
        LiveSignalContext.putDetail("gate_above_sma200", aboveSma200);
        LiveSignalContext.putDetail("gate_dex_flow", dexFlowConfirmed);
        LiveSignalContext.putDetail("gate_spread_ok", spreadOk);

        if (fundingLow && oiStable && volumeConfirmed && aboveSma200 && dexFlowConfirmed && spreadOk) {
            recordDiag(config, barHour, "BUY_SIGNAL", String.format(
                    "funding=%.5f oiChg=%.2f%% vol=%.0f ma=%.0f dexFlow=%s rsi=%.1f%s",
                    fundingRate != null ? fundingRate : 0,
                    oiChangePct != null ? oiChangePct : 0,
                    currentVol, volumeMa != null && i < volumeMa.length ? volumeMa[i] : 0,
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
            LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusYears(3);
            List<MarketIndicatorHistory> funding =
                    indicatorRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                            SYMBOL, IND_FUNDING, since);
            List<MarketIndicatorHistory> oiChange =
                    indicatorRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                            SYMBOL, IND_OI_CHANGE, since);
            List<MarketIndicatorHistory> dexFlow =
                    indicatorRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                            SYMBOL, IND_DEX_FLOW, since);
            for (MarketIndicatorHistory h : funding) {
                String key = cacheKey(IND_FUNDING, h.getCapturedAt().truncatedTo(ChronoUnit.HOURS));
                cache.put(key, Optional.of(h.getValue().doubleValue()));
            }
            for (MarketIndicatorHistory h : oiChange) {
                String key = cacheKey(IND_OI_CHANGE, h.getCapturedAt().truncatedTo(ChronoUnit.HOURS));
                cache.put(key, Optional.of(h.getValue().doubleValue()));
            }
            for (MarketIndicatorHistory h : dexFlow) {
                String key = cacheKey(IND_DEX_FLOW, h.getCapturedAt().truncatedTo(ChronoUnit.HOURS));
                cache.put(key, Optional.of(h.getValue().doubleValue()));
            }
            List<MarketIndicatorHistory> spread =
                    indicatorRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                            SYMBOL, IND_CEX_DEX_SPREAD, since);
            for (MarketIndicatorHistory h : spread) {
                String key = cacheKey(IND_CEX_DEX_SPREAD, h.getCapturedAt().truncatedTo(ChronoUnit.HOURS));
                cache.put(key, Optional.of(h.getValue().doubleValue()));
            }
            log.info("[OiFundingDivergence] Cache loaded: {} funding, {} oi_change, {} dex_flow, {} spread rows",
                    funding.size(), oiChange.size(), dexFlow.size(), spread.size());
            cacheLoaded = true;
        }
    }

    private Double getIndicatorValue(String indicator, LocalDateTime hourKey) {
        // Try exact hour, then previous hour (collector runs at :01, bar opens at :00)
        Optional<Double> v = cache.get(cacheKey(indicator, hourKey));
        if (v == null) v = cache.get(cacheKey(indicator, hourKey.minusHours(1)));
        return v != null ? v.orElse(null) : null;
    }

    private static String cacheKey(String indicator, LocalDateTime hour) {
        return indicator + "|" + hour;
    }

    // ── Config helpers ────────────────────────────────────────────────────────

    private static double getDouble(Map<String, Object> config, String key, double def) {
        Object v = config.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }

    private static boolean getBoolean(Map<String, Object> config, String key, boolean def) {
        Object v = config.get(key);
        if (v instanceof Boolean b) return b;
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
