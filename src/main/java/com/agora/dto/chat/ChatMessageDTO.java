package com.agora.dto.chat;

import com.agora.util.ChatMessageBuilder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Data
@Schema(description = "聊天消息傳輸對象")
public class ChatMessageDTO {
    
    @NotNull(message = "接收者ID不能為空")
    @Schema(description = "接收者ID", required = true)
    private Long receiverId;

    @NotBlank(message = "消息內容不能為空")
    @Size(max = 1000, message = "消息內容不能超過1000個字符")
    @Schema(description = "消息內容", required = true)
    private String content;

    @Schema(description = "消息圖片URL列表")
    private List<String> imageUrls;


    // 业务逻辑方法
    /**
     * 检查是否有图片
     */
    public boolean hasImages() {
        return imageUrls != null && !imageUrls.isEmpty();
    }


    /**
     * 构建最终的消息内容
     */
    public String buildFinalContent() {
        if (!hasImages()) {
            return content;
        }

        // 构建图片信息列表
        List<ChatMessageBuilder.ImageInfo> imageInfos = new java.util.ArrayList<>();
        for (int i = 0; i < imageUrls.size(); i++) {
            String url = imageUrls.get(i);
            imageInfos.add(new ChatMessageBuilder.ImageInfo(url));
        }

        if (content == null || content.trim().isEmpty()) {
            // 纯图片消息
            return ChatMessageBuilder.buildImageMessage(imageInfos);
        } else {
            // 混合消息
            return ChatMessageBuilder.buildMixedMessage(content, imageInfos);
        }
    }
} 