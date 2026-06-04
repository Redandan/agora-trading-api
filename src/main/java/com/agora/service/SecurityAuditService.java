package com.agora.service;

/**
 * 安全審計服務
 * 用於記錄和分析安全相關事件
 */
public interface SecurityAuditService {
    
    /**
     * 記錄密碼重置嘗試
     * @param email 用戶郵箱
     * @param ipAddress 用戶IP地址
     * @param success 是否成功
     * @param reason 失敗原因（如果失敗）
     */
    void logPasswordResetAttempt(String email, String ipAddress, boolean success, String reason);
    
    /**
     * 記錄驗證碼發送
     * @param email 用戶郵箱
     * @param ipAddress 用戶IP地址
     */
    void logVerificationCodeSent(String email, String ipAddress);
    
    /**
     * 記錄賬戶鎖定事件
     * @param email 用戶郵箱
     * @param ipAddress 用戶IP地址
     * @param reason 鎖定原因
     */
    void logAccountLockout(String email, String ipAddress, String reason);
    
    /**
     * 記錄可疑活動
     * @param email 用戶郵箱
     * @param ipAddress 用戶IP地址
     * @param activity 活動描述
     * @param riskLevel 風險等級
     */
    void logSuspiciousActivity(String email, String ipAddress, String activity, String riskLevel);
    
    /**
     * 檢查IP地址是否可疑
     * @param ipAddress IP地址
     * @return 是否可疑
     */
    boolean isSuspiciousIp(String ipAddress);
    
    /**
     * 獲取IP地址的風險評分
     * @param ipAddress IP地址
     * @return 風險評分（0-100）
     */
    int getIpRiskScore(String ipAddress);
}
