package com.agora.service.tradingview;

import com.agora.config.OkxTradingProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.infra.notification.NotificationPort;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.service.backtest.LiveSignalContext;
import com.agora.service.meta.DecisionAuditWriter;
import com.agora.service.trading.OkxTradingService;
import com.agora.service.trading.TradeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal LIVE adapter for owner 509.
 *
 * <p>The strategy owns buy/sell decisions. This adapter only enforces
 * execution correctness: exact scope, current closed bar, durable reservation,
 * configured notionals, strategy-owned exposure, provider credentials, fill
 * validation, and persisted provider identity. It does not apply AI, regime,
 * performance, OCO, stop-loss, or cross-strategy risk opinions.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TradingViewScoreBuyAutoExitLiveService {

    private static final String POSITION_PREFIX = "LOCAL_TRADINGVIEW_BTC_BASE:TV509:";
    private static final String SIDE = "LONG";
    private static final BigDecimal ESTIMATED_SELL_FEE_RATE = new BigDecimal("0.0010");
    private static final BigDecimal DUST_QTY = new BigDecimal("0.00000001");
    private static final DateTimeFormatter CLIENT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);

    private final TradingViewLocalSignalProperties properties;
    private final OkxTradingProperties okxProperties;
    private final OkxTradingService okxTradingService;
    private final BtLiveSignalRepository liveSignalRepository;
    private final DecisionAuditWriter auditWriter;
    private final NotificationPort notificationPort;

    /**
     * Serialized inside one JVM; database reservations and OKX clOrdId provide
     * restart/concurrent-delivery duplicate protection.
     */
    public synchronized void evaluate(
            BtStrategy strategy,
            MdKline signalBar,
            String source,
            List<LiveSignalContext.OrderIntent> buyIntents,
            Map<String, Object> strategyDetails) {
        if (!properties.effectiveExecutionLiveOrderEnabled()) {
            return;
        }
        String blocker = scopeBlocker(strategy, signalBar, source);
        if (blocker != null) {
            audit(strategy, signalBar, "BLOCKED", blocker, "HOLD", false, null,
                    Map.of("blocker", blocker));
            return;
        }

        executeEligibleExits(strategy, signalBar);
        if (buyIntents != null && !buyIntents.isEmpty()) {
            TradingViewAccumulationOrderPlanner.Plan plan;
            try {
                plan = TradingViewAccumulationOrderPlanner.plan(
                        buyIntents,
                        properties.defaultNotionalUsdt(),
                        properties.maxNotionalUsdt());
            } catch (Exception e) {
                String buyBlocker = "INVALID_WEIGHTED_NOTIONAL:" + safe(e.getMessage(), 160);
                audit(strategy, signalBar, "BLOCKED", buyBlocker, "BUY", false, null,
                        Map.of("blocker", buyBlocker));
                return;
            }
            String buyBlocker = buyBlocker(strategy, signalBar, plan);
            if (buyBlocker == null) {
                executeBuy(strategy, signalBar, plan, strategyDetails);
            } else {
                audit(strategy, signalBar, "BLOCKED", buyBlocker, "BUY", false, null,
                        buyContext(plan, strategyDetails));
            }
        }
    }

    private String scopeBlocker(BtStrategy strategy, MdKline bar, String source) {
        if (strategy == null || strategy.getId() == null
                || strategy.getId() != TradingViewScoreBuyAutoExitStrategyContract.CURRENT_DATABASE_STRATEGY_ID) {
            return "DATABASE_STRATEGY_MAPPING_MISMATCH";
        }
        if (bar == null
                || !TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SYMBOL.equalsIgnoreCase(
                normalizeSymbol(bar.getSymbol()))
                || !TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_INTERVAL.equalsIgnoreCase(
                normalizeInterval(bar.getIntervalCode()))
                || !TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SOURCE.equalsIgnoreCase(
                normalizeSource(source))) {
            return "LIVE_SCOPE_NOT_ALLOWLISTED";
        }
        LocalDateTime closeTime = bar.getCloseTime();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (closeTime == null || closeTime.isAfter(now.plusMinutes(1))) {
            return "BAR_NOT_CONFIRMED_CLOSED";
        }
        long ageMinutes = Math.max(0L, Duration.between(closeTime, now).toMinutes());
        if (ageMinutes > properties.liveMaxSignalAgeMinutes()) {
            return "LIVE_SIGNAL_STALE_" + ageMinutes + "_MINUTES";
        }
        if (!okxProperties.isEnabled()) {
            return "OKX_TRADING_DISABLED";
        }
        if (!okxProperties.hasPrivateCredentials()) {
            return "OKX_PRIVATE_CREDENTIALS_MISSING";
        }
        return null;
    }

    private String buyBlocker(BtStrategy strategy,
                              MdKline signalBar,
                              TradingViewAccumulationOrderPlanner.Plan plan) {
        if (!plan.withinOrderCap()) {
            return "AGGREGATE_NOTIONAL_EXCEEDS_" + plain(properties.maxNotionalUsdt()) + "_USDT";
        }
        if (liveSignalRepository.findByStrategyIdAndSymbolAndIntervalCodeAndBarOpenTime(
                strategy.getId(),
                TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SYMBOL,
                TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_INTERVAL,
                signalBar.getOpenTime()).isPresent()) {
            return "DUPLICATE_BAR_ALREADY_RESERVED";
        }
        if (strategyOwnedRows(strategy.getId()).stream()
                .anyMatch(row -> !Boolean.TRUE.equals(row.getAutoTraded()))) {
            return "UNRESOLVED_509_ORDER_RESERVATION";
        }
        BigDecimal openCost = strategyOpenCost(strategy.getId());
        if (openCost.add(plan.requestedNotionalUsdt())
                .compareTo(properties.btcBaseMaxExposureUsdt()) > 0) {
            return "STRATEGY_EXPOSURE_CAP_" + plain(properties.btcBaseMaxExposureUsdt()) + "_USDT";
        }
        BigDecimal availableUsdt;
        try {
            availableUsdt = new BigDecimal(okxTradingService.getUsdtBalance());
        } catch (Exception e) {
            return "OKX_USDT_BALANCE_UNAVAILABLE";
        }
        if (availableUsdt.compareTo(plan.requestedNotionalUsdt()) < 0) {
            return "INSUFFICIENT_AVAILABLE_USDT";
        }
        try {
            BigDecimal current = okxTradingService.getLastPrice(
                    TradingViewScoreBuyAutoExitStrategyContract.EXECUTION_SYMBOL);
            OkxTradingService.SpotInstrumentRules rules = okxTradingService.getSpotInstrumentRules(
                    TradingViewScoreBuyAutoExitStrategyContract.EXECUTION_SYMBOL);
            if (!positive(current) || !positive(rules.minSize())) {
                return "OKX_INSTRUMENT_RULES_UNAVAILABLE";
            }
            BigDecimal estimatedQty = plan.requestedNotionalUsdt().divide(
                    current, 12, RoundingMode.DOWN);
            if (estimatedQty.compareTo(rules.minSize()) < 0) {
                return "OKX_MINIMUM_SIZE_NOT_MET";
            }
        } catch (Exception e) {
            return "OKX_PREFLIGHT_UNAVAILABLE:" + safe(e.getMessage(), 100);
        }
        return null;
    }

    private boolean executeBuy(BtStrategy strategy,
                               MdKline signalBar,
                               TradingViewAccumulationOrderPlanner.Plan plan,
                               Map<String, Object> strategyDetails) {
        String clientOrderId = clientOrderId("B", signalBar.getOpenTime());
        Map<String, Object> context = buyContext(plan, strategyDetails);
        context.put("clientOrderId", clientOrderId);
        context.put("barOpenTime", signalBar.getOpenTime());
        context.put("barCloseTime", signalBar.getCloseTime());
        context.put("signalVenue", TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SOURCE);
        context.put("executionVenue", "okx");

        BtLiveSignal reservation;
        try {
            reservation = reserveBuy(strategy, signalBar, plan, clientOrderId);
        } catch (DataIntegrityViolationException e) {
            audit(strategy, signalBar, "BLOCKED", "DUPLICATE_BAR_RESERVATION", "BUY",
                    false, null, context);
            return false;
        } catch (Exception e) {
            audit(strategy, signalBar, "ERROR", "BUY_RESERVATION_FAILED", "BUY",
                    false, null, withError(context, e));
            alert("CRITICAL", "509 BUY reservation failed before order; no order sent. "
                    + safe(e.getMessage(), 240));
            return false;
        }

        TradeResult fill;
        try {
            fill = okxTradingService.placeMarketBuy(
                    TradingViewScoreBuyAutoExitStrategyContract.EXECUTION_SYMBOL,
                    plan.requestedNotionalUsdt().setScale(2, RoundingMode.HALF_UP).doubleValue(),
                    clientOrderId);
            requireValidFill(fill);
        } catch (Exception e) {
            markSubmissionUnconfirmed(reservation, "BUY", clientOrderId, e);
            context.put("liveSignalId", reservation.getId());
            audit(strategy, signalBar, "ERROR", "BUY_SUBMISSION_UNCONFIRMED", "BUY",
                    false, reservation.getId(), withError(context, e));
            alert("CRITICAL", "509 BUY submission unconfirmed; automatic retry disabled. clOrdId="
                    + clientOrderId + " error=" + safe(e.getMessage(), 240));
            return false;
        }

        try {
            BigDecimal effectiveEntry = effectiveBuyCostPerNetUnit(fill);
            reservation.setEntryPrice(effectiveEntry);
            reservation.setSuggestedTp(requiredExitPrice(effectiveEntry));
            reservation.setActualEntryPrice(fill.getAvgPrice());
            reservation.setTradedQty(fill.getQty());
            reservation.setOcoQty(fill.getQty());
            reservation.setAutoTraded(true);
            reservation.setExchangeOrderId(safe("OKX:" + fill.getOrderId(), 50));
            reservation.setFilterReason(positionReason(
                    "OPEN:CL=" + clientOrderId
                            + ":WEIGHT=" + plain(plan.aggregateWeight())
                            + ":REASONS=" + plan.aggregateReasons()));
            liveSignalRepository.saveAndFlush(reservation);
            context.put("liveSignalId", reservation.getId());
            context.put("providerOrderId", fill.getOrderId());
            context.put("avgPrice", fill.getAvgPrice());
            context.put("grossQty", fill.getGrossQty());
            context.put("netQty", fill.getQty());
            context.put("feeAmount", fill.getFeeAmount());
            context.put("feeCurrency", fill.getFeeCurrency());
            context.put("feeUsdt", fill.getFeeUsdt());
            context.put("effectiveEntryPrice", effectiveEntry);
            audit(strategy, signalBar, "PASS", "BUY_FILLED", "BUY",
                    true, reservation.getId(), context);
            alert("INFO", "509 BUY filled: " + plain(plan.requestedNotionalUsdt())
                    + " USDT, weight=" + plain(plan.aggregateWeight())
                    + ", orderId=" + fill.getOrderId()
                    + ", qty=" + plain(fill.getQty()));
            log.info("[TV509] BUY filled bar={} weight={} notional={} orderId={} qty={} effectiveEntry={}",
                    signalBar.getOpenTime(), plan.aggregateWeight(), plan.requestedNotionalUsdt(),
                    fill.getOrderId(), fill.getQty(), effectiveEntry);
            return true;
        } catch (Exception e) {
            audit(strategy, signalBar, "ERROR", "BUY_FILL_PERSIST_FAILED", "BUY",
                    true, reservation.getId(), withError(context, e));
            alert("CRITICAL", "509 BUY filled but persistence failed. orderId="
                    + fill.getOrderId() + " clOrdId=" + clientOrderId
                    + " error=" + safe(e.getMessage(), 240));
            return false;
        }
    }

    private boolean executeEligibleExits(BtStrategy strategy, MdKline signalBar) {
        List<BtLiveSignal> openLots = strategyLiveLots(strategy.getId());
        if (openLots.isEmpty()) {
            return false;
        }
        BigDecimal currentPrice;
        try {
            currentPrice = okxTradingService.getLastPrice(
                    TradingViewScoreBuyAutoExitStrategyContract.EXECUTION_SYMBOL);
        } catch (Exception e) {
            audit(strategy, signalBar, "ERROR", "EXIT_PRICE_UNAVAILABLE", "SELL",
                    false, null, Map.of("error", safe(e.getMessage(), 240)));
            return false;
        }
        if (!positive(currentPrice)) {
            return false;
        }

        List<BtLiveSignal> eligible = openLots.stream()
                .filter(lot -> estimatedNetReturn(lot, currentPrice)
                        .compareTo(TradingViewScoreBuyAutoExitStrategyContract.NET_PROFIT_TRIGGER) >= 0)
                .toList();
        if (eligible.isEmpty()) {
            return false;
        }

        OkxTradingService.SpotInstrumentRules rules;
        try {
            rules = okxTradingService.getSpotInstrumentRules(
                    TradingViewScoreBuyAutoExitStrategyContract.EXECUTION_SYMBOL);
        } catch (Exception e) {
            audit(strategy, signalBar, "ERROR", "EXIT_INSTRUMENT_RULES_UNAVAILABLE", "SELL",
                    false, null, Map.of("error", safe(e.getMessage(), 240)));
            return false;
        }
        if (!positive(rules.minSize()) || !positive(rules.lotSize())) {
            return false;
        }
        BigDecimal requestedQty = eligible.stream()
                .map(BtLiveSignal::getTradedQty)
                .filter(this::positive)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        requestedQty = floorToLot(requestedQty, rules.lotSize());
        if (!positive(requestedQty) || requestedQty.compareTo(rules.minSize()) < 0) {
            return false;
        }
        BigDecimal available = availableBtc();
        if (available.compareTo(requestedQty) < 0) {
            audit(strategy, signalBar, "BLOCKED", "STRATEGY_BTC_NOT_FULLY_AVAILABLE", "SELL",
                    false, null, Map.of(
                            "requestedQty", requestedQty,
                            "availableBtc", available,
                            "eligibleLots", eligible.size()));
            return false;
        }

        String clientOrderId = clientOrderId("S", signalBar.getOpenTime());
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("clientOrderId", clientOrderId);
        context.put("currentPrice", currentPrice);
        context.put("requestedQty", requestedQty);
        context.put("eligibleLotIds", eligible.stream().map(BtLiveSignal::getId).toList());
        context.put("netProfitTrigger", TradingViewScoreBuyAutoExitStrategyContract.NET_PROFIT_TRIGGER);
        try {
            for (BtLiveSignal lot : eligible) {
                lot.setFilterReason(positionReason(
                        "SELL_RESERVED:CL=" + clientOrderId + ":LOT=" + lot.getId()));
                liveSignalRepository.saveAndFlush(lot);
            }
        } catch (Exception e) {
            audit(strategy, signalBar, "ERROR", "SELL_RESERVATION_FAILED", "SELL",
                    false, eligible.get(0).getId(), withError(context, e));
            alert("CRITICAL", "509 SELL reservation failed before order; no order sent. "
                    + safe(e.getMessage(), 240));
            return false;
        }

        TradeResult fill;
        try {
            fill = okxTradingService.placeMarketSellWithFill(
                    TradingViewScoreBuyAutoExitStrategyContract.EXECUTION_SYMBOL,
                    requestedQty,
                    clientOrderId);
            requireValidFill(fill);
        } catch (Exception e) {
            for (BtLiveSignal lot : eligible) {
                markSubmissionUnconfirmed(lot, "SELL", clientOrderId, e);
            }
            audit(strategy, signalBar, "ERROR", "SELL_SUBMISSION_UNCONFIRMED", "SELL",
                    false, eligible.get(0).getId(), withError(context, e));
            alert("CRITICAL", "509 SELL submission unconfirmed; automatic retry disabled. clOrdId="
                    + clientOrderId + " error=" + safe(e.getMessage(), 240));
            return false;
        }

        BigDecimal soldQty = positive(fill.getGrossQty()) ? fill.getGrossQty() : fill.getQty();
        BigDecimal totalFee = positive(fill.getFeeUsdt())
                ? fill.getFeeUsdt()
                : fill.getAvgPrice().multiply(soldQty).multiply(ESTIMATED_SELL_FEE_RATE);
        BigDecimal remainingFill = soldQty;
        BigDecimal realizedTotal = BigDecimal.ZERO;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        try {
            for (BtLiveSignal lot : eligible) {
                if (!positive(remainingFill)) {
                    lot.setFilterReason(positionReason(
                            "SELL_PARTIAL_UNFILLED:CL=" + clientOrderId + ":LOT=" + lot.getId()));
                    liveSignalRepository.saveAndFlush(lot);
                    continue;
                }
                BigDecimal lotQty = lot.getTradedQty();
                BigDecimal allocated = lotQty.min(remainingFill);
                BigDecimal feeShare = totalFee.multiply(allocated)
                        .divide(soldQty, 12, RoundingMode.HALF_UP);
                BigDecimal proceeds = fill.getAvgPrice().multiply(allocated).subtract(feeShare);
                BigDecimal cost = lot.getEntryPrice().multiply(allocated);
                BigDecimal pnl = proceeds.subtract(cost).setScale(8, RoundingMode.HALF_UP);
                BigDecimal previousPnl = lot.getRealizedPnl() == null
                        ? BigDecimal.ZERO : lot.getRealizedPnl();
                lot.setRealizedPnl(previousPnl.add(pnl));
                remainingFill = remainingFill.subtract(allocated);
                BigDecimal remainingLot = lotQty.subtract(allocated);
                if (remainingLot.compareTo(DUST_QTY) <= 0) {
                    lot.setExitPrice(fill.getAvgPrice());
                    lot.setExitTime(now);
                    lot.setExitReason("TV509_AUTO_NET_PROFIT");
                    lot.setFilterReason(positionReason(
                            "CLOSED:CL=" + clientOrderId + ":ORDER=" + fill.getOrderId()));
                } else {
                    lot.setTradedQty(remainingLot);
                    lot.setOcoQty(remainingLot);
                    lot.setFilterReason(positionReason(
                            "OPEN_PARTIAL:CL=" + clientOrderId + ":ORDER=" + fill.getOrderId()));
                }
                liveSignalRepository.saveAndFlush(lot);
                realizedTotal = realizedTotal.add(pnl);
            }
        } catch (Exception e) {
            context.put("providerOrderId", fill.getOrderId());
            context.put("soldQty", soldQty);
            audit(strategy, signalBar, "ERROR", "SELL_FILL_PERSIST_FAILED", "SELL",
                    true, eligible.get(0).getId(), withError(context, e));
            alert("CRITICAL", "509 SELL filled but persistence failed. orderId="
                    + fill.getOrderId() + " clOrdId=" + clientOrderId
                    + " error=" + safe(e.getMessage(), 240));
            return false;
        }

        context.put("providerOrderId", fill.getOrderId());
        context.put("avgPrice", fill.getAvgPrice());
        context.put("soldQty", soldQty);
        context.put("feeUsdt", totalFee);
        context.put("realizedPnlUsdt", realizedTotal);
        context.put("unallocatedFillQty", remainingFill.max(BigDecimal.ZERO));
        audit(strategy, signalBar, "PASS", "SELL_FILLED", "SELL",
                true, eligible.get(0).getId(), context);
        alert("INFO", "509 SELL filled: orderId=" + fill.getOrderId()
                + ", qty=" + plain(soldQty)
                + ", realizedPnl=" + plain(realizedTotal) + " USDT");
        log.info("[TV509] SELL filled bar={} lots={} orderId={} qty={} realizedPnl={}",
                signalBar.getOpenTime(), eligible.size(), fill.getOrderId(), soldQty, realizedTotal);
        return true;
    }

    private BtLiveSignal reserveBuy(BtStrategy strategy,
                                    MdKline signalBar,
                                    TradingViewAccumulationOrderPlanner.Plan plan,
                                    String clientOrderId) {
        BtLiveSignal signal = new BtLiveSignal();
        signal.setStrategyId(strategy.getId());
        signal.setSymbol(TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SYMBOL);
        signal.setIntervalCode(TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_INTERVAL);
        signal.setBarOpenTime(signalBar.getOpenTime());
        signal.setEntryPrice(signalBar.getClosePrice());
        signal.setSuggestedTp(requiredExitPrice(signalBar.getClosePrice()));
        signal.setSuggestedSl(null);
        signal.setScore(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        signal.setNnOutput(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        signal.setNotifiedAt(LocalDateTime.now(ZoneOffset.UTC));
        signal.setAutoTraded(false);
        signal.setExchangeOrderId(safe("PENDING:" + clientOrderId, 50));
        signal.setSide(SIDE);
        signal.setFilterReason(positionReason(
                "BUY_RESERVED:CL=" + clientOrderId
                        + ":WEIGHT=" + plain(plan.aggregateWeight())
                        + ":REASONS=" + plan.aggregateReasons()));
        return liveSignalRepository.saveAndFlush(signal);
    }

    private void markSubmissionUnconfirmed(BtLiveSignal lot,
                                           String side,
                                           String clientOrderId,
                                           Exception error) {
        try {
            lot.setFilterReason(positionReason(
                    side + "_SUBMISSION_UNCONFIRMED:CL=" + clientOrderId
                            + ":ERR=" + safe(error.getMessage(), 220)));
            liveSignalRepository.saveAndFlush(lot);
        } catch (Exception persistError) {
            log.error("[TV509] failed to persist ambiguous {} status liveSignal={} clOrdId={} error={}",
                    side, lot.getId(), clientOrderId, persistError.getMessage(), persistError);
        }
    }

    private List<BtLiveSignal> strategyLiveLots(Long strategyId) {
        return strategyOwnedRows(strategyId).stream()
                .filter(lot -> Boolean.TRUE.equals(lot.getAutoTraded()))
                .filter(lot -> lot.getFilterReason() != null
                        && lot.getFilterReason().startsWith(POSITION_PREFIX)
                        && (lot.getFilterReason().contains(":OPEN:")
                        || lot.getFilterReason().contains(":OPEN_PARTIAL:")))
                .filter(lot -> positive(lot.getEntryPrice()) && positive(lot.getTradedQty()))
                .toList();
    }

    private BigDecimal strategyOpenCost(Long strategyId) {
        return strategyOwnedRows(strategyId).stream()
                .filter(lot -> Boolean.TRUE.equals(lot.getAutoTraded()))
                .filter(lot -> positive(lot.getEntryPrice()) && positive(lot.getTradedQty()))
                .map(lot -> lot.getEntryPrice().multiply(lot.getTradedQty()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<BtLiveSignal> strategyOwnedRows(Long strategyId) {
        return liveSignalRepository
                .findByStrategyIdAndSymbolAndIntervalCodeAndExitTimeIsNullAndNotifiedAtIsNotNull(
                        strategyId,
                        TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SYMBOL,
                        TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_INTERVAL)
                .stream()
                .filter(lot -> lot.getFilterReason() != null
                        && lot.getFilterReason().startsWith(POSITION_PREFIX))
                .toList();
    }

    private BigDecimal estimatedNetReturn(BtLiveSignal lot, BigDecimal currentPrice) {
        BigDecimal cost = lot.getEntryPrice().multiply(lot.getTradedQty());
        if (!positive(cost)) {
            return BigDecimal.valueOf(-1);
        }
        BigDecimal estimatedNet = currentPrice.multiply(lot.getTradedQty())
                .multiply(BigDecimal.ONE.subtract(ESTIMATED_SELL_FEE_RATE));
        return estimatedNet.subtract(cost).divide(cost, 12, RoundingMode.HALF_UP);
    }

    private BigDecimal availableBtc() {
        return okxTradingService.getFreshSpotHoldings().stream()
                .filter(holding -> "BTC".equalsIgnoreCase(holding.ccy))
                .map(holding -> holding.availBal)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal floorToLot(BigDecimal quantity, BigDecimal lotSize) {
        if (!positive(quantity) || !positive(lotSize)) {
            return BigDecimal.ZERO;
        }
        BigDecimal lots = quantity.divide(lotSize, 0, RoundingMode.DOWN);
        return lots.multiply(lotSize).stripTrailingZeros();
    }

    private BigDecimal effectiveBuyCostPerNetUnit(TradeResult fill) {
        BigDecimal grossQty = positive(fill.getGrossQty()) ? fill.getGrossQty() : fill.getQty();
        BigDecimal cost = fill.getAvgPrice().multiply(grossQty);
        if ("USDT".equalsIgnoreCase(fill.getFeeCurrency()) && positive(fill.getFeeUsdt())) {
            cost = cost.add(fill.getFeeUsdt());
        }
        return cost.divide(fill.getQty(), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal requiredExitPrice(BigDecimal effectiveEntry) {
        if (!positive(effectiveEntry)) {
            return effectiveEntry;
        }
        return effectiveEntry
                .multiply(BigDecimal.ONE.add(
                        TradingViewScoreBuyAutoExitStrategyContract.NET_PROFIT_TRIGGER))
                .divide(BigDecimal.ONE.subtract(ESTIMATED_SELL_FEE_RATE), 8, RoundingMode.HALF_UP);
    }

    private void requireValidFill(TradeResult fill) {
        if (fill == null || fill.getOrderId() == null || fill.getOrderId().isBlank()
                || !positive(fill.getAvgPrice()) || !positive(fill.getQty())) {
            throw new IllegalStateException("OKX_FILL_INCOMPLETE");
        }
    }

    private Map<String, Object> buyContext(
            TradingViewAccumulationOrderPlanner.Plan plan,
            Map<String, Object> strategyDetails) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("ownerAlias", TradingViewScoreBuyAutoExitStrategyContract.OWNER_ALIAS);
        context.put("strategyContract", TradingViewScoreBuyAutoExitStrategyContract.KEY);
        context.put("aggregateWeight", plan.aggregateWeight());
        context.put("requestedNotionalUsdt", plan.requestedNotionalUsdt());
        context.put("maxOrderNotionalUsdt", properties.maxNotionalUsdt());
        context.put("maxExposureUsdt", properties.btcBaseMaxExposureUsdt());
        context.put("intentReasons", plan.aggregateReasons());
        if (strategyDetails != null) {
            strategyDetails.forEach((key, value) ->
                    context.put("strategy." + key, value));
        }
        return context;
    }

    private void audit(BtStrategy strategy,
                       MdKline signalBar,
                       String outcome,
                       String finalOutcome,
                       String decision,
                       boolean orderSent,
                       Long liveSignalId,
                       Map<String, Object> context) {
        Long strategyId = strategy == null ? null : strategy.getId();
        Map<String, Object> auditContext = new LinkedHashMap<>(
                context == null ? Map.of() : context);
        auditContext.put("ownerAlias", TradingViewScoreBuyAutoExitStrategyContract.OWNER_ALIAS);
        auditContext.put("executionMode", "OKX_SPOT_LIVE");
        auditContext.put("decision", decision);
        auditContext.put("finalOutcome", finalOutcome);
        auditContext.put("orderSent", orderSent);
        if ("PASS".equals(outcome) && "SELL".equals(decision)) {
            auditWriter.logExit(strategyId,
                    TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SYMBOL,
                    liveSignalId, finalOutcome, auditContext);
        } else if ("PASS".equals(outcome)) {
            auditWriter.logAutoTradeOk(strategyId,
                    TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SYMBOL,
                    liveSignalId, auditContext);
        } else if ("ERROR".equals(outcome)) {
            auditWriter.logAutoTradeFail(strategyId,
                    TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SYMBOL,
                    finalOutcome, auditContext);
        } else {
            auditWriter.logEntrySkip(strategyId,
                    TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SYMBOL,
                    TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_INTERVAL,
                    signalBar == null ? null : signalBar.getOpenTime(),
                    safe(finalOutcome, 64), finalOutcome, auditContext, liveSignalId);
        }
    }

    private Map<String, Object> withError(Map<String, Object> context, Exception error) {
        Map<String, Object> copy = new LinkedHashMap<>(context);
        copy.put("error", safe(error == null ? null : error.getMessage(), 420));
        return copy;
    }

    private String clientOrderId(String side, LocalDateTime barOpenTime) {
        if (barOpenTime == null) {
            throw new IllegalArgumentException("barOpenTime is required for client order id");
        }
        return "TV509" + side + CLIENT_TIME.format(barOpenTime);
    }

    private String positionReason(String suffix) {
        return safe(POSITION_PREFIX + suffix, 500);
    }

    private void alert(String level, String message) {
        try {
            notificationPort.alert(message, false, "TradingView509Live", level);
        } catch (Exception e) {
            log.error("[TV509] notification failed level={} error={}", level, e.getMessage(), e);
        }
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private String plain(BigDecimal value) {
        return value == null ? "N/A" : value.stripTrailingZeros().toPlainString();
    }

    private String normalizeSymbol(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT)
                .replace("-", "").replace("/", "").replace("_", "");
    }

    private String normalizeInterval(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return "d".equals(normalized) ? "1d" : normalized;
    }

    private String normalizeSource(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private String safe(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

}
