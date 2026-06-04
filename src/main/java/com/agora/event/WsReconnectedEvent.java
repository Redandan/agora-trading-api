package com.agora.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * WS 斷線重連成功後發佈此事件，供 LiveSignalEvaluator 補跑最新一根 bar 的評估。
 * 只在重連時發佈（reconnectAttempts > 0），初次連線不發佈。
 */
@Getter
public class WsReconnectedEvent extends ApplicationEvent {

    private final String symbol;
    private final String intervalCode;

    public WsReconnectedEvent(Object source, String symbol, String intervalCode) {
        super(source);
        this.symbol = symbol;
        this.intervalCode = intervalCode;
    }
}
