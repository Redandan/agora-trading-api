package com.agora.service.impl;

import com.agora.service.WalletConnectNonceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Web3 钱包 Nonce 管理服务实现
 * 使用内存缓存存储 nonce，5 分钟后自动过期
 * 支持所有 Web3 钱包（WalletConnect、Tron 等）
 * 
 * 注意：虽然类名是 WalletConnectNonceServiceImpl，但实际上用于所有 Web3 钱包登录
 */
@Slf4j
@Service
public class WalletConnectNonceServiceImpl implements WalletConnectNonceService {
    
    private static final long NONCE_EXPIRATION_MINUTES = 5;
    
    /**
     * 存储结构：walletAddress -> (nonce -> timestamp)
     */
    private final Map<String, Map<String, Long>> nonceCache = new ConcurrentHashMap<>();
    
    /**
     * 标准化钱包地址
     * - 以太坊地址（0x开头，42个字符）：转换为小写
     * - Tron 地址（T开头，34个字符，Base58编码）：保持原样（大小写敏感）
     * 
     * 注意：此方法必须与 WalletConnectWebViewController.isTronAddress() 保持一致
     */
    private String normalizeAddress(String walletAddress) {
        if (walletAddress == null) {
            return null;
        }
        // Tron 地址：T 开头，34 个字符，Base58 编码（大小写敏感）
        // 必须符合 Base58 规则：不包含 0, O, I, l
        if (walletAddress.length() == 34 && walletAddress.startsWith("T") 
                && walletAddress.matches("^T[1-9A-HJ-NP-Za-km-z]{33}$")) {
            return walletAddress;
        }
        // 以太坊地址：0x 开头，转换为小写
        return walletAddress.toLowerCase();
    }
    
    @Override
    public String generateNonce(String walletAddress) {
        String normalizedAddress = normalizeAddress(walletAddress);
        String nonce = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis() / 1000;
        
        nonceCache.computeIfAbsent(normalizedAddress, k -> new ConcurrentHashMap<>())
                .put(nonce, timestamp);
        
        log.info("Generated nonce for wallet: {}", normalizedAddress);
        return nonce;
    }
    
    @Override
    public boolean validateAndConsumeNonce(String walletAddress, String nonce) {
        String normalizedAddress = normalizeAddress(walletAddress);
        
        Map<String, Long> walletNonces = nonceCache.get(normalizedAddress);
        if (walletNonces == null) {
            log.warn("No nonce found for wallet: {}", normalizedAddress);
            return false;
        }
        
        Long timestamp = walletNonces.get(nonce);
        if (timestamp == null) {
            log.warn("Nonce not found for wallet: {}", normalizedAddress);
            return false;
        }
        
        // 检查是否过期
        long currentTime = System.currentTimeMillis() / 1000;
        long expirationTime = timestamp + (NONCE_EXPIRATION_MINUTES * 60);
        
        if (currentTime > expirationTime) {
            log.warn("Nonce expired for wallet: {}", normalizedAddress);
            walletNonces.remove(nonce);
            return false;
        }
        
        // 消费 nonce（删除，确保一次性使用）
        walletNonces.remove(nonce);
        log.info("Nonce validated and consumed for wallet: {}", normalizedAddress);
        return true;
    }
    
    @Override
    public Long getNonceTimestamp(String walletAddress, String nonce) {
        String normalizedAddress = normalizeAddress(walletAddress);
        
        Map<String, Long> walletNonces = nonceCache.get(normalizedAddress);
        if (walletNonces == null) {
            return null;
        }
        
        return walletNonces.get(nonce);
    }
    
    @Override
    public boolean isNonceValid(String walletAddress, String nonce) {
        String normalizedAddress = normalizeAddress(walletAddress);
        
        Map<String, Long> walletNonces = nonceCache.get(normalizedAddress);
        if (walletNonces == null) {
            return false;
        }
        
        Long timestamp = walletNonces.get(nonce);
        if (timestamp == null) {
            return false;
        }
        
        // 检查是否过期
        long currentTime = System.currentTimeMillis() / 1000;
        long expirationTime = timestamp + (NONCE_EXPIRATION_MINUTES * 60);
        
        return currentTime <= expirationTime;
    }
    
    /**
     * 定期清理过期的 nonce（每 5 分钟执行一次）
     */
    @Scheduled(fixedRate = 5, timeUnit = TimeUnit.MINUTES)
    public void cleanupExpiredNonces() {
        long currentTime = System.currentTimeMillis() / 1000;
        long expirationTime = currentTime - (NONCE_EXPIRATION_MINUTES * 60);
        
        int cleanedCount = 0;
        for (Map.Entry<String, Map<String, Long>> entry : nonceCache.entrySet()) {
            Map<String, Long> walletNonces = entry.getValue();
            int beforeSize = walletNonces.size();
            walletNonces.entrySet().removeIf(nonceEntry -> nonceEntry.getValue() < expirationTime);
            cleanedCount += (beforeSize - walletNonces.size());
            
            if (walletNonces.isEmpty()) {
                nonceCache.remove(entry.getKey());
            }
        }
        
        if (cleanedCount > 0) {
            log.debug("Cleaned up {} expired nonces", cleanedCount);
        }
    }
}

