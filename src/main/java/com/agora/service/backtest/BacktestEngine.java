package com.agora.service.backtest;

import com.agora.dto.backtest.BacktestResultResponse;
import com.agora.model.MdKline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
public class BacktestEngine {

    public BacktestRunSummary run(
            List<MdKline> klines,
            Strategy strategy,
            Map<String, Object> config,
            BigDecimal initialCapital,
            BigDecimal feeRate
    ) {
        String baseInterval = getString(config, "runIntervalCode", "1h").toLowerCase();
        Map<String, List<MdKline>> timeframeKlines = new HashMap<String, List<MdKline>>();
        timeframeKlines.put(baseInterval, klines);
        return run(klines, timeframeKlines, strategy, config, initialCapital, feeRate);
    }

    public BacktestRunSummary run(
            List<MdKline> baseKlines,
            Map<String, List<MdKline>> timeframeKlines,
            Strategy strategy,
            Map<String, Object> config,
            BigDecimal initialCapital,
            BigDecimal feeRate
    ) {
        if (baseKlines == null || baseKlines.isEmpty()) {
            throw new IllegalArgumentException("K線資料不可為空");
        }

        String baseInterval = getString(config, "runIntervalCode", "1h").toLowerCase();
        Map<String, List<MdKline>> tfMap = new HashMap<String, List<MdKline>>();
        tfMap.putAll(timeframeKlines == null ? Collections.<String, List<MdKline>>emptyMap() : timeframeKlines);
        tfMap.put(baseInterval, baseKlines);

        // 若 timeframeKlines 對 baseInterval 提供了更長的延伸 K 線（含 warmup），則用來建立指標
        List<MdKline> extendedBaseKlines = timeframeKlines != null ? timeframeKlines.get(baseInterval) : null;
        boolean hasWarmup = extendedBaseKlines != null && extendedBaseKlines.size() > baseKlines.size();

        Map<String, Map<String, double[]>> indicatorsByTf = new HashMap<String, Map<String, double[]>>();
        for (Map.Entry<String, List<MdKline>> entry : tfMap.entrySet()) {
            String tf = entry.getKey();
            List<MdKline> tfKlines = tf.equals(baseInterval) && hasWarmup ? extendedBaseKlines : entry.getValue();
            if (tfKlines == null || tfKlines.isEmpty()) {
                continue;
            }
            indicatorsByTf.put(tf, buildIndicators(tfKlines, config));
        }
        Map<String, double[]> baseIndicators = indicatorsByTf.get(baseInterval);
        if (baseIndicators == null) {
            throw new IllegalArgumentException("缺少基準週期指標: " + baseInterval);
        }
        // 切片：去除 warmup 區段，使指標陣列 index 與 baseKlines 對齊
        if (hasWarmup && extendedBaseKlines != null) {
            int warmupOffset = extendedBaseKlines.size() - baseKlines.size();
            Map<String, double[]> sliced = new HashMap<String, double[]>();
            for (Map.Entry<String, double[]> e : baseIndicators.entrySet()) {
                sliced.put(e.getKey(), Arrays.copyOfRange(e.getValue(), warmupOffset, e.getValue().length));
            }
            indicatorsByTf.put(baseInterval, sliced);
            baseIndicators = sliced;
        }

        double fee = feeRate.doubleValue();
        boolean allowShort = getBoolean(config, "allowShort", false)
                || getBoolean(config, "shortOnly", false);
        double dailyBorrowingRate = getDouble(config, "dailyBorrowingRate", 0.0);
        double hourlyBorrowingRate = dailyBorrowingRate / 24.0;
        boolean partialTpEnabled = getBoolean(config, "partialTpEnabled", false);
        boolean moveSlToBreakeven = getBoolean(config, "moveSlToBreakeven", false);
        boolean atrTrailingStopEnabled = getBoolean(config, "atrTrailingStopEnabled", false);
        double atrMultiplier = getDouble(config, "atrMultiplier", 2.0);
        int maxHoldingHours = getInt(config, "maxHoldingHours", 0);
        double tp1Ratio = clamp(getDouble(config, "tp1Ratio", 0.5), 0.05, 0.95);

        double[] baseAtr = atrTrailingStopEnabled ? baseIndicators.get("atr") : null;

        // 進場頻率冷卻：防止 SIDEWAYS 環境過度交易
        // 預設 60 min 與 LiveSignalEvaluator 保持一致（不設 key 等同 live 行為）
        int cooldownMinutes = getInt(config, "entryFrequencyCooldownMinutes", 60);
        LocalDateTime lastExitTime = null;
        log.debug("[Backtest] Cooldown config loaded: cooldownMinutes={}", cooldownMinutes);

        double cash = initialCapital.doubleValue();
        double maxEquity = cash;
        double maxDrawdown = 0.0;
        int winTrades = 0;
        int filteredEntryCount = 0;
        // #392 Option B: lightweight RegimeFilter parity — counts LONG entries
        // suppressed because the deterministic regime classifier voted TRENDING_DOWN.
        int regimeBlockedCount = 0;
        LocalDateTime tradeStartTime = getLocalDateTime(config, "backtestTradeStartTime");

        if (getBoolean(config, "tradingViewOrderIntentExecution", false)) {
            return runTradingViewOrderIntentExecution(baseKlines, tfMap, baseInterval,
                    baseIndicators, indicatorsByTf, strategy, config, fee, tradeStartTime);
        }

        // 每次 run() 開始前清除 HistoricalFilterEvaluator thread-local 快取
        Position openPosition = null;
        List<TradeRecord> trades = new ArrayList<TradeRecord>();

        for (int i = 0; i < baseKlines.size(); i++) {
            MdKline current = baseKlines.get(i);
            MdKline previous = i > 0 ? baseKlines.get(i - 1) : null;

            Map<String, Integer> tfIndices = alignIndices(current.getOpenTime(), tfMap, baseInterval, i);
            StrategyContext context = new StrategyContext(
                    i,
                    current,
                    previous,
                    baseKlines,
                    baseIndicators,
                    tfMap,
                    tfIndices,
                    indicatorsByTf
            );

            // #305 更新 openPosition 快照，讓策略可直接讀取進場狀態
            if (openPosition != null && current.getClosePrice() != null) {
                double curClose = current.getClosePrice().doubleValue();
                double pnlPct = openPosition.side == PositionSide.LONG
                        ? (curClose - openPosition.entryPrice) / openPosition.entryPrice
                        : (openPosition.entryPrice - curClose) / openPosition.entryPrice;
                context.setOpenPosition(new StrategyContext.OpenPositionSnapshot(
                        openPosition.entryPrice, openPosition.entryTime, pnlPct,
                        openPosition.entryIndicatorSnapshot));
            }

            LiveSignalContext.clear();
            StrategySignal signal = strategy.evaluate(context, config);
            List<LiveSignalContext.OrderIntent> orderIntents = LiveSignalContext.getOrderIntents();
            double close = current.getClosePrice().doubleValue();
            double high = current.getHighPrice().doubleValue();
            double low = current.getLowPrice().doubleValue();

            if (openPosition != null) {
                // ── #450 NEW: per-strategy exit adjustment hook ──────────────
                // 在 ATR trailing / SL/TP check 之前,讓 strategy 看 hold 中
                // 狀態決定是否 forceClose 或調整 OCO。Default no-op 等同既有行為。
                // skipAdjustExit=true (config) → bypass(用於 A/B 對比 vs static OCO)。
                OpenPositionView posView = buildPositionView(openPosition, current, config);
                java.util.Optional<ExitAdjustment> exitAdj = java.util.Optional.empty();
                if (!getBoolean(config, "skipAdjustExit", false)) {
                    try {
                        exitAdj = strategy.adjustExit(context, posView, config);
                    } catch (Throwable t) {
                        log.warn("[Backtest] strategy.adjustExit threw at bar {} for {}: {}",
                                current.getOpenTime(), strategy.getType(), t.getMessage());
                    }
                }
                if (exitAdj.isPresent() && !exitAdj.get().isNoop()) {
                    ExitAdjustment a = exitAdj.get();
                    if (a.forceClose()) {
                        // 立即 market exit at current close
                        TradeRecord trade = closePartial(openPosition, current, close,
                                openPosition.quantity, fee, "STRATEGY_" + a.tag(),
                                openPosition.side, hourlyBorrowingRate);
                        trades.add(trade);
                        cash += trade.getNetPnl() + trade.getReleasedNotional();
                        if (trade.getNetPnl() > 0) winTrades++;
                        lastExitTime = current.getOpenTime();
                        openPosition = null;
                        // Skip rest of openPosition handling for this bar
                        continue;
                    } else {
                        if (a.newTp() != null) openPosition.takeProfit2Price = a.newTp().doubleValue();
                        if (a.newSl() != null) openPosition.stopLossPrice = a.newSl().doubleValue();
                    }
                }

                // ATR trailing stop: update SL before checking exits
                if (atrTrailingStopEnabled && baseAtr != null
                        && i < baseAtr.length && !Double.isNaN(baseAtr[i])) {
                    double atrStop = atrMultiplier * baseAtr[i];
                    // Use high/low as anchor (not close) to lock in more profit
                    // on strong bars where high - close > 0.5 ATR (Jesse V2 fix)
                    if (openPosition.side == PositionSide.LONG) {
                        double newSl = high - atrStop;
                        if (newSl > openPosition.stopLossPrice) {
                            openPosition.stopLossPrice = newSl;
                        }
                    } else {
                        double newSl = low + atrStop;
                        if (newSl < openPosition.stopLossPrice) {
                            openPosition.stopLossPrice = newSl;
                        }
                    }
                }

                boolean positionClosed = false;

                if (openPosition.side == PositionSide.LONG) {
                    if (low <= openPosition.stopLossPrice) {
                        TradeRecord trade = closePartial(openPosition, current, openPosition.stopLossPrice,
                                openPosition.quantity, fee, "SL", openPosition.side, hourlyBorrowingRate);
                        trades.add(trade);
                        cash += trade.getNetPnl() + trade.getReleasedNotional();
                        if (trade.getNetPnl() > 0) {
                            winTrades++;
                        }
                        openPosition = null;
                        positionClosed = true;
                    } else {
                        if (partialTpEnabled && !openPosition.tp1Taken && high >= openPosition.takeProfit1Price) {
                            double qty = openPosition.quantity * tp1Ratio;
                            TradeRecord trade = closePartial(openPosition, current, openPosition.takeProfit1Price,
                                    qty, fee, "TP1", openPosition.side, hourlyBorrowingRate);
                            trades.add(trade);
                            cash += trade.getNetPnl() + trade.getReleasedNotional();
                            if (trade.getNetPnl() > 0) {
                                winTrades++;
                            }
                            openPosition.tp1Taken = true;
                            if (moveSlToBreakeven) {
                                openPosition.stopLossPrice = Math.max(openPosition.stopLossPrice, openPosition.entryPrice);
                            }
                            if (openPosition.quantity <= EPS) {
                                openPosition = null;
                                positionClosed = true;
                            }
                        }
                        if (!positionClosed && high >= openPosition.takeProfit2Price) {
                            TradeRecord trade = closePartial(openPosition, current, openPosition.takeProfit2Price,
                                    openPosition.quantity, fee, "TP2", openPosition.side, hourlyBorrowingRate);
                            trades.add(trade);
                            cash += trade.getNetPnl() + trade.getReleasedNotional();
                            if (trade.getNetPnl() > 0) {
                                winTrades++;
                            }
                            openPosition = null;
                            positionClosed = true;
                        }
                    }
                } else {
                    if (high >= openPosition.stopLossPrice) {
                        TradeRecord trade = closePartial(openPosition, current, openPosition.stopLossPrice,
                                openPosition.quantity, fee, "SL", openPosition.side, hourlyBorrowingRate);
                        trades.add(trade);
                        cash += trade.getNetPnl() + trade.getReleasedNotional();
                        if (trade.getNetPnl() > 0) {
                            winTrades++;
                        }
                        openPosition = null;
                        positionClosed = true;
                    } else {
                        if (partialTpEnabled && !openPosition.tp1Taken && low <= openPosition.takeProfit1Price) {
                            double qty = openPosition.quantity * tp1Ratio;
                            TradeRecord trade = closePartial(openPosition, current, openPosition.takeProfit1Price,
                                    qty, fee, "TP1", openPosition.side, hourlyBorrowingRate);
                            trades.add(trade);
                            cash += trade.getNetPnl() + trade.getReleasedNotional();
                            if (trade.getNetPnl() > 0) {
                                winTrades++;
                            }
                            openPosition.tp1Taken = true;
                            if (moveSlToBreakeven) {
                                openPosition.stopLossPrice = Math.min(openPosition.stopLossPrice, openPosition.entryPrice);
                            }
                            if (openPosition.quantity <= EPS) {
                                openPosition = null;
                                positionClosed = true;
                            }
                        }
                        if (!positionClosed && low <= openPosition.takeProfit2Price) {
                            TradeRecord trade = closePartial(openPosition, current, openPosition.takeProfit2Price,
                                    openPosition.quantity, fee, "TP2", openPosition.side, hourlyBorrowingRate);
                            trades.add(trade);
                            cash += trade.getNetPnl() + trade.getReleasedNotional();
                            if (trade.getNetPnl() > 0) {
                                winTrades++;
                            }
                            openPosition = null;
                            positionClosed = true;
                        }
                    }
                }

                if (!positionClosed && openPosition != null) {
                    boolean exitBySignal = openPosition.side == PositionSide.LONG
                            ? signal == StrategySignal.SELL
                            : signal == StrategySignal.BUY;
                    boolean exitByTime = maxHoldingHours > 0
                        && Duration.between(openPosition.entryTime, current.getOpenTime()).toHours() >= maxHoldingHours;
                    if (exitBySignal || exitByTime || i == baseKlines.size() - 1) {
                    String reason = exitBySignal ? "SIGNAL" : (exitByTime ? "TIME" : "END");
                        TradeRecord trade = closePartial(openPosition, current, close,
                                openPosition.quantity, fee, reason, openPosition.side, hourlyBorrowingRate);
                        trades.add(trade);
                        cash += trade.getNetPnl() + trade.getReleasedNotional();
                        if (trade.getNetPnl() > 0) {
                            winTrades++;
                        }
                        lastExitTime = current.getOpenTime();
                        openPosition = null;
                    }
                }
            }

            boolean entryWindowOpen = tradeStartTime == null || !current.getOpenTime().isBefore(tradeStartTime);
            if (entryWindowOpen && openPosition == null && cash > 0.0 && i < baseKlines.size() - 1) {
                boolean isBuy  = signal == StrategySignal.BUY;
                boolean isSell = allowShort && signal == StrategySignal.SELL;

                // 進場頻率冷卻：檢查距離上次平倉的時間
                if ((isBuy || isSell) && cooldownMinutes > 0 && lastExitTime != null) {
                    long elapsedMinutes = Duration.between(lastExitTime, current.getOpenTime()).toMinutes();
                    if (elapsedMinutes < cooldownMinutes) {
                        log.debug("[Backtest] Cooldown active: {} minutes remaining at {}",
                                (cooldownMinutes - elapsedMinutes), current.getOpenTime());
                        isBuy = false;
                        isSell = false;
                    }
                }

                if (isBuy) {
                    openPosition = openPosition(PositionSide.LONG, current, close, cash, fee,
                            baseKlines, i, config, orderIntents);
                    if (openPosition != null) {
                        cash = 0.0;
                    }
                } else if (isSell) {
                    openPosition = openPosition(PositionSide.SHORT, current, close, cash, fee,
                            baseKlines, i, config, orderIntents);
                    if (openPosition != null) {
                        cash = 0.0;
                    }
                }
            }

            double equity = cash;
            if (openPosition != null) {
                double unrealizedPnl = openPosition.side == PositionSide.LONG
                        ? (close - openPosition.entryPrice) * openPosition.quantity
                        : (openPosition.entryPrice - close) * openPosition.quantity;
                equity += openPosition.notional + unrealizedPnl;
            }
            if (equity > maxEquity) {
                maxEquity = equity;
            }
            if (maxEquity > 0.0) {
                double drawdown = (maxEquity - equity) / maxEquity;
                if (drawdown > maxDrawdown) {
                    maxDrawdown = drawdown;
                }
            }
        }

        int longTradeCount = 0;
        int shortTradeCount = 0;
        int longWinCount = 0;
        int shortWinCount = 0;
        for (TradeRecord t : trades) {
            if ("SHORT".equals(t.getSide())) {
                shortTradeCount++;
                if (t.getNetPnl() > 0) shortWinCount++;
            } else {
                longTradeCount++;
                if (t.getNetPnl() > 0) longWinCount++;
            }
        }

        BacktestRunSummary summary = new BacktestRunSummary();
        summary.setInitialCapital(initialCapital.doubleValue());
        summary.setFinalCapital(cash);
        summary.setTotalReturn((cash - initialCapital.doubleValue()) / initialCapital.doubleValue());
        summary.setMaxDrawdown(maxDrawdown);
        summary.setTradeCount(trades.size());
        summary.setWinRate(trades.isEmpty() ? 0.0 : (double) winTrades / (double) trades.size());
        summary.setSharpeRatio(calculateSharpeRatio(trades));
        summary.setLongTradeCount(longTradeCount);
        summary.setShortTradeCount(shortTradeCount);
        summary.setLongWinRate(longTradeCount == 0 ? 0.0 : (double) longWinCount / longTradeCount);
        summary.setShortWinRate(shortTradeCount == 0 ? 0.0 : (double) shortWinCount / shortTradeCount);
        summary.setFilteredEntryCount(filteredEntryCount);
        summary.setRegimeBlockedCount(regimeBlockedCount);
        summary.setTrades(trades);
        BacktestDiagnosticCollector collector = BacktestDiagnosticCollector.fromConfig(config);
        if (trades.isEmpty()) {
            List<BacktestResultResponse.DiagnosticLogDto> logs = collector == null
                    ? new ArrayList<BacktestResultResponse.DiagnosticLogDto>()
                    : collector.snapshotLogs();
            if (logs.isEmpty()) {
                logs.add(DiagnosticMessages.noTradeFallback());
            }
            summary.setDiagnosticLogs(logs);
        } else if (collector != null) {
            List<BacktestResultResponse.DiagnosticLogDto> logs = collector.snapshotLogs();
            if (!logs.isEmpty()) {
                summary.setDiagnosticLogs(logs);
            }
        }
        return summary;
    }

    private BacktestRunSummary runTradingViewOrderIntentExecution(
            List<MdKline> baseKlines,
            Map<String, List<MdKline>> tfMap,
            String baseInterval,
            Map<String, double[]> baseIndicators,
            Map<String, Map<String, double[]>> indicatorsByTf,
            Strategy strategy,
            Map<String, Object> config,
            double fee,
            LocalDateTime tradeStartTime) {
        List<TradingViewLot> lots = new ArrayList<TradingViewLot>();
        double maxEquity = 0.0;
        double maxDrawdown = 0.0;

        for (int i = 0; i < baseKlines.size(); i++) {
            MdKline current = baseKlines.get(i);
            MdKline previous = i > 0 ? baseKlines.get(i - 1) : null;
            Map<String, Integer> tfIndices = alignIndices(current.getOpenTime(), tfMap, baseInterval, i);
            StrategyContext context = new StrategyContext(
                    i,
                    current,
                    previous,
                    baseKlines,
                    baseIndicators,
                    tfMap,
                    tfIndices,
                    indicatorsByTf
            );

            LiveSignalContext.clear();
            StrategySignal signal = strategy.evaluate(context, config);
            List<LiveSignalContext.OrderIntent> orderIntents = LiveSignalContext.getOrderIntents();
            boolean entryWindowOpen = tradeStartTime == null || !current.getOpenTime().isBefore(tradeStartTime);
            if (entryWindowOpen && signal == StrategySignal.BUY && !orderIntents.isEmpty()) {
                double entryPrice = current.getClosePrice().doubleValue();
                int orderCount = orderIntents.size();
                String orderReasons = orderIntents.stream()
                        .map(LiveSignalContext.OrderIntent::reason)
                        .collect(java.util.stream.Collectors.joining(","));
                for (LiveSignalContext.OrderIntent intent : orderIntents) {
                    double notional = Math.max(0.0, intent.quantity());
                    if (notional <= EPS || entryPrice <= EPS) {
                        continue;
                    }
                    lots.add(new TradingViewLot(
                            current.getOpenTime(),
                            entryPrice,
                            notional,
                            notional / entryPrice,
                            intent.reason(),
                            intent.label(),
                            intent.quantity(),
                            orderCount,
                            orderReasons));
                }
            }

            double close = current.getClosePrice().doubleValue();
            double equity = 0.0;
            for (TradingViewLot lot : lots) {
                if (lot.entryTime().isAfter(current.getOpenTime())) {
                    continue;
                }
                double markValue = lot.quantity() * close;
                equity += markValue - (lot.notional() * fee) - (markValue * fee);
            }
            if (equity > maxEquity) {
                maxEquity = equity;
            }
            if (maxEquity > 0.0) {
                maxDrawdown = Math.max(maxDrawdown, (maxEquity - equity) / maxEquity);
            }
        }

        MdKline finalBar = baseKlines.get(baseKlines.size() - 1);
        double finalClose = finalBar.getClosePrice().doubleValue();
        List<TradeRecord> trades = new ArrayList<TradeRecord>();
        int winTrades = 0;
        double deployedNotional = 0.0;
        double netPnl = 0.0;
        for (TradingViewLot lot : lots) {
            double grossPnl = (finalClose - lot.entryPrice()) * lot.quantity();
            double exitValue = finalClose * lot.quantity();
            double entryFee = lot.notional() * fee;
            double exitFee = exitValue * fee;
            double lotNetPnl = grossPnl - entryFee - exitFee;

            TradeRecord trade = new TradeRecord();
            trade.setEntryTime(lot.entryTime());
            trade.setExitTime(finalBar.getOpenTime());
            trade.setEntryPrice(lot.entryPrice());
            trade.setExitPrice(finalClose);
            trade.setQuantity(lot.quantity());
            trade.setGrossPnl(grossPnl);
            trade.setNetPnl(lotNetPnl);
            trade.setReturnPct(lot.entryPrice() > 0.0 ? (finalClose - lot.entryPrice()) / lot.entryPrice() : 0.0);
            trade.setExitReason("TRADINGVIEW_MARK_TO_MARKET_END");
            trade.setSide(PositionSide.LONG.name());
            trade.setBorrowingCost(0.0);
            trade.setReleasedNotional(lot.notional());
            trade.setEntryReason(lot.reason());
            trade.setEntryLabel(lot.label());
            trade.setEntryRequestedQuantity(lot.requestedQuantity());
            trade.setEntryOrderCount(lot.orderCount());
            trade.setEntryOrderReasons(lot.orderReasons());
            trades.add(trade);

            deployedNotional += lot.notional();
            netPnl += lotNetPnl;
            if (lotNetPnl > 0.0) {
                winTrades++;
            }
        }

        double initial = deployedNotional > 0.0 ? deployedNotional : 0.0;
        BacktestRunSummary summary = new BacktestRunSummary();
        summary.setInitialCapital(initial);
        summary.setFinalCapital(initial + netPnl);
        summary.setTotalReturn(initial > 0.0 ? netPnl / initial : 0.0);
        summary.setMaxDrawdown(maxDrawdown);
        summary.setTradeCount(trades.size());
        summary.setWinRate(trades.isEmpty() ? 0.0 : (double) winTrades / (double) trades.size());
        summary.setSharpeRatio(calculateSharpeRatio(trades));
        summary.setLongTradeCount(trades.size());
        summary.setShortTradeCount(0);
        summary.setLongWinRate(trades.isEmpty() ? 0.0 : (double) winTrades / (double) trades.size());
        summary.setShortWinRate(0.0);
        summary.setFilteredEntryCount(0);
        summary.setRegimeBlockedCount(0);
        summary.setTrades(trades);
        BacktestDiagnosticCollector collector = BacktestDiagnosticCollector.fromConfig(config);
        if (trades.isEmpty()) {
            List<BacktestResultResponse.DiagnosticLogDto> logs = collector == null
                    ? new ArrayList<BacktestResultResponse.DiagnosticLogDto>()
                    : collector.snapshotLogs();
            if (logs.isEmpty()) {
                logs.add(DiagnosticMessages.noTradeFallback());
            }
            summary.setDiagnosticLogs(logs);
        } else if (collector != null) {
            List<BacktestResultResponse.DiagnosticLogDto> logs = collector.snapshotLogs();
            if (!logs.isEmpty()) {
                summary.setDiagnosticLogs(logs);
            }
        }
        return summary;
    }

    private TradeRecord closePartial(Position pos,
                                     MdKline candle,
                                     double exitPrice,
                                     double qty,
                                     double feeRate,
                                     String reason,
                                     PositionSide side,
                                     double hourlyBorrowingRate) {
        double closeQty = Math.min(Math.max(qty, 0.0), pos.quantity);
        double grossPnl = pos.side == PositionSide.LONG
                ? (exitPrice - pos.entryPrice) * closeQty
                : (pos.entryPrice - exitPrice) * closeQty;
        double sellValue = exitPrice * closeQty;
        double sellFee = sellValue * feeRate;

        // 幣安借貨利息：僅 SHORT 倉收取，最少計 1 小時，向上取整
        double borrowingCost = 0.0;
        if (side == PositionSide.SHORT && hourlyBorrowingRate > 0.0) {
            long minutesHeld = Duration.between(pos.entryTime, candle.getOpenTime()).toMinutes();
            long hoursCharged = Math.max(1L, (long) Math.ceil(minutesHeld / 60.0));
            borrowingCost = pos.entryPrice * closeQty * hourlyBorrowingRate * hoursCharged;
        }

        double netPnl = grossPnl - sellFee - borrowingCost;
        double returnPct = pos.side == PositionSide.LONG
                ? (exitPrice - pos.entryPrice) / pos.entryPrice
                : (pos.entryPrice - exitPrice) / pos.entryPrice;

        double ratio = pos.initialQuantity > 0.0 ? closeQty / pos.initialQuantity : 0.0;
        double releasedNotional = pos.initialNotional * ratio;

        TradeRecord trade = new TradeRecord();
        trade.setEntryTime(pos.entryTime);
        trade.setExitTime(candle.getOpenTime());
        trade.setEntryPrice(pos.entryPrice);
        trade.setExitPrice(exitPrice);
        trade.setQuantity(closeQty);
        trade.setGrossPnl(grossPnl);
        trade.setNetPnl(netPnl);
        trade.setReturnPct(returnPct);
        trade.setExitReason(reason);
        trade.setSide(side.name());
        trade.setBorrowingCost(borrowingCost);
        trade.setEntryReason(pos.entryReason);
        trade.setEntryLabel(pos.entryLabel);
        trade.setEntryRequestedQuantity(pos.entryRequestedQuantity);
        trade.setEntryOrderCount(pos.entryOrderCount);
        trade.setEntryOrderReasons(pos.entryOrderReasons);

        pos.quantity -= closeQty;
        pos.notional -= releasedNotional;
        if (pos.quantity < EPS) {
            pos.quantity = 0.0;
        }
        if (pos.notional < EPS) {
            pos.notional = 0.0;
        }
        trade.setReleasedNotional(releasedNotional);

        // V047 — carry entry-time indicator snapshot from Position into the
        // per-trade record. Each partial-close of the same position keeps the
        // same snapshot (entry state, not exit state).
        trade.setAdx14(pos.adx14);
        trade.setRsi14(pos.rsi14);
        trade.setAtrPct(pos.atrPct);
        trade.setDd20barPct(pos.dd20barPct);
        trade.setDd50barPct(pos.dd50barPct);
        trade.setMomentum50barPct(pos.momentum50barPct);
        trade.setRealizedVol20bar(pos.realizedVol20bar);
        trade.setDistFromEma200Pct(pos.distFromEma200Pct);
        trade.setRangePct50bar(pos.rangePct50bar);
        // V050 HTF (live engine writes nulls until cross-TF kline loading is wired)
        trade.setHtfMomentum50barPct(pos.htfMomentum50barPct);
        trade.setHtfTrendUp(pos.htfTrendUp);
        trade.setHtfDistEma50Pct(pos.htfDistEma50Pct);
        trade.setVolumeRatioMa20(pos.volumeRatioMa20);
        trade.setCloseVsEma50Pct(pos.closeVsEma50Pct);
        trade.setEma20SlopePct(pos.ema20SlopePct);
        trade.setBbWidthPct(pos.bbWidthPct);

        return trade;
    }

    private Position openPosition(PositionSide side,
                                  MdKline current,
                                  double entryPrice,
                                  double cash,
                                  double fee,
                                  List<MdKline> baseKlines,
                                  int index,
                                  Map<String, Object> config,
                                  List<LiveSignalContext.OrderIntent> orderIntents) {
        double openFee = cash * fee;
        double investable = cash - openFee;
        if (investable <= 0.0) {
            return null;
        }
        Position pos = new Position();
        pos.side = side;
        pos.entryTime = current.getOpenTime();
        pos.entryPrice = entryPrice;
        pos.initialNotional = investable;
        pos.notional = investable;
        pos.initialQuantity = investable / entryPrice;
        pos.quantity = pos.initialQuantity;
        attachEntryOrderIntent(pos, orderIntents);

        EntryPlan plan = buildEntryPlan(side, baseKlines, index, entryPrice, config);
        pos.stopLossPrice = plan.stopLossPrice;
        pos.takeProfit1Price = plan.takeProfit1Price;
        pos.takeProfit2Price = plan.takeProfit2Price;

        // ─── V047 compute indicator snapshot at entry bar ─────────────────
        // We reuse the same IndicatorUtils functions the strategy already
        // used for signal emission, so training set features match what the
        // strategy actually saw.  Wrapped in try/catch so ML feature gaps
        // never break backtest execution; a NaN/None value becomes NULL in
        // the DB and HeatWave ML handles null inputs.
        try {
            attachEntrySnapshot(pos, baseKlines, index);
        } catch (Throwable t) {
            // never fail a trade just because snapshot broke
        }
        return pos;
    }

    private void attachEntryOrderIntent(Position pos, List<LiveSignalContext.OrderIntent> orderIntents) {
        if (orderIntents == null || orderIntents.isEmpty()) {
            return;
        }
        LiveSignalContext.OrderIntent primary = orderIntents.get(0);
        pos.entryReason = primary.reason();
        pos.entryLabel = primary.label();
        pos.entryRequestedQuantity = primary.quantity();
        pos.entryOrderCount = orderIntents.size();
        pos.entryOrderReasons = orderIntents.stream()
                .map(LiveSignalContext.OrderIntent::reason)
                .collect(java.util.stream.Collectors.joining(","));
    }

    /** Delegates to {@link EntryFeatureSnapshot#compute} so backfill uses identical logic. */
    private void attachEntrySnapshot(Position pos, List<MdKline> klines, int idx) {
        Map<String, Double> snap = EntryFeatureSnapshot.compute(klines, idx);
        pos.adx14             = snap.get(EntryFeatureSnapshot.ADX14);
        pos.rsi14             = snap.get(EntryFeatureSnapshot.RSI14);
        pos.atrPct            = snap.get(EntryFeatureSnapshot.ATR_PCT);
        pos.volumeRatioMa20   = snap.get(EntryFeatureSnapshot.VOLUME_RATIO_MA20);
        pos.closeVsEma50Pct   = snap.get(EntryFeatureSnapshot.CLOSE_VS_EMA50_PCT);
        pos.ema20SlopePct     = snap.get(EntryFeatureSnapshot.EMA20_SLOPE_PCT);
        pos.bbWidthPct        = snap.get(EntryFeatureSnapshot.BB_WIDTH_PCT);
        pos.dd20barPct        = snap.get(EntryFeatureSnapshot.DD_20BAR_PCT);
        pos.dd50barPct        = snap.get(EntryFeatureSnapshot.DD_50BAR_PCT);
        pos.momentum50barPct  = snap.get(EntryFeatureSnapshot.MOMENTUM_50BAR_PCT);
        pos.realizedVol20bar  = snap.get(EntryFeatureSnapshot.REALIZED_VOL_20BAR);
        pos.distFromEma200Pct = snap.get(EntryFeatureSnapshot.DIST_FROM_EMA200_PCT);
        pos.rangePct50bar     = snap.get(EntryFeatureSnapshot.RANGE_PCT_50BAR);
    }

    private EntryPlan buildEntryPlan(PositionSide side,
                                     List<MdKline> klines,
                                     int index,
                                     double entryPrice,
                                     Map<String, Object> config) {
        boolean dynamic = getBoolean(config, "dynamicLevelsEnabled", false);
        // 支援 fixedStopLossPct 和 stopLossPct 兩種鍵名，優先使用 fixedStopLossPct
        double stopLossPct = getDouble(config, "fixedStopLossPct",
                getDouble(config, "stopLossPct", 0.03));
        // 支援 fixedTakeProfitPct 和 takeProfitPct 兩種鍵名，優先使用 fixedTakeProfitPct
        double takeProfitPct = getDouble(config, "fixedTakeProfitPct",
                getDouble(config, "takeProfitPct", 0.06));

        // ATR 動態初始 SL/TP（優先於固定值）
        boolean atrDynamicApplied = false;
        double atrSlMult = getDouble(config, "atrSlMultiplier", 0.0);
        double atrTpMult = getDouble(config, "atrTpMultiplier", 0.0);
        if ((atrSlMult > 0 || atrTpMult > 0) && index >= 1) {
            int atrP = getInt(config, "atrPeriod", 14);
            double atrPct = computeAtrPct(klines, index, atrP, entryPrice);
            if (atrPct > 0) {
                if (atrSlMult > 0) stopLossPct = Math.max(0.003, Math.min(0.08, atrPct * atrSlMult));

                // ── ATR Spike 收斂（與 LiveSignalEvaluator 邏輯一致，讓回測能驗證參數）──
                // SL 用 current ATR（完整保護），TP 改用 baseline ATR（防止崩跌後掛出月球射程）
                if (atrTpMult > 0) {
                    double tpAtrPct = atrPct;  // 預設：TP 用 current ATR
                    double spikeMultiple     = getDouble(config, "atrSpikeMultiple",      2.0);
                    double convergenceFactor = getDouble(config, "atrSpikeTpConvergence", 1.5);
                    // atrSpikeConvergenceEnabled 預設 true（0=關閉 / 1=開啟，用 double 避免引入新 helper）
                    boolean spikeEnabled = getDouble(config, "atrSpikeConvergenceEnabled", 1.0) != 0.0;
                    if (spikeEnabled) {
                        double baselineAtrPct = computeBaselineAtrPct(klines, index);
                        if (baselineAtrPct > 0 && atrPct > baselineAtrPct * spikeMultiple) {
                            tpAtrPct = baselineAtrPct * convergenceFactor;
                        }
                    }
                    takeProfitPct = Math.max(0.005, Math.min(0.20, tpAtrPct * atrTpMult));

                    // R:R floor：TP ≥ SL × minRiskReward
                    double minRR    = getDouble(config, "minRiskReward", 1.5);
                    double tpFloor  = stopLossPct * minRR;
                    if (takeProfitPct < tpFloor) takeProfitPct = Math.min(0.20, tpFloor);
                }
                // ──────────────────────────────────────────────────────────────────────
                atrDynamicApplied = true;
            }
        }

        double rr2 = getDouble(config, "tp2RiskReward", 3.0);

        // 檢查用戶是否明確設定了固定止損/止盈
        // 如果用戶明確設定了，則忽視 dynamicLevelsEnabled，強制用固定值
        boolean hasExplicitFixedSL = config.containsKey("fixedStopLossPct");
        boolean hasExplicitFixedTP = config.containsKey("fixedTakeProfitPct");
        boolean useFixed = !dynamic || index <= 1 || hasExplicitFixedSL || hasExplicitFixedTP || atrDynamicApplied;

        EntryPlan plan = new EntryPlan();
        if (useFixed) {
            if (side == PositionSide.LONG) {
                plan.stopLossPrice = entryPrice * (1.0 - stopLossPct);
                plan.takeProfit1Price = entryPrice * (1.0 + takeProfitPct);
                plan.takeProfit2Price = entryPrice * (1.0 + Math.max(takeProfitPct * rr2, takeProfitPct * 1.5));
            } else {
                plan.stopLossPrice = entryPrice * (1.0 + stopLossPct);
                plan.takeProfit1Price = entryPrice * (1.0 - takeProfitPct);
                plan.takeProfit2Price = entryPrice * (1.0 - Math.max(takeProfitPct * rr2, takeProfitPct * 1.5));
            }
            return plan;
        }

        int lookback = Math.max(10, getInt(config, "keyLevelLookbackBars", 48));
        int start = Math.max(0, index - lookback);
        double buffer = getDouble(config, "keyLevelBufferPct", 0.001);
        double minRr = getDouble(config, "minRR", 2.0);
        double support = minLow(klines, start, index - 1);
        double resistance = maxHigh(klines, start, index - 1);

        if (side == PositionSide.LONG) {
            plan.stopLossPrice = support * (1.0 - buffer);
            if (plan.stopLossPrice >= entryPrice) {
                plan.stopLossPrice = entryPrice * (1.0 - stopLossPct);
            }
            double risk = entryPrice - plan.stopLossPrice;
            plan.takeProfit1Price = resistance * (1.0 - buffer);
            if (plan.takeProfit1Price <= entryPrice) {
                plan.takeProfit1Price = entryPrice + risk * Math.max(1.2, minRr);
            }
            plan.takeProfit2Price = entryPrice + risk * Math.max(rr2, minRr + 1.0);
            if (plan.takeProfit2Price <= plan.takeProfit1Price) {
                plan.takeProfit2Price = plan.takeProfit1Price + risk;
            }
        } else {
            plan.stopLossPrice = resistance * (1.0 + buffer);
            if (plan.stopLossPrice <= entryPrice) {
                plan.stopLossPrice = entryPrice * (1.0 + stopLossPct);
            }
            double risk = plan.stopLossPrice - entryPrice;
            plan.takeProfit1Price = support * (1.0 + buffer);
            if (plan.takeProfit1Price >= entryPrice) {
                plan.takeProfit1Price = entryPrice - risk * Math.max(1.2, minRr);
            }
            plan.takeProfit2Price = entryPrice - risk * Math.max(rr2, minRr + 1.0);
            if (plan.takeProfit2Price >= plan.takeProfit1Price) {
                plan.takeProfit2Price = plan.takeProfit1Price - risk;
            }
        }
        return plan;
    }

    private double computeAtrPct(List<MdKline> klines, int index, int period, double entryPrice) {
        int start = Math.max(1, index - period + 1);
        double sum = 0;
        int count = 0;
        for (int i = start; i <= index; i++) {
            double prevClose = klines.get(i - 1).getClosePrice().doubleValue();
            double high = klines.get(i).getHighPrice().doubleValue();
            double low = klines.get(i).getLowPrice().doubleValue();
            double tr = Math.max(high - low,
                    Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            sum += tr;
            count++;
        }
        double atr = count > 0 ? sum / count : 0;
        return entryPrice > 0 ? atr / entryPrice : 0;
    }

    /**
     * 計算 baseline ATR%：取當前位置往前最多 50 根 bar 的 (high-low)/close%，
     * 排序後取中位數。中位數對離群值（崩跌 spike bar）免疫，代表「正常波動水位」。
     * 與 {@code buildMarketSnapshot()} 使用相同算法，確保 Backtest/Live 一致。
     */
    private double computeBaselineAtrPct(List<MdKline> klines, int index) {
        int lookback = Math.min(50, index + 1);
        if (lookback < 5) return 0;
        double[] ranges = new double[lookback];
        for (int i = 0; i < lookback; i++) {
            MdKline k = klines.get(index - lookback + 1 + i);
            double close = k.getClosePrice().doubleValue();
            double range = k.getHighPrice().subtract(k.getLowPrice()).doubleValue();
            ranges[i] = close > 0 ? range / close * 100 : 0;
        }
        java.util.Arrays.sort(ranges);
        return lookback % 2 == 0
                ? (ranges[lookback / 2 - 1] + ranges[lookback / 2]) / 2.0
                : ranges[lookback / 2];
    }

    private Map<String, Integer> alignIndices(LocalDateTime time,
                                              Map<String, List<MdKline>> timeframeKlines,
                                              String baseInterval,
                                              int baseIndex) {
        Map<String, Integer> map = new HashMap<String, Integer>();
        for (Map.Entry<String, List<MdKline>> entry : timeframeKlines.entrySet()) {
            String tf = entry.getKey();
            if (baseInterval.equals(tf)) {
                map.put(tf, baseIndex);
            } else {
                map.put(tf, findAlignedIndex(entry.getValue(), time));
            }
        }
        return map;
    }

    private int findAlignedIndex(List<MdKline> list, LocalDateTime time) {
        if (list == null || list.isEmpty()) {
            return -1;
        }
        int lo = 0;
        int hi = list.size() - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = lo + ((hi - lo) >>> 1);
            LocalDateTime t = list.get(mid).getOpenTime();
            if (!t.isAfter(time)) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    /** Shared by backtest, live evaluation, and read-only MCP signal previews. */
    public Map<String, double[]> buildIndicators(List<MdKline> klines, Map<String, Object> config) {
        double[] closePrices = klines.stream()
                .map(MdKline::getClosePrice)
                .mapToDouble(BigDecimal::doubleValue)
                .toArray();
        double[] highPrices = klines.stream()
                .map(MdKline::getHighPrice)
                .mapToDouble(BigDecimal::doubleValue)
                .toArray();
        double[] lowPrices = klines.stream()
                .map(MdKline::getLowPrice)
                .mapToDouble(BigDecimal::doubleValue)
                .toArray();
        double[] volumes = klines.stream()
                .map(MdKline::getVolume)
                .mapToDouble(BigDecimal::doubleValue)
                .toArray();

        boolean tradingViewParity = getBoolean(config, "tradingViewParityMode", false);
        int emaFastPeriod = getInt(config, "emaFast", 12);
        int emaSlowPeriod = getInt(config, "emaSlow", 26);
        int ema20Period = getInt(config, "ema20Period", 20);
        int rsiPeriod = tradingViewParity ? 14 : getInt(config, "rsiPeriod", 14);
        int adxPeriod = getInt(config, "adxPeriod", 14);
        int atrPeriod = getInt(config, "atrPeriod", 14);
        int bollPeriod = tradingViewParity ? 20 : getInt(config, "bollPeriod", 20);
        double bollStd = tradingViewParity ? 2.0 : getDouble(config, "bollStd", 2.0);
        int volumeMaPeriod = getInt(config, "volumeMaPeriod", 5);
        int macdFast = tradingViewParity ? 12 : getInt(config, "macdFast", 12);
        int macdSlow = tradingViewParity ? 26 : getInt(config, "macdSlow", 26);
        int macdSignal = tradingViewParity ? 9 : getInt(config, "macdSignal", 9);

        Map<String, double[]> indicators = new HashMap<String, double[]>();
        indicators.put("emaFast", IndicatorUtils.ema(closePrices, emaFastPeriod));
        indicators.put("emaSlow", IndicatorUtils.ema(closePrices, emaSlowPeriod));
        indicators.put("ema20", IndicatorUtils.ema(closePrices, ema20Period));
        indicators.put("rsi", IndicatorUtils.rsi(closePrices, rsiPeriod));
        double[] macdLine = IndicatorUtils.macdLine(closePrices, macdFast, macdSlow);
        indicators.put("macdLine", macdLine);
        indicators.put("macdSignal", IndicatorUtils.macdSignal(macdLine, macdSignal));
        indicators.put("adx", IndicatorUtils.adx(highPrices, lowPrices, closePrices, adxPeriod));
        indicators.put("atr", IndicatorUtils.atr(highPrices, lowPrices, closePrices, atrPeriod));
        indicators.put("bollMid", IndicatorUtils.bollingerMiddle(closePrices, bollPeriod));
        indicators.put("bollUp", IndicatorUtils.bollingerUpper(closePrices, bollPeriod, bollStd));
        indicators.put("bollLow", IndicatorUtils.bollingerLower(closePrices, bollPeriod, bollStd));
        indicators.put("volumeMa", IndicatorUtils.sma(volumes, volumeMaPeriod));
        indicators.put("sma200", IndicatorUtils.sma(closePrices, 200));
        indicators.put("sma720", IndicatorUtils.sma(closePrices, 720));   // 720h = 30d，用於 CMI regime 過濾
        indicators.put("volumeMa20", IndicatorUtils.sma(volumes, 20));
        TradingViewScoreBuyModel.Series tradingViewSeries =
                TradingViewScoreBuyModel.replay(klines, indicators, config);
        indicators.put(TradingViewScoreBuyModel.NN_OUTPUT_KEY, tradingViewSeries.nnOutput());
        indicators.put(TradingViewScoreBuyModel.NN_SUM_KEY, tradingViewSeries.inputSum());
        indicators.put(TradingViewScoreBuyModel.NN_WEIGHT_KEY, tradingViewSeries.weight());
        indicators.put(TradingViewScoreBuyModel.NN_BIAS_KEY, tradingViewSeries.bias());
        indicators.put(TradingViewScoreBuyModel.HISTORY_COMPLETE_KEY,
                tradingViewSeries.historyComplete());
        return indicators;
    }

    /**
     * Calculates a simplified Sharpe Ratio from per-trade returns.
     * Uses mean(returnPct) / sampleStdDev(returnPct). Returns Double.NaN when fewer than 2 trades.
     */
    private double calculateSharpeRatio(List<TradeRecord> trades) {
        if (trades == null || trades.size() < 2) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (TradeRecord t : trades) {
            sum += t.getReturnPct();
        }
        double mean = sum / trades.size();

        double variance = 0.0;
        for (TradeRecord t : trades) {
            double diff = t.getReturnPct() - mean;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / (trades.size() - 1));
        if (stdDev == 0.0) {
            return Double.NaN;
        }
        return mean / stdDev;
    }

    private double maxHigh(List<MdKline> list, int start, int end) {
        double value = Double.NEGATIVE_INFINITY;
        for (int i = start; i <= end; i++) {
            value = Math.max(value, list.get(i).getHighPrice().doubleValue());
        }
        return value;
    }

    private double minLow(List<MdKline> list, int start, int end) {
        double value = Double.POSITIVE_INFINITY;
        for (int i = start; i <= end; i++) {
            value = Math.min(value, list.get(i).getLowPrice().doubleValue());
        }
        return value;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int getInt(Map<String, Object> config, String key, int defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private String getString(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private LocalDateTime getLocalDateTime(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        try {
            return LocalDateTime.parse(String.valueOf(value));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean getBoolean(Map<String, Object> config, String key, boolean defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private double getDouble(Map<String, Object> config, String key, double defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /**
     * #450 — Build OpenPositionView for strategy.adjustExit() hook.
     * 純讀,不 mutate Position。
     */
    private OpenPositionView buildPositionView(Position pos, com.agora.model.MdKline current,
                                                java.util.Map<String, Object> config) {
        double curClose = current.getClosePrice() == null ? pos.entryPrice
                : current.getClosePrice().doubleValue();
        double pnlPct = pos.side == PositionSide.LONG
                ? (curClose - pos.entryPrice) / pos.entryPrice
                : (pos.entryPrice - curClose) / pos.entryPrice;
        double pnl = pnlPct * pos.initialNotional;
        long ageHours = pos.entryTime == null ? 0
                : java.time.Duration.between(pos.entryTime, current.getOpenTime()).toHours();

        return new OpenPositionView(
                pos.side == PositionSide.LONG ? "LONG" : "SHORT",
                java.math.BigDecimal.valueOf(pos.entryPrice),
                pos.entryTime,
                java.math.BigDecimal.valueOf(pos.takeProfit2Price),
                java.math.BigDecimal.valueOf(pos.stopLossPrice),
                java.math.BigDecimal.valueOf(pos.initialNotional),
                java.math.BigDecimal.valueOf(curClose),
                java.math.BigDecimal.valueOf(pnl),
                java.math.BigDecimal.valueOf(pnlPct),
                ageHours,
                pos.entryIndicatorSnapshot == null
                        ? java.util.Collections.emptyMap()
                        : pos.entryIndicatorSnapshot
        );
    }

    private static class Position {
        private PositionSide side;
        private java.time.LocalDateTime entryTime;
        private double entryPrice;
        /** #305 進場時的指標快照，供 OpenPositionSnapshot 使用。 */
        private java.util.Map<String, Double> entryIndicatorSnapshot;
        private double initialQuantity;
        private double quantity;
        private double initialNotional;
        private double notional;
        private double stopLossPrice;
        private double takeProfit1Price;
        private double takeProfit2Price;
        private boolean tp1Taken;
        private String entryReason;
        private String entryLabel;
        private Double entryRequestedQuantity;
        private Integer entryOrderCount;
        private String entryOrderReasons;

        // ─── V047 indicator snapshot at entry time ────────────────────────
        // Copied onto TradeRecord in closePartial(); used by ML signal_scorer
        // (vw_signal_training_v2/v3). NaN encoded as null so HeatWave ML sees
        // missing-ness, not 0.
        private Double adx14;
        private Double rsi14;
        private Double atrPct;
        private Double volumeRatioMa20;
        private Double closeVsEma50Pct;
        private Double ema20SlopePct;
        private Double bbWidthPct;
        // V049 regime features (rolling / position-in-trend)
        private Double dd20barPct;
        private Double dd50barPct;
        private Double momentum50barPct;
        private Double realizedVol20bar;
        private Double distFromEma200Pct;
        private Double rangePct50bar;
        // V050 HTF features (NULL until BacktestEngine wires cross-TF kline loading)
        private Double htfMomentum50barPct;
        private Integer htfTrendUp;
        private Double htfDistEma50Pct;
    }

    private static class EntryPlan {
        private double stopLossPrice;
        private double takeProfit1Price;
        private double takeProfit2Price;
    }

    private record TradingViewLot(LocalDateTime entryTime,
                                  double entryPrice,
                                  double notional,
                                  double quantity,
                                  String reason,
                                  String label,
                                  double requestedQuantity,
                                  int orderCount,
                                  String orderReasons) {
    }

    private static final double EPS = 1e-12;

    private enum PositionSide {
        LONG,
        SHORT
    }
}
