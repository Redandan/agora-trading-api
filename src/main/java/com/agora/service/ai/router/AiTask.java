package com.agora.service.ai.router;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 抽象 AI 任務 — provider-agnostic。
 *
 * <p>AiTaskRouter 依 task type 路由到對應 AiProvider(可由 config 換 Claude/Gemini/Groq/Local)。
 *
 * <p><b>新增 task 規則</b>:用 sealed permits 鎖定 hierarchy;每個 task 都要有 {@link #type()}
 * 對應 application.yml 的 routing key。
 */
public sealed interface AiTask
        permits AiTask.AnnotateTrade,
                AiTask.ReasonOnSignal,
                AiTask.AnswerUserQuery,
                AiTask.AnalyzeMarketFlip,
                AiTask.ScoreSignal,
                AiTask.GenericPrompt,
                AiTask.MarketAdvisorPersona,
                AiTask.WithSystem {

    /** Task 類型字串(對應 application.yml `ai.routing.<type>.primary`)。 */
    String type();

    /** 系統 prompt(可選,provider 可加入 system message)。 */
    default String systemPrompt() { return null; }

    /** 用戶 prompt(主要內容)。 */
    String userPrompt();

    /** 預期 max output tokens(provider 可參考)。 */
    default int maxTokens() { return 2048; }

    // ============================================================================
    // 已知 task 類型(可逐步擴充)
    // ============================================================================

    /** 平倉後事後分析:Claude 看 entry/exit/PnL 寫一段 reflection。 */
    record AnnotateTrade(
            Long liveSignalId,
            String symbol,
            String side,
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            BigDecimal realizedPnl,
            String entryContextJson,
            String exitContextJson
    ) implements AiTask {
        @Override public String type() { return "annotate-trade"; }
        @Override public String systemPrompt() {
            return "你是量化交易事後分析助手。給你倉位資訊,寫一段繁體中文的 200-300 字反思:" +
                   "(1) 進場 vs 出場市況差異 (2) 主要勝/敗因 (3) 給未來類似情境的 1-2 句啟示。" +
                   "回應只給文字內容,不要 markdown 標題。";
        }
        @Override public String userPrompt() {
            return String.format(
                    "倉位 #%d:%s %s\n進場 $%s → 出場 $%s,實現損益 $%s\n\n進場時 context: %s\n出場時 context: %s",
                    liveSignalId, symbol, side, entryPrice, exitPrice, realizedPnl,
                    entryContextJson, exitContextJson);
        }
        @Override public int maxTokens() { return 600; }
    }

    /** 訊號出現時即時 reasoning(替代/補強 LongAiFilter)。 */
    record ReasonOnSignal(
            String symbol,
            String side,
            BigDecimal currentPrice,
            Map<String, Object> indicators,
            Map<String, Object> sentimentSnapshot
    ) implements AiTask {
        @Override public String type() { return "reason-on-signal"; }
        @Override public String systemPrompt() {
            return "你是量化交易判斷助手。給你 symbol、方向、價格、指標、市況," +
                   "回應 JSON {\"decision\":\"GO|SKIP|CAUTION\",\"confidence\":0.0-1.0,\"reason\":\"短一句\"}。" +
                   "GO=信號可信;SKIP=高風險建議跳過;CAUTION=可下但建議減倉。";
        }
        @Override public String userPrompt() {
            return String.format(
                    "%s %s @ $%s\nIndicators: %s\nSentiment: %s",
                    symbol, side, currentPrice, indicators, sentimentSnapshot);
        }
        @Override public int maxTokens() { return 200; }
    }

    /**
     * 對話式查詢 — 配合 {@code askSystemAssistant} MCP tool,使用者問題 + system snapshot JSON。
     *
     * <p>呼叫端把 snapshot JSON 放 conversationHistory 第 1 筆,prompt 要求 AI 只根據 snapshot 事實回答。
     * {@code maxTokens} 可由 caller 覆蓋(預設 1024);flash-lite 無 thinking,設定值即 visible。
     */
    record AnswerUserQuery(
            String userMessage,
            List<String> conversationHistory,
            int maxTokens
    ) implements AiTask {
        /** 相容舊呼叫端的 convenience constructor(預設 maxTokens=1024)。 */
        public AnswerUserQuery(String userMessage, List<String> conversationHistory) {
            this(userMessage, conversationHistory, 1024);
        }
        @Override public String type() { return "answer-user-query"; }
        @Override public String systemPrompt() {
            return "你是 AgoraMarketAPI 系統助手。使用者問題前會附一份 system snapshot JSON(--- 分隔)。" +
                   "**嚴格根據 snapshot 內的事實回答**,不要用訓練資料腦補。若 snapshot 沒有答案所需資訊,誠實說「snapshot 無此資訊,建議直接 call <tool_name>」。" +
                   "用繁體中文,精簡回答 100-200 字,附相關數字。";
        }
        /**
         * 把 conversationHistory 併入 userPrompt —— 各 provider(Gemini/Groq/Claude)目前只送
         * system + user 兩段訊息,不轉發 history。為了讓 snapshot 真的到達 LLM,必須 inline。
         */
        @Override public String userPrompt() {
            if (conversationHistory != null && !conversationHistory.isEmpty()) {
                return String.join("\n\n", conversationHistory) + "\n\n---\n\n使用者問題: " + userMessage;
            }
            return userMessage;
        }
    }

    /** Market indicator flip 發生後,AI 判斷該 DISMISS 或 ALERT。 */
    record AnalyzeMarketFlip(
            Long eventId,
            String symbol,
            String indicator,
            BigDecimal prevValue,
            BigDecimal currentValue,
            String thresholdCrossed,
            String contextJson
    ) implements AiTask {
        @Override public String type() { return "analyze-market-flip"; }
        @Override public String systemPrompt() {
            return "你是量化交易市況翻轉分析助手。給你一個市場指標突變事件(F&G / 鯨魚買入比等)," +
                   "判斷這是噪音還是值得警示人類的實質訊號。\n" +
                   "回應 JSON 格式(只回 JSON,不要 markdown code block):\n" +
                   "{\"decision\":\"DISMISS|ALERT|TUNE\",\"confidence\":0.00-1.00,\"reasoning\":\"短 100 字內\"}\n" +
                   "DISMISS = 正常波動不必警示;ALERT = 實質轉折,TG 給人類看;TUNE = 噪音太多建議升門檻。";
        }
        @Override public String userPrompt() {
            return String.format(
                    "Event #%d: %s / %s\n" +
                    "指標變化: %s → %s (threshold crossed: %s)\n" +
                    "Context: %s",
                    eventId, symbol, indicator, prevValue, currentValue,
                    thresholdCrossed != null ? thresholdCrossed : "(delta only)",
                    contextJson != null ? contextJson : "{}");
        }
        @Override public int maxTokens() { return 500; }
    }

    /**
     * <b>LLM-as-scorer</b> — 用 LLM 對「即將/已發生 trade entry」打 P(win) 機率分數。
     *
     * <p>用途:作為 HeatWave ML 的並行 scorer。LLM 可以用「regime 推理」(如 F&G 50 +
     * volume spike + RSI 超買 → 多頭末段風險高)補強純數值模型在 regime shift 下的盲點。
     *
     * <p>features map 結構應該對應 vw_signal_training_v2:
     * is_short / is_btc / is_1h / entry_price / hour_of_day / day_of_week /
     * adx14 / rsi14 / atr_pct / volume_ratio_ma20 / close_vs_ema50_pct /
     * ema20_slope_pct / bb_width_pct(可缺;LLM 用可用部分)。
     *
     * <p>輸出嚴格 JSON(不含 markdown code block):
     * {@code {"p_win": 0.00-1.00, "regime": "...", "reasoning": "短一句"}}
     */
    record ScoreSignal(
            String symbol,
            String side,
            BigDecimal currentPrice,
            Map<String, Object> features
    ) implements AiTask {
        @Override public String type() { return "score-signal"; }
        @Override public String systemPrompt() {
            return "你是量化交易機率評分模型。給你 trade entry 的指標快照,輸出該 trade " +
                   "獲利機率 P(win)。考量點:趨勢強度(ADX)、超買超賣(RSI)、波動(ATR)、" +
                   "成交量異常(volume_ratio)、與均線距離、BB 寬度。判斷是否 regime 不利此方向。\n" +
                   "回應嚴格 JSON 格式(不要 markdown code block,不要任何說明文字):\n" +
                   "{\"p_win\": 0.00-1.00, \"regime\": \"BULL_TRENDING|BEAR_TRENDING|CHOP|SQUEEZE|RECOVERY|UNCLEAR\", " +
                   "\"reasoning\": \"短 80 字內中文\"}\n" +
                   "p_win 必須在 [0.00, 1.00] 之間。0.5 = 不確定;> 0.6 = 看好;< 0.4 = 看壞。";
        }
        @Override public String userPrompt() {
            StringBuilder sb = new StringBuilder();
            sb.append(symbol).append(' ').append(side).append(" @ $").append(currentPrice).append('\n');
            sb.append("Features:\n");
            // Stable ordering for prompt cache hit + readability
            String[] order = {
                    "strategy_id", "is_short", "is_btc", "is_1h",
                    "hour_of_day", "day_of_week",
                    "adx14", "rsi14", "atr_pct", "volume_ratio_ma20",
                    "close_vs_ema50_pct", "ema20_slope_pct", "bb_width_pct"
            };
            for (String k : order) {
                Object v = features.get(k);
                if (v != null) sb.append("  ").append(k).append('=').append(v).append('\n');
            }
            return sb.toString();
        }
        @Override public int maxTokens() { return 400; }
    }

    /** 通用一次性 prompt(快速測試用)。 */
    record GenericPrompt(String type, String prompt, int maxTokens) implements AiTask {
        @Override public String userPrompt() { return prompt; }
    }

    /** System + user 兩段 prompt 的通用 task，適合從既有 messages list 遷移的場景。 */
    record WithSystem(String type, String systemContent, String userContent, int maxTokens) implements AiTask {
        @Override public String systemPrompt() { return systemContent; }
        @Override public String userPrompt()   { return userContent; }
    }

    /**
     * GeminiMarketAdvisor 三 persona 投票用 task。
     * 每個 persona(trend / contrarian / risk)有獨立 systemPrompt，
     * userPrompt = marketContext + responseFormat。
     */
    record MarketAdvisorPersona(
            String personaName,
            String personaSystemPrompt,
            String marketContext,
            String responseFormat,
            int maxTokens
    ) implements AiTask {
        @Override public String type() { return "market-advisor-persona"; }
        @Override public String systemPrompt() { return personaSystemPrompt; }
        @Override public String userPrompt() { return marketContext + "\n\n" + responseFormat; }
        @Override public int maxTokens() { return maxTokens; }
    }
}
