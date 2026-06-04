package com.agora.service.indicator;

/**
 * 複合指標子維度聲明。
 *
 * @param mhiKey  market_indicator_history 中的 indicator 欄位名稱，例如 "sqi_short_crowding"
 * @param label   人類可讀標籤，例如 "空頭擁擠度"
 * @param weight  權重（0.0-1.0），所有子維度之和必須 = 1.0
 */
public record SubDimension(String mhiKey, String label, double weight) {

    public SubDimension {
        if (weight <= 0 || weight > 1) {
            throw new IllegalArgumentException("SubDimension weight must be in (0, 1]: " + weight);
        }
    }
}
