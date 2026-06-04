package com.agora.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "WebRTC 掛斷通話請求資料傳輸物件")
public class WebRTCHangupDto {
    
    
    @Schema(description = "接收掛斷通知的用戶ID", example = "3", required = true)
    @NotNull(message = "目標用戶ID不能為空")
    private Long toUserId;
    
    @Schema(description = "掛斷原因", example = "user_hangup", 
            allowableValues = {"user_hangup", "timeout", "network_error", "busy", "rejected"})
    @Builder.Default
    private String reason = "user_hangup";
    
    @Schema(description = "掛斷時間戳", example = "1758976586035")
    private Long timestamp;
    
    @Schema(description = "通話持續時間（毫秒）", example = "120000")
    private Long duration;
}
