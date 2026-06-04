package com.agora.dto.telegram;

import com.agora.enums.system.PersonalityType;
import com.agora.enums.system.ReplyMode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "群組設定更新請求（所有欄位皆為可選，只傳需要修改的欄位）")
public class GroupEditRequest {

    @Schema(description = "是否啟用群組 AI 聊天", example = "true", nullable = true)
    private Boolean aiChatEnabled;

    @Schema(description = "回覆模式", example = "ACTIVE",
            allowableValues = {"ACTIVE", "PASSIVE", "DISABLED"}, nullable = true)
    private ReplyMode replyMode;

    @Schema(description = "ACTIVE 模式：累積幾條訊息後現身", example = "10", nullable = true)
    private Integer messageCountThreshold;

    @Schema(description = "ACTIVE 模式：兩次回覆最短間隔（分鐘）", example = "5", nullable = true)
    private Integer minIntervalMinutes;

    @Schema(description = "AI 個性", example = "FRIENDLY",
            allowableValues = {"FRIENDLY", "PROFESSIONAL", "HUMOROUS", "CUSTOM"}, nullable = true)
    private PersonalityType personality;

    @Schema(description = "CUSTOM 個性時使用的自訂 system prompt", nullable = true)
    private String customPrompt;
}
