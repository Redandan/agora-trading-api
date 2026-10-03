package com.agora.research;

import com.agora.model.MdKline;
import com.agora.service.trading.BtcDraBootstrapEntryStateReplayer;
import com.agora.service.trading.BtcDraExecutionContract;
import com.agora.service.trading.BtcDraShadowEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import static com.agora.service.trading.BtcDraPolicy.*;

/**
 * Fixed historical diagnostic of the current virtual-signal/single-live-lot contract.
 * Plain Java only: no Spring startup, repository, network, provider, or activation.
 * Next hourly open is an explicit proxy for the unavailable evaluation-time ticker.
 */
public final class BtcDraLiveContractReplayCli {
    public static final String INPUT_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd";
    private static final BigDecimal CAPITAL = new BigDecimal("30.00");
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2025, 1, 1, 0, 0);
    private BtcDraLiveContractReplayCli() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: <sealed-selection.tsv> <new-output.json>");
        Path output = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("OUTPUT_ALREADY_EXISTS");
        List<MdKline> bars = load(Path.of(args[0]));
        Map<String, Object> windows = new LinkedHashMap<>();
        for (int y = 2020; y <= 2024; y++) windows.put(Integer.toString(y), replay(bars,
                LocalDateTime.of(y, 1, 1, 0, 0), LocalDateTime.of(y + 1, 1, 1, 0, 0)));
        windows.put("validation", replay(bars, LocalDateTime.of(2023, 1, 1, 0, 0), CUTOFF));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "DRA_CURRENT_CONTRACT_HISTORICAL_DIAGNOSTIC_V1");
        result.put("status", "REPORTED_NOT_ACTIVATED");
        result.put("candidateVariants", 0);
        result.put("oosOpened", false);
        result.put("inputSha256", INPUT_SHA256);
        result.put("inputRows", bars.size());
        result.put("executionContract", BtcDraExecutionContract.description());
        result.put("priceBasis", "NEXT_HOURLY_OPEN_PROXY_FOR_UNAVAILABLE_EVALUATION_TIME_TICKER");
        result.put("accounting", "30_USDT_INCLUSIVE_BUY_FEE_FIXED_LOT_PROFITS_HELD_CASH_QUANTITY_FLOORED_8DP");
        result.put("actualLiveFillParity", "MISSING_PROOF_NO_HISTORICAL_TICKER_RECEIPTS_OR_OUTAGE_SEQUENCE");
        result.put("limitations", List.of("Already-seen 2020-2024 historical diagnosis, not clean OOS or a return forecast",
                "No actual latency, outage, provider rejection, partial fill, fee delay, min-size or quantity-step reconstruction",
                "2019-start runtime replay unavailable: sealed input lacks the required 90-day bootstrap history",
                "Independent reset per window; annual PnL cannot be added to obtain a continuous strategy result",
                "Cash benchmark has zero yield; custody and currency risks are outside this USDT comparison"));
        result.put("windows", windows);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println(mapper.writeValueAsString(Map.of("status", "PASS", "windows", windows.size(), "output", output.toString())));
    }

    static List<MdKline> load(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        if (!INPUT_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
            throw new IllegalArgumentException("INPUT_HASH_REJECT");
        List<MdKline> bars = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).lines().toList()) {
            String[] f = line.split("\t", -1);
            if (f.length != 7) throw new IllegalArgumentException("INPUT_COLUMNS_REJECT");
            MdKline b = new MdKline();
            b.setSymbol(SYMBOL); b.setSource(SOURCE); b.setIntervalCode(INTERVAL);
            b.setOpenTime(LocalDateTime.parse(f[0])); b.setCloseTime(LocalDateTime.parse(f[1]));
            b.setOpenPrice(new BigDecimal(f[2])); b.setHighPrice(new BigDecimal(f[3]));
            b.setLowPrice(new BigDecimal(f[4])); b.setClosePrice(new BigDecimal(f[5])); b.setVolume(new BigDecimal(f[6]));
            if (!b.getOpenTime().plusHours(1).equals(b.getCloseTime())
                    || !bars.isEmpty() && !bars.getLast().getCloseTime().equals(b.getOpenTime()))
                throw new IllegalArgumentException("HOURLY_LATTICE_REJECT");
            bars.add(b);
        }
        if (bars.size() != 52608 || !bars.getFirst().getOpenTime().equals(LocalDateTime.of(2019, 1, 1, 0, 0))
                || !bars.getLast().getCloseTime().equals(CUTOFF)) throw new IllegalArgumentException("WINDOW_REJECT");
        return List.copyOf(bars);
    }

    static Map<String, Object> replay(List<MdKline> bars, LocalDateTime start, LocalDateTime end) {
        var engine = new BtcDraShadowEngine(new ObjectMapper());
        var bootstrap = new BtcDraBootstrapEntryStateReplayer();
        var state = engine.initialState();
        var entryState = bootstrap.initialState();
        LocalDateTime warmupStart = start.minusHours(BOOTSTRAP_HISTORY_HOURS - 1L);
        List<MdKline> warmup = bars.stream().filter(b -> !b.getOpenTime().isBefore(warmupStart) && b.getOpenTime().isBefore(start)).toList();
        if (warmup.size() != BOOTSTRAP_HISTORY_HOURS - 1) throw new IllegalArgumentException("BOOTSTRAP_HISTORY_INCOMPLETE");
        for (MdKline b : warmup) {
            var step = engine.warmup(state, b); state = step.state();
            entryState = bootstrap.observe(entryState, b.getOpenTime(), step.signal().dailyReversalConfirmed()).state();
        }
        state = engine.seedBootstrapEntryState(state, entryState);
        List<MdKline> trading = bars.stream().filter(b -> !b.getOpenTime().isBefore(start) && b.getOpenTime().isBefore(end)).toList();
        if (trading.isEmpty() || trading.size() != Duration.between(start, end).toHours()) throw new IllegalArgumentException("TRADING_WINDOW_INCOMPLETE");
        BtcDraShadowEngine.StepResult pending = null;
        boolean pendingBootstrap = false;
        Lot lot = null;
        BigDecimal cash = CAPITAL, realized = BigDecimal.ZERO;
        BigDecimal passiveQty = buyQty(trading.getFirst().getOpenPrice());
        PathMetrics dra = new PathMetrics(), btc = new PathMetrics();
        List<Map<String, Object>> ledger = new ArrayList<>(), signals = new ArrayList<>();
        List<Double> holds = new ArrayList<>();
        int buys = 0, sells = 0, occupied = 0, blocked = 0, exitDeferredEntry = 0, bootstrapSuppressed = 0;
        int confirmedButCooldown = 0, queued = 0;
        for (int i = 0; i < trading.size(); i++) {
            MdKline b = trading.get(i);
            if (pending != null) {
                boolean candidate = BtcDraExecutionContract.entryEvent(pending) != null;
                if (candidate) queued++;
                boolean sold = false;
                String disposition = "NO_QUEUED_ENTRY";
                if (pendingBootstrap) {
                    if (candidate) bootstrapSuppressed++;
                    disposition = "BOOTSTRAP_NO_LIVE_EXECUTION";
                } else {
                    if (lot != null && BtcDraExecutionContract.estimatedNetReturn(lot.quantity, lot.effectiveEntry, b.getOpenPrice())
                            .compareTo(NET_PROFIT_TRIGGER) >= 0) {
                        BigDecimal proceeds = net(lot.quantity, b.getOpenPrice());
                        BigDecimal pnl = money(proceeds.subtract(CAPITAL));
                        cash = money(cash.add(proceeds)); realized = money(realized.add(pnl)); sells++; sold = true;
                        double hold = Duration.between(lot.fillTime, b.getOpenTime()).toSeconds() / 3600.0;
                        holds.add(hold);
                        ledger.add(Map.of("status", "CLOSED", "signalBarUtc", lot.signalTime.toString(),
                                "entryFillUtc", lot.fillTime.toString(), "exitFillUtc", b.getOpenTime().toString(),
                                "quantity", lot.quantity, "costUsdt", CAPITAL, "pnlUsdt", pnl, "holdHours", hold));
                        lot = null;
                    }
                    if (candidate) {
                        if (sold) { exitDeferredEntry++; disposition = "DEFERRED_EXIT_HANDLING"; }
                        else if (lot != null) { blocked++; disposition = "BLOCKED:DRA_SINGLE_LOT_ALREADY_OPEN"; }
                        else {
                            if (cash.compareTo(CAPITAL) < 0) throw new IllegalStateException("CASH_INVARIANT");
                            BigDecimal qty = buyQty(b.getOpenPrice());
                            lot = new Lot(qty, CAPITAL.divide(qty, 8, RoundingMode.HALF_UP), b.getOpenTime(),
                                    BtcDraExecutionContract.entryEvent(pending).signalBarOpenTime());
                            cash = money(cash.subtract(CAPITAL)); buys++; disposition = "BUY_PROXY_FILLED";
                        }
                    }
                }
                if (candidate) signals.add(Map.of("signalBarUtc", pending.state().lastProcessedBarOpenTime().toString(),
                        "evaluationProxyUtc", b.getOpenTime().toString(), "disposition", disposition));
            }
            if (lot != null) occupied++;
            BigDecimal equity = money(cash.add(lot == null ? BigDecimal.ZERO : net(lot.quantity, b.getClosePrice())));
            if (cash.signum() < 0 || buys - sells != (lot == null ? 0 : 1)) throw new IllegalStateException("SINGLE_LOT_LEDGER_INVARIANT");
            dra.observe(b, equity); btc.observe(b, net(passiveQty, b.getClosePrice()));
            pending = engine.step(state, b); state = pending.state(); pendingBootstrap = i == 0;
            if (pending.signal().dailyReversalConfirmed() && !pending.signal().entryEligible()
                    && state.lastEntrySignalBarOpenTime() != null
                    && b.getOpenTime().isBefore(state.lastEntrySignalBarOpenTime().plusDays(ENTRY_COOLDOWN_DAYS))) confirmedButCooldown++;
        }
        BigDecimal unrealized = lot == null ? BigDecimal.ZERO : money(net(lot.quantity, trading.getLast().getClosePrice()).subtract(CAPITAL));
        if (lot != null) ledger.add(Map.of("status", "OPEN_CENSORED_AT_END", "signalBarUtc", lot.signalTime.toString(),
                "entryFillUtc", lot.fillTime.toString(), "quantity", lot.quantity, "costUsdt", CAPITAL,
                "pnlUsdt", unrealized, "holdHours", Duration.between(lot.fillTime, end).toHours()));
        BigDecimal ledgerTotal = ledger.stream().map(l -> (BigDecimal) l.get("pnlUsdt")).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (ledgerTotal.compareTo(realized.add(unrealized)) != 0 || dra.last.subtract(CAPITAL).compareTo(ledgerTotal) != 0)
            throw new IllegalStateException("PNL_RECONCILIATION_FAILED");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startUtc", start.toString()); result.put("endExclusiveUtc", end.toString());
        result.put("dra", dra.result(start, end)); result.put("btcBuyHold", btc.result(start, end));
        result.put("cash", Map.of("initialUsdt", CAPITAL, "endingUsdt", CAPITAL, "totalPnlUsdt", 0, "maximumDrawdownPct", 0));
        result.put("realizedUsdt", realized); result.put("unrealizedUsdt", unrealized);
        result.put("buys", buys); result.put("sells", sells); result.put("openLots", buys - sells);
        result.put("virtualQueuedSignalsEvaluated", queued); result.put("singleLotBlockedSignals", blocked);
        result.put("exitHandlingDeferredSignals", exitDeferredEntry); result.put("bootstrapSuppressedSignals", bootstrapSuppressed);
        result.put("confirmedDailyBarsInsideVirtualCooldown", confirmedButCooldown);
        result.put("terminalQueuedSignalNotExecutedOutsideWindow", BtcDraExecutionContract.entryEvent(pending) != null);
        result.put("utilizationPctInitialCapital", BigDecimal.valueOf(occupied).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(trading.size()), 6, RoundingMode.HALF_UP));
        result.put("medianCompletedHoldHours", percentile(holds, .5)); result.put("p90CompletedHoldHours", percentile(holds, .9));
        result.put("terminalOpenAgeHours", lot == null ? null : Duration.between(lot.fillTime, end).toHours());
        result.put("tradeLedger", ledger); result.put("signalLedger", signals);
        result.put("ledgerAndSingleLotChecks", "PASS");
        return result;
    }

    private static BigDecimal money(BigDecimal n) { return n.setScale(8, RoundingMode.HALF_UP); }
    private static BigDecimal buyQty(BigDecimal p) {
        return CAPITAL.subtract(money(CAPITAL.multiply(FEE_RATE_PER_SIDE)))
                .divide(money(p.multiply(BigDecimal.ONE.add(ADVERSE_SLIPPAGE_RATE_PER_SIDE))), 8, RoundingMode.DOWN);
    }
    private static BigDecimal net(BigDecimal qty, BigDecimal p) {
        BigDecimal gross = money(qty.multiply(money(p.multiply(BigDecimal.ONE.subtract(ADVERSE_SLIPPAGE_RATE_PER_SIDE)))));
        return money(gross.subtract(money(gross.multiply(FEE_RATE_PER_SIDE))));
    }
    private static Double percentile(List<Double> values, double p) {
        if (values.isEmpty()) return null;
        var sorted = values.stream().sorted().toList(); double index = (sorted.size() - 1) * p;
        int lo = (int) index, hi = Math.min(lo + 1, sorted.size() - 1);
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (index - lo);
    }
    private record Lot(BigDecimal quantity, BigDecimal effectiveEntry, LocalDateTime fillTime, LocalDateTime signalTime) { }

    private static class PathMetrics {
        BigDecimal peak = CAPITAL, dd = BigDecimal.ZERO, last = CAPITAL;
        double priorDay = CAPITAL.doubleValue();
        List<Double> daily = new ArrayList<>();
        void observe(MdKline bar, BigDecimal equity) {
            peak = peak.max(equity); last = equity;
            dd = dd.max(peak.subtract(equity).divide(peak, 16, RoundingMode.HALF_UP));
            if (bar.getOpenTime().getHour() == 23) { daily.add(equity.doubleValue() / priorDay - 1); priorDay = equity.doubleValue(); }
        }
        Map<String, Object> result(LocalDateTime start, LocalDateTime end) {
            double mean = daily.stream().mapToDouble(x -> x).average().orElseThrow();
            double variance = daily.stream().mapToDouble(x -> Math.pow(x - mean, 2)).sum() / (daily.size() - 1);
            double downside = daily.stream().mapToDouble(x -> Math.pow(Math.min(x, 0), 2)).sum() / daily.size();
            double years = Duration.between(start, end).toHours() / (365.25 * 24);
            double cagr = Math.pow(last.doubleValue() / CAPITAL.doubleValue(), 1 / years) - 1;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("endingEquityUsdt", last); r.put("totalPnlUsdt", money(last.subtract(CAPITAL)));
            r.put("maximumDrawdownPct", dd.multiply(BigDecimal.valueOf(100)).setScale(6, RoundingMode.HALF_UP));
            r.put("cagrPct", cagr * 100); r.put("dailySharpeRf0", variance == 0 ? null : mean / Math.sqrt(variance) * Math.sqrt(365));
            r.put("dailySortinoTarget0", downside == 0 ? null : mean / Math.sqrt(downside) * Math.sqrt(365));
            r.put("calmar", dd.signum() == 0 ? null : cagr / dd.doubleValue());
            return r;
        }
    }
}
