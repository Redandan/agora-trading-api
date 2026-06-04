package com.agora.model;

import lombok.Data;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "tg_group_message_buffer", indexes = {
    @Index(name = "idx_tg_group_message_group_sent", columnList = "tg_group_id,sent_at"),
    @Index(name = "idx_tg_group_message_group_user", columnList = "tg_group_id,tg_user_id"),
    @Index(name = "idx_tg_group_message_group_created", columnList = "tg_group_id,created_at")
})
public class TgGroupMessageBuffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tg_group_id", nullable = false)
    private Long tgGroupId;

    @Column(name = "tg_user_id")
    private Long tgUserId;

    @Column(name = "tg_message_id")
    private Integer tgMessageId;

    @Lob
    @Column(name = "message_text")
    private String messageText;

    @Column(name = "message_type", nullable = false, length = 30)
    private String messageType;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
