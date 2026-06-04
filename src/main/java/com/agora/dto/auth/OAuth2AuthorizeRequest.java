package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * OAuth2 授权请求
 */
@Data
@Schema(description = "OAuth2 授权请求")
public class OAuth2AuthorizeRequest {
    
    @NotBlank(message = "redirect_uri 不能为空")
    @Schema(description = "前端回调地址（Web: https://redandan.github.io/oauth2-callback, Desktop: com.agoramarket.oauth://oauth2callback）", 
            example = "https://redandan.github.io/oauth2-callback", required = true)
    private String redirectUri;
    
    @NotBlank(message = "state 不能为空")
    @Schema(description = "状态参数（防 CSRF）", example = "abc123xyz", required = true)
    private String state;
    
    @Schema(description = "权限范围", example = "openid email profile")
    private String scope;
    
    @NotBlank(message = "platform 不能为空")
    @Schema(description = "平台类型", example = "web", allowableValues = {"web", "desktop"}, required = true)
    private String platform;
}

