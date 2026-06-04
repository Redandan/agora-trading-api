package com.agora.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 本地快取配置（Caffeine）
 *
 * <p>使用 Caffeine 作為 Spring Cache 的後端，所有快取資料存放於 JVM 記憶體。
 * 每個快取名稱可獨立設定 TTL 與最大容量。
 */
@Configuration
@EnableCaching
public class RedisAndCacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        // 各快取獨立 TTL 設定
        manager.registerCustomCache("users",
                cacheBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(500).build());
        manager.registerCustomCache("markets",
                cacheBuilder().expireAfterWrite(3, TimeUnit.MINUTES).maximumSize(200).build());
        manager.registerCustomCache("systemConfig",
                cacheBuilder().expireAfterWrite(1, TimeUnit.HOURS).maximumSize(50).build());
        manager.registerCustomCache("exchangeRates",
                cacheBuilder().expireAfterWrite(15, TimeUnit.MINUTES).maximumSize(100).build());
        manager.registerCustomCache("oauthBindings",
                cacheBuilder().expireAfterWrite(30, TimeUnit.MINUTES).maximumSize(200).build());
        // LiveSignal K 線快取（TTL 60s；每次 KlineClosedEvent 時由 @CacheEvict 清除）
        manager.registerCustomCache("liveSignalKlines",
                cacheBuilder().expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(20).build());

        // 市場快照快取（TTL 5min；key="BTCUSDT-4h"，對 1h/4h K 線安全）
        // 消除 ShortAiFilter Layer1+buildShortPrompt 的重複 DB 查詢
        manager.registerCustomCache("marketSnapshot",
                cacheBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(20).build());

        // 啟用策略列表快取（TTL 10min；enable/disable 時由 @CacheEvict 立即失效）
        manager.registerCustomCache("enabledStrategies",
                cacheBuilder().expireAfterWrite(10, TimeUnit.MINUTES).maximumSize(1).build());

        // Fear & Greed 指數（每日更新一次，TTL 6h）
        manager.registerCustomCache("fearGreedIndex",
                cacheBuilder().expireAfterWrite(6, TimeUnit.HOURS).maximumSize(1).build());

        // 鯨魚大單買賣比（TTL 15m，與 15m bar 週期對齊）
        manager.registerCustomCache("whaleBuyRatio",
                cacheBuilder().expireAfterWrite(15, TimeUnit.MINUTES).maximumSize(10).build());

        // OKX 資金費率（每 8h 結算，TTL 30min；key=symbol）
        manager.registerCustomCache("fundingRate",
                cacheBuilder().expireAfterWrite(30, TimeUnit.MINUTES).maximumSize(10).build());

        // OKX 多空帳戶比率（TTL 5min；key=symbol）
        manager.registerCustomCache("longShortRatio",
                cacheBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(10).build());

        // OKX 訂單簿不平衡率（TTL 5min，訂單變化快但夠用於 1h bar 決策；key=symbol）
        manager.registerCustomCache("orderbookImbalance",
                cacheBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(10).build());

        // 其餘未明確註冊的快取使用預設值：10 分鐘 TTL
        manager.setCaffeine(cacheBuilder().expireAfterWrite(10, TimeUnit.MINUTES).maximumSize(500));

        return manager;
    }

    private static Caffeine<Object, Object> cacheBuilder() {
        return Caffeine.newBuilder().recordStats();
    }
}
