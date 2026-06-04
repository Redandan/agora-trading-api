package com.agora.dto.imageaudit;

import com.agora.enums.marketplace.ProductStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Admin broken product/store image audit request")
public class BrokenImageAuditRequest {

    @Schema(description = "Maximum products to scan", example = "100")
    private Integer limit = 100;

    @Schema(description = "Optional product status filter", enumAsRef = true)
    private ProductStatusEnum status;

    @Schema(description = "Optional seller/store owner id filter")
    private Long sellerId;

    @Schema(description = "Only scan records not updated in the last N days")
    private Integer olderThanDays;

    @Schema(description = "Also scan store logo/cover image URLs", example = "true")
    private Boolean includeStores = true;

    @Schema(description = "Dry-run by default. Cleanup requires dryRun=false and confirmCleanup.", example = "true")
    private Boolean dryRun = true;

    @Schema(description = "Required for cleanup writes when dryRun=false. Must equal REMOVE_BROKEN_IMAGE_URLS.")
    private String confirmCleanup;

    @Schema(description = "HTTP probe timeout in milliseconds", example = "3000")
    private Integer timeoutMillis = 3000;
}
