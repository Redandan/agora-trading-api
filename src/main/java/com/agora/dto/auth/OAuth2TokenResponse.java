package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * OAuth2 Token 交换响应
 */
@Data
@Schema(description = "OAuth2 Token 交换响应")
public class OAuth2TokenResponse {
    
    @Schema(description = "访问令牌（JWT）", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
    private String accessToken;
    
    @Schema(description = "刷新令牌", example = "refresh_token_string")
    private String refreshToken;
    
    @Schema(description = "Token 过期时间（秒）", example = "3600")
    private Long expiresIn;
    
    @Schema(description = "Token 类型", example = "Bearer")
    private String tokenType = "Bearer";
    
    @Schema(description = "用户 ID", example = "123")
    private Long userId;
    
    @Schema(description = "用户名", example = "user_12345678")
    private String username;
    
    @Schema(description = "用户角色", example = "USER")
    private String userRole;
}

