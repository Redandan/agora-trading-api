package com.agora.exception;

import com.agora.util.ClientIdValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 全局異常處理器
 * 處理SSE相關的異常並返回適當的HTTP狀態碼和錯誤信息
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.agora.controller.sse")
@RequiredArgsConstructor
public class SSEExceptionHandler {
    
    private final ClientIdValidator clientIdValidator;
    
    /**
     * 處理ClientId格式錯誤異常
     * 返回HTTP 400 Bad Request
     */
    @ExceptionHandler(ClientIdFormatException.class)
    public ResponseEntity<Map<String, Object>> handleClientIdFormatException(ClientIdFormatException e) {
        log.error("ClientId format error: {}", e.getMessage());
        
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "Invalid ClientId Format");
        errorResponse.put("message", e.getMessage());
        errorResponse.put("clientId", e.getClientId());
        errorResponse.put("expectedFormat", e.getExpectedFormat());
        errorResponse.put("formatDescription", clientIdValidator.getFormatDescription());
        errorResponse.put("regex", clientIdValidator.getFormatRegex());
        errorResponse.put("examples", new String[]{
            "user_123_abc123def456",
            "user_456_device-uuid-123",
            "user_789_mobile-app-001"
        });
        errorResponse.put("rules", new String[]{
            "userId: 必須是數字",
            "deviceId: 8-32位字母數字和連字符組合",
            "deviceId: 不能包含特殊字符（除了-和_）",
            "deviceId: 建議使用UUID或設備指紋"
        });
        errorResponse.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        errorResponse.put("status", HttpStatus.BAD_REQUEST.value());
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }
    
    /**
     * 處理未授權異常
     * 返回HTTP 401 Unauthorized
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorizedException(UnauthorizedException e) {
        log.error("Unauthorized access: {}", e.getMessage());
        
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "Unauthorized");
        errorResponse.put("message", e.getMessage());
        errorResponse.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        errorResponse.put("status", HttpStatus.UNAUTHORIZED.value());
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }
    
    /**
     * 處理業務異常
     * 返回HTTP 400 Bad Request
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException e) {
        log.error("Business error: {}", e.getMessage());
        
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "Business Error");
        errorResponse.put("message", e.getMessage());
        errorResponse.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        errorResponse.put("status", HttpStatus.BAD_REQUEST.value());
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }
    
    /**
     * 處理 SSE 連接超時異常
     * 返回純文本響應以避免 Content-Type 轉換問題
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<String> handleAsyncRequestTimeoutException(AsyncRequestTimeoutException e) {
        log.warn("SSE connection timeout: {}", e.getMessage());
        
        // 返回純文本響應，避免 Content-Type 轉換問題
        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
                .contentType(MediaType.TEXT_PLAIN)
                .body("SSE connection timed out");
    }
    
    /**
     * 處理其他未預期的異常
     * 返回HTTP 500 Internal Server Error
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "Internal Server Error");
        errorResponse.put("message", "An unexpected error occurred");
        errorResponse.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        errorResponse.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
}
