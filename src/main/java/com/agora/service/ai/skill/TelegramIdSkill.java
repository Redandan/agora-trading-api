package com.agora.service.ai.skill;

import org.springframework.stereotype.Component;

/**
 * 查詢 Telegram User ID Skill
 * <p>
 * 透過自然語言觸發時（意圖分類為 TELEGRAM_ID），由 GroupAIChatService 傳入
 * 發訊者的 userId 字串並格式化回覆。
 */
@Component
public class TelegramIdSkill implements AiSkill {

    @Override
    public String getIntentCode() {
        return "TELEGRAM_ID";
    }

    @Override
    public String getIntentDescription() {
        return "詢問自己的 Telegram ID、用戶 ID、我的 ID、My Telegram ID";
    }

    /**
     * @param text 由 GroupAIChatService 傳入的 userId 字串，或自然語言訊息
     */
    @Override
    public String execute(String text) {
        // 若 text 為純數字（由服務層傳入的實際 userId），直接格式化回覆
        if (text != null && text.matches("\\d+")) {
            return "🪪 <b>您的 Telegram 資訊</b>\n\nUser ID：<code>" + text + "</code>";
        }
        // 自然語言觸發但無法取得 userId 時的回退訊息
        return "🪪 抱歉，目前無法取得您的 Telegram ID，請稍後再試。";
    }

    @Override
    public SkillResponse executeRich(String text) {
        return SkillResponse.html(execute(text));
    }
}
