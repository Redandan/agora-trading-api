package com.agora.dto.telegram;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "群組 AI Prompt 預覽")
public class GroupAiPromptPreviewDTO {

    @Schema(description = "Telegram 群組 ID", example = "-1001234567890", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long groupId;

    @Schema(description = "是否啟用手動 Prompt", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean manualPromptEnabled;

    @Schema(description = "最終送出的 system prompt", requiredMode = Schema.RequiredMode.REQUIRED)
    private String systemPrompt;

    @Schema(description = "生成目標", requiredMode = Schema.RequiredMode.REQUIRED)
    private String goal;

    @Schema(description = "上下文訊息清單", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> contextMessages;

    @Schema(description = "觸發訊息", nullable = true)
    private String triggerMessage;

    @Schema(description = "最終送出的 user prompt（由最近對話 + 目標組成）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String finalUserPrompt;
}
