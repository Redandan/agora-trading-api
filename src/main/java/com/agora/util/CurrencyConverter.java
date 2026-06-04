package com.agora.util;

import com.agora.dto.ExchangeRateInfo;
import com.agora.enums.system.SupportedCurrencyEnum;
import com.agora.service.ExchangeRateService;
import lombok.extern.slf4j.Slf4j;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 货币换算工具类
 */
@Slf4j
public class CurrencyConverter {
    private static final int USDT_SCALE = 4;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;
    
    /**
     * 将原币种金额换算成USDT
     * 
     * @param amount 原币种金额
     * @param rate 汇率（USDT/原币种）
     * @return USDT金额
     */
    public static BigDecimal convertToUsdt(BigDecimal amount, BigDecimal rate) {
        if (amount == null || rate == null || rate.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return amount.divide(rate, USDT_SCALE, ROUNDING_MODE);
    }
    
    /**
     * 将USDT金额换算成原币种
     * 
     * @param usdtAmount USDT金额
     * @param rate 汇率（USDT/原币种）
     * @return 原币种金额
     */
    public static BigDecimal convertFromUsdt(BigDecimal usdtAmount, BigDecimal rate) {
        if (usdtAmount == null || rate == null) {
            return BigDecimal.ZERO;
        }
        return usdtAmount.multiply(rate).setScale(2, ROUNDING_MODE);
    }
    
    /**
     * 获取货币的汇率（USDT/该货币）
     * 优先尝试从 Spring Bean (ExchangeRateService) 获取实时汇率
     * 如果 Spring 上下文不可用或获取失败，则使用默认汇率作为降级方案
     * 
     * @param currency 货币枚举
     * @return 汇率，如果货币为 USDT 返回 1.0，如果 currency 为 null 返回 1.0
     */
    public static BigDecimal getRate(SupportedCurrencyEnum currency) {
        if (currency == null) {
            return BigDecimal.ONE;
        }
        if (currency == SupportedCurrencyEnum.USDT) {
            return BigDecimal.ONE;
        }
        
        // 尝试从 Spring Bean 获取实时汇率
        if (SpringContextHolder.isSpringContextAvailable()) {
            try {
                ExchangeRateService exchangeRateService = SpringContextHolder.getBean(ExchangeRateService.class);
                if (exchangeRateService != null) {
                    ExchangeRateInfo rateInfo = exchangeRateService.getRateByCurrency(currency.getCode());
                    if (rateInfo != null && rateInfo.getRate() != null) {
                        return rateInfo.getRate();
                    }
                }
            } catch (Exception e) {
                log.debug("无法从 ExchangeRateService 获取汇率，使用默认汇率: {}", e.getMessage());
            }
        }
        
        // 降级方案：使用默认汇率
        return getDefaultRate(currency);
    }
    
    /**
     * 根据货币代码获取汇率（USDT/该货币）
     * 优先尝试从 Spring Bean (ExchangeRateService) 获取实时汇率
     * 如果 Spring 上下文不可用或获取失败，则使用默认汇率作为降级方案
     * 
     * @param currencyCode 货币代码（如 "TWD", "USD"）
     * @return 汇率，如果货币不支持或代码为 null 返回 1.0
     */
    public static BigDecimal getRateByCode(String currencyCode) {
        if (currencyCode == null || currencyCode.isEmpty()) {
            return BigDecimal.ONE;
        }
        
        // 尝试从 Spring Bean 获取实时汇率
        if (SpringContextHolder.isSpringContextAvailable()) {
            try {
                ExchangeRateService exchangeRateService = SpringContextHolder.getBean(ExchangeRateService.class);
                if (exchangeRateService != null) {
                    ExchangeRateInfo rateInfo = exchangeRateService.getRateByCurrency(currencyCode);
                    if (rateInfo != null && rateInfo.getRate() != null) {
                        return rateInfo.getRate();
                    }
                }
            } catch (Exception e) {
                log.debug("无法从 ExchangeRateService 获取汇率，使用默认汇率: {}", e.getMessage());
            }
        }
        
        // 降级方案：使用默认汇率
        SupportedCurrencyEnum currency = SupportedCurrencyEnum.fromCode(currencyCode);
        return getDefaultRate(currency);
    }
    
    /**
     * 获取货币的默认汇率（USDT/该货币）
     * 这是一个静态方法，不依赖外部服务，用于降级方案
     * 
     * @param currency 货币枚举
     * @return 默认汇率，如果货币为 USDT 返回 1.0，如果 currency 为 null 返回 1.0
     */
    public static BigDecimal getDefaultRate(SupportedCurrencyEnum currency) {
        if (currency == null) {
            return BigDecimal.ONE;
        }
        if (currency == SupportedCurrencyEnum.USDT) {
            return BigDecimal.ONE;
        }
        return currency.getDefaultRate();
    }
    
    /**
     * 根据货币代码获取默认汇率（USDT/该货币）
     * 
     * @param currencyCode 货币代码（如 "TWD", "USD"）
     * @return 默认汇率，如果货币不支持或代码为 null 返回 1.0
     */
    public static BigDecimal getDefaultRateByCode(String currencyCode) {
        if (currencyCode == null || currencyCode.isEmpty()) {
            return BigDecimal.ONE;
        }
        SupportedCurrencyEnum currency = SupportedCurrencyEnum.fromCode(currencyCode);
        return getDefaultRate(currency);
    }
}

