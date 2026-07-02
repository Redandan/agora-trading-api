package com.agora.event;

import com.agora.model.MdKline;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 每當 BinanceWsKlineService 收到一根已收盤的 K 線並存入 DB 後，發佈此事件。
 * Market-data collectors publish this event when a K-line closes. The legacy
 * LiveSignalEvaluator consumes it only when the signal-source policy explicitly
 * enables the legacy live evaluator; TradingView-primary mode skips it.
 */
@Getter
public class KlineClosedEvent extends ApplicationEvent {

    private final MdKline kline;

    public KlineClosedEvent(Object source, MdKline kline) {
        super(source);
        this.kline = kline;
    }
}
