package com.agora.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 依商品類型 (ProductTypeEnum) 條件驗證必填欄位。
 *
 * PHYSICAL 必填:pickupAddress / pickupLongitude / pickupLatitude /
 *                pickupTimeStart / pickupTimeEnd / pickupServiceTypes /
 *                pickupServiceTypeFees
 *
 * DIGITAL_SERVICE 必填:sourceRegion / sourcePlatform /
 *                      serviceLeadTimeHours / maxConcurrentOrders
 *
 * 套在 ProductCreateParam / ProductUpdateParam 類別上。
 */
@Documented
@Constraint(validatedBy = ValidProductByTypeValidator.class)
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidProductByType {
    String message() default "商品必填欄位依類型驗證失敗";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
