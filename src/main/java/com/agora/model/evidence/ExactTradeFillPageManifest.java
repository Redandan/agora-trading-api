package com.agora.model.evidence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

@Entity
@Table(name = "exact_trade_fill_page_manifest")
@Getter
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExactTradeFillPageManifest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name="run_id", nullable=false, updatable=false, length=64, columnDefinition="CHAR(64)") private String runId;
    @Column(name="page_index", nullable=false, updatable=false) private int pageIndex;
    @Column(name="request_cursor", updatable=false) private String requestCursor;
    @Column(name="next_cursor", updatable=false) private String nextCursor;
    @Column(name="page_key", nullable=false, updatable=false, length=64, columnDefinition="CHAR(64)") private String pageKey;
    @Column(name="page_sha256", nullable=false, updatable=false, length=64, columnDefinition="CHAR(64)") private String pageSha256;
    @Column(name="fill_count", nullable=false, updatable=false) private int fillCount;
    @Column(name="terminal_page", nullable=false, updatable=false) private boolean terminalPage;
    @Column(name="collected_at", nullable=false, updatable=false) private Instant collectedAt;
}
