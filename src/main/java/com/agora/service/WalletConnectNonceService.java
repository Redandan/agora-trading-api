package com.agora.service;

/**
 * Web3 钱包 Nonce 管理服务
 * 用于生成和管理登录用的 nonce（支持 WalletConnect、Tron 等所有 Web3 钱包）
 * 
 * 注意：虽然名称是 WalletConnectNonceService，但实际上用于所有 Web3 钱包登录
 * 包括 WalletConnect（以太坊）和 Tron 钱包
 */
public interface WalletConnectNonceService {
    /**
     * 生成 nonce 并存储
     * @param walletAddress 钱包地址
     * @return nonce 值
     */
    String generateNonce(String walletAddress);
    
    /**
     * 验证并消费 nonce（一次性使用）
     * @param walletAddress 钱包地址
     * @param nonce nonce 值
     * @return 是否有效
     */
    boolean validateAndConsumeNonce(String walletAddress, String nonce);
    
    /**
     * 获取 nonce 对应的时间戳
     * @param walletAddress 钱包地址
     * @param nonce nonce 值
     * @return 时间戳，如果 nonce 不存在返回 null
     */
    Long getNonceTimestamp(String walletAddress, String nonce);
    
    /**
     * 检查 nonce 是否有效（不消费，仅用于验证）
     * @param walletAddress 钱包地址
     * @param nonce nonce 值
     * @return 是否有效
     */
    boolean isNonceValid(String walletAddress, String nonce);
}

