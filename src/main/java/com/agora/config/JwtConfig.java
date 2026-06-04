package com.agora.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.jwt")
@Slf4j
public class JwtConfig {
    private String secret;
    private long accessTokenExpiration = 3600000L;  // access token 過期時間（短），默認1小時
    private long refreshTokenExpiration = 2592000000L; // refresh token 過期時間（長），默認30天
    private long trustedDeviceAccessTokenExpiration = 2592000000L; // 受信任設備刷新後的 access token 過期時間，默認30天
    private String header = "Authorization";
    private String tokenPrefix = "Bearer ";

    @PostConstruct
    public void logConfiguration() {
        log.info("=== JWT Configuration Loaded ===");
        log.info("Secret configured: {}", secret != null ? "YES" : "NO");
        log.info("Access Token Expiration: {} ms ({} hours)", accessTokenExpiration, accessTokenExpiration / (1000 * 60 * 60));
        log.info("Refresh Token Expiration: {} ms ({} days)", refreshTokenExpiration, refreshTokenExpiration / (1000 * 60 * 60 * 24));
        log.info("Trusted Device Access Token Expiration: {} ms ({} days)", trustedDeviceAccessTokenExpiration, trustedDeviceAccessTokenExpiration / (1000 * 60 * 60 * 24));
        log.info("Header: {}", header);
        log.info("Token Prefix: {}", tokenPrefix);
        log.info("Config object hash: {}", System.identityHashCode(this));
        log.info("================================");
    }
} 