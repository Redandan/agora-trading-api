package com.agora.service;

import com.agora.dto.pwa.PwaLogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * PWA 日誌服務
 * 使用內存 List 存儲，最多保留 1000 條日誌
 */
@Service
@Slf4j
public class PwaLogService {
    
    private static final int MAX_LOGS = 1000;
    
    // 內存存儲
    private final List<PwaLogEntry> logs = new ArrayList<>();
    private LocalDateTime sessionStartedAt;
    private String lastDeviceFingerprint; // 上次保存的設備指紋
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    /**
     * 保存日誌
     * 根據設備指紋自動判斷是否為新會話
     * 
     * @param deviceFingerprint 設備指紋（用於判斷是否為新會話）
     * @param ipAddress IP地址（用於記錄，可選）
     * @param logs 日誌列表
     */
    public void saveLogs(String deviceFingerprint, String ipAddress, 
                        List<PwaLogEntry> logs) {
        lock.writeLock().lock();
        try {
            // 根據設備指紋判斷是否為新會話
            boolean isNewSession = false;
            String oldDeviceFingerprint = lastDeviceFingerprint;
            
            if (deviceFingerprint != null) {
                // 如果設備指紋不同，說明是新會話
                if (lastDeviceFingerprint == null || !lastDeviceFingerprint.equals(deviceFingerprint)) {
                    isNewSession = true;
                    lastDeviceFingerprint = deviceFingerprint;
                }
            }
            
            // 如果是新會話，清除舊日誌
            if (isNewSession) {
                log.info("New session detected (device fingerprint changed), clearing old logs. Old: {}, New: {}", 
                    oldDeviceFingerprint, deviceFingerprint);
                this.logs.clear();
                this.sessionStartedAt = LocalDateTime.now();
            }
            
            // 如果會話開始時間為空，設置為當前時間
            if (this.sessionStartedAt == null) {
                this.sessionStartedAt = LocalDateTime.now();
            }
            
            // 直接添加日誌條目
            this.logs.addAll(logs);
            
            // 限制最大數量，保留最新的 1000 條
            if (this.logs.size() > MAX_LOGS) {
                log.warn("Log entries exceed max limit ({}), keeping only latest {} entries", 
                    this.logs.size(), MAX_LOGS);
                int removeCount = this.logs.size() - MAX_LOGS;
                this.logs.subList(0, removeCount).clear();
            }
            
            log.debug("Saved {} logs (total: {})", logs.size(), this.logs.size());
                
        } catch (Exception e) {
            log.error("Failed to save PWA logs", e);
            throw new RuntimeException("Failed to save logs", e);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 獲取當前所有日誌
     */
    public LogSessionInfo getAllLogs() {
        lock.readLock().lock();
        try {
            return new LogSessionInfo(
                sessionStartedAt != null ? sessionStartedAt : LocalDateTime.now(),
                LocalDateTime.now(),
                logs.size(),
                new ArrayList<>(logs) // 返回副本，避免外部修改
            );
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 日誌會話信息
     */
    public static class LogSessionInfo {
        private final LocalDateTime sessionStartedAt;
        private final LocalDateTime lastUpdated;
        private final int logCount;
        private final List<PwaLogEntry> logs;
        
        public LogSessionInfo(LocalDateTime sessionStartedAt, LocalDateTime lastUpdated, 
                             int logCount, List<PwaLogEntry> logs) {
            this.sessionStartedAt = sessionStartedAt;
            this.lastUpdated = lastUpdated;
            this.logCount = logCount;
            this.logs = logs;
        }
        
        public LocalDateTime getSessionStartedAt() {
            return sessionStartedAt;
        }
        
        public LocalDateTime getLastUpdated() {
            return lastUpdated;
        }
        
        public int getLogCount() {
            return logCount;
        }
        
        public List<PwaLogEntry> getLogs() {
            return logs;
        }
    }
}
