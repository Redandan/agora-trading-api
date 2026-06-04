package com.agora.service.ai.skill;

import org.springframework.stereotype.Component;

/**
 * 查詢群組 ID Skill
 * <p>
 * 當使用者輸入 /groupid 時，由 GroupIdBotController 呼叫，
 * 傳入當前 chatId 字串並格式化回覆。
 * 透過自然語言觸發時（意圖分類為 GROUP_ID），提示使用者改用指令查詢。
 */
@Component
public class GroupIdSkill implements AiSkill {

    @Override
    public String getIntentCode() {
        return "GROUP_ID";
    }

    @Override
    public String getIntentDescription() {
        return "詢問當前群組 ID、Chat ID、群組識別碼";
    }

    /**
     * @param text 由指令處理器傳入的 chatId 字串，或自然語言訊息
     */
    @Override
    public String execute(String text) {
        // 若 text 為純數字（由指令處理器傳入的實際 chatId），直接格式化回覆
        if (text != null && text.matches("-?\\d+")) {
            long chatId = Long.parseLong(text);
            if (chatId < 0) {
                return "📋 <b>群組資訊</b>\n\n當前群組 ID：<code>" + chatId + "</code>";
            } else {
                return "📋 <b>聊天資訊</b>\n\n當前聊天 ID：<code>" + chatId + "</code>\n\n" +
                       "💡 提示：在群組中使用 /groupid 可查詢群組 ID。";
            }
        }
        // 自然語言觸發時，提示使用者用指令
        return "📋 請在群組中輸入 /groupid 指令，即可查詢當前群組 ID。";
    }

    @Override
    public SkillResponse executeRich(String text) {
        return SkillResponse.html(execute(text));
    }
}
