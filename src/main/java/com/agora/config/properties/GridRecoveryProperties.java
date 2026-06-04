package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #378 — Grid orphan-recovery scanner config (5 keys, 1 class).
 *
 * <p>#436 Bug B — timeToleranceMinutes 從 5 提高到 60,並改為 forward-only window
 * (intent_at - 60s ≤ fillTime ≤ intent_at + N min)。原本 ±5min abs() 對 trySell
 * retry 後 reset intent_at 的場景太窄,讓第一輪 OKX 成交但 parse fail 的 fill 漏配對,
 * 整個 level 卡在 SELL_FAILED 又被 main-loop 重試 → 51020 spam。
 */
@Validated
@ConfigurationProperties(prefix = "grid.recovery")
public record GridRecoveryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60") @PositiveOrZero long delaySeconds,
        @DefaultValue("30") @Positive long giveUpMinutes,
        @DefaultValue("50.0") @Positive double priceTolerance,
        @DefaultValue("60") @Positive long timeToleranceMinutes
) {
}
