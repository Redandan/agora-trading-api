package com.agora.infra.bot;

/**
 * Bot-facing report surface. Telegram bot controllers depend on this interface,
 * not on TradingManagerService / TradingAnalysisService directly — keeps
 * {@code com.agora.bot} free of trading-domain imports (plan §3).
 */
public interface TradingReportFacade {

    /** Current account/positions snapshot (used by /report and /manager). */
    String currentSituation();

    /** Market state + AI analysis (used by /report and /analysis). */
    String marketAnalysis();

    /** Past 7-day trade summary (used by /weekly). */
    String weeklyReport();
}
