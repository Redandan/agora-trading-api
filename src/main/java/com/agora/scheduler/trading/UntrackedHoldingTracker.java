package com.agora.scheduler.trading;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * #340 — 對 reconcileHoldings 偵測到的「未追蹤持倉」做 2-cycle confirmation，避免 grid
 * BUY 成交瞬間 OKX trade poll 還沒回寫 filledQty 造成的 race-condition false positive。
 *
 * <p>規則：
 * <ul>
 *   <li>第一次看到某 (currency, diff) 只 seed，回傳 false（不發 TG）。</li>
 *   <li>連續第二次看到同一 (currency, diff) 且距離 seed >= 8 min（reconcile 10 min 留 buffer），
 *       回傳 true（confirm，可發 TG）並從候選表移除避免重複 fire。</li>
 *   <li>超過 30 min 沒再看到 → 視為 race 已自我修復，cleanup() 自動清除。</li>
 * </ul>
 *
 * <p>In-memory；服務重啟即清空 — 重啟後若仍有 orphan 會重新確認 1 次（最多延遲 20 min）。
 */
@Slf4j
@Component
public class UntrackedHoldingTracker {

    private static final long CONFIRM_AFTER_MINUTES = 8;
    private static final long STALE_AFTER_MINUTES = 30;

    private final Map<String, LocalDateTime> pendingUntracked = new ConcurrentHashMap<>();

    /**
     * 第一次呼叫只 seed 並回傳 false；第二次以後（距 seed ≥ 8 min）回傳 true 並移除候選。
     */
    public boolean confirmOrSeed(String currency, BigDecimal diff, LocalDateTime now) {
        String key = key(currency, diff);
        LocalDateTime first = pendingUntracked.get(key);
        if (first == null) {
            pendingUntracked.put(key, now);
            log.info("[UntrackedTracker] seeded candidate {} diff={} — wait for next cycle", currency, diff);
            return false;
        }
        if (Duration.between(first, now).toMinutes() >= CONFIRM_AFTER_MINUTES) {
            pendingUntracked.remove(key);
            log.info("[UntrackedTracker] confirmed {} diff={} (seeded {} min ago)",
                    currency, diff, Duration.between(first, now).toMinutes());
            return true;
        }
        return false;
    }

    /** 該 currency 已對齊（diff 歸零或變動）→ 清掉所有舊候選避免錯誤 confirm。*/
    public void clear(String currency) {
        String prefix = currency + ":";
        pendingUntracked.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
    }

    /** 移除超過 STALE_AFTER_MINUTES 沒再被觸發的候選 — 認定 race 已自我修復。*/
    public void cleanup(LocalDateTime now) {
        pendingUntracked.entrySet().removeIf(e ->
                Duration.between(e.getValue(), now).toMinutes() > STALE_AFTER_MINUTES);
    }

    private static String key(String currency, BigDecimal diff) {
        return currency + ":" + diff.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }
}
