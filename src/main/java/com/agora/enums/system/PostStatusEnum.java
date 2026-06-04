package com.agora.enums.system;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "貼文狀態")
public enum PostStatusEnum {
    @Schema(description = "草稿")
    DRAFT,
    
    @Schema(description = "已發布")
    PUBLISHED,
    
    @Schema(description = "已下架")
    ARCHIVED
}
