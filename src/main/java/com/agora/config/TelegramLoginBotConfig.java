package com.agora.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;

/**
 * Telegram Bot 配置类
 * 统一处理 Telegram Bot 登录功能和频道消息发送
 */
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "telegram.bot")
@Data
public class TelegramLoginBotConfig {
    
    /**
     * Bot Token（从 BotFather 获取）
     */
    private String token;
    
    /**
     * Bot Username（由系统自动从 Telegram API 获取）
     */
    private String username;
    
    /**
     * 频道 ID（用于发送系统通知消息到频道）
     */
    private String channelId;

    /**
     * Telegram API 基础地址，默认 https://api.telegram.org，可配置代理或镜像以绕过防火墙
     */
    private String apiBaseUrl = "https://api.telegram.org";

    /**
     * 可选的 HTTP 代理（host:port），用于绕过防火墙；可由 TELEGRAM_HTTP_PROXY 配置
     */
    private String httpProxy;
    
    private final RestTemplate restTemplate = createRestTemplate();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "telegram-username-retry");
        t.setDaemon(true);
        return t;
    });
    
    /**
     * 启动时自动获取 Bot Username
     */
    @PostConstruct
    public void init() {
        if (token == null || token.isEmpty()) {
            log.warn("⚠️ Telegram Login Bot token is not configured. Telegram login feature will be disabled.");
            log.warn("Please set TELEGRAM_LOGIN_BOT_TOKEN environment variable.");
            this.username = null;
            return;
        }
        
        // 自动从 API 获取 Bot Username
        fetchBotUsernameAsync(0);
    }

    /**
     * 创建带超时的 RestTemplate
     */
    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000); // 3s 连接超时
        factory.setReadTimeout(3000);    // 3s 读取超时

        if (httpProxy != null && !httpProxy.isEmpty() && httpProxy.contains(":")) {
            try {
                String[] parts = httpProxy.split(":", 2);
                Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(parts[0], Integer.parseInt(parts[1])));
                factory.setProxy(proxy);
                log.info("🌐 Telegram HTTP proxy enabled: {}", httpProxy);
            } catch (Exception ex) {
                log.warn("⚠️ Invalid TELEGRAM_HTTP_PROXY value: {}", httpProxy);
            }
        }
        return new RestTemplate(factory);
    }
    
    /**
     * 异步获取 Bot Username，避免阻塞启动
     */
    private void fetchBotUsernameAsync(int attempt) {
        new Thread(() -> {
            try {
                log.info("🔄 Starting async fetch of Telegram Login Bot username (attempt #{}, will delay 2 seconds)...", attempt + 1);
                // 尝试主地址与备用地址（备用始终使用官方域名）
                String normalizedBase = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
                String primaryUrl = normalizedBase + "/bot" + token + "/getMe";
                String fallbackUrl = "https://api.telegram.org/bot" + token + "/getMe";

                String[] candidates = normalizedBase.equals("https://api.telegram.org")
                        ? new String[]{primaryUrl}
                        : new String[]{primaryUrl, fallbackUrl};

                for (String apiUrl : candidates) {
                    try {
                        log.info("📡 Fetching Telegram Login Bot username from API: {}", apiUrl.replaceAll("bot[^/]+", "bot***"));
                        GetMeResponse response = restTemplate.getForObject(apiUrl, GetMeResponse.class);

                        if (response != null && response.isOk() && response.getResult() != null) {
                            String fetchedUsername = response.getResult().getUsername();
                            if (fetchedUsername != null && !fetchedUsername.isEmpty()) {
                                this.username = fetchedUsername;
                                log.info("✅ Telegram Login Bot username auto-fetched: @{} (source={})", username, apiUrl);
                                return;
                            }
                        }
                        log.warn("⚠️ Fetch attempt did not return valid username from {}", apiUrl);
                    } catch (Exception ex) {
                        log.warn("⚠️ Fetch attempt failed from {} : {}", apiUrl, ex.getMessage());
                    }
                }

                log.warn("⚠️ Failed to fetch Telegram Login Bot username after all attempts. Login feature may not work properly.");
                this.username = null;
                scheduleRetry(attempt + 1);
           
            } catch (Exception e) {
                log.warn("⚠️ Unable to fetch Telegram Login Bot username: {}. This is normal if behind a firewall.", e.getMessage());
                this.username = null;
                scheduleRetry(attempt + 1);
            }
        }, "telegram-username-fetcher").start();
    }

    /**
     * 失败后重试，最多 10 次，每次延迟 30 秒
     */
    private void scheduleRetry(int attempt) {
        if (attempt >= 10) {
            log.warn("⚠️ Reached max retry attempts for Telegram username fetch ({}). Giving up.", attempt);
            return;
        }
        long delaySeconds = 30;
        log.info("⏳ Scheduling Telegram username fetch retry attempt #{} after {} seconds...", attempt + 1, delaySeconds);
        scheduler.schedule(() -> fetchBotUsernameAsync(attempt), delaySeconds, TimeUnit.SECONDS);
    }
    
    /**
     * Telegram API getMe 响应结构
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GetMeResponse {
        private boolean ok;
        private BotInfo result;
    }
    
    /**
     * Bot 信息结构
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class BotInfo {
        @JsonProperty("id")
        private Long id;
        
        @JsonProperty("is_bot")
        private Boolean isBot;
        
        @JsonProperty("first_name")
        private String firstName;
        
        @JsonProperty("username")
        private String username;
    }
}

