package com.agora.service.market;

import com.agora.config.properties.ShortSqueezeAlertProperties;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * SQI（Squeeze Indicator）擠倉指數計算服務。
 *
 * 三個子維度 (0-100)：
 *   Short Crowding    (40%)：funding_rate + long_short_ratio
 *   Liquidation Anomaly (40%)：btc_short_liq_usd 5min vs 30天95分位
 *   Price Confirmation  (20%)：price_change_5m
 *
 * 分級：< 30 正常 / 30-49 關注 / 50-74 警告 / >= 75 Critical
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqueezeIndicatorService {

    private final MarketIndicatorHistoryRepository historyRepo;
    private final OkxLiquidationWsService liquidationWs;
    private final ShortSqueezeAlertProperties props;

    private static final String SYM = "BTCUSDT";

    // ── SQI 分級 ──
    public enum Level {
        NORMAL("正常", 0),
        ALERT("關注", 30),    // 30-39：LOG only，不發 TG
        WARNING("警告", 40),  // 40-74：發 TG 警告（V3 調整，K 線 priceConf 更準後的實測閾值）
        CRITICAL("Critical", 75);

        public final String label;
        public final int minScore;
        Level(String label, int minScore) { this.label = label; this.minScore = minScore; }

        public static Level of(int sqi) {
            if (sqi >= 75) return CRITICAL;
            if (sqi >= 50) return WARNING;
            if (sqi >= 30) return ALERT;
            return NORMAL;
        }

        public String emoji() {
            return switch (this) {
                case CRITICAL -> "🔴";
                case WARNING  -> "🟠";
                case ALERT    -> "🟡";
                case NORMAL   -> "🟢";
            };
        }
    }

    @Getter
    public static class SqiResult {
        public final int    sqi;
        public final double crowding;          // 空頭擁擠度 0-100
        public final double liqAnomaly;        // 爆倉異常度 0-100
        public final double priceConfirmation; // 價格確認度 0-100
        public final Level  level;
        public final double liq5m;             // 原始 5min 爆倉 USD（供日誌）
        public final double liqThreshold;      // 95分位閾值
        public double shortBuildIndex = 0;     // #297 空頭燃料積累指數（由 calcSqi 設定）

        SqiResult(double crowding, double liqAnomaly, double priceConfirmation,
                  double liq5m, double liqThreshold) {
            this.crowding          = crowding;
            this.liqAnomaly        = liqAnomaly;
            this.priceConfirmation = priceConfirmation;
            this.liq5m             = liq5m;
            this.liqThreshold      = liqThreshold;
            this.sqi   = (int) Math.min(crowding * 0.40 + liqAnomaly * 0.40 + priceConfirmation * 0.20, 100);
            this.level = Level.of(this.sqi);
        }

        public String formatDecomposed() {
            return String.format("空頭擁擠 %.0f/40 + 爆倉異常 %.0f/40 + 價格確認 %.0f/20",
                    crowding * 0.40, liqAnomaly * 0.40, priceConfirmation * 0.20);
        }
    }

    // ── 主入口 ────────────────────────────────────────────────────────────────

    public SqiResult calcSqi() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        double crowding          = calcShortCrowding(now);
        double[] liqResult       = calcLiquidationAnomaly();
        double liqAnomaly        = liqResult[0];
        double liq5m             = liqResult[1];
        double liqThreshold      = liqResult[2];
        double priceConfirmation = calcPriceConfirmation(now);

        SqiResult result = new SqiResult(crowding, liqAnomaly, priceConfirmation, liq5m, liqThreshold);

        // short_build_index 即時計算（#297），由 ShortSqueezeAlertScheduler 持久化
        result.shortBuildIndex = calcShortBuildIndexLive(now);

        // 診斷 logging：所有超過 30 分的時間點完整記錄
        // 目的：事後可用 grep "SQI_DIAG" 找到誤報，分析三子分構成
        if (result.getSqi() >= 30) {
            log.info("[SQI_DIAG] sqi={} level={} | crowding={}/{} liqAnomaly={}/{} priceConf={}/{}" +
                     " | liq5m={}M p95={}M liqRatio={}x | funding={} lsr={}",
                    result.getSqi(), result.getLevel().label,
                    String.format("%.1f", crowding * 0.40), "40",
                    String.format("%.1f", liqAnomaly * 0.40), "40",
                    String.format("%.1f", priceConfirmation * 0.20), "20",
                    String.format("%.2f", liq5m / 1e6),
                    String.format("%.2f", liqThreshold / 1e6),
                    String.format("%.2f", liqThreshold > 0 ? liq5m / liqThreshold : 0),
                    String.format("%.5f", getLatestIndicator("funding_rate", now)),
                    String.format("%.3f", getLatestIndicator("long_short_ratio", now)));
        } else {
            log.debug("[SQI] sqi={} crowding={} liq={} price={}",
                    result.getSqi(),
                    String.format("%.1f", crowding),
                    String.format("%.1f", liqAnomaly),
                    String.format("%.1f", priceConfirmation));
        }
        return result;
    }

    // ── 子指標計算 ────────────────────────────────────────────────────────────

    /**
     * 空頭擁擠度 (0-100)，根據市場階段自動切換計算方式：
     *
     *  熊市/中性：funding_rate 深度負值 + long_short_ratio（原設計）
     *
     *  牛市：OI/Price 背離（逆勢空頭建倉信號）+ LSR（低閾）+ 費率轉負輔助
     *    - oi_change_pct_1h 上升但 price 不漲 → 逆勢空頭在建倉
     *    - long_short_ratio < 0.82（牛市中較低的空頭偏多閾值）
     *    - funding_rate < 0（即使輕微負也是空頭開始佔主導的信號）
     */
    private double calcShortCrowding(LocalDateTime now) {
        double fundingRate = getLatestIndicator("funding_rate", now);
        double lsr         = getLatestIndicator("long_short_ratio", now);
        double shortPct    = lsr > 0 ? 1 - lsr : 0;

        // 判斷市場階段（30 日資金費率均值）
        boolean isBullMarket = isBullPhase(now);

        if (isBullMarket) {
            return calcBullMarketCrowding(fundingRate, shortPct, now);
        } else {
            return calcBearMarketCrowding(fundingRate, shortPct);
        }
    }

    /** 熊市版空頭擁擠度：funding 深度負 + LSR 高空頭 */
    private double calcBearMarketCrowding(double fundingRate, double shortPct) {
        double fundingScore = fundingRate < -0.005 ? 50
                           : fundingRate < -0.001 ? 30 : 0;
        double lsrScore     = shortPct > 0.70 ? 50 : shortPct > 0.60 ? 25 : 0;
        return Math.min(fundingScore + lsrScore, 100);
    }

    /**
     * 牛市版空頭擁擠度（closes #295）
     *
     * ⚠️ OI/Price 背離信號已移除：
     *   回測驗證 OI > 1% 在擠倉事件中時序相反（在空頭建倉後期觸發，非前期）
     *   且 +24h 下跌率 88.9%，與「擠倉看漲」語義相反。
     *   OI 背離未來另立為 short_build_index（前置燃料指標），不放入 SQI。
     *
     *  信號 A（30分）：Long/Short ratio < 0.82（牛市中仍有大量逆勢空頭）
     *  信號 B（30分）：資金費率轉負（即使輕微，代表空頭開始佔主導）
     *  最高 60 分（牛市 SQI 因無 crowding OI 分量，Critical 需靠 liqAnomaly 推高）
     */
    private double calcBullMarketCrowding(double fundingRate, double shortPct, LocalDateTime now) {
        // 信號 A：牛市中仍有異常高比例的逆勢空頭
        double lsrScore = shortPct > 0.55 ? 30 : shortPct > 0.50 ? 15 : 0;

        // 信號 B：資金費率轉負（即使輕微）
        double fundingScore = fundingRate < -0.00001 ? 30 : 0;

        double score = Math.min(lsrScore + fundingScore, 60);
        log.debug("[SQI_BULL_CROWDING] lsr={} funding={} total={}",
                lsrScore, fundingScore, score);
        return score;
    }

    /**
     * short_build_index 即時計算（#297）
     * 信號 A：OI 上升但價格不漲（逆勢空頭建倉）
     * 信號 B：多空比偏空（牛市低閾 0.82）
     * 信號 C：資金費率近零或轉負
     */
    private double calcShortBuildIndexLive(LocalDateTime now) {
        double oiChangePct = getLatestIndicator("oi_change_pct_1h", now);
        double lsr         = getLatestIndicator("long_short_ratio", now);
        double fundingRate = getLatestIndicator("funding_rate", now);
        double priceChange = calcRecentPriceChange(now); // 1h 方向

        double oiDivScore = 0;
        if (oiChangePct > 1.0 && priceChange < 0.002)  oiDivScore = 40;
        else if (oiChangePct > 0.5 && priceChange < 0) oiDivScore = 25;

        double shortPct = lsr > 0 ? 1 - lsr : 0;
        double lsrScore = shortPct > 0.55 ? 30 : shortPct > 0.50 ? 15 : 0;

        double fundingScore = fundingRate < 0.00001 ? 30 : fundingRate < 0.0001 ? 15 : 0;

        return Math.min(oiDivScore + lsrScore + fundingScore, 100);
    }

    private boolean isBullPhase(LocalDateTime now) {
        var history = historyRepo
                .findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                        SYM, "funding_rate", now.minusDays(30));
        if (history.size() < 24) return false;
        double avg = history.stream().mapToDouble(h -> h.getValue().doubleValue()).average().orElse(0);
        return avg > 0.0001;
    }

    private double calcRecentPriceChange(LocalDateTime now) {
        var prices = historyRepo
                .findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                        SYM, "kraken_btc_usd_price", now.minusHours(2));
        if (prices.size() < 2) return 0;
        double latest = prices.get(prices.size() - 1).getValue().doubleValue();
        double older  = prices.get(0).getValue().doubleValue();
        return older > 0 ? (latest - older) / older : 0;
    }

    /** 爆倉異常度 (0-100)：WS 5min 滾動 / 30天95分位 */
    private double[] calcLiquidationAnomaly() {
        // 優先：WebSocket 即時 5 分鐘滾動數據
        double liq5m = liquidationWs.isConnected() && !liquidationWs.isDegraded()
                ? liquidationWs.getShortLiqUsd(5)
                : 0;

        // 降級：從 mih 讀最新 btc_short_liq_usd_1h（Phase 1 近似）
        if (liq5m == 0) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            liq5m = getLatestIndicator("btc_short_liq_usd_1h", now);
        }

        // 動態閾值：30 天 95 分位
        Double p95 = historyRepo.findPercentile95(
                SYM, "btc_short_liq_usd_1h", LocalDateTime.now(ZoneOffset.UTC).minusDays(30));
        double threshold = (p95 != null && p95 > 0) ? p95 : props.pathBFallbackThreshold();

        double ratio = threshold > 0 ? liq5m / threshold : 0;
        double score = ratio > 3.0 ? 100
                     : ratio > 2.0 ? 75
                     : ratio > 1.5 ? 50
                     : ratio > 1.0 ? 30 : 0;

        return new double[]{score, liq5m, threshold};
    }

    /** 價格確認度 (0-100)：近期 BTC 價格變化 */
    private double calcPriceConfirmation(LocalDateTime now) {
        // 用 kraken_btc_usd_price 近似 5min 變化
        var prices = historyRepo
                .findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                        SYM, "kraken_btc_usd_price", now.minusMinutes(10));
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

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private double getLatestIndicator(String indicator, LocalDateTime now) {
        return historyRepo
                .findTopCleanBySymbolAndIndicator(SYM, indicator)
                .map(h -> h.getValue().doubleValue())
                .orElse(0.0);
    }
}
