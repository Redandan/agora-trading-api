package com.agora.config;

import com.agora.model.MarketIndicatorHistory;
import com.agora.model.MdKline;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.repository.trading.MdKlineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * 啟動時回填 SQI 歷史數據（含 K 線 price_confirmation + 方案 B 對比）。
 *
 * 數據源：
 *   funding_rate + long_short_ratio → sqi_short_crowding（~30 天）
 *   btc_short_liq_usd_1h           → sqi_liquidation_anomaly（~58 天）
 *   md_kline 1h close price        → sqi_price_confirmation（90 天+）
 *
 * 延伸至 58 天（closes #298）：
 *   - funding_rate 缺失的時間點：crowding = 0，SQI 僅 liqAnomaly + priceConf
 *   - 可找到更多歷史擠倉事件
 *
 * 方案 B 對比（closes #296）：
 *   同步計算 sqi_b（crowding×30% + liqAnomaly×50% + price×20%）
 *   寫入 sqi_b / sqi_b_short_crowding / sqi_b_liq_anomaly
 */
@Slf4j
// @Component  // #304 停用：SQI backfill 已由 CompositeIndicatorBackfillRunner 統一管理
//             // 保留此文件備查，但不再作為 Spring bean 注冊（不注冊 = 不執行）
@RequiredArgsConstructor
@AsyncStartup("SQI backfill — CompletableFuture.runAsync (#361, currently disabled)")
public class SqiBackfillRunner implements ApplicationRunner {

    private final MarketIndicatorHistoryRepository historyRepo;
    private final MdKlineRepository               klineRepo;

    private static final String SYM           = "BTCUSDT";
    private static final int    BACKFILL_DAYS = 58; // #298：延長至 Coinalyze 爆倉數據最大範圍

    private static final List<String> SQI_INDICATORS = List.of(
            "sqi", "sqi_short_crowding", "sqi_liquidation_anomaly", "sqi_price_confirmation",
            "sqi_b", "sqi_b_short_crowding", "sqi_b_liq_anomaly",
            "short_build_index");  // #297

    @Override
    public void run(ApplicationArguments args) {
        // 非同步執行，避免阻塞 Spring Boot ReadinessState（同 CompositeIndicatorBackfillRunner）
        CompletableFuture.runAsync(() -> {
            LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(BACKFILL_DAYS);

            boolean needsRebuild = shouldRebuild(since);
            if (!needsRebuild) {
                long existing = historyRepo
                        .findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(SYM, "sqi", since)
                        .size();
                if (existing >= 400) {
                    log.info("[SqiBackfill] {} rows, coverage ok, skipping", existing);
                    return;
                }
            }

            if (needsRebuild) {
                log.info("[SqiBackfill] Rebuilding (old data detected)...");
                LocalDateTime clearSince = LocalDateTime.now(ZoneOffset.UTC).minusDays(BACKFILL_DAYS + 2);
                for (String ind : SQI_INDICATORS) {
                    historyRepo.deleteBySymbolAndIndicatorAfter(SYM, ind, clearSince);
                }
            }

            log.info("[SqiBackfill] Starting {}-day backfill (A + B weights)", BACKFILL_DAYS);
            long written = backfill(since);
            log.info("[SqiBackfill] Done — written {} rows", written);
        });
    }

    private boolean shouldRebuild(LocalDateTime since) {
        var priceConfRows = historyRepo
                .findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(
                        SYM, "sqi_price_confirmation", since);
        // 條件 1：舊版 backfill（price_confirmation 全為 0）
        if (priceConfRows.size() >= 10 &&
                priceConfRows.stream().allMatch(h -> h.getValue().compareTo(BigDecimal.ZERO) == 0))
            return true;
        // 條件 2：覆蓋範圍不足（沒到 55 天）
        if (priceConfRows.size() < 350) return true;
        // 條件 3：sqi_b 不存在（方案 B 尚未計算）
        var sqiBRows = historyRepo
                .findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(
                        SYM, "sqi_b", since);
        if (sqiBRows.size() < 10) return true;
        // 條件 4：short_build_index 不存在（#297 新指標）
        var sbiRows = historyRepo
                .findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(
                        SYM, "short_build_index", since);
        if (sbiRows.size() < 10) return true;
        return false;
    }

    private long backfill(LocalDateTime since) {
        // ── 1. 載入指標數據 ──
        // liq：從 BACKFILL_DAYS+5 天前開始，確保 95 分位計算夠準
        TreeMap<LocalDateTime, Double> liqMap     = loadMap("btc_short_liq_usd_1h", since.minusDays(5));
        TreeMap<LocalDateTime, Double> fundingMap = loadMap("funding_rate",        since.minusDays(2));
        TreeMap<LocalDateTime, Double> lsrMap     = loadMap("long_short_ratio",    since.minusDays(2));
        TreeMap<LocalDateTime, Double> oiMap      = loadMap("oi_change_pct_1h",    since.minusDays(2)); // #297
        TreeMap<LocalDateTime, Double> closeMap   = loadKlineCloseMap(since.minusDays(2));

        log.info("[SqiBackfill] liq={} funding={} lsr={} kline={}",
                liqMap.size(), fundingMap.size(), lsrMap.size(), closeMap.size());

        if (liqMap.isEmpty()) {
            log.warn("[SqiBackfill] No liq data, aborting");
            return 0;
        }

        // ── 2. 爆倉 95 分位（用全部 liq 數據）──
        List<Double> liqValues = new ArrayList<>(liqMap.values());
        Collections.sort(liqValues);
        double p95 = liqValues.isEmpty() ? 20_000_000
                   : liqValues.get((int) Math.min(liqValues.size() * 0.95, liqValues.size() - 1));
        log.info("[SqiBackfill] liq p95 = ${}M", String.format("%.1f", p95 / 1e6));

        // ── 3. 以 liq 時間戳為骨架（覆蓋 58 天）──
        long written = 0;
        for (LocalDateTime t : liqMap.keySet()) {
            if (t.isBefore(since)) continue;

            double liq       = liqMap.get(t);
            double funding   = nearestValue(fundingMap, t, 3);  // 3h 容差，超出 30 天範圍時為 0
            double lsr       = nearestValue(lsrMap,     t, 3);
            double priceConf = calcPriceConfirmation(closeMap, t);

            double crowding   = calcShortCrowding(funding, lsr);
            double liqAnomaly = calcLiqAnomaly(liq, p95);
            double oiChangePct = nearestValue(oiMap, t, 2);

            // ── 方案 A（40/40/20）──
            double sqiA = Math.min(crowding * 0.40 + liqAnomaly * 0.40 + priceConf * 0.20, 100);

            // ── 方案 B（30/50/20）：爆倉優先 ──
            double sqiB = Math.min(crowding * 0.30 + liqAnomaly * 0.50 + priceConf * 0.20, 100);

            // ── short_build_index（#297）：空頭燃料積累前置指標 ──
            double priceChange1h = calcPriceChange1h(closeMap, t);
            double sbi = calcShortBuildIndex(oiChangePct, priceChange1h, lsr, funding);

            written += saveIfAbsent("sqi",                      sqiA,       t);
            written += saveIfAbsent("sqi_short_crowding",       crowding,   t);
            written += saveIfAbsent("sqi_liquidation_anomaly",  liqAnomaly, t);
            written += saveIfAbsent("sqi_price_confirmation",   priceConf,  t);
            written += saveIfAbsent("sqi_b",                    sqiB,       t);
            written += saveIfAbsent("sqi_b_short_crowding",     crowding,   t);
            written += saveIfAbsent("sqi_b_liq_anomaly",        liqAnomaly, t);
            written += saveIfAbsent("short_build_index",        sbi,        t);
        }
        return written;
    }

    // ── 子指標計算 ────────────────────────────────────────────────────────────

    private double calcShortCrowding(double fundingRate, double lsr) {
        double fundingScore = fundingRate < -0.005 ? 50 : fundingRate < -0.001 ? 30 : 0;
        double shortPct     = lsr > 0 ? 1.0 / (1.0 + lsr) : 0.5;  // lsr 是 L:S 比值
        double lsrScore     = shortPct > 0.70 ? 50 : shortPct > 0.60 ? 25 : 0;
        return Math.min(fundingScore + lsrScore, 100);
    }

    private double calcLiqAnomaly(double liq, double p95) {
        if (p95 <= 0 || liq <= 0) return 0;
        double ratio = liq / p95;
        if (ratio > 3.0) return 100;
        if (ratio > 2.0) return 75;
        if (ratio > 1.5) return 50;
        if (ratio > 1.0) return 30;
        return 0;
    }

    /**
     * short_build_index (0-100)：空頭燃料積累前置指標（#297）
     * 信號 A (40分)：OI 上升但價格不漲 → 逆勢空頭在建倉
     * 信號 B (30分)：多空比偏向空頭（牛市低閾 0.82）
     * 信號 C (30分)：資金費率近零或轉負 → 空頭開始主導
     */
    private double calcShortBuildIndex(double oiChangePct, double priceChange1h,
                                       double lsr, double fundingRate) {
        double oiDivScore = 0;
        if (oiChangePct > 1.0 && priceChange1h < 0.002)  oiDivScore = 40;
        else if (oiChangePct > 0.5 && priceChange1h < 0) oiDivScore = 25;

        double shortPct = lsr > 0 ? 1 - lsr : 0;
        double lsrScore = shortPct > 0.55 ? 30 : shortPct > 0.50 ? 15 : 0;

        double fundingScore = fundingRate < 0.00001 ? 30 : fundingRate < 0.0001 ? 15 : 0;

        return Math.min(oiDivScore + lsrScore + fundingScore, 100);
    }

    /** 1h 價格變化（short_build_index 判斷方向用）*/
    private double calcPriceChange1h(TreeMap<LocalDateTime, Double> closeMap, LocalDateTime t) {
        var current  = closeMap.floorEntry(t);
        var previous = closeMap.floorEntry(t.minusHours(1));
        if (current == null || previous == null || previous.getValue() <= 0) return 0;
        return (current.getValue() - previous.getValue()) / previous.getValue();
    }

    private double calcPriceConfirmation(TreeMap<LocalDateTime, Double> closeMap, LocalDateTime t) {
        var current  = closeMap.floorEntry(t);
        var previous = closeMap.floorEntry(t.minusHours(1));
        if (current == null || previous == null || previous.getValue() <= 0) return 0;
        double change = (current.getValue() - previous.getValue()) / previous.getValue();
        if (change > 0.010) return 100;
        if (change > 0.005) return 60;
        if (change > 0.002) return 30;
        if (change > 0)     return 15;
        return 0;
    }

    // ── 資料載入 ──────────────────────────────────────────────────────────────

    private TreeMap<LocalDateTime, Double> loadMap(String indicator, LocalDateTime since) {
        TreeMap<LocalDateTime, Double> map = new TreeMap<>();
        historyRepo.findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(SYM, indicator, since)
                .forEach(h -> { if (h.getValue() != null) map.put(h.getCapturedAt(), h.getValue().doubleValue()); });
        return map;
    }

    private TreeMap<LocalDateTime, Double> loadKlineCloseMap(LocalDateTime since) {
        TreeMap<LocalDateTime, Double> map = new TreeMap<>();
        klineRepo.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                SYM, "1h", since, LocalDateTime.now(ZoneOffset.UTC))
                .forEach(k -> { if (k.getClosePrice() != null) map.put(k.getOpenTime(), k.getClosePrice().doubleValue()); });
        return map;
    }

    private double nearestValue(TreeMap<LocalDateTime, Double> map, LocalDateTime target, int maxHours) {
        if (map.isEmpty()) return 0;
        var floor   = map.floorEntry(target);
        var ceiling = map.ceilingEntry(target);
        LocalDateTime best = null;
        if (floor   != null && floor.getKey().isAfter(target.minusHours(maxHours)))   best = floor.getKey();
        if (ceiling != null && ceiling.getKey().isBefore(target.plusHours(maxHours))) {
            if (best == null || java.time.Duration.between(ceiling.getKey(), target).abs()
                    .compareTo(java.time.Duration.between(best, target).abs()) < 0) best = ceiling.getKey();
        }
        return best != null ? map.get(best) : 0;
    }

    private int saveIfAbsent(String indicator, double value, LocalDateTime capturedAt) {
        if (historyRepo.existsBySymbolAndIndicatorAndCapturedAt(SYM, indicator, capturedAt)) return 0;
        try {
            MarketIndicatorHistory row = new MarketIndicatorHistory();
            row.setSymbol(SYM);
            row.setIndicator(indicator);
            row.setValue(BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP));
            row.setCapturedAt(capturedAt);
            historyRepo.save(row);
            return 1;
        } catch (Exception e) {
            log.warn("[SqiBackfill] save failed {}/{}: {}", indicator, capturedAt, e.getMessage());
            return 0;
        }
    }
}
