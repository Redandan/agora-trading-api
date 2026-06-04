package com.agora.service.ai.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Jina AI Embedding 客戶端
 * <p>
 * 將文字轉換為向量，供 Chroma 向量資料庫使用。
 * 免費方案：1M tokens/月，無需信用卡
 * 申請 API Key：https://jina.ai/
 * <p>
 * Rate limiting (Issue #162):
 * - Semaphore 限制 process-wide 並發 Jina HTTP 請求數（預設 2），
 *   避免 free-tier burst 時 silently 200+empty 或 429。
 * - 429 偵測：自動 exponential backoff retry（最多 3 次：2s / 4s / 8s）。
 * - 非 429 失敗：立即返回 null（保持原本行為）。
 */
@Slf4j
@Component
public class JinaEmbeddingClient {

    private static final String JINA_EMBED_URL = "https://api.jina.ai/v1/embeddings";
    private static final String MODEL = "jina-embeddings-v3";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RETRY_ON_429 = 3;

    @Value("${jina.api.key:}")
    private String apiKey;

    @Value("${jina.rate-limit.max-concurrent:2}")
    private int maxConcurrent;

    /** Lazily initialised in {@link #initRateLimiter()} so {@link #maxConcurrent} is bound first. */
    private Semaphore jinaPermits;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Lazy @Autowired
    private com.agora.service.ai.AiTokenUsageService tokenUsageService;

    @PostConstruct
    void initRateLimiter() {
        int permits = Math.max(1, maxConcurrent);
        this.jinaPermits = new Semaphore(permits, true);
        log.info("Jina rate limiter initialised: max-concurrent={}", permits);
    }

    /**
     * 將單一文字轉換為向量
     *
     * @param text 輸入文字
     * @return 向量（float 陣列），失敗時回傳 null
     */
    public List<Float> embed(String text) {
        List<List<Float>> results = embedBatch(Collections.singletonList(text));
        if (results == null || results.isEmpty()) return null;
        return results.get(0);
    }

    /**
     * 批量將文字轉換為向量
     *
     * @param texts 輸入文字列表
     * @return 向量列表，失敗時回傳 null
     */
    public List<List<Float>> embedBatch(List<String> texts) {
        if (!isEnabled()) {
            log.warn("Jina API key 未設定，跳過 embedding");
            return null;
        }
        if (texts == null || texts.isEmpty()) return null;

        Map<String, Object> body = new HashMap<>();
        body.put("model", MODEL);
        body.put("input", texts);
        body.put("task", "retrieval.passage");

        return executeWithRateLimit(body, "embedBatch", root -> {
            JsonNode data = root.path("data");
            if (!data.isArray()) return null;
            List<List<Float>> result = new java.util.ArrayList<>();
            for (JsonNode item : data) {
                JsonNode embedding = item.path("embedding");
                List<Float> vector = new java.util.ArrayList<>();
                for (JsonNode val : embedding) {
                    vector.add((float) val.asDouble());
                }
                result.add(vector);
            }
            return result;
        });
    }

    /**
     * 查詢用 embedding（task 為 retrieval.query，效果更好）
     */
    public List<Float> embedQuery(String query) {
        if (!isEnabled()) return null;

        Map<String, Object> body = new HashMap<>();
        body.put("model", MODEL);
        body.put("input", Collections.singletonList(query));
        body.put("task", "retrieval.query");

        return executeWithRateLimit(body, "embedQuery", root -> {
            JsonNode embedding = root.path("data").path(0).path("embedding");
            List<Float> vector = new java.util.ArrayList<>();
            for (JsonNode val : embedding) {
                vector.add((float) val.asDouble());
            }
            return vector;
        });
    }

    public boolean isEnabled() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * 共用 HTTP 執行邏輯：semaphore + 429 backoff + token usage 記錄。
     */
    private <T> T executeWithRateLimit(Map<String, Object> body, String op, ResponseParser<T> parser) {
        try {
            jinaPermits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Jina {} 等待 semaphore 時被中斷", op);
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(body);
            RequestBody reqBody = RequestBody.create(json, JSON);

            for (int attempt = 1; attempt <= MAX_RETRY_ON_429; attempt++) {
                Request request = new Request.Builder()
                        .url(JINA_EMBED_URL)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .post(reqBody)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.code() == 429) {
                        long backoffMs = (1L << attempt) * 1000L; // 2s, 4s, 8s
                        if (attempt < MAX_RETRY_ON_429) {
                            log.warn("Jina {} 429 rate-limited，{}ms 後 retry (attempt {}/{})",
                                    op, backoffMs, attempt, MAX_RETRY_ON_429);
                            Thread.sleep(backoffMs);
                            continue;
                        }
                        log.error("Jina {} 429 已達 retry 上限 ({})，放棄", op, MAX_RETRY_ON_429);
                        tokenUsageService.record(MODEL, 0, 0, true);
                        return null;
                    }
                    if (!response.isSuccessful()) {
                        log.warn("Jina {} 回傳非成功狀態碼: {}", op, response.code());
                        tokenUsageService.record(MODEL, 0, 0, true);
                        return null;
                    }
                    ResponseBody responseBody = response.body();
                    if (responseBody == null) return null;

                    JsonNode root = objectMapper.readTree(responseBody.string());
                    int totalTok = root.path("usage").path("total_tokens").asInt(0);
                    tokenUsageService.record(MODEL, totalTok, 0, false);
                    return parser.parse(root);
                }
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Jina {} backoff 等待時被中斷", op);
            return null;
        } catch (IOException e) {
            tokenUsageService.record(MODEL, 0, 0, true);
            log.error("Jina {} IO 錯誤: {}", op, e.getMessage());
            return null;
        } catch (Exception e) {
            tokenUsageService.record(MODEL, 0, 0, true);
            log.error("Jina {} 未預期錯誤", op, e);
            return null;
        } finally {
            jinaPermits.release();
        }
    }

    @FunctionalInterface
    private interface ResponseParser<T> {
        T parse(JsonNode root);
    }
}
