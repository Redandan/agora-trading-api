package com.agora.service.trading.evidence.okx;

import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RawFill;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

final class ExactTradeFillHashing {
    private ExactTradeFillHashing() { }

    static String identity(RawFill f) {
        return hash(f.provider(), f.accountRefHash(), f.orderId(), f.tradeId());
    }

    static String content(RawFill f) {
        return hash(f.provider(), f.accountRefHash(), f.instrumentId(), f.instrumentType(), f.orderId(),
                f.tradeId(), f.billId(), text(f.fillAt()), f.side(), text(f.fillPrice()), text(f.fillQuantity()),
                text(f.signedFeeAmount()), f.feeCurrency(), f.liquidityRole(), f.rawPayloadSha256(),
                f.cohortId(), text(f.runtimeDecisionId()), text(f.liveSignalId()),
                f.intendedChildOrderId(), f.actualChildOrderId());
    }

    static String fillSet(List<RawFill> fills) {
        return hash(fills.stream().map(RawFill::contentSha256).sorted().toArray(String[]::new));
    }

    static String hash(String... values) {
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

    private static String text(Object value) { return value == null ? "" : value.toString(); }
}
