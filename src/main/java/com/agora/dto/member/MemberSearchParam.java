package com.agora.dto.member;

import com.agora.dto.common.BaseSearchParam;
import com.agora.enums.system.UserStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "會員搜索參數")
public class MemberSearchParam extends BaseSearchParam {

    @Schema(description = "用戶ID")
    private Long userId;

    @Schema(description = "用戶名")
    private String username;

    @Schema(description = "電子郵件")
    private String email;
    
    @Schema(description = "用戶狀態", enumAsRef = true)
    private UserStatusEnum status;
} 