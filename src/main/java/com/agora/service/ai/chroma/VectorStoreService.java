package com.agora.service.ai.chroma;

import com.agora.service.ai.embedding.JinaEmbeddingClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量儲存服務
 * <p>
 * 封裝 Chroma + Jina Embedding 的操作，提供簡單的 addDocument / query 介面。
 * 內部快取 collection name → id，避免重複呼叫 Chroma API。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreService {

    // Collection 名稱常數
    public static final String COLLECTION_PROJECT_KNOWLEDGE = "project_knowledge";
    public static final String COLLECTION_GROUP_PREFIX = "group_";

    private final ChromaClient chromaClient;
    private final JinaEmbeddingClient jinaEmbeddingClient;

    /** collection name → collection ID 快取 */
    private final Map<String, String> collectionIdCache = new ConcurrentHashMap<>();

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * 新增或更新一筆文件
     *
     * @param collectionName collection 名稱（自動建立）
     * @param id             文件唯一 ID（重複則更新）
     * @param text           原始文字（同時作為 embedding 輸入與儲存內容）
     * @param metadata       附加資訊，例如 groupId、timestamp、source 等
     * @return 是否成功
     */
    public boolean addDocument(String collectionName, String id, String text, Map<String, Object> metadata) {
        if (!jinaEmbeddingClient.isEnabled()) {
            log.warn("Jina API 未啟用，跳過 addDocument");
            return false;
        }

        List<Float> embedding = jinaEmbeddingClient.embed(text);
        if (embedding == null) {
            log.warn("取得 embedding 失敗，跳過 addDocument: id={}", id);
            return false;
        }

        String collectionId = resolveCollectionId(collectionName);
        if (collectionId == null) {
            log.warn("無法取得 collection {}，跳過 addDocument", collectionName);
            return false;
        }

        return chromaClient.upsert(collectionId, id, embedding, text, metadata);
    }

    /**
     * 語意查詢：找出與 queryText 最相似的文件
     *
     * @param collectionName collection 名稱
     * @param queryText      查詢文字
     * @param nResults       回傳筆數
     * @return 相似文件列表（依相似度排序），失敗或無結果時回傳空 list
     */
    public List<ChromaDocument> query(String collectionName, String queryText, int nResults) {
        if (!jinaEmbeddingClient.isEnabled()) {
            return java.util.Collections.emptyList();
        }

        List<Float> queryEmbedding = jinaEmbeddingClient.embedQuery(queryText);
        if (queryEmbedding == null) {
            log.warn("取得 query embedding 失敗: {}", queryText);
            return java.util.Collections.emptyList();
        }

        String collectionId = resolveCollectionId(collectionName);
        if (collectionId == null) {
            return java.util.Collections.emptyList();
        }

        return chromaClient.query(collectionId, queryEmbedding, nResults);
    }

    /**
     * 取得 collection 內的文件數量
     */
    public long count(String collectionName) {
        String collectionId = resolveCollectionId(collectionName);
        if (collectionId == null) return 0;
        return chromaClient.count(collectionId);
    }

    /**
     * 列出 collection 內所有文件
     */
    public List<ChromaDocument> listDocuments(String collectionName) {
        String collectionId = resolveCollectionId(collectionName);
        if (collectionId == null) return java.util.Collections.emptyList();
        return chromaClient.getDocuments(collectionId, null);
    }

    /**
     * 依 ID 取得指定文件
     */
    public List<ChromaDocument> getDocumentsById(String collectionName, String id) {
        String collectionId = resolveCollectionId(collectionName);
        if (collectionId == null) return java.util.Collections.emptyList();
        return chromaClient.getDocuments(collectionId, java.util.Collections.singletonList(id));
    }

    /**
     * 刪除單筆文件
     */
    public void deleteDocument(String collectionName, String id) {
        String collectionId = resolveCollectionId(collectionName);
        if (collectionId == null) return;
        chromaClient.delete(collectionId, id);
    }

    /**
     * 群組 collection 名稱
     */
    public static String groupCollection(Long groupId) {
        return COLLECTION_GROUP_PREFIX + groupId;
    }

    // ─── 私有方法 ─────────────────────────────────────────────────────────────

    private String resolveCollectionId(String collectionName) {
        return collectionIdCache.computeIfAbsent(collectionName, name -> {
            String id = chromaClient.getOrCreateCollection(name);
            if (id == null) {
                log.error("無法建立或取得 collection: {}", name);
            }
            return id;
        });
    }
}
