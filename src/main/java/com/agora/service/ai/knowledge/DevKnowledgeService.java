package com.agora.service.ai.knowledge;

import com.agora.service.ai.chroma.ChromaDocument;
import com.agora.service.ai.chroma.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dev knowledge base service — multi-project edition.
 *
 * Each project gets its own Chroma collection: dev_kb_{project}.
 * Chroma auto-creates the collection on first write (getOrCreateCollection).
 * topic_key = Chroma document ID → free upsert semantics.
 *
 * Default project: "agora-backend" (backward-compatible with existing entries).
 *
 * Layer hierarchy:
 *   raw   — session notes, debugging findings, quick decisions
 *   topic — architectural decisions, module design, API contracts
 *   brief — domain overview (one per domain, kept current)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevKnowledgeService {

    public static final String DEFAULT_PROJECT = "agora-backend";

    private final VectorStoreService vectorStoreService;

    // ── Write ────────────────────────────────────────────────────────────────

    public boolean write(String topicKey, String title, String content,
                         String domain, String layer, String tags,
                         Double confidence, String fileRefs, String status,
                         String source, String project) {
        String collection = collectionFor(project);
        int version = resolveNextVersion(collection, topicKey);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("topic_key",  topicKey);
        meta.put("title",      title      != null ? title      : "");
        meta.put("domain",     domain     != null ? domain     : "general");
        meta.put("layer",      layer      != null ? layer      : "raw");
        meta.put("tags",       tags       != null ? tags       : "");
        meta.put("status",     status     != null ? status     : "confirmed");
        meta.put("version",    String.valueOf(version));
        meta.put("confidence", confidence != null ? String.valueOf(confidence) : "0.8");
        meta.put("file_refs",  fileRefs   != null ? fileRefs   : "");
        meta.put("source",     source     != null ? source     : "claude-session");
        meta.put("project",    resolveProject(project));
        meta.put("updated_at", String.valueOf(System.currentTimeMillis()));

        boolean ok = vectorStoreService.addDocument(collection, topicKey, content, meta);
        if (ok) {
            log.info("[KB] write ok: project={} topicKey={} domain={} layer={} status={} v{}",
                    resolveProject(project), topicKey, domain, layer, status, version);
        } else {
            log.warn("[KB] write failed: project={} topicKey={}", resolveProject(project), topicKey);
        }
        return ok;
    }

    // Fire-and-forget variant: returns immediately, Jina embedding runs in background.
    // Caller gets 200 before Cloudflare's 30s proxy timeout kicks in.
    @Async("kbAsyncExecutor")
    public void writeAsync(String topicKey, String title, String content,
                           String domain, String layer, String tags,
                           Double confidence, String fileRefs, String status,
                           String source, String project) {
        write(topicKey, title, content, domain, layer, tags, confidence, fileRefs, status, source, project);
    }

    // ── Search ───────────────────────────────────────────────────────────────

    public List<ChromaDocument> search(String query, String domain, String layer,
                                       String status, int limit, String project) {
        String collection = collectionFor(project);
        boolean hasFilter = domain != null || layer != null || status != null;
        int fetchSize = hasFilter ? Math.min(limit * 5, 100) : limit;

        return vectorStoreService.query(collection, query, fetchSize).stream()
                .filter(doc -> matchesFilters(doc, domain, layer, status))
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ── Get ──────────────────────────────────────────────────────────────────

    public ChromaDocument get(String topicKey, String project) {
        List<ChromaDocument> docs = vectorStoreService.getDocumentsById(collectionFor(project), topicKey);
        return docs.isEmpty() ? null : docs.get(0);
    }

    public List<ChromaDocument> getMany(List<String> topicKeys, String project) {
        if (topicKeys == null || topicKeys.isEmpty()) return List.of();

        String collection = collectionFor(project);
        List<ChromaDocument> out = new ArrayList<>();
        for (String topicKey : topicKeys) {
            if (topicKey == null || topicKey.isBlank()) continue;
            List<ChromaDocument> docs = vectorStoreService.getDocumentsById(collection, topicKey.trim());
            if (!docs.isEmpty()) out.add(docs.get(0));
        }
        return out;
    }

    // ── Mark Stale ───────────────────────────────────────────────────────────

    public boolean markStale(String topicKey, String reason, String project) {
        ChromaDocument doc = get(topicKey, project);
        if (doc == null) return false;

        Map<String, Object> meta = new LinkedHashMap<>(
                doc.getMetadata() != null ? doc.getMetadata() : Map.of());
        meta.put("status",       "stale");
        meta.put("stale_reason", reason != null ? reason : "");
        meta.put("updated_at",   String.valueOf(System.currentTimeMillis()));

        return vectorStoreService.addDocument(collectionFor(project), topicKey, doc.getDocument(), meta);
    }

    /**
     * Promote a draft entry to status=confirmed, preserving content + version.
     * Mirrors {@link #markStale} but flips status the other direction.
     * Used by KbAuditWeeklyScheduler to auto-promote Sirin failure drafts that
     * have been re-upserted enough times to indicate a real recurring bug.
     */
    public boolean promoteToConfirmed(String topicKey, String reason, String project) {
        ChromaDocument doc = get(topicKey, project);
        if (doc == null) return false;

        Map<String, Object> meta = new LinkedHashMap<>(
                doc.getMetadata() != null ? doc.getMetadata() : Map.of());
        meta.put("status",         "confirmed");
        meta.put("promoted_reason", reason != null ? reason : "");
        meta.put("updated_at",     String.valueOf(System.currentTimeMillis()));

        boolean ok = vectorStoreService.addDocument(collectionFor(project), topicKey, doc.getDocument(), meta);
        if (ok) {
            log.info("[KB] promote ok: project={} topicKey={} reason={}",
                    resolveProject(project), topicKey, reason);
        }
        return ok;
    }

    public int markStaleDomain(String domain, String reason, String project) {
        String collection = collectionFor(project);
        List<ChromaDocument> targets = vectorStoreService.listDocuments(collection).stream()
                .filter(doc -> {
                    Map<String, Object> m = doc.getMetadata();
                    return m != null
                            && domain.equals(m.get("domain"))
                            && !"stale".equals(m.get("status"));
                })
                .toList();

        int count = 0;
        for (ChromaDocument doc : targets) {
            if (markStale(doc.getId(), reason, project)) count++;
        }
        log.info("[KB] markStaleDomain: project={} domain={} affected={}", resolveProject(project), domain, count);
        return count;
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    public void delete(String topicKey, String project) {
        vectorStoreService.deleteDocument(collectionFor(project), topicKey);
        log.info("[KB] deleted: project={} topicKey={}", resolveProject(project), topicKey);
    }

    // ── Export ───────────────────────────────────────────────────────────────

    /**
     * Return every document in the project's Chroma collection, unsorted.
     * Used by the snapshot pipeline (kbExport MCP tool → daily git mirror).
     *
     * <p>No vector similarity, no topK cutoff — straight collection dump
     * via {@link VectorStoreService#listDocuments(String)}. Embedding
     * vectors are NOT included (they're recomputable from content).
     */
    public List<ChromaDocument> exportAll(String project) {
        return vectorStoreService.listDocuments(collectionFor(project));
    }

    // ── Health ───────────────────────────────────────────────────────────────

    public Map<String, Object> health(String project) {
        String collection = collectionFor(project);
        List<ChromaDocument> all = vectorStoreService.listDocuments(collection);

        Map<String, Long> byStatus = all.stream().collect(Collectors.groupingBy(
                doc -> strMeta(doc, "status", "unknown"), Collectors.counting()));
        Map<String, Long> byLayer = all.stream().collect(Collectors.groupingBy(
                doc -> strMeta(doc, "layer", "unknown"), Collectors.counting()));
        Map<String, Long> byDomain = all.stream().collect(Collectors.groupingBy(
                doc -> strMeta(doc, "domain", "unknown"), Collectors.counting()));

        String oldestConfirmedKey = all.stream()
                .filter(doc -> "confirmed".equals(strMeta(doc, "status", "")))
                .min(Comparator.comparingLong(doc -> parseLong(strMeta(doc, "updated_at", "0"))))
                .map(ChromaDocument::getId)
                .orElse(null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project",  resolveProject(project));
        result.put("total",    all.size());
        result.put("byStatus", byStatus);
        result.put("byLayer",  byLayer);
        result.put("byDomain", byDomain);
        if (oldestConfirmedKey != null) result.put("oldestConfirmed", oldestConfirmedKey);
        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Maps project name → Chroma collection name. Auto-created on first use. */
    public static String collectionFor(String project) {
        String p = resolveProject(project);
        // sanitise: lowercase, replace hyphens with underscores, strip non-alphanumeric-underscore
        return "dev_kb_" + p.toLowerCase().replace("-", "_").replaceAll("[^a-z0-9_]", "");
    }

    public static String resolveProject(String project) {
        return (project == null || project.isBlank()) ? DEFAULT_PROJECT : project.trim();
    }

    private int resolveNextVersion(String collection, String topicKey) {
        List<ChromaDocument> existing = vectorStoreService.getDocumentsById(collection, topicKey);
        if (existing.isEmpty()) return 1;
        try {
            return Integer.parseInt(strMeta(existing.get(0), "version", "0")) + 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private boolean matchesFilters(ChromaDocument doc, String domain, String layer, String status) {
        Map<String, Object> m = doc.getMetadata();
        if (m == null) return true;
        if (domain != null && !domain.equals(m.get("domain"))) return false;
        if (layer  != null && !layer.equals(m.get("layer")))   return false;
        if (status != null && !status.equals(m.get("status"))) return false;
        return true;
    }

    private static String strMeta(ChromaDocument doc, String key, String fallback) {
        Map<String, Object> m = doc.getMetadata();
        if (m == null) return fallback;
        Object v = m.get(key);
        return v != null ? v.toString() : fallback;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return Long.MAX_VALUE; }
    }
}
