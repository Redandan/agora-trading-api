package com.agora.dto.telegram;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "群組 AI 模擬生成請求")
public class GroupAiSimulationRequest {

    @Schema(description = "觸發訊息", example = "大家怎麼看今晚行情？", nullable = true)
    private String triggerText;

    @Schema(description = "上下文訊息數量（最大 5）", example = "5", nullable = true)
    private Integer contextLimit;

    @Schema(description = "僅預覽 prompt，不呼叫 AI 生成（預設 false）", example = "false", nullable = true)
    private Boolean previewOnly;
}
