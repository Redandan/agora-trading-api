package com.agora.service;

import com.agora.dto.auth.TwoFactorManageParam;
import com.agora.dto.auth.TwoFactorSetupResponse;
import com.agora.dto.auth.TwoFactorStatusResponse;
import com.agora.dto.auth.TwoFactorVerifyParam;
import com.agora.model.User;

public interface TwoFactorAuthService {
    boolean requiresTwoFactor(String username);
    TwoFactorSetupResponse generateSetupInfo(User user);
    boolean verifyCode(User user, TwoFactorVerifyParam param);
    boolean manageTwoFactor(User user, TwoFactorManageParam param);
    boolean enableTwoFactor(User user, TwoFactorVerifyParam param);
    boolean disableTwoFactor(User user, TwoFactorVerifyParam param);
    TwoFactorStatusResponse getStatus(User user);
}
