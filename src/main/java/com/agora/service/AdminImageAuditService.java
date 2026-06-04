package com.agora.service;

import com.agora.dto.imageaudit.BrokenImageAuditRequest;
import com.agora.dto.imageaudit.BrokenImageAuditResponse;
import com.agora.dto.imageaudit.BrokenImageCleanupResponse;

public interface AdminImageAuditService {
    BrokenImageAuditResponse auditProductAndStoreImages(BrokenImageAuditRequest request);

    BrokenImageCleanupResponse cleanupProductAndStoreImages(BrokenImageAuditRequest request);
}
