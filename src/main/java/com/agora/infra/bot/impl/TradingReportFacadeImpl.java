package com.agora.infra.bot.impl;

import com.agora.infra.bot.TradingReportFacade;
import com.agora.service.backtest.TradingAnalysisService;
import com.agora.service.trading.TradingManagerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class TradingReportFacadeImpl implements TradingReportFacade {

    private final TradingManagerService tradingManagerService;
    private final TradingAnalysisService tradingAnalysisService;

    @Override
    public String currentSituation() {
        return tradingManagerService.reportCurrentSituation();
    }

    @Override
    public String marketAnalysis() {
        return tradingAnalysisService.analyze();
    }

    @Override
    public String weeklyReport() {
        return tradingManagerService.reportWeekly();
    }
}
