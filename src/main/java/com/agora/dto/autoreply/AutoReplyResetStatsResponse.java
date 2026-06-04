package com.agora.dto.autoreply;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** #379 — Java 21 record. */
@Schema(description = "重置統計響應")
public record AutoReplyResetStatsResponse(
        @Schema(description = "響應消息", example = "所有配置的命中次數已重置")
        String message,

        @Schema(description = "重置時間")
        LocalDateTime timestamp,

        @Schema(description = "重置的配置數量", example = "5")
        Long resetCount
) {
    public static AutoReplyResetStatsResponse success(Long resetCount) {
        return new AutoReplyResetStatsResponse("所有配置的命中次數已重置", LocalDateTime.now(), resetCount);
    }
}
