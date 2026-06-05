package com.agora.service;

public interface SystemConfigService {
    /**
     * 獲取配置值
     * @param configKey 配置鍵
     * @param defaultValue 默認值
     * @return 配置值，如果不存在則返回默認值
     */
    String getConfigValue(String configKey, String defaultValue);
    
    /**
     * 設置配置值
     * @param configKey 配置鍵
     * @param configValue 配置值
     * @param description 配置描述
     */
    void setConfigValue(String configKey, String configValue, String description);
    
    /**
     * 檢查配置是否為 true
     * @param configKey 配置鍵
     * @return 如果配置值為 "true" 則返回 true，否則返回 false
     */
    boolean isConfigEnabled(String configKey);
}
