package com.agora.service.ai.skill;

/**
 * AI Skill 介面
 * <p>
 * 每個實作代表一種 AI 可執行的能力（查詢行情、搜尋商品等）。
 * Spring 會自動掃描所有實作，注冊至 SkillRegistry。
 * 新增功能只需建立新的實作類，不需修改任何現有程式碼。
 */
public interface AiSkill {

    /**
     * 意圖代碼，全大寫英文，例如 "PRICE"。
     * 對應 IntentClassifier 回傳的分類結果。
     */
    String getIntentCode();

    /**
     * 意圖描述，用於動態產生 IntentClassifier 的 system prompt。
     * 例如：「詢問加密貨幣價格、行情、漲跌（例：BTC多少、ETH現在幾錢）」
     */
    String getIntentDescription();

    /**
     * 執行此 skill，回傳要發送到群組的文字。
     *
     * @param text 用戶的原始訊息
     * @return 回覆文字，若無法處理可回傳 null
     */
    String execute(String text);

    /**
     * 執行此 skill，回傳包含文字與可選鍵盤的富回覆。
     * 所有 Skill 必須明確實作此方法，避免 parse mode 在對話路徑被隱式遺漏。
     *
     * @param text 用戶的原始訊息
     * @return SkillResponse，若無法處理可回傳 null
     */
    SkillResponse executeRich(String text);

    /**
     * 當 execute() 回傳 null 時，是否應 fallback 到 Groq 一般聊天。
     * 預設 false（不 fallback，直接略過）。
     * 若 Skill 有可能找不到結果並希望 AI 自行回覆，請覆寫為 true。
     */
    default boolean needsFallback() {
        return false;
    }
}
