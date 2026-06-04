package com.agora.util;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Logistics Calculator
 * Provides shipping fee calculation and delivery time estimation
 * Uses mock data for demonstration purposes
 */
public class LogisticsCalculator {

    /**
     * Shipping fee calculation request
     */
    public static class ShippingFeeRequest {
        public String carrier;
        public String serviceType;
        public BigDecimal weight;
        public String fromPostalCode;
        public String toPostalCode;
    }

    /**
     * Shipping fee calculation result
     */
    public static class ShippingFeeResult {
        public String carrier;
        public String serviceType;
        public BigDecimal fee;
        public int estimatedDays;
        public String description;
        public boolean isAvailable;
        public LocalDateTime queryTime;
    }

    /**
     * Calculate shipping fee based on request parameters
     */
    public ShippingFeeResult queryShippingFee(ShippingFeeRequest request) {
        ShippingFeeResult result = new ShippingFeeResult();
        result.carrier = request.carrier;
        result.serviceType = request.serviceType;
        result.queryTime = LocalDateTime.now();
        
        // Calculate fee based on carrier and service type
        if ("CHUNGHWA_POST".equals(request.carrier)) {
            result.fee = calculateChunghwaPostFee(request);
            result.description = "中華郵政 " + request.serviceType + " 服務";
        } else if ("BLACK_CAT".equals(request.carrier)) {
            result.fee = calculateBlackCatFee(request);
            result.description = "黑貓宅急便 " + request.serviceType + " 服務";
        } else if ("HCT".equals(request.carrier)) {
            result.fee = calculateHCTFee(request);
            result.description = "新竹物流 " + request.serviceType + " 服務";
        } else if ("KERRY".equals(request.carrier)) {
            result.fee = calculateKerryFee(request);
            result.description = "大榮貨運 " + request.serviceType + " 服務";
        } else if ("SF_EXPRESS".equals(request.carrier)) {
            result.fee = calculateSFExpressFee(request);
            result.description = "順豐速運 " + request.serviceType + " 服務";
        } else if ("FAMILY_MART".equals(request.carrier) || "SEVEN_ELEVEN".equals(request.carrier) || 
                   "HILIFE".equals(request.carrier) || "OK_MART".equals(request.carrier)) {
            result.fee = calculateConvenienceStoreFee(request);
            result.description = "便利商店 " + request.serviceType + " 服務";
        } else if ("HOME_DELIVERY_EXPRESS".equals(request.carrier)) {
            result.fee = calculateTCatFee(request);
            result.description = "宅配通 " + request.serviceType + " 服務";
        } else if ("TAIWAN_HOME_DELIVERY".equals(request.carrier)) {
            result.fee = calculateTaiwanDeliveryFee(request);
            result.description = "台灣宅配 " + request.serviceType + " 服務";
        } else if ("PLATFORM_DELIVERY".equals(request.carrier)) {
            result.fee = calculatePlatformDeliveryFee(request);
            result.description = "平台配送 " + request.serviceType + " 服務";
        } else {
            result.fee = new BigDecimal("50.00").add(request.weight.multiply(new BigDecimal("12.00")));
            result.description = "標準 " + request.serviceType + " 服務";
        }
        
        // Calculate estimated days
        result.estimatedDays = getQuickDays(request.fromPostalCode, request.toPostalCode);
        result.isAvailable = true;
        
        return result;
    }

    /**
     * Get quick shipping fee estimate
     */
    public BigDecimal getQuickFee(String carrier, BigDecimal weight) {
        if ("Chunghwa Post".equals(carrier)) {
            return new BigDecimal("40.00").add(weight.multiply(new BigDecimal("10.00")));
        } else if ("Black Cat".equals(carrier)) {
            return new BigDecimal("60.00").add(weight.multiply(new BigDecimal("15.00")));
        } else {
            return new BigDecimal("50.00").add(weight.multiply(new BigDecimal("12.00")));
        }
    }

    /**
     * Estimate delivery days between two postal codes
     */
    public int getQuickDays(String fromPostalCode, String toPostalCode) {
        // Extract first two digits for region comparison
        String fromRegion = fromPostalCode.substring(0, Math.min(2, fromPostalCode.length()));
        String toRegion = toPostalCode.substring(0, Math.min(2, toPostalCode.length()));
        
        // Same postal code
        if (fromPostalCode.equals(toPostalCode)) {
            return 1;
        }
        
        // Same region (first two digits)
        if (fromRegion.equals(toRegion)) {
            return 2;
        }
        
        // Check for special areas (islands)
        if (isSpecialArea(fromPostalCode) || isSpecialArea(toPostalCode)) {
            return 5;
        }
        
        // Check for remote areas
        if (isRemoteArea(fromPostalCode) || isRemoteArea(toPostalCode)) {
            return 4;
        }
        
        // Different regions
        return 3;
    }

    /**
     * Calculate Chunghwa Post shipping fee
     */
    private BigDecimal calculateChunghwaPostFee(ShippingFeeRequest request) {
        BigDecimal baseFee = new BigDecimal("30.00");
        
        // 郵局已改為宅配服務，使用宅配計費
        return baseFee.add(request.weight.multiply(new BigDecimal("8.00")));
    }

    /**
     * Calculate Black Cat shipping fee
     */
    private BigDecimal calculateBlackCatFee(ShippingFeeRequest request) {
        BigDecimal baseFee = new BigDecimal("50.00");
        
        switch (request.serviceType) {
            case "HOME_DELIVERY":
                return baseFee.add(request.weight.multiply(new BigDecimal("15.00")));
            default:
                return baseFee.add(request.weight.multiply(new BigDecimal("15.00")));
        }
    }

    /**
     * Calculate HCT shipping fee
     */
    private BigDecimal calculateHCTFee(ShippingFeeRequest request) {
        BigDecimal baseFee = new BigDecimal("45.00");
        
        switch (request.serviceType) {
            case "HOME_DELIVERY":
                return baseFee.add(request.weight.multiply(new BigDecimal("14.00")));
            default:
                return baseFee.add(request.weight.multiply(new BigDecimal("14.00")));
        }
    }

    /**
     * Calculate Kerry shipping fee
     */
    private BigDecimal calculateKerryFee(ShippingFeeRequest request) {
        BigDecimal baseFee = new BigDecimal("48.00");
        
        switch (request.serviceType) {
            case "HOME_DELIVERY":
                return baseFee.add(request.weight.multiply(new BigDecimal("13.00")));
            default:
                return baseFee.add(request.weight.multiply(new BigDecimal("13.00")));
        }
    }

    /**
     * Calculate SF Express shipping fee
     */
    private BigDecimal calculateSFExpressFee(ShippingFeeRequest request) {
        BigDecimal baseFee = new BigDecimal("55.00");
        
        switch (request.serviceType) {
            case "HOME_DELIVERY":
                return baseFee.add(request.weight.multiply(new BigDecimal("16.00")));
            default:
                return baseFee.add(request.weight.multiply(new BigDecimal("16.00")));
        }
    }

    /**
     * Calculate convenience store shipping fee
     */
    private BigDecimal calculateConvenienceStoreFee(ShippingFeeRequest request) {
        BigDecimal baseFee = new BigDecimal("35.00");
        
        switch (request.serviceType) {
            case "SEVEN_ELEVEN":
            case "FAMILY_MART":
            case "HILIFE":
            case "OK_MART":
                return baseFee.add(request.weight.multiply(new BigDecimal("8.00")));
            default:
                return baseFee.add(request.weight.multiply(new BigDecimal("10.00")));
        }
    }

    /**
     * Calculate T-Cat shipping fee
     */
    private BigDecimal calculateTCatFee(ShippingFeeRequest request) {
        BigDecimal baseFee = new BigDecimal("42.00");
        
        switch (request.serviceType) {
            case "HOME_DELIVERY":
                return baseFee.add(request.weight.multiply(new BigDecimal("13.00")));
            default:
                return baseFee.add(request.weight.multiply(new BigDecimal("13.00")));
        }
    }

    /**
     * Calculate Taiwan Delivery shipping fee
     */
    private BigDecimal calculateTaiwanDeliveryFee(ShippingFeeRequest request) {
        BigDecimal baseFee = new BigDecimal("40.00");
        
        switch (request.serviceType) {
            case "HOME_DELIVERY":
                return baseFee.add(request.weight.multiply(new BigDecimal("12.00")));
            default:
                return baseFee.add(request.weight.multiply(new BigDecimal("12.00")));
        }
    }

    /**
     * Calculate Platform Delivery shipping fee
     */
    private BigDecimal calculatePlatformDeliveryFee(ShippingFeeRequest request) {
        BigDecimal baseFee = new BigDecimal("35.00");
        
        switch (request.serviceType) {
            case "HOME_DELIVERY":
                return baseFee.add(request.weight.multiply(new BigDecimal("10.00")));
            default:
                return baseFee.add(request.weight.multiply(new BigDecimal("10.00")));
        }
    }

    /**
     * Check if postal code is in special area (islands)
     */
    private boolean isSpecialArea(String postalCode) {
        return postalCode.startsWith("88") || // Penghu
               postalCode.startsWith("89") || // Kinmen
               postalCode.startsWith("91") || // Kinmen
               postalCode.startsWith("92") || // Kinmen
               postalCode.startsWith("93") || // Matsu
               postalCode.startsWith("94");   // Matsu
    }

    /**
     * Check if postal code is in remote area
     */
    private boolean isRemoteArea(String postalCode) {
        return postalCode.startsWith("95") || // Taitung
               postalCode.startsWith("96") || // Taitung
               postalCode.startsWith("97") || // Hualien
               postalCode.startsWith("98");   // Hualien
    }
} 