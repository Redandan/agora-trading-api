package com.agora.dto.staking;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
@Schema(description = "更新質押配置參數")
public class StakingConfigUpdateParam {
    
    @NotNull(message = "年利率不能為空")
    @DecimalMin(value = "0.0", message = "年利率不能為負數")
    @DecimalMax(value = "1.0", message = "年利率不能超過100%")
    @Schema(description = "年利率（0-1之間，例如0.05表示5%）", required = true, example = "0.05")
    private BigDecimal annualInterestRate;
    
    @NotNull(message = "最低質押金額不能為空")
    @DecimalMin(value = "0.0", message = "最低質押金額不能為負數")
    @Schema(description = "最低質押金額", required = true, example = "50")
    private BigDecimal minStakingAmount;
    
    @Schema(description = "備註")
    private String remark;
}

