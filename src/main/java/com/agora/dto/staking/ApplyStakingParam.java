package com.agora.dto.staking;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.ToString;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
@Schema(description = "申請質押參數（活期質押）")
@ToString
public class ApplyStakingParam {
    
    @NotNull(message = "質押金額不能為空")
    @DecimalMin(value = "1.0", message = "質押金額必須大於等於1")
    @Schema(description = "質押金額", required = true)
    private BigDecimal amount;
} 