package com.agora.service.backtest;

import com.agora.config.properties.TradingSignalSourceProperties;
import com.agora.event.KlineClosedEvent;
import com.agora.model.MdKline;
import com.agora.service.trading.TradingSignalSourcePolicy;
import com.agora.service.tradingview.LocalTradingViewSignalEvaluator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class KlineClosedEventListenerTest {

    @Test
    void tradingViewPrimarySkipsLegacyLiveEvaluator() {
        LiveSignalEvaluator evaluator = mock(LiveSignalEvaluator.class);
        LocalTradingViewSignalEvaluator local = mock(LocalTradingViewSignalEvaluator.class);
        KlineClosedEventListener listener = new KlineClosedEventListener(
                evaluator,
                new TradingSignalSourcePolicy(new TradingSignalSourceProperties("TRADINGVIEW", false)),
                local);

        listener.onKlineClosed(new KlineClosedEvent(this, kline("BTCUSDT", "1D")));

        verify(evaluator, never()).evaluate("BTCUSDT", "1D");
        verify(local, never()).evaluate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void legacyPrimaryWithExplicitEnableRunsEvaluator() {
        LiveSignalEvaluator evaluator = mock(LiveSignalEvaluator.class);
        LocalTradingViewSignalEvaluator local = mock(LocalTradingViewSignalEvaluator.class);
        KlineClosedEventListener listener = new KlineClosedEventListener(
                evaluator,
                new TradingSignalSourcePolicy(new TradingSignalSourceProperties("LEGACY", true)),
                local);

        listener.onKlineClosed(new KlineClosedEvent(this, kline("BTCUSDT", "1D")));

        verify(evaluator).evaluate("BTCUSDT", "1D");
        verify(local, never()).evaluate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void localTradingViewPrimaryRunsLocalParityEvaluatorOnly() {
        LiveSignalEvaluator evaluator = mock(LiveSignalEvaluator.class);
        LocalTradingViewSignalEvaluator local = mock(LocalTradingViewSignalEvaluator.class);
        KlineClosedEventListener listener = new KlineClosedEventListener(
                evaluator,
                new TradingSignalSourcePolicy(new TradingSignalSourceProperties("LOCAL_TRADINGVIEW", false)),
                local);

        MdKline kline = kline("BTCUSDT", "1D");
        listener.onKlineClosed(new KlineClosedEvent(this, kline));

        verify(local).evaluate(kline);
        verify(evaluator, never()).evaluate("BTCUSDT", "1D");
    }

    @Test
    void oneMinuteBarsRemainIgnoredEvenWhenLegacyEnabled() {
        LiveSignalEvaluator evaluator = mock(LiveSignalEvaluator.class);
        LocalTradingViewSignalEvaluator local = mock(LocalTradingViewSignalEvaluator.class);
        KlineClosedEventListener listener = new KlineClosedEventListener(
                evaluator,
                new TradingSignalSourcePolicy(new TradingSignalSourceProperties("LEGACY", true)),
                local);

        listener.onKlineClosed(new KlineClosedEvent(this, kline("BTCUSDT", "1m")));

        verify(evaluator, never()).evaluate("BTCUSDT", "1m");
        verify(local, never()).evaluate(org.mockito.ArgumentMatchers.any());
    }

    private MdKline kline(String symbol, String intervalCode) {
        MdKline kline = new MdKline();
        kline.setSymbol(symbol);
        kline.setIntervalCode(intervalCode);
        kline.setOpenTime(LocalDateTime.of(2026, 7, 2, 0, 0));
        kline.setSource("okx");
        return kline;
    }
}
