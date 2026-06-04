package com.agora.service.indicator.impl;

import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.service.indicator.CompositeIndicator;
import com.agora.service.indicator.CompositeResult;
import com.agora.service.indicator.IndicatorLevel;
import com.agora.service.indicator.SubDimension;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * short_build_index — 空頭燃料積累前置指標。
 *
 * 三個子維度（權重 40/30/30）：
 *   sbi_oi_divergence   OI 上升但價格不漲（逆勢空頭建倉）
 *   sbi_lsr             多空比偏空（牛市低閾 0.82）
 *   sbi_funding         資金費率近零或轉負
 *
 * 特性：牛市中恒為 0（正確行為），熊市/調整期才有效。
 * 分級：NORMAL 0-29 / ALERT 30-49 / WARNING 50-74 / CRITICAL 75+
 * 回測：58 天（2026-03-03→04-30）全為 0，原因：強勢牛市無空頭燃料積累。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShortBuildIndicator implements CompositeIndicator {

    private final MarketIndicatorHistoryRepository historyRepo;

    private static final String SYM = "BTCUSDT";

    // ── 元數據 ────────────────────────────────────────────────────────────────

    @Override public String getName()        { return "short_build_index"; }
    @Override public String getDisplayName() { return "空頭燃料積累指數"; }
    @Override public String getSymbol()      { return SYM; }

    @Override
    public List<SubDimension> getDimensions() {
        return List.of(
            new SubDimension("sbi_oi_divergence", "OI/價格背離", 0.40),
            new SubDimension("sbi_lsr",           "多空比偏空",  0.30),
            new SubDimension("sbi_funding",       "費率轉負",    0.30)
        );
    }

    // ── 分級（SBI 在熊市才有意義，Warning 閾值較高）────────────────────────
    @Override public int getAlertThreshold()    { return 30; }
    @Override public int getWarningThreshold()  { return 50; }
    @Override public int getCriticalThreshold() { return 75; }

    // 牛市中 SBI 恒為 0，不需連續信號和方向過濾
    @Override public boolean isSustainedRequired()        { return false; }
    @Override public boolean isDirectionalFilterEnabled() { return false; }

    // ── 計算 ──────────────────────────────────────────────────────────────────

    @Override
    public CompositeResult calculate(LocalDateTime now) {
        double oiChangePct = getLatest("oi_change_pct_1h", now);
        double lsr         = getLatest("long_short_ratio", now);
        double fundingRate = getLatest("funding_rate", now);
        double priceChange = calcPriceChange1h(now);
        double shortPct    = lsr > 0 ? 1.0 / (1.0 + lsr) : 0.5;  // lsr 是 L:S 比值

        // 信號 A：OI 上升但價格不漲（逆勢空頭建倉）
        double oiDivScore = (oiChangePct > 1.0 && priceChange < 0.002) ? 40
                          : (oiChangePct > 0.5 && priceChange < 0) ? 25 : 0;
        // 信號 B：多空比偏空
        double lsrScore = shortPct > 0.55 ? 30 : shortPct > 0.50 ? 15 : 0;
        // 信號 C：資金費率近零或轉負
        double fundingScore = fundingRate < 0.00001 ? 30 : fundingRate < 0.0001 ? 15 : 0;

        int score = (int) Math.min(oiDivScore + lsrScore + fundingScore, 100);
        IndicatorLevel level = getLevel(score);

        log.debug("[SBI] score={} oiDiv={} lsr={} funding={}", score, oiDivScore, lsrScore, fundingScore);

        return new CompositeResult(
            score, level,
            Map.of("sbi_oi_divergence", oiDivScore,
                   "sbi_lsr",           lsrScore,
                   "sbi_funding",        fundingScore),
            Map.of("oiChangePct", oiChangePct, "priceChange1h", priceChange,
                   "lsr", lsr, "fundingRate", fundingRate),
            now, SYM
        );
    }

    // ── 告警格式 ──────────────────────────────────────────────────────────────

    @Override
    public String formatAlertMessage(CompositeResult r) {
        // #375 — 移除 formatDecomposed line，避免與 SBI 絕對值分數衝突
        // (formatDecomposed 假設 val=0-100 套 weight，但 SBI raw score 已是絕對 0-30/0-40)
        return String.format(
            "%s <b>空頭燃料積累 SBI = %d</b> %s\n\n" +
            "OI/價格背離：%.0f/40\n多空比偏空：%.0f/30\n費率轉負：%.0f/30\n\n" +
            "→ 後續關注 SQI 是否跟進上升（擠倉前置信號）",
            r.level().emoji, r.score(), r.level().label,
            r.dimValues().getOrDefault("sbi_oi_divergence", 0.0),
            r.dimValues().getOrDefault("sbi_lsr", 0.0),
            r.dimValues().getOrDefault("sbi_funding", 0.0));
    }

    /**
     * 歷史版本：使用時間約束查詢，不依賴 live 數據。
     * #315 修正：getLatest() 無時間約束會返回當前最新值，歷史計算不正確。
     */
    @Override
    public CompositeResult calculateHistorical(LocalDateTime at) {
        double oiChangePct = getAtTime("oi_change_pct_1h", at);
        double lsr         = getAtTime("long_short_ratio", at);
        double fundingRate = getAtTime("funding_rate", at);
        double shortPct    = lsr > 0 ? 1.0 / (1.0 + lsr) : 0.5;

        // 使用 1h klines 計算價格變化（比 kraken_btc_usd_price 歷史更完整）
        double priceChange = calcPriceChangeAtTime(at);

        double oiDivScore   = (oiChangePct > 1.0 && priceChange < 0.002) ? 40
                            : (oiChangePct > 0.5 && priceChange < 0) ? 25 : 0;
        double lsrScore     = shortPct > 0.55 ? 30 : shortPct > 0.50 ? 15 : 0;
        double fundingScore = fundingRate < 0.00001 ? 30 : fundingRate < 0.0001 ? 15 : 0;

        int score = (int) Math.min(oiDivScore + lsrScore + fundingScore, 100);
        return new CompositeResult(
            score, getLevel(score),
            Map.of("sbi_oi_divergence", oiDivScore, "sbi_lsr", lsrScore, "sbi_funding", fundingScore),
            Map.of("oiChangePct", oiChangePct, "lsr", lsr, "fundingRate", fundingRate),
            at, SYM
        );
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private double getLatest(String indicator, LocalDateTime now) {
        return historyRepo.findTopCleanBySymbolAndIndicator(SYM, indicator)
                .map(h -> h.getValue().doubleValue()).orElse(0.0);
    }

    /** 歷史計算：取指定時間點前最近一筆。 */
    private double getAtTime(String indicator, LocalDateTime at) {
        return historyRepo.findTopCleanBySymbolAndIndicatorAndCapturedAtLessThanEqual(
                        SYM, indicator, at)
                .map(h -> h.getValue().doubleValue()).orElse(0.0);
    }

    private double calcPriceChange1h(LocalDateTime now) {
        var prices = historyRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                SYM, "kraken_btc_usd_price", now.minusHours(2));
        if (prices.size() < 2) return 0;
        double latest = prices.get(prices.size() - 1).getValue().doubleValue();
        double older  = prices.get(0).getValue().doubleValue();
        return older > 0 ? (latest - older) / older : 0;
    }

    private double calcPriceChangeAtTime(LocalDateTime at) {
        var prices = historyRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                SYM, "kraken_btc_usd_price", at.minusHours(2))
                .stream().filter(h -> !h.getCapturedAt().isAfter(at)).toList();
        if (prices.size() < 2) return 0;
        double latest = prices.get(prices.size() - 1).getValue().doubleValue();
        double older  = prices.get(0).getValue().doubleValue();
        return older > 0 ? (latest - older) / older : 0;
    }
}
