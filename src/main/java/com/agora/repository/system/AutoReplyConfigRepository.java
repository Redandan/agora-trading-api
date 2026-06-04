package com.agora.repository.system;

import com.agora.model.AutoReplyConfig;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Repository;

@Repository
public interface AutoReplyConfigRepository extends JpaRepository<AutoReplyConfig, Long> {
    
    /**
     * 複合搜尋配置
     */
    @Query("SELECT a FROM AutoReplyConfig a WHERE " +
           "(:name IS NULL OR a.name LIKE %:name%) AND " +
           "(:keyword IS NULL OR a.keyword LIKE %:keyword%) AND " +
           "(:enabled IS NULL OR a.enabled = :enabled) AND " +
           "(:minPriority IS NULL OR a.priority >= :minPriority) AND " +
           "(:maxPriority IS NULL OR a.priority <= :maxPriority) AND " +
           "(:minHitCount IS NULL OR a.hitCount >= :minHitCount) AND " +
           "(:maxHitCount IS NULL OR a.hitCount <= :maxHitCount)")
    Page<AutoReplyConfig> searchConfigs(
            @Param("name") String name,
            @Param("keyword") String keyword,
            @Param("enabled") Boolean enabled,
            @Param("minPriority") Integer minPriority,
            @Param("maxPriority") Integer maxPriority,
            @Param("minHitCount") Long minHitCount,
            @Param("maxHitCount") Long maxHitCount,
            Pageable pageable);

    /**
     * 統計總命中次數
     */
    @Query("SELECT SUM(a.hitCount) FROM AutoReplyConfig a WHERE a.enabled = true")
    Long getTotalHitCount();
    
    /**
     * 檢查是否存在配置
     */
    boolean existsById(@NonNull Long id);
    
    /**
     * 檢查名稱是否已存在
     */
    boolean existsByName(String name);
    
    /**
     * 檢查關鍵詞是否已存在
     */
    boolean existsByKeyword(String keyword);
}