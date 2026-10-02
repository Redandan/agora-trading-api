package com.agora.service.trading;

import com.agora.config.properties.BtcDraRuntimeProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.model.BtDecisionAudit;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.service.tradingview.TradingViewScoreBuyAutoExitStrategyContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Passive post-dispatch observations. No provider, scheduler, reservation or execution dependency. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpotPerformanceObservationService {
    private final BtLiveSignalRepository positions;
    private final BtDecisionAuditRepository audits;
    private final BtcDraRuntimeProperties dra;
    private final TradingViewLocalSignalProperties tv;
    private final ObjectMapper mapper;

    @Async("metaAuditExecutor")
    public void afterClosedBar(MdKline bar) {
        afterClosedBarAt(bar, LocalDateTime.now(ZoneOffset.UTC));
    }

    synchronized void afterClosedBarAt(MdKline bar, LocalDateTime now) {
        if (!SpotPerformancePolicy.freshBar(bar, now)) return;
        try {
            observe("DRA_V1", BtcDraPolicy.RUNTIME_LEDGER_STRATEGY_ID, dra.maxLiveExposureUsdt(), bar, now);
            observe("TV509", TradingViewScoreBuyAutoExitStrategyContract.CURRENT_DATABASE_STRATEGY_ID,
                    tv.btcBaseMaxExposureUsdt(), bar, now);
        } catch (Exception e) {
            log.warn("[SpotPerformance] observation unavailable bar={} errorType={}",
                    bar.getOpenTime(), e.getClass().getSimpleName());
        }
    }

    private void observe(String owner, Long strategyId, BigDecimal capital, MdKline bar, LocalDateTime now)
            throws Exception {
        // Save commits before the synchronized observer exits. Report also checks duplicate keys.
        if (audits.existsByStrategyIdAndSymbolAndIntervalCodeAndBarOpenTimeAndEventType(
                strategyId, "BTCUSDT", "1h", bar.getOpenTime(), SpotPerformancePolicy.EVENT)) return;
        var rows = positions.findByStrategyIdAndSymbol(strategyId, "BTCUSDT");
        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setEventTime(now);
        audit.setStrategyId(strategyId);
        audit.setSymbol("BTCUSDT");
        audit.setIntervalCode("1h");
        audit.setBarOpenTime(bar.getOpenTime());
        audit.setEventType(SpotPerformancePolicy.EVENT);
        audit.setOutcome("INFO");
        audit.setReason(SpotPerformancePolicy.BASIS);
        var snapshot = SpotPerformancePolicy.snapshot(owner, rows, capital, bar, now);
        snapshot.put("configuredMode", "DRA_V1".equals(owner) ? String.valueOf(dra.mode())
                : tv.effectiveExecutionLiveOrderEnabled() ? "LIVE" : "NOT_LIVE");
        audit.setContextJson(mapper.writeValueAsString(snapshot));
        audits.saveAndFlush(audit);
    }
}
