package com.agora.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "WebRTC 掛斷通話響應資料傳輸物件")
public class WebRTCHangupResponseDto extends WebRTCResponseDto {
    
    @Schema(description = "通話唯一識別碼", example = "call_1758976586035_2")
    private String callId;
    
    @Schema(description = "掛斷原因", example = "user_hangup")
    private String reason;
    
    @Schema(description = "通話持續時間（毫秒）", example = "120000")
    private Long duration;
}
