package com.agora.service.ai.chroma;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Chroma 向量資料庫 HTTP 客戶端（API v2）
 * <p>
 * 提供 collection 建立、文件新增、向量查詢等操作。
 * 本地開發請先開 SSH Tunnel：
 *   ssh -i [key] -L 8000:127.0.0.1:8000 ubuntu@141.148.142.175 -N
 */
@Slf4j
@Component
public class ChromaClient {

    private static final String TENANT = "default_tenant";
    private static final String DATABASE = "default_database";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Value("${chroma.base-url:http://localhost:8000}")
    private String baseUrl;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─── Collection ──────────────────────────────────────────────────────────

    /**
     * 取得或建立 collection，回傳 collection ID
     */
    public String getOrCreateCollection(String collectionName) {
        // 先嘗試取得
        String id = getCollectionId(collectionName);
        if (id != null) return id;

        // 不存在則建立
        return createCollection(collectionName);
    }

    public String getCollectionId(String collectionName) {
        try {
            String url = baseUrl + "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE
                    + "/collections/" + collectionName;
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) return null;
                ResponseBody body = response.body();
                if (body == null) return null;
                JsonNode root = objectMapper.readTree(body.string());
                return root.path("id").asText(null);
            }
        } catch (Exception e) {
            log.debug("取得 collection {} 失敗: {}", collectionName, e.getMessage());
            return null;
        }
    }

    public String createCollection(String collectionName) {
        try {
            String url = baseUrl + "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE + "/collections";
            Map<String, Object> body = new HashMap<>();
            body.put("name", collectionName);
            body.put("get_or_create", true);

            String json = objectMapper.writeValueAsString(body);
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("建立 collection {} 失敗，狀態碼: {}", collectionName, response.code());
                    return null;
                }
                ResponseBody responseBody = response.body();
                if (responseBody == null) return null;
                JsonNode root = objectMapper.readTree(responseBody.string());
                String id = root.path("id").asText(null);
                log.info("Collection {} 已建立，id={}", collectionName, id);
                return id;
            }
        } catch (Exception e) {
            log.error("建立 collection {} 時發生錯誤: {}", collectionName, e.getMessage());
            return null;
        }
    }

    // ─── Upsert ───────────────────────────────────────────────────────────────

    /**
     * 新增或更新單一文件（含向量）
     *
     * @param collectionId Chroma collection UUID
     * @param id           文件唯一識別碼
     * @param embedding    向量
     * @param document     原始文字
     * @param metadata     附加資訊（可為 null）
     */
    public boolean upsert(String collectionId, String id, List<Float> embedding,
                          String document, Map<String, Object> metadata) {
        return upsertBatch(collectionId,
                Collections.singletonList(id),
                Collections.singletonList(embedding),
                Collections.singletonList(document),
                metadata != null ? Collections.singletonList(metadata) : null);
    }

    /**
     * 批量新增或更新文件
     */
    public boolean upsertBatch(String collectionId, List<String> ids, List<List<Float>> embeddings,
                                List<String> documents, List<Map<String, Object>> metadatas) {
        try {
            String url = baseUrl + "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE
                    + "/collections/" + collectionId + "/upsert";

            Map<String, Object> body = new HashMap<>();
            body.put("ids", ids);
            body.put("embeddings", embeddings);
            body.put("documents", documents);
            if (metadatas != null) body.put("metadatas", metadatas);

            String json = objectMapper.writeValueAsString(body);
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("Upsert 到 collection {} 失敗，狀態碼: {}", collectionId, response.code());
                    return false;
                }
                return true;
            }
        } catch (IOException e) {
            log.error("Upsert 發生 IO 錯誤: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Upsert 發生未預期錯誤", e);
            return false;
        }
    }

    // ─── Query ────────────────────────────────────────────────────────────────

    /**
     * 向量語意查詢
     *
     * @param collectionId 目標 collection UUID
     * @param queryEmbedding 查詢向量
     * @param nResults       回傳筆數
     * @return 文件列表（依相似度排序），失敗時回傳空 list
     */
    public List<ChromaDocument> query(String collectionId, List<Float> queryEmbedding, int nResults) {
        try {
            String url = baseUrl + "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE
                    + "/collections/" + collectionId + "/query";

            Map<String, Object> body = new HashMap<>();
            body.put("query_embeddings", Collections.singletonList(queryEmbedding));
            body.put("n_results", nResults);
            body.put("include", Arrays.asList("documents", "metadatas", "distances"));

            String json = objectMapper.writeValueAsString(body);
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("Query collection {} 失敗，狀態碼: {}", collectionId, response.code());
                    return Collections.emptyList();
                }
                ResponseBody responseBody = response.body();
                if (responseBody == null) return Collections.emptyList();

                JsonNode root = objectMapper.readTree(responseBody.string());
                return parseQueryResult(root);
            }
        } catch (Exception e) {
            log.error("Query 發生錯誤: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ChromaDocument> parseQueryResult(JsonNode root) {
        List<ChromaDocument> results = new ArrayList<>();
        JsonNode ids = root.path("ids").path(0);
        JsonNode documents = root.path("documents").path(0);
        JsonNode metadatas = root.path("metadatas").path(0);
        JsonNode distances = root.path("distances").path(0);

        for (int i = 0; i < ids.size(); i++) {
            ChromaDocument doc = new ChromaDocument();
            doc.setId(ids.path(i).asText());
            doc.setDocument(documents.path(i).asText());
            doc.setDistance(distances.path(i).asDouble());

            JsonNode meta = metadatas.path(i);
            if (meta.isObject()) {
                Map<String, Object> metaMap = new HashMap<>();
                meta.fields().forEachRemaining(entry ->
                        metaMap.put(entry.getKey(), entry.getValue().asText()));
                doc.setMetadata(metaMap);
            }
            results.add(doc);
        }
        return results;
    }

    // ─── Get ──────────────────────────────────────────────────────────────────

    /**
     * 取得指定 collection 的文件（帶 metadata）
     *
     * @param collectionId collection UUID
     * @param ids          指定 id 列表；傳 null 或空 list 表示取全部
     * @return 文件列表
     */
    public List<ChromaDocument> getDocuments(String collectionId, List<String> ids) {
        try {
            String url = baseUrl + "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE
                    + "/collections/" + collectionId + "/get";

            Map<String, Object> body = new HashMap<>();
            if (ids != null && !ids.isEmpty()) body.put("ids", ids);
            body.put("include", Arrays.asList("documents", "metadatas"));

            String json = objectMapper.writeValueAsString(body);
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("GetDocuments collection {} 失敗，狀態碼: {}", collectionId, response.code());
                    return Collections.emptyList();
                }
                ResponseBody responseBody = response.body();
                if (responseBody == null) return Collections.emptyList();

                JsonNode root = objectMapper.readTree(responseBody.string());
                return parseGetResult(root);
            }
        } catch (Exception e) {
            log.error("GetDocuments 發生錯誤: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ChromaDocument> parseGetResult(JsonNode root) {
        List<ChromaDocument> results = new ArrayList<>();
        JsonNode ids = root.path("ids");
        JsonNode documents = root.path("documents");
        JsonNode metadatas = root.path("metadatas");

        for (int i = 0; i < ids.size(); i++) {
            ChromaDocument doc = new ChromaDocument();
            doc.setId(ids.path(i).asText());
            doc.setDocument(documents.path(i).asText());

            JsonNode meta = metadatas.path(i);
            if (meta.isObject()) {
                Map<String, Object> metaMap = new HashMap<>();
                meta.fields().forEachRemaining(e -> metaMap.put(e.getKey(), e.getValue().asText()));
                doc.setMetadata(metaMap);
            }
            results.add(doc);
        }
        return results;
    }

    // ─── Delete ───────────────────────────────────────────────────────────────

    public void delete(String collectionId, String id) {
        String url = baseUrl + "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE
                + "/collections/" + collectionId + "/delete";
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("ids", java.util.Collections.singletonList(id));
            String json = objectMapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("刪除文件 {} 失敗，狀態碼: {}", id, response.code());
                }
            }
        } catch (Exception e) {
            log.error("刪除文件 {} 發生錯誤: {}", id, e.getMessage());
        }
    }

    // ─── Count ────────────────────────────────────────────────────────────────

    public long count(String collectionId) {
        try {
            String url = baseUrl + "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE
                    + "/collections/" + collectionId + "/count";
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) return 0;
                ResponseBody body = response.body();
                if (body == null) return 0;
                return Long.parseLong(body.string().trim());
            }
        } catch (Exception e) {
            log.debug("Count collection {} 失敗: {}", collectionId, e.getMessage());
            return 0;
        }
    }
}
