package com.agora.dto.tron;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TronAddressInfo {
    private String address;
    private BigDecimal trxBalance = BigDecimal.ZERO;
    private BigDecimal usdtBalance = BigDecimal.ZERO;
}
