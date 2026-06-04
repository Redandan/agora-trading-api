package com.agora.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Flutter 部署安全服務
 * 服務器啟動時生成 Token，用於驗證上傳請求
 */
@Service
@Slf4j
@lombok.RequiredArgsConstructor
public class FlutterDeploymentSecurityService {

    private final com.agora.config.properties.FlutterDeploymentProperties props;

    // 服務器啟動時生成的 Token
    private String deploymentToken;

    private LocalDateTime generatedAt;

    /**
     * 服務器啟動時生成 Token
     */
    @PostConstruct
    public void generateToken() {
        SecureRandom random = new SecureRandom();
        byte[] tokenBytes = new byte[props.security().tokenLength()];
        random.nextBytes(tokenBytes);
        deploymentToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        
        log.info("========================================");
        log.info("Flutter 部署 Token 已生成");
        log.info("Token: {}", deploymentToken);
        log.info("請將此 Token 保存到您的客戶端腳本中");
        log.info("========================================");
        generatedAt = LocalDateTime.now().withNano(0);
    }

    /**
     * 驗證 Token
     * @param providedToken 客戶端提供的 Token
     * @return 是否有效
     */
    public boolean validateToken(String providedToken) {
        if (deploymentToken == null || deploymentToken.trim().isEmpty()) {
            log.warn("部署 Token 未初始化");
            return false;
        }
        
        if (providedToken == null || providedToken.trim().isEmpty()) {
            log.warn("客戶端未提供 Token");
            return false;
        }
        
        // 使用安全的字符串比較（防止時間攻擊）
        boolean isValid = secureEquals(providedToken, deploymentToken);
        
        if (!isValid) {
            log.warn("Token 驗證失敗");
        }
        
        return isValid;
    }

    /**
     * 獲取當前 Token（用於查詢）
     */
    public String getToken() {
        return deploymentToken;
    }

    public LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    /**
     * 安全的字符串比較（防止時間攻擊）
     */
    private boolean secureEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}

