package com.agora.dto.scheduler;

import com.agora.enums.trading.SchedulerJobTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 批量執行定時任務請求
 */
@Data
@Schema(description = "批量執行定時任務請求")
public class BatchSchedulerJobRequest {
    
    @NotEmpty(message = "任務列表不能為空")
    @Schema(description = "要執行的任務類型列表", required = true)
    private List<SchedulerJobTypeEnum> jobTypes;
    
    @Schema(description = "是否強制執行", example = "false")
    private Boolean forceRun = false;
    
    @Schema(description = "執行原因或備註", example = "批量補跑")
    private String remark;
}
