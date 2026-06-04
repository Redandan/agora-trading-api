package com.agora.dto.scheduler;

import io.swagger.v3.oas.annotations.media.Schema;

/** #379 — Java 21 record. Created via constructor in SchedulerController. */
@Schema(description = "任務類型信息")
public record JobTypeInfo(
        @Schema(description = "任務代碼", example = "STAKING_SETTLEMENT")
        String code,

        @Schema(description = "任務名稱", example = "質押每日結算")
        String name,

        @Schema(description = "任務描述", example = "每日下午3點執行，處理質押收益發放")
        String description
) {
}
