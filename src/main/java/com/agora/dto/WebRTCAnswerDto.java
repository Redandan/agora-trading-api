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
@Schema(description = "WebRTC Answer 資料傳輸物件")
public class WebRTCAnswerDto {
    
    @NotBlank(message = "Call ID 不能為空")
    @Schema(description = "通話唯一識別碼", example = "call_12345_67890")
    private String callId;
    
    @NotNull(message = "發起用戶ID不能為空")
    @Schema(description = "發起通話的用戶ID", example = "123")
    private Long fromUserId;
    
    @NotNull(message = "接收用戶ID不能為空")
    @Schema(description = "接收通話的用戶ID", example = "456")
    private Long toUserId;
    
    @NotBlank(message = "SDP 不能為空")
    @Schema(description = "WebRTC SDP Answer 內容")
    private String sdp;
    
    @Schema(description = "信令類型", example = "answer")
    @Builder.Default
    private String type = "answer";
    
    @Schema(description = "發送時間")
    private LocalDateTime timestamp;
    
    @Schema(description = "是否接受通話", example = "true")
    @Builder.Default
    private Boolean accepted = true;
}
