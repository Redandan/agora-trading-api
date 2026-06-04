package com.agora.dto.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;

@Data
@Schema(description = "帳變歷史查詢參數")
public class TransactionListParam {
    
    @Schema(description = "幣種", example = "USDT", required = true)
    private String token;
    
    @Schema(description = "頁碼，從1開始", example = "1")
    @Min(value = 1, message = "頁碼必須大於等於1")
    private int page = 1;
    
    @Schema(description = "每頁數量", example = "20")
    @Min(value = 1, message = "每頁數量必須大於等於1")
    @Max(value = 100, message = "每頁數量不能超過100")
    private int size = 20;
    
    @Schema(description = "開始日期 (ISO-8601 格式)", example = "2024-01-01T00:00:00")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startDate;
    
    @Schema(description = "結束日期 (ISO-8601 格式)", example = "2024-12-31T23:59:59")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endDate;
}
