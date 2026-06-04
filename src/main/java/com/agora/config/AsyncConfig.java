package com.agora.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 異步任務配置類
 * spring.threads.virtual.enabled=true 後，Spring Boot 3.2+ 會自動以虛擬執行緒
 * 驅動 @Async(未指定 executor 者)。
 *
 * <p>{@link #metaAuditExecutor()} 是 Meta-Control audit 專屬執行緒池,pool=2/queue=10000,
 * 滿時 {@code DiscardOldestPolicy} — 絕不 block 主交易流程,audit 寫入失敗可丟棄。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Default executor for unqualified {@code @Async}.
     *
     * <p>Without a bean named {@code taskExecutor}, Spring logs a warning and
     * falls back to an unnamed SimpleAsyncTaskExecutor at runtime. Keep this
     * pool bounded: startup listeners often touch JPA, and an unbounded
     * virtual-thread executor can exhaust the 10-connection Hikari pool.
     */
    @Bean("taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(3);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("async-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }

    @Bean("taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("trading-sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    @Bean("metaAuditExecutor")
    public ThreadPoolTaskExecutor metaAuditExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(2);
        exec.setQueueCapacity(10_000);
        exec.setThreadNamePrefix("meta-audit-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        exec.initialize();
        return exec;
    }

    @Bean("kbAsyncExecutor")
    public ThreadPoolTaskExecutor kbAsyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(2);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("kb-write-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        exec.initialize();
        return exec;
    }
}

