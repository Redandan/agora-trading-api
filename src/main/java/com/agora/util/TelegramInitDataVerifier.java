package com.agora.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.util.*;

/**
 * Telegram WebApp initData 验证工具类
 * 
 * 用于验证 Telegram Web App 发送的 initData 的签名
 * 参考：https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app
 */
@Slf4j
@Component
public class TelegramInitDataVerifier {
    
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String WEB_APP_DATA = "WebAppData";
    private static final long AUTH_DATE_EXPIRATION_SECONDS = 86400; // 24 小时

    public static void main(String[] args) {
        String botToken = getArgOrEnv(args, 0, "TELEGRAM_BOT_TOKEN");
        String initData = getArgOrEnv(args, 1, "TELEGRAM_INIT_DATA");
        boolean demoMode = false;

        if (botToken == null || botToken.isEmpty() || initData == null || initData.isEmpty()) {
            demoMode = true;
            botToken = "123456:FAKE_DEMO_BOT_TOKEN_FOR_LOCAL_TEST";
            initData = buildDemoInitData(botToken);
        }

        TelegramInitDataVerifier verifier = new TelegramInitDataVerifier();
        boolean valid = verifier.verify(initData, botToken);
        Map<String, String> userInfo = verifier.parseUserInfo(initData);

        System.out.println("----------------------------------------");
        System.out.println("Telegram initData verification result");
        if (demoMode) {
            System.out.println("mode = DEMO (auto-generated local test data)");
            System.out.println("tip  = pass args/env to verify real Telegram initData");
            System.out.println("demo initData = " + initData);
        }
        System.out.println("isValid = " + valid);
        System.out.println("userInfo = " + userInfo);
        System.out.println("----------------------------------------");
    }

    private static String buildDemoInitData(String botToken) {
        TelegramInitDataVerifier verifier = new TelegramInitDataVerifier();

        Map<String, String> params = new HashMap<>();
        params.put("auth_date", String.valueOf(System.currentTimeMillis() / 1000));
        params.put("query_id", "AAHdF6IQAAAAAN0XohDhrOrc");
        params.put("user", "{\"id\":123456789,\"first_name\":\"Demo\",\"last_name\":\"User\",\"username\":\"demo_user\",\"language_code\":\"en\"}");

        String dataCheckString = verifier.buildDataCheckString(params);
        byte[] secretKey = verifier.generateSecretKey(botToken);
        String hash = verifier.calculateHmacSha256Hex(dataCheckString, secretKey);

        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);

        StringBuilder initData = new StringBuilder();
        for (String key : keys) {
            if (initData.length() > 0) {
                initData.append("&");
            }
            initData.append(key)
                    .append("=")
                    .append(encodeUrl(params.get(key)));
        }
        initData.append("&hash=").append(hash);
        return initData.toString();
    }

    private static String getArgOrEnv(String[] args, int index, String envKey) {
        if (args != null && args.length > index && args[index] != null && !args[index].isEmpty()) {
            return args[index];
        }
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isEmpty()) {
            return envValue;
        }
        return null;
    }
    
    /**
     * 验证 initData 的签名
     * 
     * @param initData 从 Telegram Web App 获取的 initData 字符串
     * @param botToken Bot Token（用于生成 secret_key）
     * @return 验证是否通过
     */
    public boolean verify(String initData, String botToken) {
        if (initData == null || initData.isEmpty()) {
            log.warn("initData is null or empty");
            return false;
        }
        
        if (botToken == null || botToken.isEmpty()) {
            log.warn("Bot token is null or empty");
            return false;
        }
        
        try {
            // 解析 initData
            Map<String, String> params = parseInitData(initData);
            
            // 提取 hash
            String hash = params.remove("hash");
            if (hash == null || hash.isEmpty()) {
                log.warn("Hash not found in initData");
                return false;
            }
            
            // 验证时间戳
            String authDateStr = params.get("auth_date");
            if (authDateStr != null) {
                long authDate = Long.parseLong(authDateStr);
                long currentTime = System.currentTimeMillis() / 1000;
                if (currentTime - authDate > AUTH_DATE_EXPIRATION_SECONDS) {
                    log.warn("initData auth_date expired: {} (current: {})", authDate, currentTime);
                    return false;
                }
            }
            
            // 生成 secret_key（Telegram WebApp 规范：HMAC_SHA256(key="WebAppData", data=botToken)）
            byte[] secretKey = generateSecretKey(botToken);
            
            // 重新构建数据字符串（按 key 排序）
            String dataCheckString = buildDataCheckString(params);
            
            // 计算 HMAC-SHA256
            String calculatedHash = calculateHmacSha256Hex(dataCheckString, secretKey);
            
            // 比对 hash（使用安全比较防止时序攻击）
            boolean isValid = secureCompare(hash, calculatedHash);
            
            if (!isValid) {
                log.warn("initData hash mismatch. Expected: {}, Got: {}", calculatedHash, hash);
            }
            
            return isValid;
        } catch (Exception e) {
            log.error("Error verifying initData", e);
            return false;
        }
    }
    
    /**
     * 从 initData 中解析用户信息
     * 
     * @param initData initData 字符串
     * @return 用户信息 Map，包含 id、first_name、username 等
     */
    public Map<String, String> parseUserInfo(String initData) {
        Map<String, String> params = parseInitData(initData);
        Map<String, String> userInfo = new HashMap<>();
        
        String userStr = params.get("user");
        if (userStr != null && !userStr.isEmpty()) {
            // user 参数在 parseInitData 中已经完成 URL 解码
            try {
                // 简单解析 JSON（实际项目中可以使用 Jackson 等库）
                userInfo = parseJsonUser(userStr);
            } catch (Exception e) {
                log.error("Failed to parse user info from initData", e);
            }
        }
        
        return userInfo;
    }
    
    /**
     * 解析 initData 字符串为参数 Map
     */
    private Map<String, String> parseInitData(String initData) {
        Map<String, String> params = new HashMap<>();
        String[] pairs = initData.split("&");
        
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = pair.substring(0, idx);
                String value = pair.substring(idx + 1);
                params.put(decodeUrl(key), decodeUrl(value));
            }
        }
        
        return params;
    }
    
    /**
     * 生成 secret_key
     * Telegram 规范：HMAC_SHA256(key="WebAppData", data=botToken)
     */
    private byte[] generateSecretKey(String botToken) {
        return calculateHmacSha256Bytes(botToken, WEB_APP_DATA.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * 构建数据检查字符串（按 key 排序）
     */
    private String buildDataCheckString(Map<String, String> params) {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            String key = keys.get(i);
            sb.append(key).append("=").append(params.get(key));
        }
        
        return sb.toString();
    }
    
    /**
     * 计算 HMAC-SHA256
     */
    private String calculateHmacSha256Hex(String data, byte[] secretKey) {
        return bytesToHex(calculateHmacSha256Bytes(data, secretKey));
    }

    /**
     * 计算 HMAC-SHA256 原始字节
     */
    private byte[] calculateHmacSha256Bytes(String data, byte[] secretKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey, HMAC_SHA256);
            mac.init(secretKeySpec);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Failed to calculate HMAC-SHA256", e);
            throw new RuntimeException("Failed to calculate HMAC-SHA256", e);
        }
    }

    private String decodeUrl(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            log.warn("Failed to URL decode value, fallback to raw text");
            return value;
        }
    }

    private static String encodeUrl(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }
    
    /**
     * 安全比较两个字符串（防止时序攻击）
     */
    private boolean secureCompare(String a, String b) {
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
    
    /**
     * 解析 JSON 格式的用户信息（简单实现）
     * 实际项目中可以使用 Jackson 等库
     */
    private Map<String, String> parseJsonUser(String json) {
        Map<String, String> userInfo = new HashMap<>();
        
        // 移除花括号
        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length() - 1);
        }
        
        // 解析键值对
        String[] pairs = json.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                String key = kv[0].trim().replace("\"", "");
                String value = kv[1].trim().replace("\"", "");
                userInfo.put(key, value);
            }
        }
        
        return userInfo;
    }
    
    /**
     * 字节数组转十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

