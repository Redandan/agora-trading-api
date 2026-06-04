package com.agora.service;

import com.agora.dto.auth.LoginResult;

/**
 * 驗證碼登入服務介面
 */
public interface TelegramBotLoginService {
    
    /**
     * 生成登录 loginToken 和 deep link
     * 
     * @param redirectUri 前端回调地址
     * @param clientIp 客户端 IP 地址
     * @param deviceFingerprint 设备指纹
     * @return loginToken（用于构建 deep link）
     */
    String generateLoginToken(String redirectUri, String clientIp, String deviceFingerprint);
    
    /**
     * 处理 Telegram Bot 的 /start 命令
     * 
     * @param chatId Telegram Chat ID
     * @param username Telegram 用户名
     * @param loginToken /start 命令的参数（loginToken）
     */
    void handleStartCommand(Long chatId, String username, String loginToken);
    
    /**
     * 验证验证码并完成登录
     * 
     * @param loginToken 登录 token（从 generateLoginToken 获取）
     * @param verificationCode 4 位验证码
     * @param clientIp 客户端 IP 地址
     * @param deviceFingerprint 设备指纹
     * @return 登录结果
     */
    LoginResult verifyVerificationCode(String loginToken, String verificationCode, String clientIp, String deviceFingerprint);
    
    /**
     * 验证loginToken和验证码（用于绑定场景，不创建用户）
     * 
     * @param loginToken 登录 token
     * @param verificationCode 4 位验证码
     * @param clientIp 客户端 IP 地址
     * @param deviceFingerprint 设备指纹
     * @return Telegram用户信息（chatId和username），如果验证失败返回null
     */
    TelegramUserInfo verifyForBinding(String loginToken, String verificationCode, String clientIp, String deviceFingerprint);
    
    /**
     * 删除已使用的loginToken（绑定成功后调用）
     * 
     * @param loginToken 登录 token
     */
    void removeLoginToken(String loginToken);
    
    /**
     * Telegram用户信息（用于绑定场景）
     */
    class TelegramUserInfo {
        private final Long chatId;
        private final String username;
        
        public TelegramUserInfo(Long chatId, String username) {
            this.chatId = chatId;
            this.username = username;
        }
        
        public Long getChatId() { return chatId; }
        public String getUsername() { return username; }
    }
}

