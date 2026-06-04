package com.agora.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Configuration
public class RateLimitConfig {
    
    @Bean
    public ConcurrentHashMap<String, Integer> registerAttempts() {
        return new ConcurrentHashMap<>();
    }
    
    @Bean
    public ScheduledExecutorService rateLimitCleanupExecutor() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(() -> {
            // 每小時清理一次註冊嘗試記錄
            registerAttempts().clear();
        }, 1, 1, TimeUnit.HOURS);
        return executor;
    }
}
