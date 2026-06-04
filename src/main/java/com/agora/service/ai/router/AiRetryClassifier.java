package com.agora.service.ai.router;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies provider failures for router fallback.
 *
 * <p>Do not infer retryability from broad substring checks like "400":
 * provider error bodies can include unrelated numbers, while the leading
 * HTTP status is the source of truth.
 */
final class AiRetryClassifier {

    private static final Pattern HTTP_STATUS =
            Pattern.compile("\\bHTTP\\s+(\\d{3})\\b", Pattern.CASE_INSENSITIVE);

    private AiRetryClassifier() {}

    static boolean isRetryable(Throwable t) {
        String msg = t != null && t.getMessage() != null ? t.getMessage() : "";
        Integer status = extractHttpStatus(msg);
        if (status != null) {
            return status == 408 || status == 409 || status == 425
                    || status == 429 || status >= 500;
        }

        String lower = msg.toLowerCase();
        if (lower.contains("rate limit") || lower.contains("too many requests")
                || lower.contains("resource_exhausted")) {
            return true;
        }
        return !(lower.contains("invalid api key")
                || lower.contains("unauthorized")
                || lower.contains("permission denied")
                || lower.contains("not found"));
    }

    static boolean isRateLimit(Throwable t) {
        String msg = t != null && t.getMessage() != null ? t.getMessage() : "";
        Integer status = extractHttpStatus(msg);
        if (status != null) return status == 429;

        String lower = msg.toLowerCase();
        return lower.contains("rate limit")
                || lower.contains("too many requests")
                || lower.contains("resource_exhausted");
    }

    private static Integer extractHttpStatus(String msg) {
        Matcher matcher = HTTP_STATUS.matcher(msg);
        if (!matcher.find()) return null;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
