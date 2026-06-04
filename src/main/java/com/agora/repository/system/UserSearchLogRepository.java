package com.agora.repository.system;

import com.agora.model.UserSearchLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用戶搜尋紀錄Repository（AOP異步記錄）
 */
@Repository
public interface UserSearchLogRepository extends JpaRepository<UserSearchLog, Long> {

    @Query("""
            select l.normalizedKeyword as normalizedKeyword,
                   min(l.rawQuery) as sampleRawQuery,
                   count(l) as searchCount,
                   sum(case when l.zeroResult = true then 1 else 0 end) as zeroResultCount,
                   avg(coalesce(l.resultCount, 0)) as avgResultCount,
                   max(l.createdAt) as lastSeenAt
            from UserSearchLog l
            where l.searchType = 'PRODUCT'
              and l.createdAt >= :since
              and l.normalizedKeyword is not null
              and l.normalizedKeyword <> ''
            group by l.normalizedKeyword
            order by sum(case when l.zeroResult = true then 1 else 0 end) desc,
                     count(l) desc,
                     max(l.createdAt) desc
            """)
    List<ProductSearchKeywordStats> findProductSearchKeywordStats(
            @Param("since") LocalDateTime since,
            Pageable pageable);

    interface ProductSearchKeywordStats {
        String getNormalizedKeyword();

        String getSampleRawQuery();

        Long getSearchCount();

        Long getZeroResultCount();

        Double getAvgResultCount();

        LocalDateTime getLastSeenAt();
    }
}
