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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 從 alternative.me 取得 Crypto Fear & Greed Index（每日更新）。
 *
 * <p>指數說明：
 * <ul>
 *   <li>0~24  → Extreme Fear（恐慌）— 歷史上的買入機會</li>
 *   <li>25~49 → Fear</li>
 *   <li>50~74 → Greed</li>
 *   <li>75~100 → Extreme Greed（過熱）</li>
 * </ul>
 * </p>
 *
 * <p>快取 6 小時（資料每日更新一次，6h TTL 可在服務重啟後快速刷新）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FearGreedService {

    private static final String URL = "https://api.alternative.me/fng/?limit=1";
    /** 指數未知時的預設中性值 */
    private static final int DEFAULT_NEUTRAL = 50;

    private final ObjectMapper objectMapper;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    /**
     * 取得當前 Fear & Greed 指數（0-100）。
     * 快取 6 小時；API 失敗時回傳中性值 50，不影響訊號評估。
     */
    public record FearGreedEntry(long timestamp, int value, String classification) {}

    /**
     * 取得過去 N 天的 Fear & Greed 歷史（每日一筆）。
     * 最多 365 天；API 失敗時回傳空列表。
     */
    public List<FearGreedEntry> getHistoricalFearGreed(int days) {
        if (days <= 0 || days > 365) days = 90;
        String url = "https://api.alternative.me/fng/?limit=" + days;
        List<FearGreedEntry> result = new ArrayList<>();
        try {
            Request request = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return result;
                JsonNode root = objectMapper.readTree(response.body().string());
                for (JsonNode item : root.path("data")) {
                    long ts = item.path("timestamp").asLong(0);
                    int val = item.path("value").asInt(0);
                    String cls = item.path("value_classification").asText("Unknown");
                    result.add(new FearGreedEntry(ts, val, cls));
                }
            }
        } catch (Exception e) {
            log.warn("[FearGreed] Historical fetch failed: {}", e.getMessage());
        }
        return result;
    }

    @Cacheable("fearGreedIndex")
    public int getFearGreedValue() {
        try {
            Request request = new Request.Builder().url(URL).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("[FearGreed] API returned {}", response.code());
                    return DEFAULT_NEUTRAL;
                }
                JsonNode root = objectMapper.readTree(response.body().string());
                int value = root.path("data").path(0).path("value").asInt(DEFAULT_NEUTRAL);
                String classification = root.path("data").path(0).path("value_classification").asText("Unknown");
                log.info("[FearGreed] value={} ({})", value, classification);
                return value;
            }
        } catch (Exception e) {
            log.warn("[FearGreed] Failed to fetch, using neutral 50: {}", e.getMessage());
            return DEFAULT_NEUTRAL;
        }
    }
}
