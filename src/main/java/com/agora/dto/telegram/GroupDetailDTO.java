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
@Schema(description = "群組詳細資訊（活躍度統計 + 活躍用戶 + 最近消息）")
public class GroupDetailDTO {

    @Schema(description = "活躍度統計", requiredMode = Schema.RequiredMode.REQUIRED)
    private GroupActivityStatsDTO activity;

    @Schema(description = "活躍用戶列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<GroupActiveUserDTO> activeUsers;

    @Schema(description = "最近消息列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<GroupMessageDTO> messages;
}
