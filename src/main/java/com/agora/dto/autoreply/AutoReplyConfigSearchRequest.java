package com.agora.dto.autoreply;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 自動回復配置搜尋請求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "自動回復配置搜尋請求")
public class AutoReplyConfigSearchRequest {

    @Schema(description = "配置名稱（模糊搜尋）", example = "價格")
    private String name;

    @Schema(description = "關鍵詞（模糊搜尋）", example = "價格")
    private String keyword;

    @Schema(description = "是否啟用", example = "true")
    private Boolean enabled;

    @Schema(description = "最小優先級", example = "1")
    private Integer minPriority;

    @Schema(description = "最大優先級", example = "10")
    private Integer maxPriority;

    @Schema(description = "最小命中次數", example = "0")
    private Long minHitCount;

    @Schema(description = "最大命中次數", example = "1000")
    private Long maxHitCount;

    @Schema(description = "頁碼 (從0開始)", example = "0")
    @Builder.Default
    private int page = 0;

    @Schema(description = "每頁大小", example = "20")
    @Builder.Default
    private int size = 20;

    @Schema(description = "排序字段", example = "priority")
    @Builder.Default
    private String sortBy = "priority";

    @Schema(description = "排序方向 (asc/desc)", example = "asc")
    @Builder.Default
    private String sortDirection = "asc";
}
