package com.agora.dto.ai;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Groq AI 使用量統計
 */
@Data
@Builder
public class GroqUsageStatsDTO {

    /** API 是否已啟用（有設定 key） */
    private boolean enabled;

    /** 目前使用的模型 */
    private String model;

    // ── 累積統計（自應用啟動起） ──────────────────────────

    /** 累積請求次數 */
    private long totalRequests;

    /** 累積使用 tokens（prompt + completion） */
    private long totalTokensUsed;

    /** 累積錯誤次數 */
    private long totalErrors;

    // ── 即時 Rate Limit（來自最後一次 API 回應的 headers） ──

    /** 每分鐘請求上限 */
    private Integer rateLimitRequestsPerMin;

    /** 每分鐘 token 上限 */
    private Integer rateLimitTokensPerMin;

    /** 當前分鐘剩餘請求數 */
    private Integer remainingRequestsPerMin;

    /** 當前分鐘剩餘 token 數 */
    private Integer remainingTokensPerMin;

    /** 請求數限制重置時間（Groq 原始字串，如 "2s"） */
    private String resetRequestsIn;

    /** Token 數限制重置時間 */
    private String resetTokensIn;

    /** 最後一次成功呼叫時間 */
    private LocalDateTime lastCallAt;
}
