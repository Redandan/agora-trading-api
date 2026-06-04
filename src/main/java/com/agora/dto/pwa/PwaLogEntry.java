package com.agora.dto.pwa;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;

/**
 * PWA 日誌條目
 * 簡單結構：日誌主體
 * 同時作為 DTO 和 Entity 使用
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PwaLogEntry {
    
    /**
     * 日誌主體（字符串）
     */
    @NotBlank(message = "Log message is required")
    private String log;
}
