package com.agora.model.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

import java.time.LocalDateTime;

/** Shared immutable provenance columns for append-only execution evidence. */
@Getter
@MappedSuperclass
public abstract class AppendOnlyEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dedupe_key", nullable = false, length = 64, columnDefinition = "CHAR(64)", updatable = false)
    private String dedupeKey;

    @Column(nullable = false, length = 32, updatable = false)
    private String provider;

    @Column(name = "event_at", nullable = false, updatable = false)
    private LocalDateTime eventAt;

    @Column(name = "provider_at", nullable = false, updatable = false)
    private LocalDateTime providerAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Column(name = "ingested_at", nullable = false, updatable = false)
    private LocalDateTime ingestedAt;

    @Column(name = "source_mode", nullable = false, length = 24, updatable = false)
    private String sourceMode;

    @Column(name = "raw_payload_sha256", nullable = false, length = 64, columnDefinition = "CHAR(64)", updatable = false)
    private String rawPayloadSha256;

    @Column(name = "provider_cursor", length = 255, updatable = false)
    private String providerCursor;

    @Column(name = "provider_page_key", length = 128, updatable = false)
    private String providerPageKey;

    @Column(name = "gap_manifest_id", length = 64, columnDefinition = "CHAR(64)", updatable = false)
    private String gapManifestId;

    @Column(name = "gap_dataset", length = 64, updatable = false)
    private String gapDataset;

    @Column(name = "gap_range_start", updatable = false)
    private LocalDateTime gapRangeStart;

    @Column(name = "gap_range_end", updatable = false)
    private LocalDateTime gapRangeEnd;

    @Column(name = "retention_class", nullable = false, length = 32, updatable = false)
    private String retentionClass;

    @Column(name = "retain_until", updatable = false)
    private LocalDateTime retainUntil;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private LocalDateTime createdAt;
}
