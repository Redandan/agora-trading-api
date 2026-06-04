package com.agora.enums.system;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "通知類型")
public enum NotificationTypeEnum {
    
    @Schema(description = "系統通知")
    SYSTEM,
    
    @Schema(description = "訂單通知")
    ORDER,
    
    @Schema(description = "配送通知")
    DELIVERY,
    
    @Schema(description = "聊天通知")
    CHAT,
    
    @Schema(description = "財務通知")
    FINANCIAL,
    
    @Schema(description = "安全通知")
    SECURITY,
    
    @Schema(description = "促銷通知")
    PROMOTION,
    
    @Schema(description = "庫存通知")
    INVENTORY,
    
    @Schema(description = "評價通知")
    REVIEW,
    
    @Schema(description = "糾紛通知")
    DISPUTE;
} 