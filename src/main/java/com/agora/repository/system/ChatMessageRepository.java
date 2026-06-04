package com.agora.repository.system;

import com.agora.model.ChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long>, JpaSpecificationExecutor<ChatMessage> {
    
    /**
     * 根據會話ID物理刪除所有消息
     */
    @Modifying
    @Query("DELETE FROM ChatMessage m WHERE m.sessionId = :sessionId")
    void deleteBySessionId(@Param("sessionId") String sessionId);
    
    /**
     * 統計會話中指定用戶的未讀消息數（在指定消息ID之後）
     */
    @Query("SELECT COUNT(m) FROM ChatMessage m WHERE m.sessionId = :sessionId " +
           "AND m.receiverId = :userId AND m.id > :afterMessageId")
    Long countUnreadAfterMessageId(@Param("sessionId") String sessionId, 
                                  @Param("userId") Long userId, 
                                  @Param("afterMessageId") Long afterMessageId);
    
    /**
     * 統計會話中指定用戶的所有消息數
     */
    @Query("SELECT COUNT(m) FROM ChatMessage m WHERE m.sessionId = :sessionId " +
           "AND m.receiverId = :userId")
    Long countBySessionIdAndReceiverId(@Param("sessionId") String sessionId, 
                                     @Param("userId") Long userId);
    
    // ========== 優化的查詢方法 ==========
    
    /**
     * 根據會話ID查詢消息（優化版本，使用索引）
     */
    @Query("SELECT m FROM ChatMessage m WHERE m.sessionId = :sessionId " +
           "ORDER BY m.createdAt DESC")
    Page<ChatMessage> findBySessionIdOrderByCreatedAtDesc(
        @Param("sessionId") String sessionId, 
        Pageable pageable);
    
    /**
     * 根據會話ID和時間範圍查詢消息（優化版本）
     */
    @Query("SELECT m FROM ChatMessage m WHERE m.sessionId = :sessionId " +
           "AND (:startDate IS NULL OR m.createdAt >= :startDate) " +
           "AND (:endDate IS NULL OR m.createdAt <= :endDate) " +
           "ORDER BY m.createdAt DESC")
    Page<ChatMessage> findBySessionIdWithDateRange(
        @Param("sessionId") String sessionId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate,
        Pageable pageable);
    
    /**
     * 使用原生SQL查詢（最高性能）
     */
    @Query(value = "SELECT * FROM chat_messages WHERE session_id = :sessionId " +
                   "ORDER BY created_at DESC LIMIT :limit OFFSET :offset", 
           nativeQuery = true)
    List<ChatMessage> findBySessionIdWithLimitNative(
        @Param("sessionId") String sessionId, 
        @Param("limit") int limit, 
        @Param("offset") int offset);
    
    /**
     * 統計會話中的消息總數（優化版本）
     */
    @Query("SELECT COUNT(m) FROM ChatMessage m WHERE m.sessionId = :sessionId")
    Long countBySessionId(@Param("sessionId") String sessionId);
    
    /**
     * 根據會話ID和時間範圍統計消息數
     */
    @Query("SELECT COUNT(m) FROM ChatMessage m WHERE m.sessionId = :sessionId " +
           "AND (:startDate IS NULL OR m.createdAt >= :startDate) " +
           "AND (:endDate IS NULL OR m.createdAt <= :endDate)")
    Long countBySessionIdWithDateRange(
        @Param("sessionId") String sessionId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate);
} 