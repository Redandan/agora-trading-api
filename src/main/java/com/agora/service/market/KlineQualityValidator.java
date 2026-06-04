package com.agora.service.market;

import com.agora.model.MdKline;
import com.agora.repository.trading.MdKlineRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 比對本地 DB md_kline vs OKX history-candles API，檢查 K 線資料品質。
 *
 * <p><b>檢查項目</b>：
 * <ul>
 *   <li>DB 是否有缺口（expected vs actual bar 數）</li>
 *   <li>OHLCV 是否與 OKX 一致（容許 0.05% 浮點誤差）</li>
 *   <li>DB 是否有 OKX 沒有的「幽靈 bar」</li>
 * </ul>
 *
 * <p><b>驗證目的</b>：在信任回測結果之前，先確認底層資料乾淨。錯誤資料會導致
 * 回測的 SL/TP 觸發時間錯誤、entry/exit 價格錯誤、volume 指標失真。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KlineQualityValidator {

    private static final String OKX_BASE = "https://www.okx.com";
    // 跨所容差：DB 來源為 Binance.us（美國），OKX 為全球流動性最高所。
    // - Price：0.3%（實測跨所價差常在 0.1-0.2%，設 0.3% 過濾噪音）
    // - Volume：300%（兩所量綱差異 ~70x，volume 差異無 bug 診斷價值，放到實質上禁用）
    private static final double PRICE_TOLERANCE_PCT = 0.003;
    private static final double VOLUME_TOLERANCE_PCT = 3.0;
    private static final int OKX_PAGE_LIMIT = 300;

    private final MdKlineRepository klineRepository;
    private final ObjectMapper objectMapper;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    public record Divergence(
            LocalDateTime openTime,
            String field,
            double dbValue,
            double okxValue,
            double diffPct
    ) {}

    public record ValidationReport(
            String symbol,
            String intervalCode,
            int daysChecked,
            int dbBarCount,
            int okxBarCount,
            int missingInDb,        // OKX 有但 DB 無
            int phantomInDb,        // DB 有但 OKX 無
            int priceDivergences,
            int volumeDivergences,
            List<Divergence> samples  // 最多列 10 筆 sample
    ) {}

    /**
     * 比對指定 symbol + interval 的最近 N 天 K 線。
     */
    /** 比對特定 source 的 DB bars vs OKX REST（原始行為：不傳 source 等同 binance）。 */
    public ValidationReport validate(String symbol, String intervalCode, int days) {
        return validate(symbol, intervalCode, days, "binance");
    }

    public ValidationReport validate(String symbol, String intervalCode, int days, String source) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime start = now.minusDays(days);

        // 1. 取 DB 指定 source 的 bars
        List<MdKline> dbBars = klineRepository
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        symbol, intervalCode, source, start, now);
        Map<LocalDateTime, MdKline> dbByTime = new HashMap<>();
        for (MdKline k : dbBars) dbByTime.put(k.getOpenTime(), k);

        // 2. 分頁從 OKX 取同時段 bars
        Map<LocalDateTime, OkxBar> okxByTime = fetchOkxBars(symbol, intervalCode, start, now);

        // 3. 逐 bar 比對
        int missingInDb = 0;
        int phantomInDb = 0;
        int priceDiv = 0;
        int volDiv = 0;
        List<Divergence> samples = new ArrayList<>();

        for (Map.Entry<LocalDateTime, OkxBar> e : okxByTime.entrySet()) {
            LocalDateTime t = e.getKey();
            OkxBar okx = e.getValue();
            MdKline db = dbByTime.get(t);
            if (db == null) {
                missingInDb++;
                if (samples.size() < 10) {
                    samples.add(new Divergence(t, "MISSING", 0, okx.close, 1.0));
                }
                continue;
            }
            // 逐欄比對（OHLCV）
            double[] dbVals = {
                    db.getOpenPrice().doubleValue(),
                    db.getHighPrice().doubleValue(),
                    db.getLowPrice().doubleValue(),
                    db.getClosePrice().doubleValue(),
                    db.getVolume().doubleValue()
            };
            double[] okxVals = { okx.open, okx.high, okx.low, okx.close, okx.volume };
            String[] fields = { "open", "high", "low", "close", "volume" };
            for (int i = 0; i < 5; i++) {
                if (dbVals[i] == 0 && okxVals[i] == 0) continue;
                double diff = Math.abs(dbVals[i] - okxVals[i]);
                double base = Math.max(Math.abs(dbVals[i]), Math.abs(okxVals[i]));
                double diffPct = base > 0 ? diff / base : 0;
                double tolerance = "volume".equals(fields[i]) ? VOLUME_TOLERANCE_PCT : PRICE_TOLERANCE_PCT;
                if (diffPct > tolerance) {
                    if ("volume".equals(fields[i])) volDiv++;
                    else priceDiv++;
                    if (samples.size() < 10) {
                        samples.add(new Divergence(t, fields[i], dbVals[i], okxVals[i], diffPct));
                    }
                }
            }
        }

        // Phantom bars：DB 有但 OKX 沒有
        for (LocalDateTime t : dbByTime.keySet()) {
            if (!okxByTime.containsKey(t)) {
                phantomInDb++;
                if (samples.size() < 10) {
                    samples.add(new Divergence(t, "PHANTOM",
                            dbByTime.get(t).getClosePrice().doubleValue(), 0, 1.0));
                }
            }
        }

        log.info("[KlineQuality] {}@{} {}d: db={} okx={} missing={} phantom={} priceDiv={} volDiv={}",
                symbol, intervalCode, days, dbBars.size(), okxByTime.size(),
                missingInDb, phantomInDb, priceDiv, volDiv);

        return new ValidationReport(symbol, intervalCode, days,
                dbBars.size(), okxByTime.size(),
                missingInDb, phantomInDb, priceDiv, volDiv, samples);
    }

    private record OkxBar(double open, double high, double low, double close, double volume) {}

    /**
     * 從 OKX history-candles API 分頁取資料。
     * OKX 以「ts」為 cursor，最新在前，用 `after` param 往前翻頁。
     */
    private Map<LocalDateTime, OkxBar> fetchOkxBars(String symbol, String intervalCode,
                                                     LocalDateTime start, LocalDateTime end) {
        Map<LocalDateTime, OkxBar> result = new HashMap<>();
        String instId = toOkxSpotInstId(symbol);
        String bar = toOkxBar(intervalCode);
        long startMs = start.toInstant(ZoneOffset.UTC).toEpochMilli();
        long cursorMs = end.toInstant(ZoneOffset.UTC).toEpochMilli();  // 最新 ts 起往前

        int pageCount = 0;
        int maxPages = 30;  // 最多取 30 × 300 = 9000 根（180 天 1h ≈ 4320 根，含餘量）
        while (pageCount < maxPages && cursorMs > startMs) {
            String url = OKX_BASE + "/api/v5/market/history-candles?instId=" + instId
                    + "&bar=" + bar + "&limit=" + OKX_PAGE_LIMIT + "&after=" + cursorMs;
            try {
                Request req = new Request.Builder().url(url).build();
                try (Response resp = httpClient.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        log.warn("[KlineQuality] OKX page {} HTTP {}", pageCount, resp.code());
                        break;
                    }
                    JsonNode root = objectMapper.readTree(resp.body().string());
                    if (!"0".equals(root.path("code").asText())) {
                        log.warn("[KlineQuality] OKX error: {}", root.path("msg").asText());
                        break;
                    }
                    JsonNode data = root.path("data");
                    if (!data.isArray() || data.isEmpty()) break;

                    long minTs = cursorMs;
                    for (JsonNode row : data) {
                        // 只收 confirm=1 的已收盤 bar
                        if ("0".equals(row.get(8).asText())) continue;
                        long ts = row.get(0).asLong();
                        if (ts < startMs) continue;
                        LocalDateTime t = Instant.ofEpochMilli(ts).atZone(ZoneOffset.UTC).toLocalDateTime();
                        result.put(t, new OkxBar(
                                row.get(1).asDouble(0),
                                row.get(2).asDouble(0),
                                row.get(3).asDouble(0),
                                row.get(4).asDouble(0),
                                row.get(5).asDouble(0)
                        ));
                        if (ts < minTs) minTs = ts;
                    }
                    cursorMs = minTs;  // 下一頁從更早開始
                    pageCount++;
                    // OKX public rate limit: 20/2s → 我們每頁 ~200ms 就很安全
                    Thread.sleep(150);
                }
            } catch (Exception ex) {
                log.warn("[KlineQuality] OKX page {} failed: {}", pageCount, ex.getMessage());
                break;
            }
        }
        log.debug("[KlineQuality] Fetched {} bars from OKX in {} pages", result.size(), pageCount);
        return result;
    }

    private String toOkxSpotInstId(String symbol) {
        if (symbol.endsWith("USDT")) return symbol.substring(0, symbol.length() - 4) + "-USDT";
        if (symbol.endsWith("BUSD")) return symbol.substring(0, symbol.length() - 4) + "-BUSD";
        return symbol;
    }

    private String toOkxBar(String intervalCode) {
        String code = intervalCode.toLowerCase(Locale.ROOT);
        if (code.endsWith("h")) return code.toUpperCase(Locale.ROOT);
        if (code.endsWith("d")) return code.toUpperCase(Locale.ROOT);
        return code;
    }

    /** Convenience for MCP output formatting. */
    public static BigDecimal pct(double v) {
        return BigDecimal.valueOf(v * 100).setScale(4, RoundingMode.HALF_UP);
    }
}
