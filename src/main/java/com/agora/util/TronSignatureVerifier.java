package com.agora.util;

import com.agora.config.AppDomainConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Tron 签名验证工具类
 * 用于验证 Tron 钱包签名
 * 
 * 注意：Tron 使用 SHA-256 哈希算法，而不是以太坊的 Keccak-256
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TronSignatureVerifier {
    
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
     * @param walletAddress Tron 钱包地址（Base58格式）
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
     * @param walletAddress Tron 钱包地址（Base58格式）
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
     * 验证签名（Tron 使用 SHA-256）
     * 
     * @param message 原始消息
     * @param signature 签名（hex 格式，65 字节，包含 r, s, v）
     * @param walletAddress Tron 钱包地址（Base58格式）
     * @return 验证是否通过
     */
    public boolean verifySignature(String message, String signature, String walletAddress) {
        if (message == null || signature == null || walletAddress == null) {
            log.warn("Invalid parameters: message, signature, or walletAddress is null");
            return false;
        }
        
        try {
            log.debug("Verifying Tron signature for wallet: {}", walletAddress);
            log.debug("Message to verify (length: {}): {}", message.length(), message.replace("\n", "\\n"));
            log.debug("Signature: {}", signature);
            
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
            
            log.debug("Signature v value: {} (0x{})", v & 0xFF, String.format("%02x", v & 0xFF));
            
            // 创建 ECDSA 签名对象
            ECDSASignature ecdsaSignature = new ECDSASignature(
                    Numeric.toBigInt(r),
                    Numeric.toBigInt(s)
            );
            
            // 尝试多种消息格式和 recovery ID 组合
            // 1. 直接对原始消息进行 SHA-256（最常见）
            // 2. 添加 Tron 消息前缀（某些钱包使用）
            // 3. 添加以太坊消息前缀（某些 Tron 钱包兼容）
            byte[] rawMessageBytes = message.getBytes(StandardCharsets.UTF_8);
            String[] messageFormats = {
                message, // 原始消息
                "\u0019TRON Signed Message:\n" + rawMessageBytes.length + message, // Tron 前缀格式（使用字节长度）
                "\u0019Ethereum Signed Message:\n" + rawMessageBytes.length + message // 以太坊前缀格式（使用字节长度）
            };
            
            log.debug("Message formats to try: raw ({} bytes), TRON prefix, Ethereum prefix", rawMessageBytes.length);
            
            // 尝试所有可能的 recovery ID (0-3)
            // 优先尝试基于 v 值计算的 recovery ID，但最终会尝试所有 0-3
            java.util.Set<Integer> recIdsToTry = new java.util.HashSet<>();
            
            // 首先尝试基于 v 值计算的 recovery ID
            int[] possibleRecIds = new int[4];
            possibleRecIds[0] = v - 27; // 标准格式
            possibleRecIds[1] = v - 35; // EIP-155 格式
            possibleRecIds[2] = v; // 直接使用 v 值
            possibleRecIds[3] = (v < 27) ? (v + 27 - 27) : (v - 27); // 调整后的标准格式
            
            log.debug("Calculated possible recovery IDs from v={}: {}, {}, {}, {}", 
                    v & 0xFF, possibleRecIds[0], possibleRecIds[1], possibleRecIds[2], possibleRecIds[3]);
            
            // 添加所有在有效范围内的 recovery ID
            for (int recId : possibleRecIds) {
                if (recId >= 0 && recId <= 3) {
                    recIdsToTry.add(recId);
                }
            }
            
            // 无论是否有有效值，都尝试所有 0-3（确保覆盖所有可能性）
            for (int i = 0; i <= 3; i++) {
                recIdsToTry.add(i);
            }
            
            log.debug("Trying {} message formats × 2 hash algorithms × {} recovery IDs = {} total combinations (v={}, 0x{})", 
                    messageFormats.length, recIdsToTry.size(), messageFormats.length * 2 * recIdsToTry.size(), 
                    v & 0xFF, String.format("%02x", v & 0xFF));
            
            // 优化：定义尝试顺序，优先尝试最可能的组合
            // 根据实际测试，TRON prefix + Keccak-256 是最常见的组合
            int[][] priorityOrder = {
                {1, 1, 0}, // TRON prefix, Keccak-256, recId 0 (最常见)
                {1, 1, 1}, // TRON prefix, Keccak-256, recId 1
                {1, 0, 0}, // TRON prefix, SHA-256, recId 0
                {1, 0, 1}, // TRON prefix, SHA-256, recId 1
                {0, 1, 0}, // raw, Keccak-256, recId 0
                {0, 1, 1}, // raw, Keccak-256, recId 1
                {0, 0, 0}, // raw, SHA-256, recId 0
                {0, 0, 1}, // raw, SHA-256, recId 1
                {2, 1, 0}, // Ethereum prefix, Keccak-256, recId 0
                {2, 1, 1}, // Ethereum prefix, Keccak-256, recId 1
                {2, 0, 0}, // Ethereum prefix, SHA-256, recId 0
                {2, 0, 1}, // Ethereum prefix, SHA-256, recId 1
            };
            
            // 预计算所有消息格式的哈希值
            byte[][][] allMessageHashes = new byte[messageFormats.length][2][];
            for (int msgIdx = 0; msgIdx < messageFormats.length; msgIdx++) {
                String msgToHash = messageFormats[msgIdx];
                byte[] messageBytes = msgToHash.getBytes(StandardCharsets.UTF_8);
                
                try {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    allMessageHashes[msgIdx][0] = digest.digest(messageBytes);
                } catch (Exception e) {
                    allMessageHashes[msgIdx][0] = null;
                }
                
                try {
                    allMessageHashes[msgIdx][1] = Hash.sha3(messageBytes);
                } catch (Exception e) {
                    allMessageHashes[msgIdx][1] = null;
                }
            }
            
            // 先尝试优先组合
            for (int[] combo : priorityOrder) {
                int msgIdx = combo[0];
                int hashIdx = combo[1];
                int recId = combo[2];
                
                if (msgIdx >= messageFormats.length || allMessageHashes[msgIdx][hashIdx] == null) {
                    continue;
                }
                
                byte[] messageHash = allMessageHashes[msgIdx][hashIdx];
                String hashAlgo = hashIdx == 0 ? "SHA-256" : "Keccak-256";
                String formatName = msgIdx == 0 ? "raw" : (msgIdx == 1 ? "TRON prefix" : "Ethereum prefix");
                
                try {
                    java.math.BigInteger publicKey = Sign.recoverFromSignature(recId, ecdsaSignature, messageHash);
                    if (publicKey == null) continue;
                    
                    String recoveredAddressHex = org.web3j.crypto.Keys.getAddress(publicKey);
                    if (recoveredAddressHex == null) continue;
                    
                    String recoveredAddressBase58 = TronAddressUtils.hexToBase58("0x" + recoveredAddressHex);
                    if (recoveredAddressBase58 == null) continue;
                    
                    log.debug("Tried format {} ({}), hash {}, recId {}: recovered = {}", 
                            msgIdx, formatName, hashAlgo, recId, recoveredAddressBase58);
                    
                    if (walletAddress.equalsIgnoreCase(recoveredAddressBase58)) {
                        log.info("✅ Tron signature verified successfully (format: {}, hash: {}, recId: {})", 
                                formatName, hashAlgo, recId);
                        return true;
                    }
                } catch (Exception e) {
                    // 继续尝试下一个组合
                }
            }
            
            // 如果优先组合都失败，尝试剩余的所有组合
            for (int msgIdx = 0; msgIdx < messageFormats.length; msgIdx++) {
                for (int hashIdx = 0; hashIdx < 2; hashIdx++) {
                    if (allMessageHashes[msgIdx][hashIdx] == null) continue;
                    
                    byte[] messageHash = allMessageHashes[msgIdx][hashIdx];
                    String hashAlgo = hashIdx == 0 ? "SHA-256" : "Keccak-256";
                    String formatName = msgIdx == 0 ? "raw" : (msgIdx == 1 ? "TRON prefix" : "Ethereum prefix");
                    
                    // 跳过已经在优先列表中尝试过的组合
                    boolean alreadyTried = false;
                    for (int[] combo : priorityOrder) {
                        if (combo[0] == msgIdx && combo[1] == hashIdx && 
                            (combo[2] == 0 || combo[2] == 1)) {
                            alreadyTried = true;
                            break;
                        }
                    }
                    if (alreadyTried) continue;
                    
                    for (int recId : recIdsToTry) {
                        // 跳过已经在优先列表中尝试过的 recId
                        if (recId == 0 || recId == 1) {
                            boolean recIdTried = false;
                            for (int[] combo : priorityOrder) {
                                if (combo[0] == msgIdx && combo[1] == hashIdx && combo[2] == recId) {
                                    recIdTried = true;
                                    break;
                                }
                            }
                            if (recIdTried) continue;
                        }
                        
                        try {
                            java.math.BigInteger publicKey = Sign.recoverFromSignature(recId, ecdsaSignature, messageHash);
                            if (publicKey == null) continue;
                            
                            String recoveredAddressHex = org.web3j.crypto.Keys.getAddress(publicKey);
                            if (recoveredAddressHex == null) continue;
                            
                            String recoveredAddressBase58 = TronAddressUtils.hexToBase58("0x" + recoveredAddressHex);
                            if (recoveredAddressBase58 == null) continue;
                            
                            log.debug("Tried format {} ({}), hash {}, recId {}: recovered = {}", 
                                    msgIdx, formatName, hashAlgo, recId, recoveredAddressBase58);
                            
                            if (walletAddress.equalsIgnoreCase(recoveredAddressBase58)) {
                                log.info("✅ Tron signature verified successfully (format: {}, hash: {}, recId: {})", 
                                        formatName, hashAlgo, recId);
                                return true;
                            }
                        } catch (Exception e) {
                            // 继续尝试下一个组合
                        }
                    }
                }
            }
            
            // 所有组合都失败，记录详细信息
            log.warn("Address mismatch after trying all combinations. Expected: {}, Tried {} message formats and {} recovery IDs. See debug logs for details.", 
                    walletAddress, messageFormats.length, recIdsToTry.size());
            return false;
            
        } catch (Exception e) {
            log.error("Error verifying Tron signature", e);
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
}

