package com.agora.dto.request;

import com.agora.enums.system.NotifyEventTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用SSE事件請求
 * 用於所有SSE測試端點，根據具體端點使用相應的欄位
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "通用SSE事件請求")
public class SSEEventRequest {

    @Schema(description = "事件類型", requiredMode = Schema.RequiredMode.REQUIRED, enumAsRef = true)
    private NotifyEventTypeEnum eventType;

    @Schema(description = "變動金額（用於balance-change）", example = "100")
    private String amount;

    @Schema(description = "貨幣（用於balance-change）", example = "USDT")
    private String currency;

    @Schema(description = "接收者ID（用於typing-event、custom-event）", example = "123")
    private Long receiverId;

    @Schema(description = "目標用戶ID（用於custom-event，不填則預設當前用戶）", example = "456")
    private Long targetUserId;

    @Schema(description = "消息內容（用於system-event、custom-event）", example = "測試消息")
    private String message;
}
