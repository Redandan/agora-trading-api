package com.agora.dto.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 基礎搜索參數類
 * 統一所有搜索 API 的請求格式
 */
@Data
@Schema(description = "基礎搜索參數")
public abstract class BaseSearchParam {
    
    @Schema(description = "頁碼，從1開始", example = "1")
    private int page = 1;
    
    @Schema(description = "每頁數量", example = "20")
    private int size = 20;
    
    @Schema(description = "開始日期 (ISO-8601 格式)", example = "2024-01-01T00:00:00")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startDate;
    
    @Schema(description = "結束日期 (ISO-8601 格式)", example = "2024-12-31T23:59:59")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endDate;
    
    @Schema(description = "搜索關鍵字")
    private String keyword;
    
    @Schema(description = "排序字段")
    private String sortBy;
    
    @Schema(description = "排序方向 (ASC/DESC)", example = "DESC")
    private String sortDirection = "DESC";
}
