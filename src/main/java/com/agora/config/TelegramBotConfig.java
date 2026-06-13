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
 * Telegram bot configuration for notifications and group assistant identity.
 */
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "telegram.bot")
@Data
public class TelegramBotConfig {
    private String token;
    private String username;
    private String channelId;
    private String apiBaseUrl = "https://api.telegram.org";
    private String httpProxy;

    private final RestTemplate restTemplate = createRestTemplate();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "telegram-username-retry");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        if (token == null || token.isEmpty()) {
            log.warn("Telegram bot token is not configured. Telegram notifications will be logged only.");
            this.username = null;
            return;
        }

        fetchBotUsernameAsync(0);
    }

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);

        if (httpProxy != null && !httpProxy.isEmpty() && httpProxy.contains(":")) {
            try {
                String[] parts = httpProxy.split(":", 2);
                Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(parts[0], Integer.parseInt(parts[1])));
                factory.setProxy(proxy);
                log.info("Telegram HTTP proxy enabled: {}", httpProxy);
            } catch (Exception ex) {
                log.warn("Invalid TELEGRAM_HTTP_PROXY value: {}", httpProxy);
            }
        }
        return new RestTemplate(factory);
    }

    private void fetchBotUsernameAsync(int attempt) {
        new Thread(() -> {
            try {
                log.info("Starting async fetch of Telegram bot username (attempt #{}, will delay 2 seconds)...", attempt + 1);
                String normalizedBase = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
                String primaryUrl = normalizedBase + "/bot" + token + "/getMe";
                String fallbackUrl = "https://api.telegram.org/bot" + token + "/getMe";

                String[] candidates = normalizedBase.equals("https://api.telegram.org")
                        ? new String[]{primaryUrl}
                        : new String[]{primaryUrl, fallbackUrl};

                for (String apiUrl : candidates) {
                    try {
                        String maskedApiUrl = maskTelegramBotDetails(apiUrl);
                        log.info("Fetching Telegram bot username from API: {}", maskedApiUrl);
                        GetMeResponse response = restTemplate.getForObject(apiUrl, GetMeResponse.class);

                        if (response != null && response.isOk() && response.getResult() != null) {
                            String fetchedUsername = response.getResult().getUsername();
                            if (fetchedUsername != null && !fetchedUsername.isEmpty()) {
                                this.username = fetchedUsername;
                                log.info("Telegram bot username auto-fetched: @{} (source={})", username, maskedApiUrl);
                                return;
                            }
                        }
                        log.warn("Fetch attempt did not return valid username from {}", maskedApiUrl);
                    } catch (Exception ex) {
                        log.warn("Fetch attempt failed from {} : {}", maskTelegramBotDetails(apiUrl), maskTelegramBotDetails(ex.getMessage()));
                    }
                }

                log.warn("Failed to fetch Telegram bot username after all attempts.");
                this.username = null;
                scheduleRetry(attempt + 1);
            } catch (Exception e) {
                log.warn("Unable to fetch Telegram bot username: {}. This is normal if behind a firewall.", maskTelegramBotDetails(e.getMessage()));
                this.username = null;
                scheduleRetry(attempt + 1);
            }
        }, "telegram-username-fetcher").start();
    }

    private static String maskTelegramBotDetails(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.replaceAll("bot[^/\\s]+", "bot***");
    }

    private void scheduleRetry(int attempt) {
        if (attempt >= 10) {
            log.warn("Reached max retry attempts for Telegram username fetch ({}). Giving up.", attempt);
            return;
        }
        long delaySeconds = 30;
        log.info("Scheduling Telegram username fetch retry attempt #{} after {} seconds...", attempt + 1, delaySeconds);
        scheduler.schedule(() -> fetchBotUsernameAsync(attempt), delaySeconds, TimeUnit.SECONDS);
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GetMeResponse {
        private boolean ok;
        private BotInfo result;
    }

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
