package com.agora.dto.staking;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Schema(description = "收益發放記錄")
public class InterestRecordDTO {
    
    @Schema(description = "交易記錄ID", nullable = false)
    private Long transactionId;
    
    @Schema(description = "用戶ID", nullable = false)
    private Long userId;
    
    @Schema(description = "用戶名", nullable = false)
    private String username;
    
    @Schema(description = "質押記錄ID", nullable = true)
    private String stakingId;
    
    @Schema(description = "質押金額", nullable = true)
    private BigDecimal stakingAmount;
    
    @Schema(description = "收益金額", nullable = false)
    private BigDecimal interestAmount;
    
    @Schema(description = "結算日期", nullable = true)
    private LocalDate settleDate;
    
    @Schema(description = "發放時間", nullable = false)
    private LocalDateTime createdAt;
}

