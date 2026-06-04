package com.agora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Telegram Webhook 配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "telegram.webhook")
public class TelegramWebhookConfig {
    
    
    /**
     * Webhook 外部访问 URL
     * 例如: https://your-domain.com/api/telegram/webhook/login-bot
     */
    private String url;
    
    /**
     * Webhook 内部路径
     * 例如: /api/telegram/webhook/login-bot
     */
    private String path = "/api/telegram/webhook/login-bot";
    
    /**
     * 允许的更新类型
     */
    private String[] allowedUpdates = {"message", "callback_query"};
    
    /**
     * 最大连接数
     */
    private Integer maxConnections = 40;
}
