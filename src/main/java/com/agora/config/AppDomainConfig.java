package com.agora.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
     * Web3 登录使用的域名（从允许的域名中提取主域名）
     */
    @Value("${app.web3.domain}")
    private String web3Domain;
    
    /**
     * 解析后的允许域名列表
     */
    private List<String> allowedOriginList;
    
    /**
     * 主域名（用于 Web3 签名消息）
     * 从允许的域名中提取第一个生产环境域名，如果没有则使用 web3Domain
     */
    private String primaryDomain;
    
    @PostConstruct
    public void init() {
        // 解析允许的域名列表
        allowedOriginList = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .collect(Collectors.toList());
        
        // 提取主域名（用于 Web3 签名消息）
        // 优先使用生产环境域名（agoramarket.purrtechllc.com），其次使用 redandan.github.io
        primaryDomain = allowedOriginList.stream()
                .filter(origin -> origin.contains("agoramarket.purrtechllc.com"))
                .findFirst()
                .map(origin -> {
                    // 从 URL 中提取域名（去掉协议和通配符）
                    String domain = origin.replace("https://", "")
                            .replace("http://", "")
                            .replace("*", "")
                            .replace("/", "");
                    return domain.isEmpty() ? web3Domain : domain;
                })
                .orElseGet(() -> {
                    // 如果没有找到 agoramarket.purrtechllc.com，尝试查找 redandan.github.io
                    return allowedOriginList.stream()
                            .filter(origin -> origin.contains("redandan.github.io"))
                            .findFirst()
                            .map(origin -> {
                                // 从 URL 中提取域名（去掉协议和通配符）
                                String domain = origin.replace("https://", "")
                                        .replace("http://", "")
                                        .replace("*", "")
                                        .replace("/", "");
                                return domain.isEmpty() ? web3Domain : domain;
                            })
                            .orElse(web3Domain);
                });
    }
    
    /**
     * 获取 Web3 登录使用的域名
     * @return 域名
     */
    public String getWeb3Domain() {
        return primaryDomain;
    }
    
    /**
     * 根据请求的 origin 获取对应的域名
     * @param origin 请求的 origin（例如：https://redandan.github.io）
     * @return 对应的域名，如果找不到则返回默认域名
     */
    public String getDomainByOrigin(String origin) {
        if (origin == null || origin.isEmpty()) {
            return primaryDomain;
        }
        
        // 从 origin 中提取域名（去掉协议和路径）
        String originDomain = origin.replace("https://", "")
                .replace("http://", "")
                .split("/")[0]; // 只取域名部分，去掉路径
        
        log.debug("Extracted origin domain: {} from origin: {}", originDomain, origin);
        
        // 在允许的域名列表中查找匹配的域名
        for (String allowedOrigin : allowedOriginList) {
            // 去掉协议
            String allowedOriginWithoutProtocol = allowedOrigin.replace("https://", "")
                    .replace("http://", "");
            
            // 检查是否匹配（支持通配符匹配）
            if (allowedOriginWithoutProtocol.contains("*")) {
                // 处理通配符：https://redandan.github.io* 应该匹配 https://redandan.github.io
                // 去掉通配符和路径
                String baseDomain = allowedOriginWithoutProtocol.replace("*", "").split("/")[0];
                if (originDomain.equals(baseDomain) || originDomain.startsWith(baseDomain)) {
                    log.debug("Matched domain: {} for origin: {} (wildcard pattern: {})", 
                            baseDomain, origin, allowedOrigin);
                    return baseDomain.isEmpty() ? primaryDomain : baseDomain;
                }
            } else {
                // 精确匹配：去掉路径部分
                String allowedDomain = allowedOriginWithoutProtocol.split("/")[0];
                if (originDomain.equals(allowedDomain)) {
                    log.debug("Matched domain: {} for origin: {} (exact match)", 
                            allowedDomain, origin);
                    return allowedDomain.isEmpty() ? primaryDomain : allowedDomain;
                }
            }
        }
        
        // 如果没有找到匹配的域名，返回默认域名
        log.debug("No matching domain found for origin: {}, using default: {}", origin, primaryDomain);
        return primaryDomain;
    }
}
