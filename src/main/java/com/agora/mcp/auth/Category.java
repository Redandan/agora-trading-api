package com.agora.mcp.auth;

/**
 * MCP 工具功能分類。
 *
 * <p>MCP server 目前註冊 80+ 工具,全部 descriptor 會作為 Claude session prompt
 * token 載入(每次對話都付費)。分類讓未來客戶端可依需求只載入子集,也提供歸因 + 指標基礎。
 *
 * <p>語意概覽:
 * <ul>
 *   <li>{@link #READ_TRADING}  — 查詢交易/倉位/策略狀態,唯讀</li>
 *   <li>{@link #WRITE_TRADING} — 下單/啟用策略/建立 grid,會影響實際交易</li>
 *   <li>{@link #ANALYTICS}     — 策略/倉位後分析、回測、歸因</li>
 *   <li>{@link #GOVERNANCE}    — Meta-Control 介入:PAUSE、Hint override、Attention rule、標註</li>
 *   <li>{@link #MARKET_DATA}   — 市況與外部指標:F&amp;G、funding、Polymarket、K 線</li>
 *   <li>{@link #MODEL_OPS}     — ML/模型訓練相關(預留,目前空)</li>
 *   <li>{@link #DIAGNOSTIC}    — 驗證/健康檢查:validate*、verifyStrategyExecution</li>
 *   <li>{@link #REPORTING}     — 定期報告:daily/weekly/current report</li>
 *   <li>{@link #META}          — 系統層:AI router、reminder、session brief、scheduler list</li>
 * </ul>
 */
public enum Category {
    /** 查詢倉位/餘額/策略狀態/訂單歷史等唯讀操作。 */
    READ_TRADING,

    /** 會下單或改變策略/grid 狀態的操作(enableStrategy、createGrid、pauseStrategy 等)。 */
    WRITE_TRADING,

    /** 回測、歸因、策略比較、trade 分析等量化分析。 */
    ANALYTICS,

    /** Meta-Control 層:override、attention rule、position annotation、flip review。 */
    GOVERNANCE,

    /** 市場外部資料:F&amp;G、funding rate、whale ratio、Polymarket、K 線品質等。 */
    MARKET_DATA,

    /** ML / 模型訓練 / inference 管道(預留,Phase 3 起啟用)。 */
    MODEL_OPS,

    /** 驗證與診斷:walkForward、robustness、verifyStrategyExecution、system health。 */
    DIAGNOSTIC,

    /** 定期業績報告:daily / weekly / current report、quota 檢查。 */
    REPORTING,

    /** 系統層工具:AI provider list、reminder、session brief、scheduler meta。 */
    META,

    // ==========================================================================
    // Marketplace D2C Operations (added 2026-04-18 for digital procurement store)
    // ==========================================================================

    /** 託管錢包運營:餘額查詢/冷錢包池管理/充值監控/提現處理。 */
    WALLET_OPS,

    /** D2C 商店運營:訂單/商品/爭議/公告/health check。 */
    STORE_OPS,

    /** 數位商品採購:供應商管理/代購紀錄/COGS 追蹤。 */
    SOURCING_OPS,

    /** 反洗錢/反詐欺:大額訂單 review/可疑模式/標記買家。 */
    RISK_OPS,

    /** TG GTM 成長:bot 對話統計/知識庫管理/促銷/群歸因。 */
    GROWTH_OPS,

    /** 平台合作賣家管理:標記/列表/SoLR 訂單轉移。 */
    FOUNDER_SELLER
}
