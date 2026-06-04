package com.agora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 市场选项排程配置属性
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "market.scheduler")
public class MarketSchedulerProperties {
    
    /**
     * 排程线程池大小
     */
    private int threadPoolSize = 5;
    
    /**
     * 安全扫描间隔（毫秒）
     * 默认每小时扫描一次，检测是否有遗漏的转换
     */
    private long safetyScanIntervalMs = 3600000L; // 1 hour
    
    /**
     * 是否启用事件驱动排程
     */
    private boolean eventDrivenEnabled = true;
    
    /**
     * 是否启用旧的轮询排程（向后兼容）
     */
    private boolean legacyPollingEnabled = false;
    
    /**
     * 排程日志级别（用于动态配置）
     */
    private String logLevel = "DEBUG";

    /**
     * 已結算市場保留天數 — 超過此天數的 RESOLVED markets 會被 cleanupResolvedMarkets() 刪除。
     * 主表行刪除時，cascade = ALL + orphanRemoval = true 會連帶刪除 market_options。
     * Issue #165 — 避免 markets / market_options 無限膨脹。
     */
    private int retentionDays = 90;
}
