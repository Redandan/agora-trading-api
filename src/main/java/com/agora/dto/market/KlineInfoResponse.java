package com.agora.dto.market;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * K 線資料覆蓋範圍與量能統計，用於快速驗證數據質量。
 */
@Data
@AllArgsConstructor
public class KlineInfoResponse {
    private String symbol;
    private String intervalCode;
    private long count;
    private LocalDateTime firstBar;
    private LocalDateTime lastBar;
    private double minVolume;
    private double maxVolume;
    private double avgVolume;
}
