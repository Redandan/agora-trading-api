package com.agora.dto.staking;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "質押配置信息")
public class StakingConfigDTO {
    
    @Schema(description = "年利率")
    private BigDecimal annualInterestRate;
    
    @Schema(description = "最低質押金額")
    private BigDecimal minStakingAmount;
    
    @Schema(description = "是否啟用")
    private Boolean isActive;
    
    @Schema(description = "生效時間")
    private LocalDateTime effectiveFrom;
    
    @Schema(description = "更新時間")
    private LocalDateTime updatedAt;
}

