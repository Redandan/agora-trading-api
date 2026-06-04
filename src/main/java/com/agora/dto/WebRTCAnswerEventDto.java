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
@Schema(description = "WebRTC Answer 事件資料傳輸物件")
public class WebRTCAnswerEventDto {
    
    @Schema(description = "事件類型", example = "WEBRTC_ANSWER")
    @Builder.Default
    private String type = "WEBRTC_ANSWER";
    
    @Schema(description = "通話唯一識別碼", example = "call_1758976586035_2")
    private String callId;
    
    @Schema(description = "發起通話的用戶ID", example = "2")
    private Long fromUserId;
    
    @Schema(description = "接收通話的用戶ID", example = "3")
    private Long toUserId;
    
    @Schema(description = "WebRTC SDP Answer 內容")
    private String sdp;
    
    @Schema(description = "是否接受通話", example = "true")
    private Boolean accepted;
    
    @Schema(description = "事件時間")
    private LocalDateTime timestamp;
}
