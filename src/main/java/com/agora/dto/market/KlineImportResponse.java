package com.agora.dto.market;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class KlineImportResponse {
    private int importedCount;
    private int skippedCount;
    private long durationMs;
}
