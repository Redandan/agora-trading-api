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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 市場熵值指數（Market Entropy Index）— CMI Framework 實作。
 *
 * <p>借用資訊理論的 Shannon 熵概念，衡量 BTC 1h 價格行為的混亂程度：
 * <ul>
 *   <li><b>高熵（>70）</b>：市場極度混亂，缺乏共識 → 大趨勢即將爆發</li>
 *   <li><b>低熵（<30）</b>：有序趨勢進行中或穩定積累</li>
 * </ul>
 *
 * <p>三個時間視窗，加權合成（短期反應快，長期更穩定）：
 * <ul>
 *   <li>24h 視窗（40%）：短期混亂度，靈敏</li>
 *   <li>48h 視窗（35%）：中期混亂度</li>
 *   <li>72h 視窗（25%）：長期混亂度，穩定</li>
 * </ul>
 *
 * <p>與 SQI 互補：SQI 捕捉「壓緊彈簧的釋放瞬間」，熵值感知「市場結構變化的前兆」。
 *
 * <p>分級：NORMAL 0-29 / ALERT 30-49 / WARNING 50-74 / CRITICAL 75+
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketEntropyIndicator implements CompositeIndicator {

    private final MdKlineRepository klineRepo;

    private static final String SYM   = "BTCUSDT";
    private static final String IVLC  = "1h";
    private static final int    BINS  = 20; // 收益率分桶數

    // ── 元數據 ────────────────────────────────────────────────────────────────

    @Override public String getName()        { return "market_entropy_index"; }
    @Override public String getDisplayName() { return "市場熵值指數"; }
    @Override public String getSymbol()      { return SYM; }

    @Override
    public List<SubDimension> getDimensions() {
        return List.of(
            new SubDimension("mei_24h", "24h 混亂度", 0.40),
            new SubDimension("mei_48h", "48h 混亂度", 0.35),
            new SubDimension("mei_72h", "72h 混亂度", 0.25)
        );
    }

    // ── 分級 ──────────────────────────────────────────────────────────────────
    @Override public int getAlertThreshold()    { return 30; }
    @Override public int getWarningThreshold()  { return 50; }
    @Override public int getCriticalThreshold() { return 75; }

    // 熵值不需方向過濾（高熵可以是漲也可以是跌前兆）
    @Override public boolean isDirectionalFilterEnabled() { return false; }

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
        // 載入 72h + 2h 緩衝的 K 線
        List<MdKline> klines = klineRepo.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                SYM, IVLC, at.minusHours(76), at);

        double entropy24 = calcEntropy(klines, at, 24);
        double entropy48 = calcEntropy(klines, at, 48);
        double entropy72 = calcEntropy(klines, at, 72);

        int score = (int) Math.min(
                entropy24 * 0.40 + entropy48 * 0.35 + entropy72 * 0.25, 100);
        IndicatorLevel level = getLevel(score);

        log.debug("[MEI] at={} e24={} e48={} e72={} score={}",
                at, entropy24, entropy48, entropy72, score);

        return new CompositeResult(
            score, level,
            Map.of("mei_24h", entropy24, "mei_48h", entropy48, "mei_72h", entropy72),
            Map.of("klineCount", (double) klines.size()),
            at, SYM
        );
    }

    // ── Shannon 熵計算 ────────────────────────────────────────────────────────

    /**
     * 計算過去 windowHours 根 1h K 線的收益率 Shannon 熵，映射到 0-100。
     *
     * <p>算法：
     * <ol>
     *   <li>取最近 windowHours 根 K 線的收盤價</li>
     *   <li>計算相鄰 K 線的百分比收益率：r[i] = (c[i] - c[i-1]) / c[i-1]</li>
     *   <li>將收益率分入 BINS 個等寬桶（以歷史最大波動為邊界）</li>
     *   <li>H = -Σ p(b) × log₂(p(b))，標準化：H_norm = H / log₂(BINS)</li>
     *   <li>映射到 0-100：entropy_score = H_norm × 100</li>
     * </ol>
     */
    private double calcEntropy(List<MdKline> klines, LocalDateTime at, int windowHours) {
        // 篩選視窗內的 K 線
        LocalDateTime windowStart = at.minusHours(windowHours);
        List<Double> closes = new ArrayList<>();
        for (MdKline k : klines) {
            if (!k.getOpenTime().isBefore(windowStart) && !k.getOpenTime().isAfter(at)
                    && k.getClosePrice() != null) {
                closes.add(k.getClosePrice().doubleValue());
            }
        }

        if (closes.size() < 4) return 0; // 數據不足

        // 計算收益率序列
        double[] returns = new double[closes.size() - 1];
        for (int i = 1; i < closes.size(); i++) {
            double prev = closes.get(i - 1);
            returns[i - 1] = prev > 0 ? (closes.get(i) - prev) / prev : 0;
        }

        // 動態分桶邊界（±3σ 覆蓋大部分波動）
        double mean = Arrays.stream(returns).average().orElse(0);
        double std  = Math.sqrt(Arrays.stream(returns).map(r -> (r - mean) * (r - mean))
                .average().orElse(1e-9));
        double lo = mean - 3 * std;
        double hi = mean + 3 * std;
        if (hi - lo < 1e-9) return 0;

        // 分桶計數
        int[] counts = new int[BINS];
        double binWidth = (hi - lo) / BINS;
        for (double r : returns) {
            int bin = (int) Math.floor((r - lo) / binWidth);
            bin = Math.max(0, Math.min(BINS - 1, bin));
            counts[bin]++;
        }

        // Shannon 熵（以 BINS 為基底標準化到 0-1）
        double H = 0;
        int total = returns.length;
        for (int cnt : counts) {
            if (cnt > 0) {
                double p = (double) cnt / total;
                H -= p * Math.log(p);
            }
        }
        double maxH = Math.log(BINS); // 最大熵
        return maxH > 0 ? Math.min(H / maxH * 100, 100) : 0;
    }

    // ── 告警格式 ──────────────────────────────────────────────────────────────

    @Override
    public String formatAlertMessage(CompositeResult r) {
        double e24 = r.dimValues().getOrDefault("mei_24h", 0.0);
        double e48 = r.dimValues().getOrDefault("mei_48h", 0.0);
        double e72 = r.dimValues().getOrDefault("mei_72h", 0.0);
        return String.format(
            "%s <b>市場熵值 MEI = %d</b> %s\n\n" +
            "📊 24h 混亂度：%.0f  48h：%.0f  72h：%.0f\n\n" +
            "熵值偏高 = 市場缺乏共識，大行情（漲或跌）蓄勢待發\n" +
            "建議結合 SQI（擠倉方向）確認趨勢",
            r.level().emoji, r.score(), r.level().label,
            e24, e48, e72);
    }
}
