package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;

@Data
public class AdminCreateUserParam {
    @NotNull(message = "用戶名不能為空")
    private String username;

    @NotNull(message = "密碼不能為空")
    private String password;

    @NotNull(message = "郵箱不能為空")
    @Email(message = "郵箱格式不正確")
    private String email;

    @Pattern(regexp = "^09\\d{8}$", message = "手機號碼格式不正確")
    private String phone;

    @NotNull(message = "角色不能為空")
    @Schema(description = "用戶角色")
    private String role = "USER"; // 默認為普通用戶

    private String name;
    private String remark;
}