package com.agora.service;

import com.agora.dto.backtest.CreateStrategyRequest;
import com.agora.dto.backtest.SopMtfAdxConfig;
import com.agora.dto.backtest.StrategyQueryRequest;
import com.agora.dto.backtest.StrategyResponse;
import com.agora.dto.backtest.UpdateStrategyRequest;
import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtBacktestResultRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.backtest.StrategyRegistry;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BtStrategyService {

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private final BtStrategyRepository strategyRepository;
    private final BtBacktestResultRepository backtestResultRepository;
    private final BtLiveSignalRepository liveSignalRepository;
    private final StrategyRegistry strategyRegistry;
    private final ObjectMapper objectMapper;
    private final NotificationPort notificationPort;

    // ── 策略品質門檻（enableStrategy 驗證使用）──────────────────────────────────
    // tradeCount≥5：配合資料限制（約100天），與 AI 探勘內部門檻一致
    // winRate 不設門檻：高風報比策略（SL=2%/TP=5%）損益平衡勝率僅需 28.6%，
    //                  totalReturn>0 已間接涵蓋勝率×風報比的綜合結果
    // tradeCount<15：觸發 robustness 閘道(3x 參數掃描),CLIFF 即拒絕。
    //                  二項分布下 15 筆能區分 40% vs 60% 勝率(p<0.05);5 筆統計上不可信。
    // tradeCount≥15：觸發 walk-forward 閘道(5 folds / 180d),檢查各時段一致性。
    //                  stdev/|mean| 比值判定:≤1.5 STABLE、1.5~3.0 UNSTABLE(WARN pass)、
    //                  >3.0 HIGHLY_UNSTABLE(拒絕)。
    public static final int    QUALITY_MIN_TRADE_COUNT           = 5;
    public static final int    QUALITY_ROBUSTNESS_EXEMPT_TRADES  = 15;
    public static final double QUALITY_MAX_DRAWDOWN              = 0.20;
    public static final double QUALITY_WF_UNSTABLE_RATIO          = 1.5;  // stdev/|mean| > 此值 → UNSTABLE(WARN pass)
    public static final double QUALITY_WF_HIGHLY_UNSTABLE_RATIO   = 3.0;  // stdev/|mean| > 此值 → HIGHLY_UNSTABLE(拒絕)
    public static final int    QUALITY_WF_FOLDS                   = 5;    // walk-forward 切幾段
    public static final int    QUALITY_WF_DAYS                    = 180;  // walk-forward 總天數

    /** 取得所有啟用策略（快取 10min，enable/disable 時由 evictEnabledStrategiesCache 立即失效）。 */
    @Cacheable("enabledStrategies")
    public List<BtStrategy> getEnabledStrategies() {
        return strategyRepository.findByEnabled(Boolean.TRUE);
    }

    /** 供 enable/disable 策略時呼叫，使快取立即失效。 */
    @CacheEvict(value = "enabledStrategies", allEntries = true)
    public void evictEnabledStrategiesCache() {
        // Spring AOP 處理快取清除，方法體留空
    }

    @Transactional
    public StrategyResponse createStrategy(CreateStrategyRequest request) {
        BtStrategy strategy = new BtStrategy();
        applyStrategyValues(strategy, request.getName(), request.getStrategyType(), false, request.getSymbols(), request.getConfig(), null);

        BtStrategy saved = strategyRepository.save(strategy);
        notifyTg(String.format("🆕 <b>策略建立</b>\nID: %d | %s\n類型: %s | 監控: %s\n狀態: <b>停用（待回測後啟用）</b>",
                saved.getId(), saved.getName(), saved.getStrategyType(),
                saved.getSymbols() != null ? saved.getSymbols() : "全部"));
        return toResponse(saved);
    }

    @Transactional
    public StrategyResponse updateStrategy(Long id, UpdateStrategyRequest request) {
        BtStrategy strategy = getRequired(id);
        boolean wasEnabled = Boolean.TRUE.equals(strategy.getEnabled());
        String oldConfig = strategy.getConfigJson();

        applyStrategyValues(strategy, request.getName(), request.getStrategyType(), request.getEnabled(), request.getSymbols(), request.getConfig(), request.getNotes());

        BtStrategy saved = strategyRepository.save(strategy);
        boolean isEnabled = Boolean.TRUE.equals(saved.getEnabled());
        String newConfig = saved.getConfigJson();

        String statusChange = (wasEnabled != isEnabled)
                ? (isEnabled ? "⚡ <b>啟用</b>" : "⏸ <b>停用</b>")
                : (isEnabled ? "✅ 啟用中" : "⏸ 停用中");
        String configNote = !java.util.Objects.equals(oldConfig, newConfig) ? "\n⚙️ 參數已更新" : "";
        notifyTg(String.format("✏️ <b>策略更新</b>\nID: %d | %s\n類型: %s\n狀態: %s%s",
                saved.getId(), saved.getName(), saved.getStrategyType(), statusChange, configNote));
        return toResponse(saved);
    }

    @Transactional
    public void deleteStrategy(Long id) {
        deleteStrategy(id, "MANUAL", "手動刪除");
    }

    @Transactional
    public void deleteStrategy(Long id, String deleteSource, String deleteReason) {
        BtStrategy strategy = getRequired(id);

        if (Boolean.TRUE.equals(strategy.getEnabled())) {
            throw new IllegalStateException("無法刪除已啟用的策略（ID=" + id + "），請先停用後再刪除");
        }

        boolean hasOpenPositions = !liveSignalRepository
                .findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(id).isEmpty();
        if (hasOpenPositions) {
            throw new IllegalStateException("策略 ID=" + id + " 仍有未出場的開倉，無法刪除");
        }

        String source = normalizeDeleteSource(deleteSource);
        String reason = (deleteReason == null || deleteReason.isBlank()) ? "未提供" : deleteReason.trim();
        notifyTg(String.format(
                "🗑 <b>策略刪除</b>\nID: %d | %s\n類型: %s\n來源: <b>%s</b>\n原因: %s",
                strategy.getId(), strategy.getName(), strategy.getStrategyType(), source, reason));
        liveSignalRepository.deleteByStrategyId(strategy.getId());
        // #242: Do NOT delete bt_backtest_result — its trades are ML training data.
        // V088 migration changes bt_backtest_result.strategy_id FK to ON DELETE SET NULL,
        // so deleting the strategy below will set strategy_id=NULL on the result rows.
        // Training view (vw_signal_training_v8_dedup) filters WHERE r.strategy_id IS NOT NULL.
        strategyRepository.delete(strategy);
    }

    private String normalizeDeleteSource(String deleteSource) {
        if (deleteSource == null || deleteSource.isBlank()) {
            return "手動";
        }
        return switch (deleteSource.trim().toUpperCase(Locale.ROOT)) {
            case "SYSTEM", "SCHEDULER", "AUTO" -> "系統";
            case "MCP" -> "MCP";
            default -> "手動";
        };
    }

    private void notifyTg(String msg) {
        try {
            notificationPort.broadcast(msg, true);
        } catch (Exception e) {
            log.warn("[Strategy] TG notify failed: {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public BtStrategy getRequired(Long id) {
        return strategyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("策略不存在: " + id));
    }

    @Transactional(readOnly = true)
    public StrategyResponse getStrategy(Long id) {
        return toResponse(getRequired(id));
    }

    @Transactional(readOnly = true)
    public List<StrategyResponse> queryStrategies(StrategyQueryRequest request) {
        StrategyQueryRequest queryRequest = request == null ? new StrategyQueryRequest() : request;

        if (queryRequest.getCreatedAtFrom() != null
                && queryRequest.getCreatedAtTo() != null
                && queryRequest.getCreatedAtFrom().isAfter(queryRequest.getCreatedAtTo())) {
            throw new IllegalArgumentException("createdAtFrom 不可晚於 createdAtTo");
        }

        Specification<BtStrategy> specification = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<Predicate>();

            if (queryRequest.getId() != null) {
                predicates.add(criteriaBuilder.equal(root.get("id"), queryRequest.getId()));
            }
            if (hasText(queryRequest.getName())) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("name")),
                        "%" + queryRequest.getName().trim().toLowerCase() + "%"));
            }
            if (hasText(queryRequest.getStrategyType())) {
                predicates.add(criteriaBuilder.equal(
                        criteriaBuilder.upper(root.get("strategyType")),
                        queryRequest.getStrategyType().trim().toUpperCase()));
            }
            if (queryRequest.getEnabled() != null) {
                predicates.add(criteriaBuilder.equal(root.get("enabled"), queryRequest.getEnabled()));
            }
            if (queryRequest.getCreatedAtFrom() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), queryRequest.getCreatedAtFrom()));
            }
            if (queryRequest.getCreatedAtTo() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("createdAt"), queryRequest.getCreatedAtTo()));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        List<BtStrategy> strategies = strategyRepository.findAll(specification, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<StrategyResponse> responses = new ArrayList<StrategyResponse>();
        for (BtStrategy strategy : strategies) {
            responses.add(toResponse(strategy));
        }
        return responses;
    }

    public Map<String, Object> parseConfig(String configJson) {
        try {
            return objectMapper.readValue(configJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            throw new IllegalArgumentException("策略設定 JSON 格式錯誤", ex);
        }
    }

    private SopMtfAdxConfig parseTypedConfig(String configJson) {
        if (configJson == null) return null;
        try {
            return objectMapper.readValue(configJson, SopMtfAdxConfig.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("策略設定 JSON 格式錯誤", ex);
        }
    }

    /**
     * 建立 AI 自動探勘生成的策略，並標記 aiGenerated=true 及所屬 discoveryBatch。
     * <p>
     * 若資料庫中已存在具有相同 {@code configFingerprint} 的 AI 策略，
     * 則直接回傳已存在的策略，而不建立重複記錄。
     */
    @Transactional
    public BtStrategy createAiGeneratedStrategy(String name, String strategyType,
                                                SopMtfAdxConfig config, String discoveryBatch,
                                                String symbols) {
        if (symbols == null || symbols.trim().isEmpty()) {
            throw new IllegalArgumentException("AI 生成策略必須指定 symbols（不可為空）");
        }
        String normalizedType = normalizeStrategyType(strategyType);
        strategyRegistry.getRequiredStrategy(normalizedType);  // validate strategy type

        String configJson = toJson(config);
        String fingerprint = computeFingerprint(normalizedType, configJson);

        Optional<BtStrategy> existing =
                strategyRepository.findFirstByConfigFingerprintAndAiGeneratedTrue(fingerprint);
        if (existing.isPresent()) {
            log.info("[AI探勘 {}] 發現相同配置的 AI 策略已存在 (id={}, name={})，跳過建立",
                    discoveryBatch, existing.get().getId(), existing.get().getName());
            return existing.get();
        }

        BtStrategy strategy = new BtStrategy();
        strategy.setName(name.trim());
        strategy.setStrategyType(normalizedType);
        strategy.setEnabled(false);  // AI 生成策略預設停用，需通過品質門檻後手動啟用
        strategy.setSymbols(symbols.trim());
        strategy.setConfigJson(configJson);
        strategy.setConfigFingerprint(fingerprint);
        strategy.setAiGenerated(true);
        strategy.setDiscoveryBatch(discoveryBatch);
        return strategyRepository.save(strategy);
    }

    private void applyStrategyValues(BtStrategy strategy,
                                     String name,
                                     String strategyType,
                                     Boolean enabled,
                                     String symbols,
                                     SopMtfAdxConfig config,
                                     String notes) {
        String normalizedStrategyType = normalizeStrategyType(strategyType);
        strategyRegistry.getRequiredStrategy(normalizedStrategyType);

        if (symbols == null || symbols.trim().isEmpty()) {
            throw new IllegalArgumentException("symbols 不可為空，請指定監控幣種（如 'BTCUSDT' 或 'BTCUSDT,ETHUSDT'）");
        }
        strategy.setName(name.trim());
        strategy.setStrategyType(normalizedStrategyType);
        strategy.setEnabled(enabled);
        strategy.setSymbols(symbols.trim());
        // #446: merge new typed-DTO values into existing config_json instead of overwriting,
        // so untyped fields (mihIndicator, buyBelow, sellAbove, useMaxIndicatorInBar 等
        // CMI / 自訂策略欄位) survive the roundtrip. SopMtfAdxConfig 只覆蓋自己宣告的欄位
        // 且 null 值不會清掉既有設定。
        strategy.setConfigJson(mergeConfigJson(strategy.getConfigJson(), config));
        // notes == null 視為「不更新」,避免 update 時誤清空既有備註
        if (notes != null) {
            strategy.setNotes(notes.trim().isEmpty() ? null : notes.trim());
        }
    }

    /**
     * #446 — Merge typed DTO non-null fields into the existing {@code config_json}.
     *
     * <p>Why: previously {@code toJson(config)} blindly overwrote the entire JSON column,
     * stripping any field not declared on {@link SopMtfAdxConfig} (e.g. CMI strategies'
     * {@code mihIndicator}, {@code buyBelow}, {@code sellAbove}). Adding every CMI /
     * future-strategy field to the shared DTO is unscalable; instead we merge.
     *
     * <p>Semantics:
     * <ul>
     *   <li>Existing JSON parsed as {@code Map<String,Object>}, becomes the base.</li>
     *   <li>DTO serialised with {@code Include.NON_NULL} → only non-null fields participate.</li>
     *   <li>Non-null DTO entries put over base entries (DTO wins on conflict — caller intent).</li>
     *   <li>Existing keys not present in DTO are preserved untouched.</li>
     *   <li>Null DTO fields don't erase existing values (callers must not rely on null=clear).</li>
     * </ul>
     *
     * <p>To explicitly clear a typed field, set it to a sentinel default (e.g. 0.0 or false)
     * rather than null — same as before the fix; this method only changes behaviour for
     * keys the DTO doesn't know about.
     */
    String mergeConfigJson(String existingJson, SopMtfAdxConfig config) {
        try {
            Map<String, Object> base;
            if (existingJson != null && !existingJson.isBlank()) {
                base = objectMapper.readValue(existingJson,
                        new TypeReference<java.util.LinkedHashMap<String, Object>>() {});
            } else {
                base = new java.util.LinkedHashMap<>();
            }
            if (config != null) {
                ObjectMapper nonNull = objectMapper.copy()
                        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
                String dtoJson = nonNull.writeValueAsString(config);
                Map<String, Object> incoming = nonNull.readValue(dtoJson,
                        new TypeReference<java.util.LinkedHashMap<String, Object>>() {});
                base.putAll(incoming);
            }
            return objectMapper.writeValueAsString(base);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("策略設定 JSON 合併失敗", ex);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("策略設定 JSON 序列化失敗", ex);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String normalizeStrategyType(String strategyType) {
        if (!hasText(strategyType)) {
            throw new IllegalArgumentException("strategyType 不可為空");
        }
        return strategyType.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Computes a 64-character hex SHA-256 fingerprint of {@code strategyType:configJson}.
     * Identical strategy configurations always produce the same fingerprint, enabling
     * deduplication of AI-generated strategies across discovery runs.
     */
    static String computeFingerprint(String strategyType, String configJson) {
        if (strategyType == null) throw new IllegalArgumentException("strategyType must not be null");
        if (configJson == null) throw new IllegalArgumentException("configJson must not be null");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((strategyType + ":" + configJson).getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[hash.length * 2];
            for (int i = 0; i < hash.length; i++) {
                hex[i * 2]     = HEX_CHARS[(hash[i] >>> 4) & 0xF];
                hex[i * 2 + 1] = HEX_CHARS[hash[i] & 0xF];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private StrategyResponse toResponse(BtStrategy strategy) {
        StrategyResponse response = new StrategyResponse();
        response.setId(strategy.getId());
        response.setName(strategy.getName());
        response.setStrategyType(strategy.getStrategyType());
        response.setEnabled(strategy.getEnabled());
        response.setAiGenerated(Boolean.TRUE.equals(strategy.getAiGenerated()));
        response.setDiscoveryBatch(strategy.getDiscoveryBatch());
        response.setSymbols(strategy.getSymbols());
        response.setConfig(parseTypedConfig(strategy.getConfigJson()));
        response.setNotes(strategy.getNotes());
        response.setAlphaSource(strategy.getAlphaSource());
        response.setTriggerConditions(strategy.getTriggerConditions());
        response.setCreatedAt(strategy.getCreatedAt());
        response.setUpdatedAt(strategy.getUpdatedAt());
        return response;
    }
}
