package com.agora.service.ai.skill;

import org.springframework.stereotype.Component;

/**
 * 機器人功能介紹 Skill
 * <p>
 * 當用戶詢問「你能做什麼」、「介紹你的服務」等問題時，
 * 直接回傳固定的功能清單，不需要呼叫 Groq。
 */
@Component
public class IntroductionSkill implements AiSkill {

    private static final String INTRO_TEXT =
            "嗨！我是小幣 🪙，以下是我能幫你做的事：\n\n" +
            "📈 加密貨幣行情 — 查詢 BTC、ETH 等幣種的最新 K 線價格\n" +
            "💱 匯率查詢 — 查詢 USDT/TWD、USD/TWD 等即時匯率\n" +
            "🎁 優惠碼 — 查詢目前可用的平台優惠碼\n" +
            "🏟 投注市場 — 查詢開放中的賽事市場\n" +
            "🏪 商店推薦 — 查詢平台上的商家資訊\n" +
            "🛒 商品搜尋 — 搜尋平台商品，輸入「我想買 XXX」試試看\n\n" +
            "直接用自然語言問我就好，例如：「BTC 現在多少？」、「有沒有折扣碼？」";

    @Override
    public String getIntentCode() {
        return "INTRO";
    }

    @Override
    public String getIntentDescription() {
        return "介紹機器人功能、詢問能做什麼、有什麼服務";
    }

    @Override
    public String execute(String text) {
        return INTRO_TEXT;
    }

    @Override
    public SkillResponse executeRich(String text) {
        return SkillResponse.text(INTRO_TEXT);
    }
}
