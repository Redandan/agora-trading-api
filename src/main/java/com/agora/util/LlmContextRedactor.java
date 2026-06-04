package com.agora.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * PII / 敏感欄位濾鏡。所有送 LLM(Groq / Gemini / Claude)的 context 必須先過這個 util,
 * 保證:
 * <ul>
 *   <li>User id 用 opaque alias 代替(user_#123),不送真名 / email / phone / ip</li>
 *   <li>Activation code(OrderDeliveryProof.codePayload)轉成格式 hint 不洩露完整碼</li>
 *   <li>Order.buyerProvidedInfoJson 的敏感 value 換成 "[REDACTED]",保留 key 讓 AI 驗 schema 完整性</li>
 * </ul>
 *
 * <h3>單元測試錨點</h3>
 * 任何修改這個 class 都必須保持 {@code LlmContextRedactorTest} 全綠:
 * redact 後的字串**不應**被 regex 命中 email / 11 位 + 電話 / 長 hex 碼。
 */
@Slf4j
public final class LlmContextRedactor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 會被視為敏感 value 的 key 關鍵字(lowercase substring match) */
    private static final Set<String> SENSITIVE_KEY_HINTS = Set.of(
            "email", "mail", "phone", "tel", "mobile",
            "account", "login", "user", "username",
            "password", "pwd", "secret", "token", "key",
            "card", "id_number", "ssn", "identity");

    private LlmContextRedactor() {}

    /** Long id → "user_#123"(無法反推原 id 除非攻擊者同時有 DB access) */
    public static String redactUserId(Long id) {
        return id == null ? "user_#null" : "user_#" + id;
    }

    /** 兌換碼 / 序號 → "XXXX**** (len=20)" 格式 hint,供 AI 判格式合理但不洩露完整碼 */
    public static String redactCodePayload(String code) {
        if (code == null) return null;
        String trimmed = code.trim();
        if (trimmed.isEmpty()) return "";
        int len = trimmed.length();
        String prefix = trimmed.substring(0, Math.min(4, len));
        return prefix + "**** (len=" + len + ")";
    }

    /**
     * Order.buyerProvidedInfoJson 過濾:遞迴走 JSON,若 key 含敏感關鍵字則 value → "[REDACTED]"。
     * 保留 key 結構讓 AI 仍可判斷買家是否有填所有必要欄位(schema 完整性)。
     * 輸入無效 JSON 回 "{}" 避免把壞 JSON 直接塞進 prompt。
     */
    public static String redactBuyerInfoJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return "{}";
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            JsonNode redacted = redactNode(root);
            return MAPPER.writeValueAsString(redacted);
        } catch (Exception e) {
            log.warn("[Redactor] buyerInfoJson 解析失敗,回空 object: {}", e.getMessage());
            return "{}";
        }
    }

    private static JsonNode redactNode(JsonNode node) {
        if (node == null || node.isNull()) return node;
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                String k = entry.getKey();
                JsonNode v = entry.getValue();
                if (isSensitiveKey(k)) {
                    out.put(k, v.isNull() ? null : "[REDACTED]");
                } else {
                    out.set(k, redactNode(v));
                }
            });
            return out;
        }
        if (node.isArray()) {
            var arr = MAPPER.createArrayNode();
            node.forEach(el -> arr.add(redactNode(el)));
            return arr;
        }
        return node;
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lc = key.toLowerCase();
        for (String hint : SENSITIVE_KEY_HINTS) {
            if (lc.contains(hint)) return true;
        }
        return false;
    }
}
