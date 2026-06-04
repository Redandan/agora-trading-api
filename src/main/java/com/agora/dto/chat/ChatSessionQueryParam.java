package com.agora.dto.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "聊天會話查詢參數")
public class ChatSessionQueryParam {
    
    @Schema(description = "用戶ID")
    private Long userId;
    
    @Schema(description = "是否只查詢未讀會話")
    private Boolean unreadOnly;
    
    @Schema(description = "是否只查詢置頂會話")
    private Boolean pinnedOnly;
    
    @Schema(description = "頁碼", defaultValue = "0")
    private Integer page = 0;
    
    @Schema(description = "每頁大小", defaultValue = "20")
    private Integer size = 20;
} 