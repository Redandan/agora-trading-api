package com.agora.dto.backtest;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
@Schema(description = "建立策略請求")
public class CreateStrategyRequest {

    @Schema(description = "策略名稱", example = "SOP BTCUSDT 1H", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "name 不可為空")
    private String name;

    @Schema(description = "策略類型", example = "SOP_MTF_ADX", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "strategyType 不可為空")
    private String strategyType;

    @Schema(description = "監控交易對，逗號分隔（必填）", example = "BTCUSDT,ETHUSDT", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "symbols 不可為空，請指定監控幣種（如 'BTCUSDT' 或 'BTCUSDT,ETHUSDT'）")
    private String symbols;

    @Schema(description = "策略參數配置", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "config 不可為空")
    @Valid
    private SopMtfAdxConfig config;
}
