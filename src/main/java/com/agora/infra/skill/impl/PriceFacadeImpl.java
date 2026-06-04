package com.agora.infra.skill.impl;

import com.agora.infra.skill.PriceFacade;
import com.agora.model.MdKline;
import com.agora.service.market.BinanceKlineImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class PriceFacadeImpl implements PriceFacade {

    private final BinanceKlineImportService binanceKlineImportService;

    @Override
    public MdKline fetchLatestKline(String symbol, String intervalCode) {
        return binanceKlineImportService.fetchLatestKline(symbol, intervalCode);
    }
}
