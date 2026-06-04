package com.agora.service.meta;

import com.agora.model.StrategyOverride;
import com.agora.repository.trading.StrategyOverrideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Strategy override(暫停/調參)處理邏輯。
 *
 * <p><b>冪等性</b>:重複呼叫 {@code pauseStrategy(同 scope)} 只會延長 expires_at,不產生多筆。
 *
 * <p><b>TTL 硬上限</b>:{@code meta-control.override.max-ttl-hours}(預設 24h),
 * 超過此值直接拒絕 —— 防止 Claude 濫用。
 *
 * <p><b>Scope 比對</b>:symbol / interval 均 null 表「所有」,比對規則:
 * override 的 scope 必須 ⊇ 要檢查的 (strategy, symbol, interval)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyOverrideService {

    private final StrategyOverrideRepository repo;

    @Value("${meta-control.override.max-ttl-hours:24}")
    private int maxTtlHours;

    /**
     * 查詢指定 (strategy, symbol, interval) 當前是否有 active PAUSE override。
     * 供 LiveSignalEvaluator.evaluateStrategy() 入口呼叫。
     */
    public Optional<StrategyOverride> findActivePause(Long strategyId, String symbol, String intervalCode) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return repo.findActive(strategyId, now).stream()
                .filter(o -> "PAUSE".equals(o.getAction()))
                .filter(o -> scopeMatches(o, symbol, intervalCode))
                .findFirst();
    }

    /**
     * PAUSE 指定策略(冪等)。
     *
     * @return (成功訊息, override_id) 或拒絕原因
     */
    @Transactional
    public PauseResult pauseStrategy(Long strategyId, String symbol, String intervalCode,
                                      int ttlMinutes, String reason, String createdBy) {
        if (ttlMinutes <= 0 || ttlMinutes > maxTtlHours * 60) {
            return PauseResult.rejected(String.format(
                    "ttlMinutes %d 超出範圍 (1~%d)", ttlMinutes, maxTtlHours * 60));
        }
        if (reason == null || reason.isBlank()) {
            return PauseResult.rejected("reason 為必填");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime newExpiry = now.plusMinutes(ttlMinutes);

        // 冪等:查現有 active PAUSE(相同 scope)
        Optional<StrategyOverride> existing = repo.findActive(strategyId, now).stream()
                .filter(o -> "PAUSE".equals(o.getAction()))
                .filter(o -> java.util.Objects.equals(o.getSymbol(), symbol))
                .filter(o -> java.util.Objects.equals(o.getIntervalCode(), intervalCode))
                .findFirst();

        StrategyOverride ov;
        boolean extended;
        if (existing.isPresent()) {
            ov = existing.get();
            // 只有新的 expiry 更晚才更新(避免縮短)
            if (newExpiry.isAfter(ov.getExpiresAt())) {
                ov.setExpiresAt(newExpiry);
                ov.setReason(ov.getReason() + " | extended: " + reason);
                repo.save(ov);
                extended = true;
            } else {
                extended = false;
            }
        } else {
            ov = new StrategyOverride();
            ov.setStrategyId(strategyId);
            ov.setSymbol(symbol);
            ov.setIntervalCode(intervalCode);
            ov.setAction("PAUSE");
            ov.setReason(reason);
            ov.setCreatedBy(createdBy);
            ov.setCreatedAt(now);
            ov.setExpiresAt(newExpiry);
            repo.save(ov);
            extended = false;
        }
        return PauseResult.ok(ov.getId(), extended, ov.getExpiresAt());
    }

    /** 撤銷所有匹配 scope 的 active PAUSE。no-op 不當 error。 */
    @Transactional
    public int resumeStrategy(Long strategyId, String symbol, String intervalCode) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<StrategyOverride> actives = repo.findActive(strategyId, now).stream()
                .filter(o -> "PAUSE".equals(o.getAction()))
                .filter(o -> scopeMatches(o, symbol, intervalCode))
                .toList();
        for (StrategyOverride ov : actives) {
            ov.setRevokedAt(now);
            repo.save(ov);
        }
        return actives.size();
    }

    /**
     * Override 的 scope ⊇ 要檢查的 (symbol, interval)?
     * override.symbol=null → 適用所有 symbols;反之必須精確匹配。
     */
    private boolean scopeMatches(StrategyOverride ov, String symbol, String intervalCode) {
        if (ov.getSymbol() != null && !ov.getSymbol().equalsIgnoreCase(symbol)) return false;
        if (ov.getIntervalCode() != null && !ov.getIntervalCode().equalsIgnoreCase(intervalCode)) return false;
        return true;
    }

    public record PauseResult(boolean ok, Long overrideId, boolean extended,
                               LocalDateTime expiresAt, String error) {
        public static PauseResult ok(Long id, boolean extended, LocalDateTime expiry) {
            return new PauseResult(true, id, extended, expiry, null);
        }
        public static PauseResult rejected(String error) {
            return new PauseResult(false, null, false, null, error);
        }
    }
}
