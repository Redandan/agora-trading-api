package com.agora.mcp.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 標記 MCP @Tool 方法所屬的功能分類。純 metadata，不影響 MCP 註冊或認證。
 *
 * <p>一個方法可同時屬於多個分類，例如 enableStrategy 屬於
 * {@link Category#WRITE_TRADING} + {@link Category#GOVERNANCE}。
 *
 * <p>使用方式：
 * <pre>
 * {@literal @}Tool(description = "...")
 * {@literal @}McpAuth(McpAuthLevel.DEV)
 * {@literal @}McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
 * public String enableStrategy(Long id, String notes) { ... }
 * </pre>
 *
 * <p>未標注此注解的工具視為 {@link Category#META}（預設 fallback）。
 *
 * <p>用途：
 * <ul>
 *   <li>Claude session 冷啟動可由 {@code getSessionBrief} 查看分類分佈</li>
 *   <li>未來 MCP client 可依 category 做 tool subset filter（省 prompt token）</li>
 *   <li>配合 Prometheus metric 做 per-category 呼叫統計</li>
 * </ul>
 *
 * @see Category
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpCategory {
    Category[] value();
}
