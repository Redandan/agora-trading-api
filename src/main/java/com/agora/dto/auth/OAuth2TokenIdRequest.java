package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * OAuth2 Token ID 请求（后端方案）
 */
@Data
@Schema(description = "OAuth2 Token ID 请求（后端方案）")
public class OAuth2TokenIdRequest {
    
    @NotBlank(message = "tokenId 不能为空")
    @Schema(description = "临时token ID（从回调URL中获取）", example = "abc123xyz", required = true)
    private String tokenId;
}

