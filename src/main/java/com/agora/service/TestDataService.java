package com.agora.service;

public interface TestDataService {
    /**
     * 生成測試數據，包括：
     * 1. 三個測試帳號（買家、賣家、外送員）
     * 2. 賣家的產品數據
     * 3. 買家的餘額
     * 4. 外送員的註冊資料
     */
    void generateTestData();

    void generateLogisticsOrder();

    void generatePlatformDeliveryOrder();

    void generateRechargeAndWithdraw();

    /**
     * 生成評價假數據
     */
    void generateReviewData();

    /**
     * 生成測試自動回復數據，包括：
     * 1. 測試用戶與自動回復機器人的對話記錄
     * 2. 自動回復反饋數據
     * 3. 不同場景的測試對話
     */
    void generateAutoReplyTestData();

    /**
     * 生成通知假數據，包括：
     * 1. 系統通知
     * 2. 促銷通知
     * 3. 評價通知
     * 4. 糾紛通知
     * 
     * 注意：訂單通知、配送通知、財務通知、安全通知會通過實際業務流程自動生成
     */
    void generateNotificationTestData();

    /**
     * 生成配送員接單測試數據，包括：
     * 1. 多個待接單的訂單
     * 2. 不同狀態的配送員
     * 3. 各種接單場景的測試數據
     */
    void generateDeliveryAcceptOrderTestData();

    /**
     * 生成退貨測試數據，包括：
     * 1. 各種退貨原因的測試訂單
     * 2. 賣家同意/拒絕退貨的場景
     * 3. 退貨物流流程測試
     * 4. 糾紛處理測試
     */
    void generateReturnProcessTestData();
} 