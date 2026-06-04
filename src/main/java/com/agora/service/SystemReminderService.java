package com.agora.service;

import com.agora.model.SystemReminder;
import com.agora.repository.system.SystemReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 系統預約提醒服務。
 *
 * <h3>核心流程</h3>
 * <ul>
 *   <li>{@link #create}: 預約一筆,fireAt 必須是未來時間</li>
 *   <li>{@link #cancel}: 把 PENDING 改為 CANCELLED(已 FIRED 不能取消)</li>
 *   <li>{@link #fireDueReminders}: Scheduler 每分鐘呼叫,發 TG 後標 FIRED/FAILED</li>
 * </ul>
 *
 * <h3>容錯</h3>
 * 單筆 TG 失敗標 FAILED + log,不影響其他 reminder。Scheduler 不會 retry FAILED 項目
 * (避免騷擾) — 若需重發,Claude 用 createReminder 重新預約即可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemReminderService {

    private final SystemReminderRepository repo;
    private final TelegramService telegramService;

    @Transactional
    public SystemReminder create(LocalDateTime fireAt, String message,
                                  String tag, String createdBy) {
        if (fireAt == null) throw new IllegalArgumentException("fireAt 為必填");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message 為必填");

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (!fireAt.isAfter(now)) {
            throw new IllegalArgumentException(
                    "fireAt 必須是未來時間(now=" + now + ", fireAt=" + fireAt + ")");
        }

        SystemReminder r = new SystemReminder();
        r.setFireAt(fireAt);
        r.setMessage(message.trim());
        r.setStatus("PENDING");
        r.setTag(tag != null && !tag.isBlank() ? tag.trim() : null);
        r.setCreatedBy(createdBy);
        r.setCreatedAt(now);
        return repo.save(r);
    }

    @Transactional
    public boolean cancel(Long id) {
        SystemReminder r = repo.findById(id).orElse(null);
        if (r == null) return false;
        if (!r.isPending()) {
            log.info("[Reminder] cancel skipped: id={} status={} (not PENDING)", id, r.getStatus());
            return false;
        }
        r.setStatus("CANCELLED");
        repo.save(r);
        log.info("[Reminder] cancelled: id={}", id);
        return true;
    }

    /**
     * 找所有 due 的 PENDING reminder,發 TG,更新狀態。
     * Scheduler 每 60 秒呼叫。
     */
    @Transactional
    public void fireDueReminders() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<SystemReminder> due = repo
                .findByStatusAndFireAtLessThanEqualOrderByFireAtAsc("PENDING", now);
        if (due.isEmpty()) return;

        log.info("[Reminder] firing {} due reminder(s)", due.size());
        for (SystemReminder r : due) {
            try {
                String tgMsg = String.format("⏰ <b>系統提醒</b>%s\n\n%s",
                        r.getTag() != null ? "(" + r.getTag() + ")" : "",
                        r.getMessage());
                telegramService.sendMessage(tgMsg, true);
                r.setStatus("FIRED");
                r.setFiredAt(LocalDateTime.now(ZoneOffset.UTC));
                r.setError(null);
                repo.save(r);
                log.info("[Reminder] fired id={} fireAt={}", r.getId(), r.getFireAt());
            } catch (Throwable t) {
                r.setStatus("FAILED");
                r.setFiredAt(LocalDateTime.now(ZoneOffset.UTC));
                r.setError(truncate(t.getMessage(), 500));
                repo.save(r);
                log.warn("[Reminder] fire failed id={}: {}", r.getId(), t.getMessage());
            }
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
