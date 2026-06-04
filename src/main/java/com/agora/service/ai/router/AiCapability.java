package com.agora.service.ai.router;

/**
 * AI Provider 的能力標籤。Router 可依 task 需求過濾 capable providers。
 */
public enum AiCapability {
    /** 支援 tool use / function calling。 */
    TOOL_USE,
    /** 支援 vision input。 */
    VISION,
    /** 大 context window(>= 100K tokens)。 */
    LARGE_CONTEXT,
    /** 流式 streaming output。 */
    STREAMING,
    /** 結構化 JSON mode 保證輸出合法 JSON。 */
    JSON_MODE,
    /** 本地部署(無外部 API call,零成本零延遲)。 */
    LOCAL
}
