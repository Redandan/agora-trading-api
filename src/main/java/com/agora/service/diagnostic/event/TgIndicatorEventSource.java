package com.agora.service.diagnostic.event;

import com.agora.model.TgNotificationLog;
import com.agora.repository.system.TgNotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #337 EventSource: tg_indicator — 從 {@code tg_notification_log} 抽取指標警報事件。
 *
 * <p>filter = TG {@code source} 欄位的 LIKE pattern（例如 {@code "ShortBuildIndicator"}）。
 *
 * <p>方向推斷由 {@link IndicatorDirectionResolver} 提供顯式 lookup（取代原本錯的
 * keyword heuristic — "Squeeze" 是 LONG 訊號不是 SHORT）。
 *
 * <p>TZ：{@code TgNotificationLog.sentAt} 是 JVM {@code LocalDateTime.now()}，server JVM
 * 為 UTC → sentAt 在 Java 端代表 UTC wall-clock。比較時 JDBC round-trip 自動處理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TgIndicatorEventSource implements EventSource {

    private static final Pattern VALUE_PATTERN = Pattern.compile(
            "(?:score|sqi|sdi|vdi|mei|sbi|epi|value|level|ratio|count)\\s*[=:]\\s*([+-]?\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    private final TgNotificationLogRepository repo;
    private final IndicatorDirectionResolver directionResolver;

    @Override
    public String name() { return "tg_indicator"; }

    @Override
    public List<Event> fetch(String filter, LocalDateTime from, LocalDateTime to) {
        if (filter == null || filter.isBlank()) {
            log.warn("[TgIndicatorEventSource] filter required (TG source LIKE pattern)");
            return List.of();
        }
        String like = filter.contains("%") ? filter : "%" + filter + "%";
        try {
            List<TgNotificationLog> rows = repo.search(
                    from, to,
                    /* level */ null,
                    /* source LIKE */ like,
                    /* ruleId */ null,
                    PageRequest.of(0, 5000));
            List<Event> events = new ArrayList<>(rows.size());
            for (TgNotificationLog row : rows) {
                String src = row.getSource() != null ? row.getSource() : "";
                String direction = directionResolver.forTgSource(src);
                double payload = extractValue(row.getMessage());
                String label = src + (Double.isNaN(payload) ? "" : "=" + String.format("%.2f", payload));
                events.add(new Event(row.getSentAt(), direction, payload, label));
            }
            events.sort(Comparator.comparing(Event::ts));
            return events;
        } catch (Exception e) {
            log.warn("[TgIndicatorEventSource] fetch failed: filter={} reason={}", filter, e.getMessage());
            return List.of();
        }
    }

    private double extractValue(String message) {
        if (message == null) return Double.NaN;
        Matcher m = VALUE_PATTERN.matcher(message);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException ignore) {}
        }
        return Double.NaN;
    }
}
