package com.agora.dto.scheduler;

import com.agora.enums.trading.SchedulerJobTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotNull;

/**
 * 定時任務執行請求
 */
@Data
@Schema(description = "定時任務執行請求")
public class SchedulerJobRequest {
    
    @NotNull(message = "任務類型不能為空")
    @Schema(description = "任務類型", required = true, example = "STAKING_SETTLEMENT")
    private SchedulerJobTypeEnum jobType;
    
    @Schema(description = "是否強制執行（忽略執行條件檢查）", example = "false")
    private Boolean forceRun = false;
    
    @Schema(description = "執行原因或備註", example = "手動補跑")
    private String remark;
}
