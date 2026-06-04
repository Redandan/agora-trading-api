package com.agora.service.telegram;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public final class PositionDrillDownTelegramButtons {

    public static final String PREFIX = "posd:";
    private static final int MAX_CALLBACK_BYTES = 64;

    private PositionDrillDownTelegramButtons() {
    }

    public record DrillDownRequest(String detailType, String symbol, Long positionId) {
    }

    public static InlineKeyboardMarkup buildKeyboard(String symbol, Long positionId) {
        String sym = normalizeSymbol(symbol);
        String id = positionId == null ? "ALL" : String.valueOf(positionId);
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(List.of(
                                button("OCO狀態", callbackData("oco", sym, id)),
                                button("Trailing", callbackData("trailing", sym, id))
                        )),
                        new InlineKeyboardRow(List.of(
                                button("倉位摘要", callbackData("positions", sym, id)),
                                button("經理Digest", callbackData("digest", sym, id))
                        ))
                ))
                .build();
    }

    public static DrillDownRequest parse(String callbackValue) {
        if (callbackValue == null || callbackValue.isBlank()) return null;
        String value = callbackValue.startsWith(PREFIX)
                ? callbackValue.substring(PREFIX.length())
                : callbackValue;
        String[] parts = value.split(":");
        if (parts.length < 3) return null;
        String detailType = switch (parts[0]) {
            case "o" -> "oco";
            case "t" -> "trailing";
            case "p" -> "positions";
            case "d" -> "digest";
            default -> "positions";
        };
        String symbol = "ALL".equalsIgnoreCase(parts[1]) ? null : normalizeSymbol(parts[1]);
        Long positionId = parsePositionId(parts[2]);
        return new DrillDownRequest(detailType, symbol, positionId);
    }

    public static String callbackData(String detailType, String symbol, String positionId) {
        String type = switch (detailType == null ? "" : detailType.toLowerCase(Locale.ROOT)) {
            case "oco", "oco_status" -> "o";
            case "trailing", "trailing_status" -> "t";
            case "digest", "manager", "position_digest" -> "d";
            default -> "p";
        };
        String data = PREFIX + type + ":" + normalizeSymbol(symbol) + ":" + normalizePositionId(positionId);
        if (data.getBytes(StandardCharsets.UTF_8).length > MAX_CALLBACK_BYTES) {
            throw new IllegalArgumentException("Position callback data exceeds Telegram limit: " + data);
        }
        return data;
    }

    private static InlineKeyboardButton button(String text, String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return "ALL";
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizePositionId(String positionId) {
        if (positionId == null || positionId.isBlank()) return "ALL";
        return positionId.trim();
    }

    private static Long parsePositionId(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
