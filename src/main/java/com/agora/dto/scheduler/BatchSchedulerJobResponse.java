package com.agora.dto.scheduler;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量執行定時任務響應
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "批量執行定時任務響應")
public class BatchSchedulerJobResponse {
    
    @Schema(description = "整體執行是否成功", example = "true")
    private Boolean success;
    
    @Schema(description = "執行開始時間")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;
    
    @Schema(description = "執行結束時間")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
    
    @Schema(description = "總執行耗時（毫秒）", example = "3500")
    private Long totalDurationMs;
    
    @Schema(description = "成功任務數", example = "2")
    private Integer successCount;
    
    @Schema(description = "失敗任務數", example = "1")
    private Integer failureCount;
    
    @Schema(description = "各任務執行詳情")
    private List<SchedulerJobResponse> results;
    
    @Schema(description = "執行備註")
    private String remark;
}
