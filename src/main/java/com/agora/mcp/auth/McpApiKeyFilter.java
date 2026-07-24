package com.agora.mcp.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed MCP Bearer API-key filter.
 *
 * <p>The minimal Trading MCP exposes only explicitly annotated tools. Every
 * protected call requires its declared DEV/OPS key; LOCAL_ONLY tools may also
 * use localhost. Unannotated and unknown tools are denied at the filter.
 *
 * <p>The former Guardian and External-AI Telegram approval paths were removed
 * after the service split. They were not configured in Trading Production and
 * their in-memory approval state could not be completed by the Telegram
 * callback running in the separate AgoraMarketAPI JVM.
 */
@Slf4j
@Component
@Order(-200)
public class McpApiKeyFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final Set<String> LOCALHOST_ADDRS =
            Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    private Map<String, McpAuthLevel> toolAuthMap = Map.of();
    private Map<String, List<Category>> toolCategoryMap = Map.of();

    private final String devKey;
    private final String opsKey;
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;

    public McpApiKeyFilter(
            @Value("${mcp.api-key:}") String devKey,
            @Value("${mcp.ops-key:}") String opsKey,
            ApplicationContext applicationContext,
            ObjectMapper objectMapper) {
        this.devKey = devKey;
        this.opsKey = opsKey;
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        log.info("[McpAuth] Bearer API-key auth ready: devKey={} | opsKey={} | failClosed=true",
                devKey.isBlank() ? "NOT SET" : "SET",
                opsKey.isBlank() ? "NOT SET" : "SET");
    }

    /**
     * Discovers protected tools and category metadata without instantiating MCP
     * beans. Adding a callable tool still requires explicit {@link McpAuth}.
     */
    @PostConstruct
    void discoverProtectedTools() {
        long started = System.nanoTime();
        Map<String, McpAuthLevel> discoveredAuth = new HashMap<>();
        Map<String, List<Category>> discoveredCategory = new HashMap<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            if ("mcpApiKeyFilter".equals(beanName)) {
                continue;
            }
            Class<?> targetClass = applicationContext.getType(beanName, false);
            if (targetClass == null || !targetClass.getPackageName().startsWith("com.agora.mcp")) {
                continue;
            }
            for (Method method : targetClass.getDeclaredMethods()) {
                org.springframework.ai.tool.annotation.Tool tool =
                        AnnotationUtils.findAnnotation(
                                method,
                                org.springframework.ai.tool.annotation.Tool.class);
                if (tool == null) {
                    continue;
                }
                String name = tool.name() == null || tool.name().isEmpty()
                        ? method.getName()
                        : tool.name();

                McpAuth mcpAuth = AnnotationUtils.findAnnotation(method, McpAuth.class);
                if (mcpAuth != null) {
                    discoveredAuth.put(name, mcpAuth.value());
                }

                McpCategory mcpCategory =
                        AnnotationUtils.findAnnotation(method, McpCategory.class);
                if (mcpCategory != null && mcpCategory.value().length > 0) {
                    discoveredCategory.put(
                            name,
                            List.copyOf(List.of(mcpCategory.value())));
                }
            }
        }
        toolAuthMap = Map.copyOf(discoveredAuth);
        toolCategoryMap = Map.copyOf(discoveredCategory);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        log.info("[McpAuth] Auto-discovered {} protected tools, {} categorized tools in {}ms",
                toolAuthMap.size(), toolCategoryMap.size(), elapsedMs);
    }

    public Map<String, Object> buildAuthContract() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "BEARER_API_KEY");
        out.put("scheme", "Bearer");
        out.put("requiresPlan", false);
        out.put("unannotatedToolPolicy", "DENY");
        out.put("nonToolMethodPolicy", "DEV_OR_OPS_KEY_REQUIRED");
        out.put("supportedLevels", List.of("OPS", "DEV", "LOCAL_ONLY"));
        return out;
    }

    public Map<String, Object> buildToolAuthMetadata(String toolName) {
        McpAuthLevel requiredLevel = toolAuthMap.get(toolName);
        List<Category> categories =
                toolCategoryMap.getOrDefault(toolName, List.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requiredLevel",
                requiredLevel == null ? "DENY_UNANNOTATED" : requiredLevel.name());
        out.put("categories", categories.stream().map(Enum::name).toList());
        out.put("operationMode", operationMode(requiredLevel, categories));
        out.put("riskLevel", riskLevel(requiredLevel, categories));
        out.put("apiKeyRequired", requiredLevel != McpAuthLevel.LOCAL_ONLY);
        out.put("localhostAllowed", requiredLevel == McpAuthLevel.LOCAL_ONLY);
        return out;
    }

    private String operationMode(
            McpAuthLevel requiredLevel,
            List<Category> categories) {
        if (requiredLevel == McpAuthLevel.DEV
                || categories.contains(Category.WRITE_TRADING)) {
            return "WRITE";
        }
        if (categories.contains(Category.GOVERNANCE)) {
            return "MIXED";
        }
        return "READ";
    }

    private String riskLevel(
            McpAuthLevel requiredLevel,
            List<Category> categories) {
        if (requiredLevel == McpAuthLevel.DEV
                || categories.contains(Category.WRITE_TRADING)) {
            return "HIGH";
        }
        if (categories.contains(Category.GOVERNANCE)) {
            return "MEDIUM";
        }
        return "LOW";
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        return !uri.endsWith("/mcp");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            log.warn("[McpAuth] Request body too large: {} bytes (limit {})",
                    contentLength, MAX_BODY_BYTES);
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }

        byte[] bodyBytes = request.getInputStream().readNBytes(MAX_BODY_BYTES);
        String toolName = extractToolName(bodyBytes);

        if (toolName != null) {
            McpAuthLevel requiredLevel = toolAuthMap.get(toolName);
            if (requiredLevel == null) {
                log.info("[McpAuth] DENIED unannotated-or-unknown tool={} ip={}",
                        toolName, request.getRemoteAddr());
                sendToolAuthRequired(response, toolName, "DENY_UNANNOTATED");
                return;
            }

            String token = extractBearer(request);
            if (!isAuthorized(requiredLevel, token, request.getRemoteAddr())) {
                log.warn("[McpAuth] DENIED tool={} ip={} level={}",
                        toolName, request.getRemoteAddr(), requiredLevel);
                sendToolAuthRequired(response, toolName, requiredLevel.name());
                return;
            }

            List<Category> categories = toolCategoryMap.get(toolName);
            if (categories == null || categories.isEmpty()) {
                log.warn("[McpCategory] tool={} has no @McpCategory", toolName);
            } else {
                log.info("[McpCategory] tool={} categories={}", toolName, categories);
            }
        } else if (!isDevOrOpsRequest(request)) {
            String method = extractMethod(bodyBytes);
            log.warn("[McpAuth] DENIED MCP method={} ip={} reason=API key missing",
                    method, request.getRemoteAddr());
            sendEndpointAuthRequired(response, method);
            return;
        }

        chain.doFilter(new ReplayableBodyRequestWrapper(request, bodyBytes), response);
    }

    private boolean isAuthorized(
            McpAuthLevel requiredLevel,
            String token,
            String remoteAddress) {
        return switch (requiredLevel) {
            case OPS -> isDevKey(token) || isOpsKey(token);
            case DEV -> isDevKey(token);
            case LOCAL_ONLY -> LOCALHOST_ADDRS.contains(remoteAddress);
        };
    }

    private String extractToolName(byte[] bodyBytes) {
        try {
            JsonNode root = objectMapper.readTree(bodyBytes);
            if (!"tools/call".equals(root.path("method").asText(null))) {
                return null;
            }
            return root.path("params").path("name").asText(null);
        } catch (Exception e) {
            log.debug("[McpAuth] Failed to parse tool name: {}", e.getMessage());
            return null;
        }
    }

    private String extractMethod(byte[] bodyBytes) {
        try {
            JsonNode root = objectMapper.readTree(bodyBytes);
            String method = root.path("method").asText(null);
            return method == null || method.isBlank() ? "unknown" : method;
        } catch (Exception e) {
            log.debug("[McpAuth] Failed to parse method: {}", e.getMessage());
            return "unknown";
        }
    }

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String value = header.substring(BEARER_PREFIX.length()).strip();
        return value.isEmpty() ? null : value;
    }

    private boolean isDevKey(String token) {
        return token != null && !devKey.isBlank() && devKey.equals(token);
    }

    private boolean isOpsKey(String token) {
        return token != null && !opsKey.isBlank() && opsKey.equals(token);
    }

    private boolean isDevOrOpsRequest(HttpServletRequest request) {
        String token = extractBearer(request);
        return isDevKey(token) || isOpsKey(token);
    }

    private void sendToolAuthRequired(
            HttpServletResponse response,
            String toolName,
            String requiredLevel) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");

        var errorNode = objectMapper.createObjectNode();
        errorNode.put("jsonrpc", "2.0");
        errorNode.putNull("id");
        var errorBody = errorNode.putObject("error");
        errorBody.put("code", -32001);
        errorBody.put(
                "message",
                "Unauthorized: " + requiredLevel
                        + " Bearer authorization required for tool '" + toolName + "'");
        response.getWriter().write(objectMapper.writeValueAsString(errorNode));
    }

    private void sendEndpointAuthRequired(
            HttpServletResponse response,
            String method) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");

        var errorNode = objectMapper.createObjectNode();
        errorNode.put("jsonrpc", "2.0");
        errorNode.putNull("id");
        var errorBody = errorNode.putObject("error");
        errorBody.put("code", -32001);
        errorBody.put(
                "message",
                "Unauthorized: DEV or OPS Bearer authorization required for MCP method '"
                        + method + "'");
        response.getWriter().write(objectMapper.writeValueAsString(errorNode));
    }

    private static class ReplayableBodyRequestWrapper
            extends HttpServletRequestWrapper {

        private final byte[] body;

        ReplayableBodyRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // In-memory request body is always ready.
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(
                            new ByteArrayInputStream(body),
                            StandardCharsets.UTF_8));
        }
    }
}
