package com.agora.service.trading.evidence.okx;

import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RawFill;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class ExactTradeFillHashing {
    private ExactTradeFillHashing() { }

    public static String identity(RawFill f) {
        return hash(f.provider(), f.accountRefHash(), f.orderId(), f.tradeId());
    }

    public static String content(RawFill f) {
        return hash(f.provider(), f.accountRefHash(), f.instrumentId(), f.instrumentType(), f.orderId(),
                f.tradeId(), f.billId(), text(f.fillAt()), f.side(), text(f.fillPrice()), text(f.fillQuantity()),
                text(f.signedFeeAmount()), f.feeCurrency(), f.liquidityRole(), f.rawPayloadSha256(),
                f.cohortId(), text(f.runtimeDecisionId()), text(f.liveSignalId()),
                f.intendedChildOrderId(), f.actualChildOrderId());
    }

    public static String fillSet(List<RawFill> fills) {
        return hash(fills.stream().map(ExactTradeFillHashing::content).sorted().toArray(String[]::new));
    }

    public static String bindingScope(Instant effectiveFrom,
                               Map<String, ExactTradeFillCollectionService.FillBinding> bindings) {
        String[] canonical = bindings.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> hash(e.getKey(), e.getValue().cohortId(),
                        text(e.getValue().runtimeDecisionId()), text(e.getValue().liveSignalId()),
                        text(e.getValue().orderCreatedAt()), Boolean.toString(e.getValue().ocoRequired()),
                        e.getValue().intendedChildOrderId(), e.getValue().actualChildOrderId()))
                .toArray(String[]::new);
        return hash(text(effectiveFrom), hash(canonical));
    }

    public static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = text(value).getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) ';');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String text(Object value) {
        if (value == null) return "";
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        return value.toString();
    }
}
