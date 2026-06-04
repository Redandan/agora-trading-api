package com.agora.dto.tron;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TronConfirmationRecord {

    private String hash;
    private BigDecimal amount;
    private String from;
    private String to;
    private String contractAddress;
    private LocalDateTime timestamp;
    private String result;
    private String message;

}
