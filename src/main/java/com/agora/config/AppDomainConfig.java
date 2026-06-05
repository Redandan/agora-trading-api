package com.agora.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 应用域名配置
 * 统一管理允许的域名，与 CORS 配置共用
 */
@Configuration
@Getter
public class AppDomainConfig {
    
    /**
     * 允许的域名列表（从 CORS 配置中提取）
     * 格式：http://localhost:*, http://127.0.0.1:*
     */
    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    /**
     * 解析后的允许域名列表
     */
    private List<String> allowedOriginList;

    @PostConstruct
    public void init() {
        // 解析允许的域名列表
        allowedOriginList = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toList());
    }
}
