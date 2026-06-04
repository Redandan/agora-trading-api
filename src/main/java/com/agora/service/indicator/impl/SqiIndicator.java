package com.agora.service.indicator.impl;

import com.agora.model.MarketIndicatorHistory;
import com.agora.model.MdKline;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.indicator.CompositeIndicator;
import com.agora.service.indicator.CompositeResult;
import com.agora.service.indicator.IndicatorLevel;
import com.agora.service.indicator.SubDimension;
import com.agora.service.market.OkxLiquidationWsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.agora.config.properties.ShortSqueezeAlertProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * SQI 擠倉指數 — CMI Framework 實作。
 *
 * 三個子維度（權重 40/40/20）：
 *   sqi_short_crowding     空頭擁擠度：funding_rate + long_short_ratio
 *   sqi_liquidation_anomaly 爆倉異常度：btc_short_liq_usd vs 30天95分位
 *   sqi_price_confirmation  價格確認度：OKX WS 即時 5min 漲幅
 *
 * 分級：NORMAL 0-29 / ALERT 30-39 / WARNING 40-74 / CRITICAL 75+
 * 回測：V4 驗證，Precision ~90%（過濾後），04-17 擠倉事件捕捉 ✅
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqiIndicator implements CompositeIndicator {

    private final MarketIndicatorHistoryRepository historyRepo;
    private final OkxLiquidationWsService liquidationWs;
    private final MdKlineRepository klineRepo;
    private final ShortSqueezeAlertProperties props;

    private static final String SYM = "BTCUSDT";

    // ── 元數據 ────────────────────────────────────────────────────────────────

    @Override public String getName()        { return "sqi"; }
    @Override public String getDisplayName() { return "SQI 擠倉指數"; }
    @Override public String getSymbol()      { return SYM; }

    @Override
    public List<SubDimension> getDimensions() {
        return List.of(
            new SubDimension("sqi_short_crowding",      "空頭擁擠度", 0.40),
            new SubDimension("sqi_liquidation_anomaly", "爆倉異常度", 0.40),
            new SubDimension("sqi_price_confirmation",  "價格確認度", 0.20)
        );
    }

    // ── 分級 ──────────────────────────────────────────────────────────────────
    @Override public int getAlertThreshold()    { return 30; }
    @Override public int getWarningThreshold()  { return 40; }
    @Override public int getCriticalThreshold() { return 75; }

    // ── 計算 ──────────────────────────────────────────────────────────────────

    @Override
    public CompositeResult calculate(LocalDateTime now) {
        double crowding   = calcShortCrowding(now);
        double[] liqData  = calcLiquidationAnomaly();
        double liqAnomaly = liqData[0];
        double liq5m      = liqData[1];
        double liqP95     = liqData[2];
        double priceConf  = calcPriceConfirmation(now);
        // #405 Phase 2 — short_build_index removed from this indicator's dimValues.
        // ShortBuildIndicator is the sole writer of "short_build_index"; including
        // it here caused CompositeIndicatorScheduler.persist() to write the same
        // name twice per tick (once as SqiIndicator extra, once as ShortBuildIndicator
        // main score), recreating the double-write race that #405 Phase 1 fixed for
        // the four sqi_* names.

        int score = (int) Math.min(crowding * 0.40 + liqAnomaly * 0.40 + priceConf * 0.20, 100);
        IndicatorLevel level = getLevel(score);

        if (score >= getAlertThreshold()) {
            log.info("[SQI_DIAG] sqi={} level={} | crowding={:.1f}/40 liqAnomaly={:.1f}/40 priceConf={:.1f}/20"
                   + " | liq5m={}M p95={}M liqRatio={}x | funding={} lsr={}",
                    score, level.label,
                    crowding * 0.40, liqAnomaly * 0.40, priceConf * 0.20,
                    String.format("%.2f", liq5m / 1e6),
                    String.format("%.2f", liqP95 / 1e6),
                    String.format("%.2f", liqP95 > 0 ? liq5m / liqP95 : 0),
                    String.format("%.5f", getLatestIndicator("funding_rate", now)),
                    String.format("%.3f", getLatestIndicator("long_short_ratio", now)));
        } else {
            log.debug("[SQI] sqi={} crowding={} liq={} price={}",
                    score, crowding, liqAnomaly, priceConf);
        }

        return new CompositeResult(
            score, level,
            Map.of("sqi_short_crowding", crowding,
                   "sqi_liquidation_anomaly", liqAnomaly,
                   "sqi_price_confirmation", priceConf),
            Map.of("liq5m", liq5m, "liqP95", liqP95),
            now, SYM
        );
    }

    // ── 子維度計算 ────────────────────────────────────────────────────────────

    private double calcShortCrowding(LocalDateTime now) {
        double fundingRate = getLatestIndicator("funding_rate", now);
        double lsr         = getLatestIndicator("long_short_ratio", now);
        double shortPct    = lsr > 0 ? 1.0 / (1.0 + lsr) : 0.5;  // lsr 是 L:S 比值，shortPct = 1/(1+lsr)
        boolean isBull     = isBullPhase(now);

        if (isBull) {
            double lsrScore     = shortPct > 0.55 ? 30 : shortPct > 0.50 ? 15 : 0;
            double fundingScore = fundingRate < -0.00001 ? 30 : 0;
            return Math.min(lsrScore + fundingScore, 60);
        } else {
            double fundingScore = fundingRate < -0.005 ? 50 : fundingRate < -0.001 ? 30 : 0;
            double lsrScore     = shortPct > 0.70 ? 50 : shortPct > 0.60 ? 25 : 0;
            return Math.min(fundingScore + lsrScore, 100);
        }
    }

    private double[] calcLiquidationAnomaly() {
        double liq5m = liquidationWs.isConnected() && !liquidationWs.isDegraded()
                ? liquidationWs.getShortLiqUsd(5)
                : 0;
        if (liq5m == 0) {
            liq5m = getLatestIndicator("btc_short_liq_usd_1h",
                    LocalDateTime.now(ZoneOffset.UTC));
        }
        Double p95 = historyRepo.findPercentile95(SYM, "btc_short_liq_usd_1h",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(30));
        double threshold = (p95 != null && p95 > 0) ? p95 : props.pathBFallbackThreshold();
        double ratio = threshold > 0 ? liq5m / threshold : 0;
        double score = ratio > 3.0 ? 100 : ratio > 2.0 ? 75 : ratio > 1.5 ? 50 : ratio > 1.0 ? 30 : 0;
        return new double[]{score, liq5m, threshold};
    }

    private double calcPriceConfirmation(LocalDateTime now) {
        var prices = historyRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                SYM, "kraken_btc_usd_price", now.minusHours(2));
        if (prices.size() < 2) return 0;
        double latest = prices.get(prices.size() - 1).getValue().doubleValue();
        double older  = prices.get(0).getValue().doubleValue();
        double change = older > 0 ? (latest - older) / older : 0;
        if (change > 0.010) return 100;
        if (change > 0.005) return 60;
        if (change > 0.003) return 30;
        if (change > 0)     return 15;
        return 0;
    }

    // ── 告警格式 ──────────────────────────────────────────────────────────────

    @Override
    public String formatAlertMessage(CompositeResult r) {
        return String.format(
            "%s <b>%s SQI = %d</b>\n\n" +
            "📊 %s\n\n" +
            "空頭擁擠度：%.0f/40\n爆倉異常度：%.0f/40\n價格確認度：%.0f/20\n\n" +
            "→ 參考 Rule #37（空頭爆倉 $30M+）",
            r.level().emoji, r.level().label, r.score(),
            r.formatDecomposed(getDimensions()),
            r.dimValues().getOrDefault("sqi_short_crowding", 0.0) * 0.40,
            r.dimValues().getOrDefault("sqi_liquidation_anomaly", 0.0) * 0.40,
            r.dimValues().getOrDefault("sqi_price_confirmation", 0.0) * 0.20);
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private boolean isBullPhase(LocalDateTime now) {
        var h = historyRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                SYM, "funding_rate", now.minusDays(30));
        if (h.size() < 24) return false;
        return h.stream().mapToDouble(r -> r.getValue().doubleValue()).average().orElse(0) > 0.0001;
    }

    private double getLatestIndicator(String indicator, LocalDateTime now) {
        return historyRepo.findTopCleanBySymbolAndIndicator(SYM, indicator)
                .map(h -> h.getValue().doubleValue()).orElse(0.0);
    }

    /**
     * 歷史時間點的指標值（at 時間點前最近一筆）。
     * 修正 calculateHistorical 的核心問題：原 getLatestIndicator 忽略時間參數，
     * 永遠返回當前最新值，導致所有歷史 SQI 分數都用今天的 market data 計算。
     */
    private double getIndicatorAtTime(String indicator, LocalDateTime at) {
        return historyRepo.findTopCleanBySymbolAndIndicatorAndCapturedAtLessThanEqual(
                        SYM, indicator, at)
                .map(h -> h.getValue().doubleValue()).orElse(0.0);
    }

    /**
     * 歷史版 SQI 計算 — 使用時間約束查詢，不依賴 live WS 數據。
     * 修正三個 bug：
     * 1. calcLiquidationAnomaly() 使用 live WS（不適合歷史計算）→ 改用 DB btc_short_liq_usd_1h
     * 2. getLatestIndicator() 忽略時間 → 改用 getIndicatorAtTime()
     * 3. P95 用 LocalDateTime.now() → 改用 at
     */
    @Override
    public CompositeResult calculateHistorical(LocalDateTime at) {
        // ── 空頭擁擠度（歷史版）──────────────────────────────────────
        double fundingRate = getIndicatorAtTime("funding_rate", at);
        double lsr         = getIndicatorAtTime("long_short_ratio", at);
        double shortPct    = lsr > 0 ? 1.0 / (1.0 + lsr) : 0.5;  // lsr 是 L:S 比值，shortPct = 1/(1+lsr)

        double crowding;
        double avgFunding30d = historyRepo
                .findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                        SYM, "funding_rate", at.minusDays(30))
                .stream().filter(h -> !h.getCapturedAt().isAfter(at))
                .mapToDouble(h -> h.getValue().doubleValue()).average().orElse(0);
        boolean isBull = avgFunding30d > 0.0001;

        if (isBull) {
            double lsrScore     = shortPct > 0.55 ? 30 : shortPct > 0.50 ? 15 : 0;
            double fundingScore = fundingRate < -0.00001 ? 30 : 0;
            crowding = Math.min(lsrScore + fundingScore, 60);
        } else {
            double fundingScore = fundingRate < -0.005 ? 50 : fundingRate < -0.001 ? 30 : 0;
            double lsrScore     = shortPct > 0.70 ? 50 : shortPct > 0.60 ? 25 : 0;
            crowding = Math.min(fundingScore + lsrScore, 100);
        }

        // ── 清算異常度（歷史版）—— 使用 DB 數據，不用 live WS ────────
        double liq5m = getIndicatorAtTime("btc_short_liq_usd_1h", at);
        Double p95   = historyRepo.findPercentile95(SYM, "btc_short_liq_usd_1h",
                at.minusDays(30));
        double threshold = (p95 != null && p95 > 0) ? p95 : props.pathBFallbackThreshold();
        double ratio = threshold > 0 ? liq5m / threshold : 0;
        double liqAnomaly = ratio > 3.0 ? 100 : ratio > 2.0 ? 75 : ratio > 1.5 ? 50 : ratio > 1.0 ? 30 : 0;

        // ── 價格確認度（歷史版）—— 使用 1h K 線收盤價（覆蓋 2 年），替代僅 4 天的 kraken_btc_usd_price
        List<MdKline> klines2h = klineRepo.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                SYM, "1h", at.minusHours(3), at);
        double priceConf = 0;
        if (klines2h.size() >= 2) {
            double latest2 = klines2h.get(klines2h.size() - 1).getClosePrice().doubleValue();
            double older2  = klines2h.get(0).getClosePrice().doubleValue();
            double change  = older2 > 0 ? (latest2 - older2) / older2 : 0;
            if (change > 0.010) priceConf = 100;
            else if (change > 0.005) priceConf = 60;
            else if (change > 0.003) priceConf = 30;
            else if (change > 0) priceConf = 15;
        }

        int score = (int) Math.min(crowding * 0.40 + liqAnomaly * 0.40 + priceConf * 0.20, 100);
        IndicatorLevel level = getLevel(score);

        log.debug("[SQI_HIST] at={} sqi={} crowding={} liq={} price={} funding={} lsr={} liq5m={}",
                at.toLocalDate(), score, crowding, liqAnomaly, priceConf,
                String.format("%.5f", fundingRate), String.format("%.3f", lsr),
                String.format("%.2f", liq5m / 1e6));

        return new CompositeResult(
            score, level,
            Map.of("sqi_short_crowding", crowding,
                   "sqi_liquidation_anomaly", liqAnomaly,
                   "sqi_price_confirmation", priceConf),
            Map.of("liq5m", liq5m, "liqP95", threshold),
            at, SYM
        );
    }

    // Coinalyze 清算數據最多 90 天，backfill 對齊至 90 天以利回測
    @Override public int getBackfillDays() { return 90; }
}
