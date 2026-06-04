package com.agora.repository.system;

import com.agora.model.TgMonitoredGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TgMonitoredGroupRepository extends JpaRepository<TgMonitoredGroup, Long> {

    Optional<TgMonitoredGroup> findByTgGroupId(Long tgGroupId);

    List<TgMonitoredGroup> findAllByOrderByLastMessageAtDesc();
}
