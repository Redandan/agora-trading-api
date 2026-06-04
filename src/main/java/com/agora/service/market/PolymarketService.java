package com.agora.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 查詢 Polymarket Gamma API，取得川普關稅/貿易政策相關預測市場的宏觀風險分數。
 *
 * <p>宏觀風險分數（macroRiskScore）= 正面政策事件（關稅暫停/貿易協議）Yes 概率的加權平均。
 * 分數高 → 市場預期正面宏觀衝擊概率高 → 加密市場可能暴漲 → 做空風險大。
 *
 * <p>API：{@code https://gamma-api.polymarket.com/public-search}（公開免費，無需認證）
 * 快取 1 小時（預測市場概率每小時變動小）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolymarketService {

    private static final String GAMMA_BASE = "https://gamma-api.polymarket.com";
    private static final long MIN_VOLUME = 5_000L;  // 至少 $5k USDC 才算有效市場

    /**
     * 搜尋關鍵字：這些市場的 Yes = 利多宏觀衝擊概率（貿易協議/川普中國訪問/關稅退款）。
     * 根據 Polymarket 實際活躍市場調整（2026-04：trade war / trump trade war 有最多活躍市場）。
     */
    private static final List<String> BULLISH_SHOCK_KEYWORDS = List.of(
            "trump trade war",
            "trade war",
            "tariff reduction",
            "tariff pause"
    );

    private final ObjectMapper objectMapper;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public record MarketInfo(String question, double yesProbability, long volume) {}

    /**
     * 宏觀風險查詢結果。
     *
     * @param riskScore  0.0-1.0 的加權平均 Yes 概率；-1.0 = 無相關活躍市場（中立，不觸發封鎖）
     * @param markets    找到的相關市場列表（按 volume 降序）
     * @param summary    人類可讀摘要
     */
    public record MacroRiskResult(double riskScore, List<MarketInfo> markets, String summary) {
        public static MacroRiskResult neutral() {
            return new MacroRiskResult(-1.0, List.of(), "無相關活躍市場，中立");
        }
    }

    /**
     * 取得當前宏觀風險分數（快取 1 小時）。
     * API 失敗時回傳 neutral（不觸發封鎖），確保系統安全降級。
     */
    @Cacheable("polymarketRisk")
    public MacroRiskResult getMacroRisk() {
        List<MarketInfo> allMarkets = new ArrayList<>();

        for (String keyword : BULLISH_SHOCK_KEYWORDS) {
            try {
                List<MarketInfo> found = searchMarkets(keyword);
                allMarkets.addAll(found);
            } catch (Exception e) {
                log.warn("[Polymarket] Search failed for keyword='{}': {}", keyword, e.getMessage());
            }
        }

        if (allMarkets.isEmpty()) {
            log.info("[Polymarket] No relevant active markets found, returning neutral");
            return MacroRiskResult.neutral();
        }

        // 去重（同一市場可能被多個關鍵字匹配）
        List<MarketInfo> unique = allMarkets.stream()
                .collect(java.util.stream.Collectors.toMap(
                        MarketInfo::question, m -> m, (a, b) -> a.volume() >= b.volume() ? a : b))
                .values().stream()
                .sorted(Comparator.comparingLong(MarketInfo::volume).reversed())
                .toList();

        // 加權平均（volume 為權重）
        double totalVolume = unique.stream().mapToLong(MarketInfo::volume).sum();
        double weightedScore = unique.stream()
                .mapToDouble(m -> m.yesProbability() * m.volume() / totalVolume)
                .sum();

        String summary = buildSummary(unique, weightedScore);
        log.info("[Polymarket] macroRiskScore={}, markets={}", String.format("%.2f", weightedScore), unique.size());
        return new MacroRiskResult(weightedScore, unique, summary);
    }

    private List<MarketInfo> searchMarkets(String keyword) throws Exception {
        // NOTE: Polymarket Gamma API /public-search uses "q" (not "query") parameter.
        // Response structure: { "events": [ { "markets": [...], "active": bool, "closed": bool } ] }
        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        String url = GAMMA_BASE + "/public-search?q=" + encoded + "&limit=10";

        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[Polymarket] HTTP {} for query='{}'", resp.code(), keyword);
                return List.of();
            }
            JsonNode root = objectMapper.readTree(resp.body().string());

            // Response wraps markets inside events[]
            JsonNode eventsNode = root.path("events");
            if (!eventsNode.isArray() || eventsNode.isEmpty()) return List.of();

            List<MarketInfo> result = new ArrayList<>();
            for (JsonNode event : eventsNode) {
                // Skip closed events
                if (event.path("closed").asBoolean(false)) continue;

                for (JsonNode m : event.path("markets")) {
                    if (!m.path("active").asBoolean(false)) continue;
                    if (m.path("closed").asBoolean(false)) continue;

                    // outcomePrices is already a JSON array node: ["0.62", "0.38"]
                    // outcomes[0] = Yes, outcomes[1] = No
                    double yesProbability = parseYesProbability(m.path("outcomePrices"));
                    if (yesProbability < 0) continue;

                    long volume = (long) m.path("volume").asDouble(0);
                    if (volume < MIN_VOLUME) continue;

                    String question = m.path("question").asText("");
                    if (question.isBlank()) continue;

                    result.add(new MarketInfo(question, yesProbability, volume));
                }
            }
            return result;
        }
    }

    /**
     * Polymarket outcomePrices 是 JsonNode array，如 {@code ["0.62","0.38"]}。
     * index 0 = Yes，index 1 = No。
     */
    private double parseYesProbability(JsonNode outcomePrices) {
        try {
            if (outcomePrices.isArray() && outcomePrices.size() > 0)
                return outcomePrices.get(0).asDouble(-1);
            // Fallback: might be a JSON string containing an array
            String raw = outcomePrices.asText("");
            if (!raw.isBlank()) {
                JsonNode arr = objectMapper.readTree(raw);
                if (arr.isArray() && arr.size() > 0)
                    return arr.get(0).asDouble(-1);
            }
            return -1;
        } catch (Exception e) {
            log.debug("[Polymarket] Failed to parse outcomePrices: {}", e.getMessage());
            return -1;
        }
    }

    private String buildSummary(List<MarketInfo> markets, double score) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("宏觀風險分數=%.0f%%（關稅緩解/貿易協議概率加權平均）\n", score * 100));
        sb.append(String.format("共 %d 個相關市場：\n", markets.size()));
        for (int i = 0; i < Math.min(markets.size(), 5); i++) {
            MarketInfo m = markets.get(i);
            sb.append(String.format("  [%.0f%%] %s（vol=$%,d）\n",
                    m.yesProbability() * 100, m.question(), m.volume()));
        }
        return sb.toString().stripTrailing();
    }
}
