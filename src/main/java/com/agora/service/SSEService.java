package com.agora.service;

import com.agora.model.ChatMessage;
import com.agora.service.sse.model.ConnectionInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.agora.enums.system.NotifyEventTypeEnum;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class SSEService {

    private final com.agora.config.properties.SseProperties props;
    
    // 存儲 clientId -> ConnectionInfo 的映射（合併所有連接相關信息）
    private final Map<String, ConnectionInfo> connections = new ConcurrentHashMap<>();
    // 存儲 userId -> Set<clientId> 的映射（支援多點登入，保留用於快速查詢）
    private final Map<String, Set<String>> userClientMap = new ConcurrentHashMap<>();
    
    // 統計信息
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong totalDisconnections = new AtomicLong(0);
    private final AtomicLong totalMessagesSent = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);
    
    // 連接頻率限制：記錄每個 clientId 的最後連接時間
    private final Map<String, Long> lastConnectionTime = new ConcurrentHashMap<>();
    private static final long CONNECTION_RATE_LIMIT_MS = 5000; // 5秒內重複連接視為異常

    public SseEmitter createEmitter(String clientId, String userId) {
        log.debug("Creating SSE emitter for client: {} (user: {}), current connections: {}", 
                clientId, userId, connections.size());
        
        // 檢查是否已存在相同的clientId（理論上不應該發生，因為每個clientId都是唯一的）
        if (connections.containsKey(clientId)) {
            long currentTime = System.currentTimeMillis();
            Long lastTime = lastConnectionTime.get(clientId);
            
            if (lastTime != null && (currentTime - lastTime) < CONNECTION_RATE_LIMIT_MS) {
                log.warn("Client {} 在 {} 秒內重複連接，可能是前端連接管理問題。清理舊連接並建立新連接。", 
                    clientId, (currentTime - lastTime) / 1000.0);
            } else {
                log.debug("Client {} 已存在，清理舊連接（距離上次連接: {} 秒）", 
                    clientId, lastTime != null ? (currentTime - lastTime) / 1000.0 : "未知");
            }
            
            removeEmitter(clientId);
        }
        
        // 記錄連接時間
        lastConnectionTime.put(clientId, System.currentTimeMillis());
        
        // 檢查連接數限制
        if (connections.size() >= props.max().connections()) {
            log.warn("Maximum connections reached ({}), rejecting new connection from user: {}", props.max().connections(), userId);
            throw new RuntimeException("Maximum connections reached");
        }
        
        // 檢查用戶連接數限制（支援多點登入）
        Set<String> userClients = userClientMap.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet());
        if (userClients.size() >= props.max().perUser()) {
            log.warn("User {} has reached maximum connections ({}), rejecting new connection", userId, props.max().perUser());
            throw new RuntimeException("Maximum connections per user reached");
        }
        
        // 創建 SSE 發射器和連接信息
        SseEmitter emitter = new SseEmitter(props.connection().timeout());
        ConnectionInfo connectionInfo = new ConnectionInfo(emitter, userId);
        
        // 原子操作：存儲連接信息
        connections.put(clientId, connectionInfo);
        userClients.add(clientId);
        
        totalConnections.incrementAndGet();
        
        log.debug("SSE emitter created successfully for client: {} (user: {}), user now has {} connections", 
                clientId, userId, userClients.size());

        // 計算距離下次心跳的等待時間（使用新的心跳間隔）
        LocalDateTime now = LocalDateTime.now();
        long heartbeatIntervalSeconds = props.heartbeat().interval() / 1000;
        LocalDateTime nextHeartbeat = now.plusSeconds(heartbeatIntervalSeconds - (now.getSecond() % heartbeatIntervalSeconds));
        long waitSeconds = ChronoUnit.SECONDS.between(now, nextHeartbeat);

        // 發送初始等待時間信息
        try {
            Map<String, Object> initialData = new HashMap<>();
            initialData.put("type", NotifyEventTypeEnum.SYSTEM_TIME_WAIT);
            initialData.put("waitSeconds", waitSeconds);
            initialData.put("nextHeartbeatTime", nextHeartbeat.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            initialData.put("heartbeatInterval", heartbeatIntervalSeconds);
            initialData.put("clientId", clientId);
            initialData.put("timestamp", now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            
            emitter.send(SseEmitter.event()
                    .data(initialData)
                    .name(NotifyEventTypeEnum.SYSTEM_TIME_WAIT.name()));
            log.debug("Sent initial wait time info to client: {} (user: {}), wait seconds: {}", 
                    clientId, userId, waitSeconds);
        } catch (IOException e) {
            log.error("Error sending initial wait time info to client: {} (user: {})", clientId, userId, e);
            totalErrors.incrementAndGet();
            cleanupConnection(clientId, userId);
        }

        emitter.onCompletion(() -> {
            log.debug("SSE connection completed for client: {} (user: {})", clientId, userId);
            totalDisconnections.incrementAndGet();
            cleanupConnection(clientId, userId);
        });

        emitter.onTimeout(() -> {
            log.debug("SSE connection timed out for client: {} (user: {})", clientId, userId);
            totalDisconnections.incrementAndGet();
            cleanupConnection(clientId, userId);
        });

        emitter.onError(e -> {
            // Browser tab closes / network switches are normal SSE lifecycle events.
            if (isClientDisconnectError(e)) {
                log.debug("SSE client disconnected for client: {} (user: {}): {}",
                        clientId, userId, e.getMessage());
            } else {
                log.error("SSE connection error for client: {} (user: {})", clientId, userId, e);
            }
            totalErrors.incrementAndGet();
            totalDisconnections.incrementAndGet();
            cleanupConnection(clientId, userId);
        });

        return emitter;
    }

    /**
     * 清理連接相關的資源
     * 改善連接狀態管理，避免狀態不一致問題
     */
    private void cleanupConnection(String clientId, String userId) {
        log.debug("Starting cleanup for client: {} (user: {})", clientId, userId);
        
        // 使用同步塊確保清理操作的原子性
        synchronized (this) {
            // 先從用戶的客戶端集合中移除
            Set<String> userClients = userClientMap.get(userId);
            if (userClients != null) {
                boolean removedFromUser = userClients.remove(clientId);
                log.debug("Removed client {} from user {} clients: {}, remaining: {}", 
                        clientId, userId, removedFromUser, userClients.size());
                
                // 如果用戶沒有更多連接，清理用戶映射
                if (userClients.isEmpty()) {
                    userClientMap.remove(userId);
                    log.debug("Removed user {} from userClientMap (no more connections)", userId);
                }
            } else {
                // 這種情況在正常清理過程中可能發生，降低為調試級別
                log.debug("No userClients found for user: {} during cleanup of client: {}", userId, clientId);
            }
            
            // 清理連接信息
            ConnectionInfo connectionInfo = connections.get(clientId);
            if (connectionInfo != null && connectionInfo.getStatus().compareAndSet(true, false)) {
                // 正常清理流程
                boolean removed = connections.remove(clientId) != null;
                log.debug("Cleanup results for client: {} - removed: {}", clientId, removed);
                log.debug("Successfully cleaned up connection for client: {} (user: {})", clientId, userId);
            } else {
                // 強制清理，確保沒有遺留的映射
                connections.remove(clientId);
                log.debug("Force cleaned up connection for client: {} (user: {})", clientId, userId);
            }
            
            // 清理連接時間記錄
            lastConnectionTime.remove(clientId);
            
            // 更新統計信息
            totalDisconnections.incrementAndGet();
        }
    }

    /**
     * 檢查連接是否仍然有效
     * 改善連接狀態檢查邏輯
     */
    private boolean isConnectionValid(String clientId) {
        ConnectionInfo connectionInfo = connections.get(clientId);
        if (connectionInfo == null || !connectionInfo.isValid()) {
            return false;
        }
        
        // 檢查連接時間是否超時
        LocalDateTime connectedAt = connectionInfo.getConnectTime();
        if (connectedAt == null) {
            log.debug("No connection time found for client: {}, marking as invalid", clientId);
            return false;
        }
        
        long timeSinceConnection = ChronoUnit.MILLIS.between(connectedAt, LocalDateTime.now());
        if (timeSinceConnection > props.connection().timeout()) {
            log.debug("Connection timeout for client: {} ({}ms), marking as invalid", clientId, timeSinceConnection);
            return false;
        }
        
        return true;
    }
    
    /**
     * 移除無效的連接
     * 新增方法來主動清理無效連接
     */
    public void removeInvalidConnection(String clientId) {
        ConnectionInfo connectionInfo = connections.get(clientId);
        if (connectionInfo != null) {
            String userId = connectionInfo.getUserId();
            log.debug("Removing invalid connection for client: {} (user: {})", clientId, userId);
            cleanupConnection(clientId, userId);
        } else {
            log.warn("Attempted to remove invalid connection for client: {} but no connection info found", clientId);
            // 強制清理
            synchronized (this) {
                connections.remove(clientId);
            }
        }
    }

    /**
     * 安全地發送消息到指定的 emitter
     */
    private boolean sendMessageSafely(SseEmitter emitter, Object data, String eventName, String clientId, String userId) {
        try {
            emitter.send(SseEmitter.event()
                    .data(data)
                    .name(eventName));
            totalMessagesSent.incrementAndGet();
            return true;
        } catch (IOException e) {
            // 檢查是否為 Broken pipe 錯誤（客戶端斷開連接）
            if (isBrokenPipeError(e) || NotifyEventTypeEnum.HEARTBEAT.name().equals(eventName)) {
                log.debug("Client disconnected while sending SSE event to client: {} (user: {}), event: {}",
                        clientId, userId, eventName);
            } else {
                log.error("IO error sending SSE event to client: {} (user: {}), event: {}", 
                        clientId, userId, eventName, e);
            }
            totalErrors.incrementAndGet();
            cleanupConnection(clientId, userId);
            return false;
        } catch (IllegalStateException e) {
            // Handle cases where the emitter is in an invalid state
            log.warn("Emitter in invalid state for client: {} (user: {}), event: {}, cleaning up connection", 
                    clientId, userId, eventName);
            totalErrors.incrementAndGet();
            cleanupConnection(clientId, userId);
            return false;
        } catch (Exception e) {
            // Catch any other unexpected exceptions
            log.error("Unexpected error sending SSE event to client: {} (user: {}), event: {}", 
                    clientId, userId, eventName, e);
            totalErrors.incrementAndGet();
            cleanupConnection(clientId, userId);
            return false;
        }
    }

    /**
     * 檢查是否為 Broken pipe 錯誤
     */
    private boolean isBrokenPipeError(IOException e) {
        String message = e.getMessage();
        return message != null && (
                message.contains("Broken pipe") ||
                message.contains("Connection reset by peer") ||
                message.contains("Connection timed out") ||
                message.contains("Connection refused") ||
                message.contains("An established connection was aborted") ||
                message.contains("An existing connection was forcibly closed") ||
                message.contains("Connection closed")
        );
    }

    private boolean isClientDisconnectError(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof IOException && isBrokenPipeError((IOException) current)) {
                return true;
            }
            String className = current.getClass().getName();
            String message = current.getMessage();
            if (className.contains("AsyncRequestNotUsableException")) {
                return true;
            }
            if (message != null && (
                    message.contains("disconnected client") ||
                    message.contains("Broken pipe") ||
                    message.contains("Connection reset by peer") ||
                    message.contains("An established connection was aborted") ||
                    message.contains("An existing connection was forcibly closed") ||
                    message.contains("Connection closed"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /*
     * 如果 Data 是 Map 類型，則會將 type 作為 eventName
     * String 類型，則會將 message 作為 eventName
     */
    public void sendEventToUser(String userId, Object data) {
        Set<String> userClients = userClientMap.get(userId);
        
        // 添加詳細的調試日誌
        log.debug("Attempting to send event to user: {}, userClients: {}, totalConnections: {}", 
                userId, userClients != null ? userClients.size() : 0, connections.size());
        
        if (userClients != null && !userClients.isEmpty()) {
            // 檢查每個連接的實際狀態
            int validConnections = 0;
            for (String clientId : userClients) {
                boolean isValid = isConnectionValid(clientId);
                boolean hasConnection = connections.containsKey(clientId);
                log.debug("Client {} for user {}: isValid={}, hasConnection={}", 
                        clientId, userId, isValid, hasConnection);
                if (isValid && hasConnection) {
                    validConnections++;
                }
            }
            
            log.debug("User {} has {} total clients, {} valid connections", 
                    userId, userClients.size(), validConnections);
            
            String eventName = "message";
            if (data instanceof Map) {
                Map<?, ?> mapData = (Map<?, ?>) data;
                if (mapData.containsKey("type")) {
                    eventName = mapData.get("type").toString();
                }
            } else if (data instanceof ChatMessage) {
                eventName = "CHAT_MESSAGE";
            }

            // 發送給用戶的所有連接
            final String finalEventName = eventName;
            int removedCount = 0;
            userClients.removeIf(clientId -> {
                if (!isConnectionValid(clientId)) {
                    log.debug("Removing invalid connection: {} for user: {}", clientId, userId);
                    return true; // 移除無效連接
                }
                ConnectionInfo connectionInfo = connections.get(clientId);
                if (connectionInfo != null && connectionInfo.getEmitter() != null) {
                    boolean success = sendMessageSafely(connectionInfo.getEmitter(), data, finalEventName, clientId, userId);
                    if (!success) {
                        log.debug("Failed to send message to client: {} for user: {}", clientId, userId);
                    }
                    return !success;
                }
                log.debug("No connection info or emitter found for client: {} for user: {}", clientId, userId);
                return true; // 移除無效連接
            });
            
            log.debug("Message sent to user: {} across {} connections, event type: {}, removed: {}", 
                    userId, userClients.size(), eventName, removedCount);
        } else {
            // 只有在調試模式下才記錄詳細信息，避免生產環境的噪音
            if (log.isDebugEnabled()) {
                log.debug("No active connection found for user: {} (userClients: {}, totalConnections: {})", 
                        userId, userClients != null ? userClients.size() : 0, connections.size());
                log.debug("Current active users: {}", userClientMap.keySet());
                log.debug("Current active clients: {}", connections.keySet());
            }
        }
    }

    public void broadcast(Object data) {
        // 使用迭代器來避免 ConcurrentModificationException
        connections.entrySet().removeIf(entry -> {
            String clientId = entry.getKey();
            ConnectionInfo connectionInfo = entry.getValue();
            
            if (!isConnectionValid(clientId)) {
                log.debug("Removing invalid connection during cleanup: {}", clientId);
                return true;
            }
            
            String userId = connectionInfo.getUserId();
            return !sendMessageSafely(connectionInfo.getEmitter(), data, "message", clientId, userId);
        });
    }


    public void removeEmitter(String clientId) {
        ConnectionInfo connectionInfo = connections.get(clientId);
        if (connectionInfo != null) {
            String userId = connectionInfo.getUserId();
            log.debug("Removing SSE emitter for client: {} (user: {})", clientId, userId);
            cleanupConnection(clientId, userId);
        } else {
            log.warn("Attempted to remove emitter {} but no connection info found", clientId);
            // 強制清理孤立的連接
            connections.remove(clientId);
        }
    }

    @Scheduled(fixedRate = 30000) // 每30秒執行一次
    public void broadcastSystemTime() {
        Map<String, Object> timeData = new HashMap<>();
        timeData.put("type", NotifyEventTypeEnum.SYSTEM_TIME);
        timeData.put("time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        
        int activeConnections = connections.size();
        if (activeConnections > 0) {
            broadcast(timeData);
            log.debug("Broadcasted system time to {} active clients", activeConnections);
        } else {
            log.debug("No active connections to broadcast system time");
        }
    }

    @Scheduled(fixedRate = 30000) // 每30秒執行一次
    public void cleanupInvalidConnections() {
        int beforeCount = connections.size();
        
        log.debug("Starting scheduled cleanup, current connections: {}", beforeCount);
        
        // 清理無效連接
        connections.entrySet().removeIf(entry -> {
            String clientId = entry.getKey();
            if (!isConnectionValid(clientId)) {
                ConnectionInfo connectionInfo = entry.getValue();
                String userId = connectionInfo != null ? connectionInfo.getUserId() : null;
                log.debug("Removing invalid connection during cleanup: {} (user: {})", clientId, userId);
                if (userId != null) {
                    cleanupConnection(clientId, userId);
                }
                return true;
            }
            return false;
        });
        
        // 檢查和修復狀態不一致
        fixConnectionStateInconsistencies();
        
        int afterCount = connections.size();
        if (beforeCount != afterCount) {
            log.debug("Cleaned up {} invalid connections. Active connections: {} -> {}", 
                    beforeCount - afterCount, beforeCount, afterCount);
        }
    }
    
    /**
     * 檢查和修復連接狀態不一致的問題
     */
    private void fixConnectionStateInconsistencies() {
        log.debug("Checking for connection state inconsistencies...");
        
        // 檢查 userClientMap 中的連接是否在 connections 中存在
        userClientMap.forEach((userId, clientIds) -> {
            clientIds.removeIf(clientId -> {
                if (!connections.containsKey(clientId)) {
                    log.debug("Found orphaned client {} in userClientMap for user {}, removing", clientId, userId);
                    return true;
                }
                return false;
            });
            
            // 如果用戶沒有有效的連接，清理用戶映射
            if (clientIds.isEmpty()) {
                userClientMap.remove(userId);
                log.debug("Removed empty user mapping for user: {}", userId);
            }
        });
        
        // 檢查 connections 中的連接是否在 userClientMap 中存在
        connections.keySet().forEach(clientId -> {
            ConnectionInfo connectionInfo = connections.get(clientId);
            if (connectionInfo == null) {
                log.warn("Found orphaned connection {} without connection info, removing", clientId);
                connections.remove(clientId);
            } else {
                String userId = connectionInfo.getUserId();
                Set<String> userClients = userClientMap.get(userId);
                if (userClients == null || !userClients.contains(clientId)) {
                    log.warn("Found connection {} for user {} not in userClientMap, fixing", clientId, userId);
                    userClientMap.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(clientId);
                }
            }
        });
        
        log.debug("Connection state consistency check completed");
    }

    /**
     * 獲取當前活躍連接數
     */
    public int getActiveConnectionCount() {
        return connections.size();
    }

    /**
     * 獲取指定用戶的連接狀態
     */
    public boolean isUserConnected(String userId) {
        Set<String> userClients = userClientMap.get(userId);
        return userClients != null && !userClients.isEmpty() && 
               userClients.stream().anyMatch(this::isConnectionValid);
    }
    
    /**
     * 根據clientId獲取userId
     */
    public String getUserIdByClientId(String clientId) {
        ConnectionInfo connectionInfo = connections.get(clientId);
        return connectionInfo != null ? connectionInfo.getUserId() : null;
    }

    /**
     * 獲取指定用戶的連接數量
     */
    public int getUserConnectionCount(String userId) {
        Set<String> userClients = userClientMap.get(userId);
        if (userClients == null) {
            return 0;
        }
        return (int) userClients.stream().filter(this::isConnectionValid).count();
    }

    /**
     * 獲取詳細的統計信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("activeConnections", getActiveConnectionCount());
        stats.put("totalConnections", totalConnections.get());
        stats.put("totalDisconnections", totalDisconnections.get());
        stats.put("totalMessagesSent", totalMessagesSent.get());
        stats.put("totalErrors", totalErrors.get());
        stats.put("maxConnections", props.max().connections());
        stats.put("maxConnectionsPerUser", props.max().perUser());
        stats.put("activeUsers", userClientMap.size());
        stats.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        
        // 計算連接時間統計
        if (!connections.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            long totalConnectionTime = connections.values().stream()
                    .filter(info -> info.getConnectTime() != null)
                    .mapToLong(info -> ChronoUnit.SECONDS.between(info.getConnectTime(), now))
                    .sum();
            stats.put("averageConnectionTime", totalConnectionTime / connections.size());
        }
        
        return stats;
    }

    /**
     * 獲取指定用戶的連接信息
     */
    public Map<String, Object> getUserConnectionInfo(String userId) {
        Map<String, Object> info = new HashMap<>();
        Set<String> userClients = userClientMap.get(userId);
        
        if (userClients != null && !userClients.isEmpty()) {
            info.put("connected", true);
            info.put("connectionCount", getUserConnectionCount(userId));
            info.put("clientIds", userClients);
            
            // 獲取連接時間信息
            Map<String, Object> connectionTimesInfo = new HashMap<>();
            userClients.forEach(clientId -> {
                ConnectionInfo connectionInfo = connections.get(clientId);
                if (connectionInfo != null && connectionInfo.getConnectTime() != null) {
                    Map<String, Object> clientInfo = new HashMap<>();
                    clientInfo.put("connectTime", connectionInfo.getConnectTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    clientInfo.put("connectionDuration", ChronoUnit.SECONDS.between(connectionInfo.getConnectTime(), LocalDateTime.now()));
                    clientInfo.put("valid", isConnectionValid(clientId));
                    connectionTimesInfo.put(clientId, clientInfo);
                }
            });
            info.put("connectionDetails", connectionTimesInfo);
        } else {
            info.put("connected", false);
            info.put("connectionCount", 0);
        }
        
        info.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return info;
    }

    /**
     * 強制斷開指定用戶的所有連接
     */
    public void forceDisconnectUser(String userId) {
        Set<String> userClients = userClientMap.get(userId);
        if (userClients != null) {
            log.warn("Force disconnecting all {} connections for user: {}", userClients.size(), userId);
            userClients.forEach(clientId -> cleanupConnection(clientId, userId));
        }
    }

    /**
     * 強制斷開所有連接（緊急情況使用）
     */
    public void forceDisconnectAll() {
        log.warn("Force disconnecting all {} connections", connections.size());
        connections.keySet().forEach(clientId -> {
            ConnectionInfo connectionInfo = connections.get(clientId);
            if (connectionInfo != null) {
                String userId = connectionInfo.getUserId();
                cleanupConnection(clientId, userId);
            }
        });
    }

    
    /**
     * 定時發送心跳包，避免代理或瀏覽器斷開連線
     */
    @Scheduled(fixedRateString = "${sse.heartbeat.interval:30000}")
    public void sendHeartbeats() {
        connections.forEach((clientId, connectionInfo) -> {
            String userId = connectionInfo.getUserId();
            if (userId != null && isConnectionValid(clientId)) {
                Map<String, Object> heartbeat = new HashMap<>();
                heartbeat.put("type", NotifyEventTypeEnum.HEARTBEAT);
                heartbeat.put("clientId", clientId);
                heartbeat.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                
                // 添加用戶的 callId 到心跳中
                String callId = connectionInfo.getCallId();
                if (callId != null && !callId.trim().isEmpty()) {
                    heartbeat.put("callId", callId);
                    heartbeat.put("callState", "incoming"); // 默認狀態為來電
                } else {
                    heartbeat.put("callState", "idle"); // 無通話時為空閒狀態
                }

                sendMessageSafely(connectionInfo.getEmitter(), heartbeat, NotifyEventTypeEnum.HEARTBEAT.name(), clientId, userId);
                
                // 記錄心跳包日誌
                log.debug("[HEARTBEAT] User {}: callId={}, state={}", userId, callId, 
                    callId != null && !callId.trim().isEmpty() ? "incoming" : "idle");
            }
        });
    }

    /**
     * 定期清理超時或無效的連線
     */
    @Scheduled(fixedRateString = "${sse.cleanup.interval:60000}")
    public void cleanupStaleConnections() {
        LocalDateTime now = LocalDateTime.now();
        connections.keySet().removeIf(clientId -> {
            ConnectionInfo connectionInfo = connections.get(clientId);
            if (connectionInfo == null || connectionInfo.getConnectTime() == null) {
                log.debug("No connection info or time found for client: {}, cleaning up", clientId);
                if (connectionInfo != null) {
                    cleanupConnection(clientId, connectionInfo.getUserId());
                }
                return true;
            }

            boolean expired = ChronoUnit.MILLIS.between(connectionInfo.getConnectTime(), now) > props.connection().timeout();
            if (expired) {
                String userId = connectionInfo.getUserId();
                log.debug("Cleaning up stale connection for client: {} (user: {})", clientId, userId);
                cleanupConnection(clientId, userId);
                return true;
            }
            return false;
        });
    }
    
    /**
     * 獲取用戶的 callId
     */
    public String getUserCallId(String userId) {
        Set<String> userClients = userClientMap.get(userId);
        if (userClients != null) {
            for (String clientId : userClients) {
                ConnectionInfo connectionInfo = connections.get(clientId);
                if (connectionInfo != null && connectionInfo.getCallId() != null) {
                    return connectionInfo.getCallId();
                }
            }
        }
        return null;
    }
    
    /**
     * 設置用戶的 callId
     */
    public void setUserCallId(String userId, String callId) {
        Set<String> userClients = userClientMap.get(userId);
        if (userClients != null) {
            userClients.forEach(clientId -> {
                ConnectionInfo connectionInfo = connections.get(clientId);
                if (connectionInfo != null) {
                    String oldCallId = connectionInfo.getCallId();
                    connectionInfo.setCallId(callId);
                    
                    // 記錄狀態變更日誌
                    log.debug("[CALL_STATE] User {}: {} -> incoming, callId={}", 
                        userId, oldCallId != null ? "idle" : "null", callId);
                }
            });
        }
    }
    
    /**
     * 清除用戶的 callId
     */
    public void clearUserCallId(String userId) {
        Set<String> userClients = userClientMap.get(userId);
        if (userClients != null) {
            userClients.forEach(clientId -> {
                ConnectionInfo connectionInfo = connections.get(clientId);
                if (connectionInfo != null) {
                    String oldCallId = connectionInfo.getCallId();
                    connectionInfo.clearCallId();
                    
                    // 記錄狀態變更日誌
                    log.debug("[CALL_STATE] User {}: {} -> idle, callId=null",
                        userId, oldCallId != null ? "incoming" : "null");
                }
            });
        }
    }
    
    /**
     * 獲取連接統計信息
     * @return 連接統計信息
     */
    public Map<String, Object> getConnectionStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalConnections", connections.size());
        stats.put("totalUsers", userClientMap.size());
        stats.put("totalMessagesSent", totalMessagesSent.get());
        stats.put("totalErrors", totalErrors.get());
        stats.put("totalDisconnections", totalDisconnections.get());
        
        // 計算每個用戶的連接數
        Map<String, Integer> userConnectionCounts = new HashMap<>();
        userClientMap.forEach((userId, clients) -> {
            int validConnections = 0;
            for (String clientId : clients) {
                if (isConnectionValid(clientId)) {
                    validConnections++;
                }
            }
            userConnectionCounts.put(userId, validConnections);
        });
        stats.put("userConnectionCounts", userConnectionCounts);
        
        return stats;
    }
    
    /**
     * 檢查連接健康狀態
     * @return 健康狀態報告
     */
    public Map<String, Object> getHealthReport() {
        Map<String, Object> report = new HashMap<>();
        
        int totalConnections = connections.size();
        int totalUsers = userClientMap.size();
        int validConnections = 0;
        int invalidConnections = 0;
        
        for (String clientId : connections.keySet()) {
            if (isConnectionValid(clientId)) {
                validConnections++;
            } else {
                invalidConnections++;
            }
        }
        
        report.put("totalConnections", totalConnections);
        report.put("validConnections", validConnections);
        report.put("invalidConnections", invalidConnections);
        report.put("totalUsers", totalUsers);
        report.put("healthStatus", invalidConnections == 0 ? "HEALTHY" : "NEEDS_CLEANUP");
        
        return report;
    }
}
