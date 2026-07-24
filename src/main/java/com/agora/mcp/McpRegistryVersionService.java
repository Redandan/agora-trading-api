package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class McpRegistryVersionService {

    private final ObjectProvider<ToolCallbackProvider> toolCallbackProviders;
    private final BuildProperties buildProperties;
    private final Environment environment;
    private final String startedAt = Instant.now().toString();

    public McpRegistryVersionService(ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                                     ObjectProvider<BuildProperties> buildPropertiesProvider,
                                     Environment environment) {
        this.toolCallbackProviders = toolCallbackProviders;
        this.buildProperties = buildPropertiesProvider.getIfAvailable();
        this.environment = environment;
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    @Tool(
            name = "getMcpRegistryVersion",
            description = "Read-only runtime and MCP registry identity. Reports the deployed version and whitelisted tool count without changing trading, Grid, OCO, funds, or database state.")
    public Map<String, Object> buildVersionInfo() {
        List<String> toolNames = toolCallbackProviders.orderedStream()
                .flatMap(provider -> java.util.Arrays.stream(provider.getToolCallbacks()))
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .distinct()
                .sorted()
                .toList();
        String namesHash = sha256Hex(String.join("\n", toolNames));
        String serverVersion = resolveServerVersion();
        String shortHash = namesHash.length() >= 12 ? namesHash.substring(0, 12) : namesHash;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("boundary", "READ_ONLY; no trading/OCO/strategy/grid/fund/Earn behavior changed.");
        out.put("transportProtocolVersion", "2025-03-26");
        out.put("serverVersion", serverVersion);
        out.put("gitCommit", resolveGitCommit());
        out.put("startedAt", startedAt);
        out.put("toolCount", toolNames.size());
        out.put("resourceCount", toolNames.size() + 1);
        out.put("resourceNamesHash", namesHash);
        out.put("registryVersion", serverVersion + ":" + toolNames.size() + ":" + shortHash);
        out.put("versionResourceUri", "mcp://version");
        out.put("callableToolName", "getMcpRegistryVersion");
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
}
