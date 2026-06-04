package com.agora.dto;

import lombok.Data;
import java.util.Map;

@Data
public class ClientLogDto {
    private String level;        // 日誌級別 (DEBUG, INFO, WARN, ERROR)
    private String message;      // 日誌消息
    private String url;          // 發生日誌的頁面 URL
    private String userAgent;    // 用戶代理字符串
    private String device;       // 設備類型
    private Long userId;         // 用戶ID (可選)
    private String userIp;       // 用戶IP地址
    private Map<String, Object> details; // 額外詳細信息
    private Long timestamp;      // 日誌時間戳
}
