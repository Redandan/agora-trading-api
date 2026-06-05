package com.agora.scheduler.trading;

import com.agora.infra.notification.NotificationPort;
import com.agora.service.trading.OkxEarnService;
import com.agora.service.trading.OkxTradingService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Keeps the OKX trading account funded from Simple Earn.
 *
 * <p>This is intentionally independent from OCO polling. Capital availability is a trading
 * readiness concern, so it must not depend on position reconciliation completing first.</p>
 *
 * <p>Disabled by default for split-service deploys; enable
 * {@code okx.earn-topup.enabled=true} only after this service should own
 * automatic Earn redemption and transfer side effects.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EarnTradingBufferTopUpScheduler {

    private final OkxTradingService okxTradingService;
    private final OkxEarnService okxEarnService;
    private final NotificationPort notificationPort;

    @Value("${okx.earn-topup.enabled:false}")
    private boolean enabled;

    @PostConstruct
    void logConfig() {
        log.info("[EarnTopUp] config: enabled={}", enabled);
    }

    @Scheduled(
            initialDelayString = "${okx.earn-topup.initial-delay-ms:30000}",
            fixedDelayString = "${okx.earn-topup.fixed-delay-ms:300000}"
    )
    public void maintainTradingBuffer() {
        if (!enabled) {
            return;
        }
        try {
            String balStr = okxTradingService.getUsdtBalance();
            if ("N/A".equals(balStr)) {
                log.warn("[EarnTopUp] Trading account has no USDT row; skip top-up");
                return;
            }

            BigDecimal balance = new BigDecimal(balStr);
            boolean topped = okxEarnService.topUpTradingBuffer(balance);
            if (topped) {
                log.info("[EarnTopUp] Trading buffer topped from Earn: before={} threshold={} target={}",
                        balance.toPlainString(),
                        okxEarnService.getTopupThresholdUsdt().toPlainString(),
                        okxEarnService.getTopupTargetUsdt().toPlainString());
                notificationPort.broadcast(
                        String.format("💰 <b>Simple Earn 自動補倉</b>\n" +
                                        "交易帳戶 USDT %.2f 低於門檻 %.2f，已從 Earn 自動補充至目標 %.2f。",
                                balance.doubleValue(),
                                okxEarnService.getTopupThresholdUsdt().doubleValue(),
                                okxEarnService.getTopupTargetUsdt().doubleValue()),
                        false);
            }
        } catch (Exception e) {
            log.warn("[EarnTopUp] Trading buffer top-up failed (non-blocking): {}", e.getMessage());
        }
    }
}
