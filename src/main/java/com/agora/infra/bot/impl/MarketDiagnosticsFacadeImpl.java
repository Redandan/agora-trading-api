package com.agora.infra.bot.impl;

import com.agora.dto.market.KlineSubscriptionInfo;
import com.agora.infra.bot.EvalSnapshotView;
import com.agora.infra.bot.MarketDiagnosticsFacade;
import com.agora.service.backtest.MarketSignalCache;
import com.agora.service.market.KlineStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
class MarketDiagnosticsFacadeImpl implements MarketDiagnosticsFacade {

    private final MarketSignalCache marketSignalCache;
    private final List<KlineStreamService> wsKlineServices;

    @Override
    public List<KlineSubscriptionInfo> allSubscriptions() {
        return wsKlineServices.stream()
                .flatMap(svc -> svc.listSubscriptions().stream())
                .toList();
    }

    @Override
    public List<EvalSnapshotView> allSignalSnapshots() {
        return marketSignalCache.getAll().stream()
                .map(s -> new EvalSnapshotView(
                        s.symbol, s.intervalCode, s.signal.name(), s.nnOutput, s.rsi))
                .toList();
    }
}
