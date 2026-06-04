package com.agora.service;

import com.agora.dto.turnstile.TurnstileVerificationResult;

public interface TurnstileService {
    /**
     * 驗證 Turnstile Token
     *
     * @param token 前端傳來的 Turnstile Token
     * @param userIp 用戶 IP 地址
     * @return 驗證結果
     */
    TurnstileVerificationResult verifyToken(String token, String userIp);
    
    /**
     * 獲取 Turnstile Site Key
     *
     * @return Site Key
     */
    String getSiteKey();
}
