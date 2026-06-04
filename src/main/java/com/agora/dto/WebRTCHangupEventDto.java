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
@Schema(description = "WebRTC 掛斷通話事件資料傳輸物件")
public class WebRTCHangupEventDto {
    
    @Schema(description = "事件類型", example = "WEBRTC_CALL_ENDED")
    @Builder.Default
    private String type = "WEBRTC_CALL_ENDED";
    
    @Schema(description = "通話唯一識別碼", example = "call_1758976586035_2")
    private String callId;
    
    @Schema(description = "發起掛斷的用戶ID", example = "2")
    private Long fromUserId;
    
    @Schema(description = "接收掛斷通知的用戶ID", example = "3")
    private Long toUserId;
    
    @Schema(description = "掛斷原因", example = "user_hangup")
    private String reason;
    
    @Schema(description = "通話持續時間（毫秒）", example = "120000")
    private Long duration;
    
    @Schema(description = "事件時間")
    private LocalDateTime timestamp;
}
