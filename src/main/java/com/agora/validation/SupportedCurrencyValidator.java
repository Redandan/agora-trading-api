package com.agora.validation;

import com.agora.enums.system.SupportedCurrencyEnum;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * 货币验证器
 * 验证货币是否为系统支持的货币类型
 */
public class SupportedCurrencyValidator implements ConstraintValidator<SupportedCurrency, SupportedCurrencyEnum> {
    
    @Override
    public void initialize(SupportedCurrency constraintAnnotation) {
        // 初始化方法，如果需要可以在这里设置参数
    }
    
    @Override
    public boolean isValid(SupportedCurrencyEnum currency, ConstraintValidatorContext context) {
        // 如果为 null，由 @NotNull 注解处理
        if (currency == null) {
            return true;
        }
        
        // 检查货币是否为系统支持的货币
        boolean isValid = SupportedCurrencyEnum.isSupported(currency.getCode());
        
        if (!isValid) {
            // 自定义错误消息，包含所有支持的货币列表
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                String.format("不支援的貨幣類型: %s。系統支援的貨幣: %s", 
                    currency.getCode(), 
                    String.join(", ", SupportedCurrencyEnum.getAllCurrencyCodes()))
            ).addConstraintViolation();
        }
        
        return isValid;
    }
}

