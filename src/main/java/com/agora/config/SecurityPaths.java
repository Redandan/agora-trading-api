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
     * - 認證相關路徑（註冊、登入）
     */
    public static final String[] ALLOWED_PATHS = {
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/v3/api-docs**",
            "/public/**",
            "/swagger-ui.html",
            "/auth/**",        // 認證相關端點
            "/test/**",
            "/images/**",
            "/oci/notifications",
            "/telegram/webhook/**",    // Telegram Webhook 回調端點
            "/backtests/**",           // Backtest MVP 執行與查詢
            "/mcp/**",                 // MCP AI 工具探索端點
            "/admin/market/import",       // K 線歷史匯入（公開 Binance 資料，冪等操作）
            "/admin/market/backfill-oi",  // OI 歷史回填（一次性，localhost-only via SSH）
            "/admin/oco/**",            // OCO 手動重試（僅限 localhost，SSH 存取，無需 JWT）
            "/market/klines",          // K 線圖表資料查詢（前端展示用，無需認證）
            "/market/symbols",         // 可用交易對清單
            "/market/intervals",       // 可用週期清單
            "/market/ticker",          // 最新 K 線快照
            "/ratelimit",              // nginx error_page 429 internal redirect target（結構化 JSON + Retry-After）
            "/slot/symbols",           // Slot Symbol 目錄（前端載入圖片資源用，無需認證）
            "/slot/rtp",               // Slot 理論 RTP 設定表（公開資訊，無需認證）
            "/actuator/health",        // 匿名健康探針（liveness/readiness），details 走 when_authorized 機制
            "/actuator/health/**",
            "/actuator/info",          // 公開版本/構建資訊
            "/actuator/prometheus",    // Prometheus 抓取端點 — ActuatorAuthFilter 二次把關
            "/actuator/metrics",       // 指標總覽 — ActuatorAuthFilter 二次把關
            "/actuator/metrics/**",
            "/favicon.ico"     // 瀏覽器自動請求的 favicon
    };
}
