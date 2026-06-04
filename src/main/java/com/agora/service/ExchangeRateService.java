// DOMAIN: marketplace
// Plan §9 — should live under com.agora.service.marketplace once that
// sub-package exists. ArchTest classifier already maps the ExchangeRate*
// name pattern to MARKETPLACE_CLASS_PATTERN.
package com.agora.service;

import com.agora.dto.ExchangeRateInfo;

import java.math.BigDecimal;

/**
 * 匯率服務接口
 */
public interface ExchangeRateService {
    
    /**
     * 獲取所有USDT對其他法幣的匯率
     * 如果超過3秒則自動更新
     * 
     * @return 匯率列表
     */
    java.util.List<ExchangeRateInfo> getAllUsdtRates();
    
    /**
     * 刷新所有匯率數據
     */
    void refreshAllRates();
    
    /**
     * 檢查匯率是否需要更新（超過10秒）
     * 
     * @return 是否需要更新
     */
    boolean needsUpdate();
    
    /**
     * 根据货币代码获取汇率（USDT/该货币）
     * 如果汇率服务不可用，返回默认汇率
     * 
     * @param currency 货币代码（如 "TWD", "USD"）
     * @return 汇率信息，如果货币不支持或服务不可用，返回默认汇率
     */
    ExchangeRateInfo getRateByCurrency(String currency);
    
    /**
     * 获取默认汇率（USDT/该货币）
     * 用于汇率服务不可用时的降级方案
     * 
     * @param currency 货币代码
     * @return 默认汇率值
     */
    BigDecimal getDefaultRate(String currency);
    
    /**
     * 获取缓存的汇率（用于判断是否使用默认汇率）
     * 
     * @param currency 货币代码
     * @return 缓存的汇率信息，如果不存在返回null
     */
    ExchangeRateInfo getCachedRate(String currency);
}
