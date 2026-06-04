package com.agora.service.backtest;

import com.agora.dto.backtest.BacktestResultResponse;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BacktestRunSummary {

    private double initialCapital;
    private double finalCapital;
    private double totalReturn;
    private double maxDrawdown;
    private double winRate;
    private int tradeCount;
    private int longTradeCount;
    private int shortTradeCount;
    private double longWinRate;
    private double shortWinRate;
    /** Simplified Sharpe Ratio: mean(returnPct) / stdDev(returnPct). NaN when fewer than 2 trades. */
    private double sharpeRatio;
    /** applyFilters=true 時，因 HistoricalFilterEvaluator 判定而略過的進場次數。 */
    private int filteredEntryCount;
    /**
     * #392 Option B — applyRegimeFilter=true 時，因 deterministic regime classifier
     * 投票 TRENDING_DOWN 而略過的 LONG 進場次數。
     */
    private int regimeBlockedCount;
    private List<TradeRecord> trades = new ArrayList<TradeRecord>();
    private List<BacktestResultResponse.DiagnosticLogDto> diagnosticLogs = new ArrayList<BacktestResultResponse.DiagnosticLogDto>();
}
