package com.agora.scheduler.trading;

import com.agora.model.MarketFlipEvent;
import com.agora.repository.trading.MarketFlipEventRepository;
import com.agora.service.meta.MarketFlipConsensusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 2B 主流程:每 5 分鐘掃 PENDING {@link MarketFlipEvent},
 * 交給 {@link MarketFlipConsensusService} 跑並行 AI 分析 + 共識合成。
 *
 * <p>若 AI 全失敗,event 仍保持 PENDING,下次 tick 會重試。
 * 60 分鐘還沒處理好的由 {@link MarketFlipAutoEscalateScheduler} 強制升級發 TG。
 *
 * <p>Config:
 * <ul>
 *   <li>{@code meta-control.market-flip.analysis-enabled} (預設 true)</li>
 *   <li>{@code meta-control.market-flip.analysis-batch-size} (預設 10) — 單次 tick 處理上限</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketFlipAnalysisScheduler {

    private final MarketFlipEventRepository eventRepo;
    private final MarketFlipConsensusService consensusService;
    private final com.agora.config.properties.MarketFlipProperties props;

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)  // 每 5min, 啟動 2min 後首跑
    public void tick() {
        if (!props.analysisEnabled()) return;
        try {
            List<MarketFlipEvent> pending = eventRepo
                    .findByStatusOrderByDetectedAtAsc("PENDING", PageRequest.of(0, props.analysisBatchSize()));
            if (pending.isEmpty()) return;

            log.info("[FlipAnalysis] 處理 {} 筆 PENDING events", pending.size());
            int ok = 0, failed = 0;
            for (MarketFlipEvent e : pending) {
                if (consensusService.processEvent(e)) ok++; else failed++;
            }
            log.info("[FlipAnalysis] tick 完成: ok={} failed={} (失敗的 event 保持 PENDING 等下次 tick)",
                    ok, failed);
        } catch (Throwable t) {
            log.error("[FlipAnalysis] tick fatal: {}", t.getMessage(), t);
        }
    }
}
