package com.agora.service.indicator.impl;

import com.agora.model.MdKline;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.indicator.CompositeIndicator;
import com.agora.service.indicator.CompositeResult;
import com.agora.service.indicator.IndicatorLevel;
import com.agora.service.indicator.SubDimension;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * 穩定幣需求指數（Stablecoin Demand Index，SDI）— CMI Framework 實作。
 *
 * <p>穩定幣存量上升 = 場內待入市資金充足，是下跌後反彈的流動性後盾。
 * 與 SQI 互補：SQI 看空頭被擠出，SDI 看多頭援軍是否在場。
 *
 * <p>三個子維度：
 * <ul>
 *   <li><b>供應增長（40%）</b>：穩定幣 7 日累積淨增幅，衡量資金流入趨勢</li>
 *   <li><b>BTC/穩幣背離（35%）</b>：BTC 跌但穩幣供應漲，最強做多信號</li>
 *   <li><b>歷史相對高位（25%）</b>：穩幣供應 vs 30 日 MA，衡量存量充裕程度</li>
 * </ul>
 *
 * <p>分級：NORMAL 0-29 / ALERT 30-49 / WARNING 50-74 / CRITICAL 75+
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StablecoinDemandIndicator implements CompositeIndicator {

    private final MarketIndicatorHistoryRepository historyRepo;
    private final MdKlineRepository klineRepo;

    private static final String SYM = "BTCUSDT";

    @Override public String getName()        { return "stablecoin_demand_index"; }
    @Override public String getDisplayName() { return "穩定幣需求指數"; }
    @Override public String getSymbol()      { return SYM; }

    @Override
    public List<SubDimension> getDimensions() {
        return List.of(
            new SubDimension("sdi_supply_growth",  "穩幣供應增長", 0.40),
            new SubDimension("sdi_divergence",     "BTC/穩幣背離", 0.35),
            new SubDimension("sdi_relative_level", "存量相對高位", 0.25)
        );
    }

    @Override public int getAlertThreshold()    { return 30; }
    @Override public int getWarningThreshold()  { return 50; }
    @Override public int getCriticalThreshold() { return 75; }

    @Override
    public CompositeResult calculate(LocalDateTime now) {
        return calcAt(now);
    }

    @Override
    public CompositeResult calculateHistorical(LocalDateTime at) {
        return calcAt(at);
    }

    private CompositeResult calcAt(LocalDateTime at) {
        // ── 信號 A：穩定幣 7 日供應增長（40分）──
        double supplyGrowthScore = calcSupplyGrowthScore(at);

        // ── 信號 B：BTC 跌但穩幣漲的背離（35分）──
        double divergenceScore = calcDivergenceScore(at);

        // ── 信號 C：穩定幣供應在 30 日相對高位（25分）──
        double relativeLevelScore = calcRelativeLevelScore(at);

        int score = (int) Math.min(
            supplyGrowthScore * 0.40 + divergenceScore * 0.35 + relativeLevelScore * 0.25, 100);
        IndicatorLevel level = getLevel(score);

        log.debug("[SDI] at={} growth={} divergence={} level={} score={}",
                at, supplyGrowthScore, divergenceScore, relativeLevelScore, score);

        return new CompositeResult(
            score, level,
            Map.of("sdi_supply_growth",  supplyGrowthScore,
                   "sdi_divergence",     divergenceScore,
                   "sdi_relative_level", relativeLevelScore),
            Map.of(),
            at, SYM
        );
    }

    /** 信號 A：穩定幣 7 日累積增長率 */
    private double calcSupplyGrowthScore(LocalDateTime at) {
        // 讀取 7 天的 stablecoin_supply_change_pct_24h 或從供應量計算
        var supply7d = historyRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                SYM, "stablecoin_total_mcap_b", at.minusDays(8));

        if (supply7d.size() < 3) {
            // 降級：用 stablecoin_supply_change_pct_24h
            var change = historyRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                    SYM, "stablecoin_supply_change_pct_24h", at.minusDays(2));
            if (change.isEmpty()) return 0;
            double latestChange = change.get(change.size() - 1).getValue().doubleValue();
            if (latestChange > 1.0)  return 100;
            if (latestChange > 0.5)  return 60;
            if (latestChange > 0.1)  return 30;
            return 0;
        }

        // 計算 7 日累積增長
        double oldest = supply7d.get(0).getValue().doubleValue();
        double latest = supply7d.get(supply7d.size() - 1).getValue().doubleValue();
        if (oldest <= 0) return 0;
        double growth7d = (latest - oldest) / oldest * 100; // %

        if (growth7d > 2.0)  return 100;
        if (growth7d > 1.0)  return 70;
        if (growth7d > 0.3)  return 40;
        if (growth7d > 0)    return 20;
        return 0;
    }

    /** 信號 B：BTC 下跌 + 穩定幣上漲（背離 = 場內等待抄底）*/
    private double calcDivergenceScore(LocalDateTime at) {
        // BTC 24h 價格變化（從 kline）
        List<MdKline> klines = klineRepo.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                SYM, "1h", at.minusHours(26), at);
        if (klines.size() < 2) return 0;
        double priceNow  = klines.get(klines.size() - 1).getClosePrice().doubleValue();
        double price24h  = klines.get(0).getClosePrice().doubleValue();
        double btcChange = price24h > 0 ? (priceNow - price24h) / price24h : 0;

        // 穩定幣 24h 變化
        var stableNow  = historyRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(SYM, "stablecoin_total_mcap_b", at.minusHours(2));
        var stable24h  = historyRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(SYM, "stablecoin_total_mcap_b", at.minusHours(26));
        if (stableNow.isEmpty() || stable24h.isEmpty()) return 0;

        double stableChange = (stableNow.get(stableNow.size()-1).getValue().doubleValue()
                             - stable24h.get(0).getValue().doubleValue())
                             / stable24h.get(0).getValue().doubleValue();

        boolean btcDown   = btcChange < -0.01;   // BTC 跌 > 1%
        boolean stableUp  = stableChange > 0.003; // 穩幣漲 > 0.3%

        if (btcDown && stableChange > 0.01) return 100; // 強背離
        if (btcDown && stableUp)            return 70;  // 中等背離
        if (!btcDown && stableUp)           return 35;  // 穩幣漲但 BTC 也漲（資金充裕）
        return 0;
    }

    /** 信號 C：穩定幣供應相對 30 日均值的位置 */
    private double calcRelativeLevelScore(LocalDateTime at) {
        var history = historyRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                SYM, "stablecoin_total_mcap_b", at.minusDays(31));
        if (history.size() < 10) return 0;

        double current = history.get(history.size() - 1).getValue().doubleValue();
        OptionalDouble ma30 = history.stream()
                .mapToDouble(h -> h.getValue().doubleValue()).average();
        if (ma30.isEmpty() || ma30.getAsDouble() <= 0) return 0;

        double ratio = current / ma30.getAsDouble(); // > 1 = 高於均值
        if (ratio > 1.05) return 100;
        if (ratio > 1.02) return 60;
        if (ratio > 1.00) return 30;
        return 0;
    }

    @Override
    public String formatAlertMessage(CompositeResult r) {
        return String.format(
            "%s <b>穩定幣需求 SDI = %d</b> %s\n\n" +
            "📊 %s\n\n" +
            "穩幣供應增長：%.0f/40\nBTC/穩幣背離：%.0f/35\n存量相對高位：%.0f/25\n\n" +
            "→ 場內等待入市的資金充足，關注 SQI 擠倉信號",
            r.level().emoji, r.score(), r.level().label,
            r.formatDecomposed(getDimensions()),
            r.dimValues().getOrDefault("sdi_supply_growth", 0.0)  * 0.40,
            r.dimValues().getOrDefault("sdi_divergence", 0.0)     * 0.35,
            r.dimValues().getOrDefault("sdi_relative_level", 0.0) * 0.25);
    }
}
