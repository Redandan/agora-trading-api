package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 授權碼交換參數（測試用）
 */
@Data
@Schema(description = "授權碼交換參數")
public class AuthCodeExchangeParam {
    
    @Schema(description = "授權碼", example = "TEST1234")
    private String code;
    
    @Schema(description = "設備ID（測試用）", example = "TESTDEVICE123456")
    private String deviceId;
    
    @Schema(description = "IP地址（測試用）", example = "127.0.0.1")
    private String ipAddress;
}