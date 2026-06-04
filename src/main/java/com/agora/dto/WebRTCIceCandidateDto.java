package com.agora.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "WebRTC ICE Candidate 資料傳輸物件")
public class WebRTCIceCandidateDto {
    
    @NotBlank(message = "Call ID 不能為空")
    @Schema(description = "通話唯一識別碼", example = "call_12345_67890")
    private String callId;
    
    @NotNull(message = "發起用戶ID不能為空")
    @Schema(description = "發起通話的用戶ID", example = "123")
    private Long fromUserId;
    
    @NotNull(message = "接收用戶ID不能為空")
    @Schema(description = "接收通話的用戶ID", example = "456")
    private Long toUserId;
    
    @NotBlank(message = "ICE Candidate 不能為空")
    @Schema(description = "ICE Candidate 內容")
    private String candidate;
    
    @Schema(description = "SDP Media ID", example = "0")
    private String sdpMid;
    
    @Schema(description = "SDP Media Line Index", example = "0")
    private Integer sdpMLineIndex;
    
    @Schema(description = "信令類型", example = "ice-candidate")
    @Builder.Default
    private String type = "ice-candidate";
    
    @Schema(description = "發送時間")
    private LocalDateTime timestamp;
}
