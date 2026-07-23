package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * #412 — Deterministic price-scenario simulation.
 *
 * <p>Given a target price (e.g. "what if BTC hits $80,000?"), walk the
 * monotonic LINEAR path from current to target and trigger every reachable
 * OCO TP/SL event in price order. Output the trigger
 * timeline, per-event PnL, and final account state.
 *
 * <p>Custom Grid simulation was removed with the executable custom Grid
 * runtime. No strategy entry simulation or Monte Carlo path is included.
 *
 * <p><b>Fee model</b>: flat OKX taker 0.1% per trade. Cumulative fees
 * shown separately so realized PnL stays comparable to the position book.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceScenarioSimulationService {

    /** OKX spot taker fee (0.1%). Used uniformly for all simulated fills. */
    private static final double TAKER_FEE_RATE = 0.001;

    private final BtLiveSignalRepository liveSignalRepo;
    private final OkxTradingService okxTradingService;

    public enum EventType {
        OCO_TP, OCO_SL
    }

    public record TriggerEvent(
            EventType type,
            double triggerPrice,
            String description,
            double qtyBtc,
            double cashDelta,    // USDT change (signed: + on sell, - on buy)
            double btcDelta,     // BTC change (signed: - on sell, + on buy)
            double realizedPnl,  // 0 for grid BUY (no realization until paired sell)
            double feeUsdt
    ) {}

    public record SimulationResult(
            String symbol,
            double currentPrice,
            double targetPrice,
            double startUsdt, double startBtc, double startTotal,
            double endUsdt, double endBtc, double endTotal,
            double realizedPnl, double totalFees, double netChange,
            int eventCount,
            List<TriggerEvent> events,
            String report
    ) {}

    /**
     * @param symbol e.g. "BTCUSDT"
     * @param targetPrice the price we want to project to
     * @param startUsdt   current USDT balance (Trading account)
     * @param startBtc    current BTC balance (Trading account)
     */
    public SimulationResult simulate(String symbol, double targetPrice,
                                     double startUsdt, double startBtc) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol required");
        }
        if (targetPrice <= 0) {
            throw new IllegalArgumentException("targetPrice must be positive");
        }

        double currentPrice = okxTradingService.getLastPrice(symbol).doubleValue();
        if (currentPrice <= 0) {
            throw new IllegalStateException("Failed to fetch current price for " + symbol);
        }

        boolean priceUp = targetPrice > currentPrice;
        List<TriggerEvent> events = new ArrayList<>();

        // ── 1. Collect OCO trigger events ────────────────────────────────
        List<BtLiveSignal> openOcoPositions = liveSignalRepo
                .findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull()
                .stream()
                .filter(p -> symbol.equals(p.getSymbol()))
                .toList();

        for (BtLiveSignal pos : openOcoPositions) {
            BigDecimal entryBd = pos.getActualEntryPrice() != null
                    ? pos.getActualEntryPrice() : pos.getEntryPrice();
            if (entryBd == null || pos.getSuggestedTp() == null || pos.getSuggestedSl() == null) {
                continue;
            }
            double entry = entryBd.doubleValue();
            double tp = pos.getSuggestedTp().doubleValue();
            double sl = pos.getSuggestedSl().doubleValue();
            double qty = (pos.getOcoQty() != null ? pos.getOcoQty()
                    : pos.getTradedQty()).doubleValue();

            if (priceUp && tp <= targetPrice && tp >= currentPrice) {
                double cash = tp * qty;
                double fee = cash * TAKER_FEE_RATE;
                double pnl = (tp - entry) * qty - fee;
                events.add(new TriggerEvent(EventType.OCO_TP, tp,
                        String.format("Position #%d OCO TP triggered (entry $%.2f → tp $%.2f)",
                                pos.getId(), entry, tp),
                        qty, cash - fee, -qty, pnl, fee));
            } else if (!priceUp && sl >= targetPrice && sl <= currentPrice) {
                double cash = sl * qty;
                double fee = cash * TAKER_FEE_RATE;
                double pnl = (sl - entry) * qty - fee;
                events.add(new TriggerEvent(EventType.OCO_SL, sl,
                        String.format("Position #%d OCO SL triggered (entry $%.2f → sl $%.2f)",
                                pos.getId(), entry, sl),
                        qty, cash - fee, -qty, pnl, fee));
            }
        }

        // ── 2. Sort events by trigger price (ascending if up, descending if down)
        events.sort(priceUp
                ? Comparator.comparingDouble(TriggerEvent::triggerPrice)
                : Comparator.comparingDouble(TriggerEvent::triggerPrice).reversed());

        // ── 3. Walk events, tally final state ────────────────────────────
        double usdt = startUsdt;
        double btc = startBtc;
        double realized = 0;
        double fees = 0;
        for (TriggerEvent e : events) {
            usdt += e.cashDelta();
            btc += e.btcDelta();
            realized += e.realizedPnl();
            fees += e.feeUsdt();
        }

        double startTotal = startUsdt + startBtc * currentPrice;
        double endTotal = usdt + btc * targetPrice;
        double netChange = endTotal - startTotal;

        String report = formatReport(symbol, currentPrice, targetPrice,
                startUsdt, startBtc, startTotal,
                usdt, btc, endTotal,
                realized, fees, netChange, events);

        return new SimulationResult(symbol, currentPrice, targetPrice,
                startUsdt, startBtc, startTotal,
                usdt, btc, endTotal,
                realized, fees, netChange,
                events.size(), events, report);
    }

    private String formatReport(String symbol, double currentPrice, double targetPrice,
                                double startUsdt, double startBtc, double startTotal,
                                double endUsdt, double endBtc, double endTotal,
                                double realized, double fees, double netChange,
                                List<TriggerEvent> events) {
        StringBuilder sb = new StringBuilder();
        double pctMove = (targetPrice - currentPrice) / currentPrice * 100;
        sb.append("=== Price Scenario Simulation ===\n");
        sb.append(String.format("Symbol: %s%n", symbol));
        sb.append(String.format("Current: $%.2f → Target: $%.2f (%+.2f%%)%n",
                currentPrice, targetPrice, pctMove));
        sb.append(String.format("Path: LINEAR (monotonic %s)%n",
                targetPrice > currentPrice ? "up" : "down"));

        sb.append("\n📊 Pre-simulation state:\n");
        sb.append(String.format("  USDT:  $%.2f%n", startUsdt));
        sb.append(String.format("  BTC:   %.8f ($%.2f)%n", startBtc, startBtc * currentPrice));
        sb.append(String.format("  Total: $%.2f%n", startTotal));

        sb.append(String.format("%n⏱ Trigger sequence (%d events):%n", events.size()));
        if (events.isEmpty()) {
            sb.append("  (none — no OCO level reached between current and target)\n");
        } else {
            int i = 1;
            double runningUsdt = startUsdt;
            double runningBtc = startBtc;
            for (TriggerEvent e : events) {
                runningUsdt += e.cashDelta();
                runningBtc += e.btcDelta();
                sb.append(String.format("%n[%d] $%.2f — %s%n", i++, e.triggerPrice(), e.description()));
                sb.append(String.format("    Qty: %.8f BTC  |  Cash Δ: %+.2f  |  Fee: $%.4f%n",
                        e.qtyBtc(), e.cashDelta(), e.feeUsdt()));
                if (e.realizedPnl() != 0) {
                    sb.append(String.format("    Realized PnL: %+.2f USDT%n", e.realizedPnl()));
                }
                sb.append(String.format("    Account: USDT $%.2f, BTC %.8f%n",
                        runningUsdt, runningBtc));
            }
        }

        sb.append("\n📈 Post-simulation state:\n");
        sb.append(String.format("  USDT:  $%.2f%n", endUsdt));
        sb.append(String.format("  BTC:   %.8f ($%.2f)%n", endBtc, endBtc * targetPrice));
        sb.append(String.format("  Total: $%.2f%n", endTotal));

        sb.append("\n💰 Net change:\n");
        sb.append(String.format("  Realized PnL: %+.2f USDT%n", realized));
        sb.append(String.format("  Total fees:   $%.4f%n", fees));
        sb.append(String.format("  Net Δ vs current: %+.2f USDT%n", netChange));

        sb.append("\n⚠️ Disclaimer: LINEAR path assumes no oscillation and excludes strategy entries.\n");
        return sb.toString();
    }

    static double round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
