package com.agora.service.ai;

import com.agora.dto.ai.GroqUsageStatsDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Groq AI API 客戶端
 * <p>
 * 使用 Groq 的免費 API（相容 OpenAI 格式），呼叫 LLaMA 等開源模型。
 * 免費方案：14,400 req/day，30 req/min
 * 申請 API Key：https://console.groq.com/
 */
@Slf4j
@Component
public class GroqApiClient {

    private static final String GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Value("${groq.api.key:}")
    private String apiKey;

    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String model;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)   // 整個呼叫最多 45 秒，防止無限阻塞
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Lazy @Autowired
    private AiTokenUsageService tokenUsageService;

    // ── 累積統計 ──────────────────────────────────────────
    private final AtomicLong totalRequests  = new AtomicLong();
    private final AtomicLong totalTokens    = new AtomicLong();
    private final AtomicLong totalErrors    = new AtomicLong();

    // ── 最後一次回應的 Rate Limit headers ─────────────────
    private volatile Integer lastLimitRequests;
    private volatile Integer lastLimitTokens;
    private volatile Integer lastRemainingRequests;
    private volatile Integer lastRemainingTokens;
    private volatile String  lastResetRequests;
    private volatile String  lastResetTokens;
    private volatile LocalDateTime lastCallAt;

    /**
     * 向 Groq 發送對話請求（使用預設 max_tokens=200、temperature=0.85）
     *
     * @param messages OpenAI 格式的訊息列表，每筆含 "role" 和 "content"
     * @return AI 回覆文字，發生錯誤時回傳 null
     */
    public String chat(List<Map<String, String>> messages) {
        return chat(messages, 200, 0.85);
    }

    /**
     * 向 Groq 發送對話請求（自訂 max_tokens 與 temperature）
     *
     * @param messages    OpenAI 格式的訊息列表，每筆含 "role" 和 "content"
     * @param maxTokens   最大回覆 token 數
     * @param temperature 生成溫度（0.0 ~ 2.0；結構化輸出建議使用 0.2 ~ 0.4）
     * @return AI 回覆文字，發生錯誤時回傳 null
     */
    public String chat(List<Map<String, String>> messages, int maxTokens, double temperature) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.warn("Groq API key 未設定，跳過 AI 回覆");
            return null;
        }

        totalRequests.incrementAndGet();
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("max_tokens", maxTokens);
            body.put("temperature", temperature);

            String json = objectMapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url(GROQ_CHAT_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                // 讀取 Rate Limit headers
                lastLimitRequests     = parseIntHeader(response, "x-ratelimit-limit-requests");
                lastLimitTokens       = parseIntHeader(response, "x-ratelimit-limit-tokens");
                lastRemainingRequests = parseIntHeader(response, "x-ratelimit-remaining-requests");
                lastRemainingTokens   = parseIntHeader(response, "x-ratelimit-remaining-tokens");
                lastResetRequests     = response.header("x-ratelimit-reset-requests");
                lastResetTokens       = response.header("x-ratelimit-reset-tokens");

                if (!response.isSuccessful()) {
                    totalErrors.incrementAndGet();
                    log.warn("Groq API 回傳非成功狀態碼: {}", response.code());
                    return null;
                }
                ResponseBody responseBody = response.body();
                if (responseBody == null) return null;

                JsonNode root = objectMapper.readTree(responseBody.string());

                // 累積 token 用量
                JsonNode usage = root.path("usage");
                int promptTok   = 0;
                int completeTok = 0;
                if (!usage.isMissingNode()) {
                    promptTok   = usage.path("prompt_tokens").asInt(0);
                    completeTok = usage.path("completion_tokens").asInt(0);
                    totalTokens.addAndGet(usage.path("total_tokens").asLong(0));
                }

                lastCallAt = LocalDateTime.now();
                tokenUsageService.record(model, promptTok, completeTok, false);
                String content = root.path("choices").path(0).path("message").path("content").asText(null);
                log.debug("Groq AI 回覆: {}", content);
                return content;
            }

        } catch (IOException e) {
            totalErrors.incrementAndGet();
            tokenUsageService.record(model, 0, 0, true);
            log.error("呼叫 Groq API 發生 IO 錯誤: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            totalErrors.incrementAndGet();
            tokenUsageService.record(model, 0, 0, true);
            log.error("呼叫 Groq API 發生未預期錯誤", e);
            return null;
        }
    }

    public boolean isEnabled() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    public String getModel() {
        return model;
    }

    /** ChatResult:text + token 用量,供 AiTaskRouter 使用。*/
    public record ChatResult(String text, int promptTokens, int completionTokens) {}

    /**
     * chat() 的 usage-tracking 版本,回傳 ChatResult 含 token 計數。
     * 供 router provider 用於 cost estimation + logging。
     */
    public ChatResult chatWithUsage(List<Map<String, String>> messages, int maxTokens, double temperature) {
        if (apiKey == null || apiKey.trim().isEmpty()) return null;
        totalRequests.incrementAndGet();
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("max_tokens", maxTokens);
            body.put("temperature", temperature);

            Request request = new Request.Builder()
                    .url(GROQ_CHAT_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                lastLimitRequests     = parseIntHeader(response, "x-ratelimit-limit-requests");
                lastLimitTokens       = parseIntHeader(response, "x-ratelimit-limit-tokens");
                lastRemainingRequests = parseIntHeader(response, "x-ratelimit-remaining-requests");
                lastRemainingTokens   = parseIntHeader(response, "x-ratelimit-remaining-tokens");
                lastResetRequests     = response.header("x-ratelimit-reset-requests");
                lastResetTokens       = response.header("x-ratelimit-reset-tokens");

                if (!response.isSuccessful()) {
                    totalErrors.incrementAndGet();
                    int code = response.code();
                    throw new RuntimeException("Groq API HTTP " + code);
                }
                ResponseBody responseBody = response.body();
                if (responseBody == null) throw new RuntimeException("Groq empty body");

                JsonNode root = objectMapper.readTree(responseBody.string());
                JsonNode usage = root.path("usage");
                int promptTok = usage.path("prompt_tokens").asInt(0);
                int completeTok = usage.path("completion_tokens").asInt(0);
                totalTokens.addAndGet(usage.path("total_tokens").asLong(0));
                lastCallAt = LocalDateTime.now();
                tokenUsageService.record(model, promptTok, completeTok, false);

                String text = root.path("choices").path(0).path("message").path("content").asText("");
                return new ChatResult(text, promptTok, completeTok);
            }
        } catch (Exception e) {
            totalErrors.incrementAndGet();
            tokenUsageService.record(model, 0, 0, true);
            throw new RuntimeException("Groq call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 判斷目前 API 配額是否充足
     * <p>
     * 以最後一次回應的 Rate Limit headers 為基準，計算 requests 與 tokens
     * 的剩餘比例，任一低於 minRemainingRatio 即視為不足。
     * 若尚未發出過任何請求（headers 為 null），預設視為充足。
     *
     * @param minRemainingRatio 最低剩餘比例，例如 0.3 代表 30%
     * @return true = 配額充足；false = 配額不足
     */
    public boolean hassufficientQuota(double minRemainingRatio) {
        if (lastLimitRequests == null || lastLimitRequests == 0) return true;
        if (lastLimitTokens == null || lastLimitTokens == 0) return true;

        double requestRatio = lastRemainingRequests != null
                ? (double) lastRemainingRequests / lastLimitRequests : 1.0;
        double tokenRatio = lastRemainingTokens != null
                ? (double) lastRemainingTokens / lastLimitTokens : 1.0;

        return requestRatio >= minRemainingRatio && tokenRatio >= minRemainingRatio;
    }

    /**
     * 回傳累積使用統計與即時 Rate Limit 資訊
     */
    public GroqUsageStatsDTO getUsageStats() {
        return GroqUsageStatsDTO.builder()
                .enabled(isEnabled())
                .model(model)
                .totalRequests(totalRequests.get())
                .totalTokensUsed(totalTokens.get())
                .totalErrors(totalErrors.get())
                .rateLimitRequestsPerMin(lastLimitRequests)
                .rateLimitTokensPerMin(lastLimitTokens)
                .remainingRequestsPerMin(lastRemainingRequests)
                .remainingTokensPerMin(lastRemainingTokens)
                .resetRequestsIn(lastResetRequests)
                .resetTokensIn(lastResetTokens)
                .lastCallAt(lastCallAt)
                .build();
    }

    private Integer parseIntHeader(Response response, String name) {
        String value = response.header(name);
        if (value == null) return null;
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return null; }
    }
}
