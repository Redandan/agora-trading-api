package com.agora.dto.staking;

import com.agora.enums.betting.StakingStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Schema(description = "質押統計數據")
public class StakingStatisticsDTO {
    
    @Schema(description = "總質押記錄數")
    private Long totalStakings;
    
    @Schema(description = "總質押金額")
    private BigDecimal totalStakedAmount;
    
    @Schema(description = "總收益金額")
    private BigDecimal totalEarnedRewards;
    
    @Schema(description = "平均質押金額")
    private BigDecimal averageStakingAmount;
    
    @Schema(description = "進行中的質押數")
    private Long activeStakings;
    
    @Schema(description = "進行中的質押金額")
    private BigDecimal activeStakedAmount;
    
    @Schema(description = "各狀態質押數量", enumAsRef = true)
    private Map<StakingStatusEnum, Long> stakingsByStatus;
    
    @Schema(description = "期間新增質押數")
    private Long newStakingsInPeriod;
    
    @Schema(description = "期間新增質押金額")
    private BigDecimal newStakedAmountInPeriod;
    
    @Schema(description = "期間總收益")
    private BigDecimal totalEarnedInPeriod;
}

