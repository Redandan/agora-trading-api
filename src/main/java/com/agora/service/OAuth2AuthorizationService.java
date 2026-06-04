package com.agora.service;

import com.agora.dto.auth.OAuth2TokenResponse;

/**
 * OAuth2 授权服务接口
 */
public interface OAuth2AuthorizationService {
    
    /**
     * 构建 Google 授权 URL
     * 
     * @param redirectUri 前端回调地址（用于保存，实际传给 Google 的是后端 callback）
     * @param state 状态参数
     * @param scope 权限范围
     * @return Google 授权 URL
     */
    String buildAuthorizationUrl(String redirectUri, String state, String scope);
    
    /**
     * 保存 state、platform、requestedRedirectUri
     * 
     * @param state 状态参数
     * @param platform 平台类型（web/desktop）
     * @param requestedRedirectUri 前端回调地址（Web: https://redandan.github.io/oauth2-callback, Desktop: com.agoramarket.oauth://oauth2callback）
     */
    void saveState(String state, String platform, String requestedRedirectUri);
    
    /**
     * 获取保存的 platform（通过 state）
     * 
     * @param state 状态参数
     * @return platform，如果不存在返回 null
     */
    String getPlatform(String state);
    
    /**
     * 获取保存的 requestedRedirectUri（前端回调地址，通过 state）
     * 
     * @param state 状态参数
     * @return requestedRedirectUri，如果不存在返回 null
     */
    String getRequestedRedirectUri(String state);
    
    /**
     * 通过 code 获取 requestedRedirectUri（用于重复回调场景）
     * 
     * @param code 授权码
     * @return requestedRedirectUri，如果不存在返回 null
     */
    String getRequestedRedirectUriByCode(String code);
    
    /**
     * 保存授权码（用于防止重复使用）
     * 
     * @param code 授权码
     * @param state 状态参数
     */
    void saveAuthorizationCode(String code, String state);
    
    /**
     * 在后端回调中直接完成token交换（后端方案）
     * 接收Google回调的code，直接完成token交换，返回临时token ID
     * 
     * @param code 授权码
     * @param state 状态参数
     * @param deviceFingerprint 设备指纹（可选，如果为null则不包含设备信息）
     * @param ipAddress IP地址（可选，如果为null则不包含设备信息）
     * @return 临时token ID，用于前端获取token
     */
    String exchangeCodeForTokenInCallback(String code, String state, String deviceFingerprint, String ipAddress);
    
    /**
     * 通过临时token ID获取token响应
     * 
     * @param tokenId 临时token ID
     * @return Token 响应，如果token ID无效或已过期返回 null
     */
    OAuth2TokenResponse getTokenByTokenId(String tokenId);
    
    /**
     * 通过tokenId获取Google OAuth用户信息（用于绑定场景）
     * 注意：此方法不会删除tokenId，允许在绑定失败时重试
     * 
     * @param tokenId 临时token ID
     * @return Google OAuth用户信息，如果token ID无效或已过期返回 null
     */
    com.agora.dto.auth.GoogleOAuthUserInfo getGoogleOAuthUserInfoByTokenId(String tokenId);
    
    /**
     * 消费tokenId（绑定成功后调用，删除tokenId）
     * 
     * @param tokenId 临时token ID
     */
    void consumeTokenId(String tokenId);
}

