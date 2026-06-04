package com.agora.dto.telegram;

import com.agora.enums.system.PersonalityType;
import com.agora.enums.system.ReplyMode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "監聽中的 Telegram 群組資料")
public class MonitoredGroupDTO {

    @Schema(description = "Telegram 群組 ID", example = "-1001234567890", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long groupId;

    @Schema(description = "群組名稱", example = "Agora Market 測試群", nullable = true)
    private String groupName;

    @Schema(description = "群組類型", example = "supergroup", requiredMode = Schema.RequiredMode.REQUIRED)
    private String groupType;

    @Schema(description = "首次監聽時間", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime firstSeenAt;

    @Schema(description = "最近消息時間", nullable = true)
    private LocalDateTime lastMessageAt;

    @Schema(description = "是否啟用群組 AI 聊天", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean aiChatEnabled;

    @Schema(description = "是否啟用手動 Prompt", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean aiManualPromptEnabled;

    @Schema(description = "手動 Prompt 內容", nullable = true)
    private String aiManualPromptText;

    @Schema(description = "當前緩衝消息數", example = "300", requiredMode = Schema.RequiredMode.REQUIRED)
    private long bufferedMessageCount;

    @Schema(description = "回覆模式", example = "ACTIVE")
    private ReplyMode replyMode;

    @Schema(description = "ACTIVE 模式：累積幾條訊息後現身", example = "10")
    private Integer messageCountThreshold;

    @Schema(description = "ACTIVE 模式：兩次回覆最短間隔（分鐘）", example = "5")
    private Integer minIntervalMinutes;

    @Schema(description = "AI 個性", example = "FRIENDLY")
    private PersonalityType personality;
}
