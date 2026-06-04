package com.agora.dto.issue;

import com.agora.dto.common.BaseSearchParam;
import com.agora.enums.marketplace.IssueStatusEnum;
import com.agora.enums.marketplace.IssueTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "客戶問題搜尋參數")
public class IssueSearchParam extends BaseSearchParam {
    
    @Schema(description = "工單號", example = "ISSUE123456789")
    private String issueId;
    
    @Schema(description = "用戶ID", example = "123")
    private Long userId;
    
    @Schema(description = "用戶名", example = "john_doe")
    private String username;
    
    @Schema(description = "問題類型", enumAsRef = true)
    private IssueTypeEnum issueType;
    
    @Schema(description = "問題狀態", enumAsRef = true)
    private IssueStatusEnum status;
    
    @Schema(description = "操作人ID", example = "456")
    private Long operatorId;
    
    @Schema(description = "操作人姓名", example = "admin")
    private String operatorName;
} 