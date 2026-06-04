package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * OAuth2 授权响应
 */
@Data
@Schema(description = "OAuth2 授权响应")
public class OAuth2AuthorizeResponse {
    
    @Schema(description = "授权 URL", example = "https://accounts.google.com/o/oauth2/v2/auth?client_id=...")
    private String authorizationUrl;
}

