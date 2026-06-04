package com.agora.enums.system;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 用戶默認首頁類型枚舉
 */
@Schema(description = "用戶默認首頁類型")
public enum DefaultHomePageEnum {
    @Schema(description = "買家首頁")
    BUYER("BUYER", "買家首頁"),
    
    @Schema(description = "賣家首頁")
    SELLER("SELLER", "賣家首頁"),
    
    @Schema(description = "外送員首頁")
    DELIVERYER("DELIVERYER", "外送員首頁"),
    
    @Schema(description = "管理員首頁")
    ADMIN("ADMIN", "管理員首頁");

    private final String code;
    private final String description;

    DefaultHomePageEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根據用戶角色和店鋪名稱獲取默認首頁
     * @param role 用戶角色
     * @param storeName 店鋪名稱
     * @return 默認首頁類型
     */
    public static DefaultHomePageEnum getDefaultByRole(String role, String storeName) {
        if ("ADMIN".equals(role)) {
            return ADMIN;
        }
        // 如果有店鋪名稱，默認為賣家首頁
        if (storeName != null && !storeName.trim().isEmpty()) {
            return SELLER;
        }
        // 默認為買家首頁
        return BUYER;
    }
    
    /**
     * 檢查當前頁面類型是否在維護中
     * @param systemConfigService 系統配置服務
     * @return true 表示在維護中，false 表示正常
     */
    public boolean isInMaintenance(com.agora.service.SystemConfigService systemConfigService) {
        if (systemConfigService == null) {
            return false;
        }
        
        switch (this) {
            case SELLER:
                return systemConfigService.isSellerMaintenanceEnabled();
            case DELIVERYER:
                return systemConfigService.isDeliveryMaintenanceEnabled();
            case BUYER:
            case ADMIN:
            default:
                return false;
        }
    }
    
    /**
     * 獲取可用的首頁（考慮維護狀態）
     * 如果當前頁面在維護中，自動返回 BUYER 首頁
     * 
     * @param systemConfigService 系統配置服務
     * @return 可用的首頁類型
     */
    public DefaultHomePageEnum getAvailableHomePage(com.agora.service.SystemConfigService systemConfigService) {
        return isInMaintenance(systemConfigService) ? BUYER : this;
    }
}
