package com.agora.mcp.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 標記 MCP @Tool 方法所需的最低認證等級。
 *
 * <p>未標注此注解的方法為 Public，任何人可呼叫。</p>
 *
 * <p>使用方式：</p>
 * <pre>
 * {@literal @}Tool(description = "...")
 * {@literal @}McpAuth(McpAuthLevel.OPS)
 * public String enableStrategy(Long strategyId) { ... }
 * </pre>
 *
 * @see McpAuthLevel
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpAuth {
    McpAuthLevel value();
}
