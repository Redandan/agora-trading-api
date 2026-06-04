package com.agora.exception;

import lombok.Getter;

/**
 * ClientId格式錯誤異常
 * 用於處理SSE連接時ClientId格式不正確的情況
 */
@Getter
public class ClientIdFormatException extends RuntimeException {
    
    private final String clientId;
    private final String expectedFormat;
    private final String formatDescription;
    
    public ClientIdFormatException(String clientId, String expectedFormat, String formatDescription) {
        super(String.format("Invalid client ID format: %s. Expected format: %s", clientId, expectedFormat));
        this.clientId = clientId;
        this.expectedFormat = expectedFormat;
        this.formatDescription = formatDescription;
    }
    
    public ClientIdFormatException(String clientId, String expectedFormat) {
        this(clientId, expectedFormat, null);
    }
}
