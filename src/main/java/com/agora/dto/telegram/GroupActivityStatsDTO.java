package com.agora.dto.telegram;

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
@Schema(description = "Telegram 群組活躍度統計")
public class GroupActivityStatsDTO {

    @Schema(description = "Telegram 群組 ID", example = "-1001234567890", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long groupId;

    @Schema(description = "最近 1 分鐘消息數", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
    private long messagesPerMinute;

    @Schema(description = "最近 1 小時消息數", example = "188", requiredMode = Schema.RequiredMode.REQUIRED)
    private long messagesPerHour;

    @Schema(description = "最近消息時間", nullable = true)
    private LocalDateTime lastMessageTime;

    @Schema(description = "最近 1 小時活躍用戶數", example = "25", requiredMode = Schema.RequiredMode.REQUIRED)
    private long recentActiveUsers;
}
