package com.agora.dto.telegram;

import com.agora.enums.system.PersonalityType;
import com.agora.enums.system.ReplyMode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "群組 AI 陪聊策略設定")
public class GroupAiStrategyDTO {

    @Schema(description = "回覆模式", example = "ACTIVE",
            allowableValues = {"ACTIVE", "PASSIVE", "DISABLED"})
    private ReplyMode replyMode;

    @Schema(description = "ACTIVE 模式：累積幾條訊息後現身", example = "10")
    private Integer messageCountThreshold;

    @Schema(description = "ACTIVE 模式：兩次回覆最短間隔（分鐘）", example = "5")
    private Integer minIntervalMinutes;

    @Schema(description = "AI 個性", example = "FRIENDLY",
            allowableValues = {"FRIENDLY", "PROFESSIONAL", "HUMOROUS", "CUSTOM"})
    private PersonalityType personality;

    @Schema(description = "CUSTOM 個性時使用的自訂 system prompt", nullable = true)
    private String customPrompt;
}
