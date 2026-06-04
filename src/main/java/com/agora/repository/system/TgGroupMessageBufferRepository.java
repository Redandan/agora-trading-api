package com.agora.repository.system;

import com.agora.model.TgGroupMessageBuffer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TgGroupMessageBufferRepository extends JpaRepository<TgGroupMessageBuffer, Long> {

    boolean existsByTgGroupIdAndTgMessageId(Long tgGroupId, Integer tgMessageId);

    long countByTgGroupId(Long tgGroupId);

    @Query("SELECT m FROM TgGroupMessageBuffer m WHERE m.tgGroupId = :groupId ORDER BY m.sentAt DESC, m.id DESC")
    List<TgGroupMessageBuffer> findRecentByGroupId(@Param("groupId") Long groupId, Pageable pageable);

    @Query(value = "SELECT id FROM tg_group_message_buffer WHERE tg_group_id = :groupId ORDER BY sent_at ASC, id ASC LIMIT :limit", nativeQuery = true)
    List<Long> findOldestIdsForTrim(@Param("groupId") Long groupId, @Param("limit") int limit);

    @Modifying
    @Query("DELETE FROM TgGroupMessageBuffer m WHERE m.id IN :ids")
    void deleteByIds(@Param("ids") List<Long> ids);

    @Query("SELECT COUNT(m) FROM TgGroupMessageBuffer m WHERE m.tgGroupId = :groupId AND m.sentAt >= :since")
    long countByGroupIdSince(@Param("groupId") Long groupId, @Param("since") LocalDateTime since);

    @Query("SELECT MAX(m.sentAt) FROM TgGroupMessageBuffer m WHERE m.tgGroupId = :groupId")
    LocalDateTime findLastMessageTime(@Param("groupId") Long groupId);

    @Query("SELECT COUNT(DISTINCT m.tgUserId) FROM TgGroupMessageBuffer m WHERE m.tgGroupId = :groupId AND m.tgUserId IS NOT NULL AND m.sentAt >= :since")
    long countDistinctUsersSince(@Param("groupId") Long groupId, @Param("since") LocalDateTime since);

    @Query("SELECT m.tgUserId, COUNT(m) FROM TgGroupMessageBuffer m WHERE m.tgGroupId = :groupId AND m.tgUserId IS NOT NULL GROUP BY m.tgUserId ORDER BY COUNT(m) DESC")
    List<Object[]> countMessagesByUserInGroup(@Param("groupId") Long groupId);
}
