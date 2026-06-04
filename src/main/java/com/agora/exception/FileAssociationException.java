package com.agora.exception;

import com.agora.dto.common.FileAssociationErrorResponse;

/**
 * 文件關聯錯誤異常
 * 用於處理文件與業務實體關聯時的錯誤
 */
public class FileAssociationException extends BusinessException {
    
    private final FileAssociationErrorResponse errorResponse;
    
    public FileAssociationException(FileAssociationErrorResponse errorResponse) {
        super("文件關聯錯誤: " + errorResponse.getMessage());
        this.errorResponse = errorResponse;
    }
    
    public FileAssociationException(String message, FileAssociationErrorResponse errorResponse) {
        super(message);
        this.errorResponse = errorResponse;
    }
    
    public FileAssociationErrorResponse getErrorResponse() {
        return errorResponse;
    }
}
