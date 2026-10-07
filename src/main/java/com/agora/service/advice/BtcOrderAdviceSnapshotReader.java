package com.agora.service.advice;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.repository.trading.SpotExecutionAttemptRepository;
import com.agora.model.SpotExecutionAttempt.State;
import com.agora.service.trading.BtcBasePositionStatePolicy;
import com.agora.service.trading.OkxTradingService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import static com.agora.service.advice.BtcOrderAdviceEngine.*;

@Component
@RequiredArgsConstructor
public class BtcOrderAdviceSnapshotReader {
    private final OkxTradingService okx;
    private final MdKlineRepository klines;
    private final BtLiveSignalRepository positions;
    private final SpotExecutionAttemptRepository attempts;

    public Input read() {
        Instant started = Instant.now();
        Instant hour = started.truncatedTo(ChronoUnit.HOURS);
        var raw = klines.findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                "BTCUSDT", "1h", "okx", LocalDateTime.ofInstant(hour.minus(168, ChronoUnit.HOURS), ZoneOffset.UTC),
                LocalDateTime.ofInstant(hour.minus(1, ChronoUnit.HOURS), ZoneOffset.UTC));
        List<Bar> bars = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        for (var b : raw) {
            if (!"BTCUSDT".equals(b.getSymbol()) || !"okx".equals(b.getSource()) || !"1h".equals(b.getIntervalCode())
                    || b.getCloseTime() == null || b.getOpenTime() == null
                    || b.getCloseTime().isAfter(LocalDateTime.ofInstant(started, ZoneOffset.UTC))
                    || b.getCloseTime().isBefore(b.getOpenTime().plusHours(1).minusSeconds(1))
                    || b.getCloseTime().isAfter(b.getOpenTime().plusHours(1))) {
                blockers.add("行情來源或收盤時間不一致");
                continue;
            }
            bars.add(new Bar(b.getOpenTime().toInstant(ZoneOffset.UTC), b.getOpenPrice(), b.getHighPrice(), b.getLowPrice(), b.getClosePrice()));
        }
        var all = new LinkedHashMap<Long, BtLiveSignal>();
        positions.findByAutoTradedIsTrueAndExitTimeIsNull().forEach(p -> all.put(p.getId(), p));
        positions.findByExitTimeIsNullAndFilterReasonStartingWith(BtcBasePositionStatePolicy.BTC_BASE_PREFIX)
                .forEach(p -> all.put(p.getId(), p));
        BigDecimal legacy = BigDecimal.ZERO, cost = BigDecimal.ZERO, protectedQuantity = BigDecimal.ZERO;
        List<String> identities = new ArrayList<>();
        for (var p : all.values()) {
            if (!"BTCUSDT".equals(p.getSymbol()) || "SHORT".equals(p.getSide())) continue;
            BigDecimal qty = p.getTradedQty();
            if (qty == null || qty.signum() <= 0 || BtcBasePositionStatePolicy.executionUnresolved(p)
                    || BtcBasePositionStatePolicy.isAdoptionPending(p)) {
                blockers.add("有尚未確認的 BTC 持倉，暫停新增建議");
                continue;
            }
            identities.add(p.getId() + ":" + number(qty) + ":" + p.getFilterReason() + ":" + p.getOcoOrderListId());
            if (BtcBasePositionStatePolicy.isAdoptedFromOco(p) && p.getOcoOrderListId() == null && Boolean.TRUE.equals(p.getAutoTraded())) {
                BigDecimal entry = p.getActualEntryPrice() != null ? p.getActualEntryPrice() : p.getEntryPrice();
                if (entry == null || entry.signum() <= 0) { blockers.add("舊持倉成本缺失"); continue; }
                legacy = legacy.add(qty);
                cost = cost.add(qty.multiply(entry));
            } else protectedQuantity = protectedQuantity.add(qty);
        }
        List<PendingOrder> pending = new ArrayList<>(parseOrders(okx.getBtcAdvicePendingOrders(null), false));
        for (String type : List.of("oco", "conditional", "trigger", "move_order_stop"))
            pending.addAll(parseOrders(okx.getBtcAdvicePendingOrders(type), true));
        pending.sort(Comparator.comparing(PendingOrder::id));
        BigDecimal fee = okx.getSpotTakerFeeRate("BTCUSDT");
        JsonNode rules = okx.getBtcAdviceInstrument();
        if (!"BTC-USDT".equals(rules.path("instId").asText()) || !"SPOT".equals(rules.path("instType").asText())
                || !"live".equals(rules.path("state").asText())) blockers.add("BTC 現貨交易規格或交易狀態無效");
        JsonNode balances = okx.getBtcAdviceBalances();
        if (balances.size() != 1 || !balances.get(0).path("details").isArray()) throw new IllegalStateException("Account snapshot incomplete");
        BigDecimal available = BigDecimal.ZERO, cashBtc = BigDecimal.ZERO;
        boolean btcSeen = false;
        for (JsonNode row : balances.get(0).path("details")) {
            if (!"BTC".equals(row.path("ccy").asText())) continue;
            if (btcSeen) throw new IllegalStateException("Duplicate BTC balance");
            btcSeen = true;
            available = decimal(row, "availBal"); cashBtc = decimal(row, "cashBal");
            if (available.signum() < 0 || cashBtc.compareTo(available) < 0) throw new IllegalStateException("Invalid BTC balances");
        }
        // A manual fill/transfer can leave legacy DB lots stale. Do not re-use stale ownership.
        if (cashBtc.add(new BigDecimal("0.00000001")).compareTo(legacy.add(protectedQuantity)) < 0)
            blockers.add("交易所 BTC 與持倉紀錄不一致，需先確認成交或轉出，不能重複建議賣出");
        JsonNode ticker = okx.getSpotTickerSnapshot("BTCUSDT");
        if (!"BTC-USDT".equals(ticker.path("instId").asText())) throw new IllegalStateException("Wrong ticker venue/symbol");
        if (attempts.existsByStateIn(List.of(State.RESERVED, State.SUBMITTING, State.SUBMISSION_UNKNOWN, State.PROVIDER_ACCEPTED)))
            blockers.add("有交易所委託尚未完成對帳，暫停新增建議");
        Instant now = Instant.now();
        if (now.isAfter(started.plusSeconds(30)) || !hour.equals(now.truncatedTo(ChronoUnit.HOURS)))
            blockers.add("資料收集跨越有效時間，請重新查詢");
        identities.sort(String::compareTo);
        String key = fingerprint(String.join("|", identities) + "|" + number(legacy) + "|" + number(cost) + "|"
                + number(available) + "|" + number(cashBtc) + "|" + number(fee) + "|" + rules.path("minSz").asText()
                + "|" + rules.path("lotSz").asText() + "|" + rules.path("tickSz").asText() + "|" + pending);
        return new Input(now, Instant.ofEpochMilli(Long.parseLong(ticker.path("ts").asText())),
                decimal(ticker, "bidPx"), decimal(ticker, "askPx"), decimal(ticker, "last"), List.copyOf(bars),
                fee, decimal(rules, "minSz"), decimal(rules, "lotSz"), decimal(rules, "tickSz"), legacy, cost, available, protectedQuantity,
                List.copyOf(pending), key, List.copyOf(blockers));
    }

    static List<PendingOrder> parseOrders(JsonNode rows, boolean algo) {
        if (!rows.isArray() || rows.size() >= 100) throw new IllegalStateException("Pending order coverage incomplete");
        List<PendingOrder> result = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!"BTC-USDT".equals(row.path("instId").asText())) throw new IllegalStateException("Wrong pending order symbol");
            String id = row.path(algo ? "algoId" : "ordId").asText();
            String side = row.path("side").asText();
            if ("buy".equals(side) && ("quote_ccy".equals(row.path("tgtCcy").asText())
                    || "market".equals(row.path("ordType").asText())))
                throw new IllegalStateException("Quote-sized pending buy requires manual review");
            if (!id.matches("[0-9]+") || !("buy".equals(side) || "sell".equals(side))) throw new IllegalStateException("Invalid pending order");
            BigDecimal quantity = decimal(row, "sz");
            if (!algo) quantity = quantity.subtract(decimal(row, "accFillSz"));
            if (quantity.signum() < 0) throw new IllegalStateException("Invalid pending quantity");
            BigDecimal price = algo ? null : decimal(row, "px");
            result.add(new PendingOrder((algo ? "algo:" : "order:") + id, row.path("ordType").asText(), side,
                    price, quantity, row.path("state").asText()));
        }
        return result;
    }
    static BigDecimal decimal(JsonNode row, String field) {
        String text = row.path(field).asText();
        if (text.isBlank()) throw new IllegalStateException("Missing numeric input: " + field);
        return new BigDecimal(text);
    }
    public static String fingerprint(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String number(BigDecimal v) { return v.stripTrailingZeros().toPlainString(); }
}
