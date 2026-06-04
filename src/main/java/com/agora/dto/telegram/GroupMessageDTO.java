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
@Schema(description = "群組消息緩衝內容")
public class GroupMessageDTO {

    @Schema(description = "Telegram 群組 ID", example = "-1001234567890", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long groupId;

    @Schema(description = "Telegram 用戶 ID（系統消息可為空）", example = "123456789", nullable = true)
    private Long userId;

    @Schema(description = "Telegram 消息 ID", example = "1024", nullable = true)
    private Integer telegramMessageId;

    @Schema(description = "消息類型", example = "text", requiredMode = Schema.RequiredMode.REQUIRED)
    private String messageType;

    @Schema(description = "消息內容", example = "hello world", nullable = true)
    private String messageText;

    @Schema(description = "發送時間", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime sentAt;
}
