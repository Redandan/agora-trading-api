package com.agora.model;

import com.agora.enums.marketplace.ProductCategoryEnum;
import com.agora.enums.marketplace.ProductTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ai_product_classification_suggestions")
@Schema(description = "AI 商品分類建議審計記錄。只有 admin 明確套用才會修改商品。")
public class AiProductClassificationSuggestion {

    public enum Status {
        PENDING,
        APPLIED,
        IGNORED,
        OVERRIDDEN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "seller_id")
    private Long sellerId;

    @Column(name = "input_snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String inputSnapshotJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "suggested_category", length = 50)
    private ProductCategoryEnum suggestedCategory;

    @Column(name = "suggested_category_confidence", precision = 5, scale = 4)
    private BigDecimal suggestedCategoryConfidence;

    @Column(name = "alternative_categories_json", columnDefinition = "TEXT")
    private String alternativeCategoriesJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "suggested_product_type", length = 30)
    private ProductTypeEnum suggestedProductType;

    @Column(name = "suggested_product_type_confidence", precision = 5, scale = 4)
    private BigDecimal suggestedProductTypeConfidence;

    @Column(name = "suggested_tags_json", columnDefinition = "TEXT")
    private String suggestedTagsJson;

    @Column(name = "suggested_source_region", length = 10)
    private String suggestedSourceRegion;

    @Column(name = "suggested_source_platform", length = 100)
    private String suggestedSourcePlatform;

    @Column(name = "model_provider", length = 40)
    private String modelProvider;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "prompt_version", length = 40)
    private String promptVersion;

    @Column(name = "classifier_version", nullable = false, length = 40)
    private String classifierVersion = "server-classifier-v1";

    @Column(name = "raw_response_json", columnDefinition = "TEXT")
    private String rawResponseJson;

    @Column(name = "normalized_output_json", nullable = false, columnDefinition = "TEXT")
    private String normalizedOutputJson;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private Status status = Status.PENDING;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewer_notes", columnDefinition = "TEXT")
    private String reviewerNotes;

    @Column(name = "override_reason", columnDefinition = "TEXT")
    private String overrideReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;
}
