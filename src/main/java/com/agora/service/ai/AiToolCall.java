package com.agora.service.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * AI tool call 的共用資料型別，供 {@link AnthropicApiClient} 與 {@link GeminiApiClient} 共用。
 *
 * @param id    工具呼叫 ID（用於 tool_result 回傳配對）
 * @param name  工具名稱
 * @param input 工具參數（JSON object）
 */
public record AiToolCall(String id, String name, JsonNode input) {}
