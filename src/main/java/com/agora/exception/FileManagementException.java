package com.agora.exception;

public class FileManagementException extends RuntimeException {
    
    public FileManagementException(String message) {
        super(message);
    }
    
    public FileManagementException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public FileManagementException(ErrorCodes errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
    
    public FileManagementException(ErrorCodes errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
    
    private ErrorCodes errorCode;
    
    public ErrorCodes getErrorCode() {
        return errorCode;
    }
    
    public enum ErrorCodes {
        SEARCH_FAILED("SEARCH_FAILED"),
        UPLOAD_FAILED("UPLOAD_FAILED"),
        DELETE_FAILED("DELETE_FAILED"),
        UPDATE_FAILED("UPDATE_FAILED"),
        VALIDATION_FAILED("VALIDATION_FAILED"),
        STORAGE_FAILED("STORAGE_FAILED");
        
        private final String code;
        
        ErrorCodes(String code) {
            this.code = code;
        }
        
        public String getCode() {
            return code;
        }
    }
}
