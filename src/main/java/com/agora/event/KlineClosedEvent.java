package com.agora.event;

import com.agora.model.MdKline;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 每當 BinanceWsKlineService 收到一根已收盤的 K 線並存入 DB 後，發佈此事件。
 * LiveSignalEvaluator 監聽此事件以即時評估策略訊號。
 */
@Getter
public class KlineClosedEvent extends ApplicationEvent {

    private final MdKline kline;

    public KlineClosedEvent(Object source, MdKline kline) {
        super(source);
        this.kline = kline;
    }
}
