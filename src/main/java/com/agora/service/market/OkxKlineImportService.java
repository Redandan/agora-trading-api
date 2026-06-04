package com.agora.service.market;

import com.agora.dto.market.KlineImportResponse;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 從 OKX REST API 批次拉取歷史 K 線並以 source='okx' 存入 md_kline。
 *
 * <p>用於初始化 OKX 資料集：V026 migration 後歷史 rows 全部是 source='binance'，
 * 此匯入器補齊過去 N 天的 source='okx' 版本，讓回測可以選 OKX 源並與 Binance 對比。
 *
 * <p>端點：{@code /api/v5/market/history-candles?instId=...&bar=...&limit=300&after=<ts>}
 * 用 {@code after} cursor 往前翻頁；每頁 150ms sleep 保守避開 rate limit（20 req/2s）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OkxKlineImportService {

    private static final String OKX_BASE = "https://www.okx.com";
    private static final int PAGE_LIMIT = 300;
    private static final long INTER_PAGE_SLEEP_MS = 150;

    private final MdKlineRepository klineRepository;
    private final MdKlineInsertHelper insertHelper;
    private final ObjectMapper objectMapper;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    /**
     * 拉取 [start, end) 的 OKX 歷史 K 線並存入 DB（source='okx'）。
     * 已存在的 (symbol, interval, openTime, 'okx') 組合自動略過。
     */
    public KlineImportResponse importHistorical(String symbol, String intervalCode,
                                                 LocalDateTime start, LocalDateTime end) {
        long began = System.currentTimeMillis();
        String instId = toOkxSpotInstId(symbol);
        String bar = toOkxBar(intervalCode);
        long startMs = start.toInstant(ZoneOffset.UTC).toEpochMilli();
        long cursorMs = end.toInstant(ZoneOffset.UTC).toEpochMilli();

        int imported = 0;
        int skipped = 0;
        int pageCount = 0;
        int maxPages = 60;  // 60 × 300 = 18000 根，足夠 180 天 1h × 多重 safety margin

        // 先預載此範圍內已存在的 openTime（source='okx'）減少 per-bar exists 查詢
        Set<LocalDateTime> existing = new HashSet<>(
                klineRepository.findOpenTimesBetweenBySource(symbol, intervalCode, "okx", start, end));

        while (pageCount < maxPages && cursorMs > startMs) {
            String url = OKX_BASE + "/api/v5/market/history-candles?instId=" + instId
                    + "&bar=" + bar + "&limit=" + PAGE_LIMIT + "&after=" + cursorMs;
            try {
                Request req = new Request.Builder().url(url).build();
                try (Response resp = httpClient.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        log.warn("[OkxImport] HTTP {} on page {}", resp.code(), pageCount);
                        break;
                    }
                    JsonNode root = objectMapper.readTree(resp.body().string());
                    if (!"0".equals(root.path("code").asText())) {
                        log.warn("[OkxImport] OKX error: {}", root.path("msg").asText());
                        break;
                    }
                    JsonNode data = root.path("data");
                    if (!data.isArray() || data.isEmpty()) break;

                    List<MdKline> toSave = new ArrayList<>();
                    long minTs = cursorMs;
                    for (JsonNode row : data) {
                        // confirm=0 未收盤，略過
                        if ("0".equals(row.get(8).asText())) continue;
                        long ts = row.get(0).asLong();
                        if (ts < startMs) continue;
                        if (ts < minTs) minTs = ts;

                        LocalDateTime openTime = Instant.ofEpochMilli(ts)
                                .atZone(ZoneOffset.UTC).toLocalDateTime();
                        if (existing.contains(openTime)) {
                            skipped++;
                            continue;
                        }
                        MdKline k = new MdKline();
                        k.setSymbol(symbol);
                        k.setIntervalCode(intervalCode);
                        k.setOpenTime(openTime);
                        k.setOpenPrice(new BigDecimal(row.get(1).asText()));
                        k.setHighPrice(new BigDecimal(row.get(2).asText()));
                        k.setLowPrice(new BigDecimal(row.get(3).asText()));
                        k.setClosePrice(new BigDecimal(row.get(4).asText()));
                        k.setVolume(new BigDecimal(row.get(5).asText()));
                        k.setSource("okx");
                        k.setCloseTime(openTime.plus(intervalDuration(intervalCode)));
                        toSave.add(k);
                        existing.add(openTime);
                    }
                    for (MdKline k : toSave) {
                        if (insertHelper.insertIgnore(k)) {
                            imported++;
                        } else {
                            skipped++;
                        }
                    }
                    pageCount++;
                    log.info("[OkxImport] {}@{} page {} imported={} skipped={} minTs={}",
                            symbol, intervalCode, pageCount, imported, skipped,
                            Instant.ofEpochMilli(minTs));

                    if (minTs >= cursorMs) break;  // 沒有更舊資料
                    cursorMs = minTs;
                    try { Thread.sleep(INTER_PAGE_SLEEP_MS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            } catch (Exception ex) {
                log.warn("[OkxImport] Page {} failed: {}", pageCount, ex.getMessage());
                break;
            }
        }

        long duration = System.currentTimeMillis() - began;
        log.info("[OkxImport] Done {}@{} imported={} skipped={} pages={} duration={}ms",
                symbol, intervalCode, imported, skipped, pageCount, duration);
        return new KlineImportResponse(imported, skipped, duration);
    }

    private String toOkxSpotInstId(String symbol) {
        if (symbol.endsWith("USDT")) return symbol.substring(0, symbol.length() - 4) + "-USDT";
        if (symbol.endsWith("BUSD")) return symbol.substring(0, symbol.length() - 4) + "-BUSD";
        return symbol;
    }

    private String toOkxBar(String intervalCode) {
        String code = intervalCode.toLowerCase(Locale.ROOT);
        if (code.endsWith("h") || code.endsWith("d")) return code.toUpperCase(Locale.ROOT);
        return code;
    }

    private Duration intervalDuration(String intervalCode) {
        String code = intervalCode.toLowerCase(Locale.ROOT);
        if (code.endsWith("m")) return Duration.ofMinutes(Long.parseLong(code.substring(0, code.length() - 1)));
        if (code.endsWith("h")) return Duration.ofHours(Long.parseLong(code.substring(0, code.length() - 1)));
        if (code.endsWith("d")) return Duration.ofDays(Long.parseLong(code.substring(0, code.length() - 1)));
        throw new IllegalArgumentException("Unsupported interval: " + intervalCode);
    }
}
