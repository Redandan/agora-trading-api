package com.agora.service;

import com.agora.enums.system.OAuthProvider;

/**
 * OAuth2 使用次数跟踪服务
 */
public interface OAuth2UsageService {
    
    /**
     * 记录 OAuth2 使用次数
     * 
     * @param provider OAuth 提供商
     */
    void recordUsage(OAuthProvider provider);
    
    /**
     * 获取今日使用次数
     * 
     * @param provider OAuth 提供商
     * @return 今日使用次数
     */
    int getTodayUsage(OAuthProvider provider);
    
    /**
     * 检查是否超过每日限制
     * 
     * @param provider OAuth 提供商
     * @return 是否超过限制
     */
    boolean isOverLimit(OAuthProvider provider);
    
    /**
     * 获取每日限制
     * 
     * @param provider OAuth 提供商
     * @return 每日限制（null 表示无限制）
     */
    Integer getDailyLimit(OAuthProvider provider);
}

