package com.agora.dto.autoreply;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** #379 — Java 21 record. */
@Schema(description = "刪除配置響應")
public record AutoReplyDeleteResponse(
        @Schema(description = "響應消息", example = "配置刪除成功")
        String message,

        @Schema(description = "被刪除的配置ID", example = "1")
        Long id,

        @Schema(description = "操作時間")
        LocalDateTime timestamp
) {
    public static AutoReplyDeleteResponse success(Long id) {
        return new AutoReplyDeleteResponse("配置刪除成功", id, LocalDateTime.now());
    }
}
