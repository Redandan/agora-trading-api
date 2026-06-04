package com.agora.dto.imageaudit;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Admin broken product/store image cleanup response")
public record BrokenImageCleanupResponse(
        boolean dryRun,
        boolean cleanupPerformed,
        int productScanned,
        int storeScanned,
        int urlChecked,
        int okCount,
        int badCount,
        int transientFailureCount,
        int productUpdated,
        int storeUpdated,
        int removedUrlCount,
        BrokenImageAuditResponse audit
) {
}
