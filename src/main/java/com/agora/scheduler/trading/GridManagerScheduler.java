package com.agora.scheduler.trading;

import com.agora.service.trading.GridManagerService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "trading.grid.enabled", havingValue = "true", matchIfMissing = false)
public class GridManagerScheduler {

    private final GridManagerService gridManagerService;

    /**
     * 預設每 5 分鐘檢查一次。fixedDelay 從上一次 finish 算,避免 overlap。
     */
    @Scheduled(fixedDelayString = "${trading.grid.check-interval-ms:300000}")
    public void checkAllGrids() {
        gridManagerService.checkAllGrids();
    }
}
