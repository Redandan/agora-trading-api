package com.agora.service.backtest;

import com.agora.dto.backtest.BacktestResultResponse;

final class DiagnosticMessages {

    private DiagnosticMessages() {
    }

    static BacktestResultResponse.DiagnosticLogDto noTradeFallback() {
        BacktestResultResponse.DiagnosticLogDto dto = new BacktestResultResponse.DiagnosticLogDto();
        dto.setCode(DiagnosticCode.NO_TRADE_TRIGGERED.getCode());
        dto.setCount(1);
        dto.setSampleDetail("本次回測未觸發任何交易，且策略未提供細部診斷。");
        return dto;
    }
}