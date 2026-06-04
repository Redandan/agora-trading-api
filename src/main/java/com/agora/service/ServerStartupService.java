package com.agora.service;

import com.agora.model.ServerStartupLog;
import com.agora.repository.system.ServerStartupLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 伺服器啟動時序紀錄服務。
 * 由 MarketWsAutoSubscriber 在各里程碑呼叫，不影響啟動主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServerStartupService {

    private static final ZoneId TZ = ZoneId.of("Asia/Taipei");

    private final ServerStartupLogRepository repo;

    /** 啟動時建立一筆 log，回傳 id 供後續更新用 */
    @Transactional
    public long recordStarted() {
        ServerStartupLog log = new ServerStartupLog();
        log.setStartedAt(LocalDateTime.now(TZ));
        return repo.save(log).getId();
    }

    /** WS 訂閱完成 */
    @Transactional
    public void recordWsReady(long logId) {
        repo.findById(logId).ifPresent(r -> {
            r.setWsReadyAt(LocalDateTime.now(TZ));
            repo.save(r);
        });
    }

    /** 暖機（首次策略評估）完成 */
    @Transactional
    public void recordFirstEval(long logId) {
        repo.findById(logId).ifPresent(r -> {
            r.setFirstEvalAt(LocalDateTime.now(TZ));
            repo.save(r);
            logSummary(r);
        });
    }

    /** 記錄備注（如 WS 訂閱部分失敗） */
    @Transactional
    public void recordNote(long logId, String note) {
        repo.findById(logId).ifPresent(r -> {
            r.setNote(note);
            repo.save(r);
        });
    }

    private void logSummary(ServerStartupLog r) {
        long wsDelay = r.getWsReadyAt() != null
                ? java.time.Duration.between(r.getStartedAt(), r.getWsReadyAt()).toMillis() : -1;
        long evalDelay = r.getFirstEvalAt() != null
                ? java.time.Duration.between(r.getStartedAt(), r.getFirstEvalAt()).toMillis() : -1;
        log.info("[StartupLog] id={} started={} ws=+{}ms firstEval=+{}ms",
                r.getId(), r.getStartedAt(), wsDelay, evalDelay);
    }
}
