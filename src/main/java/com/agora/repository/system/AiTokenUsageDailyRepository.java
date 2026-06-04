package com.agora.repository.system;

import com.agora.model.AiTokenUsageDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AiTokenUsageDailyRepository extends JpaRepository<AiTokenUsageDaily, Long> {

    Optional<AiTokenUsageDaily> findByStatDateAndModel(LocalDate statDate, String model);

    List<AiTokenUsageDaily> findByStatDate(LocalDate statDate);

    List<AiTokenUsageDaily> findByStatDateBetweenOrderByStatDateAsc(LocalDate from, LocalDate to);

    @Query("SELECT COALESCE(SUM(r.promptTok + r.completeTok), 0) FROM AiTokenUsageDaily r " +
           "WHERE r.model = :model AND r.statDate BETWEEN :from AND :to")
    long sumTokensByModelAndDateRange(@Param("model") String model,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);

    /**
     * 原子累加：當天已有記錄則加上新用量，否則新建一筆。
     */
    @Modifying
    @Query(nativeQuery = true, value =
            "INSERT INTO ai_token_usage_daily (stat_date, model, req_count, prompt_tok, complete_tok, error_count, updated_at) " +
            "VALUES (:date, :model, :req, :prompt, :complete, :err, NOW()) " +
            "ON DUPLICATE KEY UPDATE " +
            "req_count    = req_count    + :req, " +
            "prompt_tok   = prompt_tok   + :prompt, " +
            "complete_tok = complete_tok + :complete, " +
            "error_count  = error_count  + :err, " +
            "updated_at   = NOW()")
    void upsert(@Param("date") LocalDate date,
                @Param("model") String model,
                @Param("req") int req,
                @Param("prompt") long prompt,
                @Param("complete") long complete,
                @Param("err") int err);
}
