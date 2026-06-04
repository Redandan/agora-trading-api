package com.agora.dto;

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
@Schema(description = "WebRTC 通話發起事件資料傳輸物件")
public class WebRTCCallInitiatedDto {
    
    @Schema(description = "通話唯一識別碼", example = "call_1758976586035_2")
    private String callId;
    
    @Schema(description = "發起通話的用戶ID", example = "2")
    private Long fromUserId;
    
    @Schema(description = "接收通話的用戶ID", example = "3")
    private Long toUserId;
    
    @Schema(description = "事件類型", example = "WEBRTC_CALL_INITIATED")
    @Builder.Default
    private String type = "WEBRTC_CALL_INITIATED";
    
    @Schema(description = "事件時間")
    private LocalDateTime timestamp;
}
