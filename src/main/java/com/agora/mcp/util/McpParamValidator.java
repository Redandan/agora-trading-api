package com.agora.mcp.util;

public class McpParamValidator {
    private McpParamValidator() {}

    /** Returns an error string if blank, or null if valid. */
    public static String requireNonBlank(String val, String name) {
        return (val == null || val.isBlank()) ? "❌ " + name + " 為必填" : null;
    }

    /** Returns an error string if null (for non-String types such as Long, Integer). */
    public static String requireNonNull(Object val, String name) {
        return val == null ? "❌ " + name + " 為必填" : null;
    }

    /** Checks multiple String values and returns the first error, or null if all valid. */
    public static String requireAllNonBlank(String... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String err = requireNonBlank(pairs[i], pairs[i + 1]);
            if (err != null) return err;
        }
        return null;
    }
}
