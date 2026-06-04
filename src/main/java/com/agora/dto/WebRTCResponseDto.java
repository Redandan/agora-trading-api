package com.agora.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "WebRTC API 響應基礎物件")
public class WebRTCResponseDto {
    
    @Schema(description = "操作是否成功", example = "true")
    private Boolean success;
    
    @Schema(description = "響應訊息", example = "操作成功")
    private String message;
    
    @Schema(description = "錯誤代碼（失敗時）", example = "INVALID_PARAMETER")
    private String errorCode;
    
    @Schema(description = "時間戳", example = "2025-09-27T12:36:27.036")
    private String timestamp;
}
