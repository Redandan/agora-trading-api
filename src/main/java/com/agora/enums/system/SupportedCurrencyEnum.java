package com.agora.enums.system;

import lombok.Getter;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 系统支持的货币类型
 */
@Getter
public enum SupportedCurrencyEnum {
    USDT("USDT", "Tether", "USDT", new BigDecimal("1.0")),
    USD("USD", "美元", "$", new BigDecimal("1.0")),
    TWD("TWD", "新台幣", "NT$", new BigDecimal("31.0")),
    THB("THB", "泰銖", "฿", new BigDecimal("36.0")),
    CNY("CNY", "人民幣", "¥", new BigDecimal("7.2")),
    JPY("JPY", "日圓", "¥", new BigDecimal("150.0")),
    EUR("EUR", "歐元", "€", new BigDecimal("0.92")),
    GBP("GBP", "英鎊", "£", new BigDecimal("0.79")),
    KRW("KRW", "韓圓", "₩", new BigDecimal("1300.0")),
    SGD("SGD", "新加坡元", "S$", new BigDecimal("1.35")),
    HKD("HKD", "港幣", "HK$", new BigDecimal("7.8")),
    AUD("AUD", "澳幣", "A$", new BigDecimal("1.51"));
    
    private final String code;
    private final String name;
    private final String symbol;
    private final BigDecimal defaultRate;
    
    SupportedCurrencyEnum(String code, String name, String symbol, BigDecimal defaultRate) {
        this.code = code;
        this.name = name;
        this.symbol = symbol;
        this.defaultRate = defaultRate;
    }
    
    /**
     * 获取所有支持的货币代码列表（不包括USDT）
     */
    public static List<String> getSupportedCurrencyCodes() {
        return Arrays.stream(values())
                .filter(c -> !c.equals(USDT))
                .map(SupportedCurrencyEnum::getCode)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取所有支持的货币代码列表（包括USDT）
     */
    public static List<String> getAllCurrencyCodes() {
        return Arrays.stream(values())
                .map(SupportedCurrencyEnum::getCode)
                .collect(Collectors.toList());
    }
    
    /**
     * 根据代码获取枚举
     */
    public static SupportedCurrencyEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(c -> c.getCode().equals(code))
                .findFirst()
                .orElse(null);
    }
    
    /**
     * 检查是否支持该货币
     */
    public static boolean isSupported(String currency) {
        return fromCode(currency) != null;
    }
}

