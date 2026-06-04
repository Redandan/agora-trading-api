package com.agora.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Slf4j
public class ChatMessageBuilder {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构建纯文本消息内容
     */
    public static String buildTextMessage(String text) {
        return text;
    }

    /**
     * 构建纯图片消息内容
     */
    public static String buildImageMessage(List<ImageInfo> images) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "IMAGE");
            message.put("images", images);
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("构建图片消息失败: {}", e.getMessage());
            return "图片消息构建失败";
        }
    }

    /**
     * 构建混合消息内容（文本+图片）
     */
    public static String buildMixedMessage(String text, List<ImageInfo> images) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "MIXED");
            message.put("text", text);
            message.put("images", images);
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("构建混合消息失败: {}", e.getMessage());
            return text + " [图片消息构建失败]";
        }
    }

    /**
     * 解析消息内容
     */
    public static MessageContent parseMessage(String content) {
        try {
            // 尝试解析为JSON格式
            Map<String, Object> message = objectMapper.readValue(content, Map.class);
            String type = (String) message.get("type");
            
            if ("IMAGE".equals(type)) {
                return new MessageContent(MessageType.IMAGE, null, (List<ImageInfo>) message.get("images"));
            } else if ("MIXED".equals(type)) {
                return new MessageContent(MessageType.MIXED, (String) message.get("text"), 
                                       (List<ImageInfo>) message.get("images"));
            } else {
                return new MessageContent(MessageType.TEXT, content, null);
            }
        } catch (Exception e) {
            // 如果不是JSON格式，则视为纯文本
            return new MessageContent(MessageType.TEXT, content, null);
        }
    }

    /**
     * 图片信息类
     */
    public static class ImageInfo {
        private String url;
        private String description;

        public ImageInfo() {}

        public ImageInfo(String url) {
            this.url = url;
        }

        public ImageInfo(String url, String description) {
            this.url = url;
            this.description = description;
        }

        // Getters and Setters
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    /**
     * 消息类型枚举
     */
    public enum MessageType {
        TEXT, IMAGE, MIXED
    }

    /**
     * 消息内容类
     */
    public static class MessageContent {
        private MessageType type;
        private String text;
        private List<ImageInfo> images;

        public MessageContent(MessageType type, String text, List<ImageInfo> images) {
            this.type = type;
            this.text = text;
            this.images = images;
        }

        // Getters
        public MessageType getType() { return type; }
        public String getText() { return text; }
        public List<ImageInfo> getImages() { return images; }

        public boolean isTextMessage() { return type == MessageType.TEXT; }
        public boolean isImageMessage() { return type == MessageType.IMAGE; }
        public boolean isMixedMessage() { return type == MessageType.MIXED; }
        public boolean hasImages() { return images != null && !images.isEmpty(); }
    }
}
