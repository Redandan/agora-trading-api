package com.agora.scheduler.trading;

import com.agora.service.trading.WashoutAccumulationIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WashoutAccumulationIndexScheduler {

    private final WashoutAccumulationIndexService waiService;

    @Value("${trading.wai.enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${trading.wai.cron:0 5 * * * *}", zone = "UTC")
    public void runHourlyWai() {
        if (!enabled) {
            return;
        }
        try {
            WashoutAccumulationIndexService.CalculationResult result =
                    waiService.calculateAndPersistLatest("BTCUSDT", "1h");
            if (result.skipped()) {
                log.info("[WAI] skipped symbol={} interval={} reason={}",
                        result.symbol(), result.intervalCode(), result.skipReason());
                return;
            }
            var s = result.snapshot();
            log.info("[WAI] symbol={} interval={} capturedAt={} wai_score={} wai_stage={} wai_invalidated={} wai_breakout_ready={} rowsWritten={}",
                    result.symbol(), result.intervalCode(), result.capturedAt(), s.waiScore(), s.waiStage(),
                    s.waiInvalidated(), s.waiBreakoutReady(), result.rowsWritten());
        } catch (Throwable t) {
            log.warn("[WAI] hourly calculation failed: {}", t.getMessage(), t);
        }
    }
}
