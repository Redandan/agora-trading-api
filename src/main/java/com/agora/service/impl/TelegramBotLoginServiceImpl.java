package com.agora.service.impl;

import com.agora.dto.auth.LoginResult;
import com.agora.enums.system.OAuthProvider;
import com.agora.enums.system.RegistrationMethodEnum;
import com.agora.enums.system.UserStatusEnum;
import com.agora.exception.BusinessException;
import com.agora.model.User;
import com.agora.model.UserOAuthBinding;
import com.agora.repository.system.UserOAuthBindingRepository;
import com.agora.repository.system.UserRepository;
import com.agora.service.TelegramBotLoginService;
import com.agora.service.TelegramService;
import com.agora.util.JwtUtil;
import com.agora.util.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Telegram Bot 登录服务实现
 * 新方案：Web 端生成 loginToken -> Deep Link -> Bot 处理 -> 生成 4 位验证码 -> 用户输入验证码完成登录
 */
@Slf4j
@Service
public class TelegramBotLoginServiceImpl implements TelegramBotLoginService {
    
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int LOGIN_TOKEN_LENGTH = 32;
    private static final long LOGIN_TOKEN_EXPIRATION_MINUTES = 10;
    private static final int MAX_VERIFICATION_ATTEMPTS = 5; // 最大验证码尝试次数
    private static final long RATE_LIMIT_SECONDS = 30; // 频率限制：30秒
    private static final DateTimeFormatter CHINESE_DATETIME_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy年MM月dd日 HH:mm:ss")
            .toFormatter()
            .withZone(ZoneId.of("Asia/Taipei"));
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Taipei");
    
    private final TelegramService telegramService;
    private final UserRepository userRepository;
    private final UserOAuthBindingRepository oauthBindingRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    
    
    // 存储 loginToken 信息：loginToken -> LoginTokenInfo
    private final Map<String, LoginTokenInfo> loginTokenStorage = new ConcurrentHashMap<>();
    
    // 存储设备IP的登录Token映射：deviceKey (IP + DeviceFingerprint) -> loginToken
    // 用于实现频率限制：同设备IP每30秒只能发起一笔，新的覆盖旧的
    private final Map<String, String> deviceTokenMap = new ConcurrentHashMap<>();
    
    /**
     * LoginToken 信息
     */
    private static class LoginTokenInfo {
        long expirationTime;
        boolean used;
        Long chatId;
        String username; // Telegram 用户名
        String verificationCode; // 4 位验证码
        String clientIp; // 客户端 IP 地址
        String deviceFingerprint; // 设备指纹
        int verificationAttempts; // 验证码尝试次数
        long createdAt; // 创建时间（用于频率限制）
        
        LoginTokenInfo(long expirationTime, String clientIp, String deviceFingerprint) {
            this.expirationTime = expirationTime;
            this.used = false;
            this.clientIp = clientIp;
            this.deviceFingerprint = deviceFingerprint;
            this.verificationAttempts = 0;
            this.createdAt = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }
        
        boolean isMaxAttemptsReached() {
            return verificationAttempts >= MAX_VERIFICATION_ATTEMPTS;
        }
    }
    
    public TelegramBotLoginServiceImpl(TelegramService telegramService,
                                      UserRepository userRepository, UserOAuthBindingRepository oauthBindingRepository,
                                      JwtUtil jwtUtil, PasswordEncoder passwordEncoder) {
        this.telegramService = telegramService;
        this.userRepository = userRepository;
        this.oauthBindingRepository = oauthBindingRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
    }
    
    @Override
    public String generateLoginToken(String redirectUri, String clientIp, String deviceFingerprint) {
        // redirectUri 参数保留以保持 API 兼容性，但实际不再使用
        log.info("Generating login token for clientIp: {}, deviceFingerprint: {}", clientIp, deviceFingerprint);
        
        // 构建设备唯一标识（用于频率限制）
        String deviceKey = buildDeviceKey(clientIp, deviceFingerprint);
        
        // 检查是否存在未过期的旧 Token
        String existingToken = deviceTokenMap.get(deviceKey);
        if (existingToken != null) {
            LoginTokenInfo existingTokenInfo = loginTokenStorage.get(existingToken);
            if (existingTokenInfo != null && !existingTokenInfo.isExpired() && !existingTokenInfo.used) {
                // 检查是否在30秒内
                long timeSinceCreation = System.currentTimeMillis() - existingTokenInfo.createdAt;
                if (timeSinceCreation < TimeUnit.SECONDS.toMillis(RATE_LIMIT_SECONDS)) {
                    // 删除旧的 Token
                    loginTokenStorage.remove(existingToken);
                    log.info("Removed old login token due to rate limit: {} (created {}ms ago)", 
                            existingToken, timeSinceCreation);
                }
            }
        }
        
        // 生成新的 loginToken
        String loginToken = generateRandomString(LOGIN_TOKEN_LENGTH);
        
        // 存储 loginToken 信息（包含 IP 和设备指纹）
        long expirationTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(LOGIN_TOKEN_EXPIRATION_MINUTES);
        LoginTokenInfo tokenInfo = new LoginTokenInfo(expirationTime, clientIp, deviceFingerprint);
        loginTokenStorage.put(loginToken, tokenInfo);
        
        // 更新设备Token映射（新的覆盖旧的）
        deviceTokenMap.put(deviceKey, loginToken);
        
        log.info("Generated login token: {} (expires in {} minutes, IP: {}, Device: {})", 
                loginToken, LOGIN_TOKEN_EXPIRATION_MINUTES, clientIp, deviceFingerprint);
        return loginToken;
    }
    
    /**
     * 构建设备唯一标识（IP + 设备指纹）
     */
    private String buildDeviceKey(String clientIp, String deviceFingerprint) {
        return clientIp + ":" + deviceFingerprint;
    }
    
    @Override
    public void handleStartCommand(Long chatId, String username, String loginToken) {
        log.info("Handling /start command: chatId={}, username={}, loginToken={}", chatId, username, loginToken);
        
        // 验证 loginToken
        LoginTokenInfo tokenInfo = loginTokenStorage.get(loginToken);
        if (tokenInfo == null || tokenInfo.isExpired() || tokenInfo.used) {
            log.warn("Invalid, expired or used loginToken: {}", loginToken);
            sendErrorMessage(chatId, "登入連結已過期或已使用，請重新開始");
            return;
        }
        
        // 保存 chatId 和 username 到 tokenInfo
        tokenInfo.chatId = chatId;
        tokenInfo.username = username;
        
        // 生成 4 位验证码
        String verificationCode = generateVerificationCode();
        tokenInfo.verificationCode = verificationCode;
        
        // 发送验证码给用户
        sendVerificationCode(chatId, verificationCode);
    }
    
    @Override
    @Transactional
    public LoginResult verifyVerificationCode(String loginToken, String verificationCode, String clientIp, String deviceFingerprint) {
        log.info("Verifying verification code: loginToken={}, code={}, clientIp={}, deviceFingerprint={}", 
                loginToken, verificationCode, clientIp, deviceFingerprint);
        
        // 验证 loginToken
        LoginTokenInfo tokenInfo = loginTokenStorage.get(loginToken);
        if (tokenInfo == null || tokenInfo.isExpired()) {
            log.warn("Invalid or expired loginToken: {}", loginToken);
            throw new BusinessException("登入連結已過期，請重新開始");
        }
        
        if (tokenInfo.used) {
            log.warn("LoginToken already used: {}", loginToken);
            throw new BusinessException("驗證碼已使用，請重新開始");
        }
        
        if (tokenInfo.chatId == null) {
            log.warn("ChatId not set for loginToken: {}", loginToken);
            throw new BusinessException("請先在 Telegram Bot 中點擊登入連結");
        }
        
        // 验证 IP 地址
        if (tokenInfo.clientIp == null || !tokenInfo.clientIp.equals(clientIp)) {
            log.warn("IP address mismatch: expected={}, got={}, loginToken={}", 
                    tokenInfo.clientIp, clientIp, loginToken);
            throw new BusinessException("IP 地址不匹配，請使用相同的設備和網絡環境");
        }
        
        // 验证设备指纹
        if (tokenInfo.deviceFingerprint == null || !tokenInfo.deviceFingerprint.equals(deviceFingerprint)) {
            log.warn("Device fingerprint mismatch: expected={}, got={}, loginToken={}", 
                    tokenInfo.deviceFingerprint, deviceFingerprint, loginToken);
            throw new BusinessException("設備不匹配，請使用相同的設備");
        }
        
        // 检查验证码尝试次数
        if (tokenInfo.isMaxAttemptsReached()) {
            log.warn("Max verification attempts reached for loginToken: {}", loginToken);
            // 删除过期的 Token
            loginTokenStorage.remove(loginToken);
            String deviceKey = buildDeviceKey(clientIp, deviceFingerprint);
            deviceTokenMap.remove(deviceKey);
            throw new BusinessException("驗證碼嘗試次數過多，請重新開始登入流程");
        }
        
        // 验证验证码
        if (tokenInfo.verificationCode == null || !tokenInfo.verificationCode.equals(verificationCode)) {
            tokenInfo.verificationAttempts++;
            int remainingAttempts = MAX_VERIFICATION_ATTEMPTS - tokenInfo.verificationAttempts;
            log.warn("Invalid verification code: expected={}, got={}, attempts={}/{}", 
                    tokenInfo.verificationCode, verificationCode, 
                    tokenInfo.verificationAttempts, MAX_VERIFICATION_ATTEMPTS);
            
            if (remainingAttempts > 0) {
                throw new BusinessException(String.format("驗證碼錯誤，還剩 %d 次嘗試機會", remainingAttempts));
            } else {
                // 达到最大尝试次数，删除 Token
                loginTokenStorage.remove(loginToken);
                String deviceKey = buildDeviceKey(clientIp, deviceFingerprint);
                deviceTokenMap.remove(deviceKey);
                throw new BusinessException("驗證碼嘗試次數過多，請重新開始登入流程");
            }
        }
        
        // 标记 loginToken 为已用
        tokenInfo.used = true;
        
        // 清理设备Token映射
        String deviceKey = buildDeviceKey(clientIp, deviceFingerprint);
        deviceTokenMap.remove(deviceKey);
        
        try {
            // 获取用户名（从 OAuth binding 或创建新用户时保存）
            String providerId = String.valueOf(tokenInfo.chatId);
            UserOAuthBinding existingBinding = oauthBindingRepository
                    .findByOauthProviderAndOauthProviderId(OAuthProvider.TELEGRAM_BOT, providerId)
                    .orElse(null);
            
            User user;
            String telegramUsername = null;
            
            if (existingBinding != null) {
                // 已存在绑定，直接登录
                log.info("Found existing OAuth binding for Telegram Bot, chatId: {}", tokenInfo.chatId);
                user = userRepository.findById(existingBinding.getUserId())
                        .orElseThrow(() -> new BusinessException("用戶不存在"));
                telegramUsername = existingBinding.getOauthName();
            } else {
                // 新绑定，需要创建用户
                log.info("Creating new user for Telegram Bot, chatId: {}, username: {}", tokenInfo.chatId, tokenInfo.username);
                telegramUsername = tokenInfo.username != null ? tokenInfo.username : "tg_user_" + tokenInfo.chatId;
                user = createUserFromTelegram(tokenInfo.chatId, telegramUsername, clientIp);
                
                // 创建绑定
                UserOAuthBinding binding = new UserOAuthBinding();
                binding.setUserId(user.getId());
                binding.setOauthProvider(OAuthProvider.TELEGRAM_BOT);
                binding.setOauthProviderId(providerId);
                binding.setOauthName(telegramUsername);
                binding.setOauthAvatar(null);
                binding.setTelegramUserId(providerId); // 设置 telegram_user_id 用于对赌功能
                binding.setIsPrimary(true);
                oauthBindingRepository.save(binding);
            }
            
            // 检查用户状态
            if (user.getStatus() != UserStatusEnum.ACTIVE) {
                throw new BusinessException("帳號已被禁用或暫停");
            }
            
            // 生成 JWT Token（包含设备信息）
            String accessToken = jwtUtil.generateToken(user, deviceFingerprint, clientIp);
            String refreshToken = jwtUtil.generateRefreshToken(user);
            
            // 创建登录结果
            LoginResult result = new LoginResult();
            result.setToken(accessToken);
            result.setRefreshToken(refreshToken);
        result.setTokenIssuedAt(DateUtil.formatDateWithZone(jwtUtil.extractIssuedAt(accessToken)));
        result.setTokenExpiration(DateUtil.formatDateWithZone(jwtUtil.extractExpiration(accessToken)));
        result.setRefreshTokenExpiration(DateUtil.formatDateWithZone(jwtUtil.extractExpiration(refreshToken)));
            result.setUserId(user.getId());
            result.setUsername(user.getUsername());
            
            // 发送详细的成功消息给用户
            boolean isNewUser = existingBinding == null;
            String successMessage = buildSuccessMessage(user, telegramUsername, isNewUser, clientIp, deviceFingerprint);
            sendMessageToUser(tokenInfo.chatId, successMessage, true);
            
            log.info("Verification code verified successfully for user: {} (chatId: {}, isNewUser: {})", 
                    user.getUsername(), tokenInfo.chatId, isNewUser);
            return result;
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to verify verification code", e);
            throw new BusinessException("登入失敗，請稍後再試");
        }
    }
    
    @Override
    public TelegramBotLoginService.TelegramUserInfo verifyForBinding(String loginToken, String verificationCode, 
                                                                     String clientIp, String deviceFingerprint) {
        log.info("Verifying Telegram Bot for binding: loginToken={}, code={}, clientIp={}, deviceFingerprint={}", 
                loginToken, verificationCode, clientIp, deviceFingerprint);
        
        // 验证 loginToken
        LoginTokenInfo tokenInfo = loginTokenStorage.get(loginToken);
        if (tokenInfo == null || tokenInfo.isExpired()) {
            log.warn("Invalid or expired loginToken: {}", loginToken);
            throw new BusinessException("登入連結已過期，請重新開始");
        }
        
        if (tokenInfo.used) {
            log.warn("LoginToken already used: {}", loginToken);
            throw new BusinessException("驗證碼已使用，請重新開始");
        }
        
        if (tokenInfo.chatId == null) {
            log.warn("ChatId not set for loginToken: {}", loginToken);
            throw new BusinessException("請先在 Telegram Bot 中點擊登入連結");
        }
        
        // 验证 IP 地址
        if (tokenInfo.clientIp == null || !tokenInfo.clientIp.equals(clientIp)) {
            log.warn("IP address mismatch: expected={}, got={}, loginToken={}", 
                    tokenInfo.clientIp, clientIp, loginToken);
            throw new BusinessException("IP 地址不匹配，請使用相同的設備和網絡環境");
        }
        
        // 验证设备指纹
        if (tokenInfo.deviceFingerprint == null || !tokenInfo.deviceFingerprint.equals(deviceFingerprint)) {
            log.warn("Device fingerprint mismatch: expected={}, got={}, loginToken={}", 
                    tokenInfo.deviceFingerprint, deviceFingerprint, loginToken);
            throw new BusinessException("設備不匹配，請使用相同的設備");
        }
        
        // 检查验证码尝试次数
        if (tokenInfo.isMaxAttemptsReached()) {
            log.warn("Max verification attempts reached for loginToken: {}", loginToken);
            loginTokenStorage.remove(loginToken);
            String deviceKey = buildDeviceKey(clientIp, deviceFingerprint);
            deviceTokenMap.remove(deviceKey);
            throw new BusinessException("驗證碼嘗試次數過多，請重新開始登入流程");
        }
        
        // 验证验证码
        if (tokenInfo.verificationCode == null || !tokenInfo.verificationCode.equals(verificationCode)) {
            tokenInfo.verificationAttempts++;
            int remainingAttempts = MAX_VERIFICATION_ATTEMPTS - tokenInfo.verificationAttempts;
            log.warn("Invalid verification code: expected={}, got={}, attempts={}/{}", 
                    tokenInfo.verificationCode, verificationCode, 
                    tokenInfo.verificationAttempts, MAX_VERIFICATION_ATTEMPTS);
            
            if (remainingAttempts > 0) {
                throw new BusinessException(String.format("驗證碼錯誤，還剩 %d 次嘗試機會", remainingAttempts));
            } else {
                // 达到最大尝试次数，删除 Token
                loginTokenStorage.remove(loginToken);
                String deviceKey = buildDeviceKey(clientIp, deviceFingerprint);
                deviceTokenMap.remove(deviceKey);
                throw new BusinessException("驗證碼嘗試次數過多，請重新開始登入流程");
            }
        }
        
        // 验证成功，标记loginToken为已用（但不删除，因为绑定可能失败需要重试）
        // 注意：绑定成功后需要手动删除
        tokenInfo.used = true;
        
        // 返回Telegram用户信息
        String telegramUsername = tokenInfo.username != null ? tokenInfo.username : "tg_user_" + tokenInfo.chatId;
        return new TelegramBotLoginService.TelegramUserInfo(tokenInfo.chatId, telegramUsername);
    }
    
    @Override
    public void removeLoginToken(String loginToken) {
        loginTokenStorage.remove(loginToken);
        log.debug("Removed loginToken after successful binding: {}", loginToken);
    }
    
    /**
     * 生成随机字符串
     */
    private String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(SECURE_RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
    
    /**
     * 生成 4 位数字验证码
     */
    private String generateVerificationCode() {
        // 生成 1000-9999 之间的随机数
        int code = 1000 + SECURE_RANDOM.nextInt(9000);
        return String.format("%04d", code);
    }
    
    /**
     * 从 Telegram 信息创建新用户
     */
    private User createUserFromTelegram(Long chatId, String telegramUsername, String regIp) {
        User user = new User();
        
        // 生成唯一用户名
        String username = "tg_" + telegramUsername.replace("@", "") + "_" + UUID.randomUUID().toString().substring(0, 8);
        
        // 确保用户名唯一
        int suffix = 1;
        String originalUsername = username;
        while (userRepository.existsByUsername(username)) {
            username = originalUsername + "_" + suffix;
            suffix++;
        }
        
        user.setUsername(username);
        user.setEmail(null); // Telegram 不提供邮箱
        user.setName(telegramUsername);
        user.setAvatar(null);
        user.setRole("USER");
        user.setStatus(UserStatusEnum.ACTIVE);
        user.setEmailVerified(false);
        user.setTwoFactorEnabled(false);
        user.setRegistrationMethod(RegistrationMethodEnum.TELEGRAM_BOT);
        user.setRegistrationIp(regIp);

        // 生成随机密码（用户无法使用密码登录）
        String randomPassword = UUID.randomUUID().toString();
        user.setPassword(passwordEncoder.encode(randomPassword));

        return userRepository.save(user);
    }

    /**
     * 发送验证码给用户
     */
    private void sendVerificationCode(Long chatId, String verificationCode) {
        String message = String.format(
            "🔐 <b>登入驗證碼</b>\n\n" +
            "您的登入驗證碼是：\n" +
            "<b>%s</b>\n\n" +
            "請在前端輸入此驗證碼完成登入。\n" +
            "驗證碼將在 %d 分鐘後過期。",
            verificationCode, LOGIN_TOKEN_EXPIRATION_MINUTES
        );
        sendMessageToUser(chatId, message, true);
        log.info("Verification code sent to chatId: {}", chatId);
    }
    
    /**
     * 发送错误消息
     */
    private void sendErrorMessage(Long chatId, String errorMessage) {
        String message = "❌ " + errorMessage;
        sendMessageToUser(chatId, message, false);
    }
    
    /**
     * 发送消息给用户
     */
    private void sendMessageToUser(Long chatId, String message, boolean useHtml) {
        try {
            telegramService.sendMessageToUser(chatId, message, useHtml);
        } catch (Exception e) {
            log.error("Failed to send message to user: {}", chatId, e);
        }
    }
    
    
    /**
     * 构建登录成功消息
     */
    private String buildSuccessMessage(User user, String telegramUsername, boolean isNewUser, 
                                      String clientIp, String deviceFingerprint) {
        StringBuilder message = new StringBuilder();
        
        // 标题
        message.append("✅ <b>登入成功！</b>\n\n");
        
        // 欢迎信息
        if (isNewUser) {
            message.append("🎉 <b>歡迎加入 Agora Market！</b>\n\n");
            message.append("您的帳號已成功創建並登入。\n\n");
        } else {
            message.append("👋 <b>歡迎回來！</b>\n\n");
        }
        
        // 用户信息
        message.append("📋 <b>帳號資訊</b>\n");
        message.append("━━━━━━━━━━━━━━━━\n");
        
        // 显示名称（优先使用用户设置的名称，否则使用 Telegram 用户名）
        String displayName = user.getName();
        if (displayName == null || displayName.isEmpty()) {
            displayName = telegramUsername != null ? telegramUsername : user.getUsername();
        }
        message.append("👤 用戶名稱：").append(escapeHtml(displayName)).append("\n");
        message.append("🆔 用戶ID：").append(user.getId()).append("\n");
        message.append("📝 帳號：").append(escapeHtml(user.getUsername())).append("\n");
        
        // 登录时间
        String loginTime = ZonedDateTime.now(ZONE_ID).format(CHINESE_DATETIME_FORMATTER);
        message.append("🕐 登入時間：").append(loginTime).append("\n");
        
        // 角色信息（如果有特殊角色）
        if (user.getRole() != null && !"USER".equals(user.getRole())) {
            String roleDisplay = getRoleDisplayName(user.getRole());
            message.append("⭐ 角色：").append(roleDisplay).append("\n");
        }
        
        message.append("\n");
        
        // 安全信息
        message.append("🔒 <b>安全資訊</b>\n");
        message.append("━━━━━━━━━━━━━━━━\n");
        message.append("🌐 IP 地址：").append(escapeHtml(clientIp != null ? clientIp : "未知")).append("\n");
        
        // 设备指纹（只显示前8位和后8位，中间用...代替，保护隐私）
        String deviceDisplay = formatDeviceFingerprint(deviceFingerprint);
        message.append("📱 設備號：").append(deviceDisplay).append("\n");
        
        message.append("\n");
        
        // 提示信息
        message.append("💡 <b>提示</b>\n");
        message.append("━━━━━━━━━━━━━━━━\n");
        if (isNewUser) {
            message.append("• 您現在可以開始使用 Agora Market 的所有功能\n");
            message.append("• 建議先完善您的個人資料\n");
            message.append("• 如有任何問題，歡迎聯繫客服\n");
        } else {
            message.append("• 您已成功登入系統\n");
            message.append("• 可以開始使用所有功能\n");
        }
        
        message.append("\n");
        message.append("🎊 祝您使用愉快！");
        
        return message.toString();
    }
    
    /**
     * 格式化设备指纹（只显示部分，保护隐私）
     * 显示格式：前8位...后8位
     */
    private String formatDeviceFingerprint(String deviceFingerprint) {
        if (deviceFingerprint == null || deviceFingerprint.isEmpty()) {
            return "未知";
        }
        
        int length = deviceFingerprint.length();
        if (length <= 16) {
            // 如果长度小于等于16，直接显示
            return deviceFingerprint;
        } else {
            // 显示前8位和后8位，中间用...代替
            String prefix = deviceFingerprint.substring(0, 8);
            String suffix = deviceFingerprint.substring(length - 8);
            return prefix + "..." + suffix;
        }
    }
    
    /**
     * 获取角色显示名称
     */
    private String getRoleDisplayName(String role) {
        switch (role) {
            case "ADMIN":
                return "管理員";
            case "MODERATOR":
                return "版主";
            case "SELLER":
                return "賣家";
            case "BUYER":
                return "買家";
            default:
                return role;
        }
    }
    
    /**
     * 转义 HTML 特殊字符（Telegram 支持 HTML，但需要转义）
     */
    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;");
    }
    
    /**
     * 清理过期的 Token
     * 每5分钟执行一次，清理所有过期的 loginToken
     */
    @Scheduled(fixedRate = 300000) // 5分钟 = 300000毫秒
    public void cleanupExpiredTokens() {
        int beforeCount = loginTokenStorage.size();
        
        // 清理过期的 Token
        loginTokenStorage.entrySet().removeIf(entry -> {
            LoginTokenInfo tokenInfo = entry.getValue();
            boolean shouldRemove = tokenInfo.isExpired() || tokenInfo.used || tokenInfo.isMaxAttemptsReached();
            
            if (shouldRemove) {
                // 同时清理设备Token映射
                String deviceKey = buildDeviceKey(tokenInfo.clientIp, tokenInfo.deviceFingerprint);
                deviceTokenMap.remove(deviceKey);
                return true;
            }
            return false;
        });
        
        // 清理设备Token映射中的无效引用
        deviceTokenMap.entrySet().removeIf(entry -> {
            String loginToken = entry.getValue();
            return !loginTokenStorage.containsKey(loginToken);
        });
        
        int afterCount = loginTokenStorage.size();
        int cleanedCount = beforeCount - afterCount;
        
        if (cleanedCount > 0) {
            log.info("Cleaned up {} expired login tokens. Remaining: {}", cleanedCount, afterCount);
        }
    }
}
