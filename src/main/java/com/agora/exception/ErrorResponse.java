package com.agora.exception;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {
    private String code;        // 错误代码
    private String message;     // 错误消息
    private String details;     // 详细描述
} 