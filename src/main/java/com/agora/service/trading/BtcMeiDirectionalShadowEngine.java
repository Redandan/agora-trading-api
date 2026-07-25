package com.agora.service.trading;

import com.agora.model.MdKline;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ADVERSE_SLIPPAGE_RATE_PER_SIDE;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.BASE_NOTIONAL_USDT;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.EMA_PERIOD_HOURS;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ENTROPY_24H;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ENTROPY_24H_WEIGHT;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ENTROPY_48H;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ENTROPY_48H_WEIGHT;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ENTROPY_72H;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ENTROPY_72H_WEIGHT;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ENTROPY_BINS;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.ENTRY_ENTROPY_THRESHOLD;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.FEE_RATE_PER_SIDE;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.INTERVAL;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.MAX_OPEN_COST_USDT;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.MIN_REALIZED_NET_PROFIT;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.MOMENTUM_LOOKBACK_HOURS;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.NET_PROFIT_TRIGGER;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.REQUIRED_CLOSE_POINTS;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.SOURCE;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.STATE_SCHEMA_VERSION;
import static com.agora.service.trading.BtcMeiDirectionalShadowPolicy.SYMBOL;

/**
 * Pure, deterministic SHADOW engine for the source-pinned MEI directional candidate.
 *
 * <p>The engine has no repository, exchange, OCO, Grid, notification, or clock
 * dependency. It consumes one proven closed OKX hourly bar at a time and keeps
 * independent virtual lots. Entries and exits are evaluated at bar close and
 * filled at the next hourly open with adverse cost assumptions.</p>
 */
@Component
@RequiredArgsConstructor
public final class BtcMeiDirectionalShadowEngine {

    private static final int QUANTITY_SCALE = 12;
    private static final int MONEY_SCALE = 8;
    private static final int RETURN_SCALE = 8;
    private static final double EPSILON = 1e-12;

    private final ObjectMapper objectMapper;

    public State initialState() {
        return new State(
                STATE_SCHEMA_VERSION,
                null,
                List.of(),
                null,
                false,
                null,
                BigDecimal.ZERO,
                "",
                List.of(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                0,
                0,
                0,
                0,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                MAX_OPEN_COST_USDT,
                BigDecimal.ZERO);
    }

    public StepResult step(State previous, MdKline bar) {
        State state = previous == null ? initialState() : previous;
        validate(state, bar);

        List<RuntimeEvent> events = new ArrayList<>();
        List<Lot> lots = new ArrayList<>();
        BigDecimal totalBuyNotional = state.totalBuyNotionalUsdt();
        BigDecimal totalSellProceeds = state.totalSellProceedsUsdt();
        BigDecimal realizedPnl = state.realizedPnlUsdt();
        BigDecimal totalFees = state.totalFeesUsdt();
        int buyFillCount = state.buyFillCount();
        int sellFillCount = state.sellFillCount();
        int winningExitCount = state.winningExitCount();
        int deferredExitCount = state.deferredExitCount();

        for (Lot lot : state.openLots()) {
            if (lot.exitQueuedAtBarOpenTime() == null) {
                lots.add(lot);
                continue;
            }
            BigDecimal sellPrice = adverseSellPrice(bar.getOpenPrice());
            BigDecimal grossProceeds = lot.quantity().multiply(sellPrice)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal sellFee = grossProceeds.multiply(FEE_RATE_PER_SIDE)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal netProceeds = grossProceeds.subtract(sellFee);
            BigDecimal lotPnl = netProceeds.subtract(lot.grossBuyNotionalUsdt())
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal realizedReturn = returnOnCost(
                    netProceeds, lot.grossBuyNotionalUsdt());
            if (realizedReturn.compareTo(MIN_REALIZED_NET_PROFIT) < 0) {
                deferredExitCount++;
                lots.add(lot.withExitQueuedAt(null));
                events.add(new RuntimeEvent(
                        "VIRTUAL_EXIT_DEFERRED",
                        bar.getOpenTime(),
                        lot.signalBarOpenTime(),
                        lot.lotId(),
                        BigDecimal.ZERO,
                        sellPrice,
                        lot.quantity(),
                        BigDecimal.ZERO,
                        lotPnl,
                        realizedReturn,
                        "NEXT_OPEN_BELOW_NET_PROFIT_FLOOR"));
                continue;
            }
            totalSellProceeds = totalSellProceeds.add(netProceeds);
            realizedPnl = realizedPnl.add(lotPnl);
            totalFees = totalFees.add(sellFee);
            sellFillCount++;
            if (lotPnl.signum() > 0) winningExitCount++;
            events.add(new RuntimeEvent(
                    "VIRTUAL_SELL_FILL",
                    bar.getOpenTime(),
                    lot.signalBarOpenTime(),
                    lot.lotId(),
                    lot.grossBuyNotionalUsdt(),
                    sellPrice,
                    lot.quantity(),
                    sellFee,
                    lotPnl,
                    realizedReturn,
                    "NEXT_1H_OPEN_NET_PROFIT_CONFIRMED"));
        }

        if (positive(state.pendingBuyNotionalUsdt())) {
            BigDecimal buyPrice = adverseBuyPrice(bar.getOpenPrice());
            BigDecimal buyFee = state.pendingBuyNotionalUsdt().multiply(FEE_RATE_PER_SIDE)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal netBuyNotional = state.pendingBuyNotionalUsdt().subtract(buyFee);
            BigDecimal fillQuantity = netBuyNotional.divide(
                    buyPrice, QUANTITY_SCALE, RoundingMode.DOWN);
            if (!positive(fillQuantity)) {
                throw new DataQualityException("BUY_FILL_QUANTITY_NOT_POSITIVE");
            }
            String lotId = lotId(state.pendingSignalBarOpenTime());
            lots.add(new Lot(
                    lotId,
                    state.pendingSignalBarOpenTime(),
                    bar.getOpenTime(),
                    state.pendingBuyNotionalUsdt(),
                    buyPrice,
                    fillQuantity,
                    state.pendingReason(),
                    null));
            totalBuyNotional = totalBuyNotional.add(state.pendingBuyNotionalUsdt());
            totalFees = totalFees.add(buyFee);
            buyFillCount++;
            events.add(new RuntimeEvent(
                    "VIRTUAL_BUY_FILL",
                    bar.getOpenTime(),
                    state.pendingSignalBarOpenTime(),
                    lotId,
                    state.pendingBuyNotionalUsdt(),
                    buyPrice,
                    fillQuantity,
                    buyFee,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "NEXT_1H_OPEN"));
        }

        List<ClosePoint> closeHistory = appendClose(
                state.closeHistory(), bar.getOpenTime(), bar.getClosePrice());
        BigDecimal ema20 = nextEma(state.ema20(), bar.getClosePrice());
        SignalSnapshot signal = signal(closeHistory, ema20);

        List<Lot> evaluatedLots = new ArrayList<>();
        for (Lot lot : lots) {
            if (lot.exitQueuedAtBarOpenTime() != null) {
                evaluatedLots.add(lot);
                continue;
            }
            BigDecimal netProceeds = estimatedNetSellProceeds(
                    lot.quantity(), bar.getClosePrice());
            BigDecimal netReturn = returnOnCost(
                    netProceeds, lot.grossBuyNotionalUsdt());
            if (netReturn.compareTo(NET_PROFIT_TRIGGER) >= 0) {
                evaluatedLots.add(lot.withExitQueuedAt(bar.getOpenTime()));
                events.add(new RuntimeEvent(
                        "VIRTUAL_EXIT_QUEUED",
                        bar.getOpenTime(),
                        lot.signalBarOpenTime(),
                        lot.lotId(),
                        lot.grossBuyNotionalUsdt(),
                        adverseSellPrice(bar.getClosePrice()),
                        lot.quantity(),
                        BigDecimal.ZERO,
                        netProceeds.subtract(lot.grossBuyNotionalUsdt()),
                        netReturn,
                        "CLOSE_NET_RETURN_AT_LEAST_5_PERCENT"));
            } else {
                evaluatedLots.add(lot);
            }
        }

        LocalDateTime pendingSignalTime = null;
        BigDecimal pendingBuyNotional = BigDecimal.ZERO;
        String pendingReason = "";
        int queuedEntryCount = state.queuedEntryCount();
        int blockedEntryCount = state.blockedEntryCount();
        if (signal.eligible() && !state.entryConditionMetLastBar()) {
            BigDecimal openCost = evaluatedLots.stream()
                    .map(Lot::grossBuyNotionalUsdt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (openCost.add(BASE_NOTIONAL_USDT).compareTo(MAX_OPEN_COST_USDT) > 0) {
                blockedEntryCount++;
                events.add(new RuntimeEvent(
                        "VIRTUAL_ENTRY_BLOCKED",
                        bar.getOpenTime(),
                        bar.getOpenTime(),
                        lotId(bar.getOpenTime()),
                        BASE_NOTIONAL_USDT,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "MAX_OPEN_COST_EXCEEDED"));
            } else {
                pendingSignalTime = bar.getOpenTime();
                pendingBuyNotional = BASE_NOTIONAL_USDT;
                pendingReason = signal.reason();
                queuedEntryCount++;
                events.add(new RuntimeEvent(
                        "VIRTUAL_ENTRY_QUEUED",
                        bar.getOpenTime(),
                        bar.getOpenTime(),
                        lotId(bar.getOpenTime()),
                        BASE_NOTIONAL_USDT,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        signal.reason()));
            }
        }

        Metrics metrics = metrics(evaluatedLots, bar.getClosePrice());
        BigDecimal totalPnl = realizedPnl.add(metrics.unrealizedPnlUsdt())
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal openLoss = metrics.openReturn().signum() < 0
                ? metrics.openReturn().abs()
                : BigDecimal.ZERO;
        BigDecimal virtualEquity = MAX_OPEN_COST_USDT.add(totalPnl)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal peakVirtualEquity = state.peakVirtualEquityUsdt().max(virtualEquity);
        BigDecimal virtualDrawdown = positive(peakVirtualEquity)
                ? peakVirtualEquity.subtract(virtualEquity)
                        .divide(peakVirtualEquity, RETURN_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        State next = new State(
                STATE_SCHEMA_VERSION,
                bar.getOpenTime(),
                closeHistory,
                ema20,
                signal.eligible(),
                pendingSignalTime,
                pendingBuyNotional,
                pendingReason,
                List.copyOf(evaluatedLots),
                totalBuyNotional.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                totalSellProceeds.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                realizedPnl.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                totalFees.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                metrics.openCostUsdt(),
                metrics.inventoryQty(),
                metrics.inventoryValueUsdt(),
                metrics.unrealizedPnlUsdt(),
                totalPnl,
                buyFillCount,
                sellFillCount,
                winningExitCount,
                deferredExitCount,
                queuedEntryCount,
                blockedEntryCount,
                state.maxOpenCostUsdt().max(metrics.openCostUsdt()),
                state.maxOpenCapitalLossPct().max(openLoss),
                peakVirtualEquity,
                state.maxVirtualDrawdownPct().max(virtualDrawdown));
        return new StepResult(next, signal, List.copyOf(events));
    }

    public String stateSha256(State state) {
        try {
            byte[] json = objectMapper.writeValueAsString(state)
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash MEI directional state", e);
        }
    }

    public ReplayResult replay(List<MdKline> inputBars) {
        if (inputBars == null || inputBars.isEmpty()) {
            throw new DataQualityException("NO_BARS");
        }
        List<MdKline> bars = new ArrayList<>(inputBars);
        bars.sort(Comparator.comparing(MdKline::getOpenTime));
        State state = initialState();
        List<RuntimeEvent> events = new ArrayList<>();
        SignalSnapshot lastSignal = null;
        for (MdKline bar : bars) {
            StepResult step = step(state, bar);
            state = step.state();
            lastSignal = step.signal();
            events.addAll(step.events());
        }
        return new ReplayResult(
                state,
                lastSignal,
                List.copyOf(events),
                bars.get(0).getOpenTime(),
                bars.get(bars.size() - 1).getOpenTime(),
                bars.size());
    }

    private SignalSnapshot signal(List<ClosePoint> history, BigDecimal ema20) {
        if (history.size() < REQUIRED_CLOSE_POINTS || ema20 == null) {
            return new SignalSnapshot(
                    false, 0, 0, 0, 0, BigDecimal.ZERO, ema20,
                    false, "INSUFFICIENT_CLOSED_HISTORY");
        }
        double entropy24 = entropy(history, ENTROPY_24H);
        double entropy48 = entropy(history, ENTROPY_48H);
        double entropy72 = entropy(history, ENTROPY_72H);
        double score = Math.min(100.0,
                entropy24 * ENTROPY_24H_WEIGHT
                        + entropy48 * ENTROPY_48H_WEIGHT
                        + entropy72 * ENTROPY_72H_WEIGHT);
        BigDecimal close = history.get(history.size() - 1).closePrice();
        BigDecimal close24hAgo = history.get(history.size() - 1 - MOMENTUM_LOOKBACK_HOURS)
                .closePrice();
        BigDecimal momentum = close.subtract(close24hAgo)
                .divide(close24hAgo, RETURN_SCALE, RoundingMode.HALF_UP);
        boolean entropyPassed = score >= ENTRY_ENTROPY_THRESHOLD;
        boolean momentumPassed = momentum.signum() > 0;
        boolean emaPassed = close.compareTo(ema20) > 0;
        boolean eligible = entropyPassed && momentumPassed && emaPassed;
        String reason = String.format(
                Locale.ROOT,
                "MEI=%.4f threshold=%.1f momentum24h=%s close=%s ema20=%s",
                score,
                ENTRY_ENTROPY_THRESHOLD,
                momentum.toPlainString(),
                close.toPlainString(),
                ema20.toPlainString());
        return new SignalSnapshot(
                true,
                score,
                entropy24,
                entropy48,
                entropy72,
                momentum,
                ema20,
                eligible,
                reason);
    }

    private double entropy(List<ClosePoint> history, int windowHours) {
        int start = history.size() - (windowHours + 1);
        double[] returns = new double[windowHours];
        for (int i = 0; i < windowHours; i++) {
            double previous = history.get(start + i).closePrice().doubleValue();
            double current = history.get(start + i + 1).closePrice().doubleValue();
            returns[i] = previous > 0 ? (current - previous) / previous : 0;
        }
        double mean = 0;
        for (double value : returns) mean += value;
        mean /= returns.length;
        double variance = 0;
        for (double value : returns) {
            double delta = value - mean;
            variance += delta * delta;
        }
        double standardDeviation = Math.sqrt(variance / returns.length);
        double low = mean - 3 * standardDeviation;
        double high = mean + 3 * standardDeviation;
        if (high - low < EPSILON) return 0;

        int[] counts = new int[ENTROPY_BINS];
        double width = (high - low) / ENTROPY_BINS;
        for (double value : returns) {
            int bin = (int) Math.floor((value - low) / width);
            counts[Math.max(0, Math.min(ENTROPY_BINS - 1, bin))]++;
        }
        double entropy = 0;
        for (int count : counts) {
            if (count == 0) continue;
            double probability = (double) count / returns.length;
            entropy -= probability * Math.log(probability);
        }
        return Math.min(entropy / Math.log(ENTROPY_BINS) * 100.0, 100.0);
    }

    private List<ClosePoint> appendClose(List<ClosePoint> previous,
                                         LocalDateTime openTime,
                                         BigDecimal closePrice) {
        List<ClosePoint> next = new ArrayList<>(previous);
        next.add(new ClosePoint(openTime, closePrice));
        while (next.size() > REQUIRED_CLOSE_POINTS) next.remove(0);
        return List.copyOf(next);
    }

    private BigDecimal nextEma(BigDecimal previousEma, BigDecimal closePrice) {
        if (previousEma == null) return closePrice.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal alpha = BigDecimal.valueOf(2)
                .divide(BigDecimal.valueOf(EMA_PERIOD_HOURS + 1L), 16, RoundingMode.HALF_UP);
        return closePrice.multiply(alpha)
                .add(previousEma.multiply(BigDecimal.ONE.subtract(alpha)))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private Metrics metrics(List<Lot> lots, BigDecimal closePrice) {
        BigDecimal openCost = BigDecimal.ZERO;
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal value = BigDecimal.ZERO;
        for (Lot lot : lots) {
            openCost = openCost.add(lot.grossBuyNotionalUsdt());
            quantity = quantity.add(lot.quantity());
            value = value.add(estimatedNetSellProceeds(lot.quantity(), closePrice));
        }
        BigDecimal unrealized = value.subtract(openCost)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal openReturn = positive(openCost)
                ? unrealized.divide(openCost, RETURN_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new Metrics(
                openCost.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                quantity.setScale(QUANTITY_SCALE, RoundingMode.DOWN),
                value.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                unrealized,
                openReturn);
    }

    private BigDecimal estimatedNetSellProceeds(BigDecimal quantity, BigDecimal referencePrice) {
        BigDecimal gross = quantity.multiply(adverseSellPrice(referencePrice))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return gross.subtract(gross.multiply(FEE_RATE_PER_SIDE)
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal adverseBuyPrice(BigDecimal price) {
        return price.multiply(BigDecimal.ONE.add(ADVERSE_SLIPPAGE_RATE_PER_SIDE))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal adverseSellPrice(BigDecimal price) {
        return price.multiply(BigDecimal.ONE.subtract(ADVERSE_SLIPPAGE_RATE_PER_SIDE))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal returnOnCost(BigDecimal value, BigDecimal cost) {
        return positive(cost)
                ? value.subtract(cost).divide(cost, RETURN_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }

    private void validate(State state, MdKline bar) {
        if (!STATE_SCHEMA_VERSION.equals(state.schemaVersion())) {
            throw new DataQualityException("STATE_SCHEMA_VERSION_INVALID");
        }
        if (bar == null || bar.getOpenTime() == null || bar.getCloseTime() == null) {
            throw new DataQualityException("BAR_TIME_MISSING");
        }
        if (!bar.getOpenTime().plusHours(1).equals(bar.getCloseTime())) {
            throw new DataQualityException("BAR_NOT_EXACT_1H");
        }
        if (!SYMBOL.equals(normalizeSymbol(bar.getSymbol()))
                || !INTERVAL.equalsIgnoreCase(bar.getIntervalCode())
                || !SOURCE.equalsIgnoreCase(bar.getSource())) {
            throw new DataQualityException("BAR_SCOPE_MISMATCH");
        }
        if (!positive(bar.getOpenPrice()) || !positive(bar.getClosePrice())) {
            throw new DataQualityException("BAR_PRICE_INVALID");
        }
        if (state.lastProcessedBarOpenTime() != null
                && !state.lastProcessedBarOpenTime().plusHours(1).equals(bar.getOpenTime())) {
            throw new DataQualityException("HOURLY_BAR_SEQUENCE_GAP");
        }
        if (state.closeHistory() == null || state.openLots() == null
                || state.pendingBuyNotionalUsdt() == null
                || state.maxOpenCostUsdt() == null
                || state.maxOpenCapitalLossPct() == null
                || state.peakVirtualEquityUsdt() == null
                || state.maxVirtualDrawdownPct() == null) {
            throw new DataQualityException("STATE_FIELD_MISSING");
        }
        if (state.closeHistory().size() > REQUIRED_CLOSE_POINTS) {
            throw new DataQualityException("STATE_HISTORY_TOO_LARGE");
        }
        if (!state.closeHistory().isEmpty()
                && !state.closeHistory().get(state.closeHistory().size() - 1).openTime()
                .equals(state.lastProcessedBarOpenTime())) {
            throw new DataQualityException("STATE_HISTORY_TIME_MISMATCH");
        }
        if (positive(state.pendingBuyNotionalUsdt())
                && (state.pendingSignalBarOpenTime() == null
                || !state.pendingSignalBarOpenTime().isBefore(bar.getOpenTime()))) {
            throw new DataQualityException("PENDING_BUY_TIME_INVALID");
        }
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.toUpperCase(Locale.ROOT)
                .replace("-", "").replace("/", "").replace("_", "");
    }

    private String lotId(LocalDateTime signalBarOpenTime) {
        return signalBarOpenTime == null
                ? ""
                : "MEI-DIR-V1-" + signalBarOpenTime;
    }

    public record ClosePoint(LocalDateTime openTime, BigDecimal closePrice) {
    }

    public record Lot(
            String lotId,
            LocalDateTime signalBarOpenTime,
            LocalDateTime buyFillBarOpenTime,
            BigDecimal grossBuyNotionalUsdt,
            BigDecimal buyFillPrice,
            BigDecimal quantity,
            String entryReason,
            LocalDateTime exitQueuedAtBarOpenTime
    ) {
        public Lot withExitQueuedAt(LocalDateTime queuedAt) {
            return new Lot(
                    lotId,
                    signalBarOpenTime,
                    buyFillBarOpenTime,
                    grossBuyNotionalUsdt,
                    buyFillPrice,
                    quantity,
                    entryReason,
                    queuedAt);
        }
    }

    public record State(
            String schemaVersion,
            LocalDateTime lastProcessedBarOpenTime,
            List<ClosePoint> closeHistory,
            BigDecimal ema20,
            boolean entryConditionMetLastBar,
            LocalDateTime pendingSignalBarOpenTime,
            BigDecimal pendingBuyNotionalUsdt,
            String pendingReason,
            List<Lot> openLots,
            BigDecimal totalBuyNotionalUsdt,
            BigDecimal totalSellProceedsUsdt,
            BigDecimal realizedPnlUsdt,
            BigDecimal totalFeesUsdt,
            BigDecimal openCostUsdt,
            BigDecimal inventoryQty,
            BigDecimal inventoryValueUsdt,
            BigDecimal unrealizedPnlUsdt,
            BigDecimal totalPnlUsdt,
            int buyFillCount,
            int sellFillCount,
            int winningExitCount,
            int deferredExitCount,
            int queuedEntryCount,
            int blockedEntryCount,
            BigDecimal maxOpenCostUsdt,
            BigDecimal maxOpenCapitalLossPct,
            BigDecimal peakVirtualEquityUsdt,
            BigDecimal maxVirtualDrawdownPct
    ) {
        public State {
            closeHistory = closeHistory == null ? List.of() : List.copyOf(closeHistory);
            openLots = openLots == null ? List.of() : List.copyOf(openLots);
            pendingReason = pendingReason == null ? "" : pendingReason;
        }
    }

    public record SignalSnapshot(
            boolean ready,
            double score,
            double entropy24h,
            double entropy48h,
            double entropy72h,
            BigDecimal momentum24h,
            BigDecimal ema20,
            boolean eligible,
            String reason
    ) {
    }

    public record RuntimeEvent(
            String eventType,
            LocalDateTime eventBarOpenTime,
            LocalDateTime signalBarOpenTime,
            String lotId,
            BigDecimal notionalUsdt,
            BigDecimal fillPrice,
            BigDecimal fillQty,
            BigDecimal feeUsdt,
            BigDecimal netPnlUsdt,
            BigDecimal netReturn,
            String reason
    ) {
    }

    public record StepResult(
            State state,
            SignalSnapshot signal,
            List<RuntimeEvent> events
    ) {
    }

    public record ReplayResult(
            State state,
            SignalSnapshot lastSignal,
            List<RuntimeEvent> events,
            LocalDateTime firstBarOpenTime,
            LocalDateTime lastBarOpenTime,
            int barCount
    ) {
    }

    private record Metrics(
            BigDecimal openCostUsdt,
            BigDecimal inventoryQty,
            BigDecimal inventoryValueUsdt,
            BigDecimal unrealizedPnlUsdt,
            BigDecimal openReturn
    ) {
    }

    public static final class DataQualityException extends IllegalStateException {
        public DataQualityException(String message) {
            super(message);
        }
    }
}
