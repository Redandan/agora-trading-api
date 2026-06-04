package com.agora.service.ai.router;

import java.math.BigDecimal;
import java.util.Set;

/**
 * AI Provider 統一介面。實作:ClaudeSonnetProvider / GeminiFlashProvider / OllamaProvider 等。
 *
 * <p><b>實作守則</b>:
 * <ul>
 *   <li>{@link #execute(AiTask)} 必須是 thread-safe(可能多策略並發呼叫)</li>
 *   <li>API 失敗應拋 {@link AiProviderException},router 才能 fallback 到次級 provider</li>
 *   <li>{@link #healthy()} 用於 router 健康檢查;若回 false 則跳過此 provider</li>
 *   <li>{@link #estimateCostUsd(AiTask)} 用於 budget guard 預先攔截</li>
 * </ul>
 */
public interface AiProvider {

    /** Bean 名稱對應 application.yml `ai.routing.<task>.primary` 值。 */
    String name();

    /** 預設模型 ID(顯示用,如 claude-sonnet-4-6)。 */
    String model();

    /** 此 provider 支援的能力。 */
    Set<AiCapability> capabilities();

    /** 是否健康(API key 設定 + 最近一次健康檢查通過)。 */
    boolean healthy();

    /**
     * 執行 task。失敗時 throw {@link AiProviderException} 讓 router 決定 fallback。
     */
    AiResponse execute(AiTask task);

    /** 預估成本(USD)。用於 budget guard。 */
    BigDecimal estimateCostUsd(AiTask task);

    // ========================================================================
    // 共用 exception
    // ========================================================================

    class AiProviderException extends RuntimeException {
        private final boolean retryable;
        public AiProviderException(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }
        public boolean isRetryable() { return retryable; }
    }
}
