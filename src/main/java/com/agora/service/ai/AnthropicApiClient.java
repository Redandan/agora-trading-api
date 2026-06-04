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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Anthropic Claude API 客戶端。
 * <p>
 * 使用 Anthropic Messages API（非 OpenAI 相容端點），回應格式為 content[0].text。
 * 文件：https://docs.anthropic.com/en/api/messages
 */
@Slf4j
@Component
public class AnthropicApiClient {

    private static final String ANTHROPIC_CHAT_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION   = "2023-06-01";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Value("${anthropic.api.key:}")
    private String apiKey;

    @Value("${anthropic.model:claude-sonnet-4-6}")
    private String model;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Lazy @Autowired
    private AiTokenUsageService tokenUsageService;

    /**
     * 向 Claude 發送對話請求。
     *
     * @param messages    OpenAI 格式的訊息列表，每筆含 "role" 和 "content"
     *                    （system 訊息會自動轉換為 Anthropic system 參數）
     * @param maxTokens   最大回覆 token 數
     * @param temperature 生成溫度（0.0 ~ 1.0）
     * @return AI 回覆文字，未啟用或發生錯誤時回傳 null
     */
    public String chat(List<Map<String, String>> messages, int maxTokens, double temperature) {
        if (!isEnabled()) {
            log.debug("[Anthropic] API key 未設定，跳過呼叫");
            return null;
        }

        try {
            // 分離 system 訊息（Anthropic API 獨立處理 system）
            String systemContent = null;
            List<Map<String, String>> userMessages = new ArrayList<>();
            for (Map<String, String> msg : messages) {
                if ("system".equals(msg.get("role"))) {
                    systemContent = msg.get("content");
                } else {
                    userMessages.add(msg);
                }
            }

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", userMessages);
            body.put("max_tokens", maxTokens);
            body.put("temperature", temperature);
            if (systemContent != null) {
                body.put("system", systemContent);
            }

            String json = objectMapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url(ANTHROPIC_CHAT_URL)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "(empty)";
                    log.warn("[Anthropic] API 回傳非成功狀態碼: {} body={}", response.code(), errorBody);
                    return null;
                }
                ResponseBody responseBody = response.body();
                if (responseBody == null) return null;

                JsonNode root = objectMapper.readTree(responseBody.string());

                // Anthropic 回應格式：{ content: [{ type: "text", text: "..." }], usage: {...} }
                JsonNode usage = root.path("usage");
                int promptTok   = usage.path("input_tokens").asInt(0);
                int completeTok = usage.path("output_tokens").asInt(0);
                tokenUsageService.record(model, promptTok, completeTok, false);

                String content = root.path("content").path(0).path("text").asText(null);
                log.debug("[Anthropic] 回覆: {}", content);
                return content;
            }

        } catch (IOException e) {
            tokenUsageService.record(model, 0, 0, true);
            log.error("[Anthropic] IO 錯誤: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            tokenUsageService.record(model, 0, 0, true);
            log.error("[Anthropic] 未預期錯誤", e);
            return null;
        }
    }

    /**
     * 帶 Tool Use 的多輪對話（Agentic Loop）。
     *
     * <p>Claude 可在回覆中呼叫 {@code tools} 中定義的工具；每次 tool_use 回應都會執行
     * {@code toolExecutor}，並將結果作為 tool_result 繼續對話，直到 Claude 輸出
     * end_turn 或達到 {@code maxRounds} 輪上限。</p>
     *
     * @param messages      對話訊息，content 可為 String（user）或 List（assistant/tool_result）
     * @param tools         工具定義列表，格式：{name, description, input_schema}
     * @param toolExecutor  工具執行函數，接收 {@link ToolCall}，回傳結果字串（JSON）
     * @param maxTokens     最大回覆 token 數
     * @param temperature   生成溫度
     * @return Claude 最終文字回覆；API 不可用或出錯時回傳 null
     */
    public String chatWithTools(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            Function<AiToolCall, String> toolExecutor,
            int maxTokens,
            double temperature) {
        if (!isEnabled()) {
            log.debug("[Anthropic] API key 未設定，跳過 tool use 呼叫");
            return null;
        }

        // 分離 system 訊息
        String systemContent = null;
        List<Map<String, Object>> conv = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            if ("system".equals(msg.get("role"))) {
                systemContent = (String) msg.get("content");
            } else {
                conv.add(new HashMap<>(msg));
            }
        }

        for (int round = 0; round < 3; round++) {
            try {
                Map<String, Object> body = new HashMap<>();
                body.put("model", model);
                body.put("messages", conv);
                body.put("tools", tools);
                body.put("max_tokens", maxTokens);
                body.put("temperature", temperature);
                if (systemContent != null) body.put("system", systemContent);

                String json = objectMapper.writeValueAsString(body);
                Request request = new Request.Builder()
                        .url(ANTHROPIC_CHAT_URL)
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", ANTHROPIC_VERSION)
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(json, JSON))
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String errBody = response.body() != null ? response.body().string() : "(empty)";
                        log.warn("[Anthropic][ToolUse] HTTP {} body={}", response.code(), errBody);
                        return null;
                    }
                    JsonNode root = objectMapper.readTree(response.body().string());

                    JsonNode usage = root.path("usage");
                    tokenUsageService.record(model,
                            usage.path("input_tokens").asInt(0),
                            usage.path("output_tokens").asInt(0), false);

                    String stopReason = root.path("stop_reason").asText();
                    JsonNode contentArray = root.path("content");

                    if ("tool_use".equals(stopReason)) {
                        List<Map<String, Object>> assistantBlocks = new ArrayList<>();
                        List<Map<String, Object>> toolResultBlocks = new ArrayList<>();

                        for (JsonNode block : contentArray) {
                            String type = block.path("type").asText();
                            if ("text".equals(type)) {
                                assistantBlocks.add(Map.of("type", "text",
                                        "text", block.path("text").asText("")));
                            } else if ("tool_use".equals(type)) {
                                String toolId   = block.path("id").asText();
                                String toolName = block.path("name").asText();
                                JsonNode toolInput = block.path("input");

                                Map<String, Object> tuBlock = new HashMap<>();
                                tuBlock.put("type", "tool_use");
                                tuBlock.put("id", toolId);
                                tuBlock.put("name", toolName);
                                tuBlock.put("input", toolInput);
                                assistantBlocks.add(tuBlock);

                                log.debug("[Anthropic][ToolUse] round={} tool={} input={}",
                                        round, toolName, toolInput);
                                String result;
                                try {
                                    result = toolExecutor.apply(new AiToolCall(toolId, toolName, toolInput));
                                } catch (Exception e) {
                                    log.warn("[Anthropic][ToolUse] Tool {} error: {}", toolName, e.getMessage());
                                    result = "{\"error\":\"" + e.getMessage() + "\"}";
                                }
                                toolResultBlocks.add(Map.of(
                                        "type", "tool_result",
                                        "tool_use_id", toolId,
                                        "content", result));
                            }
                        }

                        conv.add(Map.of("role", "assistant", "content", assistantBlocks));
                        conv.add(Map.of("role", "user",      "content", toolResultBlocks));

                    } else {
                        // end_turn — 取文字回覆
                        for (JsonNode block : contentArray) {
                            if ("text".equals(block.path("type").asText())) {
                                String text = block.path("text").asText(null);
                                log.debug("[Anthropic][ToolUse] 最終回覆: {}", text);
                                return text;
                            }
                        }
                        return null;
                    }
                }

            } catch (IOException e) {
                tokenUsageService.record(model, 0, 0, true);
                log.error("[Anthropic][ToolUse] IO 錯誤 round={}: {}", round, e.getMessage());
                return null;
            } catch (Exception e) {
                tokenUsageService.record(model, 0, 0, true);
                log.error("[Anthropic][ToolUse] 未預期錯誤 round={}", round, e);
                return null;
            }
        }

        log.warn("[Anthropic][ToolUse] 達到最大輪數（3），強制 APPROVE");
        return "APPROVE (tool loop limit)";
    }

    public boolean isEnabled() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }
}
