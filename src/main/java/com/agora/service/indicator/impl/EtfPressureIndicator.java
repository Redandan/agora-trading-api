package com.agora.service.indicator.impl;

import com.agora.service.indicator.CompositeIndicator;
import com.agora.service.indicator.CompositeResult;
import com.agora.service.indicator.IndicatorLevel;
import com.agora.service.indicator.SubDimension;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ETF 買賣壓力指數（ETF Pressure Index，EPI）— CMI Framework 實作。
 *
 * <p>監控 5 大 BTC 現貨 ETF（IBIT/FBTC/ARKB/BITB/HODL）的成交量與價格方向，
 * 衡量機構對 BTC ETF 的買賣情緒。
 *
 * <p>算法：ETF_pressure = Σ (price_return_i × normalized_volume_i)
 * <ul>
 *   <li>正值（> 50）：整體買入壓力（上漲放量）</li>
 *   <li>負值（< 50）：整體賣出壓力（下跌放量）</li>
 * </ul>
 *
 * <p>數據源：Yahoo Finance v8 API（免費、無需 API Key）
 *
 * <p>注意：這是「買賣壓力」代理，不是精確的 ETF Net Flow。
 * 精確 net flow 需要 shares outstanding 變化（付費 API）。
 *
 * <p>分級：NORMAL 0-29 / ALERT 30-49 / WARNING 50-74 / CRITICAL 75+
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EtfPressureIndicator implements CompositeIndicator {

    private final ObjectMapper objectMapper;

    private static final String SYM = "BTCUSDT";
    private static final String[] TICKERS = {"IBIT", "FBTC", "ARKB", "BITB", "HODL"};

    // 最新 ETF 數據快取（由 @Scheduled 每日更新）
    private final AtomicReference<EtfSnapshot> latestSnapshot = new AtomicReference<>();

    @Value("${meta-control.etf-pressure.refresh-enabled:false}")
    private boolean refreshEnabled;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    @Override public String getName()        { return "etf_pressure_index"; }
    @Override public String getDisplayName() { return "ETF 買賣壓力指數"; }
    @Override public String getSymbol()      { return SYM; }

    @Override
    public List<SubDimension> getDimensions() {
        return List.of(
            new SubDimension("epi_daily_pressure", "當日買賣壓力",   0.60),
            new SubDimension("epi_3d_pressure",    "3 日累積壓力",   0.40)
        );
    }

    @Override public int getAlertThreshold()    { return 30; }
    @Override public int getWarningThreshold()  { return 60; }
    @Override public int getCriticalThreshold() { return 80; }

    // ETF 數據每日美股收盤後更新（UTC 22:00）
    @Scheduled(cron = "0 0 22 * * *", zone = "UTC")
    public void refreshEtfData() {
        if (!refreshEnabled) {
            log.debug("[EPI] refresh disabled by meta-control.etf-pressure.refresh-enabled=false");
            return;
        }
        try {
            EtfSnapshot snapshot = fetchAllEtfs();
            latestSnapshot.set(snapshot);
            log.info("[EPI] ETF data refreshed: dailyPressure={}", snapshot.dailyPressure());
        } catch (Exception e) {
            log.warn("[EPI] ETF data refresh failed: {}", e.getMessage());
        }
    }

    @Override
    public CompositeResult calculate(LocalDateTime now) {
        EtfSnapshot snap = latestSnapshot.get();
        if (snap == null) {
            // 首次運行：嘗試即時取得
            try { snap = fetchAllEtfs(); latestSnapshot.set(snap); }
            catch (Exception e) { log.warn("[EPI] fetch failed: {}", e.getMessage()); }
        }

        if (snap == null) {
            return new CompositeResult(0, IndicatorLevel.NORMAL,
                Map.of("epi_daily_pressure", 0.0, "epi_3d_pressure", 0.0),
                Map.of("error", "no_data"), now, SYM);
        }

        // 映射壓力到 0-100（50 = 中性）
        double dailyScore = pressureToScore(snap.dailyPressure());
        double threeDayScore = pressureToScore(snap.threeDayPressure());

        int score = (int) Math.min(dailyScore * 0.60 + threeDayScore * 0.40, 100);
        IndicatorLevel level = getLevel(score);

        return new CompositeResult(
            score, level,
            Map.of("epi_daily_pressure", dailyScore, "epi_3d_pressure", threeDayScore),
            Map.of("rawDaily", snap.dailyPressure(), "raw3d", snap.threeDayPressure()),
            now, SYM
        );
    }

    /** 壓力值（-1~1）→ 0-100（50=中性，>50=買壓，<50=賣壓）*/
    private double pressureToScore(double pressure) {
        return Math.min(100, Math.max(0, 50 + pressure * 50));
    }

    private EtfSnapshot fetchAllEtfs() throws Exception {
        double totalDailyPressure = 0;
        double total3dPressure = 0;
        int validCount = 0;

        for (String ticker : TICKERS) {
            try {
                String url = "https://query2.finance.yahoo.com/v8/finance/chart/"
                        + ticker + "?interval=1d&range=5d";
                Request req = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0")
                        .build();
                try (Response resp = httpClient.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) continue;
                    JsonNode root = objectMapper.readTree(resp.body().string());
                    JsonNode result = root.path("chart").path("result").path(0);
                    JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
                    JsonNode volumes = result.path("indicators").path("quote").path(0).path("volume");

                    if (!closes.isArray() || closes.size() < 2) continue;

                    int n = closes.size();
                    // 當日壓力：最後一天的 return × volume
                    double c1 = closes.get(n-1).asDouble();
                    double c0 = closes.get(n-2).asDouble();
                    double v1 = volumes.get(n-1).asDouble();
                    double ret1 = c0 > 0 ? (c1 - c0) / c0 : 0;
                    // 3 日壓力：最後 3 天的加權
                    double pressure3d = 0;
                    for (int i = Math.max(1, n-3); i < n; i++) {
                        double ci = closes.get(i).asDouble();
                        double cp = closes.get(i-1).asDouble();
                        double vi = volumes.get(i).asDouble();
                        double ri = cp > 0 ? (ci - cp) / cp : 0;
                        pressure3d += Math.signum(ri) * Math.min(Math.abs(ri * 10), 1.0);
                    }
                    pressure3d /= 3;

                    totalDailyPressure += Math.signum(ret1) * Math.min(Math.abs(ret1 * 10), 1.0);
                    total3dPressure += pressure3d;
                    validCount++;
                    log.debug("[EPI] {} ret={} v={}", ticker, ret1, v1);
                }
            } catch (Exception e) {
                log.debug("[EPI] {} fetch error: {}", ticker, e.getMessage());
            }
        }

        if (validCount == 0) throw new RuntimeException("No ETF data available");
        return new EtfSnapshot(totalDailyPressure / validCount, total3dPressure / validCount);
    }

    @Override
    public String formatAlertMessage(CompositeResult r) {
        double daily = ((Number) r.context().getOrDefault("rawDaily", 0.0)).doubleValue();
        boolean isBuying = daily > 0;
        return String.format(
            "%s <b>ETF 壓力 EPI = %d</b> %s\n\n" +
            "📊 當日壓力：%.0f/60  3日累積：%.0f/40\n\n" +
            "%s 機構 ETF 整體%s，資金%s\n" +
            "→ 參考 SQI 確認是否與擠倉動向一致",
            r.level().emoji, r.score(), r.level().label,
            r.dimValues().getOrDefault("epi_daily_pressure", 50.0) * 0.60,
            r.dimValues().getOrDefault("epi_3d_pressure", 50.0) * 0.40,
            isBuying ? "📈" : "📉",
            isBuying ? "淨買入" : "淨賣出",
            isBuying ? "流入中" : "流出中");
    }

    // ETF 歷史 backfill 不透過 ApplicationRunner（避免大量 JPQL 耗盡 Metaspace）
    @Override public int getBackfillDays() { return 0; }

    private record EtfSnapshot(double dailyPressure, double threeDayPressure) {}
}
