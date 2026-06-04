package com.agora.dto.issue;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
@Schema(description = "回覆客戶問題參數")
public class ReplyIssueParam {

    @NotBlank(message = "回覆內容不能為空")
    @Schema(description = "回覆內容", required = true, example = "您好，我們已經收到您的問題，正在處理中，請稍候。")
    private String reply;
} 