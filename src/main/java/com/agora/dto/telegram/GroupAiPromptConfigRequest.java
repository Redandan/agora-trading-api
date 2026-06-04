package com.agora.dto.telegram;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "群組 AI 手動 Prompt 設定請求")
public class GroupAiPromptConfigRequest {

    @Schema(description = "是否啟用手動 Prompt", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean manualPromptEnabled;

    @Schema(description = "手動 Prompt 內容", example = "你是理性分析風格的群組助手，回覆需精簡。", nullable = true)
    private String manualPromptText;
}
