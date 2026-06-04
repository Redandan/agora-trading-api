package com.agora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.verification-code")
public class VerificationCodeConfig {
    
    /**
     * 驗證碼長度
     */
    private int codeLength = 6;
    
    /**
     * 驗證碼過期時間（分鐘）
     */
    private int expirationMinutes = 30;
    
    /**
     * 時間限制：每個email在多少分鐘內只能申請一次驗證碼
     */
    private int rateLimitMinutes = 5;
    
    /**
     * 最大嘗試次數：驗證碼驗證失敗多少次後鎖定賬戶
     */
    private int maxAttempts = 3;
    
    /**
     * 賬戶鎖定時間（分鐘）：達到最大嘗試次數後鎖定多長時間
     */
    private int lockoutMinutes = 30;
    
    /**
     * IP地址變更限制時間（分鐘）：同一郵箱在短時間內不能頻繁變更IP地址
     */
    private int ipChangeLimitMinutes = 10;

    @Bean
    public Map<String, String> verificationCodeStore() {
        return new ConcurrentHashMap<>();
    }
} 