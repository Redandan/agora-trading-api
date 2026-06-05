package com.agora.mcp;

import com.agora.mcp.auth.McpApiKeyFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MCP Streamable HTTP Transport（MCP 2025-03-26 spec）。
 *
 * <p>每次 tool call 是獨立的 POST 請求。
 * Server 重啟後，Claude Code 下一次 tool call 自動成功，不需重開 session。
 *
 * <p>認證由 {@link com.agora.mcp.auth.McpApiKeyFilter} 在 HTTP 層處理，
 * 本控制器無需額外認證邏輯。
 *
 * <p>MCP 入口為 {@code POST /api/trading/mcp}（Streamable HTTP）。
 */
@Slf4j
@RestController
public class McpStreamableHttpController {

    private final List<ToolCallbackProvider> toolCallbackProviders;
    private final ObjectMapper objectMapper;
    private final BuildProperties buildProperties;
    private final Environment environment;
    private final McpApiKeyFilter mcpApiKeyFilter;
    private final String startedAt = java.time.Instant.now().toString();

    /** 惰性初始化、thread-safe 的工具名稱對照表。 */
    private volatile Map<String, ToolCallback> toolCallbackMap;

    public McpStreamableHttpController(List<ToolCallbackProvider> toolCallbackProviders,
                                       ObjectMapper objectMapper,
                                       ObjectProvider<BuildProperties> buildPropertiesProvider,
                                       Environment environment,
                                       ObjectProvider<McpApiKeyFilter> mcpApiKeyFilterProvider) {
        this.toolCallbackProviders = toolCallbackProviders;
        this.objectMapper = objectMapper;
        this.buildProperties = buildPropertiesProvider.getIfAvailable();
        this.environment = environment;
        this.mcpApiKeyFilter = mcpApiKeyFilterProvider.getIfAvailable();
    }

    // ── 主要入口 ──────────────────────────────────────────────────────────────

    @PostMapping(value = "/mcp",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> handleMcp(
            @RequestBody Map<String, Object> body) {

        String method = (String) body.get("method");
        Object id     = body.get("id");

        log.debug("[McpHttp] method={} id={}", method, id);

        try {
            return switch (method != null ? method : "") {
                case "initialize"                -> ok(id, buildInitResult());
                case "notifications/initialized",
                     "notifications/cancelled"   -> ResponseEntity.ok(Map.of());
                case "ping"                      -> ok(id, Map.of());
                case "tools/list"                -> ok(id, Map.of("tools", buildToolsList()));
                case "tools/call"                -> ok(id, callTool(body));
                case "resources/list"            -> ok(id, Map.of("resources", buildToolResourcesList()));
                case "resources/read"            -> ok(id, readToolResource(body));
                default                          -> methodNotFound(id, method);
            };
        } catch (IllegalArgumentException e) {
            log.warn("[McpHttp] Bad request method={}: {}", method, e.getMessage());
            return errorResponse(id, -32602, e.getMessage());
        } catch (Exception e) {
            log.error("[McpHttp] Error handling method={}: {}", method, e.getMessage(), e);
            return errorResponse(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    // ── MCP 協議處理 ──────────────────────────────────────────────────────────

    private Map<String, Object> buildInitResult() {
        return Map.of(
                "protocolVersion", "2025-03-26",
                "serverInfo", Map.of("name", "agora-trading", "version", resolveServerVersion()),
                "mcpVersion", buildMcpVersionInfo(),
                "capabilities", Map.of(
                        "tools", Map.of(),
                        "auth", buildAuthContract()
                )
        );
    }

    private Map<String, Object> buildAuthContract() {
        if (mcpApiKeyFilter != null) {
            return mcpApiKeyFilter.buildAuthContract();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "SESSION_BATCH");
        out.put("requiresPlan", true);
        out.put("planTool", "getMcpAuthProbe");
        out.put("requiredField", "requestedTools");
        out.put("requiredArguments", Map.of("requestedTools", "string[]"));
        out.put("errorCode", -32004);
        out.put("errorMessage", "BATCH_PLAN_REQUIRED");
        return out;
    }

    private String resolveServerVersion() {
        if (buildProperties != null && buildProperties.getVersion() != null && !buildProperties.getVersion().isBlank()) {
            return buildProperties.getVersion().trim();
        }
        String fromEnv = environment.getProperty("info.app.version");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromApp = environment.getProperty("spring.application.version");
        if (fromApp != null && !fromApp.isBlank()) {
            return fromApp.trim();
        }
        return "unknown";
    }

    private Map<String, Object> buildMcpVersionInfo() {
        Map<String, ToolCallback> callbacks = getCallbackMap();
        List<String> toolNames = callbacks.keySet().stream()
                .sorted()
                .toList();
        String namesHash = sha256Hex(String.join("\n", toolNames));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transportProtocolVersion", "2025-03-26");
        out.put("serverVersion", resolveServerVersion());
        out.put("gitCommit", resolveGitCommit());
        out.put("startedAt", startedAt);
        out.put("toolCount", callbacks.size());
        out.put("resourceCount", callbacks.size() + 1);
        out.put("resourceNamesHash", namesHash);
        String shortHash = namesHash.length() >= 12 ? namesHash.substring(0, 12) : namesHash;
        out.put("registryVersion", resolveServerVersion() + ":" + callbacks.size() + ":" + shortHash);
        return out;
    }

    private String resolveGitCommit() {
        if (buildProperties != null) {
            String abbrev = buildProperties.get("git.commit.id.abbrev");
            if (abbrev != null && !abbrev.isBlank()) {
                return abbrev.trim();
            }
            String full = buildProperties.get("git.commit.id");
            if (full != null && !full.isBlank()) {
                String trimmed = full.trim();
                return trimmed.length() > 12 ? trimmed.substring(0, 12) : trimmed;
            }
        }
        String fromEnv = environment.getProperty("git.commit.id.abbrev");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromApp = environment.getProperty("app.git.commit");
        if (fromApp != null && !fromApp.isBlank()) {
            return fromApp.trim();
        }
        String fromGitDir = resolveGitCommitFromWorkTree();
        if (fromGitDir != null && !fromGitDir.isBlank()) {
            return fromGitDir;
        }
        return "unknown";
    }

    private String resolveGitCommitFromWorkTree() {
        try {
            Path gitDir = Path.of(".git");
            Path headPath = gitDir.resolve("HEAD");
            if (!Files.isRegularFile(headPath)) {
                return null;
            }
            String head = Files.readString(headPath, StandardCharsets.UTF_8).trim();
            String commit;
            if (head.startsWith("ref:")) {
                String ref = head.substring("ref:".length()).trim();
                Path refPath = gitDir.resolve(ref);
                if (!Files.isRegularFile(refPath)) {
                    return null;
                }
                commit = Files.readString(refPath, StandardCharsets.UTF_8).trim();
            } else {
                commit = head;
            }
            return commit.length() > 12 ? commit.substring(0, 12) : commit;
        } catch (Exception e) {
            return null;
        }
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "unknown";
        }
    }

    private List<Map<String, Object>> buildToolsList() {
        return getCallbackMap().values().stream()
                .map(tc -> {
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("name", tc.getToolDefinition().name());
                    tool.put("description", tc.getToolDefinition().description());
                    try {
                        tool.put("inputSchema",
                                objectMapper.readTree(tc.getToolDefinition().inputSchema()));
                    } catch (Exception e) {
                        tool.put("inputSchema",
                                Map.of("type", "object", "properties", Map.of()));
                    }
                    if (mcpApiKeyFilter != null) {
                        tool.put("auth", mcpApiKeyFilter.buildToolAuthMetadata(tc.getToolDefinition().name()));
                    }
                    return tool;
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildToolResourcesList() {
        List<Map<String, Object>> resources = buildToolsList().stream()
                .map(tool -> {
                    String name = String.valueOf(tool.get("name"));
                    Map<String, Object> resource = new LinkedHashMap<>();
                    resource.put("uri", "tool://" + name);
                    resource.put("name", name);
                    resource.put("description", tool.get("description"));
                    resource.put("mimeType", "application/json");
                    return resource;
                })
                .collect(Collectors.toList());
        Map<String, Object> versionResource = new LinkedHashMap<>();
        versionResource.put("uri", "mcp://version");
        versionResource.put("name", "mcpVersion");
        versionResource.put("description", "MCP server and callable resource registry version fingerprint");
        versionResource.put("mimeType", "application/json");
        resources.add(0, versionResource);
        return resources;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readToolResource(Map<String, Object> body) {
        Map<String, Object> params = (Map<String, Object>) body.get("params");
        if (params == null) throw new IllegalArgumentException("Missing params");
        String uri = String.valueOf(params.getOrDefault("uri", ""));
        if ("mcp://version".equals(uri)) {
            return Map.of("contents", List.of(Map.of(
                    "uri", uri,
                    "mimeType", "application/json",
                    "text", toJson(buildMcpVersionInfo())
            )));
        }
        if (!uri.startsWith("tool://")) {
            throw new IllegalArgumentException("Resource not found: " + uri);
        }
        String toolName = uri.substring("tool://".length());
        Optional<Map<String, Object>> tool = buildToolsList().stream()
                .filter(t -> toolName.equals(t.get("name")))
                .findFirst();
        if (tool.isEmpty()) {
            throw new IllegalArgumentException("Resource not found: " + uri);
        }
        return Map.of("contents", List.of(Map.of(
                "uri", uri,
                "mimeType", "application/json",
                "text", toJson(tool.get())
        )));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(Map<String, Object> body) throws Exception {
        Map<String, Object> params = (Map<String, Object>) body.get("params");
        if (params == null) throw new IllegalArgumentException("Missing params");

        String toolName = (String) params.get("name");
        if (toolName == null || toolName.isBlank())
            throw new IllegalArgumentException("Missing tool name");

        Object rawArguments = params.get("arguments");
        Map<String, Object> arguments;
        if (rawArguments == null) {
            arguments = Map.of();
        } else if (rawArguments instanceof Map<?, ?> m) {
            arguments = (Map<String, Object>) m;
        } else {
            throw new IllegalArgumentException("Tool arguments must be an object");
        }

        ToolCallback tc = getCallbackMap().get(toolName);
        if (tc == null)
            throw new IllegalArgumentException("Unknown tool: " + toolName);

        String result = tc.call(objectMapper.writeValueAsString(arguments));
        return Map.of(
                "content", List.of(Map.of("type", "text", "text", result != null ? result : "")),
                "isError", false
        );
    }

    // ── 工具對照表（惰性初始化）──────────────────────────────────────────────

    private Map<String, ToolCallback> getCallbackMap() {
        if (toolCallbackMap == null) {
            synchronized (this) {
                if (toolCallbackMap == null) {
                    toolCallbackMap = toolCallbackProviders.stream()
                            .flatMap(p -> Arrays.stream(p.getToolCallbacks()))
                            .collect(Collectors.toMap(
                                    tc -> tc.getToolDefinition().name(),
                                    tc -> tc,
                                    (a, b) -> a  // 名稱衝突時保留先出現的
                            ));
                    log.info("[McpHttp] Loaded {} tools: {}", toolCallbackMap.size(),
                            toolCallbackMap.keySet());
                }
            }
        }
        return toolCallbackMap;
    }

    // ── 回應輔助方法 ──────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> ok(Object id, Object result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", result);
        return ResponseEntity.ok(resp);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private ResponseEntity<Map<String, Object>> methodNotFound(Object id, String method) {
        return errorResponse(id, -32601, "Method not found: " + method);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(Object id, int code, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("error", Map.of("code", code, "message", message));
        return ResponseEntity.ok(resp);  // MCP spec: errors still return HTTP 200
    }
}
