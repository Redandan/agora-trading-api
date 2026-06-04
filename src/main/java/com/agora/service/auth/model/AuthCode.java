package com.agora.service.auth.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 授權碼數據模型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthCode {
    
    /**
     * 授權碼
     */
    private String code;
    
    /**
     * 用戶ID
     */
    private Long userId;
    
    /**
     * 創建時間
     */
    private LocalDateTime createdAt;
    
    /**
     * 過期時間
     */
    private LocalDateTime expiresAt;
    
    /**
     * 是否已使用
     */
    private boolean used = false;
    
    /**
     * 使用時間
     */
    private LocalDateTime usedAt;
    
    /**
     * 設備號（瀏覽器指紋或設備ID）
     */
    private String deviceId;
    
    /**
     * 創建時的IP地址
     */
    private String ipAddress;
    
    /**
     * 檢查是否已過期
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
    
    /**
     * 檢查是否有效（未使用且未過期）
     */
    public boolean isValid() {
        return !used && !isExpired();
    }
    
    /**
     * 檢查設備和IP是否匹配
     */
    public boolean isDeviceAndIpMatched(String currentDeviceId, String currentIpAddress) {
        if (deviceId == null || ipAddress == null) {
            return true; // 如果沒有記錄設備信息，允許通過（向後兼容）
        }
        return deviceId.equals(currentDeviceId) && ipAddress.equals(currentIpAddress);
    }
}
