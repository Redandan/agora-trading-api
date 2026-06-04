package com.agora.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import com.agora.service.FlutterDeploymentSecurityService;
import com.agora.service.TelegramService;

@Configuration
public class CacheConfig {

    private final TelegramService telegramService;
    private final FlutterDeploymentSecurityService flutterDeploymentSecurityService;

    public CacheConfig(@Lazy TelegramService telegramService,
                       @Lazy FlutterDeploymentSecurityService flutterDeploymentSecurityService) {
        this.telegramService = telegramService;
        this.flutterDeploymentSecurityService = flutterDeploymentSecurityService;
    }

    @Async("taskExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        telegramService.removePinnedKeys("flutter-deploy-token", "service-update");

        java.time.LocalDateTime startupAt = java.time.LocalDateTime.now().withNano(0);
        String msg = String.format(
            "🔐 Flutter 部署 Token\n" +
            "%s\n\n" +
            "Token 更新,服務啟動: %s\n\n" +
            "請更新客戶端腳本，舊 token 已失效。",
                flutterDeploymentSecurityService.getToken(),
            startupAt
        );
        telegramService.sendOrEditPinned("startup-status", msg, true, true);
    }
}
