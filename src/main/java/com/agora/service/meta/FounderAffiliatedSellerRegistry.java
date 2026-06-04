package com.agora.service.meta;

import com.agora.service.SystemConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Founder-affiliated seller registry (per docs/FOUNDER_SELLER_PROTOCOL.md §3).
 *
 * <p>Stored as JSON array in {@code system_config.founder.affiliated_seller_user_ids}.
 * In-memory cache with 5-minute TTL for hot-path checks (dispute / risk gates).
 *
 * <p>Why not a dedicated table: keeps schema simple at 0→1 scale (expected ≤ 5
 * affiliated user IDs), reuses existing SystemConfigService infrastructure.
 * Migrate to a dedicated table if/when the list grows beyond ~50 entries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FounderAffiliatedSellerRegistry {

    public static final String CONFIG_KEY = "founder.affiliated_seller_user_ids";
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L; // 5 minutes

    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile Set<Long> cached = Collections.emptySet();
    private volatile long cachedAt = 0L;

    /** Returns immutable snapshot of currently affiliated user IDs (cached). */
    public Set<Long> getAffiliatedUserIds() {
        if (System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            return cached;
        }
        refresh();
        return cached;
    }

    /** Hot-path: is this user a founder-affiliated seller? */
    public boolean isAffiliated(Long userId) {
        if (userId == null) return false;
        return getAffiliatedUserIds().contains(userId);
    }

    /** Atomic add (refresh-modify-save). */
    public synchronized void addAffiliated(Long userId, String notes, Long adminId) {
        Set<Long> current = new LinkedHashSet<>(loadFresh());
        boolean added = current.add(userId);
        if (added) {
            saveAndCache(current);
            log.warn("Founder-affiliated seller ADDED: userId={}, by adminId={}, notes={}",
                    userId, adminId, notes);
        } else {
            log.info("Founder-affiliated seller already in list: userId={}", userId);
        }
    }

    /** Atomic remove (refresh-modify-save). */
    public synchronized void removeAffiliated(Long userId, String reason, Long adminId) {
        Set<Long> current = new LinkedHashSet<>(loadFresh());
        boolean removed = current.remove(userId);
        if (removed) {
            saveAndCache(current);
            log.warn("Founder-affiliated seller REMOVED: userId={}, by adminId={}, reason={}",
                    userId, adminId, reason);
        } else {
            log.info("Founder-affiliated seller not in list: userId={}", userId);
        }
    }

    private void refresh() {
        cached = loadFresh();
        cachedAt = System.currentTimeMillis();
    }

    private Set<Long> loadFresh() {
        String json = systemConfigService.getConfigValue(CONFIG_KEY, "");
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return Collections.emptySet();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<HashSet<Long>>() {});
        } catch (Exception e) {
            log.error("Failed to parse {}: '{}'. Using empty set.", CONFIG_KEY, json, e);
            return Collections.emptySet();
        }
    }

    private void saveAndCache(Set<Long> ids) {
        try {
            String json = objectMapper.writeValueAsString(ids);
            systemConfigService.setConfigValue(CONFIG_KEY, json,
                    "Founder-affiliated seller user_ids (cold-start dogfood). " +
                    "See docs/FOUNDER_SELLER_PROTOCOL.md. Last update: " + LocalDateTime.now());
            cached = Collections.unmodifiableSet(new LinkedHashSet<>(ids));
            cachedAt = System.currentTimeMillis();
        } catch (Exception e) {
            throw new RuntimeException("Failed to save affiliated seller registry", e);
        }
    }
}
