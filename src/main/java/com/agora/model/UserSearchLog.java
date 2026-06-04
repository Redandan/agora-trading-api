package com.agora.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 用戶搜尋紀錄實體類（AOP異步記錄）
 */
@Data
@Entity
@Table(name = "user_search_log")
@Schema(description = "用戶搜尋紀錄")
public class UserSearchLog {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "搜尋紀錄ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Column(name = "user_id")
    @Schema(description = "用戶ID（可為空，支持未登入用戶）", example = "1", nullable = true)
    private Long userId;

    // ========== 請求信息 ==========
    
    @Column(name = "request_method", length = 10)
    @Schema(description = "請求方法", example = "GET", nullable = true, maxLength = 10)
    private String requestMethod;

    @Column(name = "request_uri", length = 500)
    @Schema(description = "請求URI", example = "/api/products/search", nullable = true, maxLength = 500)
    private String requestUri;

    @Column(name = "request_params", columnDefinition = "TEXT")
    @Schema(description = "請求參數（JSON格式）", example = "{\"keyword\":\"商品\",\"page\":1}", nullable = true)
    private String requestParams;

    @Column(name = "request_body", columnDefinition = "TEXT")
    @Schema(description = "請求體（JSON格式）", example = "{\"keyword\":\"商品\"}", nullable = true)
    private String requestBody;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    @Schema(description = "用戶代理", example = "Mozilla/5.0...", nullable = true)
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    @Schema(description = "IP地址", example = "192.168.1.1", nullable = true, maxLength = 45)
    private String ipAddress;

    // ========== 響應信息 ==========
    
    @Column(name = "response_status")
    @Schema(description = "響應狀態碼", example = "200", nullable = true)
    private Integer responseStatus;

    @Column(name = "response_time_ms")
    @Schema(description = "響應時間(毫秒)", example = "150", nullable = true)
    private Long responseTimeMs;

    @Column(name = "response_size")
    @Schema(description = "響應大小(字節)", example = "1024", nullable = true)
    private Long responseSize;

    // ========== 搜尋特定信息 ==========
    
    @Column(name = "search_type", length = 50)
    @Schema(description = "搜尋類型（PRODUCT, POST等）", example = "PRODUCT", nullable = true, maxLength = 50)
    private String searchType;

    @Column(name = "keyword", length = 200)
    @Schema(description = "搜尋關鍵字", example = "商品", nullable = true, maxLength = 200)
    private String keyword;

    @Column(name = "raw_query", length = 300)
    @Schema(description = "原始搜尋字串（用於分類需求證據）", example = "日本 Apple 禮物卡", nullable = true, maxLength = 300)
    private String rawQuery;

    @Column(name = "normalized_keyword", length = 300)
    @Schema(description = "正規化搜尋關鍵字（聚合熱門/零結果查詢）", example = "apple gift card jp", nullable = true, maxLength = 300)
    private String normalizedKeyword;

    @Column(name = "result_count")
    @Schema(description = "搜尋結果數量", example = "10", nullable = true)
    private Long resultCount;

    @Column(name = "zero_result")
    @Schema(description = "是否為零結果搜尋", example = "false", nullable = true)
    private Boolean zeroResult;

    // ========== 時間信息 ==========
    
    @Column(name = "request_time")
    @Schema(description = "請求時間", example = "2024-01-01T10:00:00", nullable = true)
    private LocalDateTime requestTime;

    @Column(name = "response_time")
    @Schema(description = "響應時間", example = "2024-01-01T10:00:00", nullable = true)
    private LocalDateTime responseTime;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Schema(description = "記錄時間", example = "2024-01-01T10:00:00", nullable = true)
    private LocalDateTime createdAt;
}
