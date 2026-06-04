package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Claude 對已平倉 bt_live_signal 的事後分析筆記。
 *
 * <p>每筆 position 可有 N 筆 annotation(不同 tag 或多次反思)。
 * 未來累積足夠樣本後可用於 Bayesian posterior 訓練,或讓 Claude
 * 彙整成「策略改善建議」。
 *
 * <p>常用 tag: WIN_STRUCTURAL / LOSS_CHOP / FALSE_BREAKOUT / REGIME_MISMATCH /
 * LATE_ENTRY / EARLY_EXIT / STOP_HUNT
 */
@Data
@Entity
@Table(name = "position_annotation", indexes = {
        @Index(name = "idx_pa_live", columnList = "live_signal_id"),
        @Index(name = "idx_pa_tag",  columnList = "tag,created_at")
})
public class PositionAnnotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** soft ref -> bt_live_signal.id */
    @Column(name = "live_signal_id", nullable = false)
    private Long liveSignalId;

    @Column(length = 32)
    private String tag;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
