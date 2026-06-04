package com.agora.dto.backtest;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "策略資訊回應")
public class StrategyResponse {

    @Schema(description = "策略 ID", example = "1")
    private Long id;

    @Schema(description = "策略名稱", example = "SOP BTCUSDT 1H")
    private String name;

    @Schema(description = "策略類型", example = "SOP_MTF_ADX")
    private String strategyType;

    @Schema(description = "是否啟用", example = "true")
    private Boolean enabled;

    @Schema(description = "是否為 AI 自動探勘生成的策略", example = "false")
    private Boolean aiGenerated;

    @Schema(description = "AI 探勘批次 ID，僅 AI 探勘策略才有值", example = "20260320-143021", nullable = true)
    private String discoveryBatch;

    @Schema(description = "監控交易對，逗號分隔，null=全部", example = "BTCUSDT,ETHUSDT", nullable = true)
    private String symbols;

    @Schema(description = "策略參數配置", nullable = true)
    private SopMtfAdxConfig config;

    @Schema(description = "啟用/停用備註說明", nullable = true)
    private String notes;

    @Schema(description = "Alpha 來源分類（V084）：技術面趨勢 / 崩盤底部 / 市場結構(OI+Funding) 等", nullable = true)
    private String alphaSource;

    @Schema(description = "結構化觸發條件說明（V084）", nullable = true)
    private String triggerConditions;

    @Schema(description = "建立時間（ISO-8601）", example = "2026-03-18T12:30:00", nullable = true)
    private LocalDateTime createdAt;

    @Schema(description = "更新時間（ISO-8601）", example = "2026-03-18T12:35:00", nullable = true)
    private LocalDateTime updatedAt;
}
