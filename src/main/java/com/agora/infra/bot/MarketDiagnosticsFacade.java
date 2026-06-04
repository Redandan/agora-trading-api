package com.agora.infra.bot;

import com.agora.dto.market.KlineSubscriptionInfo;

import java.util.List;

/**
 * Bot-facing diagnostics surface. {@code /diag} depends on this interface,
 * not on MarketSignalCache or KlineStreamService directly — keeps
 * {@code com.agora.bot} free of trading-domain imports (plan §3).
 */
public interface MarketDiagnosticsFacade {

    /** WS subscriptions aggregated across every KlineStreamService impl (binance + okx). */
    List<KlineSubscriptionInfo> allSubscriptions();

    /** Current evaluation snapshots projected from MarketSignalCache. */
    List<EvalSnapshotView> allSignalSnapshots();
}
