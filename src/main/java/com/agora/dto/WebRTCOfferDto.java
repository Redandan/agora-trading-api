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
@Schema(description = "WebRTC Offer 資料傳輸物件")
public class WebRTCOfferDto {
    
    
    @NotNull(message = "發起用戶ID不能為空")
    @Schema(description = "發起通話的用戶ID", example = "123")
    private Long fromUserId;
    
    @NotNull(message = "接收用戶ID不能為空")
    @Schema(description = "接收通話的用戶ID", example = "456")
    private Long toUserId;
    
    @NotBlank(message = "SDP 不能為空")
    @Schema(description = "WebRTC SDP Offer 內容")
    private String sdp;
    
    @Schema(description = "信令類型", example = "offer")
    @Builder.Default
    private String type = "offer";
    
    @Schema(description = "發送時間")
    private LocalDateTime timestamp;
    
    @Schema(description = "通話類型", example = "video", allowableValues = {"video", "audio"})
    @Builder.Default
    private String callType = "video";
    
    @Schema(description = "是否包含音訊", example = "true")
    @Builder.Default
    private Boolean audioEnabled = true;
    
    @Schema(description = "是否包含視訊", example = "true")
    @Builder.Default
    private Boolean videoEnabled = true;
}
