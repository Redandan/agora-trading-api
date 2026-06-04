package com.agora.repository.system;

import com.agora.model.ClientLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClientLogRepository extends JpaRepository<ClientLog, Long> {

    /**
     * 按用戶ID查詢日誌，按時間戳降序排列
     */
    Page<ClientLog> findByUserIdOrderByTimestampDesc(Long userId, Pageable pageable);
}
