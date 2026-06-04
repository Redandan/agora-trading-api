package com.agora.enums.system;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "用戶狀態")
public enum UserStatusEnum {
    @Schema(description = "正常使用")
    ACTIVE,     // 正常使用
    
    @Schema(description = "未激活")
    INACTIVE,   // 未激活
    
    @Schema(description = "暫停使用")
    SUSPENDED,  // 暫停使用
    
    @Schema(description = "禁止使用")
    BANNED,     // 禁止使用
    
    @Schema(description = "已刪除")
    DELETED     // 已刪除
} 