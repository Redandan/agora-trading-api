package com.agora.service.walletconnect.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * WalletConnect Session 数据模型
 * 用于管理 WalletConnect 连接会话
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WalletConnectSession {
    
    /**
     * Session ID（唯一标识）
     */
    private String sessionId;
    
    /**
     * Nonce（用于签名）
     */
    private String nonce;
    
    /**
     * Nonce 生成时的时间戳（用于签名消息）
     */
    private Long nonceTimestamp;
    
    /**
     * 钱包地址（连接后设置）
     */
    private String walletAddress;
    
    /**
     * 签名（验证后设置）
     */
    private String signature;
    
    /**
     * 状态：pending / connected / verified / expired
     */
    private SessionStatus status;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 过期时间
     */
    private LocalDateTime expiresAt;
    
    /**
     * 连接时间（钱包连接时设置）
     */
    private LocalDateTime connectedAt;
    
    /**
     * 验证时间（签名验证通过时设置）
     */
    private LocalDateTime verifiedAt;
    
    /**
     * 设备指纹（用于安全验证）
     */
    private String deviceFingerprint;
    
    /**
     * IP 地址（用于安全验证）
     */
    private String ipAddress;
    
    /**
     * Session 状态枚举
     */
    public enum SessionStatus {
        PENDING,      // 等待连接
        CONNECTED,    // 已连接钱包
        VERIFIED,     // 已验证签名
        EXPIRED       // 已过期
    }
    
    /**
     * 检查是否已过期
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
    
    /**
     * 检查是否有效（未过期且未验证）
     */
    public boolean isValid() {
        return !isExpired() && status != SessionStatus.VERIFIED;
    }
}

