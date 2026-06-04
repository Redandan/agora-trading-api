package com.agora.service.system;

import com.agora.config.properties.OciMaintenanceNotifierProperties;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.TgNotificationDeduper;
import com.agora.service.TgNotificationDeduper.Severity;
import com.oracle.bmc.announcementsservice.model.AnnouncementSummary;
import com.oracle.bmc.announcementsservice.model.BaseAnnouncement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class OciMaintenanceAnnouncementNotifier {

    static final String SOURCE_PREFIX = "OciMaintenance:";

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter UTC_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
                    .withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter TPE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'Asia/Taipei'", Locale.US)
                    .withZone(TAIPEI);

    private final NotificationPort notificationPort;
    private final TgNotificationDeduper deduper;
    private final OciMaintenanceNotifierProperties props;

    public int notifyRelevant(List<AnnouncementSummary> announcements, Instant now) {
        if (!props.enabled() || announcements == null || announcements.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (AnnouncementSummary announcement : announcements) {
            if (!isRelevant(announcement, now)) {
                continue;
            }
            String key = SOURCE_PREFIX + stableKey(announcement);
            Duration ttl = Duration.ofMinutes(props.dedupTtlMinutes());
            if (!deduper.shouldSend(key, ttl, Severity.WARN)) {
                log.debug("[OciMaintenance] duplicate suppressed key={}", key);
                continue;
            }

            notificationPort.alert(formatMessage(announcement), true, key, "WARN");
            sent++;
            log.warn("[OciMaintenance] TG maintenance notice sent key={} summary={}",
                    key, announcement.getSummary());
        }
        return sent;
    }

    boolean isRelevant(AnnouncementSummary a, Instant now) {
        if (a == null) return false;
        if (a.getLifecycleState() != BaseAnnouncement.LifecycleState.Active) return false;
        if (!matchesAny(join(a.getServices()), props.serviceKeywords())) return false;
        if (!isMaintenanceLike(a)) return false;

        Instant eventTime = toInstant(a.getTimeOneValue());
        if (eventTime == null) {
            // Some OCI notices are actionable without a timestamp. If the notice
            // is active and clearly maintenance-like, send it once.
            return true;
        }
        Instant earliest = now.minus(Duration.ofHours(props.lookbackHours()));
        Instant latest = now.plus(Duration.ofHours(props.lookaheadHours()));
        return !eventTime.isBefore(earliest) && !eventTime.isAfter(latest);
    }

    private boolean isMaintenanceLike(AnnouncementSummary a) {
        BaseAnnouncement.AnnouncementType type = a.getAnnouncementType();
        if (type == BaseAnnouncement.AnnouncementType.PlannedChange
                || type == BaseAnnouncement.AnnouncementType.ScheduledMaintenance
                || type == BaseAnnouncement.AnnouncementType.EmergencyMaintenance
                || type == BaseAnnouncement.AnnouncementType.EmergencyChange
                || type == BaseAnnouncement.AnnouncementType.PlannedChangeExtended
                || type == BaseAnnouncement.AnnouncementType.PlannedChangeRescheduled
                || type == BaseAnnouncement.AnnouncementType.EmergencyMaintenanceExtended
                || type == BaseAnnouncement.AnnouncementType.EmergencyMaintenanceRescheduled) {
            return true;
        }
        return matchesAny(a.getSummary(), props.summaryKeywords());
    }

    private String stableKey(AnnouncementSummary a) {
        if (a.getId() != null && !a.getId().isBlank()) {
            return a.getId();
        }
        return String.join(":",
                Objects.toString(a.getReferenceTicketNumber(), "no-ticket"),
                Objects.toString(a.getChainId(), "no-chain"),
                Objects.toString(a.getTimeOneValue(), "no-time"));
    }

    private String formatMessage(AnnouncementSummary a) {
        Instant eventTime = toInstant(a.getTimeOneValue());
        StringBuilder sb = new StringBuilder();
        sb.append("<b>OCI MySQL 維護通知</b>\n");
        sb.append("摘要: ").append(escape(a.getSummary())).append('\n');
        sb.append("服務: ").append(escape(join(a.getServices()))).append('\n');
        sb.append("區域: ").append(escape(join(a.getAffectedRegions()))).append('\n');
        sb.append("類型: ").append(escape(valueOf(a.getAnnouncementType()))).append('\n');
        sb.append("狀態: ").append(escape(valueOf(a.getLifecycleState()))).append('\n');
        if (eventTime != null) {
            sb.append("開始時間: ").append(UTC_FMT.format(eventTime)).append('\n');
            sb.append("台北時間: ").append(TPE_FMT.format(eventTime)).append('\n');
        }
        if (a.getReferenceTicketNumber() != null) {
            sb.append("工單: ").append(escape(a.getReferenceTicketNumber())).append('\n');
        }
        sb.append("\n可能影響: 維護期間 DB 連線可能短暫抖動、超時或重連。\n");
        sb.append("建議觀察: /api/actuator/health 與 10.0.0.119:3306。");
        return sb.toString();
    }

    private static Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        return String.join(", ", values);
    }

    private static boolean matchesAny(String value, String csvKeywords) {
        if (value == null || csvKeywords == null || csvKeywords.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return Arrays.stream(csvKeywords.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .anyMatch(lower::contains);
    }

    private static String valueOf(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
