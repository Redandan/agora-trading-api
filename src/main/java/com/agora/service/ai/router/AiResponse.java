package com.agora.service.ai.router;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * AI 任務執行結果(provider-agnostic)。
 *
 * @param text       生成的文字內容(provider 主要回應)
 * @param provider   實際處理的 provider 名稱(claude-sonnet / gemini-flash / ...)
 * @param model      具體模型 ID(claude-sonnet-4-6 等)
 * @param inputTokens / outputTokens  用於 cost tracking
 * @param costUsd    本次成本估算
 * @param latency    延遲(用於監控 / SLA)
 */
public record AiResponse(
        String text,
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        BigDecimal costUsd,
        Duration latency
) {}
