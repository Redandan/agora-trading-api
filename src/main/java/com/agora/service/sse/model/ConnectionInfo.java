package com.agora.service.sse.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 連接信息封裝類
 * 包含與 clientId 相關的所有信息
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ConnectionInfo {
    
    /**
     * SSE 發射器
     */
    private SseEmitter emitter;
    
    /**
     * 用戶 ID
     */
    private String userId;
    
    /**
     * 連接狀態
     */
    private AtomicBoolean status;
    
    /**
     * 連接時間
     */
    private LocalDateTime connectTime;
    
    /**
     * 通話 ID，用於 WebRTC
     */
    private String callId;
    
    /**
     * 構造函數 - 創建新連接
     * 
     * @param emitter SSE 發射器
     * @param userId 用戶 ID
     */
    public ConnectionInfo(SseEmitter emitter, String userId) {
        this.emitter = emitter;
        this.userId = userId;
        this.status = new AtomicBoolean(true);
        this.connectTime = LocalDateTime.now();
        this.callId = null;
    }
    
    /**
     * 檢查連接是否有效
     * 
     * @return true 如果連接有效
     */
    public boolean isValid() {
        return status != null && status.get() && emitter != null;
    }
    
    /**
     * 設置連接為無效狀態
     */
    public void setInvalid() {
        if (status != null) {
            status.set(false);
        }
    }
    
    /**
     * 設置通話 ID
     * 
     * @param callId 通話 ID
     */
    public void setCallId(String callId) {
        this.callId = callId;
    }
    
    /**
     * 清除通話 ID
     */
    public void clearCallId() {
        this.callId = null;
    }
    
    /**
     * 檢查是否有進行中的通話
     * 
     * @return true 如果有通話
     */
    public boolean hasActiveCall() {
        return callId != null && !callId.trim().isEmpty();
    }
}
