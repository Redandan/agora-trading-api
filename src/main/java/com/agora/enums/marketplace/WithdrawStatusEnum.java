package com.agora.enums.marketplace;

public enum WithdrawStatusEnum {
    PENDING,         // 待處理
    PENDING_REVIEW,  // 風控人工審核中（由 WithdrawRiskService 標記）
    PROCESSING,      // 處理中（admin 已核可，正在鏈上發送）
    COMPLETED,       // 已完成
    CANCELLED,       // 已取消（用戶主動）
    FAILED,          // 失敗（鏈上失敗）
    REJECTED         // 已拒絕（admin 手動拒絕 + rejectedReason）
} 