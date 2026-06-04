package com.agora.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sanction_blacklist_address",
    uniqueConstraints = @UniqueConstraint(name = "uk_address_chain", columnNames = {"address", "chain"}))
@Schema(description = "制裁黑名單地址")
public class SanctionBlacklistAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    @Schema(description = "錢包地址", example = "T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb")
    private String address;

    @Column(nullable = false, length = 20)
    @Schema(description = "鏈名稱", example = "TRON", allowableValues = {"TRON", "ETH", "BSC"})
    private String chain;

    @Column(nullable = false, length = 50)
    @Schema(description = "來源", example = "OFAC", allowableValues = {"OFAC", "CHAINALYSIS", "INTERNAL", "USER_REPORT"})
    private String source;

    @Column(nullable = false, length = 20)
    @Schema(description = "嚴重程度 BLOCK=自動拒絕, WARN=人工審核", example = "BLOCK")
    private String severity = "BLOCK";

    @Column(length = 500)
    @Schema(description = "備註", nullable = true)
    private String reason;

    @Column(name = "added_by_admin_id")
    @Schema(description = "新增的 Admin 用戶 ID", nullable = true)
    private Long addedByAdminId;

    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt = LocalDateTime.now();
}
