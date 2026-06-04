package com.agora.dto.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@Builder
@Schema(description = "消息發送響應對象")
public class MessageSendResponseDTO {

    @Schema(description = "消息ID")
    private Long messageId;

    @Schema(description = "會話唯一標識ID")
    private String sessionId;

    @Schema(description = "發送狀態", example = "SUCCESS")
    private String status;

    @Schema(description = "SSE發送狀態", example = "SENT", allowableValues = {
        "SENT", "RECEIVER_OFFLINE", "SENDER_OFFLINE", "BOTH_OFFLINE",
        "AUTO_REPLY_SENT", "AUTO_REPLY_OFFLINE"
    })
    private String sseStatus;

    @Schema(description = "接收者是否在線", example = "true")
    private Boolean receiverOnline;

    @Schema(description = "發送時間")
    private LocalDateTime sentAt;

    @Schema(description = "錯誤信息（如果有）")
    private String errorMessage;

    @Schema(description = "Web Push 推送詳情（僅在 webPushTest 時返回）")
    private WebPushDetails webPushDetails;
}
