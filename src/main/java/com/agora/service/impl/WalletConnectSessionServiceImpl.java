package com.agora.service.impl;

import com.agora.service.walletconnect.model.WalletConnectSession;
import com.agora.service.WalletConnectSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WalletConnect Session 管理服务实现
 * 使用内存缓存存储 session（生产环境建议使用 Redis）
 */
@Service
@Slf4j
public class WalletConnectSessionServiceImpl implements WalletConnectSessionService {
    
    private static final long SESSION_EXPIRATION_MINUTES = 10; // 10 分钟过期
    
    // 内存缓存：sessionId -> WalletConnectSession
    private final ConcurrentHashMap<String, WalletConnectSession> sessionCache = new ConcurrentHashMap<>();
    
    // 定时清理任务
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
    
    public WalletConnectSessionServiceImpl() {
        // 启动定时清理任务（每5分钟清理一次过期数据）
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredSessions, 5, 5, TimeUnit.MINUTES);
    }
    
    @Override
    public String createSession(String deviceFingerprint, String ipAddress) {
        String sessionId = UUID.randomUUID().toString();
        String nonce = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis() / 1000;
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(SESSION_EXPIRATION_MINUTES);
        
        WalletConnectSession session = new WalletConnectSession();
        session.setSessionId(sessionId);
        session.setNonce(nonce);
        session.setNonceTimestamp(timestamp);
        session.setStatus(WalletConnectSession.SessionStatus.PENDING);
        session.setCreatedAt(now);
        session.setExpiresAt(expiresAt);
        session.setDeviceFingerprint(deviceFingerprint);
        session.setIpAddress(ipAddress);
        
        sessionCache.put(sessionId, session);
        
        log.info("Created WalletConnect session: {} for device: {}", sessionId, deviceFingerprint);
        
        return sessionId;
    }
    
    @Override
    public WalletConnectSession getSession(String sessionId) {
        WalletConnectSession session = sessionCache.get(sessionId);
        
        if (session == null) {
            log.warn("Session not found: {}", sessionId);
            return null;
        }
        
        // 检查是否过期
        if (session.isExpired()) {
            session.setStatus(WalletConnectSession.SessionStatus.EXPIRED);
            sessionCache.remove(sessionId);
            log.warn("Session expired: {}", sessionId);
            return null;
        }
        
        return session;
    }
    
    @Override
    public void markAsConnected(String sessionId, String walletAddress) {
        WalletConnectSession session = getSession(sessionId);
        if (session == null) {
            throw new RuntimeException("Session not found or expired: " + sessionId);
        }
        
        session.setStatus(WalletConnectSession.SessionStatus.CONNECTED);
        session.setWalletAddress(walletAddress);
        session.setConnectedAt(LocalDateTime.now());
        
        log.info("Session connected: {} with wallet: {}", sessionId, walletAddress);
    }
    
    @Override
    public void markAsVerified(String sessionId, String signature) {
        WalletConnectSession session = getSession(sessionId);
        if (session == null) {
            throw new RuntimeException("Session not found or expired: " + sessionId);
        }
        
        session.setStatus(WalletConnectSession.SessionStatus.VERIFIED);
        session.setSignature(signature);
        session.setVerifiedAt(LocalDateTime.now());
        
        log.info("Session verified: {}", sessionId);
    }
    
    @Override
    public String getNonceForSession(String sessionId) {
        WalletConnectSession session = getSession(sessionId);
        if (session == null) {
            return null;
        }
        
        return session.getNonce();
    }
    
    @Override
    public boolean validateSessionForLogin(String sessionId, String walletAddress) {
        WalletConnectSession session = getSession(sessionId);
        if (session == null) {
            return false;
        }
        
        // 检查状态
        if (session.getStatus() != WalletConnectSession.SessionStatus.CONNECTED) {
            log.warn("Session not in CONNECTED state: {} (status: {})", sessionId, session.getStatus());
            return false;
        }
        
        // 检查钱包地址
        if (!walletAddress.equalsIgnoreCase(session.getWalletAddress())) {
            log.warn("Wallet address mismatch for session: {} (expected: {}, got: {})", 
                    sessionId, session.getWalletAddress(), walletAddress);
            return false;
        }
        
        return true;
    }
    
    /**
     * 清理过期的 session
     */
    private void cleanupExpiredSessions() {
        int removedCount = sessionCache.size();
        
        sessionCache.entrySet().removeIf(entry -> {
            WalletConnectSession session = entry.getValue();
            if (session.isExpired()) {
                session.setStatus(WalletConnectSession.SessionStatus.EXPIRED);
                return true;
            }
            return false;
        });
        
        removedCount = removedCount - sessionCache.size();
        if (removedCount > 0) {
            log.debug("Cleaned up {} expired WalletConnect sessions", removedCount);
        }
    }
}

