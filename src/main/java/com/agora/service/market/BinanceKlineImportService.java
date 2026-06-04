package com.agora.service.market;

import com.agora.config.BinanceMarketDataProperties;
import com.agora.dto.market.KlineImportResponse;
import com.agora.model.MdKline;
import com.agora.repository.trading.MdKlineRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;

/**
 * 透過 Binance REST API 批次拉取歷史 K 線並存入 md_kline 表。
 * 每次呼叫 Binance 最多 1000 根，自動分頁直到涵蓋整個指定時間範圍。
 */
@Slf4j
@Service
public class BinanceKlineImportService {

    static final String DEFAULT_BASE_URL = "https://api.binance.com/api/v3/klines";
    static final String DEFAULT_FUTURES_BASE_URL = "https://fapi.binance.com/fapi/v1/klines";
    private static final int BATCH_SIZE = 1000;
    private static final ZoneId UTC = ZoneId.of("UTC");

    private final MdKlineRepository klineRepository;
    private final MdKlineInsertHelper insertHelper;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private final String spotBaseUrl;
    private final String futuresBaseUrl;

    /** Spring 使用的主要建構子 */
    @Autowired
    public BinanceKlineImportService(MdKlineRepository klineRepository,
                                     MdKlineInsertHelper insertHelper,
                                     ObjectMapper objectMapper,
                                     BinanceMarketDataProperties properties) {
        this(klineRepository, insertHelper, objectMapper,
            resolveSpotRestBaseUrl(properties),
                new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build(),
            resolveFuturesRestBaseUrl(properties));
    }

    /** 測試用建構子，允許注入自訂 baseUrl 與 OkHttpClient */
    BinanceKlineImportService(MdKlineRepository klineRepository, MdKlineInsertHelper insertHelper, ObjectMapper objectMapper,
                              String baseUrl, OkHttpClient httpClient) {
        this(klineRepository, insertHelper, objectMapper, baseUrl, httpClient, DEFAULT_FUTURES_BASE_URL);
    }

    /** 測試用建構子，允許注入自訂 baseUrl、futuresBaseUrl 與 OkHttpClient */
    BinanceKlineImportService(MdKlineRepository klineRepository, MdKlineInsertHelper insertHelper, ObjectMapper objectMapper,
                              String baseUrl, OkHttpClient httpClient, String futuresBaseUrl) {
        this.klineRepository = klineRepository;
        this.insertHelper = insertHelper;
        this.objectMapper = objectMapper;
        this.spotBaseUrl = normalizeRestBaseUrl(baseUrl, DEFAULT_BASE_URL);
        this.futuresBaseUrl = normalizeRestBaseUrl(futuresBaseUrl, DEFAULT_FUTURES_BASE_URL);
        this.httpClient = httpClient;
    }

    @PostConstruct
    void logEndpointConfig() {
        log.info("[BinanceImport] Endpoint config loaded | spotRest={} futuresRest={}", spotBaseUrl, futuresBaseUrl);
    }

    /**
     * 拉取 [startTime, endTime) 範圍的 K 線並以 upsert-skip 方式存入 DB。
     *
     * @return 匯入統計（新增數、略過已存在數、耗時 ms）
     */
    public KlineImportResponse importHistorical(String symbol, String intervalCode,
                                                LocalDateTime startTime, LocalDateTime endTime) {
        return importHistorical(symbol, intervalCode, "SPOT", startTime, endTime, "binance");
    }

    public KlineImportResponse importHistorical(String symbol, String intervalCode, String marketType,
                                                LocalDateTime startTime, LocalDateTime endTime) {
        return importHistorical(symbol, intervalCode, marketType, startTime, endTime, "binance");
    }

    /**
     * Source-aware 版本:依指定 source 寫入,並用 source-aware exists check 避免被另一源誤判跳過。
     */
    public KlineImportResponse importHistorical(String symbol, String intervalCode, String marketType,
                                                LocalDateTime startTime, LocalDateTime endTime,
                                                String source) {
        String normalizedSource = (source == null || source.isBlank()) ? "binance" : source.toLowerCase().trim();
        long startMs = startTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        long endMs   = endTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        long cursor  = startMs;
        int imported = 0, skipped = 0;
        long began   = System.currentTimeMillis();
        String normalizedMarketType = normalizeMarketType(marketType);
        String apiBaseUrl = "FUTURES".equals(normalizedMarketType) ? futuresBaseUrl : spotBaseUrl;

        while (cursor < endMs) {
            List<MdKline> batch = fetchBatch(apiBaseUrl, symbol, intervalCode, cursor, endMs);
            if (batch.isEmpty()) break;

            // 一次性查詢此批次時間範圍內已存在的 openTime（限定 source,避免被另一源誤判）
            LocalDateTime rangeStart = batch.get(0).getOpenTime();
            LocalDateTime rangeEnd   = batch.get(batch.size() - 1).getOpenTime();
            Set<LocalDateTime> existing = new HashSet<>(
                    klineRepository.findOpenTimesBetweenBySource(symbol, intervalCode, normalizedSource, rangeStart, rangeEnd));

            List<MdKline> toSave = new ArrayList<>();
            for (MdKline k : batch) {
                if (existing.contains(k.getOpenTime())) {
                    skipped++;
                } else {
                    k.setSource(normalizedSource);
                    toSave.add(k);
                }
            }
            for (MdKline k : toSave) {
                if (insertHelper.insertIgnore(k)) {
                    imported++;
                } else {
                    skipped++;
                }
            }
            log.info("[BinanceImport] {} {}@{} source={} | batch={} imported={} skipped={}",
                    normalizedMarketType, symbol, intervalCode, normalizedSource, batch.size(), imported, skipped);

            // 推進游標到最後一根的下一毫秒
            cursor = batch.get(batch.size() - 1).getOpenTime()
                         .toInstant(ZoneOffset.UTC).toEpochMilli() + 1L;
            if (batch.size() < BATCH_SIZE) break;
        }

        long duration = System.currentTimeMillis() - began;
        log.info("[BinanceImport] Done {} {}@{} source={} | total imported={} skipped={} duration={}ms",
                normalizedMarketType, symbol, intervalCode, normalizedSource, imported, skipped, duration);
        return new KlineImportResponse(imported, skipped, duration);
    }

    /**
     * 刪除 DB 中指定時間範圍的 K 線並重新從 Binance 匯入（用於修復錯誤資料）。
     *
     * @return 重新匯入統計
     */
    @org.springframework.transaction.annotation.Transactional
    public KlineImportResponse reimportHistorical(String symbol, String intervalCode, String marketType,
                                                   LocalDateTime startTime, LocalDateTime endTime) {
        return reimportHistorical(symbol, intervalCode, marketType, startTime, endTime, "binance");
    }

    /**
     * Source-aware reimport:只刪指定 source 的 bar,避免誤刪雙寫的另一源資料。
     */
    @org.springframework.transaction.annotation.Transactional
    public KlineImportResponse reimportHistorical(String symbol, String intervalCode, String marketType,
                                                   LocalDateTime startTime, LocalDateTime endTime,
                                                   String source) {
        String normalizedSource = (source == null || source.isBlank()) ? "binance" : source.toLowerCase().trim();
        int deleted = klineRepository.deleteBySymbolAndIntervalCodeAndSourceAndOpenTimeBetween(
                symbol, intervalCode, normalizedSource, startTime, endTime);
        log.info("[BinanceImport] Deleted {} existing klines for {} {}@{} source={} before re-import",
                deleted, marketType, symbol, intervalCode, normalizedSource);
        return importHistorical(symbol, intervalCode, marketType, startTime, endTime, normalizedSource);
    }

    /**
     * 從 Binance REST API 取得最新一根 K 線（不寫入 DB）。
     *
     * @param symbol       例如 "BTCUSDT"
     * @param intervalCode 例如 "1m"、"15m"
     * @return 最新 K 線，若 API 失敗則回傳 null
     */
    public MdKline fetchLatestKline(String symbol, String intervalCode) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(spotBaseUrl)).newBuilder()
                .addQueryParameter("symbol",   symbol)
                .addQueryParameter("interval", intervalCode)
                .addQueryParameter("limit",    "1")
                .build();

        Request request = new Request.Builder().url(url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("[BinanceLive] API error {}: {}", response.code(),
                        response.body() != null ? response.body().string() : "");
                return null;
            }
            String body = Objects.requireNonNull(response.body()).string();
            JsonNode arr = objectMapper.readTree(body);
            if (!arr.isArray() || arr.size() == 0) return null;
            return parseRow(symbol, intervalCode, arr.get(0));
        } catch (Exception e) {
            log.warn("[BinanceLive] Failed to fetch latest kline for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    // ── 私有方法 ─────────────────────────────────────────────────────────────

    private List<MdKline> fetchBatch(String apiBaseUrl, String symbol, String intervalCode,
                                     long startMs, long endMs) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(apiBaseUrl)).newBuilder()
                .addQueryParameter("symbol",    symbol)
                .addQueryParameter("interval",  intervalCode)
                .addQueryParameter("startTime", String.valueOf(startMs))
                .addQueryParameter("endTime",   String.valueOf(Math.min(endMs, Long.MAX_VALUE)))
                .addQueryParameter("limit",     String.valueOf(BATCH_SIZE))
                .build();

        Request request = new Request.Builder().url(url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                throw new RuntimeException("Binance API error " + response.code() + ": " + body);
            }
            String body = Objects.requireNonNull(response.body()).string();
            JsonNode arr = objectMapper.readTree(body);
            List<MdKline> list = new ArrayList<>();
            for (JsonNode row : arr) {
                list.add(parseRow(symbol, intervalCode, row));
            }
            return list;
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch Binance klines: " + e.getMessage(), e);
        }
    }

    private MdKline parseRow(String symbol, String intervalCode, JsonNode row) {
        // Binance K 線陣列欄位順序：
        // [0]openTime [1]open [2]high [3]low [4]close [5]volume [6]closeTime ...
        MdKline k = new MdKline();
        k.setSymbol(symbol);
        k.setIntervalCode(intervalCode);
        k.setOpenTime(msToLocal(row.get(0).asLong()));
        k.setOpenPrice(new BigDecimal(row.get(1).asText()));
        k.setHighPrice(new BigDecimal(row.get(2).asText()));
        k.setLowPrice(new BigDecimal(row.get(3).asText()));
        k.setClosePrice(new BigDecimal(row.get(4).asText()));
        k.setVolume(new BigDecimal(row.get(5).asText()));
        k.setCloseTime(msToLocal(row.get(6).asLong()));
        return k;
    }

    private static LocalDateTime msToLocal(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(UTC).toLocalDateTime();
    }

    private String normalizeMarketType(String marketType) {
        if (marketType == null || marketType.trim().isEmpty()) {
            return "SPOT";
        }
        String value = marketType.trim().toUpperCase();
        return "FUTURES".equals(value) ? "FUTURES" : "SPOT";
    }

    private String normalizeRestBaseUrl(String value, String defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static String resolveSpotRestBaseUrl(BinanceMarketDataProperties properties) {
        if (properties == null || properties.getSpotRestBaseUrl() == null || properties.getSpotRestBaseUrl().trim().isEmpty()) {
            return DEFAULT_BASE_URL;
        }
        return properties.getSpotRestBaseUrl().trim();
    }

    private static String resolveFuturesRestBaseUrl(BinanceMarketDataProperties properties) {
        if (properties == null || properties.getFuturesRestBaseUrl() == null || properties.getFuturesRestBaseUrl().trim().isEmpty()) {
            return DEFAULT_FUTURES_BASE_URL;
        }
        return properties.getFuturesRestBaseUrl().trim();
    }
}
