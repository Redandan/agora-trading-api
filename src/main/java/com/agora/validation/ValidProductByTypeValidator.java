package com.agora.validation;

import com.agora.enums.marketplace.ProductTypeEnum;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;

import java.util.Collection;
import java.util.Map;

/**
 * {@link ValidProductByType} 的 validator 實作。
 * 透過 Spring BeanWrapper 讀欄位,避免跟 DTO 類別硬耦合,ProductCreateParam
 * 與 ProductUpdateParam 皆可共用(欄位 getter 名必須一致)。
 *
 * 規則:
 *   productType == PHYSICAL        → pickup_* 七項必填
 *   productType == DIGITAL_SERVICE → source_*、leadTime、maxConcurrent 四項必填
 *   productType == null            → 跳過條件驗證(update partial 語意:不改 type 時不重驗)
 */
public class ValidProductByTypeValidator
        implements ConstraintValidator<ValidProductByType, Object> {

    private static final String[] PHYSICAL_REQUIRED = {
            "pickupAddress",
            "pickupLongitude",
            "pickupLatitude",
            "pickupTimeStart",
            "pickupTimeEnd",
            "pickupServiceTypes",
            "pickupServiceTypeFees"
    };

    private static final String[] DIGITAL_REQUIRED = {
            "sourceRegion",
            "sourcePlatform",
            "serviceLeadTimeHours",
            "maxConcurrentOrders"
    };

    @Override
    public boolean isValid(Object param, ConstraintValidatorContext ctx) {
        if (param == null) {
            return true;
        }
        BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(param);

        ProductTypeEnum type = readProductType(wrapper);
        // UpdateParam partial 場景:productType 為 null 時不重驗(避免誤改已存在 pickup 欄位)
        // CreateParam 則會有預設值 PHYSICAL,不會落入此分支
        if (type == null) {
            return true;
        }

        boolean valid = true;
        ctx.disableDefaultConstraintViolation();

        String[] required = (type == ProductTypeEnum.DIGITAL_SERVICE)
                ? DIGITAL_REQUIRED
                : PHYSICAL_REQUIRED;

        for (String field : required) {
            if (!wrapper.isReadableProperty(field)) {
                continue;
            }
            Object value = wrapper.getPropertyValue(field);
            if (isEmpty(value)) {
                valid = false;
                ctx.buildConstraintViolationWithTemplate(
                        buildMessage(type, field))
                        .addPropertyNode(field)
                        .addConstraintViolation();
            }
        }

        return valid;
    }

    private ProductTypeEnum readProductType(BeanWrapper wrapper) {
        if (!wrapper.isReadableProperty("productType")) {
            return null;
        }
        Object value = wrapper.getPropertyValue("productType");
        if (value instanceof ProductTypeEnum e) {
            return e;
        }
        return null;
    }

    private boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return s.isBlank();
        }
        if (value instanceof Collection<?> c) {
            return c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return m.isEmpty();
        }
        return false;
    }

    private String buildMessage(ProductTypeEnum type, String field) {
        return String.format("商品類型 %s 必填欄位: %s",
                type.name(), field);
    }
}
