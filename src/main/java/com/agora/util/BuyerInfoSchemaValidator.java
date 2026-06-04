package com.agora.util;

import com.agora.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 驗證數位商品代購訂單中,買家提供的必要資訊是否滿足商品定義的 schema。
 *
 * <p>商品 {@code buyer_info_required} 格式:
 * <pre>[{"key":"apple_id","label":"Apple ID Email","required":true}, ...]</pre>
 *
 * <p>買家 {@code buyer_provided_info} 格式:
 * <pre>{"apple_id":"foo@example.com", ...}</pre>
 *
 * <p>規則:
 * <ul>
 *   <li>schema 為 null / 空字串 / "[]" / 非陣列 → 視為無要求,略過</li>
 *   <li>schema 中 {@code required=true} 的 key 必須在 buyer JSON 中有 non-blank 值</li>
 *   <li>buyer JSON 格式錯誤 → 拋 BusinessException</li>
 *   <li>商品 schema 格式錯誤 → 只 log warning,不拋(避免賣家端 schema 爛掉把買家鎖死)</li>
 * </ul>
 */
@Slf4j
public final class BuyerInfoSchemaValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BuyerInfoSchemaValidator() {
    }

    public static void validate(String schemaJson, String providedJson) {
        if (schemaJson == null || schemaJson.isBlank() || "[]".equals(schemaJson.trim())) {
            return;
        }
        JsonNode schema;
        try {
            schema = MAPPER.readTree(schemaJson);
        } catch (Exception e) {
            log.warn("商品 buyerInfoRequired JSON 格式錯誤,跳過驗證: {}", e.getMessage());
            return;
        }
        if (!schema.isArray() || schema.isEmpty()) {
            return;
        }

        List<String> requiredKeys = new ArrayList<>();
        List<String> requiredLabels = new ArrayList<>();
        for (JsonNode item : schema) {
            JsonNode key = item.get("key");
            JsonNode req = item.get("required");
            if (key == null || !key.isTextual()) continue;
            if (req != null && req.asBoolean(false)) {
                requiredKeys.add(key.asText());
                JsonNode label = item.get("label");
                requiredLabels.add(label != null && label.isTextual() ? label.asText() : key.asText());
            }
        }
        if (requiredKeys.isEmpty()) {
            return;
        }

        if (providedJson == null || providedJson.isBlank()) {
            throw new BusinessException("請提供代購必要資訊:" + String.join("、", requiredLabels));
        }
        JsonNode provided;
        try {
            provided = MAPPER.readTree(providedJson);
        } catch (Exception e) {
            throw new BusinessException("代購必要資訊 JSON 格式錯誤");
        }
        if (!provided.isObject()) {
            throw new BusinessException("代購必要資訊須為 JSON object");
        }

        List<String> missing = new ArrayList<>();
        for (int i = 0; i < requiredKeys.size(); i++) {
            String k = requiredKeys.get(i);
            JsonNode v = provided.get(k);
            boolean empty = v == null || v.isNull()
                    || (v.isTextual() && v.asText().isBlank());
            if (empty) missing.add(requiredLabels.get(i));
        }
        if (!missing.isEmpty()) {
            throw new BusinessException("缺少代購必要資訊:" + String.join("、", missing));
        }
    }
}
