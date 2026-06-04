package com.agora.dto.scheduler;

import com.agora.enums.trading.SchedulerJobTypeEnum;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 定時任務執行響應
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "定時任務執行響應")
public class SchedulerJobResponse {
    
    @Schema(description = "執行是否成功", example = "true")
    private Boolean success;
    
    @Schema(description = "任務類型", example = "STAKING_SETTLEMENT")
    private SchedulerJobTypeEnum jobType;
    
    @Schema(description = "任務名稱", example = "質押每日結算")
    private String jobName;
    
    @Schema(description = "響應消息", example = "執行成功")
    private String message;
    
    @Schema(description = "錯誤信息（失敗時返回）")
    private String error;
    
    @Schema(description = "執行開始時間")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;
    
    @Schema(description = "執行結束時間")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
    
    @Schema(description = "執行耗時（毫秒）", example = "1250")
    private Long durationMs;
    
    @Schema(description = "處理記錄數", example = "15")
    private Integer recordCount;
    
    @Schema(description = "執行備註")
    private String remark;
}
