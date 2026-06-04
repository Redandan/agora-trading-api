package com.agora.service.impl;

import com.agora.config.properties.AuthCodeProperties;
import com.agora.service.auth.model.AuthCode;
import com.agora.service.AuthCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 臨時授權碼服務實現
 * 使用內存存儲授權碼，支持自動過期和清理
 */
@Slf4j
@Service
public class AuthCodeServiceImpl implements AuthCodeService {

    @Autowired
    private AuthCodeProperties props;

    // 使用 ConcurrentHashMap 存儲授權碼
    private final ConcurrentHashMap<String, AuthCode> authCodeStorage = new ConcurrentHashMap<>();
    
    
    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 8;
    private final Random random = new Random();
    
    @PostConstruct
    public void init() {
        log.info("AuthCodeService initialized with expiration: {} seconds, max codes: {}", 
            props.expiration(), props.maxCodes());
    }
    
    @Override
    public String generateAuthCode(Long userId, String deviceId, String ipAddress) {
        log.info("Generating auth code for user: {}, device: {}, ip: {}", userId, deviceId, ipAddress);
        
        // 檢查存儲容量
        if (authCodeStorage.size() >= props.maxCodes()) {
            log.warn("Auth code storage is full ({}), cleaning expired codes", props.maxCodes());
            cleanupExpiredCodes();
            
            // 如果清理後仍然滿，拋出異常
            if (authCodeStorage.size() >= props.maxCodes()) {
                log.error("Auth code storage is still full after cleanup");
                throw new RuntimeException("授權碼存儲已滿，請稍後再試");
            }
        }
        
        String code;
        int attempts = 0;
        final int maxAttempts = 10;
        
        // 確保生成的授權碼是唯一的
        do {
            code = generateRandomCode();
            attempts++;
            
            if (attempts >= maxAttempts) {
                log.error("Failed to generate unique auth code after {} attempts", maxAttempts);
                throw new RuntimeException("無法生成唯一的授權碼");
            }
        } while (authCodeStorage.containsKey(code));
        
        // 創建授權碼對象
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(props.expiration());
        
        AuthCode authCode = new AuthCode(code, userId, now, expiresAt, false, null, deviceId, ipAddress);
        
        // 存儲授權碼
        authCodeStorage.put(code, authCode);
        
        log.info("Auth code generated successfully: {} for user: {} device: {} ip: {} (expires at: {})", 
            code, userId, deviceId, ipAddress, expiresAt);
        
        return code;
    }
    
    @Override
    public Long validateAndConsumeAuthCode(String code, String deviceId, String ipAddress) {
        if (code == null || code.trim().isEmpty()) {
            log.warn("Empty auth code provided");
            return null;
        }
        
        // 測試用授權碼：TEST1234
        if ("TEST1234".equals(code)) {
            log.info("Test mode: Using test auth code TEST1234, returning test user ID: 1");
            return 1L; // 測試用戶ID
        }
        
        AuthCode authCode = authCodeStorage.get(code);
        
        if (authCode == null) {
            log.warn("Auth code not found: {}", code);
            return null;
        }
        
        // 檢查是否已使用
        if (authCode.isUsed()) {
            log.warn("Auth code already used: {}", code);
            return null;
        }
        
        // 檢查是否已過期
        if (authCode.isExpired()) {
            log.warn("Auth code expired: {} (expired at: {})", code, authCode.getExpiresAt());
            // 移除過期的授權碼
            authCodeStorage.remove(code);
            return null;
        }
        
        // 檢查設備和IP是否匹配
        if (!authCode.isDeviceAndIpMatched(deviceId, ipAddress)) {
            log.warn("Auth code device/IP mismatch: {} (expected device: {}, ip: {}, actual device: {}, ip: {})", 
                code, authCode.getDeviceId(), authCode.getIpAddress(), deviceId, ipAddress);
            return null;
        }
        
        // 標記為已使用
        authCode.setUsed(true);
        authCode.setUsedAt(LocalDateTime.now());
        
        // 從存儲中移除（一次性使用）
        authCodeStorage.remove(code);
        
        log.info("Auth code validated and consumed: {} for user: {} device: {} ip: {}", 
            code, authCode.getUserId(), deviceId, ipAddress);
        
        return authCode.getUserId();
    }
    
    @Override
    public Long validateAndConsumeAuthCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            log.warn("Empty auth code provided");
            return null;
        }
        
        AuthCode authCode = authCodeStorage.get(code);
        
        if (authCode == null) {
            log.warn("Auth code not found: {}", code);
            return null;
        }
        
        // 檢查是否已使用
        if (authCode.isUsed()) {
            log.warn("Auth code already used: {}", code);
            return null;
        }
        
        // 檢查是否已過期
        if (authCode.isExpired()) {
            log.warn("Auth code expired: {} (expired at: {})", code, authCode.getExpiresAt());
            // 移除過期的授權碼
            authCodeStorage.remove(code);
            return null;
        }
        
        // 標記為已使用
        authCode.setUsed(true);
        authCode.setUsedAt(LocalDateTime.now());
        
        // 從存儲中移除（一次性使用）
        authCodeStorage.remove(code);
        
        log.info("Auth code validated and consumed: {} for user: {}", code, authCode.getUserId());
        
        return authCode.getUserId();
    }
    
    @Override
    public Long checkAuthCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        
        AuthCode authCode = authCodeStorage.get(code);
        
        if (authCode == null) {
            log.debug("Auth code not found: {}", code);
            return null;
        }
        
        // 檢查是否有效
        if (!authCode.isValid()) {
            if (authCode.isUsed()) {
                log.debug("Auth code already used: {}", code);
            } else if (authCode.isExpired()) {
                log.debug("Auth code expired: {} (expired at: {})", code, authCode.getExpiresAt());
            }
            return null;
        }
        
        log.debug("Auth code exists and is valid: {} for user: {}", code, authCode.getUserId());
        return authCode.getUserId();
    }
    
    @Override
    public boolean revokeAuthCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return false;
        }
        
        AuthCode authCode = authCodeStorage.remove(code);
        boolean success = authCode != null;
        
        if (success) {
            log.info("Auth code revoked successfully: {}", code);
        } else {
            log.warn("Auth code not found for revocation: {}", code);
        }
        
        return success;
    }
    
    @Override
    @Scheduled(fixedRate = 60000) // 每分鐘執行一次清理
    public void cleanupExpiredCodes() {
        try {
            int initialSize = authCodeStorage.size();
            int cleanedCount = 0;
            
            // 使用迭代器安全地移除過期的授權碼
            authCodeStorage.entrySet().removeIf(entry -> {
                AuthCode authCode = entry.getValue();
                return authCode.isExpired();
            });
            
            cleanedCount = initialSize - authCodeStorage.size();
            
            if (cleanedCount > 0) {
                log.info("Cleanup completed. Removed {} expired auth codes. Current storage size: {}", 
                    cleanedCount, authCodeStorage.size());
            }
                
        } catch (Exception e) {
            log.error("Error during auth code cleanup: {}", e.getMessage());
        }
    }
    
    /**
     * 生成隨機授權碼
     */
    private String generateRandomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        
        return code.toString();
    }
    
}