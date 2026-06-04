package com.agora.dto.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
@Schema(description = "聊天消息更新傳輸對象")
public class ChatMessageUpdateDTO {
    
    @NotBlank(message = "消息內容不能為空")
    @Size(max = 1000, message = "消息內容不能超過1000個字符")
    @Schema(description = "消息內容", required = true)
    private String content;
} 