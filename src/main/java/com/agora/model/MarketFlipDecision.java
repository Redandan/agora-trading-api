package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * {@link MarketFlipEvent} 的最終決策 — 共識合成器或人類 override 產出。
 *
 * <p>一個 event 對應最多一筆 decision(UNIQUE)。
 */
@Data
@Entity
@Table(name = "market_flip_decision", indexes = {
        @Index(name = "idx_mfd_decided",  columnList = "decided_at"),
        @Index(name = "idx_mfd_decider",  columnList = "decider")
})
public class MarketFlipDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private Long eventId;

    /** DISMISS / ALERT / TUNE / CREATE_RULE */
    @Column(name = "final_decision", nullable = false, length = 32)
    private String finalDecision;

    /** UNANIMOUS / MAJORITY / SPLIT / SINGLE_AI / HUMAN_OVERRIDE / AUTO_ESCALATED */
    @Column(name = "consensus_type", nullable = false, length = 32)
    private String consensusType;

    /** ai-consensus / session-xxx / human-via-tg / auto-escalate */
    @Column(nullable = false, length = 128)
    private String decider;

    @Column(columnDefinition = "TEXT")
    private String summary;

    /** JSON: 實際執行什麼 (TG message / threshold change / rule created) */
    @Column(name = "action_taken_json", columnDefinition = "TEXT")
    private String actionTakenJson;

    @Column(name = "decided_at", nullable = false)
    private LocalDateTime decidedAt;
}
