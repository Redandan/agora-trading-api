package com.agora.repository.system;

import com.agora.model.ChatSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, String>, JpaSpecificationExecutor<ChatSession> {
    
    /**
     * 查找用戶參與的所有會話
     */
    @Query("SELECT s FROM ChatSession s WHERE s.userId = :userId OR s.partnerId = :userId " +
           "ORDER BY s.pinned DESC, s.latestMessageTime DESC")
    List<ChatSession> findSessionsByUserId(@Param("userId") Long userId);
    
    /**
     * 查找用戶參與的所有會話（用於未讀計數）
     */
    @Query("SELECT s FROM ChatSession s WHERE s.userId = :userId OR s.partnerId = :userId")
    List<ChatSession> findByUserIdOrPartnerId(@Param("userId") Long userId);
    
    /**
     * 統計用戶的未讀消息總數
     */
    @Query("SELECT COALESCE(SUM(" +
           "CASE WHEN s.userId = :userId THEN s.userUnreadCount " +
           "ELSE s.partnerUnreadCount END), 0) " +
           "FROM ChatSession s WHERE (s.userId = :userId OR s.partnerId = :userId)")
    Long countUnreadMessagesByUserId(@Param("userId") Long userId);
    
    /**
     * 查找用戶的未讀會話
     */
    @Query("SELECT s FROM ChatSession s WHERE " +
           "(s.userId = :userId OR s.partnerId = :userId) AND " +
           "(CASE WHEN s.userId = :userId THEN s.userUnreadCount " +
           "ELSE s.partnerUnreadCount END) > 0 " +
           "ORDER BY s.latestMessageTime DESC")
    List<ChatSession> findUnreadSessionsByUserId(@Param("userId") Long userId);
    
    /**
     * 統計用戶的總未讀消息數（優化版本）
     */
    @Query("SELECT COALESCE(SUM(" +
           "CASE WHEN s.userId = :userId THEN " +
           "  CASE WHEN s.userReadMessageId IS NULL THEN " +
           "    (SELECT COUNT(m) FROM ChatMessage m WHERE m.sessionId = s.id AND m.receiverId = :userId) " +
           "  ELSE " +
           "    (SELECT COUNT(m) FROM ChatMessage m WHERE m.sessionId = s.id AND m.receiverId = :userId AND m.id > s.userReadMessageId) " +
           "  END " +
           "ELSE " +
           "  CASE WHEN s.partnerReadMessageId IS NULL THEN " +
           "    (SELECT COUNT(m) FROM ChatMessage m WHERE m.sessionId = s.id AND m.receiverId = :userId) " +
           "  ELSE " +
           "    (SELECT COUNT(m) FROM ChatMessage m WHERE m.sessionId = s.id AND m.receiverId = :userId AND m.id > s.partnerReadMessageId) " +
           "  END " +
           "END), 0) " +
           "FROM ChatSession s WHERE (s.userId = :userId OR s.partnerId = :userId)")
    Long getTotalUnreadCountOptimized(@Param("userId") Long userId);
    
    // ========== 優化的分頁查詢方法 ==========
    
    /**
     * 查找用戶參與的會話（分頁，優化版本）
     */
    @Query("SELECT s FROM ChatSession s WHERE s.userId = :userId OR s.partnerId = :userId " +
           "ORDER BY s.pinned DESC, s.latestMessageTime DESC")
    Page<ChatSession> findSessionsByUserIdPageable(@Param("userId") Long userId, Pageable pageable);
    
    /**
     * 查找用戶的未讀會話（分頁）
     */
    @Query("SELECT s FROM ChatSession s WHERE " +
           "(s.userId = :userId OR s.partnerId = :userId) AND " +
           "(CASE WHEN s.userId = :userId THEN s.userUnreadCount " +
           "ELSE s.partnerUnreadCount END) > 0 " +
           "ORDER BY s.pinned DESC, s.latestMessageTime DESC")
    Page<ChatSession> findUnreadSessionsByUserIdPageable(@Param("userId") Long userId, Pageable pageable);
    
    /**
     * 查找用戶的置頂會話（分頁）
     */
    @Query("SELECT s FROM ChatSession s WHERE " +
           "(s.userId = :userId OR s.partnerId = :userId) AND s.pinned = true " +
           "ORDER BY s.latestMessageTime DESC")
    Page<ChatSession> findPinnedSessionsByUserIdPageable(@Param("userId") Long userId, Pageable pageable);
} 