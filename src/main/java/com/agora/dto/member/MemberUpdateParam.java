package com.agora.dto.member;

import com.agora.enums.system.UserStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "會員更新參數")
public class MemberUpdateParam {
    @Schema(description = "用戶ID")
    private String id;
    
    @Schema(description = "用戶名")
    private String username;
    
    @Schema(description = "電子郵件")
    private String email;
    
    @Schema(description = "電話號碼")
    private String phone;
    
    @Schema(
        description = "用戶狀態",
        enumAsRef = true
    )
    private UserStatusEnum status;
    
    @Schema(description = "是否為賣家")
    private Boolean isSeller;
    
    @Schema(description = "備註")
    private String remark;  // 管理員備註
} 