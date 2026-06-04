package com.agora.util;

import com.agora.config.AppDomainConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * WalletConnect 签名验证工具类
 * 用于验证以太坊钱包签名（EIP-191 标准）
 * 
 * 注意：需要添加 Web3j 依赖才能使用完整的签名验证功能
 * 如果未添加 Web3j，此工具类提供基础的消息构建功能
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletConnectSignatureVerifier {
    
    private final AppDomainConfig appDomainConfig;
    
    /**
     * 获取域名
     */
    private String getDomain() {
        return appDomainConfig.getWeb3Domain();
    }
    
    /**
     * 构建登录消息
     * 格式：简单的认证消息，包含钱包地址、nonce 和时间戳
     * 
     * @param walletAddress 钱包地址
     * @param nonce nonce 值
     * @param timestamp 时间戳
     * @return 构建的消息
     */
    public String buildLoginMessage(String walletAddress, String nonce, Long timestamp) {
        return buildLoginMessage(walletAddress, nonce, timestamp, null);
    }
    
    /**
     * 构建登录消息（支持自定义域名）
     * 格式：简单的认证消息，包含钱包地址、nonce 和时间戳
     * 
     * @param walletAddress 钱包地址
     * @param nonce nonce 值
     * @param timestamp 时间戳
     * @param domain 域名（如果为 null，则使用默认域名）
     * @return 构建的消息
     */
    public String buildLoginMessage(String walletAddress, String nonce, Long timestamp, String domain) {
        String finalDomain = domain != null ? domain : getDomain();
        return String.format(
            "Please sign this message to authenticate.\n\n" +
            "Wallet: %s\n" +
            "Nonce: %s\n" +
            "Timestamp: %d\n" +
            "Domain: %s",
            walletAddress,
            nonce,
            timestamp,
            finalDomain
        );
    }
    
    /**
     * EIP-191 消息前缀
     */
    private static final String EIP191_PREFIX = "\u0019Ethereum Signed Message:\n";
    
    /**
     * 验证签名（使用 Web3j）
     * 
     * @param message 原始消息
     * @param signature 签名（hex 格式，65 字节，包含 r, s, v）
     * @param walletAddress 钱包地址（用于验证签名是否来自该地址）
     * @return 验证是否通过
     */
    public boolean verifySignature(String message, String signature, String walletAddress) {
        if (message == null || signature == null || walletAddress == null) {
            log.warn("Invalid parameters: message, signature, or walletAddress is null");
            return false;
        }
        
        try {
            log.debug("Verifying signature for wallet: {}", walletAddress);
            log.debug("Message to verify (length: {}): {}", message.length(), message.replace("\n", "\\n"));
            log.debug("Signature: {}", signature);
            
            // 构建 EIP-191 格式的消息
            String prefixedMessage = EIP191_PREFIX + message.length() + message;
            byte[] messageBytes = prefixedMessage.getBytes(StandardCharsets.UTF_8);
            
            log.debug("Prefixed message length: {}", prefixedMessage.length());
            
            // 对消息进行 Keccak-256 哈希
            byte[] messageHash = Hash.sha3(messageBytes);
            
            // 解析签名
            byte[] signatureBytes = Numeric.hexStringToByteArray(signature);
            
            if (signatureBytes.length != 65) {
                log.warn("Invalid signature length: {} (expected 65)", signatureBytes.length);
                return false;
            }
            
            // 提取 r, s, v
            byte[] r = Arrays.copyOfRange(signatureBytes, 0, 32);
            byte[] s = Arrays.copyOfRange(signatureBytes, 32, 64);
            byte v = signatureBytes[64];
            
            // 调整 v 值（EIP-155 兼容）
            if (v < 27) {
                v += 27;
            }
            
            // 创建 ECDSA 签名对象
            ECDSASignature ecdsaSignature = new ECDSASignature(
                    Numeric.toBigInt(r),
                    Numeric.toBigInt(s)
            );
            
            // 计算 recovery ID
            int recId = v - 27;
            if (recId < 0 || recId > 1) {
                // 尝试 EIP-155 格式
                recId = v - 35;
                if (recId < 0 || recId > 1) {
                    log.warn("Invalid recovery ID: {}", v);
                    return false;
                }
            }
            
            // 恢复公钥
            java.math.BigInteger publicKey = Sign.recoverFromSignature(recId, ecdsaSignature, messageHash);
            
            if (publicKey == null) {
                log.warn("Failed to recover public key from signature");
                return false;
            }
            
            // 从公钥计算地址
            String recoveredAddress = Keys.getAddress(publicKey);
            
            if (recoveredAddress == null) {
                log.warn("Failed to recover address from public key");
                return false;
            }
            
            // 标准化地址：统一移除 0x 前缀，转小写
            String normalizedExpected = normalizeAddress(walletAddress);
            String normalizedRecovered = normalizeAddress(recoveredAddress);
            boolean isValid = normalizedExpected.equals(normalizedRecovered);
            
            if (!isValid) {
                log.warn("Address mismatch. Expected: {} (normalized: {}), Recovered: {} (normalized: {})", 
                        walletAddress, normalizedExpected, recoveredAddress, normalizedRecovered);
            } else {
                log.info("Signature verified successfully for wallet: {}", walletAddress);
            }
            
            return isValid;
            
        } catch (Exception e) {
            log.error("Error verifying signature", e);
            return false;
        }
    }
    
    /**
     * 验证时间戳是否在有效范围内（5分钟内）
     * 
     * @param timestamp 时间戳
     * @return 是否有效
     */
    public boolean isTimestampValid(Long timestamp) {
        if (timestamp == null) {
            return false;
        }
        
        long currentTime = System.currentTimeMillis() / 1000;
        long timeDiff = Math.abs(currentTime - timestamp);
        
        // 允许 5 分钟的时间差
        return timeDiff <= 300;
    }
    
    /**
     * 标准化地址：移除 0x 前缀，转小写
     * 
     * @param address 地址（可能包含或不包含 0x 前缀）
     * @return 标准化后的地址（小写，无 0x 前缀）
     */
    private String normalizeAddress(String address) {
        if (address == null) {
            return null;
        }
        // 移除 0x 前缀（如果存在），转小写
        String normalized = address.toLowerCase();
        if (normalized.startsWith("0x")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}

