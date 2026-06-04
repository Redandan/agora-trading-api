package com.agora.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Google OAuth用户信息（用于绑定场景）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GoogleOAuthUserInfo {
    private String providerId;
    private String email;
    private String name;
    private String picture;
}

