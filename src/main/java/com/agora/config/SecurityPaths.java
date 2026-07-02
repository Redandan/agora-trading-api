package com.agora.config;

/**
 * 安全路徑配置
 * 定義系統中不需要認證的公共路徑
 */
public class SecurityPaths {

    /**
     * 允許未認證訪問的路徑
     * 包括：
     * - Swagger UI 相關路徑
     * - API 文檔路徑
     */
    public static final String[] ALLOWED_PATHS = {
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/v3/api-docs**",
            "/swagger-ui.html",
            "/mcp",                    // MCP Streamable HTTP endpoint; tool auth is enforced by McpApiKeyFilter
            "/mcp/**",                 // MCP AI 工具探索端點
            "/trading/internal/reports/**", // Internal API key is enforced by InternalTradingReportController
            "/tradingview/webhook",    // TradingView alert ingress; payload secret and dry-run gate are enforced by controller/service
            "/ratelimit",              // nginx error_page 429 internal redirect target（結構化 JSON + Retry-After）
            "/actuator/health",        // 匿名健康探針（liveness/readiness），details 走 when_authorized 機制
            "/actuator/health/**",
            "/actuator/info",          // 公開版本/構建資訊
            "/actuator/prometheus",    // Prometheus 抓取端點 — ActuatorAuthFilter 二次把關
            "/actuator/metrics",       // 指標總覽 — ActuatorAuthFilter 二次把關
            "/actuator/metrics/**",
            "/favicon.ico"     // 瀏覽器自動請求的 favicon
    };
}
