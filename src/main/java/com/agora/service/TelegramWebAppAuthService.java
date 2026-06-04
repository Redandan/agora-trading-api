package com.agora.service;

import com.agora.dto.auth.LoginResult;

/**
 * Telegram WebApp 认证服务接口
 * 用于处理 Telegram WebApp initData 验签与 JWT 交换
 */
public interface TelegramWebAppAuthService {
    
    /**
     * 验证 initData 并交换 JWT
     * 
     * @param initData Telegram WebApp 提供的 initData 字符串
     * @param clientIp 客户端 IP 地址
     * @param deviceFingerprint 设备指纹
     * @param referrerGroupId Mini App / TG 群組 first-touch referrer，可為 null
     * @return 登录结果（包含 JWT token）
     */
    LoginResult exchangeJwt(String initData,
                            String clientIp,
                            String deviceFingerprint,
                            String userAgent,
                            Long referrerGroupId);
}
