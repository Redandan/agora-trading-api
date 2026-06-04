package com.agora.service.impl;

import com.agora.dto.auth.TwoFactorManageParam;
import com.agora.dto.auth.TwoFactorSetupResponse;
import com.agora.dto.auth.TwoFactorStatusResponse;
import com.agora.dto.auth.TwoFactorVerifyParam;
import com.agora.exception.BusinessException;
import com.agora.model.User;
import com.agora.repository.system.UserRepository;
import com.agora.service.TwoFactorAuthService;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import com.warrenstrange.googleauth.GoogleAuthenticatorQRGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class TwoFactorAuthServiceImpl implements TwoFactorAuthService {

    private final UserRepository userRepository;
    private final GoogleAuthenticator googleAuthenticator;

    @Override
    public boolean requiresTwoFactor(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("用戶不存在"));
        return user.getTwoFactorEnabled() && user.getTwoFactorSecret() != null;
    }

    @Override
    public TwoFactorSetupResponse generateSetupInfo(User user) {
        TwoFactorSetupResponse response = new TwoFactorSetupResponse();
        response.setEnabled(user.getTwoFactorEnabled());
        response.setConfigured(user.getTwoFactorSecret() != null);

        if (user.getTwoFactorEnabled() && user.getTwoFactorSecret() != null) {
            response.setQrCodeData(null);
            response.setSecret(null);
        } else {
            GoogleAuthenticatorKey key = googleAuthenticator.createCredentials();
            String qrCodeData = GoogleAuthenticatorQRGenerator.getOtpAuthURL(
                    "AgoraMarket", user.getUsername(), key);
            response.setQrCodeData(qrCodeData);
            response.setSecret(key.getKey());
        }
        return response;
    }

    @Override
    public boolean verifyCode(User user, TwoFactorVerifyParam param) {
        if (user.getTwoFactorSecret() == null) {
            throw new BusinessException("用戶未設置雙因素認證");
        }
        return googleAuthenticator.authorize(user.getTwoFactorSecret(), Integer.parseInt(param.getCode()));
    }

    @Override
    @Transactional
    public boolean manageTwoFactor(User user, TwoFactorManageParam param) {
        if ("enable".equals(param.getAction())) {
            return enableTwoFactor(user, new TwoFactorVerifyParam(param.getCode()));
        } else if ("disable".equals(param.getAction())) {
            return disableTwoFactor(user, new TwoFactorVerifyParam(param.getCode()));
        } else {
            throw new BusinessException("無效的操作類型");
        }
    }

    @Override
    @Transactional
    public boolean enableTwoFactor(User user, TwoFactorVerifyParam param) {
        GoogleAuthenticatorKey key = googleAuthenticator.createCredentials();
        String tempSecret = key.getKey();
        if (!googleAuthenticator.authorize(tempSecret, Integer.parseInt(param.getCode()))) {
            throw new BusinessException("驗證碼不正確");
        }
        user.setTwoFactorSecret(tempSecret);
        user.setTwoFactorEnabled(true);
        userRepository.save(user);
        log.info("用戶 {} 已啟用雙因素認證", user.getUsername());
        return true;
    }

    @Override
    @Transactional
    public boolean disableTwoFactor(User user, TwoFactorVerifyParam param) {
        if (!verifyCode(user, param)) {
            throw new BusinessException("驗證碼不正確");
        }
        user.setTwoFactorEnabled(false);
        user.setTwoFactorSecret(null);
        userRepository.save(user);
        log.info("用戶 {} 已禁用雙因素認證", user.getUsername());
        return true;
    }

    @Override
    public TwoFactorStatusResponse getStatus(User user) {
        return new TwoFactorStatusResponse(
                user.getTwoFactorEnabled(),
                user.getTwoFactorSecret() != null);
    }
}
