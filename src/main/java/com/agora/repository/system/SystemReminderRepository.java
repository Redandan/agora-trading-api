package com.agora.repository.system;

import com.agora.model.SystemReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SystemReminderRepository extends JpaRepository<SystemReminder, Long> {

    /** Scheduler 用:取所有 status=PENDING 且 fire_at <= now 的提醒。 */
    List<SystemReminder> findByStatusAndFireAtLessThanEqualOrderByFireAtAsc(
            String status, LocalDateTime now);

    /** MCP listReminders 用:取所有 PENDING(按 fire_at 升序)。 */
    List<SystemReminder> findByStatusOrderByFireAtAsc(String status);

    /** 最近 N 筆 FIRED / CANCELLED / FAILED(歷史記錄)。 */
    List<SystemReminder> findTop20ByStatusInOrderByFireAtDesc(List<String> statuses);
}
