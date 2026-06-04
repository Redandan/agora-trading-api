package com.agora.dto.telegram;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "群組 AI 模擬生成結果")
public class GroupAiSimulationResponseDTO {

    @Schema(description = "Telegram 群組 ID", example = "-1001234567890", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long groupId;

    @Schema(description = "實際送往 AI 的 prompt 預覽", requiredMode = Schema.RequiredMode.REQUIRED)
    private GroupAiPromptPreviewDTO promptPreview;

    @Schema(description = "AI 生成結果（一句消息）", nullable = true)
    private String generatedMessage;
}
