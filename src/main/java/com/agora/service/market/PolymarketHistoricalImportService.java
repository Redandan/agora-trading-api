package com.agora.service.market;

import com.agora.config.PolymarketKeywords;
import com.agora.model.MdKline;
import com.agora.model.PolymarketHistoricalOdds;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.repository.trading.PolymarketHistoricalOddsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * One-shot / incremental import of Polymarket historical odds from CLOB prices-history.
 *
 * <p>Flow:
 * <ol>
 *   <li>Gamma {@code /markets} — discover markets matching BTC-relevant keywords</li>
 *   <li>CLOB {@code /prices-history?market=<tokenId>&interval=1h} — hourly odds time-series</li>
 *   <li>Align each hour to {@code md_kline} BTC close price</li>
 *   <li>Compute prob_delta_1h and forward BTC returns (1h / 4h / 24h)</li>
 *   <li>Upsert into {@code polymarket_historical_odds} (unique key skips duplicates)</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolymarketHistoricalImportService {

    private static final String GAMMA_BASE    = "https://gamma-api.polymarket.com";
    private static final String DATA_API_BASE = "https://data-api.polymarket.com";
    private static final long   MIN_VOLUME    = 100_000L;

    // Shared keyword maps — single source of truth in PolymarketKeywords config class
    private static final Map<String, String> KEYWORD_CATEGORY = PolymarketKeywords.KEYWORD_CATEGORY;

    private final PolymarketHistoricalOddsRepository histRepo;
    private final MdKlineRepository                  klineRepo;
    private final ObjectMapper                        objectMapper;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    public record ImportResult(int marketsFound, int rowsSaved, int rowsSkipped, List<String> errors) {}

    // -----------------------------------------------------------------------
    // Public entry point (called by MCP tool)
    // -----------------------------------------------------------------------

    public ImportResult importAll() {
        Map<String, MarketMeta> markets = discoverMarkets();
        log.info("[PolymarketImport] Discovered {} relevant markets", markets.size());

        int saved = 0, skipped = 0;
        List<String> errors = new ArrayList<>();

        for (MarketMeta meta : markets.values()) {
            try {
                int[] counts = importMarket(meta);
                saved   += counts[0];
                skipped += counts[1];
            } catch (Exception e) {
                String msg = meta.title() + ": " + e.getMessage();
                errors.add(msg);
                log.warn("[PolymarketImport] Failed '{}': {}", meta.title(), e.getMessage());
            }
        }
        log.info("[PolymarketImport] Done — markets={} saved={} skipped={} errors={}",
                markets.size(), saved, skipped, errors.size());
        return new ImportResult(markets.size(), saved, skipped, errors);
    }

    // -----------------------------------------------------------------------
    // Market discovery via Gamma /markets
    // -----------------------------------------------------------------------

    private Map<String, MarketMeta> discoverMarkets() {
        Map<String, MarketMeta> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : KEYWORD_CATEGORY.entrySet()) {
            try {
                List<MarketMeta> found = searchGammaMarkets(entry.getKey(), entry.getValue());
                for (MarketMeta m : found) result.putIfAbsent(m.conditionId(), m);
            } catch (Exception e) {
                log.warn("[PolymarketImport] Gamma search error for '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        return result;
    }

    private List<MarketMeta> searchGammaMarkets(String keyword, String category) throws Exception {
        // Use /markets endpoint which returns clobTokenIds for each market
        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        // Combine active + closed markets (closed=true returns ALL closed; omit to get active)
        List<MarketMeta> all = new ArrayList<>();
        all.addAll(fetchGammaMarkets(encoded, category, false));
        all.addAll(fetchGammaMarkets(encoded, category, true));
        return all;
    }

    private List<MarketMeta> fetchGammaMarkets(String encodedQ, String category, boolean closed) throws Exception {
        String closedParam = closed ? "&closed=true" : "&closed=false";
        // Gamma /public-search also returns clobTokenIds — use it for keyword search
        String url = GAMMA_BASE + "/public-search?q=" + encodedQ + "&limit=20" + closedParam;
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return List.of();
            JsonNode root = objectMapper.readTree(resp.body().string());
            JsonNode events = root.path("events");
            if (!events.isArray()) return List.of();

            List<MarketMeta> list = new ArrayList<>();
            for (JsonNode event : events) {
                for (JsonNode m : event.path("markets")) {
                    long vol = (long) m.path("volume").asDouble(0);
                    if (vol < MIN_VOLUME) continue;

                    String conditionId = m.path("conditionId").asText("");
                    if (conditionId.isBlank()) conditionId = m.path("id").asText("");
                    if (conditionId.isBlank()) continue;

                    String title = m.path("question").asText("").trim();
                    if (title.isBlank()) continue;

                    // clobTokenIds is a stringified JSON array in the Gamma API response
                    // e.g. "[\"42332...\", \"62495...\"]"  — parse it to get yes-token
                    String tokenId = null;
                    try {
                        JsonNode tokenIdsNode = m.path("clobTokenIds");
                        String raw = tokenIdsNode.isTextual() ? tokenIdsNode.asText()
                                : tokenIdsNode.toString();
                        if (!raw.isBlank() && raw.startsWith("[")) {
                            JsonNode arr = objectMapper.readTree(raw);
                            if (arr.isArray() && arr.size() > 0)
                                tokenId = arr.get(0).asText(null);
                        }
                    } catch (Exception ignored) {}

                    list.add(new MarketMeta(conditionId, tokenId, title, category));
                }
            }
            return list;
        }
    }

    // -----------------------------------------------------------------------
    // Per-market import: CLOB prices-history → BTC join → DB save
    // -----------------------------------------------------------------------

    private int[] importMarket(MarketMeta meta) throws Exception {
        // Use conditionId (not tokenId) — data-api trades endpoint accepts conditionId
        List<PricePoint> points = fetchPricesHistoryFromTrades(meta.conditionId());
        if (points.isEmpty()) return new int[]{0, 0};

        // Preload BTC klines for the time range to avoid N+1 queries
        LocalDateTime rangeStart = points.get(0).time().minusHours(1);
        LocalDateTime rangeEnd   = points.get(points.size() - 1).time().plusHours(25);
        Map<LocalDateTime, BigDecimal> btcPrices = loadBtcPrices(rangeStart, rangeEnd);

        int saved = 0, skipped = 0;
        BigDecimal prevProb = null;

        for (int i = 0; i < points.size(); i++) {
            PricePoint p = points.get(i);

            if (histRepo.existsByMarketIdAndEventTime(meta.conditionId(), p.time())) {
                skipped++;
                prevProb = p.prob();
                continue;
            }

            PolymarketHistoricalOdds row = new PolymarketHistoricalOdds();
            row.setMarketId(meta.conditionId());
            row.setTokenId(meta.tokenId());
            row.setMarketTitle(meta.title());
            row.setMarketCategory(meta.category());
            row.setEventTime(p.time());
            row.setProb(p.prob());

            if (prevProb != null)
                row.setProbDelta1h(p.prob().subtract(prevProb).setScale(4, RoundingMode.HALF_UP));

            row.setBtcPrice(btcPrices.get(p.time()));
            row.setBtcChange1h(pctChange(btcPrices.get(p.time()), btcPrices.get(p.time().plusHours(1))));
            row.setBtcChange4h(pctChange(btcPrices.get(p.time()), btcPrices.get(p.time().plusHours(4))));
            row.setBtcChange24h(pctChange(btcPrices.get(p.time()), btcPrices.get(p.time().plusHours(24))));

            histRepo.save(row);
            saved++;
            prevProb = p.prob();
        }
        log.info("[PolymarketImport] '{}' — points={} saved={} skipped={}",
                meta.title(), points.size(), saved, skipped);
        return new int[]{saved, skipped};
    }

    // -----------------------------------------------------------------------
    // data-api/trades → reconstruct hourly price history
    // CLOB /prices-history requires auth (403); data-api/trades is public.
    // Strategy: paginate all Yes-outcome trades, group by hour, take last price.
    // -----------------------------------------------------------------------

    private List<PricePoint> fetchPricesHistoryFromTrades(String conditionId) throws Exception {
        Map<LocalDateTime, BigDecimal> hourly = new LinkedHashMap<>();
        int offset = 0;
        final int PAGE      = 10_000;
        final int MAX_PAGES = 50;   // cap at 500K trades (prevents runaway on million-trade markets)
        final int MAX_RETRY = 3;

        for (int pageNum = 0; pageNum < MAX_PAGES; pageNum++) {
            String url = DATA_API_BASE + "/trades?market=" + conditionId
                    + "&limit=" + PAGE + "&offset=" + offset;
            Request req = new Request.Builder().url(url).get().build();

            // Retry loop with exponential backoff (handles transient 429/503)
            List<JsonNode> page = null;
            for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
                try (Response resp = http.newCall(req).execute()) {
                    if (resp.body() == null) break;
                    if (!resp.isSuccessful()) {
                        if (attempt < MAX_RETRY) {
                            Thread.sleep(500L * attempt);
                            continue;
                        }
                        break;
                    }
                    JsonNode root = objectMapper.readTree(resp.body().string());
                    if (!root.isArray() || root.isEmpty()) { page = List.of(); break; }
                    page = new ArrayList<>();
                    root.forEach(page::add);
                    break;
                } catch (Exception ex) {
                    if (attempt == MAX_RETRY) throw ex;
                    Thread.sleep(500L * attempt);
                }
            }
            if (page == null || page.isEmpty()) break;  // API failure or no more data

            for (JsonNode t : page) {
                if (!"Yes".equals(t.path("outcome").asText())) continue;
                long epochSec = t.path("timestamp").asLong(0);
                if (epochSec == 0) continue;
                double price = t.path("price").asDouble(-1);
                if (price < 0 || price > 1) continue;

                LocalDateTime hour = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(epochSec), ZoneOffset.UTC)
                        .truncatedTo(ChronoUnit.HOURS);
                // Latest trade wins within the hour (overwrite keeps last seen)
                hourly.put(hour, BigDecimal.valueOf(price).setScale(4, RoundingMode.HALF_UP));
            }

            if (page.size() < PAGE) break;  // last page
            offset += PAGE;
        }

        if (hourly.isEmpty()) return List.of();

        // Sort by time ascending
        List<Map.Entry<LocalDateTime, BigDecimal>> entries = new ArrayList<>(hourly.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        List<PricePoint> result = new ArrayList<>();
        entries.forEach(e -> result.add(new PricePoint(e.getKey(), e.getValue())));
        return result;
    }

    // -----------------------------------------------------------------------
    // BTC kline helpers
    // -----------------------------------------------------------------------

    private Map<LocalDateTime, BigDecimal> loadBtcPrices(LocalDateTime from, LocalDateTime to) {
        List<MdKline> klines = klineRepo
                .findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc("BTCUSDT", "1h", from, to);
        Map<LocalDateTime, BigDecimal> map = new HashMap<>();
        for (MdKline k : klines) map.put(k.getOpenTime(), k.getClosePrice());
        return map;
    }

    private BigDecimal pctChange(BigDecimal base, BigDecimal future) {
        if (base == null || future == null || base.compareTo(BigDecimal.ZERO) == 0) return null;
        return future.subtract(base)
                .divide(base, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    // -----------------------------------------------------------------------
    // Internal records
    // -----------------------------------------------------------------------

    private record MarketMeta(String conditionId, String tokenId, String title, String category) {}
    private record PricePoint(LocalDateTime time, BigDecimal prob) {}
}
