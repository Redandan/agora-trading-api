package com.agora.service.impl;

import com.agora.dto.auth.LoginResult;
import com.agora.dto.auth.UserInfo;
import com.agora.enums.system.OAuthProvider;
import com.agora.enums.system.RegistrationMethodEnum;
import com.agora.enums.system.UserStatusEnum;
import com.agora.exception.BusinessException;
import com.agora.model.User;
import com.agora.model.UserOAuthBinding;
import com.agora.repository.system.UserOAuthBindingRepository;
import com.agora.repository.system.UserRepository;
import com.agora.service.TelegramWebAppAuthService;
import com.agora.util.JwtUtil;
import com.agora.util.DateUtil;
import com.agora.util.TelegramInitDataVerifier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Telegram WebApp 认证服务实现
 * 处理 Telegram WebApp 的 initData 验签与 JWT 交换
 */
@Slf4j
@Service
public class TelegramWebAppAuthServiceImpl implements TelegramWebAppAuthService {
    
    private final TelegramInitDataVerifier initDataVerifier;
    private final com.agora.config.TelegramLoginBotConfig telegramBotConfig;
    private final UserRepository userRepository;
    private final UserOAuthBindingRepository oauthBindingRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    
    public TelegramWebAppAuthServiceImpl(
            TelegramInitDataVerifier initDataVerifier,
            com.agora.config.TelegramLoginBotConfig telegramBotConfig,
            UserRepository userRepository,
            UserOAuthBindingRepository oauthBindingRepository,
            JwtUtil jwtUtil,
            PasswordEncoder passwordEncoder) {
        this.initDataVerifier = initDataVerifier;
        this.telegramBotConfig = telegramBotConfig;
        this.userRepository = userRepository;
        this.oauthBindingRepository = oauthBindingRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
    }
    
    @Override
    @Transactional
    public LoginResult exchangeJwt(String initData,
                                   String clientIp,
                                   String deviceFingerprint,
                                   String userAgent,
                                   Long referrerGroupId) {
        log.info("Processing Telegram WebApp JWT exchange with initData");
        
        // 1. 验证 initData 签名
        String botToken = telegramBotConfig.getToken();
        if (!initDataVerifier.verify(initData, botToken)) {
            log.warn("initData verification failed");
            throw new BusinessException("登入驗證失敗，請重新嘗試");
        }
        
        // 2. 解析用户信息
        Map<String, String> userInfo = initDataVerifier.parseUserInfo(initData);
        String userIdStr = userInfo.get("id");
        if (userIdStr == null || userIdStr.isEmpty()) {
            log.warn("User ID not found in initData");
            throw new BusinessException("無法獲取用戶信息，請重新嘗試");
        }
        
        Long telegramUserId = Long.parseLong(userIdStr);
        String firstName = userInfo.get("first_name");
        String lastName = userInfo.get("last_name");
        String username = userInfo.get("username");
        
        // 构建显示名称
        String displayName = buildDisplayName(firstName, lastName, username, telegramUserId);
        
        log.info("Telegram user info: id={}, username={}, name={}", telegramUserId, username, displayName);
        
        // 3. 查找或创建用户
        String providerId = String.valueOf(telegramUserId);
        UserOAuthBinding existingBinding = oauthBindingRepository
                .findByOauthProviderAndOauthProviderId(OAuthProvider.TELEGRAM_BOT, providerId)
                .orElse(null);
        
        User user;
        String telegramUsername = username != null ? "@" + username : displayName;
        
        if (existingBinding != null) {
            // 已存在绑定，直接登录
            log.info("Found existing OAuth binding for Telegram WebApp, userId: {}", telegramUserId);
            user = userRepository.findById(existingBinding.getUserId())
                    .orElseThrow(() -> new BusinessException("用戶不存在"));
            
            // 更新绑定信息（如果用户名有变化）
            boolean needsUpdate = false;
            if (!telegramUsername.equals(existingBinding.getOauthName())) {
                existingBinding.setOauthName(telegramUsername);
                needsUpdate = true;
            }
            // 确保 telegram_user_id 已设置
            if (existingBinding.getTelegramUserId() == null || existingBinding.getTelegramUserId().isEmpty()) {
                existingBinding.setTelegramUserId(providerId);
                needsUpdate = true;
            }
            if (needsUpdate) {
                oauthBindingRepository.save(existingBinding);
            }
        } else {
            // 新绑定，需要创建用户
            log.info("Creating new user for Telegram WebApp, userId: {}, username: {}", telegramUserId, telegramUsername);
            user = createUserFromTelegram(telegramUserId, telegramUsername, displayName, clientIp, userAgent);
            
            // 创建绑定
            UserOAuthBinding binding = new UserOAuthBinding();
            binding.setUserId(user.getId());
            binding.setOauthProvider(OAuthProvider.TELEGRAM_BOT);
            binding.setOauthProviderId(providerId);
            binding.setOauthName(telegramUsername);
            binding.setOauthAvatar(null);
            binding.setTelegramUserId(providerId);
            binding.setIsPrimary(true);
            oauthBindingRepository.save(binding);
        }
        
        // 4. 检查用户状态
        if (user.getStatus() != UserStatusEnum.ACTIVE) {
            throw new BusinessException("帳號已被禁用或暫停");
        }

        captureFirstTouchReferrer(user, referrerGroupId, initData);
        
        // 5. 生成 JWT Token（包含设备信息）
        String accessToken = jwtUtil.generateToken(user, deviceFingerprint, clientIp);
        String refreshToken = jwtUtil.generateRefreshToken(user);
        
        // 6. 创建登录结果
        LoginResult result = new LoginResult();
        result.setToken(accessToken);
        result.setRefreshToken(refreshToken);
        result.setTokenIssuedAt(DateUtil.formatDateWithZone(jwtUtil.extractIssuedAt(accessToken)));
        result.setTokenExpiration(DateUtil.formatDateWithZone(jwtUtil.extractExpiration(accessToken)));
        result.setRefreshTokenExpiration(DateUtil.formatDateWithZone(jwtUtil.extractExpiration(refreshToken)));
        result.setUserId(user.getId());
        result.setUsername(user.getUsername());
        result.setDefaultHomePage(user.getDefaultHomePage());
        result.setUserInfo(buildUserInfo(user));
        
        log.info("Telegram WebApp JWT exchange successful for user: {} (Telegram ID: {})", user.getUsername(), telegramUserId);
        return result;
    }

    private void captureFirstTouchReferrer(User user, Long explicitReferrerGroupId, String initData) {
        if (user.getReferrerGroupId() != null) {
            return;
        }
        Long resolvedReferrerGroupId = explicitReferrerGroupId != null
                ? explicitReferrerGroupId
                : extractReferrerFromSignedStartParam(initData);
        if (resolvedReferrerGroupId == null) {
            return;
        }

        user.setReferrerGroupId(resolvedReferrerGroupId);
        userRepository.save(user);
        log.info("Captured Telegram WebApp first-touch referrer: userId={}, referrerGroupId={}",
                user.getId(), resolvedReferrerGroupId);
    }

    private Long extractReferrerFromSignedStartParam(String initData) {
        String startParam = extractInitDataParam(initData, "start_param");
        if (startParam == null || startParam.isBlank()) {
            return null;
        }
        Map<String, String> embeddedParams = parseEmbeddedRouteParams(startParam);
        String ref = embeddedParams.get("ref");
        if (ref == null || ref.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(ref.trim());
        } catch (NumberFormatException e) {
            log.warn("Ignoring invalid Telegram WebApp ref in start_param: {}", ref);
            return null;
        }
    }

    private String extractInitDataParam(String initData, String expectedKey) {
        if (initData == null || initData.isBlank()) {
            return null;
        }
        String[] pairs = initData.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = decodeUrl(pair.substring(0, idx));
            if (!expectedKey.equals(key)) {
                continue;
            }
            return decodeUrl(pair.substring(idx + 1));
        }
        return null;
    }

    private Map<String, String> parseEmbeddedRouteParams(String startParam) {
        int questionIndex = startParam.indexOf('?');
        int ampIndex = startParam.indexOf('&');
        int splitIndex;
        if (questionIndex < 0) {
            splitIndex = ampIndex;
        } else if (ampIndex < 0) {
            splitIndex = questionIndex;
        } else {
            splitIndex = Math.min(questionIndex, ampIndex);
        }
        if (splitIndex < 0 || splitIndex >= startParam.length() - 1) {
            return Map.of();
        }
        String query = startParam.substring(splitIndex + 1);
        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            params.put(decodeUrl(pair.substring(0, idx)), decodeUrl(pair.substring(idx + 1)));
        }
        return params;
    }

    private String decodeUrl(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring malformed Telegram WebApp URL-encoded value: {}", value);
            return "";
        }
    }
    
    /**
     * 构建显示名称
     */
    private String buildDisplayName(String firstName, String lastName, String username, Long userId) {
        if (firstName != null && !firstName.isEmpty()) {
            if (lastName != null && !lastName.isEmpty()) {
                return firstName + " " + lastName;
            }
            return firstName;
        }
        if (username != null && !username.isEmpty()) {
            return "@" + username;
        }
        return "tg_user_" + userId;
    }
    
    /**
     * 从 Telegram 信息创建新用户
     */
    private User createUserFromTelegram(Long telegramUserId, String telegramUsername, String displayName, String regIp, String regUa) {
        User user = new User();
        
        // 生成唯一用户名
        String baseUsername = telegramUsername.replace("@", "");
        String username = "tg_" + baseUsername + "_" + UUID.randomUUID().toString().substring(0, 8);
        
        // 确保用户名唯一
        int suffix = 1;
        String originalUsername = username;
        while (userRepository.existsByUsername(username)) {
            username = originalUsername + "_" + suffix;
            suffix++;
        }
        
        user.setUsername(username);
        user.setEmail(null); // Telegram 不提供邮箱
        user.setName(displayName);
        user.setAvatar(null);
        user.setRole("USER");
        user.setStatus(UserStatusEnum.ACTIVE);
        user.setEmailVerified(false);
        user.setTwoFactorEnabled(false);
        user.setRegistrationMethod(RegistrationMethodEnum.TELEGRAM_WEBAPP);
        user.setRegistrationIp(regIp);
        user.setRegistrationUa(StringUtils.truncate(regUa, 512));

        // 生成随机密码（用户无法使用密码登录）
        String randomPassword = UUID.randomUUID().toString();
        user.setPassword(passwordEncoder.encode(randomPassword));

        return userRepository.save(user);
    }

    private UserInfo buildUserInfo(User user) {
        UserInfo userInfo = new UserInfo();
        userInfo.setId(user.getId());
        userInfo.setUsername(user.getUsername());
        userInfo.setEmail(user.getEmail());
        userInfo.setEmailVerified(user.getEmailVerified());
        userInfo.setRole(user.getRole());
        userInfo.setBalance(BigDecimal.ZERO);
        userInfo.setStackingBalance(BigDecimal.ZERO);
        userInfo.setTotalAssets(BigDecimal.ZERO);
        userInfo.setFreezeBalance(BigDecimal.ZERO);
        userInfo.setEnabled(user.getStatus() == UserStatusEnum.ACTIVE);
        userInfo.setQueryTime(LocalDateTime.now());
        userInfo.setStoreName(user.getStoreName());
        userInfo.setAmbassadorName(user.getAmbassadorName());
        userInfo.setDisplayDeliveryerName(user.getDisplayDeliveryerName());
        userInfo.setAvatar(user.getAvatar());
        userInfo.setDefaultHomePage(user.getDefaultHomePage());
        return userInfo;
    }
    
}
