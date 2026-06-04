package com.agora.service;

import com.agora.exception.BusinessException;
import com.agora.model.User;
import com.agora.model.UserWithdrawRiskState;
import com.agora.repository.system.UserRepository;
import com.agora.repository.system.UserWithdrawRiskStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Tier-based withdrawal rate limiting (no-KYC compensating control).
 *
 * Tiers:
 *   < 7 days   → no withdrawal
 *   7-30 days  → 500 USDT/day, 3000/month
 *   31-90 days, < 10 success → 2000/day, 10000/month
 *   > 90 days, ≥ 10 success → 10000/day, 50000/month
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawRiskService {

    private final UserWithdrawRiskStateRepository riskStateRepository;
    private final UserRepository userRepository;

    /**
     * Validates and records a withdrawal attempt.
     * Must be called BEFORE the wallet debit transaction.
     * Throws BusinessException if any limit is exceeded.
     */
    @Transactional
    public void checkAndRecord(Long userId, BigDecimal amount) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用戶不存在"));

        long accountAgeDays = ChronoUnit.DAYS.between(user.getCreatedAt(), LocalDateTime.now());

        if (accountAgeDays < 7) {
            throw new BusinessException("帳號需至少 7 天才能提款");
        }

        UserWithdrawRiskState state = riskStateRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserWithdrawRiskState s = new UserWithdrawRiskState();
                    s.setUserId(userId);
                    return s;
                });

        if (state.getCooldownUntil() != null && state.getCooldownUntil().isAfter(LocalDateTime.now())) {
            throw new BusinessException("帳號提款功能暫時凍結，請聯繫客服");
        }

        resetCountersIfStale(state);

        long[] limits = resolveLimits(accountAgeDays, state.getSuccessfulCount());
        long dailyLimit = limits[0];
        long monthlyLimit = limits[1];

        BigDecimal newDaily = state.getDailyAmount().add(amount);
        BigDecimal newMonthly = state.getMonthlyAmount().add(amount);

        if (newDaily.compareTo(BigDecimal.valueOf(dailyLimit)) > 0) {
            throw new BusinessException("今日提款金額超過限制 " + dailyLimit + " USDT");
        }
        if (newMonthly.compareTo(BigDecimal.valueOf(monthlyLimit)) > 0) {
            throw new BusinessException("本月提款金額超過限制 " + monthlyLimit + " USDT");
        }

        state.setDailyAmount(newDaily);
        state.setMonthlyAmount(newMonthly);
        if (state.getFirstWithdrawAt() == null) state.setFirstWithdrawAt(LocalDateTime.now());
        state.setLastWithdrawAt(LocalDateTime.now());
        riskStateRepository.save(state);
    }

    /**
     * Called when a withdrawal completes successfully — increments the success counter.
     */
    @Transactional
    public void recordSuccess(Long userId) {
        riskStateRepository.findByUserId(userId).ifPresent(state -> {
            state.setSuccessfulCount(state.getSuccessfulCount() + 1);
            riskStateRepository.save(state);
        });
    }

    private long[] resolveLimits(long ageDays, int successCount) {
        if (ageDays > 90 && successCount >= 10) return new long[]{10_000, 50_000};
        if (ageDays > 30) return new long[]{2_000, 10_000};
        return new long[]{500, 3_000};
    }

    private void resetCountersIfStale(UserWithdrawRiskState state) {
        LocalDateTime now = LocalDateTime.now();
        if (state.getLastWithdrawAt() == null) return;

        if (ChronoUnit.DAYS.between(state.getLastWithdrawAt(), now) >= 1) {
            state.setDailyAmount(BigDecimal.ZERO);
        }
        if (ChronoUnit.DAYS.between(state.getLastWithdrawAt(), now) >= 30) {
            state.setMonthlyAmount(BigDecimal.ZERO);
        }
    }
}
