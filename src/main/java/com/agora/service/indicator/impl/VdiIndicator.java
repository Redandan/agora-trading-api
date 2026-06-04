package com.agora.service.indicator.impl;

import com.agora.model.MdKline;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.indicator.CompositeIndicator;
import com.agora.service.indicator.CompositeResult;
import com.agora.service.indicator.IndicatorLevel;
import com.agora.service.indicator.SubDimension;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * VDI — BTC 價值偏離指數 (Value Deviation Index)。
 *
 * <p>用 MA200 近似 MVRV（市值/實現市值），衡量當前價格對歷史公允值的偏離程度：
 * <pre>
 *   pseudo_mvrv = close / MA200
 *   z_score     = (pseudo_mvrv − mean_365d) / std_365d
 *   score       = clamp((z_score + 3) / 6 × 100, 0, 100)
 * </pre>
 *
 * <p>三個子維度（合計權重 = 1.0）：
 * <ul>
 *   <li>vdi_zscore     (70%) — Z-Score 標準化估值偏離 → 0-100</li>
 *   <li>vdi_ma200_dist (20%) — 距 MA200 百分比 (-30%→0, 0%→50, +30%→100)</li>
 *   <li>vdi_trend      (10%) — MA50/MA200 趨勢強度 → 25-85</li>
 * </ul>
 *
 * <p>分級（關注高估值）：NORMAL 0-59 / ALERT 60-74 / WARNING 75-89 / CRITICAL 90+
 * <br>低分 (&lt;30) = 歷史低估區間，formatAlertMessage 特別標註。
 *
 * <p>完全使用現有 1d kline 數據，無需外部 API（替代 CoinMetrics 付費 API）。
 * <br>最少需要 {@value MIN_KLINES} 根日線；不足則回傳中性分數 50。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VdiIndicator implements CompositeIndicator {

    private final MdKlineRepository klineRepo;

    private static final String SYM          = "BTCUSDT";
    private static final String IVLC         = "1d";
    private static final String SOURCE       = "okx";   // OKX 1d 已 backfill 365 天
    private static final int    MA_LONG      = 200;
    private static final int    MA_SHORT     = 50;
    private static final int    ZSCORE_WIN   = 365;
    // 目前 OKX 1d backfill 約 364 根；MIN_KLINES 設 210 讓 MA200 可計算
    // Z-Score 若歷史不足 365 筆則用全部可用值（graceful degradation）
    private static final int    MIN_KLINES   = MA_LONG + 10;

    // ── 元數據 ────────────────────────────────────────────────────────────────

    @Override public String getName()        { return "vdi"; }
    @Override public String getDisplayName() { return "VDI 價值偏離指數"; }
    @Override public String getSymbol()      { return SYM; }

    @Override
    public List<SubDimension> getDimensions() {
        return List.of(
            new SubDimension("vdi_zscore",     "估值偏離(Z)", 0.70),
            new SubDimension("vdi_ma200_dist", "MA200 距離",  0.20),
            new SubDimension("vdi_trend",      "趨勢方向",    0.10)
        );
    }

    // ── 分級（高分 = 高估 = 潛在風險） ───────────────────────────────────────
    @Override public int getAlertThreshold()    { return 60; }
    @Override public int getWarningThreshold()  { return 75; }
    @Override public int getCriticalThreshold() { return 90; }

    // VDI 是絕對估值指標，不需要方向過濾或連續信號確認
    @Override public boolean isDirectionalFilterEnabled() { return false; }
    @Override public boolean isSustainedRequired()        { return false; }

    // 估值每日變動緩慢，每 6 小時最多告警一次
    @Override public int getCooldownMinutes() { return 360; }

    // VDI 是日線指標，backfill 須用獨立排程而非 ApplicationRunner，避免大量 JPQL
    // 啟動 Metaspace 耗盡。歷史數據由 Scheduler 每分鐘累積即可。
    @Override public int getBackfillDays() { return 0; }

    // ── 計算 ──────────────────────────────────────────────────────────────────

    @Override
    public CompositeResult calculate(LocalDateTime now) {
        return calcAt(now);
    }

    @Override
    public CompositeResult calculateHistorical(LocalDateTime at) {
        return calcAt(at);
    }

    private CompositeResult calcAt(LocalDateTime at) {
        // 使用 OKX 源以避免與 Binance 1d（不同 open time 基準）混用
        List<MdKline> klines = klineRepo.findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                SYM, IVLC, SOURCE, at.minusDays(MIN_KLINES + ZSCORE_WIN + 10), at);

        List<Double> closes = klines.stream()
                .filter(k -> k.getClosePrice() != null)
                .map(k -> k.getClosePrice().doubleValue())
                .collect(Collectors.toList());

        int n = closes.size();
        if (n < MA_LONG + 30) {
            log.debug("[VDI] 數據不足 available={} required={}", n, MA_LONG + 30);
            return neutralResult(at);
        }

        // 計算每個日期的 pseudo_mvrv（需要 i >= MA_LONG - 1）
        List<Double> mvrvSeries = new ArrayList<>(n - MA_LONG + 1);
        for (int i = MA_LONG - 1; i < n; i++) {
            double ma200 = closes.subList(i - MA_LONG + 1, i + 1)
                    .stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
            mvrvSeries.add(closes.get(i) / ma200);
        }

        if (mvrvSeries.isEmpty()) return neutralResult(at);

        // 當前各移動平均
        double currentClose = closes.get(n - 1);
        double ma200 = closes.subList(n - MA_LONG, n).stream()
                .mapToDouble(Double::doubleValue).average().orElse(1.0);
        double ma50 = n >= MA_SHORT
                ? closes.subList(n - MA_SHORT, n).stream()
                    .mapToDouble(Double::doubleValue).average().orElse(ma200)
                : ma200;
        double currentMvrv = mvrvSeries.get(mvrvSeries.size() - 1);

        // Z-Score：對最近 ZSCORE_WIN 個歷史 mvrv 值（不含當前點）計算
        int histEnd = mvrvSeries.size() - 1; // 排除當前點
        int histStart = Math.max(0, histEnd - ZSCORE_WIN);
        List<Double> window = mvrvSeries.subList(histStart, histEnd);
        double mean = window.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
        double variance = window.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .average().orElse(1e-9);
        double std = Math.max(Math.sqrt(variance), 1e-6);
        double zScore = (currentMvrv - mean) / std;

        // vdi_zscore: z ∈ [-3, +3] → 0-100（z=0 → 50，z=+3 → 100）
        double zNorm = Math.max(0.0, Math.min(100.0, (zScore + 3.0) / 6.0 * 100.0));

        // vdi_ma200_dist: ±30% 線性映射 → 0-100（0% = 50）
        double ma200PctDev = (currentClose - ma200) / ma200;
        double ma200Score  = Math.max(0.0, Math.min(100.0, (ma200PctDev + 0.30) / 0.60 * 100.0));

        // vdi_trend: MA50/MA200 黃金/死亡交叉強度 → 25-85
        double maCross = ma50 / ma200;
        double trendScore = maCross > 1.10 ? 85.0
                          : maCross > 1.05 ? 70.0
                          : maCross > 1.00 ? 55.0
                          : maCross > 0.95 ? 40.0
                          : 25.0;

        int score = (int) Math.min(
                zNorm * 0.70 + ma200Score * 0.20 + trendScore * 0.10, 100.0);
        IndicatorLevel level = getLevel(score);

        log.debug("[VDI] at={} close={} ma200={} mvrv={} z={} score={} level={}",
                at.toLocalDate(), (long) currentClose, (long) ma200,
                String.format("%.4f", currentMvrv),
                String.format("%+.2f", zScore), score, level);

        return new CompositeResult(
            score, level,
            Map.of("vdi_zscore", zNorm, "vdi_ma200_dist", ma200Score, "vdi_trend", trendScore),
            Map.of("zScore",     zScore,
                   "pseudoMvrv", currentMvrv,
                   "ma200",      ma200,
                   "ma200PctDev", ma200PctDev * 100.0,
                   "dataPoints", (double) mvrvSeries.size()),
            at, SYM
        );
    }

    // ── 告警格式 ──────────────────────────────────────────────────────────────

    @Override
    public String formatAlertMessage(CompositeResult r) {
        double zNorm      = r.dimValues().getOrDefault("vdi_zscore", 50.0);
        double ma200Dist  = r.dimValues().getOrDefault("vdi_ma200_dist", 50.0);
        double trend      = r.dimValues().getOrDefault("vdi_trend", 50.0);
        double zScore     = toDouble(r.context().getOrDefault("zScore", 0.0));
        double mvrv       = toDouble(r.context().getOrDefault("pseudoMvrv", 1.0));
        double ma200PctDev = toDouble(r.context().getOrDefault("ma200PctDev", 0.0));

        boolean isUndervalued = r.score() < 30;
        String header = isUndervalued
                ? "🔵 <b>VDI 歷史性低估</b>（可能長線布局機會）"
                : String.format("%s <b>VDI 價值偏離 = %d</b>（高估警告）",
                    r.level().emoji, r.score());

        String advice = r.score() >= getCriticalThreshold()
                ? "🚨 極端高估（z > +2σ），歷史上此區間常見 20-40% 回調，考慮縮倉"
                : r.score() >= getWarningThreshold()
                    ? "⚠️ 顯著高估（z > +1σ），參考 MA200 壓力位考慮減倉或設緊止盈"
                    : isUndervalued
                        ? "💡 估值低於歷史均值 2σ，歷史勝率較高，可分批建倉"
                        : "";

        return String.format(
            "%s\n\n" +
            "📊 估值偏離(Z)：%.0f  MA200距離：%.0f  趨勢：%.0f\n\n" +
            "Pseudo-MVRV：%.4f\n" +
            "距 MA200：%+.1f%%\n" +
            "Z-Score：%+.2f\n\n" +
            "%s",
            header,
            zNorm * 0.70, ma200Dist * 0.20, trend * 0.10,
            mvrv, ma200PctDev, zScore,
            advice
        );
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private CompositeResult neutralResult(LocalDateTime at) {
        return new CompositeResult(
            50, IndicatorLevel.NORMAL,
            Map.of("vdi_zscore", 50.0, "vdi_ma200_dist", 50.0, "vdi_trend", 50.0),
            Map.of("dataInsufficient", 1.0),
            at, SYM
        );
    }

    private static double toDouble(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : 0.0;
    }
}
