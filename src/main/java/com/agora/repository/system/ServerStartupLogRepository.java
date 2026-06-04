package com.agora.repository.system;

import com.agora.model.ServerStartupLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServerStartupLogRepository extends JpaRepository<ServerStartupLog, Long> {

    List<ServerStartupLog> findTop10ByOrderByStartedAtDesc();
}
