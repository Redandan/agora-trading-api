package com.agora.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

/**
 * 服務端本地 MCP 客戶端。
 *
 * <p>透過 JSON-RPC 2.0 {@code POST /api/mcp} 呼叫同一 JVM 實例上的 MCP 伺服器，
 * 讓伺服器端 AI（如 ShortAiFilter 的 Claude agentic loop）直接使用所有已定義的 MCP 工具，
 * 無需在 AI 呼叫端重複實作工具邏輯。
 *
 * <p><b>工具 Schema 快取</b>：首次呼叫 {@link #getToolSchemas} 時執行 {@code tools/list}，
 * 結果快取於記憶體中（工具清單在執行期不變）。
 *
 * <p><b>認證</b>：使用 {@code mcp.api-key} 作為 {@code Authorization: Bearer} token，
 * 與外部 Claude Code 使用相同的 key。
 */
@Slf4j
@Component
public class LocalMcpClient {

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)   // MCP tools like runBacktest can be slow
            .callTimeout(65, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${mcp.api-key:}")
    private String mcpApiKey;

    /** Lazy-initialized cache of all MCP tool schemas (Anthropic format). */
    private volatile List<Map<String, Object>> allSchemasCache = null;

    /**
     * 取得 MCP 工具的 Anthropic tools 格式 schema 清單，過濾 {@code allowed} 白名單。
     *
     * <p>第一次呼叫時執行 {@code tools/list}，之後使用記憶體快取（thread-safe）。
     * Schema 欄位名從 MCP 的 {@code inputSchema} 轉換為 Anthropic API 所需的 {@code input_schema}。
     *
     * @param allowed 允許的工具名稱白名單（只有白名單內的工具會回傳給 AI）
     * @return Anthropic tools 格式的工具清單
     */
    public List<Map<String, Object>> getToolSchemas(Set<String> allowed) {
        if (allSchemasCache == null) {
            synchronized (this) {
                if (allSchemasCache == null) {
                    allSchemasCache = fetchAllToolSchemas();
                }
            }
        }
        return allSchemasCache.stream()
                .filter(t -> allowed.contains((String) t.get("name")))
                .toList();
    }

    /**
     * 執行 MCP {@code tools/call}，回傳工具回應文字（{@code content[0].text}）。
     *
     * @param name 工具名稱
     * @param args 工具參數（Anthropic ToolCall 的 input JsonNode）
     * @return 工具執行結果字串
     * @throws RuntimeException 若 MCP 回傳錯誤或網路失敗
     */
    public String callTool(String name, JsonNode args) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", args != null ? args : objectMapper.createObjectNode());
        JsonNode result = postJsonRpc("tools/call", params);
        JsonNode content = result.path("content");
        if (content.isArray() && content.size() > 0) {
            return content.get(0).path("text").asText("");
        }
        return "{\"error\":\"empty content from MCP tool " + name + "\"}";
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private List<Map<String, Object>> fetchAllToolSchemas() {
        try {
            JsonNode result = postJsonRpc("tools/list", Map.of());
            List<Map<String, Object>> tools = new ArrayList<>();
            for (JsonNode tool : result.path("tools")) {
                String toolName = tool.path("name").asText();
                // Convert MCP inputSchema (camelCase) → Anthropic input_schema (snake_case)
                Map<String, Object> converted = new LinkedHashMap<>();
                converted.put("name", toolName);
                converted.put("description", tool.path("description").asText(""));
                JsonNode inputSchema = tool.path("inputSchema");
                converted.put("input_schema", inputSchema.isMissingNode()
                        ? Map.of("type", "object", "properties", Map.of())
                        : objectMapper.convertValue(inputSchema, Map.class));
                tools.add(converted);
            }
            log.info("[LocalMcpClient] Loaded {} MCP tool schemas: {}",
                    tools.size(), tools.stream().map(t -> (String) t.get("name")).toList());
            return tools;
        } catch (Exception e) {
            log.error("[LocalMcpClient] Failed to fetch tool schemas: {}", e.getMessage());
            return List.of();
        }
    }

    private JsonNode postJsonRpc(String method, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        body.put("params", params);

        String url = "http://localhost:" + serverPort + "/api/mcp";
        try {
            String requestJson = objectMapper.writeValueAsString(body);
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + mcpApiKey)
                    .post(RequestBody.create(requestJson, JSON_MEDIA))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody responseBody = response.body();
                String json = responseBody != null ? responseBody.string() : "{}";
                JsonNode node = objectMapper.readTree(json);
                if (node.has("error")) {
                    throw new RuntimeException("MCP JSON-RPC error: " + node.path("error"));
                }
                return node.path("result");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LocalMcpClient HTTP call failed [" + method + "]: " + e.getMessage(), e);
        }
    }
}
