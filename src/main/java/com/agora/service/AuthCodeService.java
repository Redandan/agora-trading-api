package com.agora.service;

/**
 * 臨時授權碼服務接口
 * 用於註冊成功後的安全登入流程
 */
public interface AuthCodeService {
    
    /**
     * 生成臨時授權碼
     * 
     * @param userId 用戶ID
     * @param deviceId 設備ID
     * @param ipAddress IP地址
     * @return 8位隨機授權碼
     */
    String generateAuthCode(Long userId, String deviceId, String ipAddress);
    
    /**
     * 生成臨時授權碼（向後兼容）
     * 
     * @param userId 用戶ID
     * @return 8位隨機授權碼
     */
    default String generateAuthCode(Long userId) {
        return generateAuthCode(userId, null, null);
    }
    
    /**
     * 驗證並消費授權碼
     * 
     * @param code 授權碼
     * @param deviceId 當前設備ID
     * @param ipAddress 當前IP地址
     * @return 用戶ID，如果無效返回null
     */
    Long validateAndConsumeAuthCode(String code, String deviceId, String ipAddress);
    
    /**
     * 驗證並消費授權碼（向後兼容）
     * 
     * @param code 授權碼
     * @return 用戶ID，如果無效返回null
     */
    default Long validateAndConsumeAuthCode(String code) {
        return validateAndConsumeAuthCode(code, null, null);
    }
    
    /**
     * 檢查授權碼是否存在（不消費）
     * 
     * @param code 授權碼
     * @return 用戶ID，如果無效返回null
     */
    Long checkAuthCode(String code);
    
    /**
     * 撤銷授權碼
     * 
     * @param code 授權碼
     * @return 是否成功撤銷
     */
    boolean revokeAuthCode(String code);
    
    /**
     * 清理過期授權碼
     */
    void cleanupExpiredCodes();
}
