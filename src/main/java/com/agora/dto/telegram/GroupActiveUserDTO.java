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
@Schema(description = "群組活躍用戶發言統計")
public class GroupActiveUserDTO {

    @Schema(description = "Telegram 用戶 ID", example = "123456789", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    @Schema(description = "發言次數", example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
    private long messageCount;
}
