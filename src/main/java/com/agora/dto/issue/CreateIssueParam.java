package com.agora.dto.issue;

import com.agora.enums.marketplace.IssueTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
@Schema(description = "創建客戶問題參數")
public class CreateIssueParam {

    @NotNull(message = "問題類型不能為空")
    @Schema(description = "問題類型", enumAsRef = true, required = true)
    private IssueTypeEnum issueType;

    @NotBlank(message = "問題內容不能為空")
    @Schema(description = "問題內容", required = true, example = "我的充值沒有到帳，請協助處理")
    private String content;
} 