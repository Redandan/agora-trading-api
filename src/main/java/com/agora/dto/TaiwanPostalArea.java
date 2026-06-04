package com.agora.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 台灣郵遞區號行政區劃實體類
 * 存儲台灣地區的郵遞區號、縣市、行政區信息
 */
@Data
@AllArgsConstructor
public class TaiwanPostalArea {

    private String postalCode;
    private String city;
    private String district;
    private boolean isActive;
} 