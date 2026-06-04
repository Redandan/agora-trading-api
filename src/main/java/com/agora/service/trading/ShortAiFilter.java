package com.agora.service.trading;

import com.agora.service.ai.AiStrategyDiscoveryService;
import com.agora.service.ai.AiToolCall;
import com.agora.service.ai.GeminiApiClient;
import com.agora.service.ai.LocalMcpClient;
import com.agora.service.ai.router.AiTask;
import com.agora.service.ai.router.AiTaskRouter;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.market.EventCalendarService;
import com.agora.service.market.FearGreedService;
import com.agora.service.market.OrderbookImbalanceService;
import com.agora.service.market.PolymarketService;
import com.agora.service.market.WhaleFlowService;
import com.agora.config.properties.OrderbookImbalanceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * SHORT 進場多層防護過濾器。
 *
 * <p><b>Layer 1（確定性規則，主要防線）</b>：恐懼貪婪指數、4h 趨勢方向、RSI 超賣、鯨魚流量。
 * 規則可回測，無 I/O 延遲，不受 shadow/active 模式影響。
 *
 * <p><b>Layer 2（雙重 Gemini 共識）</b>：
 * <ul>
 *   <li>Gemini Fast：prompt-only，快速保守反制（150 tokens）</li>
 *   <li>Gemini Agentic：具備 tool use，透過 {@link LocalMcpClient} 調用本地 MCP 工具，
 *       可主動查詢回測歷史、市場快照、持倉狀態，再輸出 APPROVE/REJECT</li>
 * </ul>
 * 兩者皆 APPROVE 才通過。在 {@code shadow} 模式下僅記錄決定，不攔截交易。
 *
 * <p><b>Gemini Agentic 可用的 MCP 工具（read-only 白名單）</b>：
 * <ul>
 *   <li>{@code getBacktestHistory(strategyId)} — 查策略歷史回測空頭績效</li>
 *   <li>{@code getMarketSnapshot(symbol, intervalCode)} — 取當前 K 線快照</li>
 *   <li>{@code analyzeMarket(symbol, intervalCode)} — 雙時框市場形態分析</li>
 *   <li>{@code getCurrentReport} — 查當前開倉狀態</li>
 *   <li>{@code listStrategies} — 列出所有啟用中策略</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortAiFilter {

    /** Read-only MCP 工具白名單（危險工具如 enableStrategy、cleanupStrategies 不在其中）。 */
    private static final Set<String> SHORT_FILTER_TOOLS = Set.of(
            "getBacktestHistory",
            "getMarketSnapshot",
            "analyzeMarket",
            "getCurrentReport",
            "listStrategies"
    );

    private final AiStrategyDiscoveryService aiDiscoveryService;
    private final FearGreedService fearGreedService;
    private final WhaleFlowService whaleFlowService;
    private final PolymarketService polymarketService;
    private final OkxTradingService okxTradingService;
    private final EventCalendarService eventCalendarService;
    private final OrderbookImbalanceService orderbookImbalanceService;
    private final GeminiApiClient geminiApiClient;
    private final LocalMcpClient localMcpClient;
    private final NotificationPort notificationPort;
    private final AiTaskRouter aiTaskRouter;
    private final com.agora.config.properties.ShortAiFilterProperties props;
    private final OrderbookImbalanceProperties orderbookProps;

    public record FilterResult(boolean allowed, String reason) {}

    /**
     * 對指定幣種執行多層 SHORT 過濾檢查。
     *
     * @param symbol        交易對（如 BTCUSDT）
     * @param intervalCode  1h 訊號週期
     * @param currentRsi    當前 RSI 值（來自 LiveSignalContext.Snapshot）
     * @return FilterResult.allowed=false 時應跳過做空
     */
    public FilterResult check(String symbol, String intervalCode, double currentRsi) {
        if (!props.enabled()) return new FilterResult(true, "filter disabled");

        // ── Layer 1：確定性規則（永遠有效，不受 shadow/active 影響）──
        FilterResult rule = checkRules(symbol, currentRsi);
        if (!rule.allowed()) return rule;

        // ── Layer 2：雙重 Gemini 共識 ──
        FilterResult ai = checkAiConsensus(symbol, intervalCode);

        if ("shadow".equalsIgnoreCase(props.mode())) {
            log.info("[ShortAiFilter][SHADOW] symbol={} ai={} reason={}",
                    symbol, ai.allowed() ? "APPROVE" : "REJECT", ai.reason());
            return new FilterResult(true, "shadow: " + ai.reason());
        }

        return ai;
    }

    // ─── Layer 1: 確定性規則 ────────────────────────────────────────────────

    private FilterResult checkRules(String symbol, double rsi) {
        // 0. 事件日曆封鎖（FOMC / CPI 等高影響事件窗口）
        EventCalendarService.BlockResult evt = eventCalendarService.checkBlock();
        if (evt.blocked()) {
            long h = evt.timeToEvent().toHours();
            String when = h > 0 ? String.format("%d 小時後", h) : String.format("%d 小時前已發生", -h);
            return new FilterResult(false,
                    String.format("事件窗口封鎖：%s（%s），禁止做空", evt.event().name(), when));
        }

        // 先取 4h 快照：F&G 與 4h 趨勢規則要合併判斷
        AiStrategyDiscoveryService.MarketSnapshot snap4h = null;
        try {
            snap4h = aiDiscoveryService.buildMarketSnapshot(symbol, "4h");
        } catch (Exception e) {
            log.warn("[ShortAiFilter] 4h snapshot failed for {}: {}", symbol, e.getMessage());
        }

        // 恐懼貪婪 < 25 + 4h 非空頭延續 → 恐慌反彈情境禁止做空（可能被軋空）
        // 若 4h 明確 BEARISH 且 MACD 柱狀 ≤ 0，允許順勢 SHORT（熊市持續）
        int fg = fearGreedService.getFearGreedValue();
        if (fg < 25) {
            boolean bearishTrend = snap4h != null
                    && "BEARISH".equals(snap4h.trendDirection())
                    && snap4h.macdHistogram() <= 0;
            if (!bearishTrend) {
                return new FilterResult(false, String.format(
                        "Fear&Greed=%d（<25）且 4h 非延續空頭（可能為恐慌反彈，禁止做空）", fg));
            }
            log.debug("[ShortAiFilter] F&G={} + 4h BEARISH → allow trend-following SHORT", fg);
        }

        // 4h 大時框偏多：禁止做空（與 F&G 規則分離）
        if (snap4h != null) {
            if ("BULLISH".equals(snap4h.trendDirection()))
                return new FilterResult(false,
                        "4h 趨勢=BULLISH（大時框偏多，price > EMA20）");
            if (snap4h.macdHistogram() > 0)
                return new FilterResult(false,
                        String.format("4h MACD柱狀=%.4f（動能偏多，禁止做空）", snap4h.macdHistogram()));
        }

        // RSI < 35 = 超賣，不在此時做空
        if (rsi < 35)
            return new FilterResult(false,
                    String.format("RSI=%.1f（≤35 超賣，不適合做空）", rsi));

        // 鯨魚買入比率 > 65% = 大戶持續買入
        double whale = whaleFlowService.getBuyRatio(symbol);
        if (whale > 0.65)
            return new FilterResult(false,
                    String.format("鯨魚買入=%.0f%%（>65%% 大戶持續買入）", whale * 100));

        // Polymarket 宏觀風險：關稅暫停/貿易協議概率過高時禁止做空
        try {
            PolymarketService.MacroRiskResult macro = polymarketService.getMacroRisk();
            if (macro.riskScore() >= props.macroRiskThreshold())
                return new FilterResult(false,
                        String.format("Polymarket 宏觀風險=%.0f%%（≥%.0f%% 關稅緩解/貿易協議概率，禁止做空）",
                                macro.riskScore() * 100, props.macroRiskThreshold() * 100));
        } catch (Exception e) {
            log.warn("[ShortAiFilter] Polymarket check failed, skipping: {}", e.getMessage());
        }

        // 與 F&G 規則相同邏輯：持續空頭趨勢下，資金費率/多空比的極端是結構性正常
        boolean bearishContinuation = snap4h != null
                && "BEARISH".equals(snap4h.trendDirection())
                && snap4h.macdHistogram() <= 0;

        // 資金費率 < 閾值 = 空頭付費，市場已過度偏空 → 擠壓風險（僅在非延續空頭時攔）
        try {
            double fundingRate = okxTradingService.getCurrentFundingRate(symbol);
            if (fundingRate < props.fundingRateThreshold() && !bearishContinuation)
                return new FilterResult(false,
                        String.format("資金費率=%.4f%%（<%.4f%%）且 4h 非延續空頭（擠壓風險，禁止做空）",
                                fundingRate * 100, props.fundingRateThreshold() * 100));
        } catch (Exception e) {
            log.warn("[ShortAiFilter] FundingRate check failed, skipping: {}", e.getMessage());
        }

        // 多空帳戶比率 < 閾值 = 空頭過多 → 擠壓風險（僅在非延續空頭時攔）
        try {
            double lsRatio = okxTradingService.getLongShortRatio(symbol);
            if (lsRatio > 0 && lsRatio < props.longShortRatioThreshold() && !bearishContinuation)
                return new FilterResult(false,
                        String.format("多空帳戶比率=%.2f（<%.2f）且 4h 非延續空頭（擠壓風險，禁止做空）",
                                lsRatio, props.longShortRatioThreshold()));
        } catch (Exception e) {
            log.warn("[ShortAiFilter] LongShortRatio check failed, skipping: {}", e.getMessage());
        }

        // Orderbook imbalance > 閾值 = 買牆堆積（可能即將上衝）→ 禁止做空
        // 瞬時訂單流信號，不受趨勢 gate 影響（同 whale 規則設計）
        try {
            double imbalance = orderbookImbalanceService.getImbalance(symbol);
            if (imbalance > orderbookProps.threshold())
                return new FilterResult(false,
                        String.format("Orderbook imbalance=%+.2f（>%.2f 買牆堆積，即將上衝風險）",
                                imbalance, orderbookProps.threshold()));
        } catch (Exception e) {
            log.warn("[ShortAiFilter] Orderbook imbalance check failed, skipping: {}", e.getMessage());
        }

        return new FilterResult(true, "Layer1 rules passed");
    }

    // ─── Layer 2: 雙重 Gemini 共識 ────────────────────────────────────────────

    private FilterResult checkAiConsensus(String symbol, String intervalCode) {
        String prompt = buildShortPrompt(symbol, intervalCode);
        if (prompt == null)
            return new FilterResult(true, "snapshot unavailable, fallback allow");

        // Gemini Fast：prompt-only，透過 AiTaskRouter(含 fallback + budget guard)
        CompletableFuture<String> fastF = CompletableFuture.supplyAsync(() -> {
            try {
                return aiTaskRouter.execute(
                        new AiTask.GenericPrompt("short-ai-filter-fast", prompt, 800)).text();
            } catch (Exception e) {
                log.warn("[ShortAiFilter] fast call failed: {}", e.getMessage());
                return "APPROVE (router error)";
            }
        });

        // Gemini Agentic：tool use，透過 LocalMcpClient 調用本地 MCP 工具
        CompletableFuture<String> agenticF = CompletableFuture.supplyAsync(() -> {
            if (!geminiApiClient.isEnabled()) return "APPROVE (Gemini disabled)";
            try {
                List<Map<String, Object>> messages = List.of(
                        Map.of("role", "user", "content", prompt));
                List<Map<String, Object>> tools = localMcpClient.getToolSchemas(SHORT_FILTER_TOOLS);
                return geminiApiClient.chatWithTools(
                        messages, tools, this::executeMcpTool, 1000, 0.1);
            } catch (Exception e) {
                log.warn("[ShortAiFilter] Gemini agentic call failed: {}", e.getMessage());
                return "APPROVE (Gemini agentic error)";
            }
        });

        String fastReply    = fastF.join();
        String agenticReply = agenticF.join();

        boolean fastOk    = fastReply    == null || fastReply.toUpperCase().contains("APPROVE");
        boolean agenticOk = agenticReply == null || agenticReply.toUpperCase().contains("APPROVE");

        // 降級偵測：reply 以「APPROVE (」開頭即為 try-catch fallback 而非真實 AI 判定
        boolean fastDowngraded    = fastReply    != null && fastReply.startsWith("APPROVE (");
        boolean agenticDowngraded = agenticReply != null && agenticReply.startsWith("APPROVE (Gemini");
        boolean bothDowngraded    = fastDowngraded && agenticDowngraded;

        log.debug("[ShortAiFilter] Layer2 symbol={} GeminiFast={} GeminiAgentic={}",
                symbol, fastOk ? "APPROVE" : "REJECT", agenticOk ? "APPROVE" : "REJECT");

        if (bothDowngraded) {
            // 兩個 AI 同時失效是可觀測性漏洞 — 立即 TG 警告
            log.warn("[ShortAiFilter] ⚠️ BOTH Gemini calls downgraded for {} — AI layer offline in critical moment",
                    symbol);
            try {
                notificationPort.broadcast(String.format(
                        "⚠️ <b>AI 層降級</b>\n%s SHORT 評估時 Gemini Fast + Agentic 皆失敗（rate limit / timeout），Layer 2 暫時離線。\nFast: %s\nAgentic: %s",
                        symbol, trimReply(fastReply), trimReply(agenticReply)), true);
            } catch (Exception ignored) {}
        } else if (fastDowngraded || agenticDowngraded) {
            log.warn("[ShortAiFilter] Layer2 partial downgrade for {}: fast={} agentic={}",
                    symbol, fastDowngraded, agenticDowngraded);
        }

        if (!fastOk || !agenticOk) {
            String reason = String.format(
                    "AI 共識未達：GeminiFast=%s GeminiAgentic=%s\nFast: %s\nAgentic: %s",
                    fastOk ? "✅" : "❌", agenticOk ? "✅" : "❌",
                    trimReply(fastReply), trimReply(agenticReply));
            return new FilterResult(false, reason);
        }

        String passReason = bothDowngraded
                ? "Layer2 passed: BOTH_AI_DOWNGRADED (Gemini unavailable, fallback allow)"
                : (fastDowngraded || agenticDowngraded
                        ? "Layer2 passed: AI_PARTIAL_DOWNGRADE (" +
                                (fastDowngraded ? "fast " : "") +
                                (agenticDowngraded ? "agentic" : "") + ")"
                        : "Layer2 passed: GeminiFast✅ GeminiAgentic✅");
        return new FilterResult(true, passReason);
    }

    // ─── MCP Tool Executor ────────────────────────────────────────────────────

    private String executeMcpTool(AiToolCall call) {
        try {
            return localMcpClient.callTool(call.name(), call.input());
        } catch (Exception e) {
            log.warn("[ShortAiFilter] MCP tool {} failed: {}", call.name(), e.getMessage());
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    // ─── Prompt Builder ───────────────────────────────────────────────────────

    private String buildShortPrompt(String symbol, String intervalCode) {
        try {
            AiStrategyDiscoveryService.MarketSnapshot s1h =
                    aiDiscoveryService.buildMarketSnapshot(symbol, intervalCode);
            AiStrategyDiscoveryService.MarketSnapshot s4h =
                    aiDiscoveryService.buildMarketSnapshot(symbol, "4h");
            return "你是加密貨幣交易風控系統。根據以下市場數據，判斷是否應開立 " + symbol + " SWAP 做空倉位。\n\n" +
                    "【" + intervalCode + " 快照】\n" + s1h.toPromptText() + "\n\n" +
                    "【4h 快照】\n" + s4h.toPromptText() + "\n\n" +
                    "你可以呼叫以下 MCP 工具輔助判斷：\n" +
                    "- getBacktestHistory(strategyId): 查策略歷史回測空頭績效\n" +
                    "- getMarketSnapshot(symbol, intervalCode): 取最新 K 線快照\n" +
                    "- analyzeMarket(symbol, intervalCode): 雙時框市場形態分析\n" +
                    "- getCurrentReport: 查當前開倉狀態與曝險\n" +
                    "- listStrategies: 列出所有啟用中策略\n\n" +
                    "問題：當前市場是否確認為下跌趨勢、適合做空？" +
                    "特別注意：若市場處於崩跌後反彈修復期，應回答 REJECT。\n" +
                    "請最終僅回答：APPROVE（適合做空）或 REJECT（不適合做空），後接 30 字以內中文原因。";
        } catch (Exception e) {
            log.warn("[ShortAiFilter] buildShortPrompt failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String trimReply(String reply) {
        if (reply == null) return "(null)";
        return reply.length() > 100 ? reply.substring(0, 100) + "..." : reply;
    }
}
