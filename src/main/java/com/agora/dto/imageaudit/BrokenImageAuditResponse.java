package com.agora.dto.imageaudit;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Admin broken product/store image audit response")
public record BrokenImageAuditResponse(
        boolean dryRun,
        int productScanned,
        int storeScanned,
        int urlChecked,
        int okCount,
        int badCount,
        int transientFailureCount,
        List<BrokenImageFinding> findings
) {
}
