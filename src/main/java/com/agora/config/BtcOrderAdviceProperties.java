package com.agora.config;

import com.agora.service.advice.BtcOrderAdviceEngine;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.math.BigDecimal;

@Data
@Configuration
@ConfigurationProperties(prefix = "trading.btc-order-advice")
public class BtcOrderAdviceProperties {
    private boolean monitorEnabled = false;
    private boolean notificationsEnabled = false;
    private BigDecimal tradeFraction = new BigDecimal("0.25");
    private BigDecimal cashShare = new BigDecimal("0.5");
    private int validMinutes = 60;
    private String stateFile = System.getProperty("user.home") + "/.agora-state/btc-order-advice-v1.json";
    public BtcOrderAdviceEngine.Policy policy() {
        return new BtcOrderAdviceEngine.Policy(tradeFraction, cashShare, validMinutes);
    }
}
