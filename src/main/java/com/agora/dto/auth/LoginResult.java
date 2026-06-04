package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "登入結果")
public class LoginResult {
    @Schema(description = "操作是否成功", example = "true")
    private boolean success = true;

    @Schema(description = "操作結果消息", example = "登入成功")
    private String message;

    @Schema(description = "訪問令牌", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String token;

    @Schema(description = "刷新令牌", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String refreshToken;

    @Schema(description = "訪問令牌簽發時間", example = "2025-06-03T11:07:02Z")
    private String tokenIssuedAt;

    @Schema(description = "訪問令牌過期時間", example = "2025-06-03T11:09:38Z")
    private String tokenExpiration;

    @Schema(description = "刷新令牌過期時間", example = "2025-06-10T11:09:38Z")
    private String refreshTokenExpiration;

    @Schema(description = "用戶ID", example = "1")
    private Long userId;

    @Schema(description = "用戶名", example = "testuser")
    private String username;

    @Schema(description = "默認首頁設置", enumAsRef = true)
    private com.agora.enums.system.DefaultHomePageEnum defaultHomePage;

    @Schema(description = "默認首頁是否在維護中", example = "false")
    private Boolean homePageInMaintenance;

    @Schema(description = "用戶詳細信息")
    private UserInfo userInfo;
    
    /**
     * 創建錯誤登入結果的靜態工廠方法
     * @param message 錯誤消息
     * @return 包含錯誤信息的 LoginResult
     */
    public static LoginResult createError(String message) {
        LoginResult result = new LoginResult();
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }
}
