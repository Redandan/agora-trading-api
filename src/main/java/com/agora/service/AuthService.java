package com.agora.service;


import com.agora.dto.auth.*;
import com.agora.enums.system.OAuthProvider;
import com.agora.model.User;

import jakarta.servlet.http.HttpServletRequest;

public interface AuthService {
    RegisterResult register(RegisterParam registerParam, HttpServletRequest request);

    /**
     * 用戶註冊（向後兼容）
     */
    default LoginResult register(RegisterParam registerParam) {
        throw new UnsupportedOperationException("此方法已棄用，請使用 register(RegisterParam registerParam, HttpServletRequest request)");
    }

    LoginResult login(LoginParam loginDto, HttpServletRequest request);

    /**
     * 用戶登入（向後兼容）
     */
    default LoginResult login(LoginParam loginDto) {
        throw new UnsupportedOperationException("此方法已棄用，請使用 login(LoginParam loginDto, HttpServletRequest request)");
    }

    LoginResult refreshToken(String refreshToken, HttpServletRequest request);

    void resetPassword(PasswordResetParam resetDto);

    void sendPasswordResetEmail(String email, String ipAddress);

    void resetPasswordWithCode(String email, String code, String ipAddress);

    PasswordResetCodeValidateResponse validatePasswordResetCode(PasswordResetCodeValidateParam param, String ipAddress);

    void resetPasswordWithCode(PasswordResetWithCodeParam param, String ipAddress);

    void changePassword(ChangePasswordParam param);

    UserInfo getCurrentUser();

    /**
     * 發送郵件驗證碼
     * @param email 用戶郵箱
     * @param request HTTP請求
     */
    void sendEmailVerificationEmail(String email, HttpServletRequest request);

    /**
     * 驗證郵件驗證碼
     * @param email 用戶郵箱
     * @param code 驗證碼
     * @param request HTTP請求
     */
    void verifyEmail(String email, String code, HttpServletRequest request);

    /**
     * 重發郵件驗證碼
     * @param email 用戶郵箱
     * @param request HTTP請求
     */
    void resendVerificationEmail(String email, HttpServletRequest request);



    /**
     * 更新當前用戶的個人資料
     *
     * @param updateParam 更新參數
     * @return 更新後的用戶信息
     */
    UserInfo updateProfile(UserProfileUpdateParam updateParam);

    /**
     * 管理員創建用戶
     *
     * @param createParam 創建用戶的參數，包含角色信息
     * @return 創建的用戶信息
     */
    UserInfo createUserByAdmin(AdminCreateUserParam createParam);

    /**
     * 檢查是否需要雙因素認證
     *
     * @param username 用戶名
     * @return 是否需要雙因素認證
     */
    boolean requiresTwoFactor(String username);

    // ========== 雙因素認證相關方法 ==========

    /**
     * 生成雙因素認證設置信息
     * @param user 用戶
     * @return 設置響應
     */
    TwoFactorSetupResponse generateSetupInfo(User user);

    /**
     * 驗證雙因素認證碼
     * @param user 用戶
     * @param param 驗證參數
     * @return 是否驗證成功
     */
    boolean verifyCode(User user, TwoFactorVerifyParam param);

    /**
     * 管理雙因素認證（啟用或禁用）
     * @param user 用戶
     * @param param 管理參數
     * @return 是否操作成功
     */
    boolean manageTwoFactor(User user, TwoFactorManageParam param);

    /**
     * 啟用雙因素認證
     * @param user 用戶
     * @param param 驗證參數
     * @return 是否啟用成功
     */
    boolean enableTwoFactor(User user, TwoFactorVerifyParam param);

    /**
     * 禁用雙因素認證
     * @param user 用戶
     * @param param 驗證參數
     * @return 是否禁用成功
     */
    boolean disableTwoFactor(User user, TwoFactorVerifyParam param);

    /**
     * 獲取雙因素認證狀態
     * @param user 用戶
     * @return 狀態響應
     */
    TwoFactorStatusResponse getStatus(User user);

    /**
     * 管理員重設會員密碼
     * @param param 重設密碼參數
     */
    void adminResetPassword(AdminResetPasswordParam param);

    /**
     * 發送郵箱登入驗證碼
     * @param email 用戶郵箱
     * @param request HTTP請求
     */
    void sendEmailLoginVerificationCode(String email, HttpServletRequest request);

    /**
     * 使用郵箱驗證碼登入
     * @param request 登入請求，包含郵箱和驗證碼
     * @param httpRequest HTTP請求
     * @return 登入結果
     */
    LoginResult loginWithEmailCode(EmailLoginRequest request, HttpServletRequest httpRequest);

    /**
     * 綁定郵箱（為無郵箱的賬戶綁定郵箱）
     * @param param 郵箱綁定參數
     * @param request HTTP請求
     */
    void bindEmail(BindEmailParam param, HttpServletRequest request);

    /**
     * 查看登錄方式綁定列表
     * @return 綁定列表響應
     */
    LoginBindingsResponse getLoginBindings();

    /**
     * 綁定OAuth賬號到當前登錄的賬戶
     * @param param OAuth綁定參數
     * @param request HTTP請求
     */
    void bindOAuth(BindOAuthParam param, HttpServletRequest request);

    /**
     * 解綁OAuth賬號
     * @param provider OAuth提供商
     */
    void unbindOAuth(OAuthProvider provider);

    /**
     * 用戶自助停用帳號
     * 將用戶 status 改為 SUSPENDED，下一次 JWT 請求會在
     * CustomUserDetailsServiceImpl.loadUserByUsername 被拒絕，達到撤銷 token 的效果。
     * 用戶可透過客服或重新登入流程申請恢復帳號（見 issue #125）。
     *
     * @param user 當前認證用戶
     */
    void deactivateAccount(User user);
}
