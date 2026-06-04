package com.agora.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableScheduling
public class SSEConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Type", "Content-Length")
                .maxAge(3600);
    }

    /**
     * Configure async request timeout handling for SSE connections
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // Set the default timeout for async requests (should match SSE timeout)
        configurer.setDefaultTimeout(300000L); // 5 minutes
        // Set the task executor for async operations
        configurer.setTaskExecutor(null); // Use default task executor
    }

    /**
     * 配置 SSE 相關的屬性
     */
    @Bean
    public SSEProperties sseProperties() {
        return new SSEProperties();
    }

    public static class SSEProperties {
        // SSE 連接超時時間（毫秒）
        private long connectionTimeout = 300000L; // 5分鐘
        // 心跳間隔（毫秒）
        private long heartbeatInterval = 15000L; // 15秒
        // 清理間隔（毫秒）
        private long cleanupInterval = 30000L; // 30秒

        public long getConnectionTimeout() {
            return connectionTimeout;
        }

        public void setConnectionTimeout(long connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
        }

        public long getHeartbeatInterval() {
            return heartbeatInterval;
        }

        public void setHeartbeatInterval(long heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }

        public long getCleanupInterval() {
            return cleanupInterval;
        }

        public void setCleanupInterval(long cleanupInterval) {
            this.cleanupInterval = cleanupInterval;
        }
    }
} 