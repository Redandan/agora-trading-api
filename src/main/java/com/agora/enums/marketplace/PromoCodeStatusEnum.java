package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "推廣碼申請狀態")
public enum PromoCodeStatusEnum {
    @Schema(description = "待審核")
    PENDING,    // 待審核
    
    @Schema(description = "已通過")
    APPROVED,   // 已通過
    
    @Schema(description = "已拒絕")
    REJECTED,   // 已拒絕
    
    @Schema(description = "已停用")
    DISABLED    // 已停用
} 