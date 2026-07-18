package com.agora.model.evidence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

@Entity
@Table(name = "exact_trade_fill_collection_run")
@Getter
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExactTradeFillCollectionRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name="run_id", nullable=false, updatable=false, length=64, columnDefinition="CHAR(64)") private String runId;
    @Column(nullable=false, updatable=false, length=32) private String provider;
    @Column(name="account_ref_hash", nullable=false, updatable=false, length=64, columnDefinition="CHAR(64)") private String accountRefHash;
    @Column(name="instrument_id", nullable=false, updatable=false, length=64) private String instrumentId;
    @Column(name="instrument_type", nullable=false, updatable=false, length=32) private String instrumentType;
    @Column(name="binding_scope_sha256", nullable=false, updatable=false, length=64, columnDefinition="CHAR(64)") private String bindingScopeSha256;
    @Column(nullable=false, updatable=false, length=32) private String status;
    @Column(name="started_at", nullable=false, updatable=false) private Instant startedAt;
    @Column(name="completed_at", nullable=false, updatable=false) private Instant completedAt;
    @Column(name="page_count", nullable=false, updatable=false) private int pageCount;
    @Column(name="fill_count", nullable=false, updatable=false) private int fillCount;
    @Column(name="terminal_cursor", updatable=false) private String terminalCursor;
    @Column(name="canonical_fill_set_sha256", nullable=false, updatable=false, length=64, columnDefinition="CHAR(64)") private String canonicalFillSetSha256;
    @Column(name="prior_stable_run_id", updatable=false, length=64, columnDefinition="CHAR(64)") private String priorStableRunId;
}
