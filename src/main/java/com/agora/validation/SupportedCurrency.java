package com.agora.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 验证货币是否为系统支持的货币类型
 */
@Documented
@Constraint(validatedBy = SupportedCurrencyValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SupportedCurrency {
    String message() default "不支援的貨幣類型";
    
    Class<?>[] groups() default {};
    
    Class<? extends Payload>[] payload() default {};
}

