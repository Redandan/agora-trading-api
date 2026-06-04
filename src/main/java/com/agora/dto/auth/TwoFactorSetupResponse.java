package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "雙因素認證信息響應")
public class TwoFactorSetupResponse {
    @Schema(description = "QR碼數據（僅在未啟用時返回）")
    private String qrCodeData;
    
    @Schema(description = "密鑰（僅在未啟用時返回）")
    private String secret;
    
    @Schema(description = "是否已啟用")
    private Boolean enabled;
    
    @Schema(description = "是否已配置")
    private Boolean configured;
} 