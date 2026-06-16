package com.agora.mcp.auth;

import com.agora.service.TelegramService;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 工具層認證過濾器。
 *
 * <p>在 HTTP 層攔截 MCP 工具呼叫請求（POST /mcp），提取 tool name，
 * 根據 {@link McpAuthLevel} 對照表檢查 Bearer token 或來源 IP。
 *
 * <p>必須在 HTTP 層做，不能依賴 Spring AOP/AspectJ：
 * Spring AI 的 {@code MethodToolCallbackProvider} 在虛擬執行緒（Java 21 virtual thread）
 * 中呼叫 @Tool 方法，虛擬執行緒不繼承 {@code ThreadLocal}（包含 {@code RequestContextHolder}），
 * 導致 AOP 切面的 {@code RequestContextHolder.getRequestAttributes()} 返回 null，
 * 無法取得 HTTP 請求上下文。
 *
 * <p>toolAuthMap 在 {@link #discoverProtectedTools()} 啟動時自動掃描，
 * 從帶有 {@link McpAuth} + {@code @Tool} 注解的方法建立，無需手動同步。
 */
@Slf4j
@Component
@Order(-200)
public class McpApiKeyFilter extends OncePerRequestFilter {
    private static final String APPROVAL_MODE_SESSION_BATCH = "SESSION_BATCH";
    private static final String BATCH_PLAN_REQUIRED = "BATCH_PLAN_REQUIRED";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int MAX_BODY_BYTES = 1_048_576; // 1 MB

    private static final Set<String> LOCALHOST_ADDRS =
            Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    public static final Set<String> GUARDIAN_READ_ONLY_TOOLS = Set.of(
            "getGuardianSnapshot",
            "getPositionDefenseStatus",
            "previewPositionDefensePlan",
            "getSystemHealth",
            "getCurrentStartupLogIssues",
            "getOpenPositions",
            "listOpenPositions",
            "getOcoHealth",
            "checkOcoHealth",
            "getTrailingStopStatus",
            "analyzeTrailingStopReplay",
            "getExecutionRiskSnapshot",
            "previewOcoRiskReduction",
            "scanExecutionEvents",
            "listExecutionEvents",
            "expireExecutionEvents",
            "getTgNotificationHistory",
            "getReport",
            "getDailyReport",
            "getMonthlyPnlOverview"
    );
    public static final Set<String> GUARDIAN_RISK_REDUCING_LIVE_TOOLS = Set.of(
            "pauseStrategy",
            "modifyOcoRiskReducingOnly",
            "disableStrategy",
            "sendTgMessageToTgChatId"
    );
    public static final Set<String> GUARDIAN_STRICT_DENY_TOOLS = Set.of(
            "enableStrategy",
            "createGrid",
            "placeMarketBuy",
            "placeLimitBuy",
            "subscribeEarn"
    );

    /** tool name → 所需認證等級；啟動時由 @PostConstruct 自動建立，無需手動維護。 */
    private Map<String, McpAuthLevel> toolAuthMap = new HashMap<>();

    /** tool name → 所屬 category 清單；@McpCategory 自動掃描。未標注者視為空 list。 */
    private Map<String, List<Category>> toolCategoryMap = new HashMap<>();

    private final String devKey;
    private final String opsKey;
    private final String guardianKey;
    private final String externalAiKey;
    private final boolean guardianLiveActionsEnabled;
    private final boolean probeWaitEnabled;
    private final long probeWaitMs;
    private final long probePollMs;
    private final int probeWaitMaxConcurrency;
    private final AtomicInteger activeProbeWaits = new AtomicInteger(0);
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final McpSessionMasterApproval mcpSessionMasterApproval;
    private final TelegramService telegramService;

    public McpApiKeyFilter(
            @Value("${mcp.api-key:}") String devKey,
            @Value("${mcp.ops-key:}") String opsKey,
            @Value("${mcp.guardian-key:}") String guardianKey,
            @Value("${mcp.external-ai-key:}") String externalAiKey,
            @Value("${mcp.guardian-live-actions-enabled:false}") boolean guardianLiveActionsEnabled,
            @Value("${mcp.master-approval.probe-wait-enabled:true}") boolean probeWaitEnabled,
            @Value("${mcp.master-approval.probe-wait-ms:8000}") long probeWaitMs,
            @Value("${mcp.master-approval.probe-poll-ms:250}") long probePollMs,
            @Value("${mcp.master-approval.probe-wait-max-concurrency:5}") int probeWaitMaxConcurrency,
            ApplicationContext applicationContext,
            ObjectMapper objectMapper,
            McpSessionMasterApproval mcpSessionMasterApproval,
            TelegramService telegramService) {
        this.devKey = devKey;
        this.opsKey = opsKey;
        this.guardianKey = guardianKey;
        this.externalAiKey = externalAiKey;
        this.guardianLiveActionsEnabled = guardianLiveActionsEnabled;
        this.probeWaitEnabled = probeWaitEnabled;
        this.probeWaitMs = Math.max(0L, probeWaitMs);
        this.probePollMs = Math.max(50L, probePollMs);
        this.probeWaitMaxConcurrency = Math.max(1, probeWaitMaxConcurrency);
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        this.mcpSessionMasterApproval = mcpSessionMasterApproval;
        this.telegramService = telegramService;
        log.info("[McpAuth] MCP tool-level auth ready: devKey={} | opsKey={} | guardianKey={} | externalAiKey={} | guardianLiveActions={}",
                devKey.isBlank() ? "NOT SET ⚠️" : "SET ✅",
                opsKey.isBlank() ? "NOT SET ⚠️" : "SET ✅",
                guardianKey.isBlank() ? "NOT SET ⚠️" : "SET ✅",
                externalAiKey.isBlank() ? "NOT SET ⚠️" : "SET ✅",
                guardianLiveActionsEnabled);
    }

    /**
     * 啟動時掃描所有 Bean，收集帶有 {@code @Tool} 注解的方法：
     * <ul>
     *   <li>若同時有 {@link McpAuth} → 寫入 toolAuthMap（認證檢查用）</li>
     *   <li>若同時有 {@link McpCategory} → 寫入 toolCategoryMap（分類索引 + 指標用）</li>
     * </ul>
     * 新增工具只需加注解，無需更新此過濾器。
     */
    @PostConstruct
    void discoverProtectedTools() {
        long started = System.nanoTime();
        Map<String, McpAuthLevel> discoveredAuth = new HashMap<>();
        Map<String, List<Category>> discoveredCategory = new HashMap<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            // skip self — this bean is still in construction during @PostConstruct,
            // so getBean(self) would trigger a circular-reference exception
            if ("mcpApiKeyFilter".equals(beanName)) continue;
            Class<?> targetClass = applicationContext.getType(beanName, false);
            if (targetClass == null) {
                continue;
            }
            if (!targetClass.getPackageName().startsWith("com.agora.mcp")) {
                continue;
            }
            for (Method method : targetClass.getDeclaredMethods()) {
                org.springframework.ai.tool.annotation.Tool tool =
                        AnnotationUtils.findAnnotation(method, org.springframework.ai.tool.annotation.Tool.class);
                if (tool == null) continue;
                String name = (tool.name() == null || tool.name().isEmpty())
                        ? method.getName() : tool.name();

                McpAuth mcpAuth = AnnotationUtils.findAnnotation(method, McpAuth.class);
                if (mcpAuth != null) {
                    discoveredAuth.put(name, mcpAuth.value());
                }

                McpCategory mcpCategory = AnnotationUtils.findAnnotation(method, McpCategory.class);
                if (mcpCategory != null && mcpCategory.value().length > 0) {
                    discoveredCategory.put(name,
                            Collections.unmodifiableList(new ArrayList<>(Arrays.asList(mcpCategory.value()))));
                }
            }
        }
        this.toolAuthMap = Map.copyOf(discoveredAuth);
        this.toolCategoryMap = Map.copyOf(discoveredCategory);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        log.info("[McpAuth] Auto-discovered {} protected tools, {} categorized tools in {}ms",
                toolAuthMap.size(), toolCategoryMap.size(), elapsedMs);
        log.debug("[McpAuth] Tools map: {}", toolAuthMap);
        log.debug("[McpAuth] Categories map: {}", toolCategoryMap);
    }

    /**
     * 回傳 tool name → 所屬 Category 清單的不可變 map。供 {@code getSessionBrief}、
     * 指標 / Prometheus exporter 等查詢。
     *
     * @return 不可變 map；未標注 @McpCategory 的工具不會出現
     */
    public Map<String, List<Category>> getToolCategoryMap() {
        return toolCategoryMap;
    }

    /**
     * 把目前掃到的工具依 Category 分組,方便 session brief / 指標輸出。
     * 未標注 @McpCategory 的工具歸到 UNKNOWN(以 null key 呈現於 map).
     *
     * @return TreeMap(依 Category 順序) — category → tool names(排序)
     */
    public Map<Category, List<String>> getToolsByCategory() {
        Map<Category, List<String>> grouped = new EnumMap<>(Category.class);
        for (Map.Entry<String, List<Category>> e : toolCategoryMap.entrySet()) {
            for (Category cat : e.getValue()) {
                grouped.computeIfAbsent(cat, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        // 排序 tool names 讓輸出穩定
        Map<Category, List<String>> result = new TreeMap<>();
        for (Map.Entry<Category, List<String>> e : grouped.entrySet()) {
            List<String> sorted = new ArrayList<>(e.getValue());
            Collections.sort(sorted);
            result.put(e.getKey(), Collections.unmodifiableList(sorted));
        }
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Object> buildAuthContract() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", APPROVAL_MODE_SESSION_BATCH);
        out.put("requiresPlan", true);
        out.put("planTool", "getMcpAuthProbe");
        out.put("requiredField", "requestedTools");
        out.put("requiredArguments", Map.of("requestedTools", "string[]"));
        out.put("errorCode", -32004);
        out.put("errorMessage", BATCH_PLAN_REQUIRED);
        out.put("nextActionOnDenied", "Call getMcpAuthProbe with arguments.requestedTools, wait for Telegram approval, then retry the requested tools.");
        out.put("exampleCall", Map.of(
                "method", "tools/call",
                "params", Map.of(
                        "name", "getMcpAuthProbe",
                        "arguments", Map.of("requestedTools", List.of("getSystemHealth"))
                )
        ));
        return out;
    }

    public Map<String, Object> buildToolAuthMetadata(String toolName) {
        McpAuthLevel requiredLevel = toolAuthMap.get(toolName);
        List<Category> categories = toolCategoryMap.getOrDefault(toolName, List.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requiredLevel", requiredLevel == null ? "PUBLIC_OR_UNKNOWN" : requiredLevel.name());
        out.put("categories", categories.stream().map(Enum::name).toList());
        out.put("operationMode", operationMode(requiredLevel, categories));
        out.put("riskLevel", riskLevel(toolName, requiredLevel, categories));
        out.put("externalAiPlanRequired", requiredLevel != null);
        out.put("approvalMode", requiredLevel == null ? null : APPROVAL_MODE_SESSION_BATCH);
        out.put("planTool", requiredLevel == null ? null : "getMcpAuthProbe");
        return out;
    }

    public List<Map<String, Object>> buildRequestedToolsStatus(
            HttpServletRequest request,
            Set<String> requestedTools
    ) {
        if (requestedTools == null || requestedTools.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> statuses = new ArrayList<>();
        for (String tool : requestedTools) {
            if (tool == null || tool.isBlank()) {
                continue;
            }
            String normalized = tool.trim();
            boolean approved = mcpSessionMasterApproval.isApproved(request, normalized);
            Map<String, Object> status = new LinkedHashMap<>(buildToolAuthMetadata(normalized));
            status.put("toolName", normalized);
            status.put("approved", approved);
            status.put("covered", approved);
            statuses.add(status);
        }
        return statuses;
    }

    private String operationMode(McpAuthLevel requiredLevel, List<Category> categories) {
        if (requiredLevel == McpAuthLevel.DEV || categories.contains(Category.WRITE_TRADING)) {
            return "WRITE";
        }
        if (categories.contains(Category.GOVERNANCE)) {
            return "MIXED";
        }
        return "READ";
    }

    private String riskLevel(String toolName, McpAuthLevel requiredLevel, List<Category> categories) {
        if (GUARDIAN_STRICT_DENY_TOOLS.contains(toolName)
                || requiredLevel == McpAuthLevel.DEV
                || categories.contains(Category.WRITE_TRADING)) {
            return "HIGH";
        }
        if (categories.contains(Category.GOVERNANCE)) {
            return "MEDIUM";
        }
        return "LOW";
    }

    /**
     * 攔截 MCP POST 端點：
     * <ul>
     *   <li>{@code POST /mcp} — Streamable HTTP transport 端點</li>
     * </ul>
     * 其餘請求直接放行。
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return true;
        String uri = request.getRequestURI();
        return !uri.endsWith("/mcp");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        // 1. 拒絕過大的 body（防 OOM / DoS）
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            log.warn("[McpAuth] Request body too large: {} bytes (limit {})", contentLength, MAX_BODY_BYTES);
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }

        // 2. 讀取並快取 body（因為 InputStream 只能讀一次；readNBytes 不會超過上限）
        byte[] bodyBytes = request.getInputStream().readNBytes(MAX_BODY_BYTES);

        // 3. 嘗試解析 tool name
        String toolName = extractToolName(bodyBytes);

        if (toolName != null) {
            if ("getMcpAuthProbe".equals(toolName) && looksLikeExternalAiRequest(request)) {
                sendExternalAiAuthProbe(response, request, bodyBytes);
                return;
            }

            McpAuthLevel requiredLevel = toolAuthMap.get(toolName);
            if (requiredLevel != null) {
                String token  = extractBearer(request);
                String ip     = request.getRemoteAddr();
                boolean externalAiIdentity = isExternalAiIdentity(token, request);
                boolean authorizedExternalAi = externalAiIdentity && mcpSessionMasterApproval.isApproved(request, toolName);

                boolean authorized = isGuardianKey(token)
                        ? guardianPolicyAllows(toolName, guardianLiveActionsEnabled)
                        : switch (requiredLevel) {
                            case OPS       -> isDevKey(token) || isOpsKey(token) || authorizedExternalAi;
                            case DEV       -> isDevKey(token) || authorizedExternalAi;
                            case LOCAL_ONLY -> LOCALHOST_ADDRS.contains(ip) || authorizedExternalAi;
                        };

                if (!authorized) {
                    String matchedKeyType = detectMatchedKeyTypeForRequest(token, request);
                    boolean externalAiClient = looksLikeExternalAiRequest(request);
                    boolean masterApprovalEnabled = mcpSessionMasterApproval.isEnabled();
                    boolean willCreateGrant = externalAiIdentity && masterApprovalEnabled;
                    String denyReason = !externalAiIdentity
                            ? "KEY_MISMATCH_OR_MISSING"
                            : (masterApprovalEnabled ? "APPROVAL_REQUIRED" : "MASTER_APPROVAL_DISABLED");
                    log.warn("[McpAuthDebug] tool={} requiresOps={} hasOps={} matchedKeyType={} tokenHashPrefix={} " +
                                    "isExternalAiClient={} masterApprovalEnabled={} mcpSessionId={} sessionFingerprint={} " +
                                    "willCreateGrant={} denyReason={}",
                            toolName,
                            requiredLevel == McpAuthLevel.OPS,
                            isOpsKey(token),
                            matchedKeyType,
                            tokenHashPrefix(token),
                            externalAiClient,
                            masterApprovalEnabled,
                            truncateHeader(request.getHeader("X-MCP-Session-Id")),
                            mcpSessionMasterApproval.sessionShortHash(request, toolName),
                            willCreateGrant,
                            denyReason);

                    if (externalAiIdentity) {
                        if (!mcpSessionMasterApproval.isEnabled()) {
                            sendExternalAiApprovalDisabled(response);
                            return;
                        }
                        sendExternalAiApprovalRequired(response, request, toolName);
                        return;
                    }

                    log.warn("[McpAuth] DENIED tool={} ip={} level={}", toolName, ip, requiredLevel);
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    String msg = requiredLevel + " authorization required for tool '" + toolName + "'";
                    // 用 ObjectMapper 序列化，避免 toolName 含特殊字元造成 JSON Injection
                    com.fasterxml.jackson.databind.node.ObjectNode errorNode =
                            objectMapper.createObjectNode();
                    errorNode.put("jsonrpc", "2.0");
                    errorNode.putNull("id");
                    com.fasterxml.jackson.databind.node.ObjectNode errorBody =
                            errorNode.putObject("error");
                    errorBody.put("code", -32001);
                    errorBody.put("message", "Unauthorized: " + msg);
                    response.getWriter().write(objectMapper.writeValueAsString(errorNode));
                    return;
                }

                log.debug("[McpAuth] ALLOW tool={} ip={} level={}", toolName, ip, requiredLevel);
            }

            // Category logging — 只在 allow/public 路徑跑到這裡才記,
            // 便於未來 Prometheus exporter 聚合 per-category 呼叫量。
            List<Category> cats = toolCategoryMap.get(toolName);
            if (cats != null && !cats.isEmpty()) {
                log.info("[McpCategory] tool={} categories={}", toolName, cats);
            } else {
                // 未標注 @McpCategory 的工具:visibility low,WARN 提醒開發者補 metadata
                if (toolAuthMap.containsKey(toolName)
                        // Only warn for tools we actually know about (have auth config),
                        // to avoid log noise for non-tool endpoints.
                        || org.slf4j.MDC.get("forceCategoryWarn") != null) {
                    log.warn("[McpCategory] tool={} has no @McpCategory — consider tagging", toolName);
                }
            }
        } else if (!isDevOrOpsRequest(request)) {
            String method = extractMethod(bodyBytes);
            log.warn("[McpAuth] DENIED MCP method={} ip={} reason=metadata key missing", method, request.getRemoteAddr());
            sendEndpointAuthRequired(response, method);
            return;
        }

        // 4. 以可重複讀取的 wrapper 繼續過濾鏈
        chain.doFilter(new ReplayableBodyRequestWrapper(request, bodyBytes), response);
    }

    // ─── JSON parsing ─────────────────────────────────────────────────────────

    /**
     * 從 MCP JSON-RPC body 中提取 tool name。
     * 只有 method=="tools/call" 且 params.name 存在時才回傳；其他情況回傳 null。
     */
    private String extractToolName(byte[] bodyBytes) {
        try {
            JsonNode root = objectMapper.readTree(bodyBytes);
            if (!"tools/call".equals(root.path("method").asText(null))) return null;
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

    private void sendExternalAiAuthProbe(
            HttpServletResponse response,
            HttpServletRequest request,
            byte[] bodyBytes
    ) throws IOException {
        JsonNode root = objectMapper.readTree(bodyBytes);
        JsonNode id = root.get("id");
        String targetToolName = root.path("params")
                .path("arguments")
                .path("targetToolName")
                .asText(null);
        Set<String> requestedTools = extractRequestedTools(root, targetToolName);
        if (requestedTools.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");

            com.fasterxml.jackson.databind.node.ObjectNode errorNode = objectMapper.createObjectNode();
            errorNode.put("jsonrpc", "2.0");
            if (id == null || id.isNull()) {
                errorNode.putNull("id");
            } else {
                errorNode.set("id", id);
            }
            com.fasterxml.jackson.databind.node.ObjectNode errorBody = errorNode.putObject("error");
            errorBody.put("code", -32602);
            errorBody.put("message", "INVALID_PARAMS: arguments.requestedTools (string array) is required for SESSION_BATCH");
            response.getWriter().write(objectMapper.writeValueAsString(errorNode));
            return;
        }
        if (targetToolName == null || targetToolName.isBlank()) {
            targetToolName = requestedTools.stream()
                    .filter(tool -> tool != null && !tool.isBlank())
                    .findFirst()
                    .orElse("getSessionBrief");
        }

        String normalizedTargetToolName = targetToolName.trim();
        Map<String, Object> probe = buildAuthProbe(request, normalizedTargetToolName);
        boolean externalAiClient = Boolean.TRUE.equals(probe.get("isExternalAiClient"));
        boolean masterApprovalEnabled = Boolean.TRUE.equals(probe.get("masterApprovalEnabled"));
        boolean requestedToolsCovered =
                mcpSessionMasterApproval.isApprovedForRequestedTools(request, requestedTools);
        probe.put("requestedTools", requestedTools);
        probe.put("requestedToolsCovered", requestedToolsCovered);
        probe.put("requestedToolsStatus", buildRequestedToolsStatus(request, requestedTools));
        probe.put("batchPlanCreated", false);
        probe.put("authContract", buildAuthContract());
        probe.put("nextAction", requestedToolsCovered ? "retry_requested_tools" : "wait_for_tg_approval");
        Instant approvalExpiresAt = mcpSessionMasterApproval.getApprovalExpiresAt(request, normalizedTargetToolName);
        probe.put("approvalExpiresAt", approvalExpiresAt == null ? null : approvalExpiresAt.toString());
        boolean shouldCreateOrRefreshBatchPlan =
                externalAiClient
                        && masterApprovalEnabled
                        && ("APPROVAL_REQUIRED".equals(probe.get("denyReason")) || !requestedToolsCovered);
        if (shouldCreateOrRefreshBatchPlan) {
            McpSessionMasterApproval.PendingApproval pending =
                    mcpSessionMasterApproval.createOrReusePending(request, normalizedTargetToolName, requestedTools);
            if (mcpSessionMasterApproval.markTelegramPrompted(pending.grantRequestId)) {
                telegramService.sendMcpMasterApprovalRequest(
                        pending.grantRequestId,
                        pending.sessionShortHash,
                        pending.firstToolName,
                        pending.expiresAt
                );
            }
            probe.put("grantRequestId", pending.grantRequestId);
            probe.put("status", "PENDING");
            probe.put("safeToRetry", true);
            probe.put("approvalMode", APPROVAL_MODE_SESSION_BATCH);
            probe.put("sessionFingerprint", pending.sessionShortHash);
            probe.put("approvalTtlSeconds", mcpSessionMasterApproval.getApprovalTtlSeconds());
            probe.put("requestedTools", pending.requestedTools);
            probe.put("requestedToolsCovered", false);
            probe.put("batchPlanCreated", true);
            probe.put("approvalExpiresAt", null);
            log.warn("[McpAuth] External AI auth probe created approval targetTool={} grantRequestId={} session={}",
                    normalizedTargetToolName, pending.grantRequestId, pending.sessionShortHash);

            if (maybeWaitForApproval(request, requestedTools)) {
                Instant approvedUntil = mcpSessionMasterApproval.getApprovalExpiresAt(request, normalizedTargetToolName);
                probe.put("status", "APPROVED");
                probe.put("safeToRetry", true);
                probe.put("requestedToolsCovered", true);
                probe.put("requestedToolsStatus", buildRequestedToolsStatus(request, requestedTools));
                probe.put("batchPlanCreated", true);
                probe.put("approvalExpiresAt", approvedUntil == null ? null : approvedUntil.toString());
                probe.put("nextAction", "retry_requested_tools");
                log.info("[McpAuth] External AI auth probe auto-approved during wait targetTool={} session={}",
                        normalizedTargetToolName, pending.sessionShortHash);
            }
        } else if (requestedToolsCovered) {
            probe.put("status", "APPROVED");
            probe.put("safeToRetry", true);
        }
        log.info("[McpAuth] External AI auth probe allowed targetTool={} session={}",
                normalizedTargetToolName, probe.get("sessionFingerprint"));

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");

        com.fasterxml.jackson.databind.node.ObjectNode responseNode = objectMapper.createObjectNode();
        responseNode.put("jsonrpc", "2.0");
        if (id == null || id.isNull()) {
            responseNode.putNull("id");
        } else {
            responseNode.set("id", id);
        }

        com.fasterxml.jackson.databind.node.ObjectNode result = responseNode.putObject("result");
        com.fasterxml.jackson.databind.node.ArrayNode content = result.putArray("content");
        com.fasterxml.jackson.databind.node.ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(probe));
        result.put("isError", false);

        response.getWriter().write(objectMapper.writeValueAsString(responseNode));
    }

    // ─── auth helpers ─────────────────────────────────────────────────────────

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String val = header.substring(BEARER_PREFIX.length()).strip();
            return val.isEmpty() ? null : val;
        }
        return null;
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

    private boolean isGuardianKey(String token) {
        return token != null && !guardianKey.isBlank() && guardianKey.equals(token);
    }

    private boolean isExternalAiIdentity(String token, HttpServletRequest request) {
        return request != null && looksLikeExternalAiRequest(request);
    }

    private String tokenHashPrefix(String token) {
        if (token == null || token.isBlank()) {
            return "NONE";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).substring(0, 10);
        } catch (Exception e) {
            return "HASH_ERR";
        }
    }

    private String truncateHeader(String value) {
        if (value == null || value.isBlank()) {
            return "NONE";
        }
        return value.length() <= 20 ? value : value.substring(0, 20) + "...";
    }

    static boolean guardianPolicyAllows(String toolName, boolean liveActionsEnabled) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (GUARDIAN_STRICT_DENY_TOOLS.contains(toolName)) {
            return false;
        }
        if (GUARDIAN_READ_ONLY_TOOLS.contains(toolName)) {
            return true;
        }
        return liveActionsEnabled && GUARDIAN_RISK_REDUCING_LIVE_TOOLS.contains(toolName);
    }

    private void sendExternalAiApprovalRequired(
            HttpServletResponse response,
            HttpServletRequest request,
            String toolName
    ) throws IOException {
        boolean lowRiskReadOnly = isLowRiskReadOnlyTool(toolName);
        if (lowRiskReadOnly) {
            log.info("[McpAuth] External AI read-only call requires batch plan tool={} session={}",
                    toolName, mcpSessionMasterApproval.sessionShortHash(request, toolName));
        } else {
            log.warn("[McpAuth] External AI denied without plan tool={} session={}",
                    toolName, mcpSessionMasterApproval.sessionShortHash(request, toolName));
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");

        com.fasterxml.jackson.databind.node.ObjectNode errorNode = objectMapper.createObjectNode();
        errorNode.put("jsonrpc", "2.0");
        errorNode.putNull("id");

        com.fasterxml.jackson.databind.node.ObjectNode errorBody = errorNode.putObject("error");
        errorBody.put("code", -32004);
        errorBody.put("message", BATCH_PLAN_REQUIRED);

        com.fasterxml.jackson.databind.node.ObjectNode data = errorBody.putObject("data");
        data.put("approvalMode", APPROVAL_MODE_SESSION_BATCH);
        data.put("safeToRetry", false);
        data.put("deniedTool", toolName);
        data.put("planTool", "getMcpAuthProbe");
        data.put("sessionFingerprint", mcpSessionMasterApproval.sessionShortHash(request, toolName));
        data.put("requiredNextAction", "Call getMcpAuthProbe with arguments.requestedTools before protected tool calls");
        data.put("approvalScopeNote",
                "SESSION_BATCH approval is bound to the same connector/session fingerprint and the exact requestedTools set.");
        data.put("ifStillDeniedAfterApproval",
                "Retry getMcpAuthProbe from the same connector session and include '" + toolName
                        + "' exactly in arguments.requestedTools.");
        com.fasterxml.jackson.databind.node.ArrayNode requestedTools = data.putArray("requestedTools");
        requestedTools.add(toolName);
        data.set("requiredArguments", objectMapper.valueToTree(Map.of("requestedTools", List.of(toolName))));
        data.set("toolAuth", objectMapper.valueToTree(buildToolAuthMetadata(toolName)));
        if (lowRiskReadOnly) {
            data.put("recommendedReadOnlyBundle", "getDailyAutonomousTradingDigest");
            data.put("recommendedReadOnlyBundleReason",
                    "Use this single digest for broad autonomous-trading review instead of approving many separate read-only tools.");
            data.set("recommendedReadOnlyBundleArguments", objectMapper.valueToTree(Map.of(
                    "symbol", "BTCUSDT",
                    "strategyId", 574,
                    "side", "LONG",
                    "refresh", true
            )));
            data.set("minimalBatchPlanRequestedTools", objectMapper.valueToTree(List.of("getDailyAutonomousTradingDigest")));
        }
        com.fasterxml.jackson.databind.node.ObjectNode exampleCall = data.putObject("exampleCall");
        exampleCall.put("jsonrpc", "2.0");
        exampleCall.put("id", "auth-plan-1");
        exampleCall.put("method", "tools/call");
        com.fasterxml.jackson.databind.node.ObjectNode params = exampleCall.putObject("params");
        params.put("name", "getMcpAuthProbe");
        com.fasterxml.jackson.databind.node.ObjectNode arguments = params.putObject("arguments");
        com.fasterxml.jackson.databind.node.ArrayNode tools = arguments.putArray("requestedTools");
        tools.add(toolName);

        response.getWriter().write(objectMapper.writeValueAsString(errorNode));
    }

    private void sendEndpointAuthRequired(HttpServletResponse response, String method) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");

        com.fasterxml.jackson.databind.node.ObjectNode errorNode = objectMapper.createObjectNode();
        errorNode.put("jsonrpc", "2.0");
        errorNode.putNull("id");

        com.fasterxml.jackson.databind.node.ObjectNode errorBody = errorNode.putObject("error");
        errorBody.put("code", -32001);
        errorBody.put("message", "Unauthorized: DEV or OPS authorization required for MCP method '" + method + "'");
        response.getWriter().write(objectMapper.writeValueAsString(errorNode));
    }

    private boolean isLowRiskReadOnlyTool(String toolName) {
        Map<String, Object> metadata = buildToolAuthMetadata(toolName);
        return "READ".equals(metadata.get("operationMode")) && "LOW".equals(metadata.get("riskLevel"));
    }

    private Set<String> extractRequestedTools(JsonNode root, String fallbackTool) {
        LinkedHashMap<String, Boolean> ordered = new LinkedHashMap<>();
        JsonNode toolsNode = root.path("params").path("arguments").path("requestedTools");
        if (toolsNode.isArray()) {
            for (JsonNode n : toolsNode) {
                if (n != null && n.isTextual()) {
                    String v = n.asText();
                    if (v != null && !v.isBlank()) {
                        ordered.put(v.trim(), true);
                    }
                }
            }
        }
        return ordered.keySet();
    }

    private boolean maybeWaitForApproval(HttpServletRequest request, Set<String> requestedTools) {
        if (!probeWaitEnabled || probeWaitMs <= 0 || requestedTools == null || requestedTools.isEmpty()) {
            return false;
        }
        if (mcpSessionMasterApproval.isApprovedForRequestedTools(request, requestedTools)) {
            return true;
        }

        int current = activeProbeWaits.incrementAndGet();
        if (current > probeWaitMaxConcurrency) {
            activeProbeWaits.decrementAndGet();
            log.info("[McpAuth] Probe wait skipped due to concurrency limit active={} max={}",
                    current - 1, probeWaitMaxConcurrency);
            return false;
        }

        try {
            long deadline = System.currentTimeMillis() + probeWaitMs;
            while (System.currentTimeMillis() < deadline) {
                if (mcpSessionMasterApproval.isApprovedForRequestedTools(request, requestedTools)) {
                    return true;
                }
                try {
                    Thread.sleep(probePollMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return mcpSessionMasterApproval.isApprovedForRequestedTools(request, requestedTools);
        } finally {
            activeProbeWaits.decrementAndGet();
        }
    }

    private void sendExternalAiApprovalDisabled(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");

        com.fasterxml.jackson.databind.node.ObjectNode errorNode = objectMapper.createObjectNode();
        errorNode.put("jsonrpc", "2.0");
        errorNode.putNull("id");

        com.fasterxml.jackson.databind.node.ObjectNode errorBody = errorNode.putObject("error");
        errorBody.put("code", -32003);
        errorBody.put("message", "AUTH_DISABLED: External AI master approval is disabled by server config");
        response.getWriter().write(objectMapper.writeValueAsString(errorNode));
    }

    private boolean looksLikeExternalAiRequest(HttpServletRequest request) {
        return request.getHeader("X-OpenAI-Conversation-Id") != null
                || request.getHeader("X-OpenAI-Connector-Id") != null
                || request.getHeader("X-MCP-Session-Id") != null
                || isForwardedPublicMcpRequest(request);
    }

    private boolean isForwardedPublicMcpRequest(HttpServletRequest request) {
        if (request == null || !LOCALHOST_ADDRS.contains(request.getRemoteAddr())) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri == null || !uri.endsWith("/mcp")) {
            return false;
        }
        return hasText(request.getHeader("X-Forwarded-For"))
                || hasText(request.getHeader("X-Real-IP"))
                || hasText(request.getHeader("CF-Connecting-IP"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public Map<String, Object> buildAuthProbe(String toolName) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return Map.of("error", "NO_HTTP_CONTEXT");
        }
        return buildAuthProbe(attrs.getRequest(), toolName);
    }

    public Map<String, Object> buildAuthProbe(HttpServletRequest request, String toolName) {
        String token = extractBearer(request);
        McpAuthLevel requiredLevel = toolAuthMap.get(toolName);
        String matchedKeyType = detectMatchedKeyTypeForRequest(token, request);
        boolean isExternalAiClient = looksLikeExternalAiRequest(request);
        boolean masterApprovalEnabled = mcpSessionMasterApproval.isEnabled();
        boolean externalAiIdentity = isExternalAiIdentity(token, request);
        boolean approvedExternalAi = externalAiIdentity && mcpSessionMasterApproval.isApproved(request, toolName);
        boolean authorized = false;
        if (requiredLevel != null) {
            authorized = isGuardianKey(token)
                    ? guardianPolicyAllows(toolName, guardianLiveActionsEnabled)
                    : switch (requiredLevel) {
                        case OPS -> isDevKey(token) || isOpsKey(token) || approvedExternalAi;
                        case DEV -> isDevKey(token) || approvedExternalAi;
                        case LOCAL_ONLY -> LOCALHOST_ADDRS.contains(request.getRemoteAddr()) || approvedExternalAi;
                    };
        }

        String denyReason;
        if (authorized) {
            denyReason = "ALLOW";
        } else if (externalAiIdentity && !masterApprovalEnabled) {
            denyReason = "MASTER_APPROVAL_DISABLED";
        } else if (externalAiIdentity) {
            denyReason = approvedExternalAi ? "ALLOW" : "APPROVAL_REQUIRED";
        } else if (isExternalAiClient) {
            denyReason = "KEY_MISMATCH_OR_MISSING";
        } else {
            denyReason = "NOT_EXTERNAL_AI_PATH";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolName", toolName);
        out.put("requiredLevel", requiredLevel == null ? "PUBLIC_OR_UNKNOWN" : requiredLevel.name());
        out.put("matchedKeyType", matchedKeyType);
        out.put("tokenHashPrefix", tokenHashPrefix(token));
        out.put("isExternalAiClient", isExternalAiClient);
        out.put("masterApprovalEnabled", masterApprovalEnabled);
        out.put("mcpSessionId", truncateHeader(request.getHeader("X-MCP-Session-Id")));
        out.put("sessionFingerprint", mcpSessionMasterApproval.sessionShortHash(request, toolName));
        out.put("approvedExternalAi", approvedExternalAi);
        out.put("authorized", authorized);
        out.put("denyReason", denyReason);
        return out;
    }

    private String detectMatchedKeyTypeForRequest(String token, HttpServletRequest request) {
        if (isGuardianKey(token)) return "GUARDIAN";
        if (isExternalAiIdentity(token, request)) return "EXTERNAL_AI";
        if (isOpsKey(token)) return "OPS";
        if (isDevKey(token)) return "DEV";
        return "UNKNOWN";
    }

    // ─── Replayable body wrapper ───────────────────────────────────────────────

    /** 允許 Spring AI 在過濾後再次讀取同一個 request body。 */
    private static class ReplayableBodyRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] body;

        ReplayableBodyRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bis = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read()                         { return bis.read(); }
                @Override public boolean isFinished()               { return bis.available() == 0; }
                @Override public boolean isReady()                  { return true; }
                @Override public void setReadListener(ReadListener l) { /* no-op */ }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }
    }
}
