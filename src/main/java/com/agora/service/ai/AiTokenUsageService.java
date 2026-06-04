package com.agora.service.ai;

import com.agora.model.AiTokenUsageDaily;
import com.agora.repository.system.AiTokenUsageDailyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Groq AI Token 用量持久化服務。
 * 每次 Groq API 呼叫後非同步寫入，不影響主流程延遲。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTokenUsageService {

    private final AiTokenUsageDailyRepository repo;

    /**
     * 記錄一次 Groq API 呼叫用量（非同步，不阻塞呼叫端）。
     *
     * @param model           使用的模型名稱
     * @param promptTokens    本次 prompt tokens
     * @param completionTokens 本次 completion tokens
     * @param error           是否為失敗請求
     */
    @Async
    @Transactional
    public void record(String model, int promptTokens, int completionTokens, boolean error) {
        try {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));
            repo.upsert(today, model, 1, promptTokens, completionTokens, error ? 1 : 0);
        } catch (Exception e) {
            log.warn("[AiTokenUsage] Failed to record usage: {}", e.getMessage());
        }
    }

    /**
     * 取得今日指定模型的用量統計。
     */
    public Optional<AiTokenUsageDaily> getToday(String model) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));
        return repo.findByStatDateAndModel(today, model);
    }

    /**
     * 取得今日所有模型的用量統計（Groq、Gemini、Jina 等）。
     */
    public List<AiTokenUsageDaily> getAllToday() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));
        return repo.findByStatDate(today);
    }

    /**
     * 取得本月指定模型的累計 token 用量（prompt + completion）。
     */
    public long getMonthlyTokens(String model) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        return repo.sumTokensByModelAndDateRange(model, firstOfMonth, today);
    }
}
