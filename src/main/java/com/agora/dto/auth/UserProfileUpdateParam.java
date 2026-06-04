package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

@Data
@Schema(description = "用戶資料更新參數")
public class UserProfileUpdateParam {
    @Size(max = 50, message = "姓名不能超過50個字符")
    @Schema(description = "姓名")
    private String name;

    @Size(max = 20, message = "電話號碼不能超過20個字符")
    @Schema(description = "電話號碼")
    private String phone;

    @Schema(description = "頭像URL")
    private String avatar;

    @Schema(description = "默認首頁設置", enumAsRef = true)
    private com.agora.enums.system.DefaultHomePageEnum defaultHomePage;

    // 业务逻辑方法
    /**
     * 检查是否有头像
     */
    public boolean hasAvatar() {
        return avatar != null && !avatar.trim().isEmpty();
    }

    /**
     * 检查是否为头像更新
     */
    public boolean isAvatarUpdate() {
        return hasAvatar();
    }
} 