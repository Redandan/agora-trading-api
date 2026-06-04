package com.agora.enums.system;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "通知狀態")
public enum NotificationStatusEnum {
    
    @Schema(description = "未讀")
    UNREAD,
    
    @Schema(description = "已讀")
    READ,
    
    @Schema(description = "已刪除")
    DELETED;
} 