package com.agora.exception;

import lombok.Getter;

import java.util.List;

@Getter
public class TurnstileVerificationException extends RuntimeException {
    private final List<String> errorCodes;
    
    public TurnstileVerificationException(String message, List<String> errorCodes) {
        super(message);
        this.errorCodes = errorCodes;
    }
}
