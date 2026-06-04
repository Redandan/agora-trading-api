package com.agora.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Google Gemini AI API 客戶端
 * <p>
 * 使用 Gemini 的 OpenAI 相容端點，呼叫 gemini-2.5-flash 等模型。
 * 文件：https://ai.google.dev/gemini-api/docs/openai
 */
@Slf4j
@Component
public class GeminiApiClient {

    private static final String GEMINI_CHAT_URL =
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.model:models/gemini-2.5-flash}")
    private String model;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(70, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Lazy @Autowired
    private AiTokenUsageService tokenUsageService;

    // ── 最後一次回應的 Rate Limit headers ─────────────────
    private volatile Integer lastLimitRequests;
    private volatile Integer lastLimitTokens;
    private volatile Integer lastRemainingRequests;
    private volatile Integer lastRemainingTokens;

    /**
     * Verbose chat 結果(供 AiTaskRouter 用,需要 token usage + failure 資訊)。
     */
    public record ChatResult(String text, int promptTokens, int completionTokens) {}

    /**
     * 與 {@link #chat} 等價但回傳 {@link ChatResult} 含 token 用量,且失敗時 throw RuntimeException
     * (router 需要 throw 才能 fallback 到次級 provider)。
     *
     * <p>Caller 可指定 {@code modelOverride}(例如 router 想用 gemini-2.0-flash 避免 2.5 thinking
     * 吃 maxTokens),null 則用 {@link #model} 全域值。
     */
    public ChatResult chatWithUsage(List<Map<String, String>> messages, String modelOverride,
                                      int maxTokens, double temperature) {
        if (!isEnabled()) {
            throw new RuntimeException("Gemini API key 未設定");
        }
        String useModel = (modelOverride != null && !modelOverride.isBlank()) ? modelOverride : model;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", useModel);
            body.put("messages", messages);
            body.put("max_tokens", maxTokens);
            body.put("temperature", temperature);
            String json = objectMapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url(GEMINI_CHAT_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "(empty)";
                    throw new RuntimeException("Gemini HTTP " + response.code() + ": " + err);
                }
                ResponseBody rb = response.body();
                if (rb == null) throw new RuntimeException("Gemini empty response body");
                JsonNode root = objectMapper.readTree(rb.string());

                JsonNode usage = root.path("usage");
                int promptTok   = usage.path("prompt_tokens").asInt(0);
                int completeTok = usage.path("completion_tokens").asInt(0);
                tokenUsageService.record(useModel, promptTok, completeTok, false);

                String finishReason = root.path("choices").path(0).path("finish_reason").asText("");
                if ("length".equals(finishReason)) {
                    throw new RuntimeException("Gemini finish_reason=length，回覆被截斷");
                }
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                return new ChatResult(content, promptTok, completeTok);
            }
        } catch (RuntimeException re) {
            tokenUsageService.record(useModel, 0, 0, true);
            throw re;
        } catch (IOException e) {
            tokenUsageService.record(useModel, 0, 0, true);
            throw new RuntimeException("Gemini IO error: " + e.getMessage(), e);
        }
    }

    /**
     * 向 Gemini 發送對話請求(吞錯誤版本,失敗回 null)。
     * 既有 caller 用此 method;新 caller 應改用 {@link #chatWithUsage}。
     *
     * @param messages    OpenAI 格式的訊息列表，每筆含 "role" 和 "content"
     * @param maxTokens   最大回覆 token 數
     * @param temperature 生成溫度（0.0 ~ 2.0；結構化輸出建議 0.2 ~ 0.4）
     * @return AI 回覆文字，發生錯誤時回傳 null
     */
    public String chat(List<Map<String, String>> messages, int maxTokens, double temperature) {
        if (!isEnabled()) {
            log.warn("[Gemini] API key 未設定，跳過 AI 回覆");
            return null;
        }

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("max_tokens", maxTokens);
            body.put("temperature", temperature);

            String json = objectMapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url(GEMINI_CHAT_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                lastLimitRequests     = parseIntHeader(response, "x-ratelimit-limit-requests");
                lastLimitTokens       = parseIntHeader(response, "x-ratelimit-limit-tokens");
                lastRemainingRequests = parseIntHeader(response, "x-ratelimit-remaining-requests");
                lastRemainingTokens   = parseIntHeader(response, "x-ratelimit-remaining-tokens");

                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "(empty)";
                    log.warn("[Gemini] API 回傳非成功狀態碼: {} body={}", response.code(), errorBody);
                    return null;
                }
                ResponseBody responseBody = response.body();
                if (responseBody == null) return null;

                JsonNode root = objectMapper.readTree(responseBody.string());

                JsonNode usage = root.path("usage");
                int promptTok   = usage.path("prompt_tokens").asInt(0);
                int completeTok = usage.path("completion_tokens").asInt(0);
                tokenUsageService.record(model, promptTok, completeTok, false);

                String finishReason = root.path("choices").path(0).path("finish_reason").asText("");
                if ("length".equals(finishReason)) {
                    log.warn("[Gemini] 回覆被 maxTokens 截斷 (finish_reason=length)，回傳 null 觸發 fallback");
                    return null;
                }
                String content = root.path("choices").path(0).path("message").path("content").asText(null);
                log.debug("[Gemini] 回覆: {}", content);
                return content;
            }

        } catch (IOException e) {
            tokenUsageService.record(model, 0, 0, true);
            log.error("[Gemini] IO 錯誤: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            tokenUsageService.record(model, 0, 0, true);
            log.error("[Gemini] 未預期錯誤", e);
            return null;
        }
    }

    /**
     * 多模態對話:可附圖片 URL 讓 Gemini vision 讀圖。
     *
     * <p>OpenAI-compat 多模態 message format:
     * <pre>
     * {"role":"user","content":[
     *   {"type":"text","text":"..."},
     *   {"type":"image_url","image_url":{"url":"https://..."}}
     * ]}
     * </pre>
     *
     * @param systemPrompt 系統提示(JSON schema 指令等)
     * @param userText     使用者文字輸入
     * @param imageUrls    圖片 URL 列表(Gemini 會 fetch + decode),可空
     * @param maxTokens    最大回覆 token
     * @param temperature  生成溫度
     * @return 回覆文字,失敗回 null
     */
    @SuppressWarnings("unchecked")
    public String chatMultimodal(String systemPrompt, String userText, List<String> imageUrls,
                                 int maxTokens, double temperature) {
        if (!isEnabled()) {
            log.warn("[Gemini] API key 未設定,跳過多模態呼叫");
            return null;
        }
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                Map<String, Object> sys = new LinkedHashMap<>();
                sys.put("role", "system");
                sys.put("content", systemPrompt);
                messages.add(sys);
            }

            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            List<Map<String, Object>> parts = new ArrayList<>();
            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "text");
            textPart.put("text", userText == null ? "" : userText);
            parts.add(textPart);
            if (imageUrls != null) {
                for (String url : imageUrls) {
                    if (url == null || url.isBlank()) continue;
                    Map<String, Object> imgPart = new LinkedHashMap<>();
                    imgPart.put("type", "image_url");
                    Map<String, Object> imgRef = new LinkedHashMap<>();
                    imgRef.put("url", url);
                    imgPart.put("image_url", imgRef);
                    parts.add(imgPart);
                }
            }
            userMsg.put("content", parts);
            messages.add(userMsg);

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("max_tokens", maxTokens);
            body.put("temperature", temperature);

            String json = objectMapper.writeValueAsString(body);
            Request request = new Request.Builder()
                    .url(GEMINI_CHAT_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "(empty)";
                    log.warn("[Gemini] multimodal 非成功狀態: {} body={}", response.code(), err);
                    return null;
                }
                ResponseBody rb = response.body();
                if (rb == null) return null;
                JsonNode root = objectMapper.readTree(rb.string());
                JsonNode usage = root.path("usage");
                int promptTok = usage.path("prompt_tokens").asInt(0);
                int completeTok = usage.path("completion_tokens").asInt(0);
                tokenUsageService.record(model, promptTok, completeTok, false);
                return root.path("choices").path(0).path("message").path("content").asText(null);
            }
        } catch (IOException e) {
            tokenUsageService.record(model, 0, 0, true);
            log.error("[Gemini] multimodal IO 錯誤: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            tokenUsageService.record(model, 0, 0, true);
            log.error("[Gemini] multimodal 未預期錯誤", e);
            return null;
        }
    }

    /**
     * 向 Gemini 發送帶 function calling 的 agentic 對話請求。
     *
     * <p>工具格式使用 Anthropic {@code input_schema} 格式傳入，內部自動轉換為 OpenAI function 格式。
     * 支援最多 5 輪工具呼叫迴圈，達到上限後回傳 "APPROVE (tool loop limit)"。
     *
     * @param messages      OpenAI 格式訊息列表（role/content）
     * @param anthropicTools Anthropic 格式工具定義（含 input_schema 欄位）
     * @param toolExecutor  工具執行器，接收 {@link AiToolCall}，回傳執行結果字串
     * @param maxTokens     最大回覆 token 數
     * @param temperature   生成溫度
     * @return Gemini 最終文字回覆；API 不可用或出錯時回傳 null
     */
    public String chatWithTools(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> anthropicTools,
            Function<AiToolCall, String> toolExecutor,
            int maxTokens,
            double temperature) {
        if (!isEnabled()) {
            log.debug("[Gemini] API key 未設定，跳過 tool use 呼叫");
            return null;
        }

        // 將 Anthropic tool 格式 (input_schema) 轉換為 OpenAI function 格式 (parameters)
        List<Map<String, Object>> openAiTools = new ArrayList<>();
        for (Map<String, Object> t : anthropicTools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", t.get("name"));
            fn.put("description", t.getOrDefault("description", ""));
            fn.put("parameters", t.getOrDefault("input_schema",
                    Map.of("type", "object", "properties", Map.of())));
            openAiTools.add(Map.of("type", "function", "function", fn));
        }

        List<Map<String, Object>> conv = new ArrayList<>(messages);

        for (int round = 0; round < 5; round++) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put("messages", conv);
                if (!openAiTools.isEmpty()) body.put("tools", openAiTools);
                body.put("max_tokens", maxTokens);
                body.put("temperature", temperature);

                String json = objectMapper.writeValueAsString(body);
                Request request = new Request.Builder()
                        .url(GEMINI_CHAT_URL)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(json, JSON))
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String errBody = response.body() != null ? response.body().string() : "(empty)";
                        log.warn("[Gemini][Tools] HTTP {} body={}", response.code(), errBody);
                        return null;
                    }
                    JsonNode root = objectMapper.readTree(response.body().string());

                    JsonNode usage = root.path("usage");
                    tokenUsageService.record(model,
                            usage.path("prompt_tokens").asInt(0),
                            usage.path("completion_tokens").asInt(0), false);

                    JsonNode choice  = root.path("choices").path(0);
                    String finish    = choice.path("finish_reason").asText("");
                    JsonNode message = choice.path("message");

                    if ("tool_calls".equals(finish)) {
                        // 把 assistant 訊息（含 tool_calls）加入對話
                        Map<String, Object> assistantMsg = new LinkedHashMap<>();
                        assistantMsg.put("role", "assistant");
                        assistantMsg.put("content", message.path("content").isNull()
                                ? null : message.path("content").asText(null));

                        List<Map<String, Object>> tcList = new ArrayList<>();
                        for (JsonNode tc : message.path("tool_calls")) {
                            Map<String, Object> fn = new LinkedHashMap<>();
                            fn.put("name", tc.path("function").path("name").asText());
                            fn.put("arguments", tc.path("function").path("arguments").asText("{}"));
                            Map<String, Object> tcMap = new LinkedHashMap<>();
                            tcMap.put("id", tc.path("id").asText());
                            tcMap.put("type", "function");
                            tcMap.put("function", fn);
                            tcList.add(tcMap);
                        }
                        assistantMsg.put("tool_calls", tcList);
                        conv.add(assistantMsg);

                        // 執行每個工具並回傳結果
                        for (JsonNode tc : message.path("tool_calls")) {
                            String toolId   = tc.path("id").asText();
                            String toolName = tc.path("function").path("name").asText();
                            String argsStr  = tc.path("function").path("arguments").asText("{}");
                            JsonNode argsNode;
                            try {
                                argsNode = objectMapper.readTree(argsStr);
                            } catch (Exception e) {
                                argsNode = objectMapper.createObjectNode();
                            }

                            log.debug("[Gemini][Tools] round={} tool={} args={}", round, toolName, argsStr);
                            String result;
                            try {
                                result = toolExecutor.apply(new AiToolCall(toolId, toolName, argsNode));
                            } catch (Exception e) {
                                log.warn("[Gemini][Tools] Tool {} error: {}", toolName, e.getMessage());
                                result = "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
                            }

                            Map<String, Object> toolResultMsg = new LinkedHashMap<>();
                            toolResultMsg.put("role", "tool");
                            toolResultMsg.put("tool_call_id", toolId);
                            toolResultMsg.put("content", result);
                            conv.add(toolResultMsg);
                        }
                        // 繼續下一輪

                    } else {
                        // stop / length 等 — 回傳最終文字
                        String content = message.path("content").asText(null);
                        log.debug("[Gemini][Tools] 最終回覆: {}", content);
                        return content;
                    }
                }

            } catch (IOException e) {
                tokenUsageService.record(model, 0, 0, true);
                log.error("[Gemini][Tools] IO 錯誤 round={}: {}", round, e.getMessage());
                return null;
            } catch (Exception e) {
                tokenUsageService.record(model, 0, 0, true);
                log.error("[Gemini][Tools] 未預期錯誤 round={}", round, e);
                return null;
            }
        }

        log.warn("[Gemini][Tools] 達到最大輪數（5），強制 APPROVE");
        return "APPROVE (tool loop limit)";
    }

    public boolean isEnabled() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    public Integer getRemainingRequestsPerMin() { return lastRemainingRequests; }
    public Integer getRemainingTokensPerMin()   { return lastRemainingTokens; }
    public Integer getLimitRequestsPerMin()     { return lastLimitRequests; }
    public Integer getLimitTokensPerMin()       { return lastLimitTokens; }

    private Integer parseIntHeader(Response response, String name) {
        String value = response.header(name);
        if (value == null) return null;
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return null; }
    }
}
