package com.agora.service;

import com.agora.model.UserAddress;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class DeliveryCountryPolicyService {

    public String requiredDeliveryCountry(String sourcePlatform) {
        if (sourcePlatform == null) return null;
        return switch (sourcePlatform.trim().toUpperCase(Locale.ROOT)) {
            case "COSTCO_TW" -> "TW";
            case "MAKRO_TH" -> "TH";
            default -> null;
        };
    }

    public boolean isAddressCountryEligible(UserAddress address, String requiredCountry) {
        if (requiredCountry == null) return true;
        return requiredCountry.equals(inferDeliveryCountry(address));
    }

    public String inferDeliveryCountry(UserAddress address) {
        if (address == null) return null;
        String text = String.join(" ",
                nullToEmpty(address.getCity()),
                nullToEmpty(address.getDistrict()),
                nullToEmpty(address.getDetailedAddress()),
                nullToEmpty(address.getStoreAddress()),
                nullToEmpty(address.getPostalCode())).toLowerCase(Locale.ROOT);
        if (text.contains("台灣") || text.contains("taiwan") || isTaiwanCity(text)) return "TW";
        if (text.contains("泰國") || text.contains("thailand") || text.contains("bangkok")
                || text.contains("chiang mai") || text.contains("phuket") || text.contains("nonthaburi")
                || text.contains("chonburi") || text.contains("กรุงเทพ")) return "TH";
        if (address.getPostalCode() != null && address.getPostalCode().matches("\\d{5}")) return "TH";
        return null;
    }

    public String countryLabel(String countryCode) {
        return "TW".equals(countryCode) ? "台灣" : "TH".equals(countryCode) ? "泰國" : countryCode;
    }

    public String deliveryPrompt(String sourcePlatform) {
        String required = requiredDeliveryCountry(sourcePlatform);
        return required == null ? null : "需提供" + countryLabel(required) + "收貨地址";
    }

    private boolean isTaiwanCity(String text) {
        return text.contains("台北") || text.contains("新北") || text.contains("桃園") || text.contains("台中")
                || text.contains("臺中") || text.contains("台南") || text.contains("臺南") || text.contains("高雄")
                || text.contains("基隆") || text.contains("新竹") || text.contains("嘉義") || text.contains("苗栗")
                || text.contains("彰化") || text.contains("南投") || text.contains("雲林") || text.contains("屏東")
                || text.contains("宜蘭") || text.contains("花蓮") || text.contains("台東") || text.contains("臺東")
                || text.contains("澎湖") || text.contains("金門") || text.contains("連江");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

