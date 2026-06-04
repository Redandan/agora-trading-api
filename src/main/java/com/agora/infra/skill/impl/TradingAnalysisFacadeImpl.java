package com.agora.infra.skill.impl;

import com.agora.infra.skill.TradingAnalysisFacade;
import com.agora.service.backtest.TradingAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class TradingAnalysisFacadeImpl implements TradingAnalysisFacade {

    private final TradingAnalysisService tradingAnalysisService;

    @Override
    public String analyze() {
        return tradingAnalysisService.analyze();
    }
}
