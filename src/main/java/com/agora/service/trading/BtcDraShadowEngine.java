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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static com.agora.service.trading.BtcDraShadowPolicy.ADVERSE_SLIPPAGE_RATE_PER_SIDE;
import static com.agora.service.trading.BtcDraShadowPolicy.ARM_EXPIRY_DAYS;
import static com.agora.service.trading.BtcDraShadowPolicy.BASE_NOTIONAL_USDT;
import static com.agora.service.trading.BtcDraShadowPolicy.DAILY_EMA_PERIOD_DAYS;
import static com.agora.service.trading.BtcDraShadowPolicy.EMA_SLOPE_LOOKBACK_DAYS;
import static com.agora.service.trading.BtcDraShadowPolicy.ENTRY_COOLDOWN_DAYS;
import static com.agora.service.trading.BtcDraShadowPolicy.FEE_RATE_PER_SIDE;
import static com.agora.service.trading.BtcDraShadowPolicy.INTERVAL;
import static com.agora.service.trading.BtcDraShadowPolicy.MAX_OPEN_COST_USDT;
import static com.agora.service.trading.BtcDraShadowPolicy.MIN_REALIZED_NET_PROFIT;
import static com.agora.service.trading.BtcDraShadowPolicy.MOMENTUM_LOOKBACK_HOURS;
import static com.agora.service.trading.BtcDraShadowPolicy.NET_PROFIT_TRIGGER;
import static com.agora.service.trading.BtcDraShadowPolicy.REQUIRED_CLOSE_POINTS;
import static com.agora.service.trading.BtcDraShadowPolicy.REQUIRED_DAILY_EMA_POINTS;
import static com.agora.service.trading.BtcDraShadowPolicy.SOURCE;
import static com.agora.service.trading.BtcDraShadowPolicy.STATE_SCHEMA_VERSION;
import static com.agora.service.trading.BtcDraShadowPolicy.SYMBOL;

/**
 * Pure deterministic SHADOW engine for BTC Daily Reversal Accumulation V1.
 *
 * <p>The engine has no repository, exchange, OCO, Grid, notification, or
 * clock dependency. It consumes contiguous closed OKX hourly bars. Entry is
 * confirmed only by the UTC daily close: close above daily EMA20, EMA20 above
 * its value five daily closes earlier, and positive 24-hour momentum. MEI and
 * drawdown gates are intentionally absent.</p>
 */
@Component
@RequiredArgsConstructor
public final class BtcDraShadowEngine {

    private static final int QUANTITY_SCALE = 12;
    private static final int MONEY_SCALE = 8;
    private static final int RETURN_SCALE = 8;

    private final ObjectMapper objectMapper;

    public State initialState() {
        return new State(
                STATE_SCHEMA_VERSION,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
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
                0,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                MAX_OPEN_COST_USDT,
                BigDecimal.ZERO);
    }

    /**
     * Advances indicators during bootstrap without creating historical virtual
     * positions or arming a historical entry.
     */
    public StepResult warmup(State previous, MdKline bar) {
        return advance(previous, bar, false);
    }

    /** Advances one genuine closed hourly bar with SHADOW trading enabled. */
    public StepResult step(State previous, MdKline bar) {
        return advance(previous, bar, true);
    }

    private StepResult advance(State previous, MdKline bar, boolean tradingEnabled) {
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
            if (!tradingEnabled || lot.exitQueuedAtBarOpenTime() == null) {
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
            BigDecimal realizedReturn =
                    returnOnCost(netProceeds, lot.grossBuyNotionalUsdt());
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

        LocalDateTime pendingSignalTime = state.pendingSignalBarOpenTime();
        BigDecimal pendingBuyNotional = state.pendingBuyNotionalUsdt();
        String pendingReason = state.pendingReason();
        if (tradingEnabled && positive(pendingBuyNotional)) {
            BigDecimal buyPrice = adverseBuyPrice(bar.getOpenPrice());
            BigDecimal buyFee = pendingBuyNotional.multiply(FEE_RATE_PER_SIDE)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal netBuyNotional = pendingBuyNotional.subtract(buyFee);
            BigDecimal fillQuantity = netBuyNotional.divide(
                    buyPrice, QUANTITY_SCALE, RoundingMode.DOWN);
            if (!positive(fillQuantity)) {
                throw new DataQualityException("BUY_FILL_QUANTITY_NOT_POSITIVE");
            }
            String lotId = lotId(pendingSignalTime);
            lots.add(new Lot(
                    lotId,
                    pendingSignalTime,
                    bar.getOpenTime(),
                    pendingBuyNotional,
                    buyPrice,
                    fillQuantity,
                    pendingReason,
                    null));
            totalBuyNotional = totalBuyNotional.add(pendingBuyNotional);
            totalFees = totalFees.add(buyFee);
            buyFillCount++;
            events.add(new RuntimeEvent(
                    "VIRTUAL_BUY_FILL",
                    bar.getOpenTime(),
                    pendingSignalTime,
                    lotId,
                    pendingBuyNotional,
                    buyPrice,
                    fillQuantity,
                    buyFee,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "NEXT_1H_OPEN"));
            pendingSignalTime = null;
            pendingBuyNotional = BigDecimal.ZERO;
            pendingReason = "";
        }

        List<ClosePoint> closeHistory = appendClose(
                state.closeHistory(), bar.getOpenTime(), bar.getClosePrice());
        DailyIndicators daily = updateDailyIndicators(
                state.dailyEmaHistory(), state.dailyEma20(), bar);
        SignalSnapshot signal = signal(
                closeHistory,
                daily.history(),
                daily.ema20(),
                state.armedAt(),
                state.armExpiresAt(),
                bar);

        if (tradingEnabled) {
            List<Lot> evaluatedLots = new ArrayList<>();
            for (Lot lot : lots) {
                if (lot.exitQueuedAtBarOpenTime() != null) {
                    evaluatedLots.add(lot);
                    continue;
                }
                BigDecimal netProceeds = estimatedNetSellProceeds(
                        lot.quantity(), bar.getClosePrice());
                BigDecimal netReturn =
                        returnOnCost(netProceeds, lot.grossBuyNotionalUsdt());
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
            lots = evaluatedLots;
        }

        LocalDateTime armedAt = tradingEnabled ? state.armedAt() : null;
        LocalDateTime armExpiresAt = tradingEnabled ? state.armExpiresAt() : null;
        LocalDateTime lastEntrySignalAt =
                tradingEnabled ? state.lastEntrySignalBarOpenTime() : null;
        int queuedEntryCount = state.queuedEntryCount();
        int blockedEntryCount = state.blockedEntryCount();
        int armCount = state.armCount();
        int expiredArmCount = state.expiredArmCount();

        if (tradingEnabled && armedAt != null
                && !bar.getOpenTime().isBefore(armExpiresAt)) {
            events.add(new RuntimeEvent(
                    "DRA_ARM_EXPIRED",
                    bar.getOpenTime(),
                    armedAt,
                    "",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "NO_DAILY_REVERSAL_CONFIRMATION_WITHIN_30_DAYS"));
            armedAt = null;
            armExpiresAt = null;
            expiredArmCount++;
        }

        boolean confirmationEligible = tradingEnabled
                && armedAt != null
                && bar.getOpenTime().isAfter(armedAt)
                && signal.dailyReversalConfirmed();
        if (confirmationEligible) {
            BigDecimal openCost = lots.stream()
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
                lastEntrySignalAt = bar.getOpenTime();
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
            armedAt = null;
            armExpiresAt = null;
        }

        boolean cooldownPassed = lastEntrySignalAt == null
                || !bar.getOpenTime().isBefore(
                        lastEntrySignalAt.plusDays(ENTRY_COOLDOWN_DAYS));
        if (tradingEnabled && armedAt == null && cooldownPassed) {
            armedAt = bar.getOpenTime();
            armExpiresAt = bar.getOpenTime().plusDays(ARM_EXPIRY_DAYS);
            armCount++;
            events.add(new RuntimeEvent(
                    "DRA_ARMED",
                    bar.getOpenTime(),
                    bar.getOpenTime(),
                    "",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "WAITING_FOR_DAILY_EMA20_REVERSAL_CONFIRMATION"));
        }

        Metrics metrics = metrics(lots, bar.getClosePrice());
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
                daily.history(),
                daily.ema20(),
                armedAt,
                armExpiresAt,
                lastEntrySignalAt,
                pendingSignalTime,
                pendingBuyNotional,
                pendingReason,
                List.copyOf(lots),
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
                armCount,
                expiredArmCount,
                state.maxOpenCostUsdt().max(metrics.openCostUsdt()),
                state.maxOpenCapitalLossPct().max(openLoss),
                peakVirtualEquity,
                state.maxVirtualDrawdownPct().max(virtualDrawdown));
        return new StepResult(
                next,
                signal.withEntryEligible(confirmationEligible),
                List.copyOf(events));
    }

    public String stateSha256(State state) {
        return canonicalStateSha256(stateCanonicalJson(state));
    }

    public String stateCanonicalJson(State state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize DRA state", e);
        }
    }

    public String canonicalStateSha256(String canonicalStateJson) {
        if (canonicalStateJson == null || canonicalStateJson.isBlank()) {
            throw new IllegalArgumentException("canonicalStateJson must not be blank");
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonicalStateJson.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash DRA state", e);
        }
    }

    private DailyIndicators updateDailyIndicators(
            List<DailyEmaPoint> previousHistory,
            BigDecimal previousEma,
            MdKline bar) {
        if (bar.getOpenTime().getHour() != 23) {
            return new DailyIndicators(previousHistory, previousEma);
        }
        BigDecimal ema = nextDailyEma(previousEma, bar.getClosePrice());
        List<DailyEmaPoint> history = new ArrayList<>(previousHistory);
        history.add(new DailyEmaPoint(bar.getOpenTime(), ema));
        while (history.size() > REQUIRED_DAILY_EMA_POINTS) history.remove(0);
        return new DailyIndicators(List.copyOf(history), ema);
    }

    private SignalSnapshot signal(
            List<ClosePoint> closeHistory,
            List<DailyEmaPoint> dailyEmaHistory,
            BigDecimal dailyEma20,
            LocalDateTime armedAt,
            LocalDateTime armExpiresAt,
            MdKline bar) {
        boolean dailyDecision = bar.getOpenTime().getHour() == 23;
        if (closeHistory.size() < REQUIRED_CLOSE_POINTS
                || dailyEmaHistory.size() < REQUIRED_DAILY_EMA_POINTS
                || dailyEma20 == null) {
            return new SignalSnapshot(
                    false,
                    dailyDecision,
                    BigDecimal.ZERO,
                    dailyEma20,
                    null,
                    false,
                    false,
                    false,
                    false,
                    armedAt != null,
                    armedAt,
                    armExpiresAt,
                    "INSUFFICIENT_DAILY_REVERSAL_HISTORY");
        }
        BigDecimal close = closeHistory.get(closeHistory.size() - 1).closePrice();
        BigDecimal close24hAgo =
                closeHistory.get(closeHistory.size() - 1 - MOMENTUM_LOOKBACK_HOURS)
                        .closePrice();
        BigDecimal momentum = close.subtract(close24hAgo)
                .divide(close24hAgo, RETURN_SCALE, RoundingMode.HALF_UP);
        BigDecimal emaFiveDaysAgo =
                dailyEmaHistory.get(dailyEmaHistory.size() - 1 - EMA_SLOPE_LOOKBACK_DAYS)
                        .ema20();
        boolean closeAboveEma = close.compareTo(dailyEma20) > 0;
        boolean emaRising = dailyEma20.compareTo(emaFiveDaysAgo) > 0;
        boolean momentumPositive = momentum.signum() > 0;
        boolean confirmed =
                dailyDecision && closeAboveEma && emaRising && momentumPositive;
        String reason = String.format(
                Locale.ROOT,
                "dailyDecision=%s momentum24h=%s close=%s dailyEma20=%s "
                        + "dailyEma20FiveDaysAgo=%s closeAboveEma=%s "
                        + "emaRisingFiveDays=%s",
                dailyDecision,
                momentum.toPlainString(),
                close.toPlainString(),
                dailyEma20.toPlainString(),
                emaFiveDaysAgo.toPlainString(),
                closeAboveEma,
                emaRising);
        return new SignalSnapshot(
                true,
                dailyDecision,
                momentum,
                dailyEma20,
                emaFiveDaysAgo,
                closeAboveEma,
                emaRising,
                momentumPositive,
                confirmed,
                armedAt != null,
                armedAt,
                armExpiresAt,
                reason);
    }

    private List<ClosePoint> appendClose(
            List<ClosePoint> previous,
            LocalDateTime openTime,
            BigDecimal closePrice) {
        List<ClosePoint> next = new ArrayList<>(previous);
        next.add(new ClosePoint(openTime, closePrice));
        while (next.size() > REQUIRED_CLOSE_POINTS) next.remove(0);
        return List.copyOf(next);
    }

    private BigDecimal nextDailyEma(BigDecimal previousEma, BigDecimal closePrice) {
        if (previousEma == null) {
            return closePrice.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal alpha = BigDecimal.valueOf(2)
                .divide(BigDecimal.valueOf(DAILY_EMA_PERIOD_DAYS + 1L),
                        16,
                        RoundingMode.HALF_UP);
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

    private BigDecimal estimatedNetSellProceeds(
            BigDecimal quantity,
            BigDecimal referencePrice) {
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
                && !state.lastProcessedBarOpenTime().plusHours(1)
                .equals(bar.getOpenTime())) {
            throw new DataQualityException("HOURLY_BAR_SEQUENCE_GAP");
        }
        if (state.closeHistory() == null
                || state.dailyEmaHistory() == null
                || state.openLots() == null
                || state.pendingBuyNotionalUsdt() == null
                || state.maxOpenCostUsdt() == null
                || state.maxOpenCapitalLossPct() == null
                || state.peakVirtualEquityUsdt() == null
                || state.maxVirtualDrawdownPct() == null) {
            throw new DataQualityException("STATE_FIELD_MISSING");
        }
        if (state.closeHistory().size() > REQUIRED_CLOSE_POINTS
                || state.dailyEmaHistory().size() > REQUIRED_DAILY_EMA_POINTS) {
            throw new DataQualityException("STATE_HISTORY_TOO_LARGE");
        }
        if (!state.closeHistory().isEmpty()
                && !state.closeHistory().get(state.closeHistory().size() - 1).openTime()
                .equals(state.lastProcessedBarOpenTime())) {
            throw new DataQualityException("STATE_HISTORY_TIME_MISMATCH");
        }
        if (state.armedAt() == null ^ state.armExpiresAt() == null) {
            throw new DataQualityException("ARM_STATE_INCOMPLETE");
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
        return signalBarOpenTime == null ? "" : "DRA-V1-" + signalBarOpenTime;
    }

    public record ClosePoint(LocalDateTime openTime, BigDecimal closePrice) {
    }

    public record DailyEmaPoint(LocalDateTime closeBarOpenTime, BigDecimal ema20) {
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
            List<DailyEmaPoint> dailyEmaHistory,
            BigDecimal dailyEma20,
            LocalDateTime armedAt,
            LocalDateTime armExpiresAt,
            LocalDateTime lastEntrySignalBarOpenTime,
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
            int armCount,
            int expiredArmCount,
            BigDecimal maxOpenCostUsdt,
            BigDecimal maxOpenCapitalLossPct,
            BigDecimal peakVirtualEquityUsdt,
            BigDecimal maxVirtualDrawdownPct
    ) {
        public State {
            closeHistory = closeHistory == null ? List.of() : List.copyOf(closeHistory);
            dailyEmaHistory =
                    dailyEmaHistory == null ? List.of() : List.copyOf(dailyEmaHistory);
            openLots = openLots == null ? List.of() : List.copyOf(openLots);
            pendingReason = pendingReason == null ? "" : pendingReason;
        }
    }

    public record SignalSnapshot(
            boolean ready,
            boolean dailyDecision,
            BigDecimal momentum24h,
            BigDecimal dailyEma20,
            BigDecimal dailyEma20FiveDaysAgo,
            boolean closeAboveEma,
            boolean emaRisingFiveDays,
            boolean momentumPositive,
            boolean dailyReversalConfirmed,
            boolean entryEligible,
            LocalDateTime armedAt,
            LocalDateTime armExpiresAt,
            String reason
    ) {
        public SignalSnapshot withEntryEligible(boolean value) {
            return new SignalSnapshot(
                    ready,
                    dailyDecision,
                    momentum24h,
                    dailyEma20,
                    dailyEma20FiveDaysAgo,
                    closeAboveEma,
                    emaRisingFiveDays,
                    momentumPositive,
                    dailyReversalConfirmed,
                    value,
                    armedAt,
                    armExpiresAt,
                    reason);
        }
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

    private record DailyIndicators(
            List<DailyEmaPoint> history,
            BigDecimal ema20
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
