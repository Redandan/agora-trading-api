package com.agora.enums.system;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "通知事件類型")
public enum NotifyEventTypeEnum {
    // === 現有事件 ===
    @Schema(description = "新訂單")
    NEW_DELIVERY_ORDER,
    @Schema(description = "訂單狀態更新")
    DELIVERY_ORDER_STATUS_UPDATE,
    @Schema(description = "訂單取消")
    DELIVERY_ORDER_CANCEL,
    @Schema(description = "訂單完成")
    DELIVERY_ORDER_COMPLETED,
    @Schema(description = "訂單失敗")
    DELIVERY_ORDER_FAILED,
    @Schema(description = "訂單配送中")
    DELIVERY_ORDER_DELIVERED,
    @Schema(description = "訂單取貨中")
    DELIVERY_ORDER_PICKED_UP,
    @Schema(description = "系統時間推送")
    SYSTEM_TIME,
    @Schema(description = "系統時間等待時間")
    SYSTEM_TIME_WAIT,
    
    // === 財務相關事件 ===
    @Schema(description = "餘額增加")
    BALANCE_INCREASED,
    @Schema(description = "餘額減少")
    BALANCE_DECREASED,
    @Schema(description = "充值成功")
    RECHARGE_SUCCESS,
    @Schema(description = "提款成功")
    WITHDRAW_SUCCESS,
    @Schema(description = "交易完成")
    TRANSACTION_COMPLETED,
    @Schema(description = "交易失敗")
    TRANSACTION_FAILED,
    
    // === 聊天相關事件 ===
    @Schema(description = "新訊息")
    NEW_MESSAGE,
    @Schema(description = "訊息已讀")
    MESSAGE_READ,
    @Schema(description = "訊息已發送")
    MESSAGE_SENT,
    @Schema(description = "用戶開始輸入")
    USER_TYPING_START,
    @Schema(description = "用戶停止輸入")
    USER_TYPING_STOP,
    @Schema(description = "用戶正在輸入")
    USER_TYPING_ACTIVE,
    
    // === 訂單相關事件 ===
    @Schema(description = "新訂單創建")
    ORDER_CREATED,
    @Schema(description = "訂單狀態變更")
    ORDER_STATUS_CHANGED,
    @Schema(description = "訂單已出貨")
    ORDER_SHIPPED,
    @Schema(description = "訂單配送中")
    ORDER_DELIVERING,
    @Schema(description = "訂單已送達")
    ORDER_DELIVERED,
    @Schema(description = "訂單完成")
    ORDER_COMPLETED,
    @Schema(description = "訂單取消")
    ORDER_CANCELLED,
    @Schema(description = "訂單爭議")
    ORDER_DISPUTED,
    @Schema(description = "退款申請")
    REFUND_REQUESTED,
    @Schema(description = "退款完成")
    REFUND_COMPLETED,
    
    // === 用戶認證相關事件 ===
    @Schema(description = "用戶登入")
    USER_LOGIN,
    @Schema(description = "用戶登出")
    USER_LOGOUT,
    @Schema(description = "登入異常")
    LOGIN_ANOMALY,
    @Schema(description = "Token即將過期")
    TOKEN_EXPIRING_SOON,
    @Schema(description = "Token已過期")
    TOKEN_EXPIRED,
    @Schema(description = "密碼重設")
    PASSWORD_RESET,
    @Schema(description = "雙因素認證")
    TWO_FACTOR_AUTH_REQUIRED,
    
    // === 系統安全事件 ===
    @Schema(description = "異常登入嘗試")
    SUSPICIOUS_LOGIN_ATTEMPT,
    @Schema(description = "帳戶被鎖定")
    ACCOUNT_LOCKED,
    @Schema(description = "安全警告")
    SECURITY_WARNING,
    
    // === 商品相關事件 ===
    @Schema(description = "商品庫存不足")
    PRODUCT_LOW_STOCK,
    @Schema(description = "商品價格變動")
    PRODUCT_PRICE_CHANGED,
    @Schema(description = "商品下架")
    PRODUCT_DISCONTINUED,
    @Schema(description = "商品上架")
    PRODUCT_LISTED,
    
    // === 配送相關事件 ===
    @Schema(description = "配送員接單")
    DELIVERY_ACCEPTED,
    @Schema(description = "配送員取貨")
    DELIVERY_PICKED_UP,
    @Schema(description = "配送員送達")
    DELIVERY_DELIVERED,
    @Schema(description = "配送異常")
    DELIVERY_EXCEPTION,
    
    // === 通知相關事件 ===
    @Schema(description = "新通知")
    NOTIFICATION_RECEIVED,
    @Schema(description = "通知已讀")
    NOTIFICATION_READ,
    @Schema(description = "緊急通知")
    URGENT_NOTIFICATION,
    
    // === 購物車相關事件 ===
    @Schema(description = "商品添加到購物車")
    CART_ITEM_ADDED,
    @Schema(description = "購物車商品移除")
    CART_ITEM_REMOVED,
    @Schema(description = "購物車商品數量更新")
    CART_ITEM_UPDATED,
    @Schema(description = "購物車清空")
    CART_CLEARED,
    @Schema(description = "購物車結帳完成")
    CART_CHECKOUT_COMPLETED,
    
    // === 用戶資料相關事件 ===
    @Schema(description = "頭像更新成功")
    AVATAR_UPDATED,
    @Schema(description = "個人資料更新")
    PROFILE_UPDATED,

    // === 商店相關事件 ===
    @Schema(description = "商店Logo更新成功")
    STORE_LOGO_UPDATED,
    @Schema(description = "商店封面圖片更新成功")
    STORE_COVER_UPDATED,
    @Schema(description = "商店Logo移除成功")
    STORE_LOGO_REMOVED,
    @Schema(description = "商店封面圖片移除成功")
    STORE_COVER_REMOVED,
    @Schema(description = "商店信息更新")
    STORE_INFO_UPDATED,
    @Schema(description = "商店創建成功")
    STORE_CREATED,
    @Schema(description = "商店狀態變更")
    STORE_STATUS_CHANGED,
    @Schema(description = "商店物流設定更新")
    STORE_SHIPPING_CONFIG_UPDATED,

    // === 系統維護事件 ===
    @Schema(description = "系統維護開始")
    SYSTEM_MAINTENANCE_START,
    @Schema(description = "系統維護結束")
    SYSTEM_MAINTENANCE_END,
    @Schema(description = "系統異常")
    SYSTEM_ERROR,
    @Schema(description = "心跳")
    HEARTBEAT,
    
    // === WebRTC 相關事件 ===
    @Schema(description = "WebRTC Offer 信令")
    WEBRTC_OFFER,
    @Schema(description = "WebRTC Answer 信令")
    WEBRTC_ANSWER,
    @Schema(description = "WebRTC ICE Candidate 信令")
    WEBRTC_ICE_CANDIDATE,
    @Schema(description = "WebRTC 通話發起")
    WEBRTC_CALL_INITIATED,
    @Schema(description = "WebRTC 通話接受")
    WEBRTC_CALL_ACCEPTED,
    @Schema(description = "WebRTC 通話拒絕")
    WEBRTC_CALL_REJECTED,
    @Schema(description = "WebRTC 通話結束")
    WEBRTC_CALL_ENDED,
    @Schema(description = "WebRTC 通話失敗")
    WEBRTC_CALL_FAILED,
    @Schema(description = "WebRTC 連線狀態變更")
    WEBRTC_CONNECTION_STATE_CHANGED,
    @Schema(description = "WebRTC 通話響鈴")
    WEBRTC_CALL_RINGING,
    @Schema(description = "WebRTC 通話已連接")
    WEBRTC_CALL_CONNECTED
}
