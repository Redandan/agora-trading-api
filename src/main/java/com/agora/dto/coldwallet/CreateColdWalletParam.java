package com.agora.dto.coldwallet;

import com.agora.enums.system.ProtocolEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 冷錢包創建參數
 */
@Data
@Schema(description = "冷錢包創建參數")
public class CreateColdWalletParam {
    
    /**
     * 錢包地址
     */
    @Schema(description = "錢包地址", required = true)
    @NotBlank(message = "錢包地址不能為空")
    @Pattern(regexp = "^T[A-Za-z1-9]{33}$", message = "錢包地址格式不正確，必須是有效的 TRON 地址")
    private String address;
    
    /**
     * 協議類型
     */
    @Schema(description = "協議類型", enumAsRef = true, required = true)
    @NotNull(message = "協議類型不能為空")
    private ProtocolEnum protocolEnum;
}
