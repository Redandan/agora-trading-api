package com.agora.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * ClientId 格式驗證工具類
 * 用於驗證SSE連接的clientId格式是否符合安全規範
 */
@Slf4j
@Component
public class ClientIdValidator {
    
    // ClientId格式正則表達式：user_{userId}_{deviceId}
    // userId: 數字，deviceId: 字母數字和連字符，長度8-32
    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile(
        "^user_(\\d+)_([a-zA-Z0-9\\-_]{8,32})$"
    );
    
    // 允許的deviceId字符
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9\\-_]{8,32}$"
    );
    
    /**
     * 驗證clientId格式是否正確
     * 
     * @param clientId 要驗證的clientId
     * @return 是否格式正確
     */
    public boolean isValidFormat(String clientId) {
        if (clientId == null || clientId.trim().isEmpty()) {
            log.warn("ClientId is null or empty");
            return false;
        }
        
        boolean isValid = CLIENT_ID_PATTERN.matcher(clientId).matches();
        
        if (!isValid) {
            log.warn("Invalid clientId format: {}", clientId);
        }
        
        return isValid;
    }
    
    /**
     * 驗證clientId是否屬於指定用戶
     * 
     * @param clientId 要驗證的clientId
     * @param userId 用戶ID
     * @return 是否屬於該用戶
     */
    public boolean belongsToUser(String clientId, String userId) {
        if (!isValidFormat(clientId)) {
            return false;
        }
        
        try {
            String extractedUserId = extractUserId(clientId);
            boolean belongs = userId.equals(extractedUserId);
            
            if (!belongs) {
                log.warn("ClientId {} does not belong to user {}", clientId, userId);
            }
            
            return belongs;
        } catch (Exception e) {
            log.error("Error validating clientId ownership: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 從clientId中提取userId
     * 
     * @param clientId 格式正確的clientId
     * @return 用戶ID
     * @throws IllegalArgumentException 如果clientId格式不正確
     */
    public String extractUserId(String clientId) {
        if (!isValidFormat(clientId)) {
            throw new IllegalArgumentException("Invalid clientId format: " + clientId);
        }
        
        java.util.regex.Matcher matcher = CLIENT_ID_PATTERN.matcher(clientId);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        
        throw new IllegalArgumentException("Failed to extract userId from clientId: " + clientId);
    }
    
    /**
     * 從clientId中提取deviceId
     * 
     * @param clientId 格式正確的clientId
     * @return 設備ID
     * @throws IllegalArgumentException 如果clientId格式不正確
     */
    public String extractDeviceId(String clientId) {
        if (!isValidFormat(clientId)) {
            throw new IllegalArgumentException("Invalid clientId format: " + clientId);
        }
        
        java.util.regex.Matcher matcher = CLIENT_ID_PATTERN.matcher(clientId);
        if (matcher.matches()) {
            return matcher.group(2);
        }
        
        throw new IllegalArgumentException("Failed to extract deviceId from clientId: " + clientId);
    }
    
    /**
     * 驗證deviceId格式
     * 
     * @param deviceId 要驗證的deviceId
     * @return 是否格式正確
     */
    public boolean isValidDeviceId(String deviceId) {
        if (deviceId == null || deviceId.trim().isEmpty()) {
            return false;
        }
        
        return DEVICE_ID_PATTERN.matcher(deviceId).matches();
    }
    
    /**
     * 生成符合格式的clientId
     * 
     * @param userId 用戶ID
     * @param deviceId 設備ID
     * @return 格式正確的clientId
     * @throws IllegalArgumentException 如果參數格式不正確
     */
    public String generateClientId(String userId, String deviceId) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("UserId cannot be null or empty");
        }
        
        if (!isValidDeviceId(deviceId)) {
            throw new IllegalArgumentException("Invalid deviceId format: " + deviceId);
        }
        
        // 驗證userId是否為數字
        try {
            Long.parseLong(userId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("UserId must be a number: " + userId);
        }
        
        String clientId = "user_" + userId + "_" + deviceId;
        
        log.debug("Generated clientId: {} for user: {}", clientId, userId);
        
        return clientId;
    }
    
    /**
     * 獲取clientId格式說明
     * 
     * @return 格式說明字符串
     */
    public String getFormatDescription() {
        return "ClientId format: user_{userId}_{deviceId}\n" +
               "  - userId: 數字ID\n" +
               "  - deviceId: 8-32位字母數字和連字符組合\n" +
               "  - 示例: user_123_abc123def456";
    }
    
    /**
     * 獲取clientId格式正則表達式
     * 
     * @return 正則表達式字符串
     */
    public String getFormatRegex() {
        return CLIENT_ID_PATTERN.pattern();
    }
}
