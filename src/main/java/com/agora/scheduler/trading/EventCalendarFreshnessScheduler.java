package com.agora.scheduler.trading;

import com.agora.infra.notification.NotificationPort;
import com.agora.service.market.EventCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 每週一 00:00 UTC 檢查事件日曆是否即將用盡。
 *
 * <p>EventCalendarService 的事件列表是硬編碼 2026 年度；2027 年度需手動更新。
 * 此 scheduler 在未來 60 天的事件數量低於門檻時發 TG 提醒，避免日曆靜默失效。
 *
 * <p>典型觸發時機：年末 Q4 ~ 新年度事件尚未補入前，會持續提醒直到補完。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventCalendarFreshnessScheduler {

    private static final int LOW_THRESHOLD = 2;
    private static final int LOOK_AHEAD_DAYS = 60;

    private final EventCalendarService eventCalendarService;
    private final NotificationPort notificationPort;

    /** 每週一 00:02 UTC 檢查（錯開 DailyReportScheduler 00:00，避免 TG rate limit）；若未來 60 天內事件 < 2 個，發 TG 警告。 */
    @Scheduled(cron = "0 2 0 * * MON")
    public void checkFreshness() {
        try {
            List<EventCalendarService.Event> upcoming = eventCalendarService.listUpcoming(LOOK_AHEAD_DAYS);
            if (upcoming.size() < LOW_THRESHOLD) {
                log.warn("[EventCalendar] 未來 {} 天剩餘事件僅 {} 個，日曆可能需要更新",
                        LOOK_AHEAD_DAYS, upcoming.size());
                notificationPort.broadcast(String.format(
                        "⚠️ <b>事件日曆即將用盡</b>\n未來 %d 天內僅剩 %d 個事件。\n"
                                + "請更新 EventCalendarService.java 中 TODO:NEXT-YEAR 標記處的硬編碼清單。",
                        LOOK_AHEAD_DAYS, upcoming.size()), true);
            } else {
                log.info("[EventCalendar] Freshness OK: {} events in next {}d",
                        upcoming.size(), LOOK_AHEAD_DAYS);
            }
        } catch (Exception e) {
            log.error("[EventCalendar] Freshness check failed: {}", e.getMessage(), e);
        }
    }
}
