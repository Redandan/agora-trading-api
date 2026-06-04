package com.agora.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ai_task_artifact")
public class AiTaskArtifact {

    public enum ArtifactType {
        INBOX_ID,
        HTML_REPORT,
        KB_TOPIC,
        JSON_PAYLOAD,
        LOG
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private AiTaskRecord task;

    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_type", nullable = false, length = 40)
    private ArtifactType artifactType;

    @Column(name = "uri_or_value", nullable = false, columnDefinition = "TEXT")
    private String uriOrValue;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
