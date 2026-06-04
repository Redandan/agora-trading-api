package com.agora.exception;


import com.agora.dto.common.FileAssociationErrorResponse;
import com.agora.service.DatabaseConnectionAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.apache.catalina.connector.ClientAbortException;

import jakarta.servlet.http.HttpServletRequest;

@Slf4j
@RestControllerAdvice(basePackages = "com.agora.controller")
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final DatabaseConnectionAlertService databaseConnectionAlertService;

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFoundException(ResourceNotFoundException ex) {
        ErrorResponse response = new ErrorResponse(
                "RESOURCE_NOT_FOUND",
                "Resource Not Found",
                ex.getMessage()
        );
        return new ResponseEntity<>(response, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientBalanceException(InsufficientBalanceException ex) {
        ErrorResponse response = new ErrorResponse(
                "INSUFFICIENT_BALANCE",
                "Insufficient Balance",
                ex.getMessage()
        );
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(InvalidTransactionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransactionException(InvalidTransactionException ex) {
        ErrorResponse response = new ErrorResponse(
                "INVALID_TRANSACTION",
                "Invalid Transaction",
                ex.getMessage()
        );
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(WalletException.class)
    public ResponseEntity<ErrorResponse> handleWalletException(WalletException ex) {
        ErrorResponse response = new ErrorResponse(
                "WALLET_ERROR",
                "Wallet Error",
                ex.getMessage()
        );
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<String> handleSecurityException(SecurityException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException ex) {
        ErrorResponse response = new ErrorResponse(
                "ACCESS_DENIED",
                "Access Denied",
                ex.getMessage()
        );
        return new ResponseEntity<>(response, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException ex) {
        ErrorResponse response = new ErrorResponse(
                "AUTHENTICATION_FAILED",
                "Authentication Failed",
                ex.getMessage()
        );
        return new ResponseEntity<>(response, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(MethodArgumentNotValidException ex) {
        // 獲取第一個驗證錯誤
        String fieldName = null;
        String errorMessage = null;
        String errorCode = null;
        
        if (!ex.getBindingResult().getAllErrors().isEmpty()) {
            FieldError fieldError = (FieldError) ex.getBindingResult().getAllErrors().get(0);
            fieldName = fieldError.getField();
            errorMessage = fieldError.getDefaultMessage();
            
            // 根據錯誤類型生成錯誤代碼
            errorCode = generateValidationErrorCode(fieldError);
        }
        
        ErrorResponse result = new ErrorResponse(
                errorCode != null ? errorCode : "VALIDATION_FAILED",
                "Validation Failed",
                fieldName != null ? fieldName + ": " + errorMessage : errorMessage
        );
        return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
    }
    
    /**
     * 根據驗證錯誤生成錯誤代碼
     */
    private String generateValidationErrorCode(FieldError fieldError) {
        String fieldName = fieldError.getField();
        String errorCode = fieldError.getCode();
        
        // 根據欄位和錯誤類型生成標準化的錯誤代碼
        if ("username".equals(fieldName)) {
            if ("Size".equals(errorCode)) {
                return "USERNAME_SIZE_INVALID";
            } else if ("NotBlank".equals(errorCode)) {
                return "USERNAME_REQUIRED";
            }
        } else if ("email".equals(fieldName)) {
            if ("Email".equals(errorCode)) {
                return "EMAIL_FORMAT_INVALID";
            } else if ("NotBlank".equals(errorCode)) {
                return "EMAIL_REQUIRED";
            }
        } else if ("password".equals(fieldName)) {
            if ("Size".equals(errorCode)) {
                return "PASSWORD_SIZE_INVALID";
            } else if ("NotBlank".equals(errorCode)) {
                return "PASSWORD_REQUIRED";
            }
        } else if ("confirmPassword".equals(fieldName)) {
            if ("NotBlank".equals(errorCode)) {
                return "CONFIRM_PASSWORD_REQUIRED";
            }
        }
        
        // 默認錯誤代碼
        return "VALIDATION_FAILED";
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
        ErrorResponse response = new ErrorResponse(
                "BUSINESS_ERROR",
                "Business Error",
                ex.getMessage()
        );
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(FileAssociationException.class)
    public ResponseEntity<FileAssociationErrorResponse> handleFileAssociationException(FileAssociationException ex) {
        return new ResponseEntity<>(ex.getErrorResponse(), HttpStatus.BAD_REQUEST);
    }

    /**
     * Handle SSE connection timeout exceptions specifically
     * This prevents the content-type conversion error when SSE connections timeout
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<String> handleAsyncRequestTimeoutException(AsyncRequestTimeoutException ex) {
        // Return a simple string message instead of ErrorResponse to avoid content-type conversion issues
        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
                .contentType(MediaType.TEXT_PLAIN)
                .body("SSE connection timed out");
    }

    /**
     * Handle client abort exceptions (broken pipe)
     * This is a normal case when clients disconnect, so we don't log it as an error
     */
    @ExceptionHandler(ClientAbortException.class)
    public ResponseEntity<?> handleClientAbortException(ClientAbortException ex, WebRequest request) {
        // Check if it's a broken pipe error (normal client disconnect)
        String message = ex.getMessage();
        boolean isBrokenPipe = message != null && (
                message.contains("Broken pipe") ||
                message.contains("Connection reset by peer")
        );
        
        if (isBrokenPipe) {
            // This is a normal client disconnect, only log at debug level
            log.debug("Client disconnected (broken pipe) - this is normal: {}", ex.getMessage());
        } else {
            // Other client abort errors, log at warn level
            log.warn("Client abort exception: {}", ex.getMessage());
        }
        
        // For SSE requests, return empty response
        if (isSSERequest(request)) {
            return ResponseEntity.status(HttpStatus.OK)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("");
        }
        
        // For other requests, return no content
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /**
     * 處理數據庫訪問異常（包括連接錯誤）
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccessException(DataAccessException ex) {
        // 檢測是否為數據庫連接錯誤
        if (databaseConnectionAlertService.isDatabaseConnectionError(ex)) {
            // 發送 Telegram 告警
            databaseConnectionAlertService.sendDatabaseConnectionAlert(
                ex, 
                "GlobalExceptionHandler - Controller"
            );
            
            log.error("數據庫連接錯誤已被捕獲並發送告警", ex);
        }
        
        ErrorResponse response = new ErrorResponse(
                "DATABASE_ERROR",
                "Database Error",
                "數據庫操作失敗，請稍後再試"
        );
        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGlobalException(Exception ex, WebRequest request) {
        // 檢查異常鏈中是否包含數據庫連接錯誤
        if (databaseConnectionAlertService.isDatabaseConnectionError(ex)) {
            // 發送 Telegram 告警
            databaseConnectionAlertService.sendDatabaseConnectionAlert(
                ex, 
                "GlobalExceptionHandler - General Exception"
            );
            
            log.error("數據庫連接錯誤已被捕獲並發送告警", ex);
        }
        
        // 檢測是否為SSE請求（Server-Sent Events）
        if (isSSERequest(request)) {
            log.error("SSE connection error: {}", ex.getMessage(), ex);
            // SSE請求返回純文本錯誤，避免Content-Type衝突
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("SSE connection error: " + (ex.getMessage() != null ? ex.getMessage() : "Internal Server Error"));
        }
        
        // 普通請求返回JSON錯誤
        ErrorResponse response = new ErrorResponse(
                "INTERNAL_SERVER_ERROR",
                "Internal Server Error",
                ex.getMessage()
        );
        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }
    
    /**
     * 檢測是否為SSE請求
     * 通過檢查請求路徑和Accept header來判斷
     */
    private boolean isSSERequest(WebRequest request) {
        if (request instanceof ServletWebRequest) {
            ServletWebRequest servletRequest = (ServletWebRequest) request;
            HttpServletRequest httpRequest = servletRequest.getRequest();
            
            // 方法1: 檢查請求路徑是否包含 /sse/
            String requestURI = httpRequest.getRequestURI();
            if (requestURI != null && requestURI.contains("/sse/")) {
                return true;
            }
            
            // 方法2: 檢查Accept header是否包含 text/event-stream
            String acceptHeader = httpRequest.getHeader("Accept");
            if (acceptHeader != null && acceptHeader.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
                return true;
            }
        }
        
        return false;
    }
} 
