package com.agora.service;

import com.agora.dto.auth.LoginResult;
import com.agora.enums.system.OAuthProvider;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * OAuth2 第三方登录服务接口
 */
public interface OAuth2Service {
    /**
     * 处理 OAuth2 登录
     * 
     * @param oauth2User OAuth2 用户信息
     * @param provider OAuth 提供商
     * @param deviceFingerprint 设备指纹（可选，如果为null则不包含设备信息）
     * @param ipAddress IP地址（可选，如果为null则不包含设备信息）
     * @return 登录结果（包含 JWT Token）
     */
    LoginResult processOAuth2Login(OAuth2User oauth2User, OAuthProvider provider, String deviceFingerprint, String ipAddress);
}

