package com.agora.service.ai.knowledge;

import com.agora.dto.knowledge.KnowledgeEntry;
import com.agora.dto.knowledge.KnowledgeResponse;
import com.agora.service.ai.chroma.ChromaDocument;
import com.agora.service.ai.chroma.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 項目知識庫服務
 * <p>
 * 管理員可將平台功能說明、FAQ、規則等文件存入 Chroma，
 * 讓 AI 在用戶詢問相關問題時能正確引用回答。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectKnowledgeService {

    private final VectorStoreService vectorStoreService;

    /**
     * 新增一筆知識文件
     *
     * @return 文件 ID，失敗時回傳 null
     */
    public String addKnowledge(String title, String content, String source) {
        String id = UUID.randomUUID().toString();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("title", title != null ? title : "");
        metadata.put("source", source != null ? source : "general");
        metadata.put("createdAt", String.valueOf(System.currentTimeMillis()));

        boolean success = vectorStoreService.addDocument(
                VectorStoreService.COLLECTION_PROJECT_KNOWLEDGE, id, content, metadata);

        if (!success) {
            log.warn("知識文件新增失敗: title={}", title);
            return null;
        }

        log.info("知識文件已新增: id={}, title={}", id, title);
        return id;
    }

    /**
     * 批量匯入知識文件
     *
     * @return 成功匯入的數量
     */
    public int importBatch(List<KnowledgeEntry> entries) {
        int count = 0;
        for (KnowledgeEntry entry : entries) {
            String id = addKnowledge(entry.title(), entry.content(), entry.source());
            if (id != null) count++;
        }
        log.info("批量匯入完成，成功 {}/{} 筆", count, entries.size());
        return count;
    }

    /**
     * 列出所有知識文件
     */
    public List<KnowledgeResponse> list() {
        List<ChromaDocument> docs = vectorStoreService.listDocuments(
                VectorStoreService.COLLECTION_PROJECT_KNOWLEDGE);
        return docs.stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * 依 ID 取得單筆知識文件
     *
     * @return 文件，不存在時回傳 null
     */
    public KnowledgeResponse getById(String id) {
        List<ChromaDocument> docs = vectorStoreService.getDocumentsById(
                VectorStoreService.COLLECTION_PROJECT_KNOWLEDGE, id);
        if (docs.isEmpty()) return null;
        return toResponse(docs.get(0));
    }

    /**
     * 語意搜尋知識庫（管理員測試用）
     */
    public List<KnowledgeResponse> search(String query, int nResults) {
        return vectorStoreService.query(
                VectorStoreService.COLLECTION_PROJECT_KNOWLEDGE, query, nResults)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * 取得知識庫文件數量
     */
    public long count() {
        return vectorStoreService.count(VectorStoreService.COLLECTION_PROJECT_KNOWLEDGE);
    }

    /**
     * 刪除單筆知識文件
     */
    public void delete(String id) {
        vectorStoreService.deleteDocument(VectorStoreService.COLLECTION_PROJECT_KNOWLEDGE, id);
        log.info("知識文件已刪除: id={}", id);
    }

    private KnowledgeResponse toResponse(ChromaDocument doc) {
        Map<String, Object> meta = doc.getMetadata();
        return KnowledgeResponse.builder()
                .id(doc.getId())
                .content(doc.getDocument())
                .title(meta != null ? (String) meta.get("title") : null)
                .source(meta != null ? (String) meta.get("source") : null)
                .createdAt(meta != null ? (String) meta.get("createdAt") : null)
                .build();
    }
}
