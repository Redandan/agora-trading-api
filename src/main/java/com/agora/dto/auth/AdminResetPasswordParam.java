package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Data
@Schema(description = "管理員重設會員密碼參數")
public class AdminResetPasswordParam {
    
    @Schema(description = "會員ID", example = "123")
    @NotBlank(message = "會員ID不能為空")
    private String memberId;
    
    @Schema(description = "新密碼", example = "newPassword123")
    @NotBlank(message = "新密碼不能為空")
    private String newPassword;
}
