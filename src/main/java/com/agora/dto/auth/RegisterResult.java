package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "註冊結果")
public class RegisterResult {
    @Schema(description = "操作是否成功", example = "true")
    private boolean success = true;

    @Schema(description = "操作結果消息", example = "註冊成功")
    private String message;

    @Schema(description = "錯誤代碼（當操作失敗時）", example = "USERNAME_EXISTS")
    private String errorCode;

    @Schema(description = "錯誤欄位（當操作失敗時）", example = "username")
    private String field;

    @Schema(description = "登入結果")
    private LoginResult loginResult;

    /**
     * 創建成功響應（返回登入結果）
     */
    public static RegisterResult success(LoginResult loginResult) {
        RegisterResult result = new RegisterResult();
        result.setSuccess(true);
        result.setMessage("註冊成功");
        result.setLoginResult(loginResult);
        return result;
    }

    /**
     * 創建錯誤響應
     */
    public static RegisterResult error(String message) {
        RegisterResult result = new RegisterResult();
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }

    /**
     * 創建帶錯誤代碼和欄位的錯誤響應
     */
    public static RegisterResult error(String message, String errorCode, String field) {
        RegisterResult result = new RegisterResult();
        result.setSuccess(false);
        result.setMessage(message);
        result.setErrorCode(errorCode);
        result.setField(field);
        return result;
    }
}
