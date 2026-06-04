package com.agora.dto.staking;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "下次收益發放預估")
public class NextInterestEstimateDTO {
    
    @Schema(description = "下次發放時間（帶時區，ISO-8601格式）")
    private LocalDateTime nextSettlementTime;
    
    @Schema(description = "預計發放的質押記錄數")
    private Long estimatedStakingCount;
    
    @Schema(description = "預計發放的總收益金額")
    private BigDecimal totalEstimatedInterest;
    
    @Schema(description = "平均每筆質押的預計收益")
    private BigDecimal averageEstimatedInterest;
    
    @Schema(description = "預計參與的總質押金額")
    private BigDecimal totalStakedAmount;
    
    @Schema(description = "當前年利率")
    private BigDecimal annualInterestRate;
    
    @Schema(description = "當前日利率")
    private BigDecimal dailyInterestRate;
    
    @Schema(description = "按質押記錄的詳細預估列表")
    private List<StakingInterestDetail> details;
    
    @Data
    @Schema(description = "質押收益詳情")
    public static class StakingInterestDetail {
        
        @Schema(description = "質押記錄ID")
        private String stakingId;
        
        @Schema(description = "用戶ID")
        private Long userId;
        
        @Schema(description = "質押金額")
        private BigDecimal stakingAmount;
        
        @Schema(description = "預計收益金額")
        private BigDecimal estimatedInterest;
    }
}

