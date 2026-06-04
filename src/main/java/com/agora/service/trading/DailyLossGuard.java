package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 每日虧損熔斷檢查：在開新倉前判斷當日（UTC）累計已平倉 PnL 是否低於設定門檻。
 *
 * <p>觸發熔斷後拒絕新 LONG/SHORT 進場，但不影響既有倉位的正常出場（OCO、SELL 訊號、
 * arbitration、手動平倉等）— 這些仍要能在最壞情況下平倉止損。
 *
 * <p>午夜 00:00 UTC 後自動解除（新一日的累計 PnL 歸零）。
 *
 * <p>設定 {@code trading.okx.daily-loss-limit-usdt} 控制：
 * <ul>
 *   <li>負值（預設 -15）：當日 PnL 低於此值觸發熔斷</li>
 *   <li>0 或正值：停用熔斷</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyLossGuard {

    private final OkxTradingProperties tradingProperties;
    private final BtLiveSignalRepository liveSignalRepository;

    public record GuardResult(boolean allowed, String reason, double todayPnl) {}

    /** 檢查是否允許開新倉。允許時 allowed=true。 */
    public GuardResult check() {
        double limit = tradingProperties.getDailyLossLimitUsdt();
        if (limit >= 0) return new GuardResult(true, "daily loss guard disabled", 0);

        double todayPnl = computeTodayPnl();
        if (todayPnl <= limit) {
            return new GuardResult(false,
                    String.format("當日已平倉 PnL=%+.2f USDT 已低於熔斷門檻 %.2f USDT", todayPnl, limit),
                    todayPnl);
        }
        return new GuardResult(true,
                String.format("當日 PnL=%+.2f USDT（門檻 %.2f）", todayPnl, limit),
                todayPnl);
    }

    /** 計算今日（UTC 00:00 起）所有已平倉倉位的累計 PnL。 */
    public double computeTodayPnl() {
        LocalDateTime startOfDay = LocalDateTime.of(LocalDate.now(ZoneOffset.UTC), LocalTime.MIDNIGHT);
        List<BtLiveSignal> closed = liveSignalRepository
                .findByAutoTradedIsTrueAndExitTimeIsNotNullAndExitTimeAfter(startOfDay);
        BigDecimal sum = BigDecimal.ZERO;
        for (BtLiveSignal s : closed) {
            if (s.getRealizedPnl() != null) sum = sum.add(s.getRealizedPnl());
        }
        return sum.doubleValue();
    }
}
