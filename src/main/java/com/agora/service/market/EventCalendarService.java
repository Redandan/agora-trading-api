package com.agora.service.market;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 高影響宏觀事件日曆（硬編碼）。
 *
 * <p>封鎖窗口：事件前 {@code window-before-hours}（預設 2h）至事件後
 * {@code window-after-hours}（預設 4h），這段時間拒絕新 LONG/SHORT 進場。
 *
 * <p>日曆來源：
 * <ul>
 *   <li>FOMC 會議（每 6 週一次，美東 14:00 ≈ UTC 18:00 或 19:00 依冬夏令時）</li>
 *   <li>US CPI 發布（每月 ~15 日 UTC 12:30，依 BLS 時程）</li>
 * </ul>
 *
 * <p>日期每年需手動更新（2026 年度已列入；下一年度請 grep TODO:NEXT-YEAR 補）。
 */
@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class EventCalendarService {

    public record Event(LocalDateTime time, String name) {}

    public record BlockResult(boolean blocked, Event event, Duration timeToEvent) {}

    private final com.agora.config.properties.EventCalendarProperties props;

    /**
     * 2026 年重要經濟事件（UTC 時間）。
     * TODO:NEXT-YEAR 2027 年的時程公布後在此追加。
     */
    private static final List<Event> EVENTS_2026 = List.of(
            // FOMC（美聯儲決議），2026 年時程（確認自 Fed 官方）
            new Event(LocalDateTime.of(2026, Month.JANUARY,   28, 19, 0), "FOMC Meeting"),
            new Event(LocalDateTime.of(2026, Month.MARCH,     18, 18, 0), "FOMC Meeting"),
            new Event(LocalDateTime.of(2026, Month.APRIL,     29, 18, 0), "FOMC Meeting"),
            new Event(LocalDateTime.of(2026, Month.JUNE,      17, 18, 0), "FOMC Meeting"),
            new Event(LocalDateTime.of(2026, Month.JULY,      29, 18, 0), "FOMC Meeting"),
            new Event(LocalDateTime.of(2026, Month.SEPTEMBER, 16, 18, 0), "FOMC Meeting"),
            new Event(LocalDateTime.of(2026, Month.OCTOBER,   28, 18, 0), "FOMC Meeting"),
            new Event(LocalDateTime.of(2026, Month.DECEMBER,   9, 19, 0), "FOMC Meeting"),

            // US CPI（消費者物價指數，每月約 15 日 UTC 12:30）
            new Event(LocalDateTime.of(2026, Month.JANUARY,   14, 13, 30), "US CPI"),
            new Event(LocalDateTime.of(2026, Month.FEBRUARY,  11, 13, 30), "US CPI"),
            new Event(LocalDateTime.of(2026, Month.MARCH,     12, 12, 30), "US CPI"),
            new Event(LocalDateTime.of(2026, Month.APRIL,     14, 12, 30), "US CPI"),
            new Event(LocalDateTime.of(2026, Month.MAY,       13, 12, 30), "US CPI"),
            new Event(LocalDateTime.of(2026, Month.JUNE,      11, 12, 30), "US CPI"),
            new Event(LocalDateTime.of(2026, Month.JULY,      15, 12, 30), "US CPI"),
            new Event(LocalDateTime.of(2026, Month.AUGUST,    13, 12, 30), "US CPI"),
            new Event(LocalDateTime.of(2026, Month.SEPTEMBER, 10, 12, 30), "US CPI"),
            new Event(LocalDateTime.of(2026, Month.OCTOBER,   14, 12, 30), "US CPI"),
            new Event(LocalDateTime.of(2026, Month.NOVEMBER,  12, 13, 30), "US CPI"),
            new Event(LocalDateTime.of(2026, Month.DECEMBER,  10, 13, 30), "US CPI")
    );

    /**
     * 檢查現在是否處於事件封鎖窗口。
     *
     * @return blocked=true 時應拒絕新倉位開立
     */
    public BlockResult checkBlock() {
        if (!props.enabled()) return new BlockResult(false, null, null);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (Event e : EVENTS_2026) {
            Duration toEvent = Duration.between(now, e.time());
            long hours = toEvent.toHours();
            // 負值 = 事件已過；正值 = 未來
            if (hours <= props.windowBeforeHours() && hours >= -props.windowAfterHours()) {
                return new BlockResult(true, e, toEvent);
            }
        }
        return new BlockResult(false, null, null);
    }

    /**
     * 取得未來 N 天內最接近的事件（供儀表板顯示）。
     */
    public Optional<Event> nextUpcoming(int withinDays) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime end = now.plusDays(withinDays);
        return EVENTS_2026.stream()
                .filter(e -> e.time().isAfter(now) && e.time().isBefore(end))
                .min(Comparator.comparing(Event::time));
    }

    /** 列出未來 N 天所有事件（MCP 工具使用）。 */
    public List<Event> listUpcoming(int withinDays) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime end = now.plusDays(withinDays);
        List<Event> out = new ArrayList<>();
        for (Event e : EVENTS_2026) {
            if (e.time().isAfter(now) && e.time().isBefore(end)) out.add(e);
        }
        out.sort(Comparator.comparing(Event::time));
        return out;
    }
}
