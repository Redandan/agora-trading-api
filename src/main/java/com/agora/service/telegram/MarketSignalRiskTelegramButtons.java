package com.agora.service.telegram;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public final class MarketSignalRiskTelegramButtons {

    public static final String PREFIX = "msr:";
    private static final int MAX_CALLBACK_BYTES = 64;

    private MarketSignalRiskTelegramButtons() {
    }

    public record DrillDownRequest(String detailType, String symbol, int hours, int limit) {
    }

    public static InlineKeyboardMarkup buildKeyboard(String symbol, int hours) {
        String sym = normalizeSymbol(symbol);
        int window = normalizeHours(hours);
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(List.of(
                                button("市場明細", callbackData("market_details", sym, window)),
                                button("訊號分層", callbackData("signal_routes", sym, window))
                        )),
                        new InlineKeyboardRow(List.of(
                                button("目前倉位", callbackData("current_position", sym, window)),
                                button("OCO狀態", callbackData("oco_status", sym, window))
                        )),
                        new InlineKeyboardRow(List.of(
                                button("Trailing", callbackData("trailing_status", sym, window)),
                                button("完整摘要", callbackData("full_summary", sym, window))
                        ))
                ))
                .build();
    }

    public static DrillDownRequest parse(String callbackValue) {
        if (callbackValue == null || callbackValue.isBlank()) {
            return null;
        }
        String value = callbackValue.startsWith(PREFIX)
                ? callbackValue.substring(PREFIX.length())
                : callbackValue;
        String[] parts = value.split(":");
        if (parts.length < 3) {
            return null;
        }

        String detailType = switch (parts[0]) {
            case "m" -> "market_details";
            case "r" -> "signal_routes";
            case "p" -> "current_position";
            case "o" -> "oco_status";
            case "t" -> "trailing_status";
            case "f" -> "full_summary";
            default -> "full_summary";
        };
        String symbol = "ALL".equalsIgnoreCase(parts[1]) ? null : normalizeSymbol(parts[1]);
        int hours = parsePositiveInt(parts[2], 24);
        int limit = switch (detailType) {
            case "full_summary" -> 20;
            default -> 12;
        };
        return new DrillDownRequest(detailType, symbol, normalizeHours(hours), limit);
    }

    public static String callbackData(String detailType, String symbol, int hours) {
        String type = switch (detailType == null ? "" : detailType) {
            case "market_details", "market" -> "m";
            case "signal_routes", "routes" -> "r";
            case "current_position", "position", "positions" -> "p";
            case "oco_status", "oco" -> "o";
            case "trailing_status", "trailing" -> "t";
            default -> "f";
        };
        String data = PREFIX + type + ":" + normalizeSymbol(symbol) + ":" + normalizeHours(hours);
        if (data.getBytes(StandardCharsets.UTF_8).length > MAX_CALLBACK_BYTES) {
            throw new IllegalArgumentException("Market Signal Risk callback data exceeds Telegram limit: " + data);
        }
        return data;
    }

    private static InlineKeyboardButton button(String text, String callbackData) {
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(callbackData)
                .build();
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "ALL";
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static int normalizeHours(int hours) {
        return hours <= 0 || hours > 168 ? 24 : hours;
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
