package com.agora.dto.chat;

import com.agora.dto.common.BaseSearchParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "聊天記錄查詢參數 - 只支援根據會話ID查詢")
public class ChatMessageQueryParam extends BaseSearchParam {
    
    @Schema(description = "用戶ID")
    private Long userId;

    @Schema(description = "會話ID", required = true)
    private String sessionId;

    @Schema(description = "聊天對象ID", hidden = true)
    private Long chatWithUserId;

} 