package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "雙因素認證管理參數")
public class TwoFactorManageParam {
    @Schema(description = "驗證碼", example = "123456")
    @NotBlank(message = "驗證碼不能為空")
    private String code;
    
    @Schema(description = "操作類型", example = "enable", allowableValues = {"enable", "disable"})
    @NotBlank(message = "操作類型不能為空")
    @Pattern(regexp = "enable|disable", message = "操作類型必須是 enable 或 disable")
    private String action;
}
