package com.agora.service.ai;

import com.agora.service.ai.skill.AiSkill;
import com.agora.service.ai.skill.SkillRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 意圖分類器
 * <p>
 * 兩階段分類：
 * <ol>
 *   <li><b>關鍵字匹配（快速路徑）</b>：對高頻、明確的查詢模式直接回傳意圖代碼，
 *       無需呼叫 Groq API（約覆蓋 70-80% 的實際查詢）。</li>
 *   <li><b>Groq LLM（慢速路徑）</b>：關鍵字無法判斷時才呼叫 Groq，
 *       處理模糊或混合意圖。</li>
 * </ol>
 * 分類失敗或無法比對時回傳 "GENERAL"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentClassifier {

    public static final String GENERAL = "GENERAL";

    private final GroqApiClient groqApiClient;
    private final SkillRegistry skillRegistry;

    private Map<String, String> systemMessage;

    /**
     * Keyword → intent map.  Order matters: first match wins.
     * Each key is a lowercase substring to check against the normalized message.
     * Values are intent codes returned by {@link AiSkill#getIntentCode()}.
     */
    private static final LinkedHashMap<String, String> KEYWORD_RULES = new LinkedHashMap<>();

    static {
        // ── Group / Telegram ID (unambiguous structural queries) ──────────────
        KEYWORD_RULES.put("群組id",         "GROUP_ID");
        KEYWORD_RULES.put("group id",       "GROUP_ID");
        KEYWORD_RULES.put("chat id",        "GROUP_ID");
        KEYWORD_RULES.put("chatid",         "GROUP_ID");
        KEYWORD_RULES.put("telegram id",    "TELEGRAM_ID");
        KEYWORD_RULES.put("tg id",          "TELEGRAM_ID");
        KEYWORD_RULES.put("我的id",          "TELEGRAM_ID");
        KEYWORD_RULES.put("userid",         "TELEGRAM_ID");
        KEYWORD_RULES.put("user id",        "TELEGRAM_ID");

        // ── Exchange Rate ──────────────────────────────────────────────────────
        KEYWORD_RULES.put("匯率",            "EXCHANGE_RATE");
        KEYWORD_RULES.put("exchange rate",  "EXCHANGE_RATE");
        KEYWORD_RULES.put("換算",            "EXCHANGE_RATE");
        KEYWORD_RULES.put("兌換",            "EXCHANGE_RATE");
        KEYWORD_RULES.put("twd",            "EXCHANGE_RATE");
        KEYWORD_RULES.put("rmb",            "EXCHANGE_RATE");

        // ── Crypto Price ───────────────────────────────────────────────────────
        KEYWORD_RULES.put("比特幣",          "PRICE_QUERY");
        KEYWORD_RULES.put("btcusdt",        "PRICE_QUERY");
        KEYWORD_RULES.put("以太幣",          "PRICE_QUERY");
        KEYWORD_RULES.put("ethusdt",        "PRICE_QUERY");
        KEYWORD_RULES.put("幣價",            "PRICE_QUERY");
        KEYWORD_RULES.put("現價",            "PRICE_QUERY");
        KEYWORD_RULES.put("幣的價格",        "PRICE_QUERY");

        // ── Market Analysis ────────────────────────────────────────────────────
        KEYWORD_RULES.put("市場分析",        "MARKET_QUERY");
        KEYWORD_RULES.put("market analysis","MARKET_QUERY");
        KEYWORD_RULES.put("交易分析",        "TRADING_ANALYSIS");
        KEYWORD_RULES.put("回測",            "TRADING_ANALYSIS");
        KEYWORD_RULES.put("績效",            "TRADING_ANALYSIS");
        KEYWORD_RULES.put("勝率",            "TRADING_ANALYSIS");

        // ── Platform Introduction ──────────────────────────────────────────────
        KEYWORD_RULES.put("agora是什麼",     "INTRODUCTION");
        KEYWORD_RULES.put("平台介紹",        "INTRODUCTION");
        KEYWORD_RULES.put("怎麼使用",        "INTRODUCTION");
        KEYWORD_RULES.put("如何使用",        "INTRODUCTION");
    }

    @PostConstruct
    void init() {
        StringBuilder sb = new StringBuilder(
                "你是一個意圖分類器。根據用戶訊息，從以下類別中選擇**最符合**的一個，只回傳類別代碼，不要其他文字。\n\n");
        sb.append("【背景】\n");
        sb.append("你正在服務「Agora Trading」Telegram 群組助理。Agora Trading 專注加密貨幣行情、交易策略、回測與風控。\n\n");
        sb.append("【分類原則】\n");
        sb.append("只有當用戶**明確發出請求**（想查詢、想找、想知道某項資訊）時，才使用對應類別。\n");
        sb.append("若用戶只是閒聊、提及平台名稱、分享消息、問候，或訊息中雖包含關鍵詞但並非主動請求，一律回傳 GENERAL。\n\n");
        sb.append("【可用類別】\n");
        for (AiSkill skill : skillRegistry.getAll()) {
            sb.append(String.format("%-15s- %s\n", skill.getIntentCode(), skill.getIntentDescription()));
        }
        sb.append("GENERAL         - 以上都不符合（一般閒聊、其他問題）");

        Map<String, String> msg = new HashMap<>(2);
        msg.put("role", "system");
        msg.put("content", sb.toString());
        systemMessage = msg;
    }

    /**
     * 分類用戶訊息的意圖
     *
     * @param text 用戶訊息
     * @return 意圖代碼（大寫字串），分類失敗時回傳 "GENERAL"
     */
    public String classify(String text) {
        if (text == null || text.trim().isEmpty()) return GENERAL;

        // ── Phase 1: keyword fast-path (no API call) ──────────────────────────
        String keywordResult = matchKeywords(text);
        if (keywordResult != null) {
            log.debug("意圖分類（keyword）：「{}」→ {}", text.trim(), keywordResult);
            return keywordResult;
        }

        // ── Phase 2: Groq LLM for ambiguous messages ──────────────────────────
        if (!groqApiClient.isEnabled()) return GENERAL;

        try {
            String trimmed = text.trim();

            Map<String, String> userMessage = new HashMap<>(2);
            userMessage.put("role", "user");
            userMessage.put("content", trimmed);

            List<Map<String, String>> messages = new ArrayList<>(2);
            messages.add(systemMessage);
            messages.add(userMessage);

            String result = groqApiClient.chat(messages);
            if (result == null) return GENERAL;

            String clean = result.trim().toUpperCase().replaceAll("[^A-Z_]", "");
            log.debug("意圖分類（Groq）：「{}」→ {}", trimmed, clean);

            // 確認是已知 Skill 或 GENERAL，避免回傳垃圾字串
            if (GENERAL.equals(clean) || skillRegistry.hasSkill(clean)) {
                return clean;
            }

            log.debug("意圖分類結果無法比對：{}，預設 GENERAL", clean);
            return GENERAL;

        } catch (Exception e) {
            log.debug("意圖分類失敗: {}", e.getMessage());
            return GENERAL;
        }
    }

    /**
     * Fast keyword-based intent matching.
     *
     * @param text raw user message
     * @return intent code if unambiguously matched, {@code null} if Groq should be consulted
     */
    private String matchKeywords(String text) {
        String lower = text.toLowerCase();
        for (Map.Entry<String, String> entry : KEYWORD_RULES.entrySet()) {
            if (lower.contains(entry.getKey())) {
                String intent = entry.getValue();
                // Validate that the resolved intent is still registered (defensive check)
                if (skillRegistry.hasSkill(intent)) {
                    return intent;
                }
            }
        }
        return null;
    }
}
