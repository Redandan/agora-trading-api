package com.agora.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Clock 配置類
 * 提供系統時鐘 Bean，方便測試時注入 Mock Clock
 */
@Configuration
public class ClockConfig {
    
    /**
     * 提供系統默認時鐘
     * 在生產環境使用系統時區
     * 在測試環境可以替換為固定時鐘
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
