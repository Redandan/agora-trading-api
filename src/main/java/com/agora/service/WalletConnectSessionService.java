package com.agora.service;

import com.agora.service.walletconnect.model.WalletConnectSession;

/**
 * WalletConnect Session 管理服务
 */
public interface WalletConnectSessionService {
    /**
     * 创建新的 session
     * @param deviceFingerprint 设备指纹
     * @param ipAddress IP 地址
     * @return session ID
     */
    String createSession(String deviceFingerprint, String ipAddress);
    
    /**
     * 获取 session
     * @param sessionId session ID
     * @return session 对象，如果不存在或已过期返回 null
     */
    WalletConnectSession getSession(String sessionId);
    
    /**
     * 更新 session 状态为已连接
     * @param sessionId session ID
     * @param walletAddress 钱包地址
     */
    void markAsConnected(String sessionId, String walletAddress);
    
    /**
     * 更新 session 状态为已验证
     * @param sessionId session ID
     * @param signature 签名
     */
    void markAsVerified(String sessionId, String signature);
    
    /**
     * 验证 session 并获取 nonce
     * @param sessionId session ID
     * @return nonce，如果 session 无效返回 null
     */
    String getNonceForSession(String sessionId);
    
    /**
     * 验证 session 是否可以用于登录
     * @param sessionId session ID
     * @param walletAddress 钱包地址
     * @return 是否有效
     */
    boolean validateSessionForLogin(String sessionId, String walletAddress);
}

