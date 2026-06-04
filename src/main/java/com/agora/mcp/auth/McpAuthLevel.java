package com.agora.mcp.auth;

/**
 * MCP 工具認證等級。
 *
 * <p>階層（高等級 key 可呼叫低等級工具）：
 * <pre>
 *   DEV key  → DEV + OPS + MEMBER + PUBLIC
 *   OPS key  → OPS + MEMBER + PUBLIC
 *   User JWT → MEMBER + PUBLIC
 *   LOCAL_ONLY → localhost 無需 key
 * </pre>
 */
public enum McpAuthLevel {
    /** 任何已登入的使用者 JWT（role=USER 或 ADMIN 均可）。OPS/DEV key 亦可存取。 */
    MEMBER,
    /** 後台運營 API key（mcp.ops-key）或 DEV key（mcp.api-key）。 */
    OPS,
    /** 開發者 API key（mcp.api-key），最高 key 等級。 */
    DEV,
    /** 僅允許從 localhost 發起（SSH tunnel 專用），不需要任何 token。 */
    LOCAL_ONLY
}
