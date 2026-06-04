package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Web3 钱包获取 Nonce 请求（通用，支持 WalletConnect 和 Tron）
 */
@Data
@Schema(description = "Web3 钱包获取 Nonce 请求（支持以太坊和 Tron 地址）")
public class Web3NonceRequest {
    
    @NotBlank(message = "钱包地址不能为空")
    @Pattern(regexp = "^(0x[a-fA-F0-9]{40}|T[1-9A-HJ-NP-Za-km-z]{33})$", 
            message = "钱包地址格式不正确，必须是有效的以太坊地址（0x开头，42个字符）或 Tron 地址（T开头，34个字符）")
    @Schema(description = "钱包地址（以太坊或 Tron）", 
            example = "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb")
    private String walletAddress;
}

