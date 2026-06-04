package com.agora.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 匯率信息 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "匯率信息")
public class ExchangeRateInfo {
    
    @Schema(description = "源貨幣", example = "USDT")
    private String fromCurrency;
    
    @Schema(description = "目標貨幣", example = "TWD")
    private String toCurrency;
    
    @Schema(description = "匯率值", example = "31.50")
    private BigDecimal rate;
    
    @Schema(description = "貨幣符號", example = "NT$")
    private String symbol;
    
    @Schema(description = "貨幣名稱", example = "新台幣")
    private String currencyName;
    
    @Schema(description = "最後更新時間")
    private LocalDateTime lastUpdated;
}
