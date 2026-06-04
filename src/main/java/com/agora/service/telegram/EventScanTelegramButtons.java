package com.agora.service.telegram;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.List;
import java.util.Locale;

public final class EventScanTelegramButtons {

    public static final String PREFIX = "evs:";
    private static final int MAX_CALLBACK_BYTES = 64;

    private EventScanTelegramButtons() {
    }

    public record DrillDownRequest(String detailType, String symbol, int minutes, int limit) {
    }

    public static InlineKeyboardMarkup buildKeyboard(String symbol, int minutes) {
        String sym = normalizeSymbol(symbol);
        int window = normalizeMinutes(minutes);
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(List.of(
                                button("BUY明細", callbackData("buy_details", sym, window)),
                                button("Skip原因", callbackData("skip_reasons", sym, window))
                        )),
                        new InlineKeyboardRow(List.of(
                                button("目前倉位", callbackData("current_position", sym, window)),
                                button("完整掃描", callbackData("full_scan", sym, window))
                        )),
                        new InlineKeyboardRow(List.of(
                                button("OCO狀態", callbackData("oco_status", sym, window)),
                                button("Trailing", callbackData("trailing_status", sym, window))
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
            case "b" -> "buy_details";
            case "s" -> "skip_reasons";
            case "p" -> "current_position";
            case "o" -> "oco_status";
            case "t" -> "trailing_status";
            case "f" -> "full_scan";
            default -> "full_scan";
        };
        String symbol = "ALL".equalsIgnoreCase(parts[1]) ? null : normalizeSymbol(parts[1]);
        int minutes = parsePositiveInt(parts[2], 90);
        int limit = switch (detailType) {
            case "full_scan" -> 12;
            default -> 20;
        };
        return new DrillDownRequest(detailType, symbol, normalizeMinutes(minutes), limit);
    }

    public static String callbackData(String detailType, String symbol, int minutes) {
        String type = switch (detailType == null ? "" : detailType) {
            case "buy_details", "buy" -> "b";
            case "skip_reasons", "skip" -> "s";
            case "current_position", "position", "positions" -> "p";
            case "oco_status", "oco" -> "o";
            case "trailing_status", "trailing" -> "t";
            default -> "f";
        };
        String data = PREFIX + type + ":" + normalizeSymbol(symbol) + ":" + normalizeMinutes(minutes);
        if (data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CALLBACK_BYTES) {
            throw new IllegalArgumentException("Event Scan callback data exceeds Telegram limit: " + data);
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

    private static int normalizeMinutes(int minutes) {
        return minutes <= 0 || minutes > 1440 ? 90 : minutes;
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
