package com.agora.trading.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Minimal Java-owned MCP smoke runner.
 *
 * <p>PowerShell remains responsible for SSH, app.port, and env-file handling.
 * This class owns JSON-RPC calling, marker validation, and machine-readable
 * packet output so smoke logic can be unit-tested in Java instead of being
 * duplicated in embedded Python blocks.
 */
public final class McpSmokeCli {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern RECOMMENDATION_PATTERN = Pattern.compile(
            "^(KEEP_MONITOR|NO_ACTION_[A-Z0-9_]+|PAUSE_[A-Z0-9_]+_REVIEW|PAUSE_OR_WAIT_REVIEW|"
                    + "REBUILD_[A-Z0-9_]+_REVIEW|WIDEN_RANGE_REVIEW|NARROW_RANGE_REVIEW|"
                    + "CLOSE_REVIEW_FAILURE_FIRST)$");

    private McpSmokeCli() {
    }

    public static void main(String[] args) throws Exception {
        CliArgs cli = CliArgs.parse(args);
        if (!"grid-trend".equals(cli.suite())) {
            throw new IllegalArgumentException("Unsupported smoke suite: " + cli.suite());
        }
        String review = new JsonRpcMcpClient(cli.mcpUrl(), cli.mcpKey())
                .callTool("getGridTrendAdjustmentReview", Map.of(
                        "symbol", cli.symbol(),
                        "lookbackHours", cli.lookbackHours()));
        GridTrendSmokeResult result = validateGridTrendReview(review);
        printGridTrendPacket(cli, result);
    }

    static GridTrendSmokeResult validateGridTrendReview(String review) {
        require("Grid trend review heading", "Grid Trend Adjustment Review", review);
        require("read-only boundary", "boundary=READ_ONLY", review);
        require("mutation disabled marker", "mutationAllowed=false", review);
        require("order disabled marker", "orderAllowed=false", review);
        require("grid mutation disabled marker", "gridMutationAllowed=false", review);
        require("scheduler disabled marker", "schedulerChangeAllowed=false", review);
        require("telegram disabled marker", "telegramSendAllowed=false", review);
        require("operator-review-only marker", "operator review only", review);
        require("market evidence line", "market symbol=", review);
        require("recommendation marker", "recommendation=", review);

        String recommendation = extract("recommendation=([A-Z0-9_]+)", review, "UNKNOWN");
        if (!RECOMMENDATION_PATTERN.matcher(recommendation).matches()) {
            throw new IllegalStateException("Unexpected recommendation=" + recommendation);
        }
        String status = switch (recommendation) {
            case "NO_ACTION_NO_ACTIVE_GRID" -> "NO_ACTIVE_GRID_NOT_MUTATION";
            case "NO_ACTION_INSUFFICIENT_EVIDENCE" -> "BLOCKED_INSUFFICIENT_GRID_TREND_EVIDENCE";
            default -> "READY_GRID_TREND_REVIEW_NOT_MUTATION";
        };
        return new GridTrendSmokeResult(
                status,
                recommendation,
                extract("activeGridCount=([0-9]+)", review, "N/A"),
                extract("trend=([A-Z_]+)", review, "UNKNOWN"),
                extract("trendPct=([-+0-9.NA/%]+)", review, "N/A"),
                extract("atrPct=([-+0-9.NA/%]+)", review, "N/A"));
    }

    private static void printGridTrendPacket(CliArgs cli, GridTrendSmokeResult result) {
        System.out.println("[grid-trend-adjustment-review] read-only production MCP check");
        System.out.println("scope=READ_ONLY; server-local /api/mcp only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.");
        System.out.printf("symbol=%s lookbackHours=%d mcpUrl=%s%n", cli.symbol(), cli.lookbackHours(), cli.mcpUrl());
        System.out.println("grid_trend_adjustment_review_packet=GRID_TREND_ADJUSTMENT_REVIEW_PACKET");
        System.out.println("grid_trend_adjustment_review_status=" + result.status());
        System.out.println("grid_trend_adjustment_recommendation=" + result.recommendation());
        System.out.println("active_grid_count=" + result.activeGridCount());
        System.out.println("trend=" + result.trend());
        System.out.println("trendPct=" + result.trendPct());
        System.out.println("atrPct=" + result.atrPct());
        System.out.println("requiredEvidence=market trend, ATR, current price alignment, grid level state, and read-only recommendation markers");
        System.out.println("notAuthorization=true");
        System.out.println("nextAction=operator may review the packet; any closeGrid/createGrid/pause/resume or scheduler integration requires separate explicit approval.");
        System.out.println("OK read-only check complete");
    }

    private static void require(String description, String marker, String text) {
        if (text == null || !text.contains(marker)) {
            throw new IllegalStateException("Missing " + description + "; marker=" + marker);
        }
    }

    private static String extract(String pattern, String text, String defaultValue) {
        var matcher = Pattern.compile(pattern, Pattern.MULTILINE).matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : defaultValue;
    }

    record GridTrendSmokeResult(
            String status,
            String recommendation,
            String activeGridCount,
            String trend,
            String trendPct,
            String atrPct
    ) {
    }

    private record CliArgs(String suite, String mcpUrl, String mcpKey, String symbol, int lookbackHours) {
        static CliArgs parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                values.put(arg.substring(2), args[++i]);
            }
            String suite = requireArg(values, "suite");
            String mcpUrl = requireArg(values, "mcp-url");
            String mcpKey = values.containsKey("mcp-key-env")
                    ? System.getenv(requireArg(values, "mcp-key-env"))
                    : values.get("mcp-key");
            if (mcpKey == null || mcpKey.isBlank()) {
                throw new IllegalArgumentException("--mcp-key or --mcp-key-env with a populated environment variable is required");
            }
            String symbol = values.getOrDefault("symbol", "BTCUSDT").trim().toUpperCase();
            int lookbackHours = Integer.parseInt(values.getOrDefault("lookback-hours", "72"));
            if (!symbol.matches("^[A-Z0-9_-]{1,31}$")) {
                throw new IllegalArgumentException("symbol contains unsupported characters");
            }
            if (lookbackHours < 24 || lookbackHours > 336) {
                throw new IllegalArgumentException("lookback-hours must be between 24 and 336");
            }
            return new CliArgs(suite, mcpUrl, mcpKey, symbol, lookbackHours);
        }

        private static String requireArg(Map<String, String> values, String name) {
            String value = values.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("--" + name + " is required");
            }
            return value;
        }
    }

    private static final class JsonRpcMcpClient {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        private final String mcpUrl;
        private final String mcpKey;

        private JsonRpcMcpClient(String mcpUrl, String mcpKey) {
            this.mcpUrl = mcpUrl;
            this.mcpKey = mcpKey;
        }

        String callTool(String name, Map<String, Object> arguments) throws IOException, InterruptedException {
            ObjectNode body = JSON.createObjectNode();
            body.put("jsonrpc", "2.0");
            body.put("id", "java-smoke-" + name);
            body.put("method", "tools/call");
            ObjectNode params = body.putObject("params");
            params.put("name", name);
            params.set("arguments", JSON.valueToTree(arguments));

            HttpRequest request = HttpRequest.newBuilder(URI.create(mcpUrl))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + mcpKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("MCP HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = JSON.readTree(response.body());
            if (root.has("error")) {
                throw new IllegalStateException("MCP JSON-RPC error: " + root.path("error"));
            }
            JsonNode result = root.path("result");
            if (result.path("isError").asBoolean(false)) {
                throw new IllegalStateException(name + " returned isError=true: " + result);
            }
            JsonNode content = result.path("content");
            String text = content.isArray() && !content.isEmpty()
                    ? content.get(0).path("text").asText("")
                    : result.toString();
            return decodeQuotedString(text);
        }

        private static String decodeQuotedString(String text) {
            if (text != null && text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
                try {
                    JsonNode decoded = JSON.readTree(text);
                    if (decoded.isTextual()) {
                        return decoded.asText();
                    }
                } catch (Exception ignored) {
                    return text;
                }
            }
            return text;
        }
    }
}
