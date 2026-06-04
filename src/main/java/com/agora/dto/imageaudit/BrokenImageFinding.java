package com.agora.dto.imageaudit;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Broken product/store image finding")
public record BrokenImageFinding(
        String objectType,
        Long objectId,
        Long ownerId,
        String title,
        String sourceField,
        String url,
        Integer httpStatus,
        String errorCode,
        String recommendedAction
) {
}
