package com.agora.scheduler.trading;

import com.agora.service.trading.FundingArbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Funding Rate Arbitrage 排程器。
 *
 * <p>每 30 min:
 * <ol>
 *   <li>對所有 OPEN position 呼叫 {@code service.reconcileAll()} —— 檢查出場條件、累積 funding</li>
 *   <li>(Phase 1 留空)auto-open 邏輯:留待手動驗證後再開啟</li>
 * </ol>
 *
 * <p>Phase 1 **不啟用 auto-open** —— 第一次必須 Claude 手動用 MCP createFundingArb 測試,
 * 確認 OKX 實際帳戶兩條腿都正確後才考慮開自動進場邏輯。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "trading.funding-arb", name = "enabled", havingValue = "true")
public class FundingArbScheduler {

    private final FundingArbService service;

    @Scheduled(fixedDelayString = "${trading.funding-arb.scheduler.fixed-delay-ms:1800000}",
               initialDelayString = "${trading.funding-arb.scheduler.initial-delay-ms:60000}")
    public void tick() {
        try {
            service.reconcileAll();
        } catch (Throwable t) {
            log.error("[FundingArbScheduler] reconcile fatal: {}", t.getMessage(), t);
        }
        // Phase 1: 不做 auto-open。Claude 用 MCP createFundingArb 手動開倉。
    }
}
